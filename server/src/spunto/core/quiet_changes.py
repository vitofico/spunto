"""Finish bundled title and recording proposals against the same revision as their receipts."""

import json
import re

from spunto.core.notes import sequence
from spunto.core.plugin_notes import PluginNotes
from spunto.core.render import _MARKDOWN
from spunto.core.suggestions import apply_quiet, owner_credential, suggest
from spunto.host.storage import encoded

_ATX = re.compile(r"\A((?:[ \t]*\r?\n)* {0,3})#(?:[ \t][^\r\n]*|(?=\r?\n|$))")
_SETEXT = re.compile(r"^ {0,3}=+[ \t]*\r?$", re.MULTILINE)
_RECORDING = re.compile(r"\[Recording (0|[1-9][0-9]?):([0-5][0-9])\]\(/a/([0-9a-f-]{36})\)")


def heading_span(body):
    if heading := _ATX.match(body):
        return heading.end(1), heading.end()
    underline = _SETEXT.search(body)
    if not underline:
        return None
    # Parse block structure only. An underline inside a list or code block is not a note title.
    prefix = body[: underline.end()].rstrip("\r")
    tokens = []
    _MARKDOWN.block.parse(prefix, _MARKDOWN, {}, tokens)
    if not tokens or tokens[0].type != "heading_open" or tokens[0].tag != "h1":
        return None
    lines = prefix.splitlines(keepends=True)
    first, end = tokens[0].map
    return sum(map(len, lines[:first])), len("".join(lines[:end]).rstrip("\r\n"))


def titled_body(body, title):
    span = heading_span(body)
    if span:
        start, end = span
        return body[:start] + "# " + title + body[end:]
    return "# " + title + "\n\n" + body


async def title_event(ctx, note):
    if ctx.operation != "hook:note.saved":
        return True
    async with ctx._host.store.read() as conn:
        return bool(
            await conn.execute_fetchall(
                "SELECT 1 FROM note_revision WHERE note_id=? AND version=? "
                "AND change_kind IN ('create','edit','append','merge')",
                (note["id"], note["version"]),
            )
        )


async def _title(ctx, conn, row, title):
    if not title or title == row["title"]:
        return None
    proposal = await suggest(
        ctx, row["id"], "set_title", {"title": title}, note_version=row["version"], connection=conn
    )
    # Backfill jobs and explicit commands always produce a suggestion for review.
    if (
        ctx.operation == "hook:note.saved"
        and heading_span(row["body"]) is None
        and await owner_credential(ctx, conn, row["updated_by"])
        and await conn.execute_fetchall(
            "SELECT 1 FROM note_revision WHERE note_id=? AND version=? "
            "AND change_kind IN ('create','edit','append','merge')",
            (row["id"], row["version"]),
        )
    ):
        updated = await apply_quiet(ctx, conn, row, proposal["id"], titled_body(row["body"], title))
        if updated:
            return None
    return proposal


async def finish_summary(ctx, note, title):
    adapter = PluginNotes(ctx)
    async with adapter.store.write() as conn:
        row = await adapter.core._row(conn, note["id"])
        if row["version"] != note["version"]:
            return False, None
        proposal = await _title(ctx, conn, row, title)
        # On an automatic edit the saved hook observes this new revision.
        await conn.execute(
            "INSERT INTO plugin_record(plugin_id,collection,id,note_id,key,data,updated_at) "
            "VALUES(?,'processed',?,?,NULL,?,?) ON CONFLICT(plugin_id,collection,id) "
            "DO UPDATE SET data=excluded.data,updated_at=excluded.updated_at",
            (
                ctx.plugin_id,
                row["id"],
                row["id"],
                encoded({"version": row["version"]}),
                ctx.clock(),
            ),
        )
        ctx.check("suggest")
        return True, proposal


async def finish_source(ctx, note, key, facts):
    capture = ctx.capture
    async with ctx._host.store.write() as conn:
        row = await capture._row(conn, note["id"], note["version"])
        if row is None:
            return False, None
        await capture._store_result(conn, "sources", key, row["id"], row["version"], facts)
        # A new URL replaces this plugin's previous suggestions on the note.

        for prior in await conn.execute_fetchall(
            "SELECT id FROM suggestion WHERE plugin_id=? AND note_id=? AND status='pending'",
            (ctx.plugin_id, row["id"]),
        ):
            await conn.execute(
                "UPDATE suggestion SET status='superseded',change_seq=? WHERE id=?",
                (await sequence(conn), prior["id"]),
            )
        proposal = await _title(ctx, conn, row, facts["title"])
        for path in facts["labels"][: 2 if proposal else 3]:
            await suggest(
                ctx,
                row["id"],
                "add_label",
                {"path": path},
                note_version=row["version"],
                confidence=facts.get("label_confidence", {}).get(path),
                connection=conn,
            )
        return True, proposal


def _recording_body(body, seconds):
    matches = list(_RECORDING.finditer(body))
    if not matches or _RECORDING.sub("", body).strip() or len(matches) != len(seconds):
        return False
    return {m[3]: int(m[1]) * 60 + int(m[2]) for m in matches} == seconds


async def finish_recordings(ctx, note, proposed, values, pending):
    capture = ctx.capture
    async with ctx._host.store.write() as conn:
        row = await capture._row(conn, note["id"], note["version"])
        if row is None:
            return None
        suggestion = await suggest(
            ctx,
            row["id"],
            "replace_body",
            {"body": proposed},
            note_version=row["version"],
            connection=conn,
        )
        for key, receipt, attachment in pending:
            await capture._store_result(
                conn,
                "recordings",
                key,
                row["id"],
                row["version"],
                {**receipt, "complete": True, "suggestion_id": suggestion["id"]},
                attachment_id=attachment["id"],
            )
        markers = await conn.execute_fetchall(
            "SELECT id FROM plugin_record WHERE plugin_id='voice-memos' "
            "AND collection='host:capture-markers' AND key=?",
            (row["id"],),
        )
        # Bind pre-sync upload receipts to the note's lifetime once it exists.
        if markers:
            await conn.execute(
                "UPDATE plugin_record SET note_id=? WHERE plugin_id='voice-memos' "
                "AND collection='host:capture-markers' AND key=? AND note_id IS NULL",
                (row["id"], row["id"]),
            )
        seconds = {v["attachment_id"]: v.get("marker_seconds", int(v["seconds"])) for v in values}
        linked = await conn.execute_fetchall(
            "SELECT attachment_id FROM note_attachment WHERE note_id=?", (row["id"],)
        )
        if (
            ctx.settings.get("keep_link", True)
            and {r[0] for r in linked} == seconds.keys()
            and {row["id"] + "/" + clip for clip in seconds} <= {m[0] for m in markers}
            and _recording_body(row["body"], seconds)
            and proposed.startswith(row["body"] + "\n\n")
        ):
            await conn.execute(
                "UPDATE suggestion SET payload=? WHERE id=?",
                (json.dumps({"body": proposed, "_recording_append": True}), suggestion["id"]),
            )
            return bool(await apply_quiet(ctx, conn, row, suggestion["id"], proposed))
        return False
