import sqlite3
from contextlib import closing

import pytest

from spunto.errors import OperatorError
from spunto.store import migrations
from spunto.store.db import DB_NAME, Store, connect
from spunto.store.lock import DataLock
from spunto.store.migrations import LATEST, migrate, schema_version
from spunto.store.snapshot import taken_at

pytestmark = pytest.mark.anyio

PRAGMAS = {"journal_mode": "wal", "synchronous": 2, "foreign_keys": 1, "busy_timeout": 5000}


def pragmas(conn: sqlite3.Connection) -> dict[str, object]:
    return {name: conn.execute(f"PRAGMA {name}").fetchone()[0] for name in PRAGMAS}


async def test_every_connection_is_opened_as_the_spec_says(tmp_path):
    with closing(connect(tmp_path / DB_NAME)) as conn:
        assert pragmas(conn) == PRAGMAS
    store = await Store.open(tmp_path / DB_NAME)
    try:
        async with store.write() as conn:
            assert {
                n: (await conn.execute_fetchall(f"PRAGMA {n}"))[0][0] for n in PRAGMAS
            } == PRAGMAS
        async with store.read() as conn:
            assert {
                n: (await conn.execute_fetchall(f"PRAGMA {n}"))[0][0] for n in PRAGMAS
            } == PRAGMAS
    finally:
        await store.close()


async def test_a_failed_write_leaves_nothing_behind(tmp_path):
    store = await Store.open(tmp_path / DB_NAME)
    try:
        async with store.write() as conn:
            await conn.execute("CREATE TABLE t (v)")
        with pytest.raises(RuntimeError):
            async with store.write() as conn:
                await conn.execute("INSERT INTO t VALUES (1)")
                raise RuntimeError
        async with store.write() as conn:
            await conn.execute("INSERT INTO t VALUES (2)")
        async with store.read() as conn:
            assert [tuple(r) for r in await conn.execute_fetchall("SELECT v FROM t")] == [(2,)]
    finally:
        await store.close()


def test_a_second_process_cannot_open_the_same_data_directory(tmp_path):
    with DataLock(tmp_path):
        with pytest.raises(OperatorError, match=f"Another Spunto process is using {tmp_path}"):
            DataLock(tmp_path)
    DataLock(tmp_path).release()  # free again once the holder is gone


def test_an_unwritable_data_directory_is_named(tmp_path):
    tmp_path.chmod(0o500)
    try:
        with pytest.raises(OperatorError, match="Cannot write to the data directory"):
            DataLock(tmp_path / "data")
    finally:
        tmp_path.chmod(0o700)


def test_a_fresh_database_is_created_without_a_snapshot(tmp_path):
    assert migrate(tmp_path / DB_NAME, tmp_path / "pre") == 0
    with closing(connect(tmp_path / DB_NAME)) as conn:
        assert schema_version(conn) == LATEST
        facts = dict(conn.execute("SELECT key, value FROM meta").fetchall())
    assert len(facts["instance_id"]) == 32
    assert len(facts["sync_epoch"]) == 8
    assert not (tmp_path / "pre").exists()
    assert migrate(tmp_path / DB_NAME, tmp_path / "pre") == LATEST


def test_pending_migrations_run_after_a_snapshot(tmp_path, monkeypatch):
    migrate(tmp_path / DB_NAME, tmp_path / "pre")
    with closing(connect(tmp_path / DB_NAME)) as conn:
        conn.execute("INSERT INTO owner VALUES (1, 'hash', 'setup', 0)")
    monkeypatch.setattr(
        migrations, "MIGRATIONS", [*migrations.MIGRATIONS, "CREATE TABLE extra (v);"]
    )
    monkeypatch.setattr(migrations, "LATEST", LATEST + 1)

    assert migrate(tmp_path / DB_NAME, tmp_path / "pre") == LATEST

    [snapshot] = [p for p in (tmp_path / "pre").iterdir() if taken_at(p)]
    assert snapshot.name.endswith(f"-s{LATEST}.db")
    with closing(sqlite3.connect(snapshot)) as conn:
        assert conn.execute("SELECT count(*) FROM owner").fetchone()[0] == 1
    with closing(connect(tmp_path / DB_NAME)) as conn:
        assert schema_version(conn) == LATEST + 1
        assert conn.execute("SELECT count(*) FROM extra").fetchone()[0] == 0


def test_a_failing_migration_changes_nothing(tmp_path, monkeypatch):
    migrate(tmp_path / DB_NAME, tmp_path / "pre")
    broken = "CREATE TABLE extra (v); INSERT INTO missing VALUES (1);"
    monkeypatch.setattr(migrations, "MIGRATIONS", [*migrations.MIGRATIONS, broken])
    monkeypatch.setattr(migrations, "LATEST", LATEST + 1)

    with pytest.raises(sqlite3.OperationalError):
        migrate(tmp_path / DB_NAME, tmp_path / "pre")

    with closing(connect(tmp_path / DB_NAME)) as conn:
        assert schema_version(conn) == LATEST
        tables = {row[0] for row in conn.execute("SELECT name FROM sqlite_master")}
    assert "extra" not in tables


def test_a_database_from_a_newer_release_is_refused(tmp_path):
    migrate(tmp_path / DB_NAME, tmp_path / "pre")
    with closing(connect(tmp_path / DB_NAME)) as conn:
        conn.execute("UPDATE meta SET value = '2027.3.0' WHERE key = 'migrated_by'")
        conn.execute(f"PRAGMA user_version = {LATEST + 1}")
    with pytest.raises(OperatorError, match=r"upgraded by Spunto 2027\.3\.0 .* Run 2027\.3\.0"):
        migrate(tmp_path / DB_NAME, tmp_path / "pre")


async def test_optional_event_metadata_is_committed_and_rollback_is_silent(tmp_path):
    store = await Store.open(tmp_path / DB_NAME)
    events = []

    async def sink(event, note_id=None, note_version=None, **metadata):
        events.append((event, note_id, note_version, metadata))

    store.event_sink = sink
    try:
        with pytest.raises(RuntimeError):
            async with store.write():
                store.emit(
                    "note.purged",
                    "synthetic",
                    2,
                    private=False,
                    machine=False,
                    origin="owner",
                    change_seq=1,
                )
                raise RuntimeError("rollback")
        await store.drain_events()
        assert not events
        async with store.write():
            store.emit(
                "note.purged",
                "synthetic",
                2,
                private=False,
                machine=False,
                origin="owner",
                change_seq=1,
            )
        await store.drain_events()
        assert events == [
            (
                "note.purged",
                "synthetic",
                2,
                {"private": False, "machine": False, "origin": "owner", "change_seq": 1},
            )
        ]
        with pytest.raises(ValueError, match="never note content"):
            async with store.write():
                store.emit("note.saved", "synthetic", 2, body="must never enter metadata")
    finally:
        await store.close()


async def test_legacy_event_sink_keeps_its_original_call_signature(tmp_path):
    store = await Store.open(tmp_path / DB_NAME)
    events = []

    async def sink(event, note_id=None, note_version=None):
        events.append((event, note_id, note_version))

    store.event_sink = sink
    try:
        async with store.write():
            store.emit("attachment.added", "synthetic", 1, attachment_id="attachment")
        await store.drain_events()
        assert events == [("attachment.added", "synthetic", 1)]
    finally:
        await store.close()
