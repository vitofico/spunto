"""Plugin-scoped JSON storage, no SQL escapes or plugin-owned tables."""

import json

from spunto.host.manifest import ManifestError


def encoded(value):
    result = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    if len(result.encode()) > 65536:
        raise ManifestError("plugin storage value exceeds 64 KB")
    return result


def _canonical(value):
    encoded(value)
    return json.dumps(
        value, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")
    )


def key(value):
    if (
        not isinstance(value, str)
        or not 1 <= len(value) <= 200
        or value.startswith(("_host_", "host:"))
    ):
        raise ManifestError("storage keys cannot use reserved host namespaces")
    if not isinstance(value, str) or not 1 <= len(value) <= 200:
        raise ManifestError("storage key must contain 1 to 200 characters")
    return value


class KV:
    def __init__(self, ctx):
        self.ctx = ctx

    async def get(self, name, default=None):
        self.ctx.check()
        async with self.ctx._host.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT value FROM plugin_kv WHERE plugin_id=? AND key=?",
                (self.ctx.plugin_id, key(name)),
            )
        self.ctx.check()
        return json.loads(rows[0][0]) if rows else default

    async def set(self, name, value):
        self.ctx.check_write()
        payload = encoded(value)
        async with self.ctx._host.store.write() as conn:
            self.ctx.check()
            await conn.execute(
                "INSERT INTO plugin_kv(plugin_id,key,value) VALUES(?,?,?) ON "
                "CONFLICT(plugin_id,key) DO UPDATE SET value=excluded.value",
                (self.ctx.plugin_id, key(name), payload),
            )
            self.ctx.check()

    async def delete(self, name):
        self.ctx.check_write()
        async with self.ctx._host.store.write() as conn:
            self.ctx.check()
            await conn.execute(
                "DELETE FROM plugin_kv WHERE plugin_id=? AND key=?", (self.ctx.plugin_id, key(name))
            )
            self.ctx.check()


class Records:
    def __init__(self, ctx):
        self.ctx = ctx

    async def put(self, collection, record_id, data, *, note_id=None, key=None):
        self.ctx.check_write()
        payload = encoded(data)
        if key is not None:
            globals()["key"](key)
        async with self.ctx._host.store.write() as conn:
            self.ctx.check()
            await conn.execute(
                "INSERT INTO plugin_record(plugin_id,collection,id,note_id,key,data,"
                "updated_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(plugin_id,collection,id) "
                "DO UPDATE SET note_id=excluded.note_id,key=excluded.key,"
                "data=excluded.data,updated_at=excluded.updated_at",
                (
                    self.ctx.plugin_id,
                    globals()["key"](collection),
                    globals()["key"](record_id),
                    note_id,
                    key,
                    payload,
                    self.ctx._host.clock(),
                ),
            )
            self.ctx.check()

    async def get(self, collection, record_id, default=None):
        self.ctx.check()
        async with self.ctx._host.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT data FROM plugin_record WHERE plugin_id=? AND collection=? AND id=?",
                (self.ctx.plugin_id, key(collection), key(record_id)),
            )
        self.ctx.check()
        return json.loads(rows[0][0]) if rows else default

    async def list(self, collection, *, note_id=None, key=None, limit=100, after=None):
        self.ctx.check()
        if type(limit) is not int or not 1 <= limit <= 1000:
            raise ManifestError("record limit must be 1 to 1000")
        sql = (
            "SELECT id,note_id,key,data,updated_at FROM plugin_record WHERE "
            "plugin_id=? AND collection=?"
        )
        args = [self.ctx.plugin_id, globals()["key"](collection)]
        if after is not None:
            sql += " AND id>?"
            args.append(globals()["key"](after))
        if key is not None:
            globals()["key"](key)
        for column, value in (("note_id", note_id), ("key", key)):
            if value is not None:
                sql += f" AND {column}=?"
                args.append(value)
        async with self.ctx._host.store.read() as conn:
            rows = await conn.execute_fetchall(sql + " ORDER BY id LIMIT ?", (*args, limit))
        self.ctx.check()
        return [{**dict(row), "data": json.loads(row["data"])} for row in rows]

    async def compare_and_set(
        self, collection, record_id, expected_data, data, *, note_id=None, key=None
    ):
        """Replace exact JSON atomically; expected_data=None requires an absent row."""
        self.ctx.check_write()
        collection, record_id = globals()["key"](collection), globals()["key"](record_id)
        payload = encoded(data)
        expected = _canonical(expected_data) if expected_data is not None else None
        if key is not None:
            globals()["key"](key)
        async with self.ctx._host.store.write() as conn:
            self.ctx.check()
            rows = await conn.execute_fetchall(
                "SELECT data FROM plugin_record WHERE plugin_id=? AND collection=? AND id=?",
                (self.ctx.plugin_id, collection, record_id),
            )
            if (not rows) != (expected is None) or (
                rows and _canonical(json.loads(rows[0]["data"])) != expected
            ):
                self.ctx.check()
                return False
            await conn.execute(
                "INSERT INTO plugin_record(plugin_id,collection,id,note_id,key,data,updated_at) "
                "VALUES(?,?,?,?,?,?,?) ON CONFLICT(plugin_id,collection,id) DO UPDATE SET "
                "note_id=excluded.note_id,key=excluded.key,data=excluded.data,"
                "updated_at=excluded.updated_at",
                (
                    self.ctx.plugin_id,
                    collection,
                    record_id,
                    note_id,
                    key,
                    payload,
                    self.ctx._host.clock(),
                ),
            )
            self.ctx.check()
        return True

    async def compare_and_delete(self, collection, record_id, expected_data):
        """Delete only an existing row whose JSON still matches the caller's copy."""
        self.ctx.check_write()
        collection, record_id = globals()["key"](collection), globals()["key"](record_id)
        expected = _canonical(expected_data)
        async with self.ctx._host.store.write() as conn:
            self.ctx.check()
            rows = await conn.execute_fetchall(
                "SELECT data FROM plugin_record WHERE plugin_id=? AND collection=? AND id=?",
                (self.ctx.plugin_id, collection, record_id),
            )
            if not rows or _canonical(json.loads(rows[0]["data"])) != expected:
                self.ctx.check()
                return False
            await conn.execute(
                "DELETE FROM plugin_record WHERE plugin_id=? AND collection=? AND id=?",
                (self.ctx.plugin_id, collection, record_id),
            )
            self.ctx.check()
        return True

    async def delete(self, collection, record_id):
        self.ctx.check_write()
        async with self.ctx._host.store.write() as conn:
            self.ctx.check()
            await conn.execute(
                "DELETE FROM plugin_record WHERE plugin_id=? AND collection=? AND id=?",
                (self.ctx.plugin_id, key(collection), key(record_id)),
            )
            self.ctx.check()


class Storage:
    def __init__(self, ctx):
        self.kv, self.records = KV(ctx), Records(ctx)
