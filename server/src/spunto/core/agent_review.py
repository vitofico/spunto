"""Record human review in the same writer serialization as agent edits."""

import json

from spunto.api.errors import Problem
from spunto.core.notes import sequence
from spunto.host.language import tr
from spunto.host.plugin import ContextError

PLUGIN = "agent-inbox"
MEMBERSHIP = "member:reviewed"


async def _agent_wrote(conn, row):
    return bool(
        await conn.execute_fetchall(
            "SELECT 1 FROM credential WHERE id=? AND kind='pat'", (row["updated_by"],)
        )
    )


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
        if row["source_kind"] != "agent" and not await _agent_wrote(conn, row):
            if base_version is None:
                return False
            raise Problem(409, "note_not_from_agent", tr("Note unavailable"))
        exists = await conn.execute_fetchall(
            "SELECT 1 FROM annotation WHERE plugin_id=? AND note_id=? AND kind=?",
            (PLUGIN, row["id"], MEMBERSHIP),
        )
        if not exists:
            await conn.execute(
                "INSERT INTO annotation VALUES (?,?,?,?,?,?)",
                (PLUGIN, row["id"], MEMBERSHIP, "{}", await sequence(conn), ctx.clock()),
            )
        await _forget_legacy_record(conn, row["id"])
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
                conn, row
            ):
                await clear(conn, note_id, ctx.clock())
        ctx.check("annotate")
