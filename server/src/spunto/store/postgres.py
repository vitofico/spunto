"""Shared PostgreSQL pool, transaction-local workspace context and forced RLS.

The adapter translates only SQL produced by the trusted application. Caller values
remain parameters. No tenant can provide SQL or pick a database or schema.
"""

import asyncio
import contextvars
import json
import re
import struct
import uuid
from collections.abc import AsyncIterator, Sequence
from contextlib import asynccontextmanager
from importlib.resources import files
from inspect import Parameter, signature

import psycopg
from psycopg import sql
from psycopg_pool import AsyncConnectionPool

from spunto.errors import OperatorError
from spunto.store.db import Store, now_ms

# Original column order for trusted, columnless application INSERTs.
_COLUMNS = {
    "meta": ["key", "value"],
    "owner": ["id", "password_hash", "password_source", "created_at"],
    "credential": [
        "id",
        "kind",
        "name",
        "client",
        "scope",
        "external_ok",
        "private_ok",
        "secret_hash",
        "created_at",
        "expires_at",
        "last_used_at",
        "revoked_at",
        "last_sync_at",
    ],
    "note": [
        "id",
        "body",
        "title",
        "body_hash",
        "version",
        "change_seq",
        "created_at",
        "updated_at",
        "pinned",
        "archived_at",
        "trashed_at",
        "private",
        "pinned_clock",
        "archived_clock",
        "trashed_clock",
        "private_clock",
        "open_tasks",
        "source_kind",
        "source_ref",
        "source_url",
        "origin",
        "machine",
        "created_by",
        "updated_by",
        "conflict_of",
        "import_key",
    ],
    "tombstone": ["kind", "id", "change_seq", "deleted_at"],
    "note_revision": ["note_id", "version", "body", "change_kind", "changed_by", "changed_at"],
    "label": ["id", "key", "path", "created_at"],
    "note_label": ["note_id", "label_id"],
    "attachment": ["id", "sha256", "mime", "size", "filename", "width", "height", "created_at"],
    "note_attachment": ["note_id", "attachment_id"],
    "applied_op": ["credential_id", "op_id", "result", "applied_at"],
    "plugin": [
        "id",
        "version",
        "kind",
        "switch",
        "state",
        "pinned_by_env",
        "granted",
        "settings",
        "ai_route",
        "position",
        "data_version",
        "last_run_at",
        "last_error",
        "manifest_snapshot",
        "origin",
        "source_kind",
        "source_identity",
    ],
    "plugin_kv": ["plugin_id", "key", "value"],
    "plugin_record": ["plugin_id", "collection", "id", "note_id", "key", "data", "updated_at"],
    "suggestion": [
        "id",
        "plugin_id",
        "note_id",
        "note_version",
        "action",
        "payload",
        "confidence",
        "reason",
        "status",
        "model",
        "change_seq",
        "created_at",
        "decided_at",
        "decided_by",
    ],
    "annotation": ["plugin_id", "note_id", "kind", "payload", "change_seq", "updated_at"],
    "note_text": ["plugin_id", "note_id", "text", "updated_at"],
    "job": [
        "id",
        "plugin_id",
        "kind",
        "note_id",
        "args",
        "priority",
        "status",
        "attempts",
        "run_after",
        "lease_until",
        "last_error",
        "created_at",
        "updated_at",
        "run_receipt",
    ],
    "setting": ["key", "value"],
    "embedding_model": ["model_key", "profile", "descriptor", "dimension", "created_at"],
    "embedding_route": ["profile", "active_key", "target_key"],
    "chunk": ["id", "note_id", "ordinal", "text_hash", "model_key"],
    "embedding_note": ["note_id", "model_key", "text_hash", "body_hash"],
    "ai_stat": [
        "day",
        "plugin_id",
        "capability",
        "outcome",
        "n",
        "input_tokens",
        "output_tokens",
    ],
    "pairing": ["code_hash", "credential_id", "created_at", "expires_at"],
}
_PRIMARY_KEYS = {
    "meta": ["key"],
    "owner": ["id"],
    "credential": ["id"],
    "note": ["id"],
    "tombstone": ["kind", "id"],
    "note_revision": ["note_id", "version"],
    "label": ["id"],
    "note_label": ["note_id", "label_id"],
    "attachment": ["id"],
    "note_attachment": ["note_id", "attachment_id"],
    "applied_op": ["credential_id", "op_id"],
    "plugin": ["id"],
    "plugin_kv": ["plugin_id", "key"],
    "plugin_record": ["plugin_id", "collection", "id"],
    "suggestion": ["id"],
    "annotation": ["plugin_id", "note_id", "kind"],
    "note_text": ["plugin_id", "note_id"],
    "job": ["id"],
    "setting": ["key"],
    "embedding_model": ["model_key"],
    "embedding_route": ["profile"],
    "chunk": ["id"],
    "embedding_note": ["note_id", "model_key"],
    "ai_stat": ["day", "plugin_id", "capability", "outcome"],
    "pairing": ["code_hash"],
}


def _qmarks(statement: str) -> str:
    """Leave quoted SQL literals intact when converting parameter markers."""
    parts = re.split(r"('(?:''|[^'])*'|\"(?:\"\"|[^\"])*\")", statement)
    return "".join(part.replace("?", "%s") if i % 2 == 0 else part for i, part in enumerate(parts))


def _fts_query(value: str) -> str:
    """Convert the core's quoted FTS terms, phrases and final prefix to tsquery."""
    terms = []
    for match in re.finditer(r'"([^"]*)"(\*)?', value):
        words = re.findall(r"[^\W_]+", match[1], re.UNICODE)
        lexemes = ["'" + word.replace("'", "''") + "'" for word in words]
        if lexemes:
            if match[2]:
                lexemes[-1] += ":*"
            terms.append("(" + " <-> ".join(lexemes) + ")")
    return " & ".join(terms)


def _vector_text(blob) -> str:
    values = struct.unpack("<" + "f" * (len(blob) // 4), blob)
    return "[" + ",".join(str(value) for value in values) + "]"


def _vector_statement(statement: str, args: list):
    """Map the core's trusted fingerprint SQL to one shared pgvector table."""
    match = re.search(r"\bchunk_vec_([a-f0-9]{64})\b", statement)
    if not match:
        return statement, args
    table, key = match[0], match[1]
    if re.fullmatch(
        r"CREATE VIRTUAL TABLE IF NOT EXISTS chunk_vec_[a-f0-9]{64} "
        r"USING vec0\(embedding float\[\d+\] distance_metric=cosine\)",
        statement,
    ):
        return "SELECT 1", []  # The operator migration already created shared vector storage.
    if re.fullmatch(r"DROP TABLE IF EXISTS chunk_vec_[a-f0-9]{64}", statement):
        return "DELETE FROM chunk_vector WHERE model_key=?", [key]
    if statement.startswith("INSERT INTO " + table):
        return (
            "INSERT INTO chunk_vector(chunk_id,embedding,model_key) VALUES(?,CAST(? AS vector),?)",
            [args[0], _vector_text(args[1]), key],
        )
    delete_prefix = "DELETE FROM " + table  # noqa: S608 - validated fingerprint
    if statement.startswith(delete_prefix):
        statement = statement.replace(delete_prefix, "DELETE FROM chunk_vector")
        statement = statement.replace("rowid", "chunk_id")
        if " WHERE " in statement:
            statement += " AND model_key=?"
        else:
            statement += " WHERE model_key=?"
        return statement, [*args, key]
    if "v.embedding MATCH ? AND k=?" in statement:
        statement = statement.replace("v.embedding MATCH ? AND k=? AND ", "")
        statement = statement.replace(
            table + " v",
            "(SELECT chunk_id AS rowid,embedding,embedding <=> CAST(? AS vector) AS distance "
            "FROM chunk_vector WHERE model_key='" + key + "' ORDER BY distance LIMIT ?) v",
        )
        args[0] = _vector_text(args[0])
    else:
        statement = statement.replace(
            table + " v",
            "(SELECT chunk_id AS rowid,embedding FROM chunk_vector WHERE model_key='"  # noqa: S608
            + key
            + "') v",
        )
    return statement, args


def _translate(statement: str, parameters: Sequence = ()) -> tuple[str, list, bool]:
    """Explicit compatibility boundary for Notes and host storage SQL."""
    statement, args = statement.strip().rstrip(";"), list(parameters)
    returning_id = False
    statement, args = _vector_statement(statement, args)
    if statement == "PRAGMA user_version":
        return "SELECT 8 AS user_version", [], False
    if re.match(r"(?is)^(?:CREATE|ALTER|DROP|PRAGMA|ATTACH|DETACH)\b", statement):
        raise ValueError("Schema changes require operator migrations")
    statement = re.sub(r"\bifnull\(", "coalesce(", statement, flags=re.I)
    statement = re.sub(r"\bgroup_concat\(", "string_agg(", statement, flags=re.I)
    statement = re.sub(r"\bAS INTEGER\b", "AS BIGINT", statement, flags=re.I)
    statement = re.sub(
        r"length\(CAST\((\w+) AS BLOB\)\)", r"octet_length(\1)", statement, flags=re.I
    )
    statement = re.sub(r"\((1|0)\)", lambda m: "(TRUE)" if m[1] == "1" else "(FALSE)", statement)
    statement = re.sub(
        r"(?i)(\bWHERE|\bAND|\bOR) (1|0)(?=\s+(?:AND|OR|ORDER|LIMIT)|$)",
        lambda m: m[1] + (" TRUE" if m[2] == "1" else " FALSE"),
        statement,
    )
    statement = re.sub(
        r"value=CAST\(value AS BIGINT\)\+1", "value=(CAST(value AS BIGINT)+1)::text", statement
    )
    statement = statement.replace("? IS NULL", "CAST(? AS TEXT) IS NULL")
    statement = statement.replace("(? OR", "(CAST(? AS BIGINT)<>0 OR")
    statement = re.sub(r"\bIS NOT \?", "IS DISTINCT FROM ?", statement)
    statement = re.sub(r"\bIS \?", "IS NOT DISTINCT FROM ?", statement)
    statement = re.sub(r"json_extract\((\w+),\'\$\.(\w+)\'\)", r"(\1::jsonb->>'\2')", statement)
    statement = statement.replace("min(priority,?)", "least(priority,?)")
    if "note_fts MATCH ?" in statement:
        index = _qmarks(statement.split("note_fts MATCH ?", 1)[0]).count("%s")
        query = _fts_query(args[index])
        args[index] = query
        statement = statement.replace(
            "note_fts MATCH ?", "note_fts.document @@ to_tsquery('simple',?)"
        )
        statement = statement.replace(" AND note_fts.rank MATCH 'bm25(2.0,1.0,0.5)'", "")
        if "note_fts.rank AS score" in statement:
            # FTS5's rank is lower-is-better; negate ts_rank_cd so a score sorts the same way.
            at = _qmarks(statement.split("note_fts.rank AS score", 1)[0]).count("%s")
            args.insert(at, query)
            statement = statement.replace(
                "note_fts.rank AS score",
                "-ts_rank_cd(note_fts.document,to_tsquery('simple',?)) AS score",
            )
        if "ORDER BY note_fts.rank" in statement:
            statement = statement.replace(
                "ORDER BY note_fts.rank",
                "ORDER BY ts_rank_cd(note_fts.document,to_tsquery('simple',?)) DESC,n.id",
            )
            args.append(query)
    insert = re.match(r"(?is)^INSERT( OR (IGNORE|REPLACE))? INTO (\w+)\s*(\([^)]*\))?", statement)
    if insert:
        table, supplied = insert[3], insert[4]
        columns = (
            [s.strip() for s in supplied[1:-1].split(",")] if supplied else _COLUMNS.get(table)
        )
        if columns is None:
            raise ValueError("Unrecognized application INSERT table")
        statement = (
            "INSERT INTO " + table + "(" + ",".join(columns) + ")" + statement[insert.end() :]
        )
        if insert[2] == "IGNORE":
            statement += " ON CONFLICT DO NOTHING"
        elif insert[2] == "REPLACE":
            keys = _PRIMARY_KEYS[table]
            statement += " ON CONFLICT(workspace_id," + ",".join(keys) + ") DO UPDATE SET "
            statement += ",".join(
                name + "=excluded." + name for name in columns if name not in keys
            )
        statement = re.sub(
            r"(?i)ON\s+CONFLICT\s*\((?!workspace_id\b)", "ON CONFLICT(workspace_id,", statement
        )
        if "DO UPDATE SET" in statement:
            before, tail = statement.split("DO UPDATE SET", 1)
            tail = tail.replace("CAST(value AS BIGINT)", "CAST(" + table + ".value AS BIGINT)")
            statement = before + "DO UPDATE SET" + tail
        if table in {"job", "chunk", "label"} and "RETURNING" not in statement.upper():
            statement += " RETURNING id"
            returning_id = True
    return _qmarks(statement), args, returning_id


class Row:
    """sqlite.Row-compatible name/index access and dict(row)."""

    def __init__(self, names, values):
        self._names, self._values = names, tuple(values)

    def keys(self):
        return self._names

    def __getitem__(self, key):
        return self._values[self._names.index(key)] if isinstance(key, str) else self._values[key]

    def __iter__(self):
        return iter(self._values)

    def __len__(self):
        return len(self._values)


class Cursor:
    def __init__(self, cursor, *, lastrowid=None, expose_rowid=False):
        self._cursor, self.lastrowid = cursor, lastrowid
        self.rowcount, self._expose_rowid = cursor.rowcount, expose_rowid

    def _row(self, values):
        if values is None:
            return None
        values = [
            struct.pack("<" + "f" * len(json.loads(value)), *json.loads(value))
            if col.name == "embedding" and isinstance(value, str)
            else value
            for col, value in zip(self._cursor.description, values, strict=True)
        ]
        pairs = [
            (col.name, value)
            for col, value in zip(self._cursor.description, values, strict=True)
            if col.name != "workspace_id" and (col.name != "rowid" or self._expose_rowid)
        ]
        return Row([name for name, _ in pairs], [value for _, value in pairs])

    async def fetchone(self):
        return self._row(await self._cursor.fetchone())

    async def fetchall(self):
        return [self._row(row) for row in await self._cursor.fetchall()]

    async def close(self):
        await self._cursor.close()

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        await self.close()


class Connection:
    """Application dialect adapter; the raw connection stays private."""

    def __init__(self, connection):
        self._connection = connection

    async def execute(self, statement: str, parameters: Sequence = ()) -> Cursor:
        translated, args, returning_id = _translate(statement, parameters)
        try:
            cursor = await self._connection.execute(translated, args or None)
        except psycopg.Error as exc:
            # PostgreSQL DETAIL can contain the entire failing note/credential row.
            # Keep the exception class for callers, but never propagate its values.
            raise type(exc)(f"PostgreSQL operation failed ({exc.sqlstate or 'unknown'}).") from None
        lastrowid = (await cursor.fetchone())[0] if returning_id and cursor.rowcount else None
        expose = bool(re.search(r"(?i)^SELECT\s+(?:\w+\.)?rowid\b", statement))
        return Cursor(cursor, lastrowid=lastrowid, expose_rowid=expose)

    async def execute_fetchall(self, statement: str, parameters: Sequence = ()) -> list[Row]:
        cursor = await self.execute(statement, parameters)
        try:
            return await cursor.fetchall()
        finally:
            await cursor.close()

    async def executemany(self, statement: str, parameters):
        for args in parameters:
            await self.execute(statement, args)


async def migrate_postgres(admin_url: str, runtime_role: str) -> None:
    """Numbered shared migrations run with an operator connection, then close it."""
    migrations = sorted(
        (
            p
            for p in files(__package__).joinpath("postgres_sql").iterdir()
            if p.name.endswith(".sql")
        ),
        key=lambda p: p.name,
    )
    async with await psycopg.AsyncConnection.connect(admin_url) as connection:
        await connection.execute("SELECT pg_advisory_xact_lock(831701846)")
        await connection.execute(
            "CREATE TABLE IF NOT EXISTS public.spunto_schema_migration "
            "(version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)"
        )
        cursor = await connection.execute(
            "SELECT coalesce(max(version),0) FROM public.spunto_schema_migration"
        )
        current = (await cursor.fetchone())[0]
        if current > len(migrations):
            raise OperatorError("SPUNTO_DATABASE_URL points to a newer PostgreSQL schema")
        for version, path in enumerate(migrations, 1):
            if not path.name.startswith(f"{version:04d}_"):
                raise RuntimeError("PostgreSQL migrations are out of sequence")
            if version > current:
                await connection.execute(path.read_text(), prepare=False)
                await connection.execute(
                    "INSERT INTO public.spunto_schema_migration VALUES (%s,%s)", (version, now_ms())
                )
        for command in [
            "GRANT USAGE ON SCHEMA spunto TO {}",
            "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA spunto TO {}",
            "GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA spunto TO {}",
            "GRANT SELECT ON public.spunto_schema_migration TO {}",
        ]:
            await connection.execute(sql.SQL(command).format(sql.Identifier(runtime_role)))


class PostgresStore:
    """A root owns one shared pool; bound handles carry fixed authorized context."""

    backend = "postgres"
    vector_available = True

    def __init__(self, pool: AsyncConnectionPool, *, root=None, context=None):
        self._pool, self._root, self._context = pool, root or self, context
        self._events = contextvars.ContextVar("spunto_pg_events", default=None)
        self.event_sink, self.change_event = None, asyncio.Event()
        self._dispatch_tasks: set[asyncio.Task] = set() if root is None else root._dispatch_tasks

    @classmethod
    async def open(cls, database_url: str, *, admin_url=None, min_size=1, max_size=8):
        if admin_url:
            async with await psycopg.AsyncConnection.connect(database_url) as connection:
                cursor = await connection.execute("SELECT current_user")
                runtime_role = (await cursor.fetchone())[0]
            await migrate_postgres(admin_url, runtime_role)
        pool = AsyncConnectionPool(
            database_url,
            min_size=min_size,
            max_size=max_size,
            kwargs={"autocommit": True},
            open=False,
        )
        try:
            await pool.open(wait=True, timeout=10)
            async with pool.connection() as connection:
                cursor = await connection.execute(
                    "SELECT coalesce(max(version),0) FROM public.spunto_schema_migration"
                )
                expected = len(
                    [
                        p
                        for p in files(__package__).joinpath("postgres_sql").iterdir()
                        if p.name.endswith(".sql")
                    ]
                )
                if (await cursor.fetchone())[0] != expected:
                    raise OperatorError(
                        "SPUNTO_DATABASE_URL requires this release's PostgreSQL schema"
                    )
                cursor = await connection.execute(
                    "SELECT rolsuper,rolbypassrls,rolcreaterole "
                    "FROM pg_roles WHERE rolname=current_user"
                )
                if any(await cursor.fetchone()):
                    raise OperatorError(
                        "SPUNTO_DATABASE_URL requires a restricted PostgreSQL runtime role"
                    )
                cursor = await connection.execute(
                    "SELECT count(*),bool_and(relrowsecurity AND relforcerowsecurity "
                    "AND NOT pg_has_role(current_user,relowner,'MEMBER')) FROM pg_class c "
                    "JOIN pg_namespace n ON n.oid=c.relnamespace "
                    "WHERE n.nspname='spunto' AND c.relkind='r'"
                )
                count, isolated = await cursor.fetchone()
                if count < 27 or not isolated:
                    raise OperatorError(
                        "SPUNTO_DATABASE_URL requires migrated forced-RLS tables "
                        "owned by another role"
                    )
            return cls(pool)
        except BaseException:
            await pool.close()
            raise

    def for_workspace(self, workspace_id: str, user_id: str, role: str):
        if role not in {"owner", "editor", "viewer"} or not workspace_id or not user_id:
            raise ValueError("A workspace, user and valid membership role are required")
        if self._context is not None:
            raise ValueError("A bound store cannot be rebound")
        return type(self)(self._pool, root=self, context=(workspace_id, user_id, role))

    async def initialize_workspace(self, *, created_at: int | None = None):
        """Seed an already-authorized public workspace, idempotently."""
        async with self.write() as connection:
            for key, value in [
                ("instance_id", uuid.uuid4().hex),
                ("sync_epoch", uuid.uuid4().hex[:8]),
                ("change_seq", "0"),
                ("plugins_rev", "0"),
            ]:
                await connection.execute(
                    "INSERT OR IGNORE INTO meta(key,value) VALUES (?,?)", (key, value)
                )
            await connection.execute(
                "INSERT OR IGNORE INTO owner(id,password_hash,password_source,created_at) "
                "VALUES(1,'oidc-only','oidc',?)",
                (created_at if created_at is not None else now_ms(),),
            )

    @asynccontextmanager
    async def _transaction(self, *, readonly: bool):
        if self._context is None:
            raise RuntimeError("Bind an authorized workspace before accessing domain data")
        if not readonly and self._context[2] == "viewer":
            raise PermissionError("Workspace viewers cannot write")
        async with self._pool.connection() as connection:
            async with connection.transaction():
                if readonly:
                    await connection.execute(
                        "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY"
                    )
                await connection.execute("SET LOCAL search_path = pg_catalog, spunto")
                await connection.execute(
                    "SELECT set_config('spunto.workspace_id',%s,true),"
                    "set_config('spunto.user_id',%s,true),"
                    "set_config('spunto.role',%s,true)",
                    self._context,
                )
                # Preserve the short-writer/idempotency contract per workspace, across replicas.
                if not readonly:
                    await connection.execute(
                        "SELECT pg_advisory_xact_lock(hashtextextended(%s,0))", (self._context[0],)
                    )
                yield Connection(connection)

    @asynccontextmanager
    async def read(self) -> AsyncIterator[Connection]:
        async with self._transaction(readonly=True) as connection:
            yield connection

    @asynccontextmanager
    async def write(self) -> AsyncIterator[Connection]:
        events = []
        token = self._events.set(events)
        committed = False
        transaction = self._transaction(readonly=False)
        try:
            connection = await transaction.__aenter__()
            try:
                yield connection
            except BaseException as error:
                rollback = asyncio.create_task(
                    transaction.__aexit__(
                        type(error),
                        error,
                        error.__traceback__,
                    )
                )
                try:
                    await asyncio.shield(rollback)
                except asyncio.CancelledError:
                    await rollback
                raise
            else:
                commit = asyncio.create_task(transaction.__aexit__(None, None, None))
                try:
                    await asyncio.shield(commit)
                except asyncio.CancelledError:
                    await commit
                    committed = True
                    raise
                committed = True
        finally:
            if committed:
                self.change_event.set()
                if self.event_sink:
                    parameters = signature(self.event_sink).parameters
                    accepts = any(p.kind == Parameter.VAR_KEYWORD for p in parameters.values())
                    for event, note_id, version, metadata in events:
                        optional = (
                            metadata
                            if accepts
                            else {k: v for k, v in metadata.items() if k in parameters}
                        )
                        task = asyncio.create_task(
                            self.event_sink(
                                event, note_id=note_id, note_version=version, **optional
                            )
                        )
                        self._dispatch_tasks.add(task)
                        task.add_done_callback(self._dispatched)
            self._events.reset(token)

    def emit(self, event, note_id=None, note_version=None, **metadata):
        class Recorder:
            _events = self._events.get()

        Store.emit(Recorder(), event, note_id, note_version, **metadata)

    _dispatched = Store._dispatched
    drain_events = Store.drain_events

    async def close(self):
        await self.drain_events()
        if self._root is self:
            await self._pool.close()
