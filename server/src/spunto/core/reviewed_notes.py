"""Ask-only, confirmed single-note changes with transactional replay and undo."""

import hashlib
import json
import re
import uuid

from spunto.ai.passages import evidence
from spunto.api.errors import Problem
from spunto.core.notes import Notes
from spunto.host.plugin import ContextError

_ATTACHMENT = re.compile(r"/a/([0-9a-fA-F-]{36})(?![0-9a-fA-F-])")


def authorize(ctx):
    ctx.check("notes.review")
    if (
        ctx.plugin_id != "ask"
        or ctx.operation != "command:save-answer"
        or ctx.unattended
        or ctx.principal is None
        or not ctx.principal.owner
        or not ctx.principal.writable
    ):
        raise ContextError("reviewed_owner_command_required")


def metadata(row):
    return hashlib.sha256(
        json.dumps(
            [
                row[key]
                for key in (
                    "private",
                    "machine",
                    "pinned",
                    "archived_at",
                    "trashed_at",
                    "origin",
                    "source_kind",
                    "source_ref",
                    "source_url",
                    "created_by",
                    "conflict_of",
                )
            ],
            ensure_ascii=False,
        ).encode()
    ).hexdigest()


def replacement(body, before, after):
    if not before or body.count(before) != 1 or before == after:
        raise Problem(409, "ask_change_ambiguous", "The exact change is unclear. Ask again.")
    result = body.replace(before, after, 1)
    if set(_ATTACHMENT.findall(body)) != set(_ATTACHMENT.findall(result)):
        raise Problem(409, "ask_attachment_change", "Edit attachment links in the note editor.")
    return result


async def transact(ctx, action_id, action, *, stage, check):
    """An applied_op receipt, not mutable process memory, decides every replay."""
    authorize(ctx)
    if action["kind"] == "create":
        ctx.check("notes.create")
    notes = Notes(ctx._host.store, ctx.principal)
    keys = ["ask-review:" + action_id + suffix for suffix in (":apply", ":undo")]
    async with notes.store.write() as conn:
        authorize(ctx)
        if action["kind"] == "create":
            ctx.check("notes.create")
        check()
        prior = await conn.execute_fetchall(
            "SELECT op_id,result FROM applied_op WHERE credential_id=? AND op_id IN (?,?)",
            (ctx.principal.id, *keys),
        )
        prior = {row["op_id"]: json.loads(row["result"]) for row in prior}
        if keys[1] in prior:
            result = prior[keys[1]]
        elif keys[0] in prior and stage != "undo":
            result = prior[keys[0]]
        else:
            undo = stage == "undo"
            if keys[0] not in prior and stage != "preview":
                raise Problem(409, "ask_action_changed", "Confirm the preview before undoing it.")
            expected = prior[keys[0]] if undo else action
            sources = {
                key: value
                for key, value in action.get("sources", {}).items()
                if key != action.get("note_id")
            }
            if sources and not undo:
                await ctx.notes._guard_sources(
                    conn,
                    {key: value["version"] for key, value in sources.items()},
                    source_fingerprints={
                        key: value["fingerprint"] for key, value in sources.items()
                    },
                )
            note_id = expected.get("note_id")
            row = await notes._row(conn, note_id) if note_id else None
            if row is not None:
                authoring = action.get("created_receipt")
                if authoring:
                    created = await conn.execute_fetchall(
                        "SELECT result FROM applied_op WHERE credential_id=? AND op_id=?",
                        (ctx.principal.id, "ask-review:" + authoring + ":apply"),
                    )
                    if not created or json.loads(created[0][0])["note_id"] != note_id:
                        raise ContextError("authoring_target_unavailable")
                _, fingerprint = await evidence(conn, row)
                if (
                    row["private"]
                    or row["archived_at"] is not None
                    or (row["machine"] and not (authoring or undo and action["kind"] == "create"))
                    or row["version"] != expected["version"]
                    or fingerprint != expected["fingerprint"]
                    or metadata(row) != expected["metadata"]
                ):
                    raise Problem(409, "version_conflict", "The note changed. Ask again.")
            if undo:
                if action["kind"] == "create":
                    await notes.flags_in_transaction(conn, note_id, {"trashed": True})
                else:
                    await notes._update(
                        conn,
                        row,
                        action["before_body"],
                        "ask_undo",
                        coalesce=False,
                        changes={"machine": row["machine"]},
                    )
                result = {"note_id": note_id, "state": "done", "kind": action["kind"]}
                if action["kind"] != "create":
                    restored = await notes._row(conn, note_id)
                    _, fingerprint = await evidence(conn, restored)
                    result.update(
                        version=restored["version"],
                        fingerprint=fingerprint,
                        metadata=metadata(restored),
                    )
            else:
                if action["kind"] == "create":
                    created = await notes._insert(
                        conn,
                        action["body"],
                        str(uuid.uuid7()),
                        ctx.clock(),
                        {"kind": "plugin", "ref": "ask"},
                        False,
                        origin="plugin",
                        machine=True,
                    )
                    note_id = created["id"]
                else:
                    body = replacement(row["body"], action["before"], action["after"])
                    await notes._update(
                        conn,
                        row,
                        body,
                        "ask_review",
                        coalesce=False,
                        changes={"machine": row["machine"]},
                    )
                row = await notes._row(conn, note_id)
                _, fingerprint = await evidence(conn, row)
                result = {
                    "note_id": note_id,
                    "state": "undo",
                    "kind": action["kind"],
                    "version": row["version"],
                    "fingerprint": fingerprint,
                    "metadata": metadata(row),
                }
            result["applied_at"] = ctx.clock()
            await conn.execute(
                "INSERT INTO applied_op VALUES (?,?,?,?)",
                (ctx.principal.id, keys[int(undo)], json.dumps(result), ctx.clock()),
            )
        authorize(ctx)
        if action["kind"] == "create":
            ctx.check("notes.create")
        check()
    return result
