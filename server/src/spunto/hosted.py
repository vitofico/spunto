"""Shared hosted composition. SQLite self-hosting retains its original startup path."""

import asyncio
import logging
import math
import secrets
from contextlib import asynccontextmanager
from dataclasses import replace

from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse, RedirectResponse
from pydantic import BaseModel, Field

from spunto import __version__
from spunto.api import (
    access,
    ai,
    attachments,
    auth,
    errors,
    imports,
    notes,
    plugins,
    reference,
    slip,
    suggestions,
    sync,
    themes,
    web,
)
from spunto.api.deps import Services, check_origin
from spunto.api.errors import Problem, problem_response
from spunto.api.network import SecurityHeadersMiddleware, WrongHostMiddleware, client_ip
from spunto.auth import devices, passwords, sessions, tokens
from spunto.auth.accounts import Accounts, OIDCTransactions, digest
from spunto.auth.oidc import OIDCClient, OIDCError, OIDCSettings
from spunto.store.db import now_ms
from spunto.store.postgres import PostgresStore


class Hosted:
    def __init__(self, settings, store, accounts, oidc):
        self.settings, self.store, self.accounts, self.oidc = settings, store, accounts, oidc
        self._runtimes = {}
        self._lock = asyncio.Lock()
        self._ai_checks = set()

    async def start(self):
        self.dummy_password_hash = await passwords.hash_password(secrets.token_urlsafe(32))
        # Restore every space's persistent jobs and schedules before accepting traffic.
        # These are lightweight runtimes in one process, sharing the same DB pool.
        after = ""
        while True:
            rows = await self.accounts.all(
                "SELECT w.*,m.user_id FROM public.spunto_workspace w "
                "JOIN public.spunto_membership m ON m.workspace_id=w.id AND m.role='owner' "
                "WHERE w.id>%s ORDER BY w.id LIMIT 100",
                (after,),
            )
            for row in rows:
                await self.services({**row, "role": "owner"}, row["user_id"])
            if len(rows) < 100:
                break
            after = rows[-1]["id"]

    async def _check_ai(self, runtime):
        try:
            await runtime.ai.health_check()
            await runtime.host.refresh_ai()
        except Exception:
            logging.getLogger("spunto").warning("code=ai_startup_check_unavailable")

    async def services(self, workspace, user_id):
        """Share plugin runtimes, never mutable request authority or database connections."""
        workspace_id = workspace["id"]
        async with self._lock:
            if workspace_id not in self._runtimes:
                from spunto.ai.gateway import Gateway
                from spunto.ai.vectors import VectorIndex
                from spunto.core.attachments import Attachments
                from spunto.core.plugin_notes import PluginNotes
                from spunto.host import Host

                owner = await self.accounts.one(
                    "SELECT user_id FROM public.spunto_membership "
                    "WHERE workspace_id=%s AND role='owner'",
                    (workspace_id,),
                )
                if not owner:
                    raise Problem(404, "workspace_unavailable", "That space is unavailable.")
                store = self.store.for_workspace(workspace_id, owner["user_id"], "owner")
                await store.initialize_workspace()
                data_dir = self.settings.data_dir / "workspaces" / workspace_id
                attachment_dir = self.settings.attachments_dir / workspace_id
                data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
                attachment_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
                settings = self.settings.model_copy(
                    update={
                        "data_dir": data_dir,
                        "attachments_dir": attachment_dir,
                        "backup_dir": self.settings.backup_dir / workspace_id,
                    }
                )
                gateway = Gateway(store, settings)
                await gateway.selection.load()
                vectors = VectorIndex(store, gateway)
                host = Host(
                    store,
                    settings,
                    notes_factory=PluginNotes,
                    attachments_factory=Attachments,
                    gateway=gateway,
                    vectors=vectors,
                )
                await host.discover()
                store.plugin_host, store.event_sink = host, host.emit
                from spunto.jobs import maintenance

                async def daily_retention():
                    await maintenance.prune(store, settings)

                host.register_core("purge", daily_retention, at="04:00")
                await host.start()
                self._runtimes[workspace_id] = Services(
                    settings,
                    store,
                    host=host,
                    ai=gateway,
                    vectors=vectors,
                    hosted=self,
                )
                if settings.mcp_enabled:
                    from spunto.mcp import MCP

                    self._runtimes[workspace_id].mcp = MCP(self._runtimes[workspace_id])
                check = asyncio.create_task(self._check_ai(self._runtimes[workspace_id]))
                self._ai_checks.add(check)
                check.add_done_callback(self._ai_checks.discard)
            base = self._runtimes[workspace_id]
        await base.host.refresh_models()
        scoped = self.store.for_workspace(workspace_id, user_id, workspace["role"])
        scoped.plugin_host, scoped.event_sink = base.host, base.host.emit
        scoped.change_event = base.store.change_event
        scoped._dispatch_tasks = base.store._dispatch_tasks
        return replace(base, store=scoped)

    async def browser_credential(self, services, token, account):
        credential_id = digest(token + ":" + services.store._context[0])[:16]
        # This internal credential permits existing session-bound core operations.
        # The actual browser session is independently checked in the identity store.
        owner_store = self.store.for_workspace(
            services.store._context[0],
            account["id"],
            "owner",
        )
        async with owner_store.write() as conn:
            await conn.execute(
                "INSERT OR IGNORE INTO credential "
                "(id,kind,name,scope,private_ok,secret_hash,created_at,expires_at) "
                "VALUES (?,'session',?,'full',1,?,?,?)",
                (
                    credential_id,
                    account["display_name"],
                    digest(token),
                    now_ms(),
                    now_ms() + 30 * 86_400_000,
                ),
            )
            row = (
                await conn.execute_fetchall(
                    "SELECT revoked_at,expires_at FROM credential WHERE id=?",
                    (credential_id,),
                )
            )[0]
            if row["revoked_at"] is not None or row["expires_at"] <= now_ms():
                raise Problem(401, "unauthenticated", "Sign in again.")
        return credential_id

    async def close(self):
        for task in self._ai_checks:
            task.cancel()
        await asyncio.gather(*self._ai_checks, return_exceptions=True)
        for runtime in self._runtimes.values():
            await runtime.store.drain_events()
            runtime.store.event_sink = None
            await runtime.host.close()
            await runtime.ai.close()
        await self.accounts.close()
        await self.store.close()


@asynccontextmanager
async def hosted_startup(settings):
    store = await PostgresStore.open(settings.database_url)
    accounts = None
    manager = None
    try:
        accounts = await Accounts.open(settings.identity_database_url)
        oidc = (
            OIDCClient(
                OIDCSettings(
                    issuer=settings.oidc_issuer,
                    client_id=settings.oidc_client_id,
                    client_secret=settings.oidc_client_secret,
                    redirect_uri=settings.public_url + "/api/v1/auth/oidc/callback",
                    allow_insecure_loopback=settings.oidc_allow_insecure_loopback,
                ),
                transaction_store=OIDCTransactions(accounts),
            )
            if settings.oidc_issuer
            else None
        )
        manager = Hosted(settings, store, accounts, oidc)
        await manager.start()
        yield Services(settings, store, hosted=manager)
    finally:
        if manager:
            await manager.close()
        else:
            if accounts:
                await accounts.close()
            await store.close()


_PUBLIC = frozenset(
    {
        "/api/v1/meta",
        "/api/v1/auth/options",
        "/api/v1/auth/login",
        "/api/v1/auth/register",
        "/api/v1/auth/oidc/start",
        "/api/v1/auth/oidc/callback",
        "/api/v1/auth/pairing/exchange",
        "/api/v1/openapi.json",
        "/healthz",
        "/readyz",
    }
)
_UNSAFE = frozenset({"POST", "PUT", "PATCH", "DELETE"})


class HostedIdentityMiddleware:
    def __init__(self, app, manager):
        self.app, self.manager = app, manager

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        request = Request(scope)
        protected = request.url.path.startswith(("/api/", "/a/", "/mcp"))
        public = request.url.path in _PUBLIC
        if request.url.path == "/api/v1/meta" and (
            request.headers.get("authorization")
            or request.cookies.get(sessions.cookie_name(self.manager.settings))
        ):
            public = False
        if not protected or public:
            return await self.app(scope, receive, send)
        try:
            manager = self.manager
            account = None
            principal = None
            authorization = request.headers.get("authorization")
            requested_space = request.headers.get("x-spunto-workspace")
            if requested_space is None and request.url.path.startswith("/a/"):
                requested_space = request.query_params.get("workspace")
            if requested_space and (len(requested_space) > 80 or "/" in requested_space):
                raise Problem(404, "workspace_unavailable", "That space is unavailable.")
            if authorization:
                scheme, _, token = authorization.partition(" ")
                parts = token.split("_", 3)
                if scheme.lower() != "bearer" or len(parts) != 4:
                    raise Problem(401, "unauthenticated", "Use a valid device or access token.")
                account = await manager.accounts.credential_account(parts[2])
                if account:
                    if requested_space and requested_space != account["workspace_id"]:
                        raise Problem(
                            403, "workspace_mismatch", "This credential belongs to another space."
                        )
                    workspace = await manager.accounts.workspace(
                        account["id"], account["workspace_id"]
                    )
                    services = await manager.services(workspace, account["id"])
                    auth_store = manager.store.for_workspace(
                        workspace["id"], account["id"], "owner"
                    )
                    principal = await tokens.authenticate(auth_store, token)
                    if principal:
                        principal = replace(
                            principal,
                            user_id=account["id"],
                            workspace_id=workspace["id"],
                            workspace_role=workspace["role"],
                        )
            else:
                token = request.cookies.get(sessions.cookie_name(manager.settings))
                account = await manager.accounts.resolve_session(token)
                if account:
                    expected_account = request.headers.get("x-spunto-account")
                    expected_credential = request.headers.get("x-spunto-credential")
                    if (expected_account is not None or expected_credential is not None) and (
                        expected_account != account["id"]
                        or expected_credential != digest(token)[:16]
                    ):
                        # Browser cookies can change in another tab or in a delayed
                        # Set-Cookie response. Fence the initiating actor before
                        # looking up a space, starting its host or writing credentials.
                        raise Problem(
                            409,
                            "identity_changed",
                            "The sign-in changed. Reload before continuing.",
                        )
                    workspace = await manager.accounts.workspace(account["id"], requested_space)
                    services = await manager.services(workspace, account["id"])
                    credential = await manager.browser_credential(services, token, account)
                    principal = tokens.Principal(
                        credential,
                        "session",
                        account["display_name"],
                        "full",
                        private_ok=True,
                        user_id=account["id"],
                        workspace_id=workspace["id"],
                        workspace_role=workspace["role"],
                    )
                    scope.setdefault("state", {})["hosted_session_id"] = credential
                    scope["state"]["browser_session_id"] = digest(token)[:16]
            if account is None or principal is None:
                raise Problem(401, "unauthenticated", "Sign in first.")
            if principal.scope == "status" and request.url.path != "/api/v1/status":
                raise Problem(
                    403, "status_only_token", "This token reaches only the status endpoint."
                )
            if request.method in _UNSAFE:
                check_origin(request, manager.settings, required=principal.kind == "session")
                account_action = request.url.path.startswith(
                    "/api/v1/shared-notes"
                ) or request.url.path in {
                    "/api/v1/auth/logout",
                    "/api/v1/auth/pairing",
                    "/api/v1/auth/account/password",
                    "/api/v1/auth/account/oidc-link",
                    "/api/v1/workspaces",
                    "/mcp",
                }
                if not principal.writable and not account_action:
                    raise Problem(403, "read_only_space", "This space is read-only.")
            admin = request.url.path.startswith(
                (
                    "/api/v1/settings",
                    "/api/v1/tokens",
                    "/api/v1/credentials",
                    "/api/v1/status",
                )
            ) or (request.method in _UNSAFE and request.url.path.startswith("/api/v1/plugins"))
            if admin and workspace["role"] != "owner":
                raise Problem(
                    403, "owner_required", "Only the space owner can manage this setting."
                )
            state = scope.setdefault("state", {})
            state.update(
                services=services, hosted_principal=principal, account=account, workspace=workspace
            )
        except Problem as exc:
            return await problem_response(exc.status, exc.code, exc.detail)(scope, receive, send)
        await self.app(scope, receive, send)


class HostedMCP:
    async def __call__(self, scope, receive, send):
        from spunto.mcp import MCP

        scoped = scope["state"]["services"]
        endpoint = MCP(scoped)
        endpoint.limits = scoped.mcp.limits
        async with endpoint.manager.run():
            await endpoint.app(scope, receive, send)


class LocalLogin(BaseModel):
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(max_length=passwords.MAX_BYTES)


class LocalRegistration(LocalLogin):
    display_name: str | None = Field(default=None, max_length=80)


class LocalPassword(BaseModel):
    username: str | None = Field(default=None, max_length=64)
    new_password: str = Field(max_length=passwords.MAX_BYTES)
    current_password: str | None = Field(default=None, max_length=passwords.MAX_BYTES)


class OIDCLink(BaseModel):
    current_password: str = Field(max_length=passwords.MAX_BYTES)


def _session_token(request, settings):
    if request.state.hosted_principal.kind != "session":
        raise Problem(
            403, "session_required", "Sign in in a browser to manage account credentials."
        )
    return request.cookies[sessions.cookie_name(settings)]


def _session_response(settings, token):
    response = Response(status_code=204, headers={"Cache-Control": "no-store"})
    response.set_cookie(
        sessions.cookie_name(settings),
        token,
        path="/",
        secure=settings.secure,
        httponly=True,
        samesite="lax",
        max_age=30 * 86400,
    )
    return response


@asynccontextmanager
async def _local_guess(request, services, username, *, registration=False):
    manager = services.hosted
    address = client_ip(request, services.settings)
    keys = (
        ["registration-ip:" + digest(address)]
        if registration
        else ["ip:" + digest(address), "name:" + digest(username.lower())]
    )
    if delay := services.backoff.slowdown():
        await asyncio.sleep(delay)
    async with services.backoff.turn:
        wait = max(services.backoff.retry_after(address), await manager.accounts.guess_wait(keys))
        if wait:
            raise Problem(
                429,
                "rate_limited",
                "Too many sign-in attempts. Try again later.",
                {"Retry-After": str(math.ceil(wait))},
            )
        if registration:
            # Successful signups also consume the address budget; otherwise an
            # open signup form could create unlimited accounts without a failure.
            await manager.accounts.guess_failed(keys)
        try:
            yield
        except Problem as exc:
            if exc.status in {401, 409}:
                services.backoff.failed(address)
                if not registration:
                    await manager.accounts.guess_failed(keys)
            raise
        else:
            services.backoff.succeeded(address)
            if not registration:
                await manager.accounts.guess_succeeded(keys)


def create_hosted_app(services):
    from spunto.api.collaboration import router as collaboration_router

    manager = services.hosted
    app = FastAPI(
        title="Spunto",
        version=__version__,
        openapi_url="/api/v1/openapi.json",
        docs_url=None,
        redoc_url=None,
    )
    app.state.services = services
    errors.install(app)

    @app.get("/healthz")
    async def healthz():
        return {"status": "ok"}

    @app.get("/readyz")
    async def readyz():
        try:
            async with manager.store._pool.connection() as conn:
                await conn.execute("SELECT 1")
            await manager.accounts.one("SELECT 1")
            return {"status": "ready"}
        except Exception:
            return JSONResponse({"status": "not ready"}, 503)

    @app.get("/api/v1/auth/options")
    async def options():
        return {
            "mode": "hosted",
            "password_enabled": True,
            "registration_enabled": services.settings.local_registration_enabled,
            "oidc_enabled": manager.oidc is not None,
            "login_url": "/api/v1/auth/oidc/start" if manager.oidc else None,
            "label": manager.settings.oidc_label,
        }

    @app.post("/api/v1/auth/login", status_code=204)
    async def local_login(body: LocalLogin, request: Request):
        check_origin(request, services.settings, required=True)
        async with _local_guess(request, services, body.username):
            account, token = await manager.accounts.password_sign_in(
                body.username, body.password, manager.dummy_password_hash
            )
        workspace = await manager.accounts.workspace(account["id"])
        await manager.services(workspace, account["id"])
        return _session_response(services.settings, token)

    @app.post("/api/v1/auth/register", status_code=204)
    async def local_register(body: LocalRegistration, request: Request):
        check_origin(request, services.settings, required=True)
        if not services.settings.local_registration_enabled:
            raise Problem(
                403,
                "registration_disabled",
                "Registration is closed. Ask the operator for an account.",
            )
        async with _local_guess(request, services, body.username, registration=True):
            account, workspace, token = await manager.accounts.register_local(
                body.username, body.password, body.display_name
            )
        await manager.services({**workspace, "role": "owner"}, account["id"])
        return _session_response(services.settings, token)

    @app.get("/api/v1/auth/account/security")
    async def account_security(request: Request):
        token = _session_token(request, services.settings)
        result = await manager.accounts.security(request.state.account["id"], token)
        result["reauth_url"] = (
            "/api/v1/auth/oidc/start?reauth=local-password"
            if manager.oidc and result["oidc_linked"]
            else None
        )
        return JSONResponse(result, headers={"Cache-Control": "no-store"})

    @app.put("/api/v1/auth/account/password", status_code=204)
    async def account_password(body: LocalPassword, request: Request):
        old_token = _session_token(request, services.settings)
        user_id = request.state.account["id"]
        async with _local_guess(request, services, user_id):
            token = await manager.accounts.set_local_password(
                user_id, old_token, body.username, body.new_password, body.current_password
            )
        return _session_response(services.settings, token)

    @app.post("/api/v1/auth/account/oidc-link")
    async def account_oidc_link(body: OIDCLink, request: Request):
        token = _session_token(request, services.settings)
        if not manager.oidc or request.state.account["issuer"] is not None:
            raise Problem(409, "link_unavailable", "That sign-in cannot be linked.")
        async with _local_guess(request, services, request.state.account["id"]):
            await manager.accounts.verify_local_password(
                request.state.account["id"], body.current_password, manager.dummy_password_hash
            )
            await manager.accounts.grant_reauthentication(
                request.state.account["id"], token, "oidc-link"
            )
        return JSONResponse(
            {"login_url": "/api/v1/auth/oidc/start?reauth=oidc-link"},
            headers={"Cache-Control": "no-store"},
        )

    @app.get("/api/v1/meta")
    async def meta(request: Request):
        result = {
            "version": __version__,
            "api_version": 1,
            "public_url": services.settings.public_url,
            "unencrypted": not services.settings.secure,
            "setup_pending": False,
            "max_upload_bytes": services.settings.max_upload_bytes,
            "sync_epoch": "sign-in-required",
            "mcp_url": services.settings.public_url + "/mcp"
            if services.settings.mcp_enabled
            else None,
            "ai_capabilities": [],
            "plugins_rev": 0,
            "min_android_build": 0,
        }
        scoped = getattr(request.state, "services", None)
        if scoped:
            async with scoped.store.read() as conn:
                values = dict(
                    (row[0], row[1])
                    for row in await conn.execute_fetchall(
                        "SELECT key,value FROM meta WHERE key IN ('sync_epoch','plugins_rev')"
                    )
                )
            result.update(
                sync_epoch=values["sync_epoch"],
                plugins_rev=int(values["plugins_rev"]),
                ai_capabilities=scoped.ai.capabilities,
            )
        return result

    @app.get("/api/v1/auth/oidc/start")
    async def start(request: Request, reauth: str | None = None):
        if not manager.oidc:
            raise Problem(404, "sso_unavailable", "Single sign-on is not configured.")
        kwargs = {}
        if reauth is not None:
            if reauth not in {"local-password", "oidc-link"}:
                raise Problem(422, "validation_failed", "Unknown sign-in verification request.")
            token = request.cookies.get(sessions.cookie_name(services.settings), "")
            account = await manager.accounts.resolve_session(token)
            if not account:
                raise Problem(401, "unauthenticated", "Sign in first.")
            if reauth == "local-password" and (account["issuer"] != services.settings.oidc_issuer):
                raise Problem(
                    403,
                    "reauthentication_unavailable",
                    "Use your local password to verify this account.",
                )
            if reauth == "oidc-link":
                proof = await manager.accounts.one(
                    "SELECT 1 FROM public.spunto_account_reauthentication WHERE session_hash=%s "
                    "AND user_id=%s AND purpose='oidc-link' AND expires_at>%s",
                    (digest(token), account["id"], now_ms()),
                )
                if not proof:
                    raise Problem(
                        403, "reauthentication_required", "Verify your local password first."
                    )
            kwargs = {"purpose": reauth, "user_id": account["id"], "session_hash": digest(token)}
        browser = secrets.token_urlsafe(32)
        try:
            authorization = await manager.oidc.begin(browser, **kwargs)
        except OIDCError:
            raise Problem(503, "sso_unavailable", "Sign-in is temporarily unavailable.") from None
        response = RedirectResponse(authorization.url, status_code=303)
        response.headers["Cache-Control"] = "no-store"
        response.set_cookie(
            "__Host-oidc" if services.settings.secure else "oidc",
            browser,
            secure=services.settings.secure,
            httponly=True,
            samesite="lax",
            max_age=300,
            path="/",
        )
        return response

    @app.get("/api/v1/auth/oidc/callback")
    async def callback(request: Request, code: str = "", state: str = ""):
        if not manager.oidc:
            raise Problem(404, "sso_unavailable", "Single sign-on is not configured.")
        browser = request.cookies.get("__Host-oidc" if services.settings.secure else "oidc", "")
        try:
            identity = await manager.oidc.complete(code, state, browser)
        except OIDCError:
            raise Problem(
                400, "sso_failed", "Sign-in failed or expired. Please try again."
            ) from None
        if identity.purpose:
            token = request.cookies.get(sessions.cookie_name(services.settings), "")
            account = await manager.accounts.resolve_session(token)
            if (
                not account
                or account["id"] != identity.user_id
                or digest(token) != identity.session_hash
            ):
                raise Problem(
                    403,
                    "reauthentication_required",
                    "The account session changed. Start verification again.",
                )
            if identity.purpose == "local-password":
                if (account["issuer"], account["subject"]) != (identity.issuer, identity.subject):
                    raise Problem(
                        403, "identity_mismatch", "Verify the same account that is signed in."
                    )
                await manager.accounts.grant_reauthentication(
                    account["id"], token, "local-password"
                )
            else:
                await manager.accounts.link_oidc(identity, account["id"], token)
            response = RedirectResponse(
                "/settings/account?security=reauthenticated", status_code=303
            )
        else:
            account, workspace = await manager.accounts.sign_in(identity)
            await manager.services({**workspace, "role": "owner"}, account["id"])
            token = await manager.accounts.issue_session(account["id"])
            response = _session_response(services.settings, token)
            response.status_code = 303
            response.headers["Location"] = "/"
        response.delete_cookie(
            "__Host-oidc" if services.settings.secure else "oidc",
            path="/",
            secure=services.settings.secure,
            httponly=True,
            samesite="lax",
        )
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/api/v1/auth/session")
    async def session(request: Request):
        account, workspace = request.state.account, request.state.workspace
        return {
            "owner_id": account["id"],
            "user_id": account["id"],
            "workspace_id": workspace["id"],
            "workspace_role": workspace["role"],
            "credential_id": getattr(
                request.state, "browser_session_id", request.state.hosted_principal.id
            ),
            "password_managed": False,
            "auth_method": account.get("auth_method", "oidc" if account["issuer"] else "password"),
        }

    @app.get("/api/v1/auth/me")
    async def me(request: Request):
        account, workspace = request.state.account, request.state.workspace
        credential = await manager.accounts.one(
            "SELECT username FROM public.spunto_local_credential WHERE user_id=%s", (account["id"],)
        )
        return {
            "account": {
                **{key: account[key] for key in ("id", "display_name", "email")},
                "username": credential["username"] if credential else None,
            },
            "workspace": {key: workspace[key] for key in ("id", "name", "role", "personal")},
            "auth": {
                "provider": "local"
                if account.get("auth_method") == "password" or not account["issuer"]
                else "oidc",
                "issuer": account["issuer"],
                "oidc_linked": account["issuer"] is not None,
            },
        }

    @app.post("/api/v1/auth/logout", status_code=204)
    async def logout(request: Request):
        principal = request.state.hosted_principal
        if principal.kind == "session":
            token = request.cookies.get(sessions.cookie_name(services.settings))
            await manager.accounts.revoke_session(token)
        else:
            bookkeeping = manager.store.for_workspace(
                principal.workspace_id, principal.user_id, "owner"
            )
            async with bookkeeping.write() as conn:
                await conn.execute(
                    "UPDATE credential SET revoked_at=? WHERE id=?",
                    (now_ms(), principal.id),
                )
        response = Response(status_code=204)
        if principal.kind == "session":
            response.delete_cookie(
                sessions.cookie_name(services.settings),
                path="/",
                secure=services.settings.secure,
                httponly=True,
                samesite="lax",
            )
        return response

    @app.post("/api/v1/auth/pairing")
    async def pairing(request: Request):
        code, now = secrets.token_urlsafe(32), now_ms()
        principal = request.state.hosted_principal
        if principal.kind != "session":
            raise Problem(403, "session_required", "Sign in in a browser to pair a device.")
        async with manager.accounts.transaction() as conn:
            await conn.execute(
                "INSERT INTO public.spunto_pairing "
                "(code_hash,workspace_id,user_id,credential_id,created_at,expires_at) "
                "VALUES (%s,%s,%s,%s,%s,%s)",
                (
                    digest(code),
                    principal.workspace_id,
                    principal.user_id,
                    principal.id,
                    now,
                    now + devices.PAIRING_MS,
                ),
            )
        return {
            "code": code,
            "public_url": services.settings.public_url,
            "expires_at": _iso(now + devices.PAIRING_MS),
        }

    @app.post("/api/v1/auth/pairing/exchange")
    async def exchange(body: dict, request: Request):
        check_origin(request, services.settings, required=False)
        code, name = body.get("code"), body.get("name")
        if (
            not isinstance(code, str)
            or len(code) > 100
            or not isinstance(name, str)
            or not 1 <= len(name.strip()) <= 120
        ):
            raise Problem(422, "validation_failed", "Provide a pairing code and device name.")
        async with manager.accounts.transaction() as conn:
            row = await (
                await conn.execute(
                    "DELETE FROM public.spunto_pairing "
                    "WHERE code_hash=%s AND expires_at>%s RETURNING *",
                    (digest(code), now_ms()),
                )
            ).fetchone()
            if not row:
                raise Problem(401, "invalid_pairing", "That pairing code is invalid or expired.")
            workspace = await manager.accounts.workspace(row["user_id"], row["workspace_id"])
            scoped = manager.store.for_workspace(workspace["id"], row["user_id"], "owner")
            async with scoped.write() as domain:
                await devices.active_session(domain, row["credential_id"], now_ms())
                credential = await devices.issue(domain, name.strip())
        return {
            **credential,
            "user_id": row["user_id"],
            "workspace_id": workspace["id"],
            "workspace_role": workspace["role"],
        }

    # SQLite owner-password routes use the hosted account endpoints above.
    # Reuse the remaining core routes with request-bound, forced-RLS stores.
    for module in (
        access,
        ai,
        notes,
        attachments,
        imports,
        plugins,
        reference,
        suggestions,
        sync,
        slip,
        themes,
    ):
        for route in module.router.routes:
            if route.path != "/api/v1/auth/password":
                app.router.routes.append(route)
    for route in auth.router.routes:
        if route.path == "/api/v1/credentials":
            app.router.routes.append(route)
    if services.settings.mcp_enabled:
        app.add_route("/mcp", HostedMCP(), methods=["POST", "GET", "DELETE", "OPTIONS"])
    app.include_router(collaboration_router)
    app.include_router(web.router)
    app.add_middleware(HostedIdentityMiddleware, manager=manager)
    app.add_middleware(SecurityHeadersMiddleware)
    app.add_middleware(WrongHostMiddleware, settings=services.settings)
    return app


def _iso(value):
    from spunto.core.notes import iso

    return iso(value)
