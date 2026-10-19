"""At-least-once public events with stable receiver deduplication keys."""

import json
from urllib.parse import urlsplit

from spunto.host import Ctx, Plugin
from spunto.host.plugin import ContextError
from spunto.net.fetch import FetchError
from spunto.plugins._capture import identity

plugin = Plugin("webhooks")


def destinations(ctx):
    values = ctx.settings.get("urls", [])
    if not isinstance(values, list) or len(values) > 10:
        return []
    result = []
    for value in values:
        if not isinstance(value, str) or len(value) > 2048:
            return []
        parsed = urlsplit(value)
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username
            or parsed.password
            or parsed.fragment
        ):
            return []
        if value not in result:
            result.append(value)
    return result


async def payload(
    ctx, note_id, event, expected_version, change_seq, private=None, machine=None, origin=None
):
    if event == "note.saved":
        note = await ctx.capture.note(note_id, note_version=expected_version)
        if note is None or note["change_seq"] != change_seq:
            return None
        return {
            "event": event,
            "id": note_id,
            "version": note["version"],
            "change_seq": change_seq,
            "body": note["body"],
            "title": note["title"],
            "labels": note["labels"],
            "created_at": note["created_at"],
            "updated_at": note["updated_at"],
            "origin": note["origin"],
        }
    # Missing eligibility metadata must never imply a public tombstone.
    if (
        type(private) is not bool
        or private
        or type(machine) is not bool
        or machine
        or origin not in {"owner", "agent", "external"}
    ):
        return None
    state = await ctx.capture.event_state(note_id)
    if event == "note.trashed":
        if (
            not state.get("public")
            or not state.get("trashed")
            or state.get("change_seq") != change_seq
        ):
            return None
    elif event == "note.purged":
        if state["exists"]:
            return None
    else:
        return None
    return {
        "event": event,
        "id": note_id,
        "version": expected_version,
        "change_seq": change_seq,
        "origin": origin,
    }


async def dispatch(
    ctx,
    note_id,
    event,
    expected_version,
    change_seq,
    private=None,
    machine=None,
    origin=None,
    targets=None,
    attempt=0,
):
    if type(attempt) is not int or not 0 <= attempt <= 2 or type(change_seq) is not int:
        return
    urls = destinations(ctx)
    if targets is not None:
        urls = [url for url in targets if url in urls]
    pending = []
    args = {
        "note_id": note_id,
        "event": event,
        "expected_version": expected_version,
        "change_seq": change_seq,
        "private": private,
        "machine": machine,
        "origin": origin,
    }
    for target in urls:
        key = identity("spunto-note-event-v1", event, note_id, change_seq, target)
        if await ctx.storage.records.get("deliveries", key):
            continue
        value = await payload(ctx, **args)
        if value is None:
            return
        value["event_id"] = key
        body = json.dumps(
            value, ensure_ascii=False, allow_nan=False, separators=(",", ":")
        ).encode()
        if len(body) > 450000:
            return
        transient = False
        headers = {"Content-Type": "application/json", "Idempotency-Key": key}
        token = ctx.settings.get("token")
        if token:
            headers["Authorization"] = "Bearer " + token

        async def pre_send(target=target, value=value):
            if target not in destinations(ctx):
                raise ContextError("capture_destination_changed")
            live = await payload(ctx, **args)
            expected = {name: item for name, item in value.items() if name != "event_id"}
            if live is None or live != expected:
                raise ContextError("capture_source_changed")

        try:
            response = await ctx.fetch(
                target,
                method="POST",
                body=body,
                headers=headers,
                max_bytes=450000,
                timeout=10,
                pre_send=pre_send,
            )
            if 200 <= response.status_code < 300:
                # Store only hashes/metadata; never an untagged retry copy of note text.
                await ctx.storage.records.put("deliveries", key, {"delivered": True})
                continue
            transient = response.status_code in {408, 429} or response.status_code >= 500
        except ContextError as exc:
            if str(exc) not in {"capture_source_changed", "capture_destination_changed"}:
                raise
            return
        except FetchError as exc:
            transient = str(exc) in {"fetch_timeout", "fetch_connection_failed"}
        if transient and attempt < 2:
            pending.append(target)
    if pending:
        await ctx.enqueue(
            "deliver",
            {**args, "targets": pending, "attempt": attempt + 1},
            note_id=note_id,
            delay_ms=30000 * 2**attempt,
        )


@plugin.on("note.saved")
async def saved(ctx: Ctx, note_id: str, note_version: int | None = None):
    note = await ctx.capture.note(note_id, note_version=note_version)
    if note is None:
        return
    await dispatch(ctx, note_id, "note.saved", note["version"], note["change_seq"])


@plugin.on("note.trashed")
async def trashed(
    ctx: Ctx,
    note_id: str,
    note_version: int | None = None,
    private=None,
    machine=None,
    origin=None,
    change_seq=None,
):
    await dispatch(ctx, note_id, "note.trashed", note_version, change_seq, private, machine, origin)


@plugin.on("note.purged")
async def purged(
    ctx: Ctx,
    note_id: str,
    note_version: int | None = None,
    private=None,
    machine=None,
    origin=None,
    change_seq=None,
):
    await dispatch(ctx, note_id, "note.purged", note_version, change_seq, private, machine, origin)


@plugin.job("deliver")
async def deliver(
    ctx: Ctx,
    note_id: str,
    event: str,
    expected_version: int,
    change_seq: int,
    private=None,
    machine=None,
    origin=None,
    targets=None,
    attempt=0,
):
    await dispatch(
        ctx,
        note_id,
        event,
        expected_version,
        change_seq,
        private,
        machine,
        origin,
        targets,
        attempt,
    )
