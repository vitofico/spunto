"""What request handlers share: the running services, the session, the Origin rule."""

from dataclasses import dataclass, field
from typing import Annotated

from fastapi import Depends, Request

from spunto.api.errors import Problem
from spunto.auth import sessions, tokens
from spunto.auth.backoff import Backoff
from spunto.config import Settings
from spunto.store.db import Store, now_ms


@dataclass
class Services:
    settings: Settings
    store: Store
    backoff: Backoff = field(default_factory=Backoff)
    # Exists only while there is no owner: printed in the log, single use,
    # and new at every start (02 §9.3).
    setup_code: str | None = None
    started_at: int = field(default_factory=now_ms)
    mcp: object | None = None
    host: object | None = None
    ai: object | None = None
    vectors: object | None = None
    hosted: object | None = None


def get_services(request: Request) -> Services:
    return getattr(request.state, "services", request.app.state.services)


ServicesDep = Annotated[Services, Depends(get_services)]

_UNSAFE = {"POST", "PUT", "PATCH", "DELETE"}


def check_origin(request: Request, settings: Settings, *, required: bool) -> None:
    """Refuse a write that a page on another site made the browser send (02 §9.4).

    Browsers always send Origin on such writes. Scripts using a password or a
    bearer token may leave it out, so it is required only where a cookie
    authenticates the request.
    """
    origin = request.headers.get("origin")
    if origin is None and not required:
        return
    if origin != settings.public_url:
        raise Problem(403, "origin_mismatch", f"Writes must come from {settings.public_url}.")


async def current_session(request: Request, services: ServicesDep) -> str:
    """The signed-in session's credential id; writes must also carry a matching Origin."""
    if getattr(request.state, "hosted_session_id", None):
        if request.method in _UNSAFE:
            check_origin(request, services.settings, required=True)
        return request.state.hosted_session_id
    if request.headers.get("authorization"):
        raise Problem(403, "session_required", "Use an owner session for this action.")
    token = request.cookies.get(sessions.cookie_name(services.settings))
    session_id = token and await sessions.resolve(services.store, token, now_ms())
    if not session_id:
        raise Problem(401, "unauthenticated", "Sign in first.")
    if request.method in _UNSAFE:
        check_origin(request, services.settings, required=True)
    return session_id


SessionDep = Annotated[str, Depends(current_session)]


async def current_principal(request: Request, services: ServicesDep) -> tokens.Principal:
    if getattr(request.state, "hosted_principal", None):
        principal = request.state.hosted_principal
        if principal.scope == "status":
            raise Problem(403, "status_only_token", "This token reaches only the status endpoint.")
        if request.method in _UNSAFE and not principal.writable:
            raise Problem(403, "read_only_token", "This token is read-only.")
        if request.method in _UNSAFE or principal.kind != "session":
            check_origin(request, services.settings, required=principal.kind == "session")
        return principal
    authorization = request.headers.get("authorization")
    if authorization:
        scheme, _, value = authorization.partition(" ")
        principal = (
            await tokens.authenticate(services.store, value) if scheme.lower() == "bearer" else None
        )
        if principal is None:
            raise Problem(401, "unauthenticated", "Use a valid bearer token.")
        if principal.scope == "status":
            raise Problem(403, "status_only_token", "This token reaches only the status endpoint.")
        if request.method in _UNSAFE and not principal.writable:
            raise Problem(403, "read_only_token", "This token is read-only.")
        check_origin(request, services.settings, required=False)
        return principal
    session_id = await current_session(request, services)
    async with services.store.read() as conn:
        row = (
            await conn.execute_fetchall("SELECT name FROM credential WHERE id=?", (session_id,))
        )[0]
    return tokens.Principal(session_id, "session", row["name"], "full", private_ok=True)


PrincipalDep = Annotated[tokens.Principal, Depends(current_principal)]
