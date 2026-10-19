"""The SQLite file, opened the way 02 §3.2 says."""

import asyncio
import logging
import sqlite3
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from inspect import Parameter, signature
from pathlib import Path

import aiosqlite

DB_NAME = "spunto.db"
READERS = 4
log = logging.getLogger(__name__)

# WAL lets readers run beside the one writer. synchronous=FULL puts every commit
# on disk before the server answers, so a phone never clears its outbox for a
# write that a power cut could undo.
_PRAGMAS = """
PRAGMA busy_timeout = 5000;
PRAGMA journal_mode = WAL;
PRAGMA synchronous = FULL;
PRAGMA foreign_keys = ON;
"""


def now_ms() -> int:
    """The server clock as stored: integer milliseconds since the epoch, UTC (02 §4.1)."""
    return time.time_ns() // 1_000_000


def connect(path: Path) -> sqlite3.Connection:
    """A blocking connection, for startup, the command line and snapshots."""
    conn = sqlite3.connect(path, autocommit=True)
    conn.row_factory = sqlite3.Row
    _load_vec(conn)
    conn.executescript(_PRAGMAS)
    return conn


def _vec_path() -> str:
    import sqlite_vec

    return sqlite_vec.loadable_path()


def _load_vec(conn: sqlite3.Connection) -> bool:
    try:
        path = _vec_path()
        conn.enable_load_extension(True)
        try:
            conn.load_extension(path)
        finally:
            conn.enable_load_extension(False)
        return True
    except ImportError, AttributeError, OSError, sqlite3.Error:
        log.warning("vector_extension_unavailable")
        return False


async def _load_vec_async(conn: aiosqlite.Connection) -> bool:
    try:
        path = _vec_path()
        await conn.enable_load_extension(True)
        try:
            await conn.load_extension(path)
        finally:
            await conn.enable_load_extension(False)
        return True
    except ImportError, AttributeError, OSError, sqlite3.Error:
        log.warning("vector_extension_unavailable")
        return False


async def _statement(conn: aiosqlite.Connection, sql: str) -> None:
    """A cancelled caller still waits for SQLite's queued transaction command."""
    task = asyncio.create_task(conn.execute(sql))
    try:
        await asyncio.shield(task)
    except asyncio.CancelledError:
        # SQLite's worker cannot cancel an enqueued BEGIN/COMMIT. Join it before
        # checking transaction state or returning this connection to the pool.
        await task
        raise


class Store:
    """One writer connection behind a lock, plus a few readers that never wait for it."""

    def __init__(self, writer: aiosqlite.Connection, readers: list[aiosqlite.Connection]):
        self._writer = writer
        self._write_lock = asyncio.Lock()
        self._readers: asyncio.Queue[aiosqlite.Connection] = asyncio.Queue()
        for reader in readers:
            self._readers.put_nowait(reader)
        self._connections = [writer, *readers]
        self.event_sink = None
        self.change_event = asyncio.Event()
        self._events = None
        self._dispatch_tasks: set[asyncio.Task] = set()

    def emit(
        self, event: str, note_id: str | None = None, note_version: int | None = None, **metadata
    ) -> None:
        if self._events is None:
            raise RuntimeError("Events must be recorded inside a write transaction")
        if set(metadata) - {"attachment_id", "private", "machine", "origin", "change_seq"}:
            raise ValueError("Event metadata must be bounded source flags, never note content")
        if any(
            type(metadata[name]) is not bool for name in ("private", "machine") if name in metadata
        ):
            raise ValueError("Event eligibility flags must be boolean")
        if "change_seq" in metadata and (
            type(metadata["change_seq"]) is not int or metadata["change_seq"] < 1
        ):
            raise ValueError("Event sequence must be positive")
        if any(
            not isinstance(metadata[name], str) or len(metadata[name]) > 200
            for name in ("attachment_id", "origin")
            if name in metadata
        ):
            raise ValueError("Event source identifiers must be bounded")
        self._events.append((event, note_id, note_version, metadata))

    def _dispatched(self, task: asyncio.Task) -> None:
        self._dispatch_tasks.discard(task)
        if not task.cancelled() and task.exception() is not None:
            log.error("hook_enqueue_failed")

    async def drain_events(self) -> None:
        while self._dispatch_tasks:
            tasks = tuple(self._dispatch_tasks)
            await asyncio.gather(*tasks, return_exceptions=True)
            # Python can finish gather immediately for already-completed tasks,
            # before their scheduled cleanup callbacks get an event-loop turn.
            self._dispatch_tasks.difference_update(tasks)

    @classmethod
    async def open(cls, path: Path, readers: int = READERS) -> Store:
        connections = []
        vector_available = True
        for _ in range(readers + 1):
            conn = await aiosqlite.connect(path, autocommit=True)
            conn.row_factory = sqlite3.Row
            vector_available = await _load_vec_async(conn) and vector_available
            await conn.executescript(_PRAGMAS)
            connections.append(conn)
        store = cls(connections[0], connections[1:])
        store.vector_available = vector_available
        return store

    @asynccontextmanager
    async def write(self) -> AsyncIterator[aiosqlite.Connection]:
        """A short write transaction. Never hold it across a network call."""
        events = []
        committed = False
        try:
            async with self._write_lock:
                try:
                    await _statement(self._writer, "BEGIN IMMEDIATE")
                    self._events = events
                    yield self._writer
                    commit = asyncio.create_task(self._writer.execute("COMMIT"))
                    try:
                        await asyncio.shield(commit)
                    except asyncio.CancelledError:
                        await commit
                        committed = True
                        raise
                    committed = True
                except BaseException:
                    if self._writer.in_transaction:
                        await _statement(self._writer, "ROLLBACK")
                    raise
                finally:
                    self._events = None
        finally:
            if committed:
                self.change_event.set()
                if self.event_sink:
                    parameters = signature(self.event_sink).parameters
                    accepts_optional = any(
                        p.kind == Parameter.VAR_KEYWORD for p in parameters.values()
                    )
                    for event, note_id, version, metadata in events:
                        optional = (
                            metadata
                            if accepts_optional
                            else {k: v for k, v in metadata.items() if k in parameters}
                        )
                        task = asyncio.create_task(
                            self.event_sink(
                                event, note_id=note_id, note_version=version, **optional
                            )
                        )
                        self._dispatch_tasks.add(task)
                        task.add_done_callback(self._dispatched)

    @asynccontextmanager
    async def read(self) -> AsyncIterator[aiosqlite.Connection]:
        conn = await self._readers.get()
        try:
            await _statement(conn, "BEGIN")
            yield conn
        finally:
            try:
                if conn.in_transaction:
                    await _statement(conn, "ROLLBACK")
            finally:
                self._readers.put_nowait(conn)

    async def close(self) -> None:
        await self.drain_events()
        for conn in self._connections:
            await conn.close()
