"""Workspace plugin-migration snapshots, format ``spunto-pg-workspace/1``.

A snapshot is a mode-0700 directory containing mode-0600 ``manifest.json``,
``manifest.sha256`` and one PostgreSQL binary COPY file per domain table. The
manifest records the workspace ID, PostgreSQL major version, ordered column
signatures, row counts, byte counts and SHA-256 digests. Generated columns are
recomputed on restore; identity values and pgvector binary values are preserved.
All exports share one forced-RLS REPEATABLE READ transaction. Public account,
membership, session, share and credential-locator tables are never exported.
Attachment metadata is included; the workspace attachment files must be retained
separately. Access grants removed while emptying domain tables are not restored;
explicit note shares must be granted again. These files contain private notes and
plugin settings.

``restore_postgres_snapshot(owner_bound_store, snapshot_directory)`` is an
operator utility, never an automatic rollback or request endpoint. Stop the
workspace's workers and empty its domain tables first. Restore requires the SAME
workspace ID, an exact schema/PostgreSQL-major match, existing identity sequences
above the saved values, and database TEMP permission. It validates the complete
snapshot, then uses temporary binary staging tables and one scoped transaction;
COPY FROM cannot target forced-RLS tables directly. It never changes global
sequence counters or reads public identity tables. Cross-cluster restores with
lower identity sequences require an operator to advance their floors first.

External credentials are restored revoked with their token hashes removed.
Domain pairing rows are cleared; public pairing codes can no longer exchange
because their source domain credentials are revoked. Devices and PAT clients
must authenticate again.
The global OIDC sessions are not restored: a fresh browser login creates a fresh
domain credential. ``sync_epoch`` rotates while ``instance_id`` is retained, so
Android reconciles its retained same-account, same-space outbox against a fresh
replica. Internal plugin credentials and all other domain rows remain intact.
"""

import hashlib
import json
import os
import shutil
import uuid
from datetime import UTC, datetime
from pathlib import Path

import psycopg
from psycopg import sql

from spunto.errors import OperatorError
from spunto.store.db import now_ms

FORMAT = "spunto-pg-workspace/1"
# Foreign-key order; self-referential note links are checked after each INSERT.
TABLES = (
    "meta",
    "owner",
    "credential",
    "note",
    "tombstone",
    "note_revision",
    "label",
    "note_label",
    "attachment",
    "note_attachment",
    "applied_op",
    "plugin",
    "plugin_kv",
    "plugin_record",
    "suggestion",
    "annotation",
    "note_text",
    "job",
    "setting",
    "embedding_model",
    "embedding_route",
    "chunk",
    "embedding_note",
    "ai_stat",
    "pairing",
    "note_fts",
    "chunk_vector",
)
_CHUNK = 65536


def _scope(store):
    context = getattr(store, "_context", None)
    if getattr(store, "backend", None) != "postgres" or not context or context[2] != "owner":
        raise OperatorError("A verified owner-bound PostgreSQL workspace is required.")
    return context[0]


async def _layout(connection):
    rows = await (
        await connection.execute(
            "SELECT c.relname,a.attname,format_type(a.atttypid,a.atttypmod),"
            "a.attgenerated,a.attidentity,a.attnotnull,"
            "pg_get_serial_sequence(quote_ident(n.nspname)||'.'||quote_ident(c.relname),a.attname) "
            "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
            "JOIN pg_attribute a ON a.attrelid=c.oid "
            "WHERE n.nspname='spunto' AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped "
            "ORDER BY c.relname,a.attnum"
        )
    ).fetchall()
    layout = {}
    for table, name, kind, generated, identity, required, sequence in rows:
        layout.setdefault(table, []).append(
            {
                "name": name,
                "type": kind,
                "generated": generated,
                "identity": identity,
                "required": required,
                "sequence": sequence,
            }
        )
    if set(layout) != set(TABLES):
        raise OperatorError("The PostgreSQL domain schema is unsupported by this snapshot format.")
    return layout


def _columns(layout):
    return [column["name"] for column in layout if not column["generated"]]


def _file_name(index, table):
    return f"{index:02d}-{table}.copy"


def _private_file(path):
    return os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb")


def _sync_directory(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


async def _dump_table(connection, table, columns, workspace, path):
    statement = sql.SQL(
        "COPY (SELECT {} FROM spunto.{} WHERE workspace_id=%s) TO STDOUT (FORMAT BINARY)"
    ).format(
        sql.SQL(",").join(map(sql.Identifier, columns)),
        sql.Identifier(table),
    )
    digest, size = hashlib.sha256(), 0
    with _private_file(path) as output:
        async with connection.cursor().copy(statement, (workspace,)) as copy:
            async for block in copy:
                output.write(block)
                digest.update(block)
                size += len(block)
        output.flush()
        os.fsync(output.fileno())
    return digest.hexdigest(), size


async def write_postgres_snapshot(store, directory: Path, now: datetime) -> Path:
    """Atomically publish one private, consistent snapshot of a bound workspace."""
    workspace = _scope(store)
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    target = directory / "workspace.pgsnapshot"
    if target.exists():
        raise OperatorError("This PostgreSQL workspace snapshot already exists.")
    partial = directory / (".workspace.pgsnapshot-" + uuid.uuid4().hex)
    partial.mkdir(mode=0o700)
    try:
        async with store.read() as scoped:
            connection = scoped._connection
            layout = await _layout(connection)
            manifest = {
                "format": FORMAT,
                "workspace_id": workspace,
                "postgres_major": connection.info.server_version // 10000,
                "created_at": now.astimezone(UTC).isoformat(),
                "tables": [],
            }
            for index, table in enumerate(TABLES):
                count = (
                    await (
                        await connection.execute(
                            sql.SQL("SELECT count(*) FROM spunto.{} WHERE workspace_id=%s").format(
                                sql.Identifier(table)
                            ),
                            (workspace,),
                        )
                    ).fetchone()
                )[0]
                name = _file_name(index, table)
                digest, size = await _dump_table(
                    connection,
                    table,
                    _columns(layout[table]),
                    workspace,
                    partial / name,
                )
                manifest["tables"].append(
                    {
                        "table": table,
                        "file": name,
                        "columns": layout[table],
                        "rows": count,
                        "bytes": size,
                        "sha256": digest,
                    }
                )
        content = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode()
        for name, data in (
            ("manifest.json", content),
            (
                "manifest.sha256",
                (hashlib.sha256(content).hexdigest() + "  manifest.json\n").encode(),
            ),
        ):
            with _private_file(partial / name) as output:
                output.write(data)
                output.flush()
                os.fsync(output.fileno())
        _sync_directory(partial)
        partial.rename(target)
        _sync_directory(directory)
        return target
    except BaseException:
        shutil.rmtree(partial, ignore_errors=True)
        raise


def _manifest(directory):
    try:
        content = (directory / "manifest.json").read_bytes()
        if len(content) > 1_048_576:
            raise ValueError
        digest = (directory / "manifest.sha256").read_text().split()[0]
        if hashlib.sha256(content).hexdigest() != digest:
            raise ValueError
        manifest = json.loads(content)
        if manifest["format"] != FORMAT or len(manifest["tables"]) != len(TABLES):
            raise ValueError
        for index, (table, entry) in enumerate(zip(TABLES, manifest["tables"], strict=True)):
            if (
                entry["table"] != table
                or entry["file"] != _file_name(index, table)
                or type(entry["rows"]) is not int
                or entry["rows"] < 0
                or type(entry["bytes"]) is not int
                or entry["bytes"] < 0
            ):
                raise ValueError
            with (directory / entry["file"]).open("rb") as source:
                if (
                    os.fstat(source.fileno()).st_size != entry["bytes"]
                    or hashlib.file_digest(source, "sha256").hexdigest() != entry["sha256"]
                ):
                    raise ValueError
        return manifest
    except OSError, ValueError, KeyError, TypeError, IndexError:
        raise OperatorError("The PostgreSQL workspace snapshot is incomplete or damaged.") from None


async def _stage_table(connection, directory, entry, index, workspace):
    table, temporary = entry["table"], "spunto_restore_" + str(index)
    columns = _columns(entry["columns"])
    names = sql.SQL(",").join(map(sql.Identifier, columns))
    await connection.execute(
        sql.SQL(
            "CREATE TEMP TABLE {} ON COMMIT DROP AS SELECT {} FROM spunto.{} WITH NO DATA"
        ).format(sql.Identifier(temporary), names, sql.Identifier(table))
    )
    digest = hashlib.sha256()
    with (directory / entry["file"]).open("rb") as source:
        async with connection.cursor().copy(
            sql.SQL("COPY pg_temp.{} ({}) FROM STDIN (FORMAT BINARY)").format(
                sql.Identifier(temporary), names
            )
        ) as copy:
            while block := source.read(_CHUNK):
                digest.update(block)
                await copy.write(block)
    if digest.hexdigest() != entry["sha256"]:
        raise OperatorError("The PostgreSQL workspace snapshot changed during restore.")
    counts = await (
        await connection.execute(
            sql.SQL(
                "SELECT count(*),count(*) FILTER (WHERE workspace_id IS DISTINCT FROM %s) "
                "FROM pg_temp.{}"
            ).format(sql.Identifier(temporary)),
            (workspace,),
        )
    ).fetchone()
    if counts != (entry["rows"], 0):
        raise OperatorError("The PostgreSQL workspace snapshot row counts or scope are invalid.")
    for column in entry["columns"]:
        if column["sequence"]:
            maximum = (
                await (
                    await connection.execute(
                        sql.SQL("SELECT max({}) FROM pg_temp.{}").format(
                            sql.Identifier(column["name"]), sql.Identifier(temporary)
                        )
                    )
                ).fetchone()
            )[0]
            if maximum is not None:
                floor, called = await (
                    await connection.execute(
                        sql.SQL("SELECT last_value,is_called FROM {}").format(
                            sql.Identifier(*column["sequence"].split(".", 1))
                        )
                    )
                ).fetchone()
                if maximum > floor or (maximum == floor and not called):
                    raise OperatorError(
                        "An operator must advance PostgreSQL identity sequences before restore."
                    )
    return temporary, names


async def _restore_postgres_snapshot(store, directory: Path) -> dict:
    """Restore only into an empty, authorized same-workspace domain transaction."""
    workspace = _scope(store)
    directory = Path(directory)
    manifest = _manifest(directory)
    if manifest.get("workspace_id") != workspace:
        raise OperatorError(
            "A PostgreSQL workspace snapshot cannot be restored into another workspace."
        )
    async with store.write() as scoped:
        connection = scoped._connection
        layout = await _layout(connection)
        if manifest.get("postgres_major") != connection.info.server_version // 10000 or any(
            entry["columns"] != layout[entry["table"]] for entry in manifest["tables"]
        ):
            raise OperatorError("The PostgreSQL workspace snapshot schema is incompatible.")
        for table in TABLES:
            if (
                await (
                    await connection.execute(
                        sql.SQL("SELECT EXISTS(SELECT 1 FROM spunto.{})").format(
                            sql.Identifier(table)
                        )
                    )
                ).fetchone()
            )[0]:
                raise OperatorError("The PostgreSQL workspace must be empty before restore.")
        staged = []
        for index, entry in enumerate(manifest["tables"]):
            staged.append(await _stage_table(connection, directory, entry, index, workspace))
        # Never recreate another member's credentials with the restoring owner's authority.
        credentials = staged[TABLES.index("credential")][0]
        invalidated = await connection.execute(
            sql.SQL(
                "UPDATE pg_temp.{} SET secret_hash=NULL,revoked_at=%s WHERE kind<>'plugin'"
            ).format(sql.Identifier(credentials)),
            (now_ms(),),
        )
        pairing = staged[TABLES.index("pairing")][0]
        await connection.execute(sql.SQL("DELETE FROM pg_temp.{}").format(sql.Identifier(pairing)))
        counts = {}
        for entry, (temporary, names) in zip(manifest["tables"], staged, strict=True):
            result = await connection.execute(
                sql.SQL(
                    "INSERT INTO spunto.{} ({}) OVERRIDING SYSTEM VALUE SELECT {} FROM pg_temp.{}"
                ).format(sql.Identifier(entry["table"]), names, names, sql.Identifier(temporary))
            )
            counts[entry["table"]] = result.rowcount
        await connection.execute(
            "UPDATE spunto.meta SET value=%s WHERE key='sync_epoch'",
            (uuid.uuid4().hex[:8],),
        )
    return {
        "workspace_id": workspace,
        "tables": counts,
        "external_credentials_invalidated": invalidated.rowcount,
        "pairings_cleared": manifest["tables"][TABLES.index("pairing")]["rows"],
        "sync_epoch_rotated": True,
    }


async def restore_postgres_snapshot(store, directory: Path) -> dict:
    """Restore only an empty, authorized same-workspace domain; errors expose no rows."""
    try:
        return await _restore_postgres_snapshot(store, directory)
    except psycopg.Error:
        raise OperatorError(
            "The PostgreSQL workspace snapshot restore failed and was rolled back."
        ) from None
