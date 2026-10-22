"""Generation-bound plugin access to the core's transactional note operations."""

import json
import uuid
from datetime import datetime

from spunto.api.errors import Problem
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes, _body, _cursor, _id, _next, sequence
from spunto.host.plugin import ContextError
from spunto.host.views import compile_filter
from spunto.host.vocabulary import validate_annotation


class PluginNotes:
    def __init__(self, ctx):
        self.ctx = ctx
        self.store = ctx._host.store
        permissions = ctx._host.manifests[ctx.plugin_id]["permissions"]
        self.egress = any(p.startswith(("net.", "ai.")) for p in permissions)
        self.ai = any(p.startswith("ai.") for p in permissions)
        caller = ctx.principal
        self.core = Notes(
            self.store,
            Principal(
                "plugin:" + ctx.plugin_id,
                "plugin",
                ctx.plugin_id,
                "plugin",
                private_ok=not self.egress and (caller is None or caller.private_ok),
                external_ok=caller is None or caller.external_ok,
            ),
        )

    def _write_allowed(self):
        if self.ctx.principal is not None and not self.ctx.principal.writable:
            raise ContextError("read_only_token")

    async def _value(self, conn, row):
        return (await self._values(conn, [row]))[0]

    async def _values(self, conn, rows):
        results = await self.core._serialize_many(conn, rows)
        for result, row in zip(results, rows, strict=True):
            result["import_key"] = row["import_key"]
            self.ctx._borrowed_notes[row["id"]] = row["body"]
        return results

    async def get(self, note_id):
        self.ctx.check("notes.read")
        async with self.store.read() as conn:
            try:
                row = await self.core._row(conn, note_id)
            except Problem as exc:
                if exc.status == 404:
                    self.ctx.check("notes.read")
                    return None
                raise
            result = await self._value(conn, row)
        self.ctx.check("notes.read")
        return result

    async def labels(self, *, limit=500):
        self.ctx.check("notes.read")
        if type(limit) is not int or not 1 <= limit <= 500:
            raise ValueError("label limit must be 1 to 500")
        visibility, _ = self.core._visibility()
        async with self.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT l.path,count(DISTINCT n.id) AS count FROM label l "  # noqa: S608
                "JOIN note_label nl ON nl.label_id=l.id JOIN note n ON n.id=nl.note_id "
                f"WHERE ({visibility}) AND n.trashed_at IS NULL AND n.machine=0 "
                "GROUP BY l.id,l.path,l.key ORDER BY count DESC,l.key LIMIT ?",
                (limit,),
            )
        self.ctx.check("notes.read")
        return {"labels": [dict(row) for row in rows], "total": len(rows)}

    async def search(self, query, *, limit=8, machine=False):
        self.ctx.check("notes.read")
        if machine or type(limit) is not int or not 1 <= limit <= 50:
            raise ValueError("AI search needs eligible notes and a limit of 1 to 50")
        result = await self.core.search(query, limit=limit, machine=False)
        for note in result["hits"]:
            self.ctx._borrowed_notes[note["id"]] = note["body"]
        self.ctx.check("notes.read")
        return result

    async def suggestion_decisions(self, note_id):
        self.ctx.check("suggest")
        async with self.store.read() as conn:
            try:
                await self.core._row(conn, note_id)
            except Problem as exc:
                if exc.status == 404:
                    self.ctx.check("suggest")
                    return []
                raise
            rows = await conn.execute_fetchall(
                "SELECT payload FROM suggestion WHERE plugin_id=? AND note_id=? "
                "AND action='add_label' AND status='dismissed'",
                (self.ctx.plugin_id, note_id),
            )
        self.ctx.check("suggest")
        return list(dict.fromkeys(json.loads(row[0])["path"] for row in rows))

    async def suggestion_history(self, *, cursor=0, limit=100, recent=False):
        """Authoritative, plugin-scoped decisions without exposing other plugins' rows."""
        self.ctx.check("suggest")
        if (
            type(cursor) is not int
            or cursor < 0
            or type(limit) is not int
            or not 1 <= limit <= 500
            or type(recent) is not bool
        ):
            raise ValueError("invalid suggestion history page")
        condition = " AND rowid<?" if recent and cursor else " AND rowid>?" if not recent else ""
        order = "DESC" if recent else "ASC"
        async with self.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT rowid,* FROM suggestion WHERE plugin_id=?"  # noqa: S608
                + condition
                + " ORDER BY rowid "
                + order
                + " LIMIT ?",
                (self.ctx.plugin_id, *([cursor] if condition else []), limit + 1),
            )
        self.ctx.check("suggest")
        return {
            "suggestions": [
                {
                    "id": r["id"],
                    "action": r["action"],
                    "note_id": r["note_id"],
                    "payload": json.loads(r["payload"]),
                    "status": r["status"],
                    "created_ms": r["created_at"],
                }
                for r in rows[:limit]
            ],
            "next_cursor": rows[limit - 1]["rowid"] if len(rows) > limit else None,
        }

    async def _guard_sources(self, conn, versions, *, public_output=False):
        if versions is None:
            return
        if not isinstance(versions, dict) or not 1 <= len(versions) <= 50:
            raise ValueError("source versions must name 1 to 50 notes")
        for note_id, version in versions.items():
            if type(version) is not int or version < 1:
                raise ValueError("source version must be positive")
            row = await self.core._row(conn, note_id)
            if public_output and row["private"]:
                raise ContextError("private_source_requires_private_output")
            if self.egress and (row["machine"] or row["private"]):
                raise ContextError("source_unavailable")
            if row["version"] != version:
                raise Problem(409, "version_conflict", "A source changed; recompute the output.")

    async def suggest_many(
        self,
        note_id,
        items,
        *,
        note_version,
        auto_accept_existing=False,
        threshold=0.9,
        source_versions=None,
    ):
        from spunto.core.suggestions import suggest_many

        self._write_allowed()
        return await suggest_many(
            self.ctx,
            note_id,
            items,
            note_version=note_version,
            auto_accept_existing=auto_accept_existing,
            threshold=threshold,
            source_versions=source_versions,
        )

    async def query(
        self,
        *,
        source=None,
        created_within_days=None,
        labels=None,
        machine=False,
        limit=500,
        cursor=None,
        view="all",
    ):
        self.ctx.check("notes.read")
        if type(limit) is not int or not 1 <= limit <= 500:
            raise ValueError("limit must be 1 to 500")
        if machine and self.ai:
            raise ContextError("machine_note_forbidden")
        filt = {
            k: v
            for k, v in {
                "source": source,
                "created_within_days": created_within_days,
                "labels": labels,
            }.items()
            if v is not None
        }
        if view != "all":
            views = (
                self.ctx._host.manifests[self.ctx.plugin_id].get("contributes", {}).get("views", [])
            )
            matched = next((v for v in views if v["id"] == view), None)
            if matched is None:
                raise ValueError("unknown view")
            filt.update(matched["filter"])
        sql, args = compile_filter(filt, plugin_id=self.ctx.plugin_id, now=self.ctx.clock())
        visibility, _ = self.core._visibility()
        fingerprint = json.dumps([self.ctx.plugin_id, filt, machine], sort_keys=True)
        after = _cursor(cursor, fingerprint)
        if after and (
            len(after) != 2 or type(after[0]) is not int or not isinstance(after[1], str)
        ):
            raise ValueError("invalid plugin cursor")
        where = ["n.trashed_at IS NULL", visibility, sql]
        if not machine and source != "plugin:" + self.ctx.plugin_id:
            where.append("n.machine=0")
        if after:
            where.append("(n.created_at,n.id)<(?,?)")
            args.extend(after)
        async with self.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT n.*,n.rowid AS fts_id FROM note n WHERE "  # noqa: S608
                + " AND ".join(where)
                + " ORDER BY n.created_at DESC,n.id DESC LIMIT ?",
                [*args, limit + 1],
            )
            results = await self._values(conn, rows[:limit])
        self.ctx.check("notes.read")
        return {
            "notes": results,
            "next_cursor": _next(
                [rows[limit - 1]["created_at"], rows[limit - 1]["id"]], fingerprint
            )
            if len(rows) > limit
            else None,
        }

    async def latest(self, *, source=None):
        page = await self.query(source=source, limit=1)
        return page["notes"][0] if page["notes"] else None

    async def lookup_import(self, import_key):
        self.ctx.check("notes.update_own")
        visibility, _ = self.core._visibility()
        async with self.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT n.*,n.rowid AS fts_id FROM note n WHERE import_key=? AND created_by=? AND "  # noqa: S608
                + visibility,
                (import_key, self.core.principal.id),
            )
            result = await self._value(conn, dict(rows[0])) if rows else None
        self.ctx.check("notes.update_own")
        return result

    async def _creation_budget(self, conn):
        if not self.ctx.unattended:
            return
        day = (
            datetime.fromtimestamp(self.ctx.clock() / 1000, self.ctx._host.settings.zone)
            .date()
            .isoformat()
        )
        key = "_host_created:" + day
        rows = await conn.execute_fetchall(
            "SELECT value FROM plugin_kv WHERE plugin_id=? AND key=?", (self.ctx.plugin_id, key)
        )
        count = json.loads(rows[0][0]) if rows else 0
        if count >= 5:
            raise ContextError("creation_daily_limit")
        await conn.execute(
            "INSERT INTO plugin_kv VALUES (?,?,?) ON CONFLICT(plugin_id,key) DO UPDATE SET "
            "value=excluded.value",
            (self.ctx.plugin_id, key, json.dumps(count + 1)),
        )

    def _import_metadata(self, body, metadata, *, private=None):
        if metadata is None and private is None:
            return None
        caller = self.ctx.principal
        if (
            self.ctx._host.manifests[self.ctx.plugin_id].get("creates") != "import"
            or self.egress
            or self.ctx.unattended
            or (caller is not None and (not caller.owner or caller.kind != "session"))
        ):
            raise ContextError("owner_import_only")
        if private is not None and type(private) is not bool:
            raise ValueError("invalid import private flag")
        if private and not self.core.principal.private_ok:
            raise ContextError("private_note_forbidden")
        if metadata is None:
            return None
        from spunto.api.notes import Source
        from spunto.core.suggestions import _note_id
        from spunto.core.text import label_key, labels

        required = {"source", "origin", "machine", "conflict_of"}
        if (
            not isinstance(metadata, dict)
            or not required <= set(metadata)
            or set(metadata) - required - {"labels"}
        ):
            raise ValueError("invalid import metadata fields")
        result = dict(metadata)
        result["source"] = Source.model_validate(result["source"]).model_dump()
        if result["origin"] not in {"owner", "agent", "external", "plugin"}:
            raise ValueError("invalid import origin")
        if type(result["machine"]) is not bool:
            raise ValueError("invalid import machine flag")
        if result["conflict_of"] is not None:
            result["conflict_of"] = _note_id(result["conflict_of"])
        if "labels" in result:
            paths = result["labels"]
            if not isinstance(paths, list) or not all(isinstance(x, str) for x in paths):
                raise ValueError("invalid import labels")
            keys = [label_key(x) for x in paths]
            if len(set(keys)) != len(keys) or set(keys) != {x.key for x in labels(body)}:
                raise ValueError("import labels must match the body")
        return result

    async def _seed_import_labels(self, conn, metadata, created):
        from spunto.core.text import label_key

        for path in metadata.get("labels", []) if metadata else []:
            await conn.execute(
                "INSERT OR IGNORE INTO label(key,path,created_at) VALUES (?,?,?)",
                (label_key(path), path, created),
            )

    async def create(
        self,
        body,
        *,
        created_at=None,
        updated_at=None,
        id=None,
        import_key=None,
        source_ref=None,
        pinned=False,
        archived=False,
        trashed=False,
        private=False,
        machine=None,
        cites=None,
        request_id=None,
        source_versions=None,
        import_metadata=None,
    ):
        self._write_allowed()
        self.ctx.check("notes.create")
        creates = self.ctx._host.manifests[self.ctx.plugin_id].get("creates")
        manual = creates == "owner"
        if manual and (
            self.ctx.principal is None
            or not self.ctx.principal.owner
            or self.ctx.unattended
            or not (self.ctx.operation or "").startswith("command:")
        ):
            raise ContextError("owner_command_required")
        if manual and (
            type(private) is not bool
            or import_metadata is not None
            or source_ref is not None
            or pinned is not False
            or archived is not False
            or trashed is not False
        ):
            raise ContextError("manual_creation_fields_invalid")
        imported = creates == "import"
        metadata = self._import_metadata(body, import_metadata)
        _body(body, allow_empty=imported)
        now = self.ctx.clock()
        created = now if created_at is None else created_at
        updated = now if updated_at is None else updated_at
        if any(type(t) is not int or t < 0 or t > now + 86400000 for t in (created, updated)):
            raise ValueError("invalid import timestamp")
        if not imported and (import_key or created_at is not None or updated_at is not None or id):
            raise ContextError("import_only_field")
        if private and not self.core.principal.private_ok:
            raise ContextError("private_note_forbidden")
        if creates not in {"import", "machine", "external", "owner"}:
            raise ContextError("missing_creates")
        if machine is not None and bool(machine) != (creates == "machine"):
            raise ContextError("invalid_provenance")
        if id is None and imported:
            id = str(uuid.UUID(int=(created << 80) | (uuid.uuid7().int & ((1 << 80) - 1))))
        note_id = _id(id, now)
        if request_id is not None and (
            not isinstance(request_id, str) or not 1 <= len(request_id) <= 200
        ):
            raise ValueError("invalid creation request id")
        if cites is not None and (not isinstance(cites, list) or len(cites) > 50):
            raise ValueError("invalid citation references")
        async with self.store.write() as conn:
            self.ctx.check("notes.create")
            prior = []
            if request_id:
                prior = await conn.execute_fetchall(
                    "SELECT result FROM applied_op WHERE credential_id=? AND op_id=?",
                    (self.core.principal.id, request_id),
                )
            if prior:
                # The receipt acknowledges the original creation; it never
                # reads a note the owner may since have edited or hidden.
                receipt = json.loads(prior[0][0])
                result = {
                    **{
                        key: receipt[key]
                        for key in ("id", "version", "created_at", "origin", "machine")
                        if key in receipt
                    },
                    "replayed": True,
                }
            else:
                await self._guard_sources(
                    conn, source_versions, public_output=manual and not private
                )
                for cite in cites or []:
                    source = await self.core._row(conn, cite)
                    if source["machine"] or source["private"]:
                        raise ContextError("citation_unavailable")
                if import_key and await conn.execute_fetchall(
                    "SELECT 1 FROM note WHERE import_key=?", (import_key,)
                ):
                    raise ContextError("import_exists_or_unavailable")
                await self._creation_budget(conn)
                await self._seed_import_labels(conn, metadata, created)
                await self.core._insert(
                    conn,
                    body,
                    note_id,
                    created,
                    metadata["source"]
                    if metadata
                    else {
                        "kind": "import" if imported else "plugin",
                        "ref": self.ctx.plugin_id,
                    },
                    private,
                    origin=metadata["origin"]
                    if metadata
                    else (
                        "owner"
                        if imported or manual
                        else creates
                        if creates == "external"
                        else "plugin"
                    ),
                    conflict_of=metadata["conflict_of"] if metadata else None,
                    machine=metadata["machine"] if metadata else creates == "machine",
                    allow_empty=imported,
                )
                await conn.execute(
                    "UPDATE note SET "
                    "updated_at=?,import_key=?,machine=?,pinned=?,archived_at=?,trashed_at=? "
                    "WHERE id=?",
                    (
                        updated,
                        import_key,
                        int(metadata["machine"] if metadata else creates == "machine"),
                        int(pinned),
                        updated if archived else None,
                        updated if trashed else None,
                        note_id,
                    ),
                )
                await conn.execute(
                    "UPDATE note_revision SET change_kind=?,changed_at=? "
                    "WHERE note_id=? AND version=1",
                    ("import" if imported else "create", updated, note_id),
                )
                row = await self.core._row(conn, note_id, include_trashed=True)
                result = await self._value(conn, row)
                if request_id:
                    await conn.execute(
                        "INSERT INTO applied_op VALUES (?,?,?,?)",
                        (
                            self.core.principal.id,
                            request_id,
                            json.dumps(
                                {
                                    "id": note_id,
                                    "version": result["version"],
                                    "created_at": result["created_at"],
                                    "origin": result["origin"],
                                    "machine": result["machine"],
                                }
                            ),
                            now,
                        ),
                    )
            self.ctx.check("notes.create")
        self.ctx.check("notes.create")
        return result

    async def update_own(
        self,
        note_id,
        body,
        *,
        updated_at=None,
        pinned=None,
        archived=None,
        trashed=None,
        private=None,
        import_metadata=None,
    ):
        self._write_allowed()
        self.ctx.check("notes.update_own")
        if self.ctx._host.manifests[self.ctx.plugin_id].get("creates") == "owner":
            raise ContextError("owner_edits_require_review")
        _body(body, allow_empty=True)
        metadata = self._import_metadata(body, import_metadata, private=private)
        async with self.store.write() as conn:
            self.ctx.check("notes.update_own")
            try:
                row = await self.core._row(conn, note_id, include_trashed=True)
            except Problem as exc:
                if exc.status == 404:
                    return None
                raise
            if (
                row["created_by"] != self.core.principal.id
                or row["updated_by"] != self.core.principal.id
            ):
                return None
            others = await conn.execute_fetchall(
                "SELECT 1 FROM note_revision WHERE note_id=? AND changed_by!=? LIMIT 1",
                (note_id, self.core.principal.id),
            )
            if others:
                return None
            changes = {}
            now = self.ctx.clock()
            if private is not None:
                changes.update(private=int(private), private_clock=now)
            if metadata is not None:
                changes.update(
                    source_kind=metadata["source"]["kind"],
                    source_ref=metadata["source"]["ref"],
                    source_url=metadata["source"]["url"],
                    origin=metadata["origin"],
                    machine=int(metadata["machine"]),
                    conflict_of=metadata["conflict_of"],
                )
                await self._seed_import_labels(conn, metadata, row["created_at"])
            for name, value in (("pinned", pinned), ("archived", archived), ("trashed", trashed)):
                if value is not None:
                    changes[name if name == "pinned" else name + "_at"] = (
                        int(value) if name == "pinned" else now if value else None
                    )
                    changes[name + "_clock"] = now
            result = await self.core._update(
                conn, row, body, "import", changes=changes, coalesce=False
            )
            if updated_at is not None:
                if type(updated_at) is not int or not 0 <= updated_at <= now + 86400000:
                    raise ValueError("invalid timestamp")
                await conn.execute("UPDATE note SET updated_at=? WHERE id=?", (updated_at, note_id))
                await conn.execute(
                    "UPDATE note_revision SET changed_at=? WHERE note_id=? AND version=?",
                    (updated_at, note_id, result["version"]),
                )
                result = await self._value(
                    conn, await self.core._row(conn, note_id, include_trashed=True)
                )
            self.ctx.check("notes.update_own")
        self.ctx.check("notes.update_own")
        return result

    async def annotate(self, note_id, kind, payload, *, note_version=None):
        self._write_allowed()
        self.ctx.check("annotate")
        if note_version is not None and (type(note_version) is not int or note_version < 1):
            raise ValueError("annotation note version must be positive")
        if payload is not None:
            validate_annotation(kind, payload)
            contributions = self.ctx._host.manifests[self.ctx.plugin_id].get("contributes", {})
            if kind.startswith("member:"):
                declared = any(
                    v["id"] == kind[7:]
                    or kind[7:]
                    in {v.get("filter", {}).get("member"), v.get("filter", {}).get("not_member")}
                    for v in contributions.get("views", [])
                )
            else:
                declared = bool(contributions.get(kind))
            if not declared:
                raise ContextError("annotation_slot_undeclared")
        async with self.store.write() as conn:
            self.ctx.check("annotate")
            if payload is not None:
                row = await self.core._row(conn, note_id)
                if self.egress and row["machine"]:
                    raise ContextError("machine_note_forbidden")
                if note_version is not None and row["version"] != note_version:
                    raise Problem(409, "version_conflict", "The note changed; recompute the panel.")
            now = self.ctx.clock()
            if payload is None:
                existed = await conn.execute_fetchall(
                    "SELECT 1 FROM annotation WHERE plugin_id=? AND note_id=? AND kind=?",
                    (self.ctx.plugin_id, note_id, kind),
                )
                if existed:
                    await conn.execute(
                        "DELETE FROM annotation WHERE plugin_id=? AND note_id=? AND kind=?",
                        (self.ctx.plugin_id, note_id, kind),
                    )
                    await conn.execute(
                        "INSERT OR REPLACE INTO tombstone VALUES ('annotation',?,?,?)",
                        (f"{self.ctx.plugin_id}/{note_id}/{kind}", await sequence(conn), now),
                    )
            else:
                if kind == "slip":
                    day = (
                        datetime.fromtimestamp(now / 1000, self.ctx._host.settings.zone)
                        .date()
                        .isoformat()
                    )
                    prior = await conn.execute_fetchall(
                        "SELECT value FROM setting WHERE key='host:slip_day'",
                    )
                    if prior and json.loads(prior[0][0]) == day:
                        raise ContextError("slip_daily_limit")
                    await conn.execute(
                        "INSERT INTO setting VALUES ('host:slip_day',?) ON "
                        "CONFLICT(key) DO UPDATE SET value=excluded.value",
                        (json.dumps(day),),
                    )
                await conn.execute(
                    "INSERT INTO annotation VALUES (?,?,?,?,?,?) ON "
                    "CONFLICT(plugin_id,note_id,kind) DO UPDATE SET "
                    "payload=excluded.payload,change_seq=excluded.change_seq,"
                    "updated_at=excluded.updated_at",
                    (
                        self.ctx.plugin_id,
                        note_id,
                        kind,
                        json.dumps(payload, ensure_ascii=False),
                        await sequence(conn),
                        now,
                    ),
                )
            self.ctx.check("annotate")
            # Row version is deliberately unchanged by derived plugin output.
        self.ctx.check("annotate")

    async def set_search_text(self, note_id, text):
        self._write_allowed()
        self.ctx.check("search.text")
        if not isinstance(text, str) or len(text) > 20000:
            raise ValueError("search text exceeds 20000 characters")
        async with self.store.write() as conn:
            self.ctx.check("search.text")
            row = await self.core._row(conn, note_id)
            await conn.execute(
                "INSERT INTO note_text VALUES (?,?,?,?) ON CONFLICT(plugin_id,note_id) DO "
                "UPDATE SET text=excluded.text,updated_at=excluded.updated_at",
                (self.ctx.plugin_id, note_id, text, self.ctx.clock()),
            )
            await self.core._index(conn, row)
            await conn.execute("DELETE FROM embedding_note WHERE note_id=?", (note_id,))
            self.store.emit("note.embed", note_id=note_id)
            self.ctx.check("search.text")
        self.ctx.check("search.text")

    async def suggest_batch(self, items, *, source_versions=None):
        """Validate and commit a bounded proposal batch in one writer transaction."""
        from spunto.core.suggestions import suggest

        self._write_allowed()
        self.ctx.check("suggest")
        if not isinstance(items, list) or not 1 <= len(items) <= 3:
            raise ValueError("a suggestion batch needs one to three items")
        async with self.store.write() as conn:
            await self._guard_sources(conn, source_versions)
            results = []
            for item in items:
                if not isinstance(item, dict) or set(item) - {
                    "note_id",
                    "action",
                    "payload",
                    "note_version",
                    "confidence",
                    "reason",
                    "model",
                }:
                    raise ValueError("invalid suggestion batch item")
                results.append(await suggest(self.ctx, **item, connection=conn))
            self.ctx.check("suggest")
        return results

    async def suggest(
        self,
        note_id,
        action,
        payload,
        *,
        note_version=None,
        confidence=None,
        reason="",
        model=None,
        source_versions=None,
    ):
        from spunto.core.suggestions import suggest

        self._write_allowed()
        return await suggest(
            self.ctx,
            note_id,
            action,
            payload,
            note_version=note_version,
            confidence=confidence,
            reason=reason,
            model=model,
            source_versions=source_versions,
        )
