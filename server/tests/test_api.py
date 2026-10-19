import asyncio
import logging
from contextlib import closing

import pytest
from conftest import PUBLIC_URL, run_server

from spunto.auth import owner, passwords
from spunto.auth.backoff import Backoff
from spunto.auth.passwords import hash_now
from spunto.store.db import DB_NAME, Store, connect

pytestmark = pytest.mark.anyio

PASSWORD = "correct horse battery"
ORIGIN = {"Origin": PUBLIC_URL}


async def finish_setup(server, password=PASSWORD):
    code = server.services.setup_code
    return await server.client.post("/api/v1/setup", json={"code": code, "password": password})


async def test_health_and_readiness(server):
    assert (await server.client.get("/healthz")).json() == {"status": "ok"}
    assert (await server.client.get("/readyz")).json() == {"status": "ready"}


async def test_readiness_fails_when_the_database_cannot_answer(server):
    await server.services.store.close()
    response = await server.client.get("/readyz")
    assert response.status_code == 503


async def test_probes_answer_on_any_host(server):
    for path in ("/healthz", "/readyz"):
        response = await server.client.get(path, headers={"Host": "10.1.2.3:8080"})
        assert response.status_code == 200


async def test_a_wrong_host_gets_a_page_naming_the_public_url(server):
    page = await server.client.get("/", headers={"Host": "192.168.1.10:8080"})
    assert page.status_code == 421
    assert "This server lives at" in page.text and f'href="{PUBLIC_URL}"' in page.text

    api = await server.client.post(
        "/api/v1/auth/login", json={"password": PASSWORD}, headers={"Host": "localhost:8080"}
    )
    assert api.status_code == 421
    assert api.headers["content-type"] == "application/problem+json"
    assert api.json()["code"] == "wrong_host"
    assert api.json()["detail"] == f"This server lives at {PUBLIC_URL}."


async def test_forwarded_host_counts_only_from_a_trusted_proxy(env):
    env(SPUNTO_TRUSTED_PROXIES="10.42.0.0/16")
    forwarded = {"Host": "spunto.internal:8080", "X-Forwarded-Host": "spunto.test:8080"}
    async with run_server(client_address="10.42.0.9") as server:
        assert (await server.client.get("/api/v1/meta", headers=forwarded)).status_code == 200
    async with run_server(client_address="198.51.100.4") as server:
        assert (await server.client.get("/api/v1/meta", headers=forwarded)).status_code == 421


async def test_meta_says_setup_is_pending_and_the_link_unencrypted(server):
    meta = (await server.client.get("/api/v1/meta")).json()
    assert meta["setup_pending"] is True
    assert meta["unencrypted"] is True
    assert meta["public_url"] == PUBLIC_URL
    assert len(meta["sync_epoch"]) == 8


async def test_setup_code_is_logged_and_exchanged_once_for_a_session(env, caplog):
    with caplog.at_level(logging.WARNING):
        async with run_server() as server:
            code = server.services.setup_code
            assert code in caplog.text

            wrong = await server.client.post(
                "/api/v1/setup", json={"code": "AAAA-" + code[5:], "password": PASSWORD}
            )
            assert (wrong.status_code, wrong.json()["code"]) == (401, "wrong_setup_code")

            done = await finish_setup(server)
            assert done.status_code == 204
            cookie = done.headers["set-cookie"]
            assert cookie.startswith("session=spunto_session_")
            assert "HttpOnly" in cookie and "SameSite=lax" in cookie and "Path=/" in cookie
            assert "Secure" not in cookie  # plain HTTP on a LAN (D33)
            assert (await server.client.get("/api/v1/credentials")).status_code == 200

            again = await server.client.post(
                "/api/v1/setup", json={"code": code, "password": "another password"}
            )
            assert (again.status_code, again.json()["code"]) == (409, "setup_done")
            assert (await server.client.get("/api/v1/meta")).json()["setup_pending"] is False


async def test_setup_code_changes_at_every_start_until_used(env, caplog):
    async with run_server() as server:
        first = server.services.setup_code
    async with run_server() as server:
        second = server.services.setup_code
        assert second != first
        assert (await finish_setup(server)).status_code == 204
    caplog.clear()
    with caplog.at_level(logging.WARNING):
        async with run_server() as server:
            assert server.services.setup_code is None
    assert "setup code" not in caplog.text


async def test_a_weak_password_does_not_spend_the_code(server):
    code = server.services.setup_code
    weak = await server.client.post("/api/v1/setup", json={"code": code, "password": "short"})
    assert (weak.status_code, weak.json()["code"]) == (422, "validation_failed")
    assert server.services.setup_code == code


async def test_https_uses_a_host_only_secure_cookie(env):
    env(SPUNTO_PUBLIC_URL="https://spunto.test")
    async with run_server() as server:
        cookie = (await finish_setup(server)).headers["set-cookie"]
        assert cookie.startswith("__Host-session=spunto_session_")
        assert "Secure" in cookie and "Path=/" in cookie and "Domain" not in cookie
        assert (await server.client.get("/api/v1/meta")).json()["unencrypted"] is False
        assert (await server.client.get("/api/v1/credentials")).status_code == 200


async def test_login_logout_and_the_origin_rule(server):
    await finish_setup(server)
    server.client.cookies.clear()
    assert (await server.client.get("/api/v1/credentials")).status_code == 401

    wrong = await server.client.post("/api/v1/auth/login", json={"password": "not it at all"})
    assert (wrong.status_code, wrong.json()["code"]) == (401, "wrong_password")
    foreign = await server.client.post(
        "/api/v1/auth/login", json={"password": PASSWORD}, headers={"Origin": "https://evil.test"}
    )
    assert (foreign.status_code, foreign.json()["code"]) == (403, "origin_mismatch")
    assert (await server.client.post("/api/v1/auth/login", json={"password": PASSWORD})).is_success

    listed = (await server.client.get("/api/v1/credentials")).json()
    assert [c["current"] for c in listed if c["kind"] == "session"].count(True) == 1

    # A cookie-authenticated write must say where it comes from.
    assert (await server.client.post("/api/v1/auth/logout")).status_code == 403
    no = await server.client.post("/api/v1/auth/logout", headers={"Origin": "http://spunto.test"})
    assert no.status_code == 403
    out = await server.client.post("/api/v1/auth/logout", headers=ORIGIN)
    assert out.status_code == 204
    assert (await server.client.get("/api/v1/credentials")).status_code == 401


async def test_login_before_setup_is_refused(server):
    response = await server.client.post("/api/v1/auth/login", json={"password": PASSWORD})
    assert (response.status_code, response.json()["code"]) == (409, "setup_required")


async def test_backoff_covers_setup_and_login_together(server):
    for _ in range(5):
        wrong = await server.client.post(
            "/api/v1/setup", json={"code": "AAAA", "password": PASSWORD}
        )
        assert wrong.status_code == 401
    blocked = await finish_setup(server)
    assert (blocked.status_code, blocked.json()["code"]) == (429, "rate_limited")
    assert blocked.headers["retry-after"] == "1"

    server.services.backoff = Backoff()  # time passes
    assert (await finish_setup(server)).status_code == 204
    for _ in range(5):
        response = await server.client.post("/api/v1/auth/login", json={"password": "wrong!!!!"})
        assert response.status_code == 401
    response = await server.client.post("/api/v1/auth/login", json={"password": PASSWORD})
    assert response.status_code == 429


async def test_parallel_guesses_cannot_outrun_the_backoff(server):
    await finish_setup(server)
    guesses = [
        server.client.post("/api/v1/auth/login", json={"password": f"guess number {i}"})
        for i in range(20)
    ]
    statuses = sorted(response.status_code for response in await asyncio.gather(*guesses))
    assert statuses == [401] * 5 + [429] * 15


async def test_password_recovery_cannot_leave_a_session_from_the_old_password(server, monkeypatch):
    await finish_setup(server)
    server.client.cookies.clear()
    verified = asyncio.Event()
    finish_verification = asyncio.Event()
    verify = passwords.verify_password

    async def paused_verification(stored, supplied):
        result = await verify(stored, supplied)
        verified.set()
        await finish_verification.wait()
        return result

    monkeypatch.setattr(passwords, "verify_password", paused_verification)
    pending_login = asyncio.create_task(
        server.client.post("/api/v1/auth/login", json={"password": PASSWORD})
    )
    await verified.wait()
    # The recovery command opens its own writer beside the running server.
    recovery_store = await Store.open(server.services.settings.data_dir / DB_NAME, readers=1)
    try:
        await owner.set_password(recovery_store, hash_now("replacement password"), "ui")
    finally:
        await recovery_store.close()
    finish_verification.set()
    response = await pending_login
    assert (response.status_code, response.json()["code"]) == (401, "wrong_password")
    assert "set-cookie" not in response.headers
    assert (await server.client.get("/api/v1/credentials")).status_code == 401
    async with server.services.store.read() as conn:
        assert not await conn.execute_fetchall(
            "SELECT id FROM credential WHERE revoked_at IS NULL AND kind!='plugin'"
        )
    assert (
        await server.client.post("/api/v1/auth/login", json={"password": "replacement password"})
    ).status_code == 204


async def test_password_from_the_environment_wins_at_every_start(env, data_dir):
    env(SPUNTO_OWNER_PASSWORD=PASSWORD)
    async with run_server() as server:
        assert server.services.setup_code is None
        refused = await server.client.post(
            "/api/v1/setup", json={"code": "AAAA", "password": PASSWORD}
        )
        assert (refused.status_code, refused.json()["code"]) == (409, "setup_done")
        assert (
            await server.client.post("/api/v1/auth/login", json={"password": PASSWORD})
        ).is_success

    env(SPUNTO_OWNER_PASSWORD="", SPUNTO_OWNER_PASSWORD_HASH=hash_now("a new password"))
    async with run_server() as server:
        old = await server.client.post("/api/v1/auth/login", json={"password": PASSWORD})
        assert old.status_code == 401
        new = await server.client.post("/api/v1/auth/login", json={"password": "a new password"})
        assert new.is_success

    with closing(connect(data_dir / DB_NAME)) as conn:
        sessions = conn.execute(
            "SELECT revoked_at FROM credential WHERE kind='session' ORDER BY created_at"
        ).fetchall()
        [(source,)] = conn.execute("SELECT password_source FROM owner").fetchall()
    assert sessions[0][0] is not None  # the first session ended when the password changed
    assert sessions[1][0] is None
    assert source == "env"


async def test_unknown_paths_are_problems(server):
    response = await server.client.get("/api/v1/nothing-here")
    assert response.status_code == 404
    assert response.json()["code"] == "not_found"
