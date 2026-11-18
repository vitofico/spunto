"""Columnless application INSERTs must mean the same row on both backends."""

from contextlib import closing

from spunto.store.db import connect
from spunto.store.migrations import migrate
from spunto.store.postgres import _COLUMNS, _PRIMARY_KEYS


def test_translator_column_order_matches_sqlite_schema(tmp_path):
    db = tmp_path / "spunto.db"
    migrate(db, tmp_path / "snapshots")
    with closing(connect(db)) as conn:
        for table, columns in _COLUMNS.items():
            info = conn.execute(f"PRAGMA table_info({table})").fetchall()  # noqa: S608
            assert [row[1] for row in info] == columns, table
            keys = [row[1] for row in sorted(info, key=lambda r: r[5]) if row[5]]
            assert keys == _PRIMARY_KEYS[table], table
