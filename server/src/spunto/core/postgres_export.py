"""Stream a scoped PostgreSQL snapshot into the existing portable Markdown exporter."""

import asyncio
import sqlite3
import tempfile

from spunto.core.export import build_zip
from spunto.store.db import DB_NAME
from spunto.store.migrations import migrate


async def build_postgres_zip(services, principal):
    # Only export data is staged: no account, token, plugin or provider secrets.
    with tempfile.TemporaryDirectory(prefix="spunto-export-snapshot-") as temporary:
        from pathlib import Path

        directory = Path(temporary)
        await asyncio.to_thread(migrate, directory / DB_NAME, directory / "backups")
        snapshot = sqlite3.connect(directory / DB_NAME)
        try:
            async with services.store.read() as source:
                for table, order in (
                    ("note", "id"),
                    ("label", "id"),
                    ("attachment", "id"),
                    ("note_label", "note_id,label_id"),
                    ("note_attachment", "note_id,attachment_id"),
                ):
                    columns = [row[1] for row in snapshot.execute(f"PRAGMA table_info({table})")]
                    offset = 0
                    while True:
                        rows = await source.execute_fetchall(
                            f"SELECT {','.join(columns)} FROM {table} "  # noqa: S608
                            f"ORDER BY {order} LIMIT 100 OFFSET ?",
                            (offset,),
                        )
                        if not rows:
                            break
                        snapshot.executemany(
                            f"INSERT INTO {table}({','.join(columns)}) "  # noqa: S608
                            f"VALUES ({','.join('?' for _ in columns)})",
                            [tuple(row[column] for column in columns) for row in rows],
                        )
                        offset += len(rows)
            snapshot.commit()
        finally:
            snapshot.close()
        settings = services.settings.model_copy(update={"data_dir": directory})
        return await asyncio.to_thread(build_zip, settings, principal)
