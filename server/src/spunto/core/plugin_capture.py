"""A single event/owner-command target, never a general plugin note query."""

from spunto.auth.tokens import Principal
from spunto.core.notes import Notes, _id
from spunto.host.plugin import ContextError
from spunto.host.storage import encoded, key


class ScopedCapture:
    def __init__(self, ctx):
        self.ctx = ctx
        self.core = Notes(
            ctx._host.store,
            Principal(
                "plugin:" + ctx.plugin_id, "plugin", ctx.plugin_id, "plugin", private_ok=False
            ),
        )

    def _scope(self, note_id):
        self.ctx.check()
        if not isinstance(note_id, str):
            raise ContextError("capture_scope_required")
        note_id = _id(note_id, self.ctx.clock())
        operation = self.ctx.operation or ""
        owner = self.ctx.principal is not None and self.ctx.principal.owner
        permissions = set(self.ctx._host.manifests[self.ctx.plugin_id]["permissions"]) & set(
            self.ctx._host.states[self.ctx.plugin_id]["granted"]
        )
        if not any(
            permission in {"net.fetch", "suggest", "search.text", "notes.read"}
            or permission.startswith("ai.")
            for permission in permissions
        ):
            raise ContextError("capture_permission_required")
        bound = (
            _id(self.ctx.capture_note_id, self.ctx.clock())
            if isinstance(self.ctx.capture_note_id, str)
            else None
        )
        if bound != note_id or not (
            operation.startswith(("hook:", "job:")) or owner and operation.startswith("command:")
        ):
            raise ContextError("capture_scope_required")
        return note_id

    async def _row(self, conn, note_id, version=None):
        note_id = self._scope(note_id)
        rows = await conn.execute_fetchall(
            "SELECT n.*,n.rowid AS fts_id FROM note n WHERE n.id=?", (note_id,)
        )
        if not rows:
            return None
        row = dict(rows[0])
        if row["private"] or row["machine"] or row["trashed_at"] is not None:
            return None
        if version is not None and row["version"] != version:
            return None
        explicit = (
            (
                self.ctx.principal is not None
                and self.ctx.principal.owner
                and (self.ctx.operation or "").startswith("command:")
            )
            or not self.ctx.unattended
            and (self.ctx.operation or "").startswith("job:")
        )
        actor = await conn.execute_fetchall(
            "SELECT kind FROM credential WHERE id=?", (row["updated_by"],)
        )
        if not explicit and (row["origin"] != "owner" or actor and actor[0][0] == "pat"):
            return None
        if (
            self.ctx.principal is not None
            and not self.ctx.principal.external_ok
            and row["origin"] == "external"
        ):
            return None
        return row

    async def note(self, note_id, *, note_version=None):
        async with self.ctx._host.store.read() as conn:
            row = await self._row(conn, note_id, note_version)
            if row is None:
                return None
            value = await self.core._serialize(conn, row, include_plugins=False)
        self.ctx.check()
        self.ctx._borrowed_notes[row["id"]] = row["body"]
        return value

    async def proposal_state(self, note_id, suggestion_id):
        """Only a plugin's own proposal on the currently bound eligible source."""
        self.ctx.check("suggest")
        async with self.ctx._host.store.read() as conn:
            source = await self._row(conn, note_id)
            if source is None:
                raise ContextError("capture_source_changed")
            rows = await conn.execute_fetchall(
                "SELECT status,note_version FROM suggestion WHERE id=? AND plugin_id=? "
                "AND note_id=?",
                (suggestion_id, self.ctx.plugin_id, note_id),
            )
        self.ctx.check("suggest")
        return dict(rows[0]) if rows else None

    async def event_state(self, note_id):
        """No body, including for trash; a missing row does not imply it was public."""
        note_id = self._scope(note_id)
        self.ctx.check("notes.read")
        async with self.ctx._host.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT private,machine,trashed_at,version,change_seq FROM note WHERE id=?",
                (note_id,),
            )
        self.ctx.check("notes.read")
        if not rows:
            return {"exists": False, "public": False}
        row = dict(rows[0])
        return {
            "exists": True,
            "public": not row["private"] and not row["machine"],
            "trashed": row["trashed_at"] is not None,
            "version": row["version"],
            "change_seq": row["change_seq"],
        }

    async def store_result(
        self, collection, record_id, note_id, note_version, data, *, attachment_id=None
    ):
        self.ctx.check_write()
        collection, record_id, payload = key(collection), key(record_id), encoded(data)
        async with self.ctx._host.store.write() as conn:
            row = await self._row(conn, note_id, note_version)
            if row is None:
                raise ContextError("capture_source_changed")
            if attachment_id is not None:
                links = await conn.execute_fetchall(
                    "SELECT 1 FROM note_attachment WHERE note_id=? AND attachment_id=?",
                    (note_id, attachment_id),
                )
                if not links:
                    raise ContextError("capture_attachment_changed")
            await conn.execute(
                "INSERT INTO plugin_record(plugin_id,collection,id,note_id,key,data,updated_at) "
                "VALUES(?,?,?,?,NULL,?,?) ON CONFLICT(plugin_id,collection,id) DO UPDATE SET "
                "note_id=excluded.note_id,data=excluded.data,updated_at=excluded.updated_at",
                (self.ctx.plugin_id, collection, record_id, note_id, payload, self.ctx.clock()),
            )
            self.ctx.check()

    async def set_search_text(self, note_id, note_version, text):
        self.ctx.check_write()
        self.ctx.check("search.text")
        if not isinstance(text, str) or len(text) > 20_000:
            raise ValueError("search text exceeds 20000 characters")
        async with self.ctx._host.store.write() as conn:
            row = await self._row(conn, note_id, note_version)
            if row is None:
                raise ContextError("capture_source_changed")
            await conn.execute(
                "INSERT INTO note_text VALUES(?,?,?,?) ON CONFLICT(plugin_id,note_id) DO UPDATE "
                "SET text=excluded.text,updated_at=excluded.updated_at",
                (self.ctx.plugin_id, note_id, text, self.ctx.clock()),
            )
            await self.core._index(conn, row)
            await conn.execute("DELETE FROM embedding_note WHERE note_id=?", (note_id,))
            self.ctx._host.store.emit("note.embed", note_id=note_id)
            self.ctx.check("search.text")
