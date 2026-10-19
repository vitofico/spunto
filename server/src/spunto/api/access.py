"""Owner token management and status-token-only monitoring."""

import asyncio
from datetime import UTC, datetime
from typing import Literal

from fastapi import APIRouter, Request, Response
from pydantic import BaseModel, ConfigDict, Field, model_validator

from spunto import __version__
from spunto.api.deps import ServicesDep, SessionDep, check_origin, current_session
from spunto.api.errors import Problem
from spunto.auth import owner, passwords, sessions, tokens
from spunto.core.notes import iso
from spunto.store.db import DB_NAME, now_ms
from spunto.store.migrations import LATEST
from spunto.store.snapshot import taken_at

router = APIRouter(prefix="/api/v1", tags=["Access"])


class TokenBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=120)
    client: Literal["claude-code", "codex", "script", "monitor"] = "script"
    scope: Literal["read", "write", "status"] = "read"
    private_ok: bool = False
    external_ok: bool = True
    expires_at: datetime | None = None

    @model_validator(mode="after")
    def _expiry(self):
        if self.expires_at and (
            self.expires_at.tzinfo is None or self.expires_at <= datetime.now(UTC)
        ):
            raise ValueError("expires_at must be a future time with a time zone")
        return self


@router.post("/tokens", status_code=201)
async def create_token(body: TokenBody, session: SessionDep, services: ServicesDep):
    credential_id, token = await tokens.create(
        services.store,
        name=body.name,
        client=body.client,
        scope=body.scope,
        private_ok=body.private_ok,
        external_ok=body.external_ok,
        expires_at=int(body.expires_at.timestamp() * 1000) if body.expires_at else None,
    )
    return {"id": credential_id, "token": token}


@router.delete("/credentials/{id}", status_code=204)
async def revoke_credential(id: str, session: SessionDep, services: ServicesDep):
    async with services.store.write() as conn:
        rows = await conn.execute_fetchall("SELECT kind FROM credential WHERE id=?", (id,))
        if not rows:
            raise Problem(404, "not_found", "That credential is unavailable.")
        if rows[0][0] == "plugin":
            raise Problem(403, "plugin_credential", "Disable the plugin to revoke its access.")
        await conn.execute("UPDATE credential SET revoked_at=? WHERE id=?", (now_ms(), id))
    return Response(status_code=204)


class PasswordBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    password: str = Field(max_length=passwords.MAX_BYTES)


@router.put("/auth/password", status_code=204)
async def change_password(body: PasswordBody, session: SessionDep, services: ServicesDep):
    if (
        services.settings.owner_password is not None
        or services.settings.owner_password_hash is not None
    ):
        raise Problem(
            409,
            "password_from_environment",
            "Change the owner password in the environment and restart.",
        )
    if reason := passwords.problem(body.password):
        raise Problem(422, "validation_failed", reason)
    await owner.set_password(services.store, await passwords.hash_password(body.password), "ui")
    response = Response(status_code=204)
    response.delete_cookie(
        sessions.cookie_name(services.settings),
        path="/",
        secure=services.settings.secure,
        httponly=True,
        samesite="lax",
    )
    return response


async def _backup_status(services):
    if services.hosted:
        return {"last_at": None, "size_bytes": None, "age_hours": None, "managed": True}
    directory = services.settings.backup_dir

    def inspect():
        candidates = [(taken_at(path), path) for path in directory.glob("spunto-*.db")]
        candidates = [(when, path) for when, path in candidates if when is not None]
        if not candidates:
            return {"last_at": None, "size_bytes": None, "age_hours": None}
        when, path = max(candidates)
        return {
            "last_at": when.isoformat().replace("+00:00", "Z"),
            "size_bytes": path.stat().st_size,
            "age_hours": round((datetime.now(UTC) - when).total_seconds() / 3600, 2),
        }

    return await asyncio.to_thread(inspect)


@router.get("/status")
async def status(request: Request, services: ServicesDep):
    authorization = request.headers.get("authorization")
    if authorization:
        scheme, _, value = authorization.partition(" ")
        principal = (
            getattr(request.state, "hosted_principal", None)
            if services.hosted
            else (
                await tokens.authenticate(services.store, value)
                if scheme.lower() == "bearer"
                else None
            )
        )
        if principal is None:
            raise Problem(401, "unauthenticated", "Use a valid status token.")
        if principal.kind != "pat" or principal.scope != "status":
            raise Problem(403, "status_token_required", "Use an owner session or status token.")
        check_origin(request, services.settings, required=False)
    else:
        await current_session(request, services)
    async with services.store.read() as conn:
        counts = (
            await conn.execute_fetchall(
                "SELECT count(*) AS notes,sum(private) AS private,"
                "sum(CASE WHEN trashed_at IS NOT NULL THEN 1 ELSE 0 END) AS trash FROM note"
            )
        )[0]
        credentials = await conn.execute_fetchall(
            "SELECT id,kind,name,scope,last_used_at,last_sync_at FROM credential WHERE "
            "revoked_at IS NULL ORDER BY created_at DESC"
        )
        meta = dict(await conn.execute_fetchall("SELECT key,value FROM meta"))
        jobs = await conn.execute_fetchall(
            "SELECT plugin_id,status,count(*) AS count FROM job GROUP BY plugin_id,status"
        )
        destinations = await conn.execute_fetchall(
            "SELECT plugin_id,substr(key,12) AS host,CAST(value AS INTEGER) AS count "
            "FROM plugin_kv WHERE key LIKE 'host:fetch:%'"
        )
    if services.hosted:
        async with services.store.read() as conn:
            size = (
                await conn.execute_fetchall("SELECT coalesce(sum(pg_column_size(n)),0) FROM note n")
            )[0][0]
    else:
        path = services.settings.data_dir / DB_NAME
        size = await asyncio.to_thread(lambda: path.stat().st_size)
    return {
        "version": __version__,
        "schema": LATEST,
        "sync_epoch": meta["sync_epoch"],
        "theme_failures": [
            {"id": card["id"], "name": card["name"], "reason": card["reason"]}
            for card in services.host.themes.catalog()
            if card["status"] == "failed"
        ],
        "change_seq": int(meta["change_seq"]),
        "uptime_seconds": max(0, (now_ms() - services.started_at) // 1000),
        "database": {
            "size_bytes": size,
            "size_kind": "note_rows" if services.hosted else "database_file",
            "notes": counts["notes"],
            "private_notes": counts["private"] or 0,
            "trashed_notes": counts["trash"] or 0,
        },
        "backup": await _backup_status(services),
        "login_slowdown": services.backoff.slowdown() > 0,
        "ai": {
            "enabled": bool(services.ai and services.ai.capabilities),
            "capabilities": services.ai.capabilities if services.ai else [],
            "health": services.ai.health if services.ai else [],
            "indexing": await services.vectors.status() if services.vectors else None,
            "stats": (await services.ai.status())["stats"] if services.ai else [],
        },
        "plugins": [
            {
                "id": item["id"],
                "switch": item["switch"],
                "state": item["state"],
                "last_run_at": iso(item["last_run_at"]),
                "last_error": item["last_error"],
            }
            for item in services.host.catalog()
        ]
        if services.host
        else [],
        "jobs": [dict(row) for row in jobs],
        "network_destinations": [dict(row) for row in destinations],
        "credentials": [
            {
                **dict(row),
                "last_used_at": iso(row["last_used_at"]),
                "last_sync_at": iso(row["last_sync_at"]),
            }
            for row in credentials
        ],
    }
