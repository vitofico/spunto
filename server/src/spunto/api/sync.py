"""Device-only ordered sync, durable operation receipts and restore reconciliation."""

import gzip
import json
import re
import uuid
from datetime import datetime
from typing import Annotated, Any, Literal

from fastapi import APIRouter, Depends, Query, Request, Response
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from spunto.api.deps import PrincipalDep, ServicesDep
from spunto.api.errors import Problem
from spunto.api.notes import Source
from spunto.auth.tokens import Principal
from spunto.core import suggestions
from spunto.core.notes import MAX_BODY, Notes, _id, iso
from spunto.store.db import now_ms

router = APIRouter(prefix="/api/v1/sync", tags=["Sync"])
PAGE_BYTES = 2_000_000


async def device(principal: PrincipalDep):
    if principal.kind != "device":
        raise Problem(403, "device_required", "Use an Android device token for sync.")
    return principal


DeviceDep = Annotated[Principal, Depends(device)]


class Operation(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    op_id: str
    type: Literal["note.create", "note.update", "note.flags", "note.restore", "suggestion.decide"]
    note_id: str | None = None
    body: str | None = Field(None, max_length=MAX_BODY)
    base_version: int | None = Field(None, ge=1)
    created_at: int | None = Field(None, ge=0)
    source: Source | None = None
    private: bool | None = None
    machine: bool | None = None
    origin: Literal["owner", "agent", "plugin", "external"] | None = None
    pinned: bool | None = None
    archived: bool | None = None
    trashed: bool | None = None
    at: int | None = Field(None, ge=0)
    pinned_at: int | None = Field(None, ge=0)
    archived_at: int | None = Field(None, ge=0)
    trashed_at: int | None = Field(None, ge=0)
    private_at: int | None = Field(None, ge=0)
    suggestion_id: str | None = None
    decision: Literal["accept", "dismiss"] | None = None

    @model_validator(mode="after")
    def required_fields(self):
        self.op_id = str(uuid.UUID(self.op_id))
        if self.type == "suggestion.decide":
            if self.suggestion_id is None or self.decision is None:
                raise ValueError("suggestion_id and decision are required")
        else:
            if self.note_id is None:
                raise ValueError("note_id is required")
            self.note_id = _id(self.note_id, now_ms())
            if self.type != "note.flags" and self.body is None:
                raise ValueError("body is required")
            if self.type == "note.create" and not self.body:
                raise ValueError("body cannot be empty")
            if self.type == "note.update" and self.base_version is None:
                raise ValueError("base_version is required")
            if self.type == "note.flags" and not self.flags():
                raise ValueError("at least one flag is required")
        if self.type != "note.restore" and (self.machine is not None or self.origin is not None):
            raise ValueError("provenance is accepted only for note.restore")
        if self.type == "note.restore":
            kind = self.source.kind if self.source else "android"
            if kind == "plugin" and (self.machine is None or self.origin is None):
                raise ValueError("plugin restores need explicit machine and origin")
            origin = self.origin or "owner"
            if (
                (self.machine and (origin != "plugin" or kind != "plugin" or not self.source.ref))
                or (origin in {"plugin", "external"} and kind != "plugin")
                or (origin == "agent" and kind != "agent")
                or (origin == "owner" and kind in {"plugin", "agent"})
            ):
                raise ValueError("invalid restore provenance")
        return self

    def flags(self):
        return {
            name: getattr(self, name)
            for name in ("pinned", "archived", "trashed", "private")
            if getattr(self, name) is not None
        }


class Push(BaseModel):
    epoch: str = Field(max_length=64)
    # Validate individual entries inside the loop: one malformed entry must not block others.
    ops: list[Any] = Field(max_length=100)


class Result(BaseModel):
    op_id: str | None = None
    status: Literal["applied", "merged", "conflict", "duplicate", "rejected"]
    version: int | None = None
    body: str | None = None
    note: dict | None = None
    conflict_id: str | None = None
    code: str | None = None
    original: dict | None = None


class PushResponse(BaseModel):
    epoch: str
    results: list[Result]


class Item(BaseModel):
    kind: Literal["note", "suggestion", "annotation", "tombstone"]
    change_seq: int
    data: dict


class PullResponse(BaseModel):
    epoch: str
    cursor: str
    has_more: bool
    plugins_rev: int
    items: list[Item]


class ReconcileNote(BaseModel):
    id: str = Field(max_length=36)
    body_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    updated_at: datetime

    @model_validator(mode="after")
    def valid(self):
        self.id = _id(self.id, now_ms())
        if self.updated_at.tzinfo is None:
            raise ValueError("updated_at needs a time zone")
        return self


class Reconcile(BaseModel):
    epoch: str = Field(max_length=64)
    notes: list[ReconcileNote] = Field(max_length=1000)


class ReconcileResponse(BaseModel):
    epoch: str
    restore_ids: list[str]


async def metadata(conn):
    rows = await conn.execute_fetchall(
        "SELECT key,value FROM meta WHERE key IN ('sync_epoch','change_seq','plugins_rev')"
    )
    return {row["key"]: row["value"] for row in rows}


def check_epoch(epoch, meta):
    if epoch != meta["sync_epoch"]:
        raise Problem(
            409, "resync_required", "The server was restored; reconcile your local notes."
        )


async def apply(conn, domain, op):
    if op.type == "suggestion.decide":
        decided = await suggestions.decide(
            domain.store, domain.principal, op.suggestion_id, op.decision, connection=conn
        )
        result = decided.get("result")
        if result and result.get("conflict"):
            return {
                "status": "conflict",
                "version": result["version"],
                "conflict_id": result["conflict_id"],
                "note": result["note"],
            }
        return {
            "status": "merged" if result and result.get("merged") else "applied",
            **(
                {"version": result["version"], "note": result}
                if result and "version" in result
                else {}
            ),
            **({"body": result["body"]} if result and result.get("merged") else {}),
        }
    if op.type in {"note.create", "note.restore"}:
        rows = await conn.execute_fetchall("SELECT * FROM note WHERE id=?", (op.note_id,))
        if rows:
            if op.type == "note.create":
                raise Problem(409, "id_conflict", "That note id already exists.")
            if (op.origin is not None and op.origin != rows[0]["origin"]) or (
                op.source is not None and op.source.kind != rows[0]["source_kind"]
            ):
                raise Problem(
                    422, "invalid_provenance", "Restored provenance must match the existing note."
                )
            if rows[0]["body"] == op.body:
                result = await domain._serialize(conn, rows[0])
            else:
                # A restored ancestor cannot prove that two different bodies are the same edit.
                # Preserve both when the device's base is beyond the restored server version.
                result = await domain.edit_in_transaction(
                    conn,
                    op.note_id,
                    op.body,
                    op.base_version or rows[0]["version"] + 1,
                    restore=True,
                    machine=bool(op.machine),
                )
        else:
            deleted = await conn.execute_fetchall(
                "SELECT id FROM tombstone WHERE kind='note' AND id=?", (op.note_id,)
            )
            if deleted:
                raise Problem(
                    409,
                    "id_conflict" if op.type == "note.create" else "not_found",
                    "That note was permanently deleted.",
                )
            now = now_ms()
            if op.created_at is not None and op.created_at > now + 86_400_000:
                raise Problem(422, "validation_failed", "created_at is too far ahead.")
            await conn.execute("DELETE FROM tombstone WHERE kind='note' AND id=?", (op.note_id,))
            result = await domain._insert(
                conn,
                op.body,
                op.note_id,
                op.created_at if op.created_at is not None else now,
                op.source.model_dump() if op.source else {"kind": "android"},
                bool(op.private),
                allow_empty=op.type == "note.restore",
                origin=op.origin if op.type == "note.restore" else None,
                machine=bool(op.machine) if op.type == "note.restore" else False,
            )
        if not rows and op.private is not None:
            clock = min(
                op.private_at
                if op.private_at is not None
                else op.at
                if op.at is not None
                else op.created_at
                if op.created_at is not None
                else now_ms(),
                now_ms(),
            )
            await conn.execute("UPDATE note SET private_clock=? WHERE id=?", (clock, op.note_id))
            result["flag_times"]["private"] = clock
        flags = op.flags()
        if not rows:
            flags.pop("private", None)
        target = result.get("conflict_id", op.note_id)
        if flags:
            flagged = await domain.flags_in_transaction(
                conn,
                target,
                flags,
                {
                    name: getattr(op, f"{name}_at")
                    if getattr(op, f"{name}_at") is not None
                    else op.at
                    if op.at is not None
                    else op.created_at
                    if op.created_at is not None
                    else now_ms()
                    for name in flags
                },
            )
            if result.get("conflict"):
                result["note"] = flagged
            else:
                if result.get("merged"):
                    flagged["merged"] = True
                result = flagged
    elif op.type == "note.update":
        result = await domain.edit_in_transaction(conn, op.note_id, op.body, op.base_version)
    else:
        flags = op.flags()
        result = await domain.flags_in_transaction(
            conn,
            op.note_id,
            flags,
            {
                name: getattr(op, f"{name}_at")
                if getattr(op, f"{name}_at") is not None
                else op.at
                if op.at is not None
                else now_ms()
                for name in flags
            },
        )
    if result.get("conflict"):
        return {
            "status": "conflict",
            "version": result["version"],
            "conflict_id": result["conflict_id"],
            "note": result["note"],
        }
    return {
        "status": "merged" if result.get("merged") else "applied",
        "version": result["version"],
        "note": result,
        **({"body": result["body"]} if result.get("merged") else {}),
    }


@router.post("/push", response_model=PushResponse, response_model_exclude_none=True)
async def push(body: Push, principal: DeviceDep, services: ServicesDep):
    async with services.store.read() as conn:
        check_epoch(body.epoch, await metadata(conn))
    domain, results = Notes(services.store, principal), []
    for raw in body.ops:
        op_id = (
            raw.get("op_id")
            if isinstance(raw, dict) and isinstance(raw.get("op_id"), str)
            else None
        )
        try:
            op = Operation.model_validate(raw)
            async with services.store.write() as conn:
                check_epoch(body.epoch, await metadata(conn))
                active = await conn.execute_fetchall(
                    "SELECT id FROM credential WHERE id=? AND kind='device' AND revoked_at IS NULL",
                    (principal.id,),
                )
                if not active:
                    raise Problem(401, "unauthenticated", "Sign in again.")
                previous = await conn.execute_fetchall(
                    "SELECT result FROM applied_op WHERE credential_id=? AND op_id=?",
                    (principal.id, op.op_id),
                )
                if previous:
                    result = {"status": "duplicate", "original": json.loads(previous[0][0])}
                else:
                    result = await apply(conn, domain, op)
                    await conn.execute(
                        "INSERT INTO applied_op VALUES(?,?,?,?)",
                        (principal.id, op.op_id, json.dumps(result), now_ms()),
                    )
                await conn.execute(
                    "UPDATE credential SET last_sync_at=? WHERE id=?", (now_ms(), principal.id)
                )
        except ValidationError:
            code = (
                "too_large"
                if isinstance(raw, dict)
                and isinstance(raw.get("body"), str)
                and len(raw["body"]) > MAX_BODY
                else "invalid_operation"
            )
            result = {"status": "rejected", "code": code}
        except Problem as error:
            if error.code == "resync_required" or error.status == 401:
                raise
            code = (
                "invalid_id"
                if error.code == "validation_failed" and "UUIDv7" in error.detail
                else error.code
            )
            result = {"status": "rejected", "code": code}
        results.append({"op_id": op_id, **result})
    return {"epoch": body.epoch, "results": results}


@router.get("/pull", response_model=PullResponse)
async def pull(
    request: Request,
    principal: DeviceDep,
    services: ServicesDep,
    cursor: str | None = None,
    limit: Annotated[int, Query(ge=1, le=500)] = 500,
):
    domain = Notes(services.store, principal)
    async with services.store.read() as conn:
        meta = await metadata(conn)
        epoch, seq = meta["sync_epoch"], 0
        if cursor is not None:
            match = re.fullmatch(r"([a-f0-9]{8})\.([0-9]{1,20})", cursor)
            if not match:
                raise Problem(422, "invalid_cursor", "Use an epoch.seq sync cursor.")
            check_epoch(match[1], meta)
            seq = int(match[2])
            if seq > int(meta["change_seq"]):
                raise Problem(422, "invalid_cursor", "The cursor is ahead of the server.")
        changes = await conn.execute_fetchall(
            "SELECT 'note' kind,id,change_seq FROM note WHERE change_seq>? UNION ALL "
            "SELECT 'suggestion',id,change_seq FROM suggestion WHERE change_seq>? UNION ALL "
            "SELECT 'annotation',plugin_id||'/'||note_id||'/'||kind,change_seq "
            "FROM annotation WHERE change_seq>? UNION ALL "
            "SELECT 'tombstone',kind||'/'||id,change_seq FROM tombstone WHERE change_seq>? "
            "ORDER BY change_seq LIMIT ?",
            (seq, seq, seq, seq, limit + 1),
        )
        items, size = [], 0
        for change in changes[:limit]:
            kind, item_id = change["kind"], change["id"]
            if kind == "note":
                row = (await conn.execute_fetchall("SELECT * FROM note WHERE id=?", (item_id,)))[0]
                data = await domain._serialize(conn, row, include_plugins=False)
            elif kind == "suggestion":
                row = (
                    await conn.execute_fetchall("SELECT * FROM suggestion WHERE id=?", (item_id,))
                )[0]
                data = suggestions.value(dict(row))
            elif kind == "annotation":
                plugin_id, note_id, annotation_kind = item_id.split("/", 2)
                row = (
                    await conn.execute_fetchall(
                        "SELECT * FROM annotation WHERE plugin_id=? AND note_id=? AND kind=?",
                        (plugin_id, note_id, annotation_kind),
                    )
                )[0]
                data = {
                    **dict(row),
                    "id": item_id,
                    "plugin": plugin_id,
                    "payload": json.loads(row["payload"]),
                    "updated_at": iso(row["updated_at"]),
                }
            else:
                tombstone_kind, target = item_id.split("/", 1)
                row = (
                    await conn.execute_fetchall(
                        "SELECT * FROM tombstone WHERE kind=? AND id=?", (tombstone_kind, target)
                    )
                )[0]
                data = {**dict(row), "deleted_at": iso(row["deleted_at"])}
            item = {"kind": kind, "change_seq": change["change_seq"], "data": data}
            item_size = len(json.dumps(item, ensure_ascii=False, separators=(",", ":")).encode())
            if items and size + item_size > PAGE_BYTES - 1024:
                break
            items.append(item)
            size += item_size
        has_more = len(changes) > len(items)
        new_seq = items[-1]["change_seq"] if items else seq
        if not has_more:
            # Plugin switches spend a change_seq without a row; a cursor left behind them
            # makes /changes/wait report "changed" forever and the phone sync in a loop.
            new_seq = max(new_seq, int(meta["change_seq"]))
    payload = {
        "epoch": epoch,
        "cursor": f"{epoch}.{new_seq}",
        "has_more": has_more,
        "plugins_rev": int(meta["plugins_rev"]),
        "items": items,
    }
    credential_store = services.store
    if services.hosted:
        credential_store = services.hosted.store.for_workspace(
            principal.workspace_id,
            principal.user_id,
            "owner",
        )
    async with credential_store.write() as conn:
        await conn.execute(
            "UPDATE credential SET last_sync_at=? WHERE id=?", (now_ms(), principal.id)
        )
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
    accepted = request.headers.get("accept-encoding", "").lower().split(",")
    gzip_ok = any(
        entry.strip().split(";", 1)[0] == "gzip"
        and not re.search(r";\s*q=0(?:\.0*)?(?:\s*$|;)", entry)
        for entry in accepted
    )
    if gzip_ok:
        return Response(
            gzip.compress(encoded, mtime=0),
            media_type="application/json",
            headers={"Content-Encoding": "gzip", "Vary": "Accept-Encoding"},
        )
    return Response(encoded, media_type="application/json", headers={"Vary": "Accept-Encoding"})


@router.post("/reconcile", response_model=ReconcileResponse)
async def reconcile(body: Reconcile, principal: DeviceDep, services: ServicesDep):
    restore = []
    async with services.store.read() as conn:
        meta = await metadata(conn)
        check_epoch(body.epoch, meta)
        for note in body.notes:
            rows = await conn.execute_fetchall(
                "SELECT body_hash,updated_at FROM note WHERE id=?", (note.id,)
            )
            local_time = int(note.updated_at.timestamp() * 1000)
            if not rows:
                purged = await conn.execute_fetchall(
                    "SELECT id FROM tombstone WHERE kind='note' AND id=?", (note.id,)
                )
                if not purged:
                    restore.append(note.id)
            elif rows[0]["updated_at"] < local_time or (
                rows[0]["body_hash"] != note.body_hash and rows[0]["updated_at"] == local_time
            ):
                restore.append(note.id)
    return {"epoch": meta["sync_epoch"], "restore_ids": restore}
