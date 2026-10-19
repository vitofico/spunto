"""Numbered SQL migrations, tracked with PRAGMA user_version (02 §3.4)."""

import sqlite3
from contextlib import closing
from datetime import UTC, datetime
from importlib.resources import files
from pathlib import Path

from spunto import __version__
from spunto.errors import OperatorError
from spunto.store.db import connect
from spunto.store.snapshot import write_snapshot


def _load() -> list[str]:
    scripts = sorted(
        (f for f in files(__package__).joinpath("sql").iterdir() if f.name.endswith(".sql")),
        key=lambda f: f.name,
    )
    for number, script in enumerate(scripts, start=1):
        if not script.name.startswith(f"{number:04d}_"):
            raise RuntimeError(f"Migration {script.name} is out of sequence; expected {number:04d}")
    return [script.read_text() for script in scripts]


MIGRATIONS = _load()
LATEST = len(MIGRATIONS)


def migrate(db_path: Path, snapshot_dir: Path) -> int:
    """Bring the database to LATEST and return the version it started at.

    A database that already holds data is snapshotted first, so a failed or
    regretted upgrade can be undone with `spunto restore` (HOST-5).
    """
    with closing(connect(db_path)) as conn:
        current = schema_version(conn)
        if current > LATEST:
            written_by = conn.execute("SELECT value FROM meta WHERE key = 'migrated_by'").fetchone()
            newer = written_by["value"] if written_by else "a newer release"
            raise OperatorError(
                f"This database was upgraded by Spunto {newer} (schema {current}), and this is "
                f"Spunto {__version__}, which knows schema {LATEST}. Run {newer} or newer."
            )
        if current == LATEST:
            return current
        if current > 0:
            write_snapshot(conn, snapshot_dir, datetime.now(UTC))
        for version in range(current + 1, LATEST + 1):
            _apply(conn, version)
    return current


def schema_version(conn: sqlite3.Connection) -> int:
    return conn.execute("PRAGMA user_version").fetchone()[0]


def _apply(conn: sqlite3.Connection, version: int) -> None:
    conn.execute("BEGIN IMMEDIATE")
    try:
        conn.executescript(MIGRATIONS[version - 1])
        conn.execute(f"PRAGMA user_version = {version}")
        conn.execute(
            "INSERT INTO meta (key, value) VALUES ('migrated_by', ?)"
            " ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            (__version__,),
        )
        conn.execute("COMMIT")
    except BaseException:
        conn.execute("ROLLBACK")
        raise
