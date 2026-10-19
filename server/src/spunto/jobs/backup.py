"""Snapshots on demand and every day, retention, and restore (02 §10.6)."""

import asyncio
import logging
import os
import re
import secrets
import shutil
import sqlite3
from contextlib import closing
from datetime import UTC, datetime, time, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from spunto.config import Settings
from spunto.errors import OperatorError
from spunto.store.db import DB_NAME, connect
from spunto.store.lock import DataLock
from spunto.store.migrations import LATEST, schema_version
from spunto.store.snapshot import checksum_path, taken_at, verify_checksum, write_snapshot

log = logging.getLogger(__name__)

# Snapshots taken before a migration live apart from the daily ones and follow
# their own, simpler retention.
PRE_MIGRATION = "pre-migration"
PRE_MIGRATION_KEEP = timedelta(days=30)

_M1_COLUMNS = {
    "note": (
        "id,body,title,body_hash,version,change_seq,created_at,updated_at,pinned,archived_at,"
        "trashed_at,private,pinned_clock,archived_clock,trashed_clock,private_clock,open_tasks,"
        "source_kind,source_ref,source_url,origin,machine,created_by,updated_by,conflict_of,import_key"
    ),
    "note_revision": "note_id,version,body,change_kind,changed_by,changed_at",
    "label": "id,key,path,created_at",
    "note_label": "note_id,label_id",
    "note_fts": "title,body,extra",
    "tombstone": "kind,id,change_seq,deleted_at",
    "attachment": "id,sha256,mime,size,filename,width,height,created_at",
    "note_attachment": "note_id,attachment_id",
    "applied_op": "credential_id,op_id,result,applied_at",
}

_M2_COLUMNS = {
    "plugin": "id,version,kind,switch,state,pinned_by_env,granted,settings,ai_route,position,"
    "data_version,last_run_at,last_error",
    "plugin_kv": "plugin_id,key,value",
    "plugin_record": "plugin_id,collection,id,note_id,key,data,updated_at",
    "suggestion": "id,plugin_id,note_id,note_version,action,payload,confidence,reason,status,"
    "model,change_seq,created_at,decided_at,decided_by",
    "annotation": "plugin_id,note_id,kind,payload,change_seq,updated_at",
    "note_text": "plugin_id,note_id,text,updated_at",
    "job": "id,plugin_id,kind,note_id,args,priority,status,attempts,run_after,lease_until,"
    "last_error,created_at,updated_at",
    "setting": "key,value",
}

_M4_COLUMNS = {
    "embedding_model": "model_key,profile,descriptor,dimension,created_at",
    "embedding_route": "profile,active_key,target_key",
    "chunk": "id,note_id,ordinal,text_hash,model_key",
    "embedding_note": "note_id,model_key,text_hash,body_hash",
    "ai_stat": "day,plugin_id,capability,outcome,n",
}


def backup_now(settings: Settings, now: datetime | None = None) -> Path:
    """Write a snapshot of the live database, then apply retention."""
    now = now or datetime.now(UTC)
    db = settings.data_dir / DB_NAME
    if not db.exists():
        raise OperatorError(f"There is no database at {db} yet; start the server once first.")
    with closing(connect(db)) as conn:
        snapshot = write_snapshot(conn, settings.backup_dir, now)
    prune(settings.backup_dir, settings.backup_keep_daily, settings.backup_keep_weekly, now)
    return snapshot


def prune(directory: Path, keep_daily: int, keep_weekly: int, now: datetime) -> None:
    """Keep the newest snapshot of each of the last `keep_daily` days and `keep_weekly` weeks.

    Days and weeks count only those that have a snapshot, so a server that was
    down for a while does not lose its older history. Files that are not
    snapshots are left alone.
    """
    days: set = set()
    weeks: set = set()
    for when, path in sorted(_snapshots(directory), reverse=True):
        day, week = when.date(), when.isocalendar()[:2]
        keep = False
        if day not in days and len(days) < keep_daily:
            days.add(day)
            keep = True
        if week not in weeks and len(weeks) < keep_weekly:
            weeks.add(week)
            keep = True
        if not keep:
            _remove(path)
    for when, path in _snapshots(directory / PRE_MIGRATION):
        if when < now - PRE_MIGRATION_KEEP:
            _remove(path)


async def run_daily(settings: Settings) -> None:
    """Take the daily snapshot at SPUNTO_BACKUP_TIME in TZ, until cancelled."""
    while True:
        await asyncio.sleep(seconds_until(settings.backup_time, settings.zone, datetime.now(UTC)))
        try:
            snapshot = await asyncio.to_thread(backup_now, settings)
        except Exception:
            log.exception("The daily snapshot failed")
        else:
            log.info("Daily snapshot written to %s", snapshot)


def seconds_until(at: time, zone: ZoneInfo, now: datetime) -> float:
    local = now.astimezone(zone)
    target = datetime.combine(local.date(), at, zone)
    if target <= local:
        target = datetime.combine(local.date() + timedelta(days=1), at, zone)
    return (target - now).total_seconds()


def restore(settings: Settings, snapshot: Path, now: datetime | None = None) -> str:
    """Replace the database with `snapshot` and return a summary for the operator.

    Runs only while the server is stopped, which the data lock enforces. The
    current database is moved aside, never deleted, and the restored one gets a
    new sync epoch, so devices send back whatever they hold that is newer than
    the snapshot (02 §6.8).
    """
    now = now or datetime.now(UTC)
    if not snapshot.is_file():
        raise OperatorError(f"There is no snapshot at {snapshot}.")
    with DataLock(settings.data_dir):
        verify_checksum(snapshot)
        read_only = f"{snapshot.resolve().as_uri()}?mode=ro"
        try:
            with closing(sqlite3.connect(read_only, uri=True)) as conn:
                from spunto.store.db import _load_vec

                _load_vec(conn)
                intact = conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
                schema = schema_version(conn)
        except sqlite3.DatabaseError:
            intact = False
        if not intact:
            raise OperatorError(f"{snapshot} fails SQLite's integrity check.")
        if schema == 0:
            raise OperatorError(f"{snapshot.name} is not a Spunto snapshot.")
        if schema > LATEST:
            raise OperatorError(
                f"{snapshot.name} holds schema {schema}, newer than this release knows "
                f"({LATEST}); restore it with the release that wrote it."
            )
        with closing(sqlite3.connect(read_only, uri=True)) as conn:
            _load_vec(conn)
            _validate_snapshot(conn, snapshot)
            note_count, newest, missing = _restore_facts(conn, settings, snapshot, schema)

        db = settings.data_dir / DB_NAME
        incoming = db.with_name(f"{DB_NAME}.restoring")
        # Left behind by an interrupted restore, these would replay into the fresh copy.
        for suffix in ("-wal", "-shm"):
            Path(f"{incoming}{suffix}").unlink(missing_ok=True)
        shutil.copyfile(snapshot, incoming)
        with closing(connect(incoming)) as conn:
            # A restored snapshot must never revive a spent pairing grant.
            if schema >= 5:
                conn.execute("DELETE FROM pairing")
            old_epoch = conn.execute("SELECT value FROM meta WHERE key = 'sync_epoch'").fetchone()
            epoch = _new_epoch(old_epoch["value"] if old_epoch else None)
            conn.execute(
                "INSERT INTO meta (key, value) VALUES ('sync_epoch', ?)"
                " ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                (epoch,),
            )

        with incoming.open("rb") as file:
            os.fsync(file.fileno())
        # Preserve the original inode before atomically replacing the active path.
        # Moving it away first would leave no active database if installation fails.
        aside = None
        if db.exists():
            # Fold the write-ahead log into the file, so the copy set aside is whole.
            with closing(connect(db)) as conn:
                if conn.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchone()[0]:
                    raise OperatorError("The database is still in use; let other commands finish.")
            aside = db.with_name(f"{DB_NAME}.before-restore-{now:%Y-%m-%dT%H%M%SZ}")
            try:
                os.link(db, aside)
            except FileExistsError:
                raise OperatorError(
                    f"{aside} already exists; retry the restore in a second."
                ) from None
            _sync_directory(settings.data_dir)
        incoming.replace(db)
        _sync_directory(settings.data_dir)

    lines = [f"Restored {snapshot.name} (schema {schema}) into {db}."]
    if aside:
        lines.append(f"The previous database is kept at {aside}.")
    if schema < LATEST:
        lines.append(f"The server will upgrade it to schema {LATEST} when it starts.")
    lines.append(f"Notes restored: {note_count}; newest created date: {newest or 'none'}.")
    if missing:
        lines.append(f"Missing referenced attachment files ({len(missing)}):")
        lines.extend(f"  {path}" for path in missing)
    else:
        lines.append("Missing referenced attachment files: none.")
    lines.append(f"New sync epoch {epoch}: devices will send back what is newer than the snapshot.")
    return "\n".join(lines)


def _validate_snapshot(conn: sqlite3.Connection, snapshot: Path) -> None:
    """user_version is generic SQLite metadata; also require Spunto's foundation schema."""
    try:
        conn.execute("SELECT id, password_hash, password_source, created_at FROM owner LIMIT 0")
        conn.execute(
            "SELECT id, kind, name, client, scope, external_ok, private_ok, secret_hash, "
            "created_at, expires_at, last_used_at, revoked_at FROM credential LIMIT 0"
        )
        facts = dict(conn.execute("SELECT key, value FROM meta"))
        if schema_version(conn) >= 2:
            expected = _M1_COLUMNS | (_M2_COLUMNS if schema_version(conn) >= 3 else {})
            if schema_version(conn) >= 4:
                expected |= _M4_COLUMNS
            for table, columns in expected.items():
                actual = {
                    row[0]
                    for row in conn.execute("SELECT name FROM pragma_table_info(?)", (table,))
                }
                if not set(columns.split(",")) <= actual:
                    raise OperatorError(f"{snapshot.name} is not a Spunto snapshot.")
            fts = conn.execute(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='note_fts'"
            ).fetchone()
            if (
                not fts
                or not isinstance(fts[0], str)
                or not re.search(r"\bUSING\s+fts5\s*\(", fts[0], re.IGNORECASE)
            ):
                raise OperatorError(f"{snapshot.name} is not a Spunto snapshot.")
            for key in ("change_seq", "plugins_rev"):
                if (
                    not isinstance(facts.get(key), str)
                    or not re.fullmatch(r"0|[1-9][0-9]*", facts[key])
                    or len(facts[key]) > 19
                    or int(facts[key]) > 2**63 - 1
                ):
                    raise OperatorError(f"{snapshot.name} is not a Spunto snapshot.")
            plugin_changes = (
                " UNION ALL SELECT change_seq FROM suggestion UNION ALL "
                "SELECT change_seq FROM annotation"
                if schema_version(conn) >= 3
                else ""
            )
            latest = conn.execute(
                "SELECT max(change_seq) FROM (SELECT change_seq FROM note "  # noqa: S608
                "UNION ALL SELECT change_seq FROM tombstone" + plugin_changes + ")"  # noqa: S608
            ).fetchone()[0]
            if latest is not None and (
                not isinstance(latest, int) or latest < 1 or latest > int(facts["change_seq"])
            ):
                raise OperatorError(f"{snapshot.name} is not a Spunto snapshot.")
            if conn.execute("PRAGMA foreign_key_check").fetchone():
                raise OperatorError(f"{snapshot.name} is not a Spunto snapshot.")
    except sqlite3.DatabaseError:
        raise OperatorError(f"{snapshot.name} is not a Spunto snapshot.") from None
    if not (
        isinstance(facts.get("instance_id"), str)
        and re.fullmatch(r"[0-9a-f]{32}", facts["instance_id"])
        and isinstance(facts.get("sync_epoch"), str)
        and re.fullmatch(r"[0-9a-f]{8}", facts["sync_epoch"])
        and facts.get("migrated_by")
    ):
        raise OperatorError(f"{snapshot.name} is not a Spunto snapshot.")


def _restore_facts(
    conn: sqlite3.Connection, settings: Settings, snapshot: Path, schema: int
) -> tuple[int, str | None, list[Path]]:
    if schema < 2:
        return 0, None, []
    count, newest = conn.execute("SELECT count(*),max(created_at) FROM note").fetchone()
    try:
        newest_date = (
            datetime.fromtimestamp(newest / 1000, UTC)
            .isoformat(timespec="milliseconds")
            .replace("+00:00", "Z")
            if newest is not None
            else None
        )
    except ValueError, TypeError, OverflowError, OSError:
        raise OperatorError(f"{snapshot.name} is not a Spunto snapshot.") from None
    missing = []
    attachments = conn.execute(
        "SELECT DISTINCT a.sha256 FROM attachment a "
        "JOIN note_attachment na ON na.attachment_id=a.id ORDER BY a.sha256"
    )
    for (digest,) in attachments:
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise OperatorError(f"{snapshot.name} is not a Spunto snapshot.")
        path = settings.attachments_dir / digest[:2] / digest[2:4] / digest
        if not path.is_file():
            missing.append(path)
    return count, newest_date, missing


def _sync_directory(directory: Path) -> None:
    fd = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _new_epoch(old: str | None) -> str:
    while (epoch := secrets.token_hex(4)) == old:
        pass
    return epoch


def _snapshots(directory: Path) -> list[tuple[datetime, Path]]:
    if not directory.is_dir():
        return []
    return [(when, path) for path in directory.iterdir() if (when := taken_at(path))]


def _remove(snapshot: Path) -> None:
    snapshot.unlink(missing_ok=True)
    checksum_path(snapshot).unlink(missing_ok=True)
