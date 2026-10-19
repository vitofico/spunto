"""Core retention: trash, old revisions and idempotency results."""

import asyncio
import logging

from spunto.auth.tokens import Principal
from spunto.config import Settings
from spunto.core.notes import Notes, sequence
from spunto.store.db import Store, now_ms

log = logging.getLogger(__name__)
DAY_MS = 86_400_000


async def prune(store: Store, settings: Settings, now: int | None = None) -> dict:
    now = now_ms() if now is None else now
    service = Notes(
        store, Principal("maintenance", "session", "Retention", "full", private_ok=True)
    )
    async with store.write() as conn:
        await conn.execute("DELETE FROM tombstone WHERE deleted_at<?", (now - 180 * DAY_MS,))
        rows = await conn.execute_fetchall(
            "SELECT *,rowid AS fts_id FROM note WHERE trashed_at IS NOT NULL AND trashed_at<=?",
            (now - settings.trash_days * DAY_MS,),
        )
        for row in rows:
            await service._delete(conn, row)
        revisions = await conn.execute(
            (
                "DELETE FROM note_revision WHERE changed_at<? AND version NOT IN "
                "(SELECT version FROM note_revision newer WHERE "
                "newer.note_id=note_revision.note_id ORDER BY version DESC LIMIT 20)"
            ),
            (now - settings.revision_days * DAY_MS,),
        )
        operations = await conn.execute(
            "DELETE FROM applied_op WHERE applied_at<?", (now - 30 * DAY_MS,)
        )
        expired = await conn.execute_fetchall(
            "SELECT id FROM suggestion WHERE status='pending' AND created_at<?",
            (now - 14 * DAY_MS,),
        )
        for item in expired:
            await conn.execute(
                "UPDATE suggestion SET status='expired',change_seq=? WHERE id=?",
                (await sequence(conn), item[0]),
            )
        return {
            "notes": len(rows),
            "revisions": revisions.rowcount,
            "operations": operations.rowcount,
        }


async def run_daily(store: Store, settings: Settings) -> None:
    while True:
        try:
            counts = await prune(store, settings)
            if any(counts.values()):
                log.info(
                    "Retention removed notes=%d revisions=%d operations=%d",
                    counts["notes"],
                    counts["revisions"],
                    counts["operations"],
                )
        except Exception:
            # Exception messages may hold SQL parameters; log a stable code only.
            log.error("retention_failed")
        await asyncio.sleep(86_400)
