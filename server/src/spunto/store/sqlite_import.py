"""Offline, verified SQLite snapshot import into an empty hosted personal space."""

import hashlib
import json
import math
import re
import sqlite3
import stat
import struct
from contextlib import closing
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote

import psycopg
from psycopg import sql

from spunto.errors import OperatorError
from spunto.store.db import _load_vec, now_ms
from spunto.store.migrations import LATEST, MIGRATIONS
from spunto.store.postgres import _COLUMNS, PostgresStore

_FTS = ("rowid", "title", "body", "extra")
_VECTOR = ("model_key", "chunk_id", "embedding")
_ROWIDS = {"note", "suggestion"}
_TABLES = tuple(_COLUMNS) + ("note_fts", "chunk_vector")
_IDENTITIES = (
    ("note", "rowid"),
    ("suggestion", "rowid"),
    ("label", "id"),
    ("job", "id"),
    ("chunk", "id"),
)
_HEX = re.compile(r"[a-f0-9]{64}")


@dataclass(frozen=True)
class TableAudit:
    count: int
    source_sha256: str
    imported_sha256: str


@dataclass(frozen=True)
class AttachmentFile:
    sha256: str
    size: int
    relative_path: str


@dataclass(frozen=True)
class ImportReport:
    schema_version: int
    snapshot_sha256: str
    instance_id: str
    sync_epoch: str
    tables: dict[str, TableAudit]
    attachments: tuple[AttachmentFile, ...]
    revoked_browser_sessions: int
    expired_pairing_grants: int
    sequence_reconciliation_required: bool = True


def _fingerprint(row):
    values = [{"bytes": value.hex()} if isinstance(value, bytes) else value for value in row]
    encoded = json.dumps(
        values, ensure_ascii=False, allow_nan=False, separators=(",", ":")
    ).encode()
    return hashlib.sha256(encoded).digest()


def _digest(hashes):
    return hashlib.sha256(b"".join(sorted(hashes))).hexdigest()


def _source_identity(path):
    info = path.stat()
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns


def _file_hash(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _catalog(connection):
    return {
        (kind, name, table): " ".join(statement.split()) if statement else None
        for kind, name, table, statement in connection.execute(
            "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE substr(name,1,7)<>'sqlite_'"
        )
    }


def _validate_source(source):
    if source.execute("PRAGMA user_version").fetchone()[0] != LATEST:
        raise OperatorError("The snapshot must use exactly the supported SQLite schema version.")
    if [tuple(row) for row in source.execute("PRAGMA quick_check")] != [("ok",)]:
        raise OperatorError("The snapshot failed SQLite integrity verification.")
    if source.execute("PRAGMA foreign_key_check").fetchone() is not None:
        raise OperatorError("The snapshot failed SQLite foreign-key verification.")
    with closing(sqlite3.connect(":memory:")) as expected:
        for migration in MIGRATIONS:
            expected.executescript(migration)
        vectors = []
        catalog = _catalog(source)
        for (kind, name, _), statement in catalog.items():
            if (
                kind != "table"
                or not statement
                or not statement.upper().startswith("CREATE VIRTUAL TABLE")
            ):
                continue
            if name == "note_fts":
                continue
            if not name.startswith("chunk_vec_") or not _HEX.fullmatch(name[10:]):
                raise OperatorError("The snapshot contains an unsupported SQLite virtual table.")
            if not vectors and not _load_vec(expected):
                raise OperatorError("SQLite vector support is required to verify this snapshot.")
            key = name[10:]
            model = source.execute(
                "SELECT dimension FROM embedding_model WHERE model_key=?", (key,)
            ).fetchone()
            if not model or not isinstance(model[0], int) or not 1 <= model[0] <= 8192:
                raise OperatorError("The snapshot contains an unsupported vector model.")
            dimension = model[0]
            expected.execute(
                f"CREATE VIRTUAL TABLE {name} "  # noqa: S608 - validated hexadecimal identifier
                f"USING vec0(embedding float[{dimension}] distance_metric=cosine)"
            )
            vectors.append((name, key, dimension))
        if catalog != _catalog(expected):
            raise OperatorError(
                "The snapshot schema contains changed or unsupported SQLite objects."
            )
    return vectors


def _columns(table):
    if table == "note_fts":
        return _FTS
    if table == "chunk_vector":
        return _VECTOR
    return tuple(_COLUMNS[table]) + (("rowid",) if table in _ROWIDS else ())


def _rows(source, table):
    # Identifiers are fixed application schema, never source values.
    return source.execute(f"SELECT {','.join(_columns(table))} FROM {table}")  # noqa: S608


def _manifest(source):
    result = {}
    for sha, size in source.execute("SELECT sha256,size FROM attachment"):
        if (
            not isinstance(sha, str)
            or not _HEX.fullmatch(sha)
            or not isinstance(size, int)
            or size < 0
        ):
            raise OperatorError("The snapshot contains an unsafe attachment manifest.")
        entry = AttachmentFile(sha, size, f"{sha[:2]}/{sha[2:4]}/{sha}")
        if sha in result and result[sha] != entry:
            raise OperatorError("The snapshot contains inconsistent attachment sizes.")
        result[sha] = entry
    return tuple(result[key] for key in sorted(result))


async def _verify_table(connection, table, expected):
    projection = sql.SQL(",").join(map(sql.Identifier, _columns(table)))
    if table == "chunk_vector":
        projection = sql.SQL("model_key,chunk_id,embedding::text")
    cursor = await connection.execute(
        sql.SQL("SELECT {} FROM spunto.{}").format(projection, sql.Identifier(table))
    )
    hashes = []
    async for row in cursor:
        if table == "chunk_vector":
            values = json.loads(row[2], parse_int=float)
            row = (*row[:2], struct.pack("<" + "f" * len(values), *values))
        hashes.append(_fingerprint(row))
    if len(hashes) != len(expected) or _digest(hashes) != _digest(expected):
        raise OperatorError("The imported table failed row-count or digest verification.")


async def _target_is_empty(connection):
    rows = await (
        await connection.execute(
            "SELECT table_name,column_name FROM information_schema.columns "
            "WHERE table_schema='spunto'"
        )
    ).fetchall()
    actual = {}
    for table, column in rows:
        actual.setdefault(table, set()).add(column)
    expected = {
        table: set(_columns(table))
        | {"workspace_id"}
        | ({"document"} if table == "note_fts" else set())
        for table in _TABLES
    }
    if actual != expected:
        raise OperatorError("The target contains an unsupported PostgreSQL domain schema.")
    for table in _TABLES:
        row = await (
            await connection.execute(
                sql.SQL("SELECT 1 FROM spunto.{} LIMIT 1").format(sql.Identifier(table))
            )
        ).fetchone()
        if row is not None:
            raise OperatorError("The target personal workspace is not empty; import was refused.")


async def _copy(source, connection, vectors, imported_at):
    audits, expected = {}, {}
    for table in _COLUMNS:
        columns = _columns(table)
        override = sql.SQL(" OVERRIDING SYSTEM VALUE") if table in _ROWIDS else sql.SQL("")
        statement = sql.SQL("INSERT INTO spunto.{} ({}){} VALUES ({})").format(
            sql.Identifier(table),
            sql.SQL(",").join(map(sql.Identifier, columns)),
            override,
            sql.SQL(",").join(sql.Placeholder() for _ in columns),
        )
        before, after, conflicts = [], [], []
        for source_row in _rows(source, table):
            before.append(_fingerprint(source_row))
            row = list(source_row)
            if table == "credential" and row[columns.index("kind")] == "session":
                index = columns.index("revoked_at")
                row[index] = row[index] if row[index] is not None else imported_at
            if table == "pairing":
                index = columns.index("expires_at")
                row[index] = min(row[index], imported_at)
            after.append(_fingerprint(row))
            if table == "note" and row[columns.index("conflict_of")] is not None:
                conflicts.append((row[columns.index("conflict_of")], row[columns.index("id")]))
                row[columns.index("conflict_of")] = None
            await connection.execute(statement, row)
        for parent, note_id in conflicts:
            await connection.execute(
                "UPDATE spunto.note SET conflict_of=%s WHERE id=%s", (parent, note_id)
            )
        expected[table] = after
        audits[table] = TableAudit(len(before), _digest(before), _digest(after))
    hashes = []
    for row in _rows(source, "note_fts"):
        hashes.append(_fingerprint(row))
        await connection.execute(
            "INSERT INTO spunto.note_fts(rowid,title,body,extra) VALUES(%s,%s,%s,%s)", row
        )
    expected["note_fts"] = hashes
    audits["note_fts"] = TableAudit(len(hashes), _digest(hashes), _digest(hashes))
    hashes = []
    for table, key, dimension in vectors:
        for chunk_id, blob in source.execute(f"SELECT rowid,embedding FROM {table}"):  # noqa: S608
            if not isinstance(blob, bytes) or len(blob) != dimension * 4:
                raise OperatorError("The snapshot contains an invalid vector embedding.")
            values = struct.unpack("<" + "f" * dimension, blob)
            if not all(math.isfinite(value) for value in values):
                raise OperatorError("The snapshot contains a non-finite vector embedding.")
            hashes.append(_fingerprint((key, chunk_id, blob)))
            await connection.execute(
                "INSERT INTO spunto.chunk_vector(model_key,chunk_id,embedding) "
                "VALUES(%s,%s,%s::spunto.vector)",
                (key, chunk_id, json.dumps(values)),
            )
    expected["chunk_vector"] = hashes
    audits["chunk_vector"] = TableAudit(len(hashes), _digest(hashes), _digest(hashes))
    for table in _TABLES:
        await _verify_table(connection, table, expected[table])
    return audits


async def import_sqlite_snapshot(
    source: Path,
    database_url: str,
    workspace_id: str,
    user_id: str,
    *,
    identity_database_url: str,
) -> ImportReport:
    """Import once. Stop hosted writers and reconcile sequences before restarting.

    Target must already exist as a personal workspace with exactly its mapped owner
    membership, and have no domain rows, including initial owner/meta rows. All
    imported domain rows and token locators commit in one transaction. No files
    are copied, no source schema is upgraded, and no identity is created or linked.
    """
    store = None
    try:
        path = source.resolve(strict=True)  # noqa: ASYNC240 - offline operator command
        if not stat.S_ISREG(path.stat().st_mode):
            raise OperatorError("The source must be a regular SQLite snapshot file.")
        wal = path.with_name(path.name + "-wal")
        if wal.exists() and wal.stat().st_size:
            raise OperatorError(
                "Use a standalone SQLite snapshot, not a database with an active WAL."
            )
        source_identity = _source_identity(path)
        snapshot_hash = _file_hash(path)
        if _source_identity(path) != source_identity:
            raise OperatorError("The source snapshot changed while it was being opened.")
        uri = "file:" + quote(str(path), safe="/") + "?mode=ro&immutable=1"
        with closing(sqlite3.connect(uri, uri=True, autocommit=True)) as snapshot:
            _load_vec(snapshot)
            snapshot.execute("PRAGMA query_only=ON")
            snapshot.execute("BEGIN")
            vectors = _validate_source(snapshot)
            manifest = _manifest(snapshot)
            meta = dict(snapshot.execute("SELECT key,value FROM meta"))
            if not all(
                isinstance(meta.get(key), str) and meta[key]
                for key in ("instance_id", "sync_epoch")
            ):
                raise OperatorError("The snapshot is missing its instance or sync identity.")
            revoked = snapshot.execute(
                "SELECT count(*) FROM credential WHERE kind='session'"
            ).fetchone()[0]
            pairings = snapshot.execute("SELECT count(*) FROM pairing").fetchone()[0]
            async with await psycopg.AsyncConnection.connect(
                identity_database_url, connect_timeout=10
            ) as identity:
                target = await (
                    await identity.execute(
                        "SELECT w.personal,m.role FROM public.spunto_workspace w "
                        "JOIN public.spunto_membership m ON m.workspace_id=w.id "
                        "WHERE w.id=%s AND m.user_id=%s AND w.created_by=%s FOR SHARE OF w,m",
                        (workspace_id, user_id, user_id),
                    )
                ).fetchone()
                members = await (
                    await identity.execute(
                        "SELECT count(*) FROM public.spunto_membership WHERE workspace_id=%s",
                        (workspace_id,),
                    )
                ).fetchone()
                if target != (True, "owner") or members[0] != 1:
                    raise OperatorError(
                        "Import requires a personal workspace owned only by the mapped account."
                    )
                for table in ("spunto_credential_locator", "spunto_pairing", "spunto_note_share"):
                    occupied = await (
                        await identity.execute(
                            sql.SQL("SELECT 1 FROM public.{} WHERE workspace_id=%s LIMIT 1").format(
                                sql.Identifier(table)
                            ),
                            (workspace_id,),
                        )
                    ).fetchone()
                    if occupied is not None:
                        raise OperatorError(
                            "The target workspace is not empty; access metadata already exists."
                        )
                store = await PostgresStore.open(database_url)
                scoped = store.for_workspace(workspace_id, user_id, "owner")
                async with scoped.write() as domain:
                    await _target_is_empty(domain._connection)
                    audits = await _copy(snapshot, domain._connection, vectors, now_ms())
                    if (
                        (wal.exists() and wal.stat().st_size)
                        or _source_identity(path) != source_identity
                        or _file_hash(path) != snapshot_hash
                        or _source_identity(path) != source_identity
                        or (wal.exists() and wal.stat().st_size)
                    ):
                        raise OperatorError(
                            "The source snapshot changed during import; import was rolled back."
                        )
            return ImportReport(
                LATEST,
                snapshot_hash,
                meta["instance_id"],
                meta["sync_epoch"],
                audits,
                manifest,
                revoked,
                pairings,
            )
    except OperatorError:
        raise
    except OSError, sqlite3.Error, psycopg.Error, ValueError, TypeError, KeyError:
        raise OperatorError(
            "SQLite import failed; verify the snapshot, target and database permissions."
        ) from None
    finally:
        if store is not None:
            await store.close()


async def reconcile_import_sequences(admin_url: str) -> dict[str, int]:
    """Offline operator gate: transactionally move identity sequences beyond all imported IDs."""
    result = {}
    try:
        async with await psycopg.AsyncConnection.connect(
            admin_url, connect_timeout=10
        ) as connection:
            role = await (
                await connection.execute(
                    "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user"
                )
            ).fetchone()
            if not role or not role[0]:
                raise OperatorError(
                    "Sequence reconciliation requires an operator role with full workspace access."
                )
            for table, _ in _IDENTITIES:
                await connection.execute(
                    sql.SQL("LOCK TABLE spunto.{} IN ACCESS EXCLUSIVE MODE").format(
                        sql.Identifier(table)
                    )
                )
            for table, column in _IDENTITIES:
                sequence = await (
                    await connection.execute(
                        "SELECT n.nspname,c.relname FROM pg_class c "
                        "JOIN pg_namespace n ON n.oid=c.relnamespace "
                        "WHERE c.oid=pg_get_serial_sequence(%s,%s)::regclass",
                        ("spunto." + table, column),
                    )
                ).fetchone()
                if not sequence or sequence[0] != "spunto":
                    raise OperatorError("A required PostgreSQL identity sequence is unavailable.")
                maximum = (
                    await (
                        await connection.execute(
                            sql.SQL("SELECT coalesce(max({}),0) FROM spunto.{}").format(
                                sql.Identifier(column),
                                sql.Identifier(table),
                            )
                        )
                    ).fetchone()
                )[0]
                last, called = await (
                    await connection.execute(
                        sql.SQL("SELECT last_value,is_called FROM {}.{}").format(
                            sql.Identifier(sequence[0]),
                            sql.Identifier(sequence[1]),
                        )
                    )
                ).fetchone()
                next_value = max(maximum + 1, last + int(called), 1)
                if next_value > 2**63 - 1:
                    raise OperatorError(
                        "An imported identity exceeds the PostgreSQL sequence range."
                    )
                await connection.execute(
                    sql.SQL("ALTER SEQUENCE {}.{} RESTART WITH {}").format(
                        sql.Identifier(sequence[0]),
                        sql.Identifier(sequence[1]),
                        sql.Literal(next_value),
                    )
                )
                result[table + "." + column] = next_value
        return result
    except OperatorError:
        raise
    except psycopg.Error:
        raise OperatorError(
            "PostgreSQL sequence reconciliation failed; keep hosted writers stopped."
        ) from None


def verify_attachment_files(directory: Path, manifest: tuple[AttachmentFile, ...]) -> int:
    """Verify an externally copied content-addressed tree without modifying files."""
    try:
        root = directory.resolve(strict=True)
        for entry in manifest:
            if (
                not _HEX.fullmatch(entry.sha256)
                or entry.size < 0
                or entry.relative_path != f"{entry.sha256[:2]}/{entry.sha256[2:4]}/{entry.sha256}"
            ):
                raise OperatorError("The attachment manifest is unsafe.")
            path = (root / entry.relative_path).resolve(strict=True)
            path.relative_to(root)
            if (
                not path.is_file()
                or path.stat().st_size != entry.size
                or _file_hash(path) != entry.sha256
            ):
                raise OperatorError("An attachment file failed size or checksum verification.")
        return len(manifest)
    except OperatorError:
        raise
    except OSError, ValueError, TypeError:
        raise OperatorError(
            "The copied attachment tree is missing, unreadable or unsafe."
        ) from None
