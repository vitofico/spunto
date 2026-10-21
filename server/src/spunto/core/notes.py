"""The sole note write path: notes, revisions, labels and FTS commit together."""

import asyncio
import base64
import hashlib
import json
import re
import uuid
from datetime import UTC, date, datetime, timedelta

import aiosqlite

from spunto.api.errors import Problem
from spunto.auth.tokens import Principal
from spunto.core.merge import merge
from spunto.core.text import fold, label_key, labels, rewrite_labels, search_query, title_of
from spunto.core.text import set_labels as apply_labels
from spunto.store.db import Store, now_ms

MAX_BODY = 100_000
COALESCE_MS = 10 * 60 * 1000


def iso(ms: int | None) -> str | None:
    return (
        datetime.fromtimestamp(ms / 1000, UTC)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
        if ms is not None
        else None
    )


def _date_ms(value: date | str | None, *, end: bool = False) -> int | None:
    if value is None:
        return None
    try:
        day = date.fromisoformat(value) if isinstance(value, str) else value
        return int(
            datetime.combine(day + timedelta(days=int(end)), datetime.min.time(), UTC).timestamp()
            * 1000
        )
    except ValueError, TypeError, OverflowError:
        raise Problem(422, "validation_failed", "Use dates in YYYY-MM-DD form.") from None


def _body(value: str, *, allow_empty: bool = False) -> None:
    if not isinstance(value, str) or not int(not allow_empty) <= len(value) <= MAX_BODY:
        raise Problem(422, "validation_failed", "body must contain 1 to 100000 characters.")


def _id(value: str | None, now: int) -> str:
    if value is None:
        return str(uuid.uuid7())
    try:
        parsed = uuid.UUID(value)
        if (
            parsed.version != 7
            or parsed.variant != uuid.RFC_4122
            or parsed.int >> 80 > now + 86_400_000
        ):
            raise ValueError
        return str(parsed)
    except ValueError, TypeError, AttributeError:
        raise Problem(
            422, "validation_failed", "id must be a UUIDv7 dated no more than one day ahead."
        ) from None


def _cursor(value: str | None, fingerprint: str) -> list | None:
    if value is None:
        return None
    if len(value) > 8192:
        raise Problem(422, "validation_failed", "The cursor is too long.")
    try:
        payload = json.loads(base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)))
        if payload[0] != fingerprint or not isinstance(payload[1], list):
            raise ValueError
        return payload[1]
    except ValueError, TypeError, IndexError, KeyError:
        raise Problem(
            422, "validation_failed", "The cursor does not belong to this query."
        ) from None


def _next(values: list, fingerprint: str) -> str:
    return (
        base64.urlsafe_b64encode(json.dumps([fingerprint, values], separators=(",", ":")).encode())
        .decode()
        .rstrip("=")
    )


def _tasks(body: str) -> int:
    from spunto.core.text import task_counts

    return task_counts(body)[0]


async def sequence(conn: aiosqlite.Connection) -> int:
    rows = await conn.execute_fetchall(
        "UPDATE meta SET value=CAST(value AS INTEGER)+1 WHERE key='change_seq' RETURNING value"
    )
    return int(rows[0][0])


class Notes:
    def __init__(self, store: Store, principal: Principal):
        self.store, self.principal = store, principal

    def _write_allowed(self) -> None:
        if not self.principal.writable:
            raise Problem(403, "read_only_token", "This token is read-only.")

    def _visibility(self, alias: str = "n") -> tuple[str, list]:
        conditions, args = [], []
        if not self.principal.private_ok:
            conditions.append(f"{alias}.private=0")
        if not self.principal.external_ok:
            conditions.append(f"{alias}.origin!='external'")
        return " AND ".join(conditions) or "1", args

    async def _row(self, conn, note_id: str, *, include_trashed: bool = False):
        visibility, args = self._visibility()
        rows = await conn.execute_fetchall(
            f"SELECT n.*, n.rowid AS fts_id FROM note n WHERE n.id=? AND ({visibility})"  # noqa: S608
            + ("" if include_trashed else " AND n.trashed_at IS NULL"),
            [note_id, *args],
        )
        if not rows:
            raise Problem(404, "not_found", "That note is unavailable.")
        return dict(rows[0])

    async def _serialize(
        self, conn, row, *, render: bool = False, include_plugins: bool = True
    ) -> dict:
        paths = await conn.execute_fetchall(
            (
                "SELECT l.path FROM label l JOIN note_label nl ON nl.label_id=l.id "
                "WHERE nl.note_id=? ORDER BY l.id"
            ),
            (row["id"],),
        )
        actor = await conn.execute_fetchall(
            "SELECT kind FROM credential WHERE id=?", (row["updated_by"],)
        )
        source = {"kind": row["source_kind"], "ref": row["source_ref"]}
        if row["source_url"]:
            source["url"] = row["source_url"]
        result = {
            "id": row["id"],
            "body": row["body"],
            "body_hash": row["body_hash"],
            "title": row["title"],
            "labels": [p[0] for p in paths],
            "version": row["version"],
            "change_seq": row["change_seq"],
            "created_at": iso(row["created_at"]),
            "updated_at": iso(row["updated_at"]),
            "pinned": bool(row["pinned"]),
            "archived": row["archived_at"] is not None,
            "trashed": row["trashed_at"] is not None,
            "private": bool(row["private"]),
            "flag_times": {
                name: row[f"{name}_clock"] for name in ("pinned", "archived", "trashed", "private")
            },
            "open_tasks": row["open_tasks"],
            "source": source,
            "origin": row["origin"],
            "updated_via_agent": bool(actor and actor[0][0] == "pat"),
            "machine": bool(row["machine"]),
            "conflict_of": row["conflict_of"],
            "suggestions": [],
            "annotations": [],
        }
        if not include_plugins:
            return result
        proposals = await conn.execute_fetchall(
            "SELECT s.* FROM suggestion s JOIN plugin p ON p.id=s.plugin_id "
            "WHERE s.note_id=? AND s.status='pending' AND p.state='enabled' AND p.switch='on' "
            "AND s.created_at>? "
            "ORDER BY coalesce(s.confidence,0) DESC,p.position,s.created_at DESC",
            (row["id"], now_ms() - 14 * 86400000),
        )
        counts = {}
        for proposal in proposals:
            from spunto.core.suggestions import references_visible

            count = counts.get(proposal["plugin_id"], 0)
            if count >= 3 or not await references_visible(conn, self, proposal):
                continue
            counts[proposal["plugin_id"]] = count + 1
            item = dict(proposal)
            item["payload"] = json.loads(item["payload"])
            item["created_at"] = iso(item["created_at"])
            result["suggestions"].append(item)
        annotations = await conn.execute_fetchall(
            "SELECT a.* FROM annotation a JOIN plugin p ON p.id=a.plugin_id "
            "WHERE a.note_id=? AND p.state='enabled' AND p.switch='on' "
            "ORDER BY p.position,a.plugin_id,a.kind",
            (row["id"],),
        )
        meta = False
        for annotation in annotations:
            item = dict(annotation)
            if item["kind"] == "meta":
                if meta:
                    continue
                meta = True
            item["payload"] = await self._visible_components(conn, json.loads(item["payload"]))
            if item["payload"] is None:
                continue
            if render:
                from spunto.host.vocabulary import render_output

                item["payload"] = render_output(item["payload"])
            item["updated_at"] = iso(item["updated_at"])
            result["annotations"].append(item)
        if render:
            from spunto.core.render import render_markdown

            result["html"] = render_markdown(row["body"])
            if self.principal.workspace_id:
                from urllib.parse import quote

                result["html"] = re.sub(
                    r'(["\'])/a/([a-fA-F0-9-]{36})(?=["\'])',
                    lambda m: (
                        m[1]
                        + "/a/"
                        + m[2]
                        + "?workspace="
                        + quote(self.principal.workspace_id, safe="")
                    ),
                    result["html"],
                )
        return result

    async def _visible_components(self, conn, payload):
        if isinstance(payload, list):
            return [
                result
                for item in payload
                if (result := await self._visible_components(conn, item)) is not None
            ]
        if not isinstance(payload, dict):
            return payload
        if payload.get("type") == "notes":
            key = "ids" if "ids" in payload else "items"
            visible = []
            for item in payload.get(key, []):
                note_id = item if isinstance(item, str) else item.get("id")
                try:
                    await self._row(conn, note_id)
                except Problem as exc:
                    if exc.status != 404:
                        raise
                    continue
                visible.append(item)
            return {**payload, key: visible} if visible else None
        return {key: await self._visible_components(conn, value) for key, value in payload.items()}

    async def _index(self, conn, row) -> None:
        prior = await conn.execute_fetchall(
            "SELECT l.key FROM note_label nl JOIN label l ON l.id=nl.label_id WHERE nl.note_id=?",
            (row["id"],),
        )
        parsed = labels(row["body"])
        if {item[0] for item in prior} != {item.key for item in parsed}:
            self.store.emit("labels.changed", row["id"], row["version"])
        await conn.execute("DELETE FROM note_label WHERE note_id=?", (row["id"],))
        for label in parsed:
            await conn.execute(
                "INSERT OR IGNORE INTO label(key,path,created_at) VALUES (?,?,?)",
                (label.key, label.path, now_ms()),
            )
            await conn.execute(
                "INSERT OR IGNORE INTO note_label SELECT ?,id FROM label WHERE key=?",
                (row["id"], label.key),
            )
        await conn.execute("DELETE FROM note_fts WHERE rowid=?", (row["fts_id"],))
        extra = await conn.execute_fetchall(
            "SELECT t.text FROM note_text t JOIN plugin p ON p.id=t.plugin_id "
            "WHERE t.note_id=? AND p.state='enabled' AND p.switch='on' ORDER BY p.position",
            (row["id"],),
        )
        await conn.execute(
            "INSERT INTO note_fts(rowid,title,body,extra) VALUES (?,?,?,?)",
            (
                row["fts_id"],
                fold(row["title"]),
                fold(row["body"]),
                fold("\n".join(r[0] for r in extra)),
            ),
        )
        attachment_ids = set(re.findall(r"/a/([0-9a-fA-F-]{36})(?![0-9a-fA-F-])", row["body"]))
        if shared_note_id := getattr(self.store, "shared_note_id", None):
            allowed = {
                link[0]
                for link in await conn.execute_fetchall(
                    "SELECT attachment_id FROM note_attachment WHERE note_id=?", (shared_note_id,)
                )
            }
            if attachment_ids - allowed:
                raise Problem(
                    403, "attachment_not_shared", "Only this note's attachments may be used."
                )
        prior_attachments = {
            link[0]
            for link in await conn.execute_fetchall(
                "SELECT attachment_id FROM note_attachment WHERE note_id=?", (row["id"],)
            )
        }
        await conn.execute("DELETE FROM note_attachment WHERE note_id=?", (row["id"],))
        for attachment_id in attachment_ids:
            await conn.execute(
                "INSERT OR IGNORE INTO note_attachment SELECT ?,id FROM attachment WHERE id=?",
                (row["id"], attachment_id),
            )
            if attachment_id not in prior_attachments:
                linked = await conn.execute_fetchall(
                    "SELECT 1 FROM note_attachment WHERE note_id=? AND attachment_id=?",
                    (row["id"], attachment_id),
                )
                if linked:
                    self.store.emit(
                        "attachment.added", row["id"], row["version"], attachment_id=attachment_id
                    )

    async def _revision(self, conn, row, kind: str, *, coalesce: bool = True) -> None:
        if coalesce:
            previous = await conn.execute_fetchall(
                "SELECT * FROM note_revision WHERE note_id=? ORDER BY version DESC LIMIT 1",
                (row["id"],),
            )
            # Keep the initial ancestor and explicit restore points for offline edits and undo.
            if (
                previous
                and previous[0]["change_kind"] in {"edit", "append", "merge"}
                and previous[0]["changed_by"] == self.principal.id
                and row["updated_at"] - previous[0]["changed_at"] < COALESCE_MS
                and row["updated_by"] == self.principal.id
            ):
                await conn.execute(
                    "DELETE FROM note_revision WHERE note_id=? AND version=?",
                    (row["id"], previous[0]["version"]),
                )
        await conn.execute(
            "INSERT INTO note_revision VALUES (?,?,?,?,?,?)",
            (row["id"], row["version"], row["body"], kind, self.principal.id, row["updated_at"]),
        )

    async def _update(
        self, conn, row, body: str, kind: str, *, changes: dict | None = None, coalesce: bool = True
    ) -> dict:
        _body(body, allow_empty=True)
        previous_hash, previous_machine = row["body_hash"], row["machine"]
        previous_private, previous_trash = row["private"], row["trashed_at"]
        previous_credential = row["updated_by"]
        if self.principal.owner and body != row["body"]:
            row["machine"] = 0
        row.update(changes or {})
        row.update(
            body=body,
            title=title_of(body),
            body_hash=hashlib.sha256(body.encode()).hexdigest(),
            open_tasks=_tasks(body),
            version=row["version"] + 1,
            change_seq=await sequence(conn),
            updated_at=now_ms(),
            updated_by=self.principal.id,
        )
        columns = [k for k in row if k not in {"id", "fts_id"}]
        await conn.execute(
            "UPDATE note SET " + ",".join(f"{k}=?" for k in columns) + " WHERE id=?",  # noqa: S608
            [*[row[k] for k in columns], row["id"]],
        )
        if row["private"] or row["machine"] or row["trashed_at"] is not None:
            from spunto.ai.vectors import purge_note_vectors

            await purge_note_vectors(conn, row["id"])
        elif (
            previous_hash != row["body_hash"]
            or previous_machine
            or previous_private
            or previous_trash is not None
        ):
            self.store.emit("note.embed", row["id"], row["version"])
        await self._index(conn, row)
        await self._revision(
            conn, row, kind, coalesce=coalesce and previous_credential == self.principal.id
        )
        stale = await conn.execute_fetchall(
            "SELECT id FROM suggestion WHERE note_id=? AND action='replace_body' "
            "AND status='pending' AND note_version!=?",
            (row["id"], row["version"]),
        )
        for item in stale:
            await conn.execute(
                "UPDATE suggestion SET status='superseded',change_seq=? WHERE id=?",
                (await sequence(conn), item[0]),
            )
        self.store.emit("note.saved", row["id"], row["version"])
        return await self._serialize(conn, row)

    async def _insert(
        self,
        conn,
        body: str,
        note_id: str,
        created: int,
        source: dict,
        private: bool,
        *,
        origin: str | None = None,
        conflict_of: str | None = None,
        allow_empty: bool = False,
        machine: bool = False,
    ) -> dict:
        _body(body, allow_empty=allow_empty or conflict_of is not None)
        now = now_ms()
        agent = self.principal.kind == "pat" and conflict_of is None
        row = {
            "id": note_id,
            "body": body,
            "title": title_of(body),
            "body_hash": hashlib.sha256(body.encode()).hexdigest(),
            "version": 1,
            "change_seq": await sequence(conn),
            "created_at": created,
            "updated_at": now,
            "private": int(private),
            "private_clock": now if private else 0,
            "machine": int(machine),
            "open_tasks": _tasks(body),
            "source_kind": "agent" if agent else source.get("kind", "api"),
            "source_ref": self.principal.name if agent else source.get("ref"),
            "source_url": source.get("url"),
            "origin": origin or ("agent" if agent else "owner"),
            "created_by": self.principal.id,
            "updated_by": self.principal.id,
            "conflict_of": conflict_of,
        }
        columns = list(row)
        await conn.execute(
            "INSERT INTO note("  # noqa: S608
            + ",".join(columns)
            + ") VALUES ("
            + ",".join("?" for _ in columns)
            + ")",
            list(row.values()),
        )
        row = dict(
            (
                await conn.execute_fetchall(
                    "SELECT *,rowid AS fts_id FROM note WHERE id=?", (note_id,)
                )
            )[0]
        )
        await self._index(conn, row)
        await self._revision(conn, row, "create", coalesce=False)
        if not row["private"] and not row["machine"]:
            self.store.emit("note.embed", row["id"], row["version"])
        self.store.emit("note.saved", row["id"], row["version"])
        return await self._serialize(conn, row)

    async def create(
        self,
        body: str,
        id: str | None = None,
        created_at: int | None = None,
        source: dict | None = None,
        private: bool = False,
        op_id: str | None = None,
        dedupe: bool = False,
    ) -> dict:
        self._write_allowed()
        _body(body)
        now = now_ms()
        note_id = _id(id, now)
        if private and not self.principal.private_ok:
            raise Problem(403, "private_note_forbidden", "This token is not allowed private notes.")
        if created_at is not None and (created_at < 0 or created_at > now + 86_400_000):
            raise Problem(
                422,
                "validation_failed",
                "created_at must be a valid time no more than one day ahead.",
            )
        async with self.store.write() as conn:
            operation_owner = self.principal.id
            if op_id:
                # Browser drafts outlive a rotated cookie session. PAT and device
                # operations retain their individual credential namespaces.
                if self.principal.kind == "session":
                    owner_rows = await conn.execute_fetchall("SELECT created_at FROM owner")
                    if owner_rows:
                        operation_owner = f"owner-session:{owner_rows[0][0]}"
                prior = await conn.execute_fetchall(
                    "SELECT result FROM applied_op WHERE credential_id=? AND op_id=?",
                    (operation_owner, op_id),
                )
                if prior:
                    result = json.loads(prior[0][0])
                    await self._row(conn, result["id"])
                    return result
            if dedupe:
                duplicate = await conn.execute_fetchall(
                    (
                        "SELECT *,rowid AS fts_id FROM note WHERE created_by=? AND body_hash=? "
                        "AND body=? AND created_at>=? AND trashed_at IS NULL ORDER BY "
                        "created_at DESC LIMIT 1"
                    ),
                    (
                        self.principal.id,
                        hashlib.sha256(body.encode()).hexdigest(),
                        body,
                        now - COALESCE_MS,
                    ),
                )
                if (
                    duplicate
                    and (self.principal.private_ok or not duplicate[0]["private"])
                    and (self.principal.external_ok or duplicate[0]["origin"] != "external")
                ):
                    result = await self._serialize(conn, duplicate[0])
                    result["duplicate"] = True
                else:
                    result = await self._insert(
                        conn,
                        body,
                        note_id,
                        created_at if created_at is not None else now,
                        source or {},
                        private,
                    )
            else:
                exists = await conn.execute_fetchall(
                    (
                        "SELECT 1 FROM note WHERE id=? UNION SELECT 1 FROM tombstone WHERE "
                        "kind='note' AND id=?"
                    ),
                    (note_id, note_id),
                )
                if exists:
                    raise Problem(
                        409, "id_conflict", "A note with that id already exists or was deleted."
                    )
                result = await self._insert(
                    conn,
                    body,
                    note_id,
                    created_at if created_at is not None else now,
                    source or {},
                    private,
                )
            if op_id:
                await conn.execute(
                    "INSERT INTO applied_op VALUES (?,?,?,?)",
                    (operation_owner, op_id, json.dumps(result), now),
                )
            return result

    async def get(self, id: str, include_trashed: bool = False, render: bool = False) -> dict:
        async with self.store.read() as conn:
            return await self._serialize(
                conn, await self._row(conn, id, include_trashed=include_trashed), render=render
            )

    async def edit(self, id: str, body: str, base_version: int) -> dict:
        self._write_allowed()
        _body(body, allow_empty=True)
        while True:
            async with self.store.read() as conn:
                snapshot = await self._row(conn, id)
                if base_version > snapshot["version"]:
                    raise Problem(
                        409,
                        "resync_required",
                        "The server has an older version; reconcile after its restore.",
                    )
                ancestors = (
                    await conn.execute_fetchall(
                        "SELECT body FROM note_revision WHERE note_id=? AND version<=? "
                        "ORDER BY version DESC LIMIT 1",
                        (id, base_version),
                    )
                    if base_version < snapshot["version"]
                    else []
                )
            merged = (
                body
                if base_version == snapshot["version"]
                else await asyncio.to_thread(merge, ancestors[0][0], snapshot["body"], body)
                if ancestors
                else None
            )
            # A clean merge can exceed the per-note budget; preserve each valid full body.
            if merged is not None and len(merged) > MAX_BODY:
                merged = None
            async with self.store.write() as conn:
                row = await self._row(conn, id)
                if row["version"] != snapshot["version"]:
                    continue
                if merged is not None:
                    result = await self._update(
                        conn, row, merged, "edit" if base_version == row["version"] else "merge"
                    )
                    if base_version < snapshot["version"]:
                        result["merged"] = True
                    return result
                copy = await self._insert(
                    conn,
                    body,
                    str(uuid.uuid7()),
                    row["created_at"],
                    {
                        "kind": row["source_kind"],
                        "ref": row["source_ref"],
                        "url": row["source_url"],
                    },
                    bool(row["private"]),
                    origin=row["origin"],
                    conflict_of=id,
                )
                # Commit the preserved incoming text before returning HTTP 409.
                return {
                    "conflict": True,
                    "conflict_id": copy["id"],
                    "version": row["version"],
                    "note": copy,
                }

    async def edit_in_transaction(
        self, conn, id, body, base_version, *, restore=False, machine=False
    ):
        """Sync stores both the domain result and the receipt in this transaction."""
        _body(body, allow_empty=True)
        row = await self._row(conn, id, include_trashed=restore)
        current = row["version"]
        if base_version > current and not restore:
            raise Problem(409, "resync_required", "Reconcile after the server restore.")
        ancestors = (
            await conn.execute_fetchall(
                "SELECT body FROM note_revision WHERE note_id=? AND version<=? "
                "ORDER BY version DESC LIMIT 1",
                (id, base_version),
            )
            if base_version < current
            else []
        )
        merged = (
            body
            if base_version == current
            else (
                await asyncio.to_thread(merge, ancestors[0][0], row["body"], body)
                if ancestors
                else None
            )
        )
        if merged is not None and len(merged) <= MAX_BODY:
            result = await self._update(
                conn, row, merged, "edit" if base_version == current else "merge"
            )
            if base_version != current:
                result["merged"] = True
            return result
        copy = await self._insert(
            conn,
            body,
            str(uuid.uuid7()),
            row["created_at"],
            {"kind": row["source_kind"], "ref": row["source_ref"], "url": row["source_url"]},
            bool(row["private"]),
            origin=row["origin"],
            conflict_of=id,
            machine=machine if restore else False,
        )
        return {"conflict": True, "conflict_id": copy["id"], "version": current, "note": copy}

    async def append(
        self, id: str, text: str, expected_version: int | None = None, separator: str = "paragraph"
    ) -> dict:
        self._write_allowed()
        if not text:
            raise Problem(422, "validation_failed", "text must not be empty.")
        async with self.store.write() as conn:
            row = await self._row(conn, id)
            if expected_version is not None and expected_version != row["version"]:
                raise Problem(
                    409,
                    "version_conflict",
                    f"That note is now version {row['version']}; read it and retry.",
                )
            return await self._update(
                conn, row, row["body"] + ("\n" if separator == "line" else "\n\n") + text, "append"
            )

    async def set_labels(self, id: str, add: list[str], remove: list[str]) -> dict:
        self._write_allowed()
        try:
            for path in [*add, *remove]:
                label_key(path)
        except ValueError as exc:
            raise Problem(422, "validation_failed", str(exc)) from None
        async with self.store.write() as conn:
            row = await self._row(conn, id)
            try:
                body = apply_labels(row["body"], add, remove)
            except ValueError as exc:
                raise Problem(422, "validation_failed", str(exc)) from None
            if body == row["body"]:
                return await self._serialize(conn, row)
            return await self._update(conn, row, body, "label_rewrite", coalesce=False)

    async def flags(self, id: str, flags: dict, clocks: dict | None = None) -> dict:
        self._write_allowed()
        async with self.store.write() as conn:
            return await self.flags_in_transaction(conn, id, flags, clocks)

    async def flags_in_transaction(self, conn, id, flags, clocks=None):
        now, changes = now_ms(), {}
        row = await self._row(conn, id, include_trashed=True)
        for name, value in flags.items():
            if name not in {"pinned", "archived", "trashed", "private"} or not isinstance(
                value, bool
            ):
                raise Problem(
                    422,
                    "validation_failed",
                    "Use pinned, archived, trashed or private boolean flags.",
                )
            if name == "private" and not self.principal.private_ok:
                raise Problem(
                    403,
                    "private_note_forbidden",
                    "This token is not allowed to change privacy.",
                )
            clock = min((clocks or {}).get(name, now), now)
            if clock < row[f"{name}_clock"]:
                continue
            column = name if name in {"pinned", "private"} else f"{name}_at"
            changes[column] = int(value) if column == name else now if value else None
            changes[f"{name}_clock"] = clock
        if not changes:
            return await self._serialize(conn, row)
        result = await self._update(conn, row, row["body"], "edit", changes=changes)
        if changes.get("trashed_at") is not None:
            self.store.emit(
                "note.trashed",
                id,
                result["version"],
                private=result["private"],
                machine=result["machine"],
                origin=result["origin"],
                change_seq=result["change_seq"],
            )
        return result

    async def revisions(self, id: str) -> list[dict]:
        async with self.store.read() as conn:
            await self._row(conn, id, include_trashed=True)
            rows = await conn.execute_fetchall(
                (
                    "SELECT version,body,change_kind,changed_by,changed_at FROM "
                    "note_revision WHERE note_id=? ORDER BY version DESC"
                ),
                (id,),
            )
            return [{**dict(r), "changed_at": iso(r["changed_at"])} for r in rows]

    async def restore_revision(self, id: str, version: int) -> dict:
        self._write_allowed()
        if self.principal.kind != "session":
            raise Problem(403, "session_required", "Restore history from an owner session.")
        async with self.store.write() as conn:
            row = await self._row(conn, id, include_trashed=True)
            revisions = await conn.execute_fetchall(
                "SELECT body FROM note_revision WHERE note_id=? AND version=?", (id, version)
            )
            if not revisions:
                raise Problem(404, "not_found", "That revision is unavailable.")
            return await self._update(conn, row, revisions[0][0], "restore", coalesce=False)

    async def _delete(self, conn, row) -> None:
        from spunto.ai.vectors import purge_note_vectors

        await purge_note_vectors(conn, row["id"])
        copies = await conn.execute_fetchall(
            "SELECT *,rowid AS fts_id FROM note WHERE conflict_of=?", (row["id"],)
        )
        for copy in copies:
            await self._update(
                conn,
                dict(copy),
                copy["body"],
                "edit",
                changes={"conflict_of": None},
                coalesce=False,
            )
        outputs = await conn.execute_fetchall(
            "SELECT 'suggestion' AS kind,id FROM suggestion WHERE note_id=? UNION ALL "
            "SELECT 'annotation',plugin_id||'/'||note_id||'/'||kind FROM annotation WHERE "
            "note_id=?",
            (row["id"], row["id"]),
        )
        for output in outputs:
            await conn.execute(
                "INSERT OR REPLACE INTO tombstone VALUES (?,?,?,?)",
                (output["kind"], output["id"], await sequence(conn), now_ms()),
            )
        event_flags = {
            "private": bool(row["private"]),
            "machine": bool(row["machine"]),
            "origin": row["origin"],
        }
        await conn.execute("DELETE FROM note_fts WHERE rowid=?", (row["fts_id"],))
        await conn.execute("DELETE FROM note WHERE id=?", (row["id"],))
        deleted_seq = await sequence(conn)
        await conn.execute(
            (
                "INSERT INTO tombstone VALUES ('note',?,?,?) ON CONFLICT(kind,id) DO "
                "UPDATE SET "
                "change_seq=excluded.change_seq,deleted_at=excluded.deleted_at"
            ),
            (row["id"], deleted_seq, now_ms()),
        )
        self.store.emit(
            "note.purged", row["id"], row["version"], **event_flags, change_seq=deleted_seq
        )

    async def delete(self, id: str) -> None:
        if self.principal.kind != "session":
            raise Problem(403, "session_required", "Delete forever from an owner session.")
        async with self.store.write() as conn:
            row = await self._row(conn, id, include_trashed=True)
            if row["trashed_at"] is None:
                raise Problem(
                    409, "not_trashed", "Move this note to the trash before deleting forever."
                )
            await self._delete(conn, row)

    async def empty_trash(self) -> int:
        if self.principal.kind != "session":
            raise Problem(403, "session_required", "Empty the trash from an owner session.")
        async with self.store.write() as conn:
            rows = await conn.execute_fetchall(
                "SELECT *,rowid AS fts_id FROM note WHERE trashed_at IS NOT NULL"
            )
            for row in rows:
                await self._delete(conn, row)
            return len(rows)

    def _filters(self, label_paths: list[str], from_date, to_date) -> tuple[list[str], list]:
        visibility, args = self._visibility()
        conditions = [f"({visibility})"]
        for path in label_paths:
            try:
                key = label_key(path)
            except ValueError as exc:
                raise Problem(422, "validation_failed", str(exc)) from None
            conditions.append(
                "EXISTS (SELECT 1 FROM note_label nl JOIN label l ON nl.label_id=l.id "
                "WHERE nl.note_id=n.id AND (l.key=? OR "
                "substr(l.key,1,length(?)+1)=?||'/'))"
            )
            args.extend([key, key, key])
        if (start := _date_ms(from_date)) is not None:
            conditions.append("n.created_at>=?")
            args.append(start)
        if (end := _date_ms(to_date, end=True)) is not None:
            conditions.append("n.created_at<?")
            args.append(end)
        return conditions, args

    async def list(
        self,
        view: str = "timeline",
        labels: list[str] | None = None,
        from_date=None,
        to_date=None,
        limit: int = 50,
        cursor: str | None = None,
        render: bool = False,
    ) -> dict:
        builtins = {
            "timeline",
            "archive",
            "trash",
            "conflicts",
            "all",
            "pinned",
            "recent",
            "search",
        }
        conditions, args = self._filters(labels or [], from_date, to_date)
        if view not in builtins:
            from spunto.host.views import compile_filter

            host = getattr(self.store, "plugin_host", None)
            plugin_id, _, view_id = view.partition("/")
            state = host.states.get(plugin_id) if host else None
            manifests = host.manifests if host else {}
            views = manifests.get(plugin_id, {}).get("contributes", {}).get("views", [])
            chosen = next((v for v in views if v["id"] == view_id), None)
            if not state or state["state"] != "enabled" or state["switch"] != "on" or not chosen:
                raise Problem(404, "not_found", "That view is unavailable.")
            async with self.store.read() as conn:
                hidden = await conn.execute_fetchall(
                    "SELECT value FROM setting WHERE key='hidden_views'"
                )
            if hidden and view in json.loads(hidden[0][0]):
                raise Problem(404, "not_found", "That view is unavailable.")
            extra, extra_args = compile_filter(chosen["filter"], plugin_id=plugin_id, now=now_ms())
            conditions.extend(["n.trashed_at IS NULL", extra])
            args.extend(extra_args)
        else:
            conditions.extend(
                {
                    "timeline": ["n.trashed_at IS NULL", "n.archived_at IS NULL"],
                    "archive": ["n.trashed_at IS NULL", "n.archived_at IS NOT NULL"],
                    "trash": ["n.trashed_at IS NOT NULL"],
                    "conflicts": ["n.trashed_at IS NULL", "n.conflict_of IS NOT NULL"],
                    "pinned": ["n.trashed_at IS NULL", "n.pinned=1"],
                    "recent": ["n.trashed_at IS NULL"],
                    "search": ["n.trashed_at IS NULL", "n.archived_at IS NULL"],
                    "all": [],
                }[view]
            )
        fingerprint = hashlib.sha256(
            json.dumps([self.principal.id, conditions, args, view]).encode()
        ).hexdigest()[:16]
        position = _cursor(cursor, fingerprint)
        recent = view in {"recent", "search"}
        if position:
            if (
                len(position) != (2 if recent else 3)
                or not all(type(value) is int for value in position[:-1])
                or not isinstance(position[-1], str)
            ):
                raise Problem(422, "validation_failed", "Invalid note cursor.")
            conditions.append(
                "(n.created_at,n.id)<(?,?)" if recent else "(n.pinned,n.created_at,n.id)<(?,?,?)"
            )
            args.extend(position)
        order = (
            "n.created_at DESC,n.id DESC" if recent else "n.pinned DESC,n.created_at DESC,n.id DESC"
        )
        async with self.store.read() as conn:
            # The fragments are fixed above; all caller values remain bound parameters.
            rows = await conn.execute_fetchall(
                "SELECT n.* FROM note n WHERE "  # noqa: S608
                + " AND ".join(conditions)
                + f" ORDER BY {order} LIMIT ?",
                [*args, limit + 1],
            )
            result = [await self._serialize(conn, row, render=render) for row in rows[:limit]]
        last = rows[limit - 1] if len(rows) > limit else None
        return {
            "notes": result,
            "next_cursor": _next(
                (
                    [last["created_at"], last["id"]]
                    if recent
                    else [last["pinned"], last["created_at"], last["id"]]
                ),
                fingerprint,
            )
            if last
            else None,
        }

    async def search(
        self,
        query: str = "",
        labels: list[str] | None = None,
        from_date=None,
        to_date=None,
        include_archived: bool = False,
        sort: str = "relevance",
        limit: int = 50,
        cursor: str | None = None,
        *,
        render: bool = False,
        mode: str = "hybrid",
        machine: bool | None = None,
    ) -> dict:
        if len(query) > 500:
            raise Problem(422, "validation_failed", "query must have at most 500 characters.")
        match, inline = search_query(query)
        if not match and not inline and query.strip():
            # Punctuation alone has no words to match; listing every note would read as hits.
            return {"hits": [], "next_cursor": None, "semantic": "off"}
        match = fold(match)
        conditions, args = self._filters([*(labels or []), *inline], from_date, to_date)
        conditions.append("n.trashed_at IS NULL")
        if not include_archived:
            conditions.append("n.archived_at IS NULL")
        if machine is not None:
            conditions.append("n.machine=?")
            args.append(int(machine))

        host = getattr(self.store, "plugin_host", None)
        sources = bool(
            host
            and any(
                host.states[plugin_id]["state"] == "enabled" and plugin.handlers["search_source"]
                for plugin_id, plugin in host.plugins.items()
            )
        )
        if mode == "hybrid" and match and sources:
            fingerprint = hashlib.sha256(
                json.dumps(
                    [
                        self.principal.id,
                        match,
                        conditions,
                        args,
                        "hybrid",
                        sort,
                        host.plugins_rev,
                    ]
                ).encode()
            ).hexdigest()[:16]
            position = _cursor(cursor, fingerprint)
            semantic = "on"
            if position is None:
                keyword, rankings = await asyncio.gather(
                    self.search(
                        query,
                        labels,
                        from_date,
                        to_date,
                        include_archived,
                        sort,
                        50,
                        render=False,
                        mode="keyword",
                        machine=machine,
                    ),
                    host.search_sources(query, principal=self.principal),
                )
                semantic = "on" if any(ok for _, ok in rankings) else "unavailable"
                keyword_ids = [hit["id"] for hit in keyword["hits"]]
                meaning_ids = {note_id for ids, _ in rankings for note_id in ids}
                # Search extensions can only nominate IDs; core enforces every scope and filter.
                if meaning_ids:
                    async with self.store.read() as conn:
                        allowed = await conn.execute_fetchall(
                            "SELECT n.id FROM note n WHERE "  # noqa: S608
                            + " AND ".join(conditions)
                            + " AND n.private=0 AND n.machine=0 AND n.id IN ("
                            + ",".join("?" for _ in meaning_ids)
                            + ")",
                            [*args, *meaning_ids],
                        )
                    meaning_ids.intersection_update(row[0] for row in allowed)
                scores = {}
                for ids in [keyword_ids, *[ids for ids, ok in rankings if ok]]:
                    for rank, note_id in enumerate(ids, 1):
                        if note_id in keyword_ids or note_id in meaning_ids:
                            scores[note_id] = scores.get(note_id, 0) + 1 / (60 + rank)
                ordered = sorted(scores, key=lambda note_id: (-scores[note_id], note_id))[:50]
                position = [
                    [
                        note_id,
                        "both"
                        if note_id in keyword_ids and note_id in meaning_ids
                        else "keyword"
                        if note_id in keyword_ids
                        else "meaning",
                    ]
                    for note_id in ordered
                ]
            if (
                not isinstance(position, list)
                or len(position) > 50
                or any(
                    not isinstance(item, list)
                    or len(item) != 2
                    or not isinstance(item[0], str)
                    or len(item[0]) != 36
                    or item[1] not in {"keyword", "meaning", "both"}
                    for item in position
                )
                or len({item[0] for item in position}) != len(position)
            ):
                raise Problem(422, "validation_failed", "Invalid hybrid search cursor.")
            if not position:
                return {"hits": [], "next_cursor": None, "semantic": semantic}
            async with self.store.read() as conn:
                rows = await conn.execute_fetchall(
                    "SELECT n.* FROM note n WHERE "  # noqa: S608
                    + " AND ".join(conditions)
                    + " AND n.id IN ("
                    + ",".join("?" for _ in position)
                    + ")",
                    [*args, *[item[0] for item in position]],
                )
                found = {row["id"]: row for row in rows}
                eligible = [
                    (note_id, kind)
                    for note_id, kind in position
                    if note_id in found
                    and (
                        kind == "keyword"
                        or not found[note_id]["private"]
                        and not found[note_id]["machine"]
                    )
                ]
                hits = [
                    {**await self._serialize(conn, found[note_id], render=render), "match": kind}
                    for note_id, kind in eligible[:limit]
                ]
            remaining = [list(item) for item in eligible[limit:]]
            return {
                "hits": hits,
                "next_cursor": _next(remaining, fingerprint) if remaining else None,
                "semantic": semantic,
            }

        async def full_rows(conn, candidates):
            ids = [row["id"] for row in candidates]
            if not ids:
                return []
            found = await conn.execute_fetchall(
                "SELECT * FROM note WHERE id IN (" + ",".join("?" for _ in ids) + ")",  # noqa: S608
                ids,
            )
            by_id = {row["id"]: row for row in found}
            return [by_id[note_id] for note_id in ids]

        if not match:
            view = "recent" if include_archived else "search"
            fingerprint = hashlib.sha256(
                json.dumps([self.principal.id, conditions, args, view]).encode()
            ).hexdigest()[:16]
            position = _cursor(cursor, fingerprint)
            if position is not None:
                if (
                    len(position) != 2
                    or type(position[0]) is not int
                    or not isinstance(position[1], str)
                ):
                    raise Problem(422, "validation_failed", "Invalid note cursor.")
                conditions.append("(n.created_at,n.id)<(?,?)")
                args.extend(position)
            async with self.store.read() as conn:
                candidates = await conn.execute_fetchall(
                    "SELECT n.id,n.created_at FROM note n WHERE "  # noqa: S608
                    + " AND ".join(conditions)
                    + " ORDER BY n.created_at DESC,n.id DESC LIMIT ?",
                    [*args, limit + 1],
                )
                rows = await full_rows(conn, candidates[:limit])
                hits = [
                    {**await self._serialize(conn, row, render=render), "match": "keyword"}
                    for row in rows
                ]
            last = candidates[limit - 1] if len(candidates) > limit else None
            return {
                "hits": hits,
                "next_cursor": _next([last["created_at"], last["id"]], fingerprint)
                if last
                else None,
                "semantic": "off",
            }
        ranked = sort == "relevance"
        fingerprint = hashlib.sha256(
            json.dumps([self.principal.id, match, conditions, args, ranked]).encode()
        ).hexdigest()[:16]
        position = _cursor(cursor, fingerprint)
        if position is not None and (
            len(position) > 50
            or not all(isinstance(value, str) and len(value) == 36 for value in position)
            or len(set(position)) != len(position)
        ):
            raise Problem(422, "validation_failed", "Invalid search cursor.")
        conditions.append("note_fts MATCH ?")
        args.append(match)
        join = " JOIN note_fts ON note_fts.rowid=n.rowid"
        async with self.store.read() as conn:
            if position is not None:
                if not position:
                    return {"hits": [], "next_cursor": None, "semantic": "off"}
                conditions.append("n.id IN (" + ",".join("?" for _ in position) + ")")
                candidates = await conn.execute_fetchall(
                    "SELECT n.id FROM note n" + join + " WHERE " + " AND ".join(conditions),  # noqa: S608
                    [*args, *position],
                )
                visible_ids = {row["id"] for row in candidates}
                candidates = [{"id": note_id} for note_id in position if note_id in visible_ids]
            else:
                # FTS5's rank ordering can stream a limited result. Calling bm25 in
                # SELECT and sorting entire note bodies materializes every match.
                order = "note_fts.rank" if ranked else "n.created_at DESC,n.id DESC"
                if ranked:
                    conditions.append("note_fts.rank MATCH 'bm25(2.0,1.0,0.5)'")
                query = (
                    "SELECT n.id"  # noqa: S608
                    + (",n.updated_at,note_fts.rank AS score" if ranked else "")
                    + " FROM note n"
                    + join
                    + " WHERE "
                    + " AND ".join(conditions)
                    + f" ORDER BY {order} LIMIT 50"
                )
                if ranked:
                    # Equal scores (a duplicate, say) put the newer note first; only
                    # the 50 kept candidates are sorted again.
                    query = (
                        f"SELECT id FROM ({query}) AS ranked"  # noqa: S608
                        " ORDER BY score,updated_at DESC,id DESC"
                    )
                candidates = await conn.execute_fetchall(query, args)
            rows = await full_rows(conn, candidates[:limit])
            hits = [
                {**await self._serialize(conn, row, render=render), "match": "keyword"}
                for row in rows
            ]
        # BM25 depends on live corpus statistics. Carry the bounded remaining candidates,
        # rather than live scores, so unrelated new notes cannot invalidate the next page.
        remaining = [row["id"] for row in candidates[limit:]]
        return {
            "hits": hits,
            "next_cursor": _next(remaining, fingerprint) if remaining else None,
            "semantic": "off",
        }

    async def labels(
        self, prefix: str | None = None, limit: int = 500, *, include_archived: bool = True
    ) -> dict:
        """Labels with note counts. The web rail leaves archived notes out, so its
        counts match the label views, which never show archived notes."""
        visibility, args = self._visibility()
        archived = "" if include_archived else "AND n.archived_at IS NULL "
        async with self.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT n.id,l.key,l.path FROM label l JOIN note_label nl ON "  # noqa: S608
                "nl.label_id=l.id "  # noqa: S608
                "JOIN note n ON nl.note_id=n.id WHERE n.trashed_at IS NULL "
                f"{archived}AND ({visibility}) ORDER BY l.id",  # noqa: S608
                args,
            )
        counts, displays = {}, {}
        for row in rows:
            keys, paths = row["key"].split("/"), row["path"].split("/")
            for depth in range(1, len(keys) + 1):
                key, path = "/".join(keys[:depth]), "/".join(paths[:depth])
                displays.setdefault(key, path)
                sets = counts.setdefault(key, [set(), set()])
                sets[0].add(row["id"])
                if depth == len(keys):
                    sets[1].add(row["id"])
        prefix_key = fold(prefix or "")
        result = [
            {"path": displays[k], "count": len(v[0]), "own_count": len(v[1])}
            for k, v in sorted(counts.items())
            if fold(displays[k]).startswith(prefix_key)
        ]
        return {"labels": result[:limit], "total": len(result)}

    async def rewrite_label(self, old: str, new: str | None) -> int:
        if self.principal.kind != "session":
            raise Problem(
                403, "session_required", "Change the label vocabulary from an owner session."
            )
        try:
            label_key(old)
            if new is not None:
                label_key(new)
        except ValueError as exc:
            raise Problem(422, "validation_failed", str(exc)) from None
        async with self.store.write() as conn:
            rows = await conn.execute_fetchall("SELECT *,rowid AS fts_id FROM note")
            changed = 0
            for row in rows:
                try:
                    body = rewrite_labels(row["body"], old, new)
                except ValueError as exc:
                    raise Problem(422, "validation_failed", str(exc)) from None
                if body != row["body"]:
                    await self._update(conn, dict(row), body, "label_rewrite", coalesce=False)
                    changed += 1
            return changed
