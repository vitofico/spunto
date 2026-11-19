"""Record human review in the same writer serialization as agent edits."""

import json

from spunto.api.errors import Problem
from spunto.core.notes import sequence
from spunto.host.language import tr
from spunto.host.plugin import ContextError

PLUGIN = "agent-inbox"
MEMBERSHIP = "member:reviewed"
BASE = "host:review-base"


async def _agent_wrote(conn, credential_id):
    return bool(
        await conn.execute_fetchall(
            "SELECT 1 FROM credential WHERE id=? AND kind='pat'", (credential_id,)
        )
    )


async def _reviewed(conn, note_id):
    return bool(
        await conn.execute_fetchall(
            "SELECT 1 FROM annotation WHERE plugin_id=? AND note_id=? AND kind=?",
            (PLUGIN, note_id, MEMBERSHIP),
        )
    )


async def _remember(conn, row, version):
    # Key holds the base, data holds the newest revision known to this record.
    await conn.execute(
        "INSERT INTO plugin_record(plugin_id,collection,id,note_id,key,data,updated_at) "
        "VALUES(?,?,?,?,?,?,?) ON CONFLICT(plugin_id,collection,id) DO UPDATE SET "
        "key=excluded.key,data=excluded.data,updated_at=excluded.updated_at",
        (
            PLUGIN,
            BASE,
            row["id"],
            row["id"],
            str(version) if version is not None else None,
            str(row["version"]),
            row["updated_at"],
        ),
    )


async def remember_creation(conn, row):
    await _remember(conn, row, None)


async def forget_base(conn, note_id):
    await conn.execute(
        "DELETE FROM plugin_record WHERE plugin_id=? AND collection=? AND id=?",
        (PLUGIN, BASE, note_id),
    )


async def remember_metadata(conn, row):
    await conn.execute(
        "UPDATE plugin_record SET data=?,updated_at=? "
        "WHERE plugin_id=? AND collection=? AND id=? AND data=?",
        (
            str(row["version"]),
            row["updated_at"],
            PLUGIN,
            BASE,
            row["id"],
            str(row["version"] - 1),
        ),
    )


async def remember_edit(conn, row, previous_credential):
    if await _reviewed(conn, row["id"]) or (
        previous_credential != row["updated_by"]
        and not await _agent_wrote(conn, previous_credential)
    ):
        # The immediately preceding revision is the exact base of this batch.
        await _remember(conn, row, row["version"] - 1)
        return True
    markers = await conn.execute_fetchall(
        "SELECT key,data FROM plugin_record WHERE plugin_id=? AND collection=? AND id=?",
        (PLUGIN, BASE, row["id"]),
    )
    if markers and markers[0]["data"] == str(row["version"] - 1):
        await _remember(conn, row, markers[0]["key"])
    elif row["source_kind"] == "agent" and row["version"] == 2:
        await remember_creation(conn, row)
    elif markers:
        await forget_base(conn, row["id"])
    # For an already-pending legacy edit we cannot infer where that batch started.
    return False


async def comparison(conn, row, note):
    if (
        note["trashed"]
        or (row["source_kind"] != "agent" and not note["updated_via_agent"])
        or any(
            item["plugin_id"] == PLUGIN and item["kind"] == MEMBERSHIP
            for item in note["annotations"]
        )
    ):
        return None
    markers = await conn.execute_fetchall(
        "SELECT b.key,r.version,r.body FROM plugin_record b "
        "LEFT JOIN note_revision r ON r.note_id=b.note_id AND r.version=CAST(b.key AS BIGINT) "
        "WHERE b.plugin_id=? AND b.collection=? AND b.id=? AND b.data=?",
        (PLUGIN, BASE, row["id"], str(row["version"])),
    )
    if markers:
        prior = markers[0]
        if prior["key"] is None:
            return {"kind": "created", "before": None}
        before = {"version": prior["version"], "body": prior["body"]} if prior["version"] else None
        return {"kind": "edited", "before": before}
    kind = (
        "created"
        if row["source_kind"] == "agent" and (row["version"] == 1 or not note["updated_via_agent"])
        else "edited"
    )
    return {"kind": kind, "before": None}


async def _forget_legacy_record(conn, note_id):
    await conn.execute(
        "DELETE FROM plugin_record WHERE plugin_id=? AND collection='reviewed' AND id=?",
        (PLUGIN, note_id),
    )


async def accept(ctx, note_id, base_version):
    if (
        ctx.plugin_id != PLUGIN
        or ctx.operation != "command:mark-reviewed"
        or ctx.principal is None
        or not ctx.principal.owner
        or ctx.principal.workspace_role != "owner"
        or ctx.unattended
    ):
        raise ContextError("owner_required")
    if base_version is not None and (type(base_version) is not int or base_version < 1):
        raise ValueError("base_version must be a positive integer")
    ctx.check_write()
    notes = ctx.notes
    async with notes.store.write() as conn:
        ctx.check("notes.read")
        ctx.check("annotate")
        try:
            row = await notes.core._row(conn, note_id)
        except Problem as exc:
            if exc.status != 404 or base_version is not None:
                raise
            return False
        if base_version is not None and row["version"] != base_version:
            raise Problem(409, "version_conflict", tr("The note changed."))
        if row["source_kind"] != "agent" and not await _agent_wrote(conn, row["updated_by"]):
            if base_version is None:
                return False
            raise Problem(409, "note_not_from_agent", tr("Note unavailable"))
        if not await _reviewed(conn, row["id"]):
            await conn.execute(
                "INSERT INTO annotation VALUES (?,?,?,?,?,?)",
                (PLUGIN, row["id"], MEMBERSHIP, "{}", await sequence(conn), ctx.clock()),
            )
        await _forget_legacy_record(conn, row["id"])
        await forget_base(conn, row["id"])
        ctx.check("annotate")
    return True


async def clear(conn, note_id, now):
    deleted = await conn.execute(
        "DELETE FROM annotation WHERE plugin_id=? AND note_id=? AND kind=?",
        (PLUGIN, note_id, MEMBERSHIP),
    )
    if deleted.rowcount:
        await _forget_legacy_record(conn, note_id)
        await conn.execute(
            "INSERT OR REPLACE INTO tombstone VALUES ('annotation',?,?,?)",
            (f"{PLUGIN}/{note_id}/{MEMBERSHIP}", await sequence(conn), now),
        )


async def reconcile_legacy(ctx, note_id):
    # Jobs queued before this was transactional may still need to clear an
    # old membership. New reviews remove the legacy record in the writer.
    if await ctx.storage.records.get("reviewed", note_id) is None:
        return
    notes = ctx.notes
    async with notes.store.write() as conn:
        ctx.check("notes.read")
        ctx.check("annotate")
        records = await conn.execute_fetchall(
            "SELECT data FROM plugin_record WHERE plugin_id=? AND collection='reviewed' AND id=?",
            (PLUGIN, note_id),
        )
        if records:
            try:
                row = await notes.core._row(conn, note_id)
            except Problem as exc:
                if exc.status != 404:
                    raise
                return
            if row["version"] > json.loads(records[0]["data"])["version"] and await _agent_wrote(
                conn, row["updated_by"]
            ):
                await clear(conn, note_id, ctx.clock())
        ctx.check("annotate")
