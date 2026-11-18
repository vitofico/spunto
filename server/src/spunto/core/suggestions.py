"""Validated plugin proposals and atomic owner decisions through the core writer."""

import json
import math
import uuid
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta

from spunto.api.errors import Problem
from spunto.core.note_links import append_link, link_text, proposal_target, visible_link
from spunto.core.note_links import targets as note_link_targets
from spunto.core.notes import MAX_BODY, Notes, _body, iso, sequence
from spunto.core.plugin_notes import PluginNotes
from spunto.core.text import label_key, labels, rewrite_labels, set_labels
from spunto.host.manifest import ManifestError
from spunto.store.db import now_ms

ACTIONS = {
    "add_label",
    "set_title",
    "append_text",
    "replace_body",
    "merge_notes",
    "label_change",
    "open",
}
EXPIRY_MS = 14 * 86_400_000


def _text(value, maximum, *, empty=False):
    if not isinstance(value, str) or not int(not empty) <= len(value) <= maximum:
        raise ManifestError("suggestion text has an invalid length")
    return value


def _note_id(value):
    try:
        parsed = uuid.UUID(value)
        if parsed.version != 7:
            raise ValueError
        return str(parsed)
    except ValueError, TypeError, AttributeError:
        raise ManifestError("suggestion needs a UUIDv7 note id") from None


def validate_payload(action: str, payload: dict) -> dict:
    if action not in ACTIONS or not isinstance(payload, dict):
        raise ManifestError("unknown suggestion action or invalid payload")
    fields = {
        "add_label": {"path"},
        "set_title": {"title"},
        "append_text": {"text"},
        "replace_body": {"body"},
        "merge_notes": {"source_id", "target_id"},
        "label_change": {"old", "new", "note_ids"},
        "open": {"note_id", "plugin_id", "view"},
    }[action]
    if set(payload) - fields:
        raise ManifestError("suggestion payload has unknown fields")
    result = dict(payload)
    try:
        if action == "add_label":
            label_key(result["path"])
        elif action == "set_title":
            _text(result["title"], 80)
            if "\n" in result["title"] or "\r" in result["title"]:
                raise ManifestError("suggestion title must be one line")
        elif action == "append_text":
            _text(result["text"], 4000)
        elif action == "replace_body":
            _text(result["body"], MAX_BODY, empty=True)
        elif action == "merge_notes":
            result = {key: _note_id(result[key]) for key in ("source_id", "target_id")}
            if result["source_id"] == result["target_id"]:
                raise ManifestError("cannot merge a note with itself")
        elif action == "label_change":
            if set(result) == {"old", "new"}:
                label_key(result["old"])
                if result["new"] is not None:
                    label_key(result["new"])
            elif set(result) == {"new", "note_ids"}:
                label_key(result["new"])
                ids = result["note_ids"]
                if not isinstance(ids, list) or not 1 <= len(ids) <= 10:
                    raise ManifestError("label cluster needs one to ten notes")
                result["note_ids"] = [_note_id(i) for i in ids]
                if len(set(result["note_ids"])) != len(ids):
                    raise ManifestError("label cluster needs distinct notes")
            else:
                raise ManifestError("label change needs a rename or a note cluster")
        elif set(result) == {"note_id"}:
            result["note_id"] = _note_id(result["note_id"])
        elif set(result) == {"plugin_id", "view"}:
            _text(result["plugin_id"], 24)
            _text(result["view"], 40)
        else:
            raise ManifestError("open must name one note or one plugin view")
    except KeyError, ValueError, TypeError:
        raise ManifestError("suggestion payload is invalid") from None
    return result


def is_link_proposal(row) -> bool:
    return row["plugin_id"] == "links" and row["action"] == "append_text"


def manages_receipts(principal):
    return principal.owner and principal.writable


async def receipt_actor(conn, domain, row):
    principal = domain.principal
    if not manages_receipts(principal):
        return False
    if principal.workspace_role == "owner" or row["decided_by"] == principal.id:
        return True
    if not principal.user_id or getattr(domain.store, "_context", None) is None:
        return False
    rows = await conn.execute_fetchall(
        "SELECT spunto.credential_belongs_to_actor(?)", (row["decided_by"],)
    )
    return bool(rows[0][0])


def receipt(row, note_version):
    if (
        row["status"] == "accepted"
        and note_version is not None
        and row.get("receipt_version") == note_version
        and not row["receipt_hidden"]
    ):
        return {"note_version": note_version, "undoable": True}
    return None


def value(row: dict, *, payload: dict | None = None, note_version=None) -> dict:
    payload = json.loads(row["payload"]) if payload is None else payload
    one_tap = (
        row["action"] in {"add_label", "set_title"}
        or is_link_proposal(row)
        or (
            row["plugin_id"] == "voice-memos"
            and row["action"] == "replace_body"
            and payload.get("_recording_append") is True
        )
    )
    return {
        "id": row["id"],
        "plugin": row["plugin_id"],
        "note_id": row["note_id"],
        "note_version": row["note_version"],
        "action": row["action"],
        "payload": {key: item for key, item in payload.items() if key != "_recording_append"},
        **({"one_tap": True} if one_tap and row["status"] == "pending" else {}),
        "confidence": row["confidence"],
        "reason": row["reason"],
        "status": row["status"],
        "change_seq": row["change_seq"],
        "created_at": iso(row["created_at"]),
        "decided_at": iso(row["decided_at"]),
        **({"receipt": receipt_value} if (receipt_value := receipt(row, note_version)) else {}),
    }


async def suggest(
    ctx,
    note_id,
    action,
    payload,
    *,
    note_version=None,
    confidence=None,
    reason="",
    model=None,
    source_versions=None,
    connection=None,
):
    ctx.check("suggest")
    ctx.check_write()
    if note_id is not None:
        note_id = _note_id(note_id)
    payload = validate_payload(action, payload)
    _text(reason, 280, empty=True)
    if model is not None:
        _text(model, 200)
    if confidence is not None and (
        type(confidence) not in {int, float}
        or not math.isfinite(confidence)
        or not 0 <= confidence <= 1
    ):
        raise ManifestError("suggestion confidence must be between zero and one")
    if note_id is None and action not in {"label_change", "open"}:
        raise ManifestError("this action needs a note")
    if action == "label_change" and note_id is not None:
        raise ManifestError("label changes belong to Review")
    if action == "replace_body" and note_version is None:
        raise ManifestError("replace_body requires the version it was computed from")
    if note_version is not None and (type(note_version) is not int or note_version < 1):
        raise ManifestError("suggestion note version must be positive")
    adapter = PluginNotes(ctx)
    now = ctx.clock()
    async with _decision_transaction(adapter.store, connection) as conn:
        ctx.check("suggest")
        await adapter._guard_sources(conn, source_versions)
        if note_id is not None:
            row = await adapter.core._row(conn, _note_id(note_id))
            if adapter.egress and row["machine"]:
                raise ManifestError("machine notes cannot be suggestion input")
            if note_version is not None and note_version != row["version"]:
                raise Problem(
                    409, "version_conflict", "The note changed; recompute this suggestion."
                )
            note_version = row["version"]
        elif note_version is not None:
            raise ManifestError("a global suggestion cannot carry a note version")
        if ctx.plugin_id == "links" and action == "append_text":
            target_id = proposal_target(payload)
            if target_id is None or target_id == note_id:
                raise ManifestError("a link suggestion needs one target note")
            await adapter.core._row(conn, target_id)
            payload = {"text": link_text(target_id)}
        if action == "add_label":
            visibility, _ = adapter.core._visibility()
            exists = await conn.execute_fetchall(
                "SELECT l.id FROM label l JOIN note_label nl ON nl.label_id=l.id "  # noqa: S608
                "JOIN note n ON n.id=nl.note_id WHERE l.key=? AND n.trashed_at IS NULL "
                f"AND ({visibility}) LIMIT 1",  # noqa: S608
                (label_key(payload["path"]),),
            )
            payload["is_new"] = not bool(exists)
        if action == "merge_notes":
            if note_id != payload["target_id"]:
                raise ManifestError("a merge suggestion must belong to its target note")
            source = await adapter.core._row(conn, payload["source_id"])
            if adapter.egress and source["machine"]:
                raise ManifestError("machine notes cannot be suggestion input")
            payload.update(source_version=source["version"], target_version=note_version)
        if action == "label_change" and "note_ids" in payload:
            if await conn.execute_fetchall(
                "SELECT 1 FROM label WHERE key=?", (label_key(payload["new"]),)
            ):
                raise ManifestError("a new cluster label must not already exist")
            versions = {}
            for target_id in payload["note_ids"]:
                target = await adapter.core._row(conn, target_id)
                if target["machine"] or target["private"] or labels(target["body"]):
                    raise ManifestError("label cluster needs eligible unlabeled notes")
                versions[target_id] = target["version"]
            payload["note_versions"] = versions
        if action == "open":
            if "note_id" in payload:
                await adapter.core._row(conn, payload["note_id"])
            else:
                manifest = ctx._host.manifests.get(payload["plugin_id"], {})
                if not any(
                    v["id"] == payload["view"]
                    for v in manifest.get("contributes", {}).get("views", [])
                ):
                    raise ManifestError("open names an unknown view")
        pending = await conn.execute_fetchall(
            "SELECT * FROM suggestion WHERE plugin_id=? AND note_id IS ? AND status='pending'",
            (ctx.plugin_id, note_id),
        )
        replaced = [
            s
            for s in pending
            if s["action"] == action
            and (
                (
                    action == "add_label"
                    and label_key(json.loads(s["payload"])["path"]) == label_key(payload["path"])
                )
                or (action == "label_change" and json.loads(s["payload"]) == payload)
                or action not in {"add_label", "label_change"}
            )
        ]
        if len(pending) - len(replaced) >= 3:
            raise ManifestError("at most three pending suggestions per plugin and note")
        if note_id is None and action == "label_change":
            day = datetime.fromtimestamp(now / 1000, UTC)
            week = (day - timedelta(days=day.weekday())).replace(
                hour=0, minute=0, second=0, microsecond=0
            )
            count = await conn.execute_fetchall(
                "SELECT count(*) FROM suggestion WHERE plugin_id=? "
                "AND note_id IS NULL AND action='label_change' AND created_at>=?",
                (ctx.plugin_id, int(week.timestamp() * 1000)),
            )
            if count[0][0] >= 3:
                raise ManifestError("at most three label-level suggestions per plugin and week")
        for prior in replaced:
            await conn.execute(
                "UPDATE suggestion SET status='superseded',change_seq=? WHERE id=?",
                (await sequence(conn), prior["id"]),
            )
        record = {
            "id": str(uuid.uuid7()),
            "plugin_id": ctx.plugin_id,
            "note_id": note_id,
            "note_version": note_version,
            "action": action,
            "payload": json.dumps(payload),
            "confidence": confidence,
            "reason": reason,
            "status": "pending",
            "model": model,
            "change_seq": await sequence(conn),
            "created_at": now,
            "decided_at": None,
            "decided_by": None,
        }
        await conn.execute(
            "INSERT INTO suggestion(id,plugin_id,note_id,note_version,action,payload,confidence,"
            "reason,status,model,change_seq,created_at,decided_at,decided_by) "
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            tuple(record.values()),
        )
        ctx.check("suggest")
    return value(record)


async def owner_credential(ctx, conn, credential_id) -> bool:
    author = await conn.execute_fetchall(
        "SELECT id FROM credential WHERE id=? AND kind IN ('session','device')",
        (credential_id,),
    )
    if not author:
        return False
    if getattr(ctx._host.store, "_context", None) is None:
        return True
    hosted = await conn.execute_fetchall(
        "SELECT spunto.owner_authored_credential(?)", (credential_id,)
    )
    return bool(hosted[0][0])


async def _quiet_settings(ctx, conn, note):
    ctx.check("suggest")
    ctx.check_write()
    if (
        ctx.plugin_id
        not in {"label-suggestions", "title-summary", "voice-memos", "link-enrichment"}
        or note["origin"] != "owner"
        or note["private"]
        or note["machine"]
        or note["trashed_at"] is not None
        or note["conflict_of"] is not None
    ):
        return None
    caller = ctx.principal
    if caller is not None and (not caller.owner or caller.workspace_role != "owner"):
        return None
    rows = await conn.execute_fetchall(
        "SELECT settings FROM plugin WHERE id=? AND state='enabled' AND switch='on' "
        "AND origin='bundled'",
        (ctx.plugin_id,),
    )
    if not rows:
        return None
    settings = json.loads(rows[0]["settings"])
    if settings.get("change_mode") != "automatic":
        return None
    if not await owner_credential(ctx, conn, note["created_by"]):
        return None
    return settings


async def quiet_eligible(ctx, conn, note) -> bool:
    return await _quiet_settings(ctx, conn, note) is not None


async def _record_receipt(conn, suggestion_id, revision):
    await conn.execute(
        "UPDATE suggestion SET applied_version=?,receipt_version=? WHERE id=?",
        (revision, revision, suggestion_id),
    )


async def apply_quiet(ctx, conn, note, suggestion_id, body):
    """Accept an existing proposal for the checked note inside the caller's transaction."""
    if not await quiet_eligible(ctx, conn, note):
        return None
    if body == note["body"]:
        return None
    rows = await conn.execute_fetchall(
        "SELECT id FROM suggestion WHERE id=? AND plugin_id=? AND note_id=? AND note_version=? "
        "AND status='pending' AND action IN ('set_title','append_text','replace_body')",
        (suggestion_id, ctx.plugin_id, note["id"], note["version"]),
    )
    if not rows:
        return None
    adapter = PluginNotes(ctx)
    result = await adapter.core._update(conn, note, body, "suggestion", coalesce=False)
    await conn.execute(
        "UPDATE suggestion SET status='accepted',change_seq=?,decided_at=?,decided_by=? WHERE id=?",
        (await sequence(conn), ctx.clock(), adapter.core.principal.id, suggestion_id),
    )
    await _record_receipt(conn, suggestion_id, note["version"])
    adapter.store.emit("suggestion.decided", note["id"], note["version"])
    return result


async def suggest_many(
    ctx,
    note_id,
    items,
    *,
    note_version,
    auto_accept_existing=False,
    threshold=0.9,
    source_versions=None,
):
    """Commit one validated label response, including opt-in decisions, atomically."""
    ctx.check("suggest")
    ctx.check_write()
    note_id = _note_id(note_id)
    if not isinstance(items, list) or len(items) > 3 or type(note_version) is not int:
        raise ManifestError("invalid label response")
    if (
        type(auto_accept_existing) is not bool
        or type(threshold) not in {int, float}
        or not 0.7 <= threshold <= 1
    ):
        raise ManifestError("invalid automatic acceptance settings")
    prepared, seen = [], set()
    for item in items:
        if (
            not isinstance(item, dict)
            or set(item) - {"action", "payload", "confidence"}
            or item.get("action") != "add_label"
        ):
            raise ManifestError("bulk proposals must be labels")
        payload = validate_payload("add_label", item.get("payload"))
        confidence = item.get("confidence")
        if (
            type(confidence) not in {int, float}
            or not math.isfinite(confidence)
            or not 0 <= confidence <= 1
        ):
            raise ManifestError("invalid label confidence")
        key = label_key(payload["path"])
        if key in seen:
            raise ManifestError("duplicate label proposal")
        seen.add(key)
        prepared.append((payload, confidence, key))
    adapter, records, accepted = PluginNotes(ctx), [], []
    now = ctx.clock()
    async with adapter.store.write() as conn:
        ctx.check("suggest")
        try:
            note = await adapter.core._row(conn, note_id)
        except Problem as error:
            if error.status == 404:
                return []
            raise
        if note["version"] != note_version or note["private"] or note["machine"]:
            return []
        await adapter._guard_sources(conn, source_versions)
        automatic = await _quiet_settings(ctx, conn, note)
        dismissed = await conn.execute_fetchall(
            "SELECT payload FROM suggestion WHERE plugin_id=? AND note_id=? "
            "AND action='add_label' AND status='dismissed'",
            (ctx.plugin_id, note_id),
        )
        excluded = {label_key(json.loads(row[0])["path"]) for row in dismissed}
        excluded.update(item.key for item in labels(note["body"]))
        visibility, _ = adapter.core._visibility()
        pending = await conn.execute_fetchall(
            "SELECT * FROM suggestion WHERE plugin_id=? AND note_id=? AND status='pending'",
            (ctx.plugin_id, note_id),
        )
        for prior in pending:
            await conn.execute(
                "UPDATE suggestion SET status='superseded',change_seq=? WHERE id=?",
                (await sequence(conn), prior["id"]),
            )
        for payload, confidence, key in prepared:
            if key in excluded:
                continue
            exists = await conn.execute_fetchall(
                "SELECT l.path FROM label l JOIN note_label nl ON nl.label_id=l.id "  # noqa: S608
                "JOIN note n ON n.id=nl.note_id WHERE l.key=? "
                "AND n.trashed_at IS NULL AND n.machine=0 AND n.private=0 "
                f"AND ({visibility}) LIMIT 1",
                (key,),
            )
            payload = {
                "path": exists[0][0] if exists else payload["path"],
                "is_new": not bool(exists),
            }
            auto = (
                automatic is not None
                and bool(exists)
                and confidence >= automatic.get("confidence_threshold", 0.9)
            )
            record = {
                "id": str(uuid.uuid7()),
                "plugin_id": ctx.plugin_id,
                "note_id": note_id,
                "note_version": note_version,
                "action": "add_label",
                "payload": json.dumps(payload),
                "confidence": confidence,
                "reason": "",
                "status": "accepted" if auto else "pending",
                "model": None,
                "change_seq": await sequence(conn),
                "created_at": now,
                "decided_at": now if auto else None,
                "decided_by": adapter.core.principal.id if auto else None,
            }
            await conn.execute(
                "INSERT INTO suggestion(id,plugin_id,note_id,note_version,action,payload,"
                "confidence,reason,status,model,change_seq,created_at,decided_at,decided_by) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tuple(record.values()),
            )
            records.append(record)
            if auto:
                accepted.append(record)
        if accepted:
            original_body = note["body"]
            body = set_labels(
                original_body, [json.loads(row["payload"])["path"] for row in accepted], []
            )
            await adapter.core._update(conn, note, body, "label_suggestion", coalesce=False)
            for record in accepted:
                await _record_receipt(conn, record["id"], note["version"])
                record.update(receipt_version=note["version"], receipt_hidden=0)
            adapter.store.emit("suggestion.decided", note_id, note["version"])
        ctx.check("suggest")
    return [value(record, note_version=note["version"]) for record in records]


async def _rename_cluster(conn, domain, visibility, payload):
    affected = await conn.execute_fetchall(
        "SELECT n.*,n.rowid AS fts_id FROM note n WHERE " + visibility  # noqa: S608
    )
    changed = 0
    for target in affected:
        try:
            body = rewrite_labels(target["body"], payload["old"], payload["new"])
        except ValueError:
            raise Problem(
                422, "validation_failed", "The rename creates an invalid label."
            ) from None
        if body != target["body"]:
            await domain._update(conn, dict(target), body, "label_rewrite", coalesce=False)
            changed += 1
    return {"changed": changed}


async def _link_destination(conn, domain, row):
    try:
        payload = json.loads(row["payload"])
    except json.JSONDecodeError:
        payload = None
    target_id = proposal_target(payload)
    if target_id is None or target_id == row["note_id"]:
        raise Problem(404, "not_found", "That suggestion is unavailable.")
    return await domain._row(conn, target_id)


async def visible_payload(
    conn, domain, row, *, include_trashed=False, source=None, link_titles=True
) -> dict | None:
    if is_link_proposal(row):
        try:
            if source is None:
                source = await domain._row(conn, row["note_id"])
            if source["trashed_at"] is not None:
                return None
            target = await _link_destination(conn, domain, row)
        except Problem as error:
            if error.status != 404:
                raise
            return None
        return {"text": visible_link(target) if link_titles else link_text(target["id"])}
    payload = json.loads(row["payload"])
    related = []
    if row["action"] == "merge_notes":
        related = [payload["source_id"], payload["target_id"]]
    elif row["action"] == "open" and "note_id" in payload:
        related = [payload["note_id"]]
    elif row["action"] == "label_change" and "note_ids" in payload:
        related = payload["note_ids"]
    for note_id in related:
        try:
            await domain._row(conn, note_id, include_trashed=include_trashed)
        except Problem as error:
            if error.status != 404:
                raise
            return None
    if row["action"] == "open" and "plugin_id" in payload:
        enabled = await conn.execute_fetchall(
            "SELECT id FROM plugin WHERE id=? AND state='enabled'", (payload["plugin_id"],)
        )
        if not enabled:
            return None
    return payload


async def sync_value(conn, domain, row):
    row = dict(row)
    version = None
    if (
        row["note_id"]
        and row["status"] == "accepted"
        and row["receipt_version"] is not None
        and await receipt_actor(conn, domain, row)
    ):
        try:
            note = await domain._row(conn, row["note_id"])
        except Problem as error:
            if error.status != 404:
                raise
        else:
            version = note["version"]
    if not is_link_proposal(row):
        return value(row, note_version=version)
    # Devices can accept an append offline. Keep the cached caption independent of note titles.
    payload = await visible_payload(conn, domain, row, link_titles=False)
    if payload is None:
        if not receipt(row, version):
            row.update(status="superseded", note_id=None)
        payload = {}
    return value(row, payload=payload, note_version=version)


async def pending(store, principal, *, note_id=None, scope="note", limit=100):
    if scope not in {"note", "review"} or type(limit) is not int or not 1 <= limit <= 500:
        raise Problem(422, "validation_failed", "Use note or review scope and a limit of 1 to 500.")
    domain = Notes(store, principal)
    visibility, _ = domain._visibility()
    active = "(s.status='pending' AND p.state='enabled' AND s.created_at>?)"
    if scope == "note" and manages_receipts(principal):
        active += " OR (s.status='accepted' AND s.receipt_hidden=0 AND s.receipt_version=n.version)"
    where = [
        f"({active})",
        "((n.trashed_at IS NULL AND n.id IS NOT NULL AND "
        f"({visibility})) OR (s.note_id IS NULL AND ?))",  # noqa: S608
    ]
    args = [now_ms() - EXPIRY_MS, principal.owner]
    if note_id is not None:
        where.append("s.note_id=?")
        args.append(note_id)
    async with store.read() as conn:
        records = await conn.execute_fetchall(
            "SELECT s.*,n.version AS current_version FROM suggestion s "  # noqa: S608
            "JOIN plugin p ON p.id=s.plugin_id LEFT JOIN note n ON n.id=s.note_id WHERE "
            + " AND ".join(where)
            + " ORDER BY p.position,s.created_at,s.id LIMIT ?",
            [*args, limit],
        )
        visible = []
        for row in records:
            if row["status"] == "accepted" and not await receipt_actor(conn, domain, row):
                continue
            payload = await visible_payload(conn, domain, row)
            if payload is not None or row["status"] == "accepted":
                visible.append(
                    value(dict(row), payload=payload or {}, note_version=row["current_version"])
                )
    return {"suggestions": visible}


@asynccontextmanager
async def _decision_transaction(store, connection):
    if connection is not None:
        yield connection
    else:
        async with store.write() as conn:
            yield conn


async def _undo(conn, domain, row, note, now):
    if row["undone_at"] is not None:
        return False, None
    ancestor = []
    if (
        note
        and row["status"] == "accepted"
        and row["receipt_version"] == note["version"]
        and note["trashed_at"] is None
    ):
        # Suggestion writes keep this base in history. A batch of up to three labels
        # also keeps it inside the latest revisions throughout independent undos.
        ancestor = await conn.execute_fetchall(
            "SELECT body FROM note_revision WHERE note_id=? AND version=?",
            (note["id"], row["applied_version"] - 1),
        )
    if not ancestor:
        raise Problem(
            409, "suggestion_undo_stale", "The note changed. Open history to see the change."
        )
    body = ancestor[0]["body"]
    remaining = []
    if row["action"] == "add_label":
        remaining = await conn.execute_fetchall(
            "SELECT id,payload FROM suggestion WHERE note_id=? AND applied_version=? "
            "AND receipt_version=? AND status='accepted' AND id<>? ORDER BY id",
            (note["id"], row["applied_version"], note["version"], row["id"]),
        )
        body = set_labels(body, [json.loads(item["payload"])["path"] for item in remaining], [])
    kind = "label_suggestion_undo" if row["action"] == "add_label" else "suggestion_undo"
    await domain._update(conn, note, body, kind, coalesce=False)
    row.update(status="dismissed", change_seq=await sequence(conn), undone_at=now)
    await conn.execute(
        "UPDATE suggestion SET status='dismissed',change_seq=?,undone_at=?,decided_at=?,"
        "decided_by=? WHERE id=?",
        (row["change_seq"], now, now, domain.principal.id, row["id"]),
    )
    for item in remaining:
        await conn.execute(
            "UPDATE suggestion SET receipt_version=?,change_seq=? WHERE id=?",
            (note["version"], await sequence(conn), item["id"]),
        )
    row["decided_at"] = now
    domain.store.emit("suggestion.decided", note["id"], note["version"])
    return True, await domain._serialize(conn, note)


async def decide(
    store, principal, suggestion_id, decision, *, connection=None, reviewed_version=None
):
    if not principal.writable:
        raise Problem(403, "read_only_token", "This token is read-only.")
    if decision not in {"accept", "dismiss", "undo"}:
        raise Problem(422, "validation_failed", "Use accept, dismiss or undo.")
    if decision == "undo" and not manages_receipts(principal):
        raise Problem(403, "session_required", "Sign in to undo a change.")
    domain = Notes(store, principal)
    now = now_ms()
    stale = False
    result = None
    async with _decision_transaction(store, connection) as conn:
        found = await conn.execute_fetchall(
            "SELECT s.* FROM suggestion s JOIN plugin p ON p.id=s.plugin_id "
            "WHERE s.id=? AND (p.state='enabled' OR s.applied_version IS NOT NULL)",
            (suggestion_id,),
        )
        if not found:
            raise Problem(404, "not_found", "That suggestion is unavailable.")
        row = dict(found[0])
        if row["note_id"] is None and not principal.owner:
            raise Problem(404, "not_found", "That suggestion is unavailable.")
        terminal = row["status"] in {"accepted", "dismissed"}
        destination = None
        if is_link_proposal(row):
            note = await domain._row(conn, row["note_id"], include_trashed=decision == "undo")
            if terminal:
                payload = await visible_payload(conn, domain, row, source=note) or {}
            else:
                destination = await _link_destination(conn, domain, row)
                payload = {"text": visible_link(destination)}
        else:
            payload = await visible_payload(conn, domain, row, include_trashed=terminal)
            if payload is None:
                raise Problem(404, "not_found", "That suggestion is unavailable.")
            note = (
                await domain._row(conn, row["note_id"], include_trashed=decision == "undo")
                if row["note_id"]
                else None
            )
        if decision == "undo":
            if not await receipt_actor(conn, domain, row):
                raise Problem(
                    403, "suggestion_decision_forbidden", "Use the account that applied it."
                )
            applied, result = await _undo(conn, domain, row, note, now)
            return {"suggestion": value(row, payload=payload), "applied": applied, "result": result}
        if terminal:
            if row["status"] == "accepted" and decision == "dismiss" and row["applied_version"]:
                if not await receipt_actor(conn, domain, row):
                    raise Problem(
                        403, "suggestion_decision_forbidden", "Use the account that applied it."
                    )
                if not row["receipt_hidden"]:
                    row.update(receipt_hidden=1, change_seq=await sequence(conn))
                    await conn.execute(
                        "UPDATE suggestion SET receipt_hidden=1,change_seq=? WHERE id=?",
                        (row["change_seq"], row["id"]),
                    )
            elif (
                row["status"] != {"accept": "accepted", "dismiss": "dismissed"}[decision]
                and row["undone_at"] is None
            ):
                raise Problem(409, "suggestion_decided", "That suggestion already has a decision.")
            return {
                "suggestion": value(
                    row,
                    payload=payload,
                    note_version=(
                        note["version"] if note and await receipt_actor(conn, domain, row) else None
                    ),
                ),
                "applied": False,
            }
        if reviewed_version is not None and (
            row["action"] != "replace_body"
            or row["note_version"] != reviewed_version
            or note["version"] != reviewed_version
        ):
            raise Problem(409, "suggestion_stale", "The note changed. Review a new proposal.")
        offline_replace = (
            connection is not None
            and principal.kind == "device"
            and decision == "accept"
            and reviewed_version is None
            and row["action"] == "replace_body"
            and row["status"] in {"pending", "superseded"}
        )
        if row["status"] != "pending" and not offline_replace:
            raise Problem(409, "suggestion_stale", "That suggestion is no longer pending.")
        status = "accepted" if decision == "accept" else "dismissed"
        if row["created_at"] <= now - EXPIRY_MS:
            status, stale = "expired", True
        elif (
            decision == "accept"
            and row["action"] in {"replace_body", "set_title"}
            and note["version"] != row["note_version"]
            and not offline_replace
        ):
            status, stale = "superseded", True
        elif decision == "accept":
            action = row["action"]
            original_body = note["body"] if note else None
            original_version = note["version"] if note else None
            if action == "add_label":
                body = set_labels(note["body"], [payload["path"]], [])
            elif action == "set_title":
                from spunto.core.quiet_changes import titled_body

                body = titled_body(note["body"], payload["title"])
            elif action == "append_text":
                if destination is None:
                    body = note["body"] + "\n\n" + payload["text"]
                elif destination["id"] in note_link_targets(note["body"]):
                    body = note["body"]
                else:
                    body = append_link(note, destination)
            elif action == "replace_body":
                if offline_replace and note["version"] != row["note_version"]:
                    result = await domain.edit_in_transaction(
                        conn, note["id"], payload["body"], row["note_version"], coalesce=False
                    )
                    body = None
                else:
                    body = payload["body"]
            elif action == "merge_notes":
                source = await domain._row(conn, payload["source_id"])
                if (
                    source["version"] != payload["source_version"]
                    or note["version"] != payload["target_version"]
                ):
                    raise Problem(
                        409, "version_conflict", "A merge note changed; compare the notes again."
                    )
                body = note["body"] + "\n\n" + source["body"]
                _body(body, allow_empty=True)
                result = await domain._update(conn, note, body, "merge", coalesce=False)
                await domain._update(
                    conn,
                    source,
                    source["body"],
                    "flags",
                    changes={"trashed_at": now, "trashed_clock": now},
                    coalesce=False,
                )
                store.emit("note.trashed", source["id"], source["version"])
                body = None
            elif action == "label_change":
                if principal.kind != "session" and (
                    "note_ids" in payload
                    or not (connection is not None and principal.kind == "device")
                ):
                    raise Problem(403, "session_required", "Change labels from an owner session.")
                visibility, _ = domain._visibility()
                if "note_ids" in payload:
                    # Validate the entire cluster before the first ordinary edit.
                    targets = [await domain._row(conn, i) for i in payload["note_ids"]]
                    if any(
                        t["version"] != payload["note_versions"][t["id"]]
                        or t["private"]
                        or t["machine"]
                        or labels(t["body"])
                        for t in targets
                    ):
                        raise Problem(409, "version_conflict", "A cluster note changed.")
                    for target in targets:
                        await domain._update(
                            conn,
                            target,
                            set_labels(target["body"], [payload["new"]], []),
                            "label_rewrite",
                            coalesce=False,
                        )
                    result, body = {"changed": len(targets)}, None
                else:
                    result, body = await _rename_cluster(conn, domain, visibility, payload), None
            elif action == "open":
                if "note_id" in payload:
                    await domain._row(conn, payload["note_id"])
                result, body = {"open": payload}, None
            else:
                raise Problem(422, "validation_failed", "Unknown suggestion action.")
            if body is not None:
                _body(body, allow_empty=True)
                if body != note["body"]:
                    kind = "label_suggestion" if action == "add_label" else "suggestion"
                    result = await domain._update(conn, note, body, kind, coalesce=False)
                else:
                    result = await domain._serialize(conn, note)
        row.update(
            status=status, change_seq=await sequence(conn), decided_at=now, decided_by=principal.id
        )
        await conn.execute(
            "UPDATE suggestion SET status=?,change_seq=?,decided_at=?,decided_by=? WHERE id=?",
            (status, row["change_seq"], now, principal.id, row["id"]),
        )
        if (
            not stale
            and decision == "accept"
            and row["action"] in {"add_label", "set_title", "append_text", "replace_body"}
            and result
            and not result.get("conflict")
            and result["version"] != original_version
            and result["body"] != original_body
        ):
            await _record_receipt(conn, row["id"], result["version"])
            row.update(receipt_version=result["version"], receipt_hidden=0)
            # The note's ordinary update serialized it before the decision was recorded.
            result["suggestions"] = (
                await domain._serialize(conn, await domain._row(conn, note["id"]))
            )["suggestions"]
        if not stale:
            store.emit("suggestion.decided", row["note_id"], note["version"] if note else None)
    if stale:
        raise Problem(409, "suggestion_stale", "That suggestion expired or the note changed.")
    return {
        "suggestion": value(
            row,
            payload=payload,
            note_version=result.get("version") if result and manages_receipts(principal) else None,
        ),
        "applied": decision == "accept",
        "result": result,
    }
