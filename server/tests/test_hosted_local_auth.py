"""Independent account acceptance: real restricted PostgreSQL and signed synthetic OIDC."""

import asyncio
import os
import sys
import time
import uuid
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from urllib.parse import parse_qs, urlsplit

import httpx
import psycopg
import pytest
from test_oidc import ISSUER, provider  # noqa: F401 (shared signed-provider fixture)

from spunto.auth import accounts as account_module
from spunto.auth.accounts import OIDCTransactions, digest
from spunto.auth.backoff import Backoff
from spunto.auth.oidc import OIDCClient, OIDCSettings
from spunto.config import Settings
from spunto.hosted import create_hosted_app, hosted_startup

pytestmark = pytest.mark.anyio
ORIGIN = "http://127.0.0.1:18081"
PASSWORD = "synthetic local account password"
REPLACEMENT = "synthetic replacement account password"


class FixtureURLs(dict):
    def __repr__(self):
        return "<private disposable PostgreSQL connection URLs>"


@dataclass
class Browser:
    client: httpx.AsyncClient
    services: object = field(repr=False)
    state: dict = field(repr=False)
    users: set
    names: set
    pending: set

    @property
    def accounts(self):
        return self.services.hosted.accounts

    async def request(self, method, path, *, token=None, origin=ORIGIN, **kwargs):
        # Each request owns its cookie explicitly, including anonymous requests.
        headers = {"Cookie": "session=" + token if token else ""}
        if origin is not None:
            headers["Origin"] = origin
        headers.update(kwargs.pop("headers", {}))
        response = await self.client.request(method, path, headers=headers, **kwargs)
        self.client.cookies.clear()
        return response

    async def register(self, name=None, *, operator=False):
        name = name or "acceptance_" + uuid.uuid4().hex
        self.names.add(name.lower())
        if operator:
            account, workspace, token = await self.accounts.register_local(
                name, PASSWORD, create_session=False
            )
            self.users.add(account["id"])
            assert token is None
            return {"user_id": account["id"], "workspace_id": workspace["id"], "username": name}
        response = await self.request(
            "POST", "/api/v1/auth/register", json={"username": name, "password": PASSWORD}
        )
        assert response.status_code == 204, response.text
        token = response.cookies["session"]
        account = await self.accounts.resolve_session(token)
        self.users.add(account["id"])
        result = (await self.request("GET", "/api/v1/auth/session", token=token)).json()
        return {**result, "token": token, "username": name}

    async def login(self, name, password=PASSWORD):
        return await self.request(
            "POST", "/api/v1/auth/login", json={"username": name, "password": password}
        )

    async def start_oidc(self, *, token=None, purpose=None):
        path = "/api/v1/auth/oidc/start" + ("?reauth=" + purpose if purpose else "")
        response = await self.request("GET", path, token=token)
        assert response.status_code == 303, response.text
        params = parse_qs(urlsplit(response.headers["location"]).query)
        assert params["redirect_uri"] == [ORIGIN + "/api/v1/auth/oidc/callback"]
        assert params["code_challenge_method"] == ["S256"]
        if purpose:
            assert params["prompt"] == ["login"] and params["max_age"] == ["0"]
        self.state["nonce"] = params["nonce"][0]
        self.pending.add(digest(params["state"][0]))
        return params["state"][0], response.cookies["oidc"]

    async def finish_oidc(self, transaction, subject, *, token=None, auth_time=None):
        state, browser = transaction
        self.state["claims"] = {"sub": subject}
        if auth_time is not None:
            self.state["claims"]["auth_time"] = auth_time
        cookie = "oidc=" + browser + ("; session=" + token if token else "")
        response = await self.request(
            "GET",
            "/api/v1/auth/oidc/callback",
            params={"code": "synthetic-authorization-code", "state": state},
            headers={"Cookie": cookie},
        )
        if response.status_code == 303 and "session" in response.cookies:
            account = await self.accounts.resolve_session(response.cookies["session"])
            self.users.add(account["id"])
        return response

    async def oidc_login(self, subject=None):
        subject = subject or uuid.uuid4().hex
        response = await self.finish_oidc(await self.start_oidc(), subject)
        assert response.status_code == 303, response.text
        token = response.cookies["session"]
        session = await self.request("GET", "/api/v1/auth/session", token=token)
        assert session.status_code == 200
        return {**session.json(), "token": token, "subject": subject}


@pytest.fixture
async def browsers(tmp_path, provider):  # noqa: F811 (imported pytest fixture)
    urls = FixtureURLs(
        {
            "runtime": os.environ.get("SPUNTO_TEST_POSTGRES_URL"),
            "identity": os.environ.get("SPUNTO_TEST_IDENTITY_URL"),
            "admin": os.environ.get("SPUNTO_TEST_POSTGRES_ADMIN_URL"),
        }
    )
    if not all(urls.values()):
        pytest.skip("Set the three disposable PostgreSQL test connection URLs")
    state, transport = provider
    users, names, pending = set(), set(), set()
    address = "2001:db8::" + uuid.uuid4().hex[:4] + ":" + uuid.uuid4().hex[:4]
    opened = 0

    @asynccontextmanager
    async def open_browser(*, oidc=False, registration=False):
        nonlocal opened
        opened += 1
        settings = Settings(
            public_url=ORIGIN,
            data_dir=tmp_path / str(opened),
            database_url=urls["runtime"],
            identity_database_url=urls["identity"],
            local_registration_enabled=registration,
            oidc_allow_insecure_loopback=True,
            **({"oidc_issuer": ISSUER, "oidc_client_id": "spunto"} if oidc else {}),
        )
        async with (
            httpx.AsyncClient(transport=transport) as http,
            hosted_startup(settings) as services,
        ):
            if oidc:
                services.hosted.oidc = OIDCClient(
                    OIDCSettings(
                        ISSUER,
                        "spunto",
                        ORIGIN + "/api/v1/auth/oidc/callback",
                        allow_insecure_loopback=True,
                    ),
                    http_client=http,
                    transaction_store=OIDCTransactions(services.hosted.accounts),
                )
            app = create_hosted_app(services)
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app, client=(address, 50000)), base_url=ORIGIN
            ) as client:
                yield Browser(client, services, state, users, names, pending)

    yield open_browser, urls
    async with await psycopg.AsyncConnection.connect(urls["admin"]) as conn:
        await conn.execute(
            "DELETE FROM public.spunto_oidc_transaction WHERE user_id=ANY(%s) "
            "OR state_hash=ANY(%s)",
            (list(users), list(pending)),
        )
        await conn.execute(
            "DELETE FROM public.spunto_workspace WHERE created_by=ANY(%s)", (list(users),)
        )
        await conn.execute("DELETE FROM public.spunto_account WHERE id=ANY(%s)", (list(users),))
        keys = ["ip:" + digest(address), "registration-ip:" + digest(address)] + [
            "name:" + digest(n) for n in names | users
        ]
        await conn.execute("DELETE FROM public.spunto_auth_throttle WHERE key=ANY(%s)", (keys,))


async def test_no_oidc_startup_default_closed_registration_and_operator_bootstrap(browsers):
    open_browser, _ = browsers
    async with open_browser() as browser:
        assert (await browser.request("GET", "/readyz")).status_code == 200
        options = (await browser.request("GET", "/api/v1/auth/options")).json()
        assert options["mode"] == "hosted" and options["password_enabled"]
        assert not options["registration_enabled"] and not options["oidc_enabled"]
        assert not options.get("login_url")
        denied = await browser.request(
            "POST", "/api/v1/auth/register", json={"username": "closed", "password": PASSWORD}
        )
        assert denied.status_code == 403 and denied.json()["code"] == "registration_disabled"
        assert (await browser.request("GET", "/api/v1/auth/oidc/start")).status_code == 404
        owner = await browser.register(operator=True)
        login = await browser.login(owner["username"])
        assert login.status_code == 204 and login.headers["cache-control"] == "no-store"
        assert (
            "HttpOnly" in login.headers["set-cookie"]
            and "SameSite=lax" in login.headers["set-cookie"]
        )
        session = await browser.request(
            "GET", "/api/v1/auth/session", token=login.cookies["session"]
        )
        assert session.json()["user_id"] == owner["user_id"]
        assert session.json()["auth_method"] == "password"


async def test_registered_users_have_distinct_replicas_composite_notes_and_search(browsers):
    open_browser, _ = browsers
    async with open_browser(registration=True) as browser:
        alice, bob = await browser.register(), await browser.register()
        assert alice["user_id"] != bob["user_id"] and alice["workspace_id"] != bob["workspace_id"]
        note_id = str(uuid.uuid7())
        for user, text in ((alice, "Alice sentinel orchid"), (bob, "Bob sentinel tulip")):
            created = await browser.request(
                "POST", "/api/v1/notes", token=user["token"], json={"id": note_id, "body": text}
            )
            assert created.status_code == 201, created.text
        for user, own, other in ((alice, "orchid", "tulip"), (bob, "tulip", "orchid")):
            notes = await browser.request("GET", "/api/v1/notes", token=user["token"])
            assert len(notes.json()["notes"]) == 1 and own in notes.json()["notes"][0]["body"]
            assert (
                await browser.request("GET", "/api/v1/search?q=" + other, token=user["token"])
            ).json()["hits"] == []
            assert (
                len(
                    (
                        await browser.request("GET", "/api/v1/search?q=" + own, token=user["token"])
                    ).json()["hits"]
                )
                == 1
            )
        denied = await browser.request(
            "GET",
            "/api/v1/notes",
            token=bob["token"],
            headers={"X-Spunto-Workspace": alice["workspace_id"]},
        )
        assert denied.status_code == 404
        duplicate = await browser.request(
            "POST",
            "/api/v1/auth/register",
            json={"username": alice["username"].upper(), "password": PASSWORD},
        )
        assert (
            duplicate.status_code == 409 and duplicate.json()["code"] == "registration_unavailable"
        )
        login = await browser.login(alice["username"].upper())
        assert login.status_code == 204
        assert (await browser.accounts.resolve_session(login.cookies["session"]))["id"] == alice[
            "user_id"
        ]


@pytest.mark.parametrize("endpoint", ["login", "register"])
@pytest.mark.parametrize("origin", [None, "https://untrusted.example.test"])
async def test_public_password_writes_require_origin_even_without_cookie(
    browsers, endpoint, origin
):
    open_browser, _ = browsers
    async with open_browser(registration=True) as browser:
        user = await browser.register(operator=True)
        response = await browser.request(
            "POST",
            "/api/v1/auth/" + endpoint,
            origin=origin,
            json={"username": user["username"], "password": PASSWORD},
        )
        assert response.status_code == 403 and response.json()["code"] == "origin_mismatch"
        assert "set-cookie" not in response.headers
        row = await browser.accounts.one(
            "SELECT count(*) AS n FROM public.spunto_login_session WHERE user_id=%s",
            (user["user_id"],),
        )
        assert row["n"] == 0


async def test_registration_validation_and_generic_login_failure(browsers):
    open_browser, _ = browsers
    async with open_browser(registration=True) as browser:
        for name, password in (
            ("ab", PASSWORD),
            (".invalid", PASSWORD),
            ("éclair", PASSWORD),
            ("safe", "short"),
            ("safe", "界" * 342),
        ):
            response = await browser.request(
                "POST", "/api/v1/auth/register", json={"username": name, "password": password}
            )
            assert response.status_code == 422
        user = await browser.register(operator=True)
        unknown = "unknown_" + uuid.uuid4().hex
        browser.names.add(unknown)
        wrong = await browser.login(user["username"], "incorrect synthetic password")
        absent = await browser.login(unknown, "incorrect synthetic password")
        assert wrong.status_code == absent.status_code == 401
        assert wrong.json() == absent.json()
        assert wrong.json()["code"] == "invalid_credentials"
        assert "set-cookie" not in wrong.headers and "set-cookie" not in absent.headers


async def test_unicode_username_cannot_alias_an_ascii_account(browsers):
    open_browser, _ = browsers
    async with open_browser(registration=True) as browser:
        name = "kelvin_" + uuid.uuid4().hex
        alias = "K" + name[1:]
        denied = await browser.request(
            "POST", "/api/v1/auth/register", json={"username": alias, "password": PASSWORD}
        )
        assert denied.status_code == 422
        user = await browser.register(name, operator=True)
        assert (await browser.login(alias)).status_code == 401
        valid = await browser.login(name.upper())
        assert valid.status_code == 204
        assert (await browser.accounts.resolve_session(valid.cookies["session"]))["id"] == user[
            "user_id"
        ]


async def test_password_change_current_proof_rotation_and_logout_replay(browsers):
    open_browser, _ = browsers
    async with open_browser(registration=True) as browser:
        alice, bob = await browser.register(), await browser.register()
        second = (await browser.login(alice["username"])).cookies["session"]
        for current in (None, "incorrect synthetic password"):
            failed = await browser.request(
                "PUT",
                "/api/v1/auth/account/password",
                token=alice["token"],
                json={"new_password": REPLACEMENT, "current_password": current},
            )
            assert failed.status_code == 401 and failed.json()["code"] == "wrong_password"
        changed = await browser.request(
            "PUT",
            "/api/v1/auth/account/password",
            token=alice["token"],
            json={"new_password": REPLACEMENT, "current_password": PASSWORD},
        )
        assert changed.status_code == 204, changed.text
        fresh = changed.cookies["session"]
        assert fresh not in {alice["token"], second}
        for old in (alice["token"], second):
            assert (
                await browser.request("GET", "/api/v1/auth/session", token=old)
            ).status_code == 401
        assert (
            await browser.request("GET", "/api/v1/auth/session", token=bob["token"])
        ).status_code == 200
        assert (await browser.login(alice["username"])).status_code == 401
        assert (await browser.login(alice["username"], REPLACEMENT)).status_code == 204
        stored = await browser.accounts.one(
            "SELECT password_hash FROM public.spunto_local_credential WHERE user_id=%s",
            (alice["user_id"],),
        )
        assert (
            stored["password_hash"].startswith("$argon2id$")
            and REPLACEMENT not in stored["password_hash"]
        )
        assert (
            await browser.request("POST", "/api/v1/auth/logout", token=fresh)
        ).status_code == 204
        assert (
            await browser.request("GET", "/api/v1/auth/session", token=fresh)
        ).status_code == 401


@pytest.mark.parametrize("kind", ["pat", "device"])
async def test_nonbrowser_credential_cannot_manage_account_even_with_browser_cookie(browsers, kind):
    open_browser, _ = browsers
    async with open_browser(oidc=True, registration=True) as browser:
        user = await browser.register()
        if kind == "pat":
            created = await browser.request(
                "POST",
                "/api/v1/tokens",
                token=user["token"],
                json={"name": "Synthetic account boundary", "scope": "write"},
            )
            assert created.status_code == 201, created.text
        else:
            pairing = await browser.request("POST", "/api/v1/auth/pairing", token=user["token"])
            assert pairing.status_code == 200
            created = await browser.request(
                "POST",
                "/api/v1/auth/pairing/exchange",
                json={"code": pairing.json()["code"], "name": "Synthetic local account device"},
            )
            assert created.status_code == 200, created.text
            assert created.json()["user_id"] == user["user_id"]
            assert created.json()["workspace_id"] == user["workspace_id"]
        headers = {"Authorization": "Bearer " + created.json()["token"]}
        for method, path, payload in (
            ("GET", "/api/v1/auth/account/security", None),
            (
                "PUT",
                "/api/v1/auth/account/password",
                {"new_password": REPLACEMENT, "current_password": PASSWORD},
            ),
            ("POST", "/api/v1/auth/account/oidc-link", {"current_password": PASSWORD}),
        ):
            response = await browser.request(
                method,
                path,
                token=user["token"],
                headers=headers,
                **({"json": payload} if payload else {}),
            )
            assert response.status_code == 403 and response.json()["code"] == "session_required"


async def test_throttle_survives_restart_and_success_resets_durable_counter(browsers, monkeypatch):
    open_browser, _ = browsers
    now = [int(time.time() * 1000)]
    monkeypatch.setattr(account_module, "now_ms", lambda: now[0])
    async with open_browser(registration=True) as browser:
        user = await browser.register()
        browser.services.backoff = Backoff(clock=lambda: now[0] / 1000)
        for _ in range(5):
            assert (
                await browser.login(user["username"], "incorrect synthetic password")
            ).status_code == 401
        blocked = await browser.login(user["username"])
        assert blocked.status_code == 429 and int(blocked.headers["retry-after"]) >= 1
    async with open_browser() as browser:
        assert browser.services.backoff.retry_after("unused") == 0
        blocked = await browser.login(user["username"])
        assert blocked.status_code == 429 and int(blocked.headers["retry-after"]) >= 1
        now[0] += 2_000
        browser.services.backoff = Backoff(clock=lambda: now[0] / 1000)
        assert (await browser.login(user["username"])).status_code == 204
        remaining = await browser.accounts.one(
            "SELECT count(*) AS n FROM public.spunto_auth_throttle WHERE key=%s",
            ("name:" + digest(user["username"]),),
        )
        assert remaining["n"] == 0
        for _ in range(5):
            assert (
                await browser.login(user["username"], "incorrect synthetic password")
            ).status_code == 401
        blocked = await browser.login(user["username"])
        assert blocked.status_code == 429 and int(blocked.headers["retry-after"]) == 1


async def test_successful_registration_consumes_durable_address_budget(browsers, monkeypatch):
    open_browser, _ = browsers
    now = int(time.time() * 1000)
    monkeypatch.setattr(account_module, "now_ms", lambda: now)
    async with open_browser(registration=True) as browser:
        for _ in range(5):
            await browser.register()
        denied = await browser.request(
            "POST",
            "/api/v1/auth/register",
            json={"username": "limited_" + uuid.uuid4().hex, "password": PASSWORD},
        )
        assert denied.status_code == 429 and int(denied.headers["retry-after"]) == 1
    async with open_browser(registration=True) as browser:
        denied = await browser.request(
            "POST",
            "/api/v1/auth/register",
            json={"username": "limited_" + uuid.uuid4().hex, "password": PASSWORD},
        )
        assert denied.status_code == 429 and "set-cookie" not in denied.headers


async def test_operator_password_reset_keeps_exact_account_and_revokes_sessions(browsers):
    open_browser, _ = browsers
    async with open_browser(registration=True) as browser:
        alice, bob = await browser.register(), await browser.register()
        sibling = (await browser.login(alice["username"])).cookies["session"]
        token = await browser.accounts.set_local_password(
            alice["user_id"], None, None, REPLACEMENT, None, operator=True
        )
        assert token is None
        for old in (alice["token"], sibling):
            assert (
                await browser.request("GET", "/api/v1/auth/session", token=old)
            ).status_code == 401
        assert (
            await browser.request("GET", "/api/v1/auth/session", token=bob["token"])
        ).status_code == 200
        assert (await browser.login(alice["username"])).status_code == 401
        fresh = await browser.login(alice["username"], REPLACEMENT)
        assert fresh.status_code == 204
        session = (
            await browser.request("GET", "/api/v1/auth/session", token=fresh.cookies["session"])
        ).json()
        assert (
            session["user_id"] == alice["user_id"]
            and session["workspace_id"] == alice["workspace_id"]
        )


async def test_operator_cli_uses_identity_only_stdin_and_exact_reset_account(browsers):
    open_browser, urls = browsers
    env = {key: value for key, value in os.environ.items() if not key.startswith("SPUNTO_")}
    env["SPUNTO_IDENTITY_DATABASE_URL"] = urls["identity"]

    async def cli(*arguments, password=PASSWORD):
        process = await asyncio.create_subprocess_exec(
            sys.executable,
            "-c",
            "from spunto.cli import main; raise SystemExit(main())",
            "account",
            *arguments,
            env=env,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            output, error = await asyncio.wait_for(
                process.communicate((password + "\n").encode()), 30
            )
        except BaseException:
            if process.returncode is None:
                process.kill()
            await process.wait()
            raise
        assert password.encode() not in output + error
        return process.returncode, output.decode(), error.decode()

    async with open_browser() as browser:
        username = "cli_" + uuid.uuid4().hex
        browser.names.add(username)
        code, output, error = await cli("create", "--username", username)
        assert code == 0, error
        user_id = str(uuid.UUID(output.strip().removeprefix("Hosted account created: ")))
        browser.users.add(user_id)
        account = await browser.accounts.one(
            "SELECT id FROM public.spunto_account WHERE id=%s", (user_id,)
        )
        assert account["id"] == user_id
        first = await browser.login(username)
        assert first.status_code == 204
        token = first.cookies["session"]
        before = (await browser.request("GET", "/api/v1/auth/session", token=token)).json()
        other = await browser.register(operator=True)
        other_login = await browser.login(other["username"])
        assert other_login.status_code == 204
        other_token = other_login.cookies["session"]
        count = await browser.accounts.one("SELECT count(*) AS n FROM public.spunto_account")
        for invalid in ("not-a-uuid", str(uuid.uuid4())):
            code, _, _ = await cli("set-password", "--user", invalid, password=REPLACEMENT)
            assert code == 1
            assert (await browser.accounts.one("SELECT count(*) AS n FROM public.spunto_account"))[
                "n"
            ] == count["n"]
            assert (
                await browser.request("GET", "/api/v1/auth/session", token=token)
            ).status_code == 200
        code, output, error = await cli("set-password", "--user", user_id, password=REPLACEMENT)
        assert code == 0, error
        assert "all browser sessions revoked" in output
        assert (
            await browser.request("GET", "/api/v1/auth/session", token=token)
        ).status_code == 401
        assert (
            await browser.request("GET", "/api/v1/auth/session", token=other_token)
        ).status_code == 200
        assert (await browser.login(username)).status_code == 401
        fresh = await browser.login(username, REPLACEMENT)
        assert fresh.status_code == 204
        after = (
            await browser.request("GET", "/api/v1/auth/session", token=fresh.cookies["session"])
        ).json()
        assert (
            after["user_id"] == before["user_id"]
            and after["workspace_id"] == before["workspace_id"]
        )


async def test_exact_username_space_and_note_grants_revoke_without_third_user_access(browsers):
    open_browser, _ = browsers
    async with open_browser(registration=True) as browser:
        alice, bob, carol = (
            await browser.register(),
            await browser.register(),
            await browser.register(),
        )
        space = await browser.request(
            "POST",
            "/api/v1/workspaces",
            token=alice["token"],
            json={"name": "Synthetic local collaboration"},
        )
        assert space.status_code == 201
        space_id = space.json()["id"]
        member_path = "/api/v1/workspaces/" + space_id + "/members"
        for selector in (
            {},
            {"username": bob["username"], "user_id": carol["user_id"]},
            {"username": bob["username"], "email": "synthetic@example.test"},
        ):
            assert (
                await browser.request(
                    "POST", member_path, token=alice["token"], json={**selector, "role": "editor"}
                )
            ).status_code == 422
        assert (
            await browser.request(
                "POST",
                member_path,
                token=alice["token"],
                json={"username": bob["username"][:-1], "role": "editor"},
            )
        ).status_code == 404
        granted = await browser.request(
            "POST",
            member_path,
            token=alice["token"],
            json={"username": bob["username"].upper(), "role": "editor"},
        )
        assert granted.status_code == 200 and granted.json()["user_id"] == bob["user_id"]
        assert granted.json()["username"] == bob["username"]
        scoped = {"X-Spunto-Workspace": space_id}
        assert (
            await browser.request(
                "POST",
                "/api/v1/notes",
                token=bob["token"],
                headers=scoped,
                json={"body": "Local shared space sentinel"},
            )
        ).status_code == 201
        assert (
            await browser.request("GET", "/api/v1/notes", token=carol["token"], headers=scoped)
        ).status_code == 404
        assert (
            await browser.request(
                "DELETE", member_path + "/" + bob["user_id"], token=alice["token"]
            )
        ).status_code == 204
        assert (
            await browser.request("GET", "/api/v1/notes", token=bob["token"], headers=scoped)
        ).status_code == 404
        note = await browser.request(
            "POST",
            "/api/v1/notes",
            token=alice["token"],
            json={"body": "Local individually shared sentinel"},
        )
        assert note.status_code == 201
        note_id = note.json()["id"]
        shares = "/api/v1/notes/" + note_id + "/shares"
        granted = await browser.request(
            "POST",
            shares,
            token=alice["token"],
            json={"username": bob["username"], "role": "viewer"},
        )
        assert granted.status_code == 200 and granted.json()["user_id"] == bob["user_id"]
        shared = "/api/v1/shared-notes/" + note_id
        assert (await browser.request("GET", shared, token=bob["token"])).status_code == 200
        assert (await browser.request("GET", shared, token=carol["token"])).status_code == 404
        assert (
            await browser.request("GET", "/api/v1/notes/" + note_id, token=bob["token"])
        ).status_code == 404
        assert (
            await browser.request(
                "PATCH",
                shared,
                token=bob["token"],
                json={"body": "Denied viewer edit", "version": note.json()["version"]},
            )
        ).status_code == 403
        assert (
            await browser.request(
                "POST",
                shares,
                token=alice["token"],
                json={"username": bob["username"], "role": "editor"},
            )
        ).status_code == 200
        assert (
            await browser.request(
                "PATCH",
                shared,
                token=bob["token"],
                json={"body": "Accepted local editor", "version": note.json()["version"]},
            )
        ).status_code == 200
        assert (
            await browser.request("DELETE", shares + "/" + bob["user_id"], token=alice["token"])
        ).status_code == 204
        assert (await browser.request("GET", shared, token=bob["token"])).status_code == 404
        assert (await browser.request("GET", "/api/v1/notes", token=bob["token"])).json()[
            "notes"
        ] == []
        for user in (alice, bob, carol):
            assert (
                await browser.request("GET", "/api/v1/auth/session", token=user["token"])
            ).json()["user_id"] == user["user_id"]


@pytest.mark.parametrize(
    "failure", ["missing_time", "stale_time", "different_subject", "different_session"]
)
async def test_first_sso_password_requires_fresh_same_identity_and_session(browsers, failure):
    open_browser, _ = browsers
    async with open_browser(oidc=True) as browser:
        alice, bob = await browser.oidc_login(), await browser.oidc_login()
        transaction = await browser.start_oidc(token=alice["token"], purpose="local-password")
        response = await browser.finish_oidc(
            transaction,
            bob["subject"] if failure == "different_subject" else alice["subject"],
            token=bob["token"] if failure == "different_session" else alice["token"],
            auth_time=None
            if failure == "missing_time"
            else time.time() - 600
            if failure == "stale_time"
            else time.time(),
        )
        assert response.status_code == (400 if failure in {"missing_time", "stale_time"} else 403)
        assert (
            await browser.finish_oidc(
                transaction, alice["subject"], token=alice["token"], auth_time=time.time()
            )
        ).status_code == 400
        for user in (alice, bob):
            security = await browser.request(
                "GET", "/api/v1/auth/account/security", token=user["token"]
            )
            assert (
                not security.json()["reauthenticated"] and not security.json()["local_password_set"]
            )
        browser.names.add("fresh_" + alice["user_id"])
        denied = await browser.request(
            "PUT",
            "/api/v1/auth/account/password",
            token=alice["token"],
            json={"username": "fresh_" + alice["user_id"], "new_password": PASSWORD},
        )
        assert denied.status_code == 403 and denied.json()["code"] == "reauthentication_required"


async def test_fresh_sso_password_setup_keeps_account_library_and_revokes_old_sessions(browsers):
    open_browser, _ = browsers
    async with open_browser(oidc=True) as browser:
        alice = await browser.oidc_login()
        sibling = await browser.oidc_login(alice["subject"])
        note = await browser.request(
            "POST",
            "/api/v1/notes",
            token=alice["token"],
            json={"body": "Synthetic SSO library retained"},
        )
        assert note.status_code == 201
        transaction = await browser.start_oidc(token=alice["token"], purpose="local-password")
        verified = await browser.finish_oidc(
            transaction, alice["subject"], token=alice["token"], auth_time=time.time()
        )
        assert (
            verified.status_code == 303
            and verified.headers["location"] == "/settings/account?security=reauthenticated"
        )
        assert (
            await browser.request("GET", "/api/v1/auth/account/security", token=alice["token"])
        ).json()["reauthenticated"]
        assert not (
            await browser.request("GET", "/api/v1/auth/account/security", token=sibling["token"])
        ).json()["reauthenticated"]
        name = "linked_" + uuid.uuid4().hex
        browser.names.add(name)
        assert (
            await browser.request(
                "PUT",
                "/api/v1/auth/account/password",
                token=sibling["token"],
                json={"username": name, "new_password": PASSWORD},
            )
        ).status_code == 403
        changed = await browser.request(
            "PUT",
            "/api/v1/auth/account/password",
            token=alice["token"],
            json={"username": name, "new_password": PASSWORD},
        )
        assert changed.status_code == 204, changed.text
        for old in (alice["token"], sibling["token"]):
            assert (
                await browser.request("GET", "/api/v1/auth/session", token=old)
            ).status_code == 401
        login = await browser.login(name)
        assert login.status_code == 204
        token = login.cookies["session"]
        session = (await browser.request("GET", "/api/v1/auth/session", token=token)).json()
        assert (
            session["user_id"] == alice["user_id"]
            and session["workspace_id"] == alice["workspace_id"]
        )
        assert (
            await browser.request("GET", "/api/v1/notes/" + note.json()["id"], token=token)
        ).json()["body"] == "Synthetic SSO library retained"
        assert (await browser.oidc_login(alice["subject"]))["user_id"] == alice["user_id"]
        assert not (
            await browser.request("GET", "/api/v1/auth/account/security", token=token)
        ).json()["reauthenticated"]


async def test_explicit_oidc_link_is_bound_to_local_account_not_email_or_other_browser(browsers):
    open_browser, _ = browsers
    async with open_browser(oidc=True, registration=True) as browser:
        alice, bob = await browser.register(), await browser.register()
        assert (
            await browser.request(
                "GET", "/api/v1/auth/oidc/start?reauth=oidc-link", token=alice["token"]
            )
        ).status_code == 403
        wrong = await browser.request(
            "POST",
            "/api/v1/auth/account/oidc-link",
            token=alice["token"],
            json={"current_password": "incorrect synthetic password"},
        )
        assert wrong.status_code == 401 and wrong.json()["code"] == "wrong_password"
        proof = await browser.request(
            "POST",
            "/api/v1/auth/account/oidc-link",
            token=alice["token"],
            json={"current_password": PASSWORD},
        )
        assert proof.status_code == 200
        transaction = await browser.start_oidc(token=alice["token"], purpose="oidc-link")
        subject = uuid.uuid4().hex
        assert (
            await browser.finish_oidc(
                transaction, subject, token=bob["token"], auth_time=time.time()
            )
        ).status_code == 403
        transaction = await browser.start_oidc(token=alice["token"], purpose="oidc-link")
        assert (
            await browser.finish_oidc(
                transaction, subject, token=alice["token"], auth_time=time.time()
            )
        ).status_code == 303
        signed_in = await browser.oidc_login(subject)
        assert (
            signed_in["user_id"] == alice["user_id"]
            and signed_in["workspace_id"] == alice["workspace_id"]
        )
        assert not (
            await browser.request("GET", "/api/v1/auth/account/security", token=bob["token"])
        ).json()["oidc_linked"]
        # The fixture gives every subject the same verified email; it must not merge them.
        other = await browser.oidc_login()
        assert other["user_id"] not in {alice["user_id"], bob["user_id"]}


async def test_link_cannot_take_existing_sso_identity_or_use_expired_local_proof(browsers):
    open_browser, _ = browsers
    async with open_browser(oidc=True, registration=True) as browser:
        local = await browser.register()
        sso = await browser.oidc_login()
        assert (
            await browser.request(
                "POST",
                "/api/v1/auth/account/oidc-link",
                token=local["token"],
                json={"current_password": PASSWORD},
            )
        ).status_code == 200
        transaction = await browser.start_oidc(token=local["token"], purpose="oidc-link")
        denied = await browser.finish_oidc(
            transaction, sso["subject"], token=local["token"], auth_time=time.time()
        )
        assert denied.status_code == 409 and denied.json()["code"] == "link_unavailable"
        assert (await browser.oidc_login(sso["subject"]))["user_id"] == sso["user_id"]
        async with browser.accounts.transaction() as conn:
            await conn.execute(
                "UPDATE public.spunto_account_reauthentication SET expires_at=0 WHERE user_id=%s",
                (local["user_id"],),
            )
        assert (
            await browser.request(
                "GET", "/api/v1/auth/oidc/start?reauth=oidc-link", token=local["token"]
            )
        ).status_code == 403
        assert not (
            await browser.request("GET", "/api/v1/auth/account/security", token=local["token"])
        ).json()["oidc_linked"]


async def account_security_snapshot(browser, users):
    ids = [user["user_id"] for user in users]
    return {
        "credentials": await browser.accounts.all(
            "SELECT * FROM public.spunto_local_credential WHERE user_id=ANY(%s) ORDER BY user_id",
            (ids,),
        ),
        "sessions": await browser.accounts.all(
            "SELECT * FROM public.spunto_login_session WHERE user_id=ANY(%s) ORDER BY token_hash",
            (ids,),
        ),
    }


async def shared_space(browser, alice, bob):
    response = await browser.request(
        "POST", "/api/v1/workspaces", token=alice["token"], json={"name": "Actor fence fixture"}
    )
    assert response.status_code == 201
    space = response.json()["id"]
    response = await browser.request(
        "POST",
        "/api/v1/workspaces/" + space + "/members",
        token=alice["token"],
        json={"user_id": bob["user_id"], "role": "editor"},
    )
    assert response.status_code == 200
    return space


@pytest.mark.parametrize("mismatch", ["account", "credential", "account_only", "credential_only"])
async def test_actor_mismatch_precedes_shared_space_and_global_account_writes(
    browsers, monkeypatch, mismatch
):
    open_browser, _ = browsers
    async with open_browser(registration=True) as browser:
        alice, bob = await browser.register(), await browser.register()
        space = await shared_space(browser, alice, bob)
        created = await browser.request(
            "POST",
            "/api/v1/notes",
            token=alice["token"],
            headers={"X-Spunto-Workspace": space},
            json={"body": "Original shared actor sentinel"},
        )
        assert created.status_code == 201
        note = created.json()
        headers = {"X-Spunto-Workspace": space}
        if mismatch != "credential_only":
            headers["X-Spunto-Account"] = (
                alice["user_id"] if mismatch == "account" else bob["user_id"]
            )
        if mismatch != "account_only":
            headers["X-Spunto-Credential"] = (
                alice["credential_id"] if mismatch == "credential" else bob["credential_id"]
            )
        before = await account_security_snapshot(browser, (alice, bob))

        async def forbidden(*args, **kwargs):
            raise AssertionError(
                "Actor mismatch must be rejected before any workspace/runtime action"
            )

        with monkeypatch.context() as guard:
            guard.setattr(browser.accounts, "workspace", forbidden)
            guard.setattr(browser.services.hosted, "services", forbidden)
            for method, path, body in (
                ("GET", "/api/v1/notes", None),
                ("POST", "/api/v1/notes", {"body": "Wrong actor create"}),
                (
                    "PATCH",
                    "/api/v1/notes/" + note["id"],
                    {"body": "Wrong actor edit", "version": note["version"]},
                ),
                (
                    "PUT",
                    "/api/v1/auth/account/password",
                    {"new_password": REPLACEMENT, "current_password": PASSWORD},
                ),
                ("POST", "/api/v1/auth/account/oidc-link", {"current_password": PASSWORD}),
            ):
                response = await browser.request(
                    method,
                    path,
                    token=bob["token"],
                    headers=headers,
                    **({"json": body} if body else {}),
                )
                assert response.status_code == 409 and response.json()["code"] == "identity_changed"
                assert "set-cookie" not in response.headers
        assert await account_security_snapshot(browser, (alice, bob)) == before
        notes = await browser.request(
            "GET", "/api/v1/notes", token=alice["token"], headers={"X-Spunto-Workspace": space}
        )
        assert len(notes.json()["notes"]) == 1 and notes.json()["notes"][0]["body"] == note["body"]
        for user in (alice, bob):
            assert (
                await browser.request("GET", "/api/v1/auth/session", token=user["token"])
            ).status_code == 200


async def test_delayed_rotated_cookie_cannot_act_as_other_or_older_browser_session(
    browsers, monkeypatch
):
    open_browser, _ = browsers
    async with open_browser(registration=True) as browser:
        alice, bob = await browser.register(), await browser.register()
        space = await shared_space(browser, alice, bob)
        rotated = await browser.request(
            "PUT",
            "/api/v1/auth/account/password",
            token=alice["token"],
            json={"current_password": PASSWORD, "new_password": REPLACEMENT},
        )
        assert rotated.status_code == 204
        fresh = rotated.cookies["session"]
        before = await account_security_snapshot(browser, (alice, bob))

        async def forbidden(*args, **kwargs):
            raise AssertionError(
                "A replaced cookie cannot inherit the initiating browser's authority"
            )

        with monkeypatch.context() as guard:
            guard.setattr(browser.accounts, "workspace", forbidden)
            guard.setattr(browser.services.hosted, "services", forbidden)
            for initiating in (alice, bob):
                expected = {
                    "X-Spunto-Workspace": space,
                    "X-Spunto-Account": initiating["user_id"],
                    "X-Spunto-Credential": initiating["credential_id"],
                }
                for method, path, body in (
                    ("POST", "/api/v1/notes", {"body": "Delayed cookie actor write"}),
                    (
                        "PUT",
                        "/api/v1/auth/account/password",
                        {"current_password": REPLACEMENT, "new_password": PASSWORD},
                    ),
                ):
                    response = await browser.request(
                        method, path, token=fresh, headers=expected, json=body
                    )
                    assert (
                        response.status_code == 409
                        and response.json()["code"] == "identity_changed"
                    )
                    assert "set-cookie" not in response.headers
        assert await account_security_snapshot(browser, (alice, bob)) == before
        assert (
            await browser.request(
                "GET", "/api/v1/notes", token=fresh, headers={"X-Spunto-Workspace": space}
            )
        ).json()["notes"] == []
        assert (
            await browser.request("GET", "/api/v1/auth/session", token=fresh)
        ).status_code == 200
        assert (
            await browser.request("GET", "/api/v1/auth/session", token=bob["token"])
        ).status_code == 200


async def test_actor_headers_allow_legacy_hydration_valid_browser_and_bearer(browsers):
    open_browser, _ = browsers
    async with open_browser(registration=True) as browser:
        alice, bob = await browser.register(), await browser.register()
        initial = await browser.request("GET", "/api/v1/auth/session", token=alice["token"])
        assert (
            initial.status_code == 200 and initial.json()["credential_id"] == alice["credential_id"]
        )
        expected = {
            "X-Spunto-Account": alice["user_id"],
            "X-Spunto-Credential": alice["credential_id"],
        }
        created = await browser.request(
            "POST",
            "/api/v1/notes",
            token=alice["token"],
            headers=expected,
            json={"body": "Valid initiating actor"},
        )
        assert created.status_code == 201
        pat = await browser.request(
            "POST",
            "/api/v1/tokens",
            token=alice["token"],
            headers=expected,
            json={"name": "Synthetic bearer actor boundary", "scope": "write"},
        )
        assert pat.status_code == 201
        mixed = {
            "Authorization": "Bearer " + pat.json()["token"],
            "X-Spunto-Account": bob["user_id"],
            "X-Spunto-Credential": bob["credential_id"],
        }
        result = await browser.request(
            "POST",
            "/api/v1/notes",
            token=bob["token"],
            headers=mixed,
            json={"body": "Bearer keeps its explicit authority"},
        )
        assert result.status_code == 201
        assert (await browser.request("GET", "/api/v1/notes", token=bob["token"])).json()[
            "notes"
        ] == []
        assert (
            len(
                (await browser.request("GET", "/api/v1/notes", token=alice["token"])).json()[
                    "notes"
                ]
            )
            == 2
        )


async def test_new_identity_tables_deny_runtime_and_public_without_domain_control_access(browsers):
    _, urls = browsers
    tables = ("spunto_local_credential", "spunto_account_reauthentication", "spunto_auth_throttle")
    async with await psycopg.AsyncConnection.connect(urls["runtime"], autocommit=True) as runtime:
        role = await (
            await runtime.execute(
                "SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user"
            )
        ).fetchone()
        assert role == (False, False)
        for table in tables:
            with pytest.raises(psycopg.errors.InsufficientPrivilege):
                await runtime.execute(
                    psycopg.sql.SQL("SELECT * FROM public.{} LIMIT 0").format(
                        psycopg.sql.Identifier(table)
                    )
                )
    async with await psycopg.AsyncConnection.connect(urls["identity"], autocommit=True) as identity:
        for table in tables:
            await identity.execute(
                psycopg.sql.SQL("SELECT * FROM public.{} LIMIT 0").format(
                    psycopg.sql.Identifier(table)
                )
            )
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            await identity.execute("SELECT * FROM spunto.note LIMIT 0")
    async with await psycopg.AsyncConnection.connect(urls["admin"]) as admin:
        rows = await (
            await admin.execute(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace, "
                "LATERAL aclexplode(c.relacl) a WHERE n.nspname='public' "
                "AND c.relname=ANY(%s) AND a.grantee=0",
                (list(tables),),
            )
        ).fetchall()
        assert rows == []
