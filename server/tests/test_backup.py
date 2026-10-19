import hashlib
import shutil
import sqlite3
import uuid
from contextlib import closing
from datetime import UTC, datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from spunto.errors import OperatorError
from spunto.jobs.backup import PRE_MIGRATION, backup_now, prune, restore, seconds_until
from spunto.store.db import DB_NAME, connect
from spunto.store.lock import DataLock
from spunto.store.migrations import LATEST, MIGRATIONS, migrate
from spunto.store.snapshot import checksum_path, verify_checksum, write_snapshot

NOW = datetime(2026, 10, 13, 3, 30, tzinfo=UTC)


@pytest.fixture
def db(settings):
    settings.data_dir.mkdir(parents=True)
    migrate(settings.data_dir / DB_NAME, settings.backup_dir / PRE_MIGRATION)
    with closing(connect(settings.data_dir / DB_NAME)) as conn:
        conn.execute("INSERT INTO owner VALUES (1, 'first-hash', 'setup', 0)")
        conn.execute(
            "INSERT INTO credential (id, kind, name, scope, created_at) VALUES"
            " ('s1', 'session', 'Firefox', 'full', 0)"
        )
    return settings.data_dir / DB_NAME


def rows(path, sql, args=()):
    with closing(sqlite3.connect(path)) as conn:
        return conn.execute(sql, args).fetchall()


def checksum(path):
    checksum_path(path).write_text(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  x\n")
    return path


def seed_note(conn, body="Snapshot note", created_at=1_760_000_000_000):
    note_id = str(uuid.uuid7())
    sequence = int(conn.execute("SELECT value FROM meta WHERE key='change_seq'").fetchone()[0]) + 1
    conn.execute("UPDATE meta SET value=? WHERE key='change_seq'", (str(sequence),))
    conn.execute(
        "INSERT INTO note(id,body,title,body_hash,version,change_seq,created_at,updated_at,"
        "source_kind,origin,created_by,updated_by)"
        " VALUES (?,?,?,?,1,?,?,?,'api','owner','s1','s1')",
        (
            note_id,
            body,
            body,
            hashlib.sha256(body.encode()).hexdigest(),
            sequence,
            created_at,
            created_at,
        ),
    )
    conn.execute(
        "INSERT INTO note_revision VALUES (?,1,?,'create','s1',?)", (note_id, body, created_at)
    )
    rowid = conn.execute("SELECT rowid FROM note WHERE id=?", (note_id,)).fetchone()[0]
    conn.execute(
        "INSERT INTO note_fts(rowid,title,body,extra) VALUES (?,?,?,'')", (rowid, body, body)
    )
    return note_id


def test_backup_writes_a_named_snapshot_and_checksum(settings, db):
    snapshot = backup_now(settings, NOW)
    assert snapshot == settings.backup_dir / f"spunto-2026-10-13T0330Z-s{LATEST}.db"
    verify_checksum(snapshot)
    assert checksum_path(snapshot).read_text().endswith(f"  {snapshot.name}\n")
    assert rows(snapshot, "SELECT password_hash FROM owner") == [("first-hash",)]
    assert not list(settings.backup_dir.glob(".*partial"))


def test_backup_is_consistent_while_a_write_is_open(settings, db):
    with closing(connect(db)) as writer:
        writer.execute("BEGIN IMMEDIATE")
        writer.execute("UPDATE owner SET password_hash = 'uncommitted'")
        snapshot = backup_now(settings, NOW)
        writer.execute("COMMIT")
    assert rows(snapshot, "SELECT password_hash FROM owner") == [("first-hash",)]
    later = backup_now(settings, NOW + timedelta(minutes=1))
    assert rows(later, "SELECT password_hash FROM owner") == [("uncommitted",)]


def test_backup_needs_a_database(settings):
    with pytest.raises(OperatorError, match="There is no database at"):
        backup_now(settings, NOW)


def test_retention_keeps_daily_and_weekly_snapshots(tmp_path):
    def touch(directory, when):
        directory.mkdir(exist_ok=True)
        path = directory / f"spunto-{when:%Y-%m-%dT%H%M}Z-s1.db"
        path.touch()
        checksum_path(path).touch()
        return path

    # Twice a day for 60 days, ending on Tuesday 2026-10-13.
    taken = [NOW - timedelta(hours=12 * i) for i in range(120)]
    paths = {when: touch(tmp_path, when) for when in taken}
    unrelated = tmp_path / "notes-export.zip"
    unrelated.touch()
    old_pre = touch(tmp_path / PRE_MIGRATION, NOW - timedelta(days=31))
    new_pre = touch(tmp_path / PRE_MIGRATION, NOW - timedelta(days=29))

    prune(tmp_path, keep_daily=7, keep_weekly=4, now=NOW)

    kept = sorted(when for when, path in paths.items() if path.exists())
    # The newest of each of the last seven days, then of each of the four newest weeks,
    # two of which the daily ones already cover, as borg prune counts them.
    daily = [datetime(2026, 10, d, 15, 30, tzinfo=UTC) for d in range(7, 13)] + [NOW]
    weekly = [datetime(2026, 9, 27, 15, 30, tzinfo=UTC), datetime(2026, 10, 4, 15, 30, tzinfo=UTC)]
    assert kept == weekly + daily
    assert all(checksum_path(paths[when]).exists() == (when in kept) for when in taken)
    assert unrelated.exists()
    assert new_pre.exists() and not old_pre.exists()


def test_daily_time_is_local(monkeypatch):
    rome = ZoneInfo("Europe/Rome")  # UTC+2 until the end of October
    assert seconds_until(time(3, 30), rome, datetime(2026, 10, 13, 1, 0, tzinfo=UTC)) == 1800
    assert seconds_until(time(3, 30), rome, datetime(2026, 10, 13, 1, 30, tzinfo=UTC)) == 86400
    # The night the clocks go back has 25 hours.
    assert seconds_until(time(3, 30), rome, datetime(2026, 10, 24, 1, 30, tzinfo=UTC)) == 90000


def test_restore_brings_back_the_snapshot_with_a_new_epoch(settings, db):
    snapshot = backup_now(settings, NOW)
    [(epoch,)] = rows(db, "SELECT value FROM meta WHERE key = 'sync_epoch'")
    with closing(connect(db)) as conn:
        conn.execute("UPDATE owner SET password_hash = 'second-hash'")
        conn.execute("DELETE FROM credential")

    summary = restore(settings, snapshot, NOW + timedelta(hours=1))

    assert rows(db, "SELECT password_hash FROM owner") == [("first-hash",)]
    assert rows(db, "SELECT id FROM credential") == [("s1",)]
    [(new_epoch,)] = rows(db, "SELECT value FROM meta WHERE key = 'sync_epoch'")
    assert new_epoch != epoch
    aside = db.with_name(f"{DB_NAME}.before-restore-2026-10-13T043000Z")
    assert rows(aside, "SELECT password_hash FROM owner") == [("second-hash",)]
    assert f"Restored {snapshot.name}" in summary
    assert str(aside) in summary
    assert f"New sync epoch {new_epoch}" in summary
    assert sorted(p.name for p in db.parent.iterdir() if p.name.startswith(DB_NAME)) == [
        DB_NAME,
        aside.name,
    ]


def test_a_failed_restore_leaves_the_current_database_in_place(settings, db, monkeypatch):
    snapshot = backup_now(settings, NOW)

    def full_disk(source, target):
        raise OSError(28, "No space left on device")

    monkeypatch.setattr(shutil, "copyfile", full_disk)
    with pytest.raises(OSError):
        restore(settings, snapshot)
    assert rows(db, "SELECT password_hash FROM owner") == [("first-hash",)]


def test_failed_installation_keeps_the_current_database(settings, db, monkeypatch):
    snapshot = backup_now(settings, NOW)
    with closing(connect(db)) as conn:
        conn.execute("UPDATE owner SET password_hash = 'current-hash'")
    replace = type(db).replace

    def full_disk(path, target):
        if path.name == f"{DB_NAME}.restoring":
            raise OSError(28, "No space left on device")
        return replace(path, target)

    monkeypatch.setattr(type(db), "replace", full_disk)
    with pytest.raises(OSError, match="No space left on device"):
        restore(settings, snapshot, NOW)
    assert rows(db, "SELECT password_hash FROM owner") == [("current-hash",)]
    [aside] = db.parent.glob(f"{DB_NAME}.before-restore-*")
    assert rows(aside, "SELECT password_hash FROM owner") == [("current-hash",)]


def test_restore_does_not_overwrite_the_previous_database(settings, db):
    snapshot = backup_now(settings, NOW)
    restore(settings, snapshot, NOW)
    [aside] = db.parent.glob(f"{DB_NAME}.before-restore-*")
    with closing(connect(db)) as conn:
        conn.execute("UPDATE owner SET password_hash = 'current-hash'")
    with pytest.raises(OperatorError, match="already exists"):
        restore(settings, snapshot, NOW)
    assert rows(db, "SELECT password_hash FROM owner") == [("current-hash",)]
    assert rows(aside, "SELECT password_hash FROM owner") == [("first-hash",)]


def test_restore_refuses_while_the_server_runs(settings, db):
    snapshot = backup_now(settings, NOW)
    with DataLock(settings.data_dir), pytest.raises(OperatorError, match="Stop it first"):
        restore(settings, snapshot)


def test_restore_refuses_a_changed_snapshot(settings, db):
    snapshot = backup_now(settings, NOW)
    with snapshot.open("r+b") as f:
        f.seek(100)
        f.write(b"\xff")
    with pytest.raises(OperatorError, match="does not match its checksum"):
        restore(settings, snapshot)
    checksum_path(snapshot).unlink()
    with pytest.raises(OperatorError, match="no readable checksum file"):
        restore(settings, snapshot)
    assert rows(db, "SELECT password_hash FROM owner") == [("first-hash",)]


def test_restore_refuses_a_damaged_or_newer_database(settings, db, tmp_path):
    def with_checksum(path):
        import hashlib

        checksum_path(path).write_text(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  x\n")
        return path

    garbage = tmp_path / "garbage.db"
    garbage.write_bytes(b"not a database" * 100)
    with pytest.raises(OperatorError, match="fails SQLite's integrity check"):
        restore(settings, with_checksum(garbage))

    newer = backup_now(settings, NOW)
    with closing(sqlite3.connect(newer)) as conn:
        conn.execute(f"PRAGMA user_version = {LATEST + 1}")
    with pytest.raises(OperatorError, match=f"holds schema {LATEST + 1}, newer than"):
        restore(settings, with_checksum(newer))

    foreign = tmp_path / "foreign.db"
    with closing(sqlite3.connect(foreign)) as conn:
        conn.execute("CREATE TABLE t (x)")
    with pytest.raises(OperatorError, match="foreign.db is not a Spunto snapshot"):
        restore(settings, with_checksum(foreign))

    # Other SQLite applications use user_version too; a nonzero value and a
    # generic meta table must not be enough to replace the active database.
    with closing(sqlite3.connect(foreign)) as conn:
        conn.executescript(
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);"
            " PRAGMA user_version = 1;"
        )
    with pytest.raises(OperatorError, match="foreign.db is not a Spunto snapshot"):
        restore(settings, with_checksum(foreign))
    assert rows(db, "SELECT password_hash FROM owner") == [("first-hash",)]


def test_restore_reports_notes_and_preserves_full_body_and_search(settings, db):
    body = "Snapshot contents stay out of the operator summary #idea"
    with closing(connect(db)) as conn:
        first = seed_note(conn, body, 1_760_000_000_000)
        seed_note(conn, "Newest snapshot note", 1_760_000_100_000)
    snapshot = backup_now(settings, NOW)
    with closing(connect(db)) as conn:
        conn.execute("UPDATE note SET body='Changed after backup' WHERE id=?", (first,))
        seed_note(conn, "Created after backup")
    summary = restore(settings, snapshot, NOW)
    assert rows(db, "SELECT count(*) FROM note") == [(2,)]
    assert rows(db, "SELECT body FROM note WHERE id=?", (first,)) == [(body,)]
    assert rows(db, "SELECT count(*) FROM note_fts WHERE note_fts MATCH 'Snapshot'") == [(2,)]
    assert "Notes restored: 2" in summary
    newest = datetime.fromtimestamp(1_760_000_100_000 / 1000, UTC).isoformat(
        timespec="milliseconds"
    )
    assert newest.replace("+00:00", "Z") in summary
    assert body not in summary and "first-hash" not in summary


def test_restore_reports_only_missing_referenced_files_in_configured_hash_layout(settings, db):
    settings.attachments_dir = db.parent / "custom-content"
    with closing(connect(db)) as conn:
        note_id = seed_note(conn)
        for number, digest in enumerate(("abcd" + "1" * 60, "beef" + "2" * 60, "cafe" + "3" * 60)):
            attachment_id = str(uuid.uuid7())
            conn.execute(
                "INSERT INTO attachment(id,sha256,mime,size,filename,created_at)"
                " VALUES (?,?,?,1,?,0)",
                (attachment_id, digest, "image/png", f"original-{number}.png"),
            )
            if number < 2:
                conn.execute("INSERT INTO note_attachment VALUES (?,?)", (note_id, attachment_id))
    present = settings.attachments_dir / "ab" / "cd" / ("abcd" + "1" * 60)
    present.parent.mkdir(parents=True)
    present.write_bytes(b"image")
    snapshot = backup_now(settings, NOW)
    summary = restore(settings, snapshot, NOW)
    missing = settings.attachments_dir / "be" / "ef" / ("beef" + "2" * 60)
    assert "Missing referenced attachment files (1):" in summary
    assert str(missing) in summary
    assert str(present) not in summary
    assert "cafe" + "3" * 60 not in summary  # unreferenced files do not matter to this restore
    assert (
        "original-" not in summary
    )  # arbitrary client-provided names do not enter operator output


def test_restore_accepts_legacy_schema_one_and_upgrades_on_next_start(settings, db, tmp_path):
    legacy = tmp_path / "legacy.db"
    with closing(connect(legacy)) as conn:
        conn.executescript(MIGRATIONS[0])
        conn.execute("PRAGMA user_version=1")
        conn.execute("INSERT INTO meta VALUES ('migrated_by','0.1.0')")
        conn.execute("INSERT INTO owner VALUES (1,'legacy-hash','setup',0)")
        snapshot = write_snapshot(conn, tmp_path / "legacy-snapshots", NOW)
    summary = restore(settings, snapshot, NOW)
    assert "Notes restored: 0" in summary and "newest created date: none" in summary
    assert f"upgrade it to schema {LATEST}" in summary
    assert rows(db, "PRAGMA user_version") == [(1,)]
    assert rows(db, "SELECT password_hash FROM owner") == [("legacy-hash",)]
    migrate(db, settings.backup_dir / PRE_MIGRATION)
    assert rows(db, "PRAGMA user_version") == [(LATEST,)]
    assert rows(db, "SELECT count(*) FROM note") == [(0,)]


@pytest.mark.parametrize(
    "damage",
    [
        "DROP TABLE note_revision",
        "DROP TABLE label",
        "DROP TABLE note_label",
        "DROP TABLE note_fts",
        "DROP TABLE tombstone",
        "DROP TABLE attachment",
        "DROP TABLE note_attachment",
        "DROP TABLE applied_op",
        "DELETE FROM meta WHERE key='change_seq'",
        "UPDATE meta SET value='invalid' WHERE key='plugins_rev'",
        "ALTER TABLE note DROP COLUMN private_clock",
    ],
)
def test_restore_rejects_incomplete_claimed_schema_two(settings, db, damage):
    snapshot = backup_now(settings, NOW)
    with closing(sqlite3.connect(snapshot)) as conn:
        conn.execute(damage)
        conn.commit()
    with pytest.raises(OperatorError, match="is not a Spunto snapshot"):
        restore(settings, checksum(snapshot), NOW)
    assert rows(db, "SELECT password_hash FROM owner") == [("first-hash",)]
    assert not list(db.parent.glob(f"{DB_NAME}.before-restore-*"))


def test_restore_rejects_forged_schema_two_with_foundation_tables_only(settings, db, tmp_path):
    foreign = tmp_path / "foreign.db"
    with closing(connect(foreign)) as conn:
        conn.executescript(MIGRATIONS[0])
        conn.execute("PRAGMA user_version=2")
        conn.execute("INSERT INTO meta VALUES ('migrated_by','claimed-m1')")
        conn.execute("INSERT INTO meta VALUES ('change_seq','0'),('plugins_rev','0')")
    with pytest.raises(OperatorError, match="is not a Spunto snapshot"):
        restore(settings, checksum(foreign), NOW)
    assert rows(db, "SELECT password_hash FROM owner") == [("first-hash",)]


def test_restore_refuses_note_sequence_ahead_of_global_counter(settings, db):
    with closing(connect(db)) as conn:
        seed_note(conn)
    snapshot = backup_now(settings, NOW)
    with closing(sqlite3.connect(snapshot)) as conn:
        conn.execute("UPDATE meta SET value='0' WHERE key='change_seq'")
        conn.commit()
    with pytest.raises(OperatorError, match="is not a Spunto snapshot"):
        restore(settings, checksum(snapshot), NOW)


def test_restore_cannot_revive_a_spent_pairing_code(settings, db):
    with closing(connect(db)) as conn:
        conn.execute("INSERT INTO pairing VALUES ('synthetic-code-hash','s1',0,9999999999999)")
    snapshot = backup_now(settings, NOW)
    with closing(connect(db)) as conn:
        conn.execute("DELETE FROM pairing")
    restore(settings, snapshot, NOW + timedelta(minutes=1))
    assert rows(db, "SELECT count(*) FROM pairing") == [(0,)]
