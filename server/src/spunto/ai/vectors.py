"""Core-owned, route-coherent SQLite vectors; network calls never hold the writer."""

import asyncio
import hashlib
import json
import math
import re
import struct
from collections import Counter, OrderedDict
from contextlib import asynccontextmanager

from markdown_it import MarkdownIt

from spunto.ai.errors import AIError
from spunto.store.db import now_ms

_MARKDOWN = MarkdownIt("commonmark", {"html": False})
_KEY = re.compile(r"[a-f0-9]{64}\Z")


class VectorUnavailable(RuntimeError):
    code = "vectors_unavailable"

    def __init__(self):
        super().__init__(self.code)


def table_name(key):
    # Only locally generated fingerprints enter SQL identifiers.
    if not isinstance(key, str) or not _KEY.fullmatch(key):
        raise ValueError("Invalid vector model key")
    return "chunk_vec_" + key


def chunks(body, extra=""):
    def plain(value):
        output = []
        for token in _MARKDOWN.parse(value):
            if token.type in {"fence", "code_block"}:
                output.append(token.content.rstrip())
            elif token.type == "inline":
                output.append(
                    "".join(
                        child.content if child.type not in {"softbreak", "hardbreak"} else "\n"
                        for child in token.children or []
                        if child.type in {"text", "code_inline", "image", "softbreak", "hardbreak"}
                    )
                )
        return re.sub(r"(?<!\w)#(?=\w)", "", "\n\n".join(output)).strip()

    text = "\n\n".join(filter(None, (plain(body), plain(extra))))
    if not text:
        return []
    if len(text) <= 1500:
        return [text]
    paragraphs = text.split("\n\n")
    result, group = [], []
    for paragraph in paragraphs:
        if group and len("\n\n".join([*group, paragraph])) > 1000:
            result.append("\n\n".join(group))
            group = [group[-1]] if len(group) > 1 else []
        group.append(paragraph)
    if group:
        result.append("\n\n".join(group))
    return result


def vector_blob(vector, dimension):
    if len(vector) != dimension or not dimension:
        raise ValueError("embedding_dimension_mismatch")
    if any(type(value) not in {int, float} or not math.isfinite(value) for value in vector):
        raise ValueError("embedding_invalid")
    norm = math.sqrt(sum(float(value) ** 2 for value in vector))
    if not norm or not math.isfinite(norm):
        raise ValueError("embedding_invalid")
    return struct.pack("<" + "f" * dimension, *(value / norm for value in vector))


def fingerprint(profile, dimension):
    descriptor = {
        "base_url": profile.base_url,
        "embed_model": profile.embed_model,
        "embed_query_prefix": profile.embed_query_prefix,
        "embed_document_prefix": profile.embed_document_prefix,
        "dimension": dimension,
    }
    encoded = json.dumps(descriptor, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode()).hexdigest(), encoded


async def purge_note_vectors(conn, note_id, *, key=None):
    rows = await conn.execute_fetchall(
        "SELECT id,model_key FROM chunk WHERE note_id=? AND (? IS NULL OR model_key=?)",
        (note_id, key, key),
    )
    for row in rows:
        await conn.execute(
            f"DELETE FROM {table_name(row['model_key'])} WHERE rowid=?",  # noqa: S608
            (row["id"],),
        )
    await conn.execute(
        "DELETE FROM chunk WHERE note_id=? AND (? IS NULL OR model_key=?)",
        (note_id, key, key),
    )
    await conn.execute(
        "DELETE FROM embedding_note WHERE note_id=? AND (? IS NULL OR model_key=?)",
        (note_id, key, key),
    )


class VectorIndex:
    def __init__(self, store, gateway):
        self.store, self.gateway = store, gateway
        self.routes = {}
        self.models = {}
        self._queries = OrderedDict()
        self.enqueue = None
        self.generation = 0
        self._lock = asyncio.Lock()
        self._users = Counter()

    @property
    def enabled(self):
        return bool(self.routes) and self.store.vector_available

    def profile_for(self, plugin_id):
        return self.routes.get(plugin_id)

    async def active_key(self, profile=None):
        routes = await self._routes(profile)
        return (routes[0]["active_key"] or routes[0]["target_key"]) if routes else None

    def _check(self, generation, guard=None):
        if not self.enabled or generation != self.generation:
            raise VectorUnavailable()
        if guard:
            guard()

    async def _model(self, key, profile=None):
        if key in self.models:
            stored, dimension = self.models[key]
            connection = self.gateway.embedding_profile(profile or stored.name)
            if connection.base_url != stored.base_url:
                raise VectorUnavailable()
            # A shared model fingerprint never shares another profile's key,
            # rate budget or timeout. Reuse only its model/prefix descriptor.
            return connection.model_copy(
                update={
                    field: getattr(stored, field)
                    for field in ("embed_model", "embed_query_prefix", "embed_document_prefix")
                }
            ), dimension
        async with self.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT * FROM embedding_model WHERE model_key=?", (key,)
            )
        if not rows:
            raise VectorUnavailable()
        row = rows[0]
        current = self.gateway.embedding_profile(profile or row["profile"])
        descriptor = json.loads(row["descriptor"])
        # Never reuse credentials for a different endpoint after configuration changes.
        if current.base_url != descriptor["base_url"]:
            raise VectorUnavailable()
        dimension = descriptor.pop("dimension")
        profile = current.model_copy(update=descriptor)
        self.models[key] = profile, dimension
        return profile, dimension

    def set_routes(self, routes, *, force=False):
        """Fence membership immediately, without waiting for model traffic or the writer."""
        desired = dict(routes) if self.store.vector_available else {}
        changed = desired != self.routes
        if changed or force:
            self.generation += 1
            self.routes = desired
        return changed

    async def sync_routes(self, routes):
        """Explicit model refresh for CLI/tests; host preparation runs as a leased job."""
        self.set_routes(routes, force=True)
        await self.prepare_routes()

    async def prepare_routes(self):
        """Probe dimensions and prepare/backfill only the current fenced membership."""
        generation = self.generation
        if not self.routes:
            return
        async with self._lock:
            if not self.routes:
                return
            self._check(generation)
            for name in sorted(set(self.routes.values())):
                profile = self.gateway.embedding_profile(name)
                dimension = await self.gateway.dimension(profile=profile)
                self._check(generation)
                if type(dimension) is not int or not 1 <= dimension <= 8192:
                    raise ValueError("embedding_dimension_invalid")
                key, descriptor = fingerprint(profile, dimension)
                self.models[key] = profile, dimension
                async with self.store.write() as conn:
                    self._check(generation)
                    await conn.execute(
                        "INSERT OR IGNORE INTO embedding_model VALUES (?,?,?,?,?)",
                        (key, name, descriptor, dimension, now_ms()),
                    )
                    await conn.execute(
                        f"CREATE VIRTUAL TABLE IF NOT EXISTS {table_name(key)} "  # noqa: S608
                        f"USING vec0(embedding float[{dimension}] distance_metric=cosine)"
                    )
                    await conn.execute(
                        "INSERT INTO embedding_route(profile,active_key,target_key) "
                        "VALUES (?,NULL,?) "
                        "ON CONFLICT(profile) DO UPDATE SET target_key=excluded.target_key",
                        (name, key),
                    )
                    self._check(generation)
            await self.backlog(generation=generation)

    async def _snapshot(self, note_id):
        async with self.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT * FROM note WHERE id=? AND private=0 AND machine=0 AND trashed_at IS NULL",
                (note_id,),
            )
            if not rows:
                return None
            extra = await conn.execute_fetchall(
                "SELECT t.text FROM note_text t JOIN plugin p ON p.id=t.plugin_id "
                "WHERE t.note_id=? AND p.state='enabled' AND p.switch='on' "
                "ORDER BY p.position,p.id",
                (note_id,),
            )
        note = dict(rows[0])
        attached = "\n\n".join(r[0] for r in extra)
        note["_embedding_extra_hash"] = hashlib.sha256(attached.encode()).hexdigest()
        texts = await asyncio.to_thread(chunks, note["body"], attached)
        text_hash = hashlib.sha256(json.dumps(texts, ensure_ascii=False).encode()).hexdigest()
        return note, texts, text_hash

    async def _routes(self, profile=None):
        names = {profile} if profile is not None else set(self.routes.values())
        async with self.store.read() as conn:
            rows = await conn.execute_fetchall("SELECT * FROM embedding_route")
        return [dict(row) for row in rows if row["profile"] in names]

    async def index_note(self, note_id, profile=None, *, guard=None, priority=0):
        await self.index_notes([note_id], profile=profile, guard=guard, priority=priority)

    @asynccontextmanager
    async def _lease_routes(self, profile=None):
        # Pin descriptors/tables atomically with route selection. The writer is
        # released before chunking, inference or querying; profiles run in parallel.
        names = set(self.routes.values())
        if profile is not None:
            names.intersection_update({profile})
        keys = set()
        try:
            async with self.store.write() as conn:
                routes = [
                    dict(row)
                    for row in await conn.execute_fetchall("SELECT * FROM embedding_route")
                    if row["profile"] in names
                ]
                keys = {
                    key
                    for route in routes
                    for key in (route["active_key"], route["target_key"])
                    if key
                }
                self._users.update(keys)
            yield routes
        finally:
            self._users.subtract(keys)
            self._users += Counter()  # Remove zero counters without an await.
            async with self.store.write() as conn:
                await self._retire(conn)

    async def index_notes(self, note_ids, profile=None, *, guard=None, priority=0):
        generation = self.generation
        self._check(generation, guard)
        async with self._lease_routes(profile) as routes:
            self._check(generation, guard)
            await self._index_notes(note_ids, routes, guard=guard, priority=priority)

    async def _index_notes(self, note_ids, routes, *, guard=None, priority=0):
        """Validate every batch across this lease before writing any note's chunks."""
        if not 1 <= len(note_ids) <= 32:
            raise ValueError("embedding_note_batch_invalid")
        generation = self.generation
        self._check(generation, guard)
        snapshots = []
        for note_id in dict.fromkeys(note_ids):
            snapshot = await self._snapshot(note_id)
            if snapshot is not None:
                snapshots.append(snapshot)
        plans = []
        for route in routes:
            for key in dict.fromkeys(filter(None, (route["target_key"], route["active_key"]))):
                try:
                    prepared = await self._model_plans(
                        snapshots, key, route["profile"], generation, guard, priority
                    )
                except (AIError, VectorUnavailable) as exc:
                    self._check(generation, guard)
                    historical = bool(route["target_key"]) and key != route["target_key"]
                    if not historical or (
                        isinstance(exc, AIError) and exc.category not in {"provider", "unavailable"}
                    ):
                        raise
                    # A removed historical model must not prevent its replacement
                    # from backfilling. Keep that active index unchanged until the
                    # complete target can replace it; working old models still update.
                    continue
                plans.extend(prepared)
        # No transaction or chunk write precedes validation of the entire lease.
        for plan in plans:
            note, key = plan["note"], plan["key"]
            latest = await self._snapshot(note["id"])
            self._check(generation, guard)
            if latest is None or latest[2] != plan["text_hash"]:
                continue
            async with self.store.write() as conn:
                self._check(generation, guard)
                current = await conn.execute_fetchall(
                    "SELECT body_hash,private,machine,trashed_at FROM note WHERE id=?",
                    (note["id"],),
                )
                if (
                    not current
                    or current[0]["body_hash"] != note["body_hash"]
                    or current[0]["private"]
                    or current[0]["machine"]
                    or current[0]["trashed_at"] is not None
                ):
                    continue
                extra = await conn.execute_fetchall(
                    "SELECT t.text FROM note_text t JOIN plugin p ON p.id=t.plugin_id "
                    "WHERE t.note_id=? AND p.state='enabled' AND p.switch='on' "
                    "ORDER BY p.position,p.id",
                    (note["id"],),
                )
                if (
                    hashlib.sha256("\n\n".join(r[0] for r in extra).encode()).hexdigest()
                    != note["_embedding_extra_hash"]
                ):
                    continue
                if plan["unchanged"]:
                    await conn.execute(
                        "UPDATE embedding_note SET body_hash=? WHERE note_id=? AND model_key=?",
                        (note["body_hash"], note["id"], key),
                    )
                    self._check(generation, guard)
                    continue
                await purge_note_vectors(conn, note["id"], key=key)
                for ordinal, (text, blob) in enumerate(
                    zip(plan["texts"], plan["vectors"], strict=True)
                ):
                    rows = await conn.execute_fetchall(
                        "INSERT INTO chunk(note_id,ordinal,text_hash,model_key) "
                        "VALUES(?,?,?,?) RETURNING id",
                        (note["id"], ordinal, hashlib.sha256(text.encode()).hexdigest(), key),
                    )
                    await conn.execute(
                        f"INSERT INTO {table_name(key)}(rowid,embedding) VALUES (?,?)",  # noqa: S608
                        (rows[0][0], blob),
                    )
                await conn.execute(
                    "INSERT INTO embedding_note VALUES (?,?,?,?)",
                    (note["id"], key, plan["text_hash"], note["body_hash"]),
                )
                self._check(generation, guard)
        await self._activate(generation=generation, guard=guard)

    async def _model_plans(self, snapshots, key, profile, generation, guard, priority):
        model, dimension = await self._model(key, profile)
        plans, pending = [], []
        for note, texts, text_hash in snapshots:
            async with self.store.read() as conn:
                existing = await conn.execute_fetchall(
                    "SELECT text_hash FROM embedding_note WHERE note_id=? AND model_key=?",
                    (note["id"], key),
                )
            plan = {
                "note": note,
                "texts": texts,
                "text_hash": text_hash,
                "key": key,
                "vectors": [],
                "unchanged": bool(existing and existing[0][0] == text_hash),
            }
            plans.append(plan)
            if not plan["unchanged"]:
                pending.extend((plan, text) for text in texts)
        for start in range(0, len(pending), 32):
            self._check(generation, guard)
            batch = pending[start : start + 32]
            notes = {plan["note"]["id"]: plan["note"] for plan, _ in batch}
            result = await self.gateway.embed(
                [text for _, text in batch],
                kind="document",
                notes=list(notes.values()),
                profile=model,
                plugin_id="core",
                priority=priority,
                check=lambda: self._check(generation, guard),
            )
            self._check(generation, guard)
            if len(result) != len(batch):
                raise ValueError("embedding_batch_invalid")
            for (plan, _), vector in zip(batch, result, strict=True):
                plan["vectors"].append(vector_blob(vector, dimension))
        return plans

    async def changed(self, note_id):
        if self.enabled and self.enqueue:
            async with self.store.read() as conn:
                rows = await conn.execute_fetchall(
                    "SELECT source_kind,version FROM note WHERE id=? AND private=0 "
                    "AND machine=0 AND trashed_at IS NULL",
                    (note_id,),
                )
            if rows:
                priority = (
                    5 if rows[0]["source_kind"] == "import" and rows[0]["version"] == 1 else 0
                )
                await self.enqueue(note_id, priority=priority)

    async def backlog(self, *, generation=None):
        if not self.enabled:
            return
        generation = self.generation if generation is None else generation
        self._check(generation)
        async with self.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT id FROM note WHERE private=0 AND machine=0 AND trashed_at IS NULL"
            )
        if self.enqueue:
            for row in rows:
                self._check(generation)
                await self.enqueue(row[0], priority=5)
        if not rows:
            await self._activate(generation=generation)

    async def backfill(self):
        """CLI/test helper; production backlog is dispatched through leased core jobs."""
        generation = self.generation
        async with self.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT id FROM note WHERE private=0 AND machine=0 AND trashed_at IS NULL"
            )
        for start in range(0, len(rows), 32):
            self._check(generation)
            await self.index_notes([row[0] for row in rows[start : start + 32]], priority=5)
        await self._activate(generation=generation)

    async def _activate(self, *, generation=None, guard=None):
        if not self.enabled:
            return
        generation = self.generation if generation is None else generation
        self._check(generation, guard)
        async with self.store.write() as conn:
            self._check(generation, guard)
            for route in await conn.execute_fetchall("SELECT * FROM embedding_route"):
                self._check(generation, guard)
                if route["profile"] not in self.routes.values():
                    continue
                missing = await conn.execute_fetchall(
                    "SELECT 1 FROM note n WHERE n.private=0 AND n.machine=0 "
                    "AND n.trashed_at IS NULL AND NOT EXISTS(SELECT 1 FROM embedding_note e "
                    "WHERE e.note_id=n.id AND e.model_key=? AND e.body_hash=n.body_hash) LIMIT 1",
                    (route["target_key"],),
                )
                self._check(generation, guard)
                if missing:
                    continue
                await conn.execute(
                    "UPDATE embedding_route SET active_key=target_key WHERE profile=?",
                    (route["profile"],),
                )
                self._check(generation, guard)
                await conn.execute(
                    "INSERT INTO meta(key,value) VALUES('active_embedding_index',?) "
                    "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                    (route["target_key"],),
                )
                self._check(generation, guard)
            await self._retire(conn)
            self._check(generation, guard)

    async def _retire(self, conn):
        used = {
            value
            for row in await conn.execute_fetchall(
                "SELECT active_key,target_key FROM embedding_route"
            )
            for value in row
            if value
        } | set(self._users)
        for row in await conn.execute_fetchall("SELECT model_key FROM embedding_model"):
            if row[0] not in used:
                key = row[0]
                await conn.execute(f"DROP TABLE IF EXISTS {table_name(key)}")  # noqa: S608
                await conn.execute("DELETE FROM chunk WHERE model_key=?", (key,))
                await conn.execute("DELETE FROM embedding_note WHERE model_key=?", (key,))
                await conn.execute("DELETE FROM embedding_model WHERE model_key=?", (key,))
                self.models.pop(key, None)

    async def nearest(
        self,
        note_id=None,
        text=None,
        k=50,
        *,
        profile=None,
        external_ok=True,
        guard=None,
        plugin_id="core",
    ):
        generation = self.generation
        self._check(generation, guard)
        name = profile or next(iter(self.routes.values()))
        async with self._lease_routes(name) as routes:
            self._check(generation, guard)
            if not routes:
                raise VectorUnavailable()
            return await self._nearest(
                note_id,
                text,
                k,
                profile=name,
                external_ok=external_ok,
                guard=guard,
                plugin_id=plugin_id,
            )

    async def _nearest(
        self,
        note_id=None,
        text=None,
        k=50,
        *,
        profile=None,
        external_ok=True,
        guard=None,
        plugin_id="core",
    ):
        if (note_id is None) == (text is None) or type(k) is not int or not 1 <= k <= 100:
            raise ValueError("invalid_vector_query")
        generation = self.generation
        self._check(generation, guard)
        name = profile or next(iter(self.routes.values()))
        if name not in self.routes.values():
            raise VectorUnavailable()
        routes = await self._routes(name)
        if not routes:
            raise VectorUnavailable()
        route = routes[0]
        key = route["active_key"] or route["target_key"]
        model, dimension = await self._model(key, name)
        family = None
        if note_id is not None:
            note = await self._snapshot(note_id)
            if note is None or (not external_ok and note[0]["origin"] == "external"):
                raise VectorUnavailable()
            family = note[0]["conflict_of"] or note_id
            await self.index_note(note_id, profile=name, guard=guard)
            self._check(generation, guard)
            # On-demand indexing may have completed the model switch and dropped
            # the old table. Read the now-active descriptor before reading vectors.
            routes = await self._routes(name)
            self._check(generation, guard)
            if not routes:
                raise VectorUnavailable()
            route = routes[0]
            key = route["active_key"] or route["target_key"]
            async with self.store.read() as conn:
                self._check(generation, guard)
                vectors = [
                    row[0]
                    for row in await conn.execute_fetchall(
                        f"SELECT v.embedding FROM {table_name(key)} v JOIN chunk c ON c.id=v.rowid "  # noqa: S608
                        "WHERE c.note_id=? AND c.model_key=? ORDER BY c.ordinal",
                        (note_id, key),
                    )
                ]
        else:
            if not isinstance(text, str) or not text.strip() or len(text) > 100000:
                raise ValueError("invalid_vector_query")
            cache_key = key, hashlib.sha256(text.encode()).digest()
            cached = self._queries.get(cache_key)
            if cached is None:
                result = await self.gateway.embed(
                    [text],
                    kind="query",
                    profile=model,
                    plugin_id=plugin_id,
                    priority=0,
                    check=lambda: self._check(generation, guard),
                )
                self._check(generation, guard)
                if len(result) != 1:
                    raise ValueError("embedding_batch_invalid")
                cached = vector_blob(result[0], dimension)
                self._queries[cache_key] = cached
                if len(self._queries) > 128:
                    self._queries.popitem(last=False)
            else:
                self._queries.move_to_end(cache_key)
            vectors = [cached]
        routes = await self._routes(name)
        self._check(generation, guard)
        if not routes:
            raise VectorUnavailable()
        current = routes[0]
        if (current["active_key"] or current["target_key"]) != key:
            return await self.nearest(
                note_id=note_id,
                text=text,
                k=k,
                profile=name,
                external_ok=external_ok,
                guard=guard,
                plugin_id=plugin_id,
            )
        found = {}
        async with self.store.read() as conn:
            self._check(generation, guard)
            if note_id is not None:
                eligible = await conn.execute_fetchall(
                    "SELECT conflict_of FROM note WHERE id=? AND private=0 AND machine=0 "
                    "AND trashed_at IS NULL AND (? OR origin!='external')",
                    (note_id, int(external_ok)),
                )
                if not eligible:
                    raise VectorUnavailable()
                family = eligible[0][0] or note_id
            for vector in vectors:
                rows = await conn.execute_fetchall(
                    f"SELECT n.id,v.distance FROM {table_name(key)} v "  # noqa: S608
                    "JOIN chunk c ON c.id=v.rowid JOIN note n ON n.id=c.note_id "
                    "WHERE v.embedding MATCH ? AND k=? AND c.model_key=? "
                    "AND n.private=0 AND n.machine=0 AND n.trashed_at IS NULL "
                    "AND (? OR n.origin!='external') "
                    "AND (? IS NULL OR (n.id!=? AND n.id!=? "
                    "AND (n.conflict_of IS NULL OR n.conflict_of!=?)))",
                    (
                        vector,
                        max(k, 20) if note_id else k,
                        key,
                        int(external_ok),
                        note_id,
                        note_id,
                        family,
                        family,
                    ),
                )
                for row in rows:
                    found[row["id"]] = max(found.get(row["id"], -1), 1.0 - row["distance"])
        self._check(generation, guard)
        return [
            {"id": id, "score": score}
            for id, score in sorted(found.items(), key=lambda item: (-item[1], item[0]))[:k]
        ]

    async def reindex(self, *, drop=False):
        if drop:
            self.generation += 1
            async with self.store.write() as conn:
                for row in await conn.execute_fetchall("SELECT model_key FROM embedding_model"):
                    if self._users[row[0]]:
                        # In-flight generations are invalid, but their SQLite
                        # descriptors survive until their final lease exits.
                        await conn.execute(f"DELETE FROM {table_name(row[0])}")  # noqa: S608
                    else:
                        await conn.execute(f"DROP TABLE IF EXISTS {table_name(row[0])}")  # noqa: S608
                await conn.execute("DELETE FROM chunk")
                await conn.execute("DELETE FROM embedding_note")
                await conn.execute("DELETE FROM embedding_route")
                await self._retire(conn)
                await conn.execute("DELETE FROM meta WHERE key='active_embedding_index'")
            self.models.clear()
            self._queries.clear()
        if self.enabled:
            await self.sync_routes(self.routes)

    async def status(self):
        async with self.store.read() as conn:
            routes = [
                dict(row) for row in await conn.execute_fetchall("SELECT * FROM embedding_route")
            ]
            count = (await conn.execute_fetchall("SELECT count(*) FROM chunk"))[0][0]
            total = (
                await conn.execute_fetchall(
                    "SELECT count(*) FROM note WHERE private=0 AND machine=0 AND trashed_at IS NULL"
                )
            )[0][0]
            for route in routes:
                route["total"] = total
                route["indexed"] = (
                    await conn.execute_fetchall(
                        "SELECT count(*) FROM embedding_note e JOIN note n ON n.id=e.note_id "
                        "WHERE e.model_key=? AND e.body_hash=n.body_hash AND n.private=0 "
                        "AND n.machine=0 AND n.trashed_at IS NULL",
                        (route["target_key"],),
                    )
                )[0][0]
        return {
            "available": self.store.vector_available,
            "enabled": self.enabled,
            "chunks": count,
            "routes": routes,
        }
