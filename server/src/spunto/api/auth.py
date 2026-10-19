"""Owner setup, login and logout (02 §5.3, §9)."""

import asyncio
import hashlib
import logging
import math
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Request, Response
from pydantic import BaseModel, Field

from spunto.api.deps import Services, ServicesDep, SessionDep, check_origin
from spunto.api.errors import Problem
from spunto.api.network import client_ip
from spunto.auth import owner, passwords, sessions
from spunto.store.db import now_ms

log = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1")


@router.get("/auth/options")
async def auth_options():
    return {"mode": "local"}


Password = Annotated[str, Field(max_length=passwords.MAX_BYTES)]


class SetupBody(BaseModel):
    code: Annotated[str, Field(max_length=100)]
    password: Password


class LoginBody(BaseModel):
    password: Password


@router.post("/setup", status_code=204)
async def setup(body: SetupBody, request: Request, services: ServicesDep) -> Response:
    """Exchange the one-time code from the log for the owner password (HOST-3)."""
    check_origin(request, services.settings, required=False)
    if await owner.password_hash(services.store) is not None:
        services.setup_code = None
        raise Problem(409, "setup_done", "Setup is already done; sign in instead.")
    if reason := passwords.problem(body.password):
        raise Problem(422, "validation_failed", reason)
    async with _guess(request, services) as address:
        expected = services.setup_code
        if expected is None or not owner.same_code(body.code, expected):
            services.backoff.failed(address)
            raise Problem(401, "wrong_setup_code", "That setup code is not right.")
        # Spend the code before the first await, so a second request racing this one finds none.
        services.setup_code = None
        services.backoff.succeeded(address)
    new_hash = await passwords.hash_password(body.password)
    if not await owner.create(services.store, new_hash):
        raise Problem(409, "setup_done", "Setup is already done; sign in instead.")
    log.info("The owner finished setup")
    return await _signed_in(request, services, new_hash)


@router.post("/auth/login", status_code=204)
async def login(body: LoginBody, request: Request, services: ServicesDep) -> Response:
    check_origin(request, services.settings, required=False)
    stored = await owner.password_hash(services.store)
    if stored is None:
        raise Problem(409, "setup_required", "There is no owner yet; finish setup first.")
    async with _guess(request, services) as address:
        if not await passwords.verify_password(stored, body.password):
            services.backoff.failed(address)
            raise Problem(401, "wrong_password", "That password is not right.")
        services.backoff.succeeded(address)
    return await _signed_in(request, services, stored)


@router.post("/auth/logout", status_code=204)
async def logout(session_id: SessionDep, services: ServicesDep) -> Response:
    await sessions.revoke(services.store, session_id, now_ms())
    response = Response(status_code=204)
    _clear_cookie(response, services)
    return response


@router.get("/auth/session")
async def session_identity(session_id: SessionDep, services: ServicesDep):
    """Scope durable browser drafts to this owner, across session rotations and restores."""
    async with services.store.read() as conn:
        instance = (await conn.execute_fetchall("SELECT value FROM meta WHERE key='instance_id'"))[
            0
        ][0]
        row = (await conn.execute_fetchall("SELECT created_at,password_source FROM owner"))[0]
    identity = hashlib.sha256(f"{instance}:{row['created_at']}".encode()).hexdigest()[:32]
    return {
        "owner_id": identity,
        "credential_id": session_id,
        "password_managed": row["password_source"] == "env",  # noqa: S105 - metadata, no secret
    }


@router.get("/credentials")
async def credentials(session_id: SessionDep, services: ServicesDep) -> list[dict[str, object]]:
    """Ways in that still work, with their last use (sessions only until M1 adds tokens)."""
    now = now_ms()
    async with services.store.read() as conn:
        rows = await conn.execute_fetchall(
            "SELECT id, kind, name, client, scope, private_ok, external_ok,"
            " created_at, expires_at, last_used_at, last_sync_at FROM credential"
            " WHERE revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)"
            " ORDER BY created_at DESC",
            (now,),
        )
    return [
        {
            "id": row["id"],
            "kind": row["kind"],
            "name": row["name"],
            "client": row["client"],
            "scope": row["scope"],
            "private_ok": bool(row["private_ok"]),
            "external_ok": bool(row["external_ok"]),
            "created_at": _iso(row["created_at"]),
            "last_used_at": _iso(row["last_used_at"]),
            "expires_at": _iso(row["expires_at"]),
            "last_sync_at": _iso(row["last_sync_at"]),
            "current": row["id"] == session_id,
        }
        for row in rows
    ]


@asynccontextmanager
async def _guess(request: Request, services: Services) -> AsyncIterator[str]:
    """One setup-code or password check under the shared back-off; yields the client's address.

    The check and the recording of its outcome happen inside the back-off's
    turn. Checks run one at a time anyway (argon2 has a single thread), so
    taking turns costs nothing.
    """
    address = client_ip(request, services.settings)
    if delay := services.backoff.slowdown():
        await asyncio.sleep(delay)
    async with services.backoff.turn:
        if wait := services.backoff.retry_after(address):
            raise Problem(
                429,
                "rate_limited",
                "Too many wrong tries from this address; wait a little and try again.",
                {"Retry-After": str(math.ceil(wait))},
            )
        yield address


async def _signed_in(request: Request, services: Services, password_hash: str) -> Response:
    name = request.headers.get("user-agent", "")[:120] or "Web session"
    token = await sessions.create(
        services.store, name, now_ms(), expected_password_hash=password_hash
    )
    if token is None:
        raise Problem(401, "wrong_password", "The password changed; sign in with the new password.")
    response = Response(status_code=204)
    response.set_cookie(
        sessions.cookie_name(services.settings),
        token,
        max_age=sessions.LIFETIME_MS // 1000,
        path="/",
        secure=services.settings.secure,
        httponly=True,
        samesite="lax",
    )
    return response


def _clear_cookie(response: Response, services: Services) -> None:
    response.delete_cookie(
        sessions.cookie_name(services.settings),
        path="/",
        secure=services.settings.secure,
        httponly=True,
        samesite="lax",
    )


def _iso(ms: int | None) -> str | None:
    if ms is None:
        return None
    moment = datetime.fromtimestamp(ms / 1000, UTC)
    return moment.isoformat(timespec="milliseconds").replace("+00:00", "Z")


class DeviceLoginBody(LoginBody):
    name: str = Field(min_length=1, max_length=120, pattern=r"\S")


class DeviceCredential(BaseModel):
    id: str
    token: str
    name: str


class PairingGrant(BaseModel):
    code: str
    public_url: str
    expires_at: str


class PairingExchangeBody(BaseModel):
    code: str = Field(min_length=1, max_length=100)
    name: str = Field(min_length=1, max_length=120, pattern=r"\S")


@router.post("/auth/devices", response_model=DeviceCredential)
async def device_login(body: DeviceLoginBody, request: Request, services: ServicesDep):
    from spunto.auth import devices

    check_origin(request, services.settings, required=False)
    stored = await owner.password_hash(services.store)
    if stored is None:
        raise Problem(409, "setup_required", "Finish setup first.")
    async with _guess(request, services) as address:
        if not await passwords.verify_password(stored, body.password):
            services.backoff.failed(address)
            raise Problem(401, "wrong_password", "That password is not right.")
        services.backoff.succeeded(address)
    async with services.store.write() as conn:
        current = await conn.execute_fetchall("SELECT password_hash FROM owner")
        if not current or current[0][0] != stored:
            raise Problem(401, "wrong_password", "The password changed; sign in again.")
        return await devices.issue(conn, body.name.strip())


@router.post("/auth/pairing", response_model=PairingGrant)
async def create_pairing(session_id: SessionDep, services: ServicesDep):
    import secrets

    from spunto.auth import devices

    code, now = secrets.token_urlsafe(32), now_ms()
    expires = now + devices.PAIRING_MS
    async with services.store.write() as conn:
        await devices.active_session(conn, session_id, now)
        await conn.execute(
            "DELETE FROM pairing WHERE expires_at<=? OR credential_id=?", (now, session_id)
        )
        await conn.execute(
            "INSERT INTO pairing VALUES (?,?,?,?)", (devices.digest(code), session_id, now, expires)
        )
    return {"code": code, "public_url": services.settings.public_url, "expires_at": _iso(expires)}


@router.post("/auth/pairing/exchange", response_model=DeviceCredential)
async def exchange_pairing(body: PairingExchangeBody, request: Request, services: ServicesDep):
    from spunto.auth import devices

    check_origin(request, services.settings, required=False)
    async with _guess(request, services) as address:
        async with services.store.write() as conn:
            rows = await conn.execute_fetchall(
                "SELECT * FROM pairing WHERE code_hash=? AND expires_at>?",
                (devices.digest(body.code), now_ms()),
            )
            if not rows:
                services.backoff.failed(address)
                raise Problem(
                    401, "invalid_pairing_code", "This pairing code expired or was already used."
                )
            try:
                await devices.active_session(conn, rows[0]["credential_id"], now_ms())
            except Problem:
                services.backoff.failed(address)
                raise Problem(
                    401, "invalid_pairing_code", "Create a fresh pairing code from Devices."
                ) from None
            await conn.execute(
                "DELETE FROM pairing WHERE code_hash=?", (devices.digest(body.code),)
            )
            result = await devices.issue(conn, body.name.strip())
        services.backoff.succeeded(address)
        return result
