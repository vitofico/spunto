"""A cosmetic owner name stays private and never changes authentication identity."""

import json

import pytest
from conftest import PUBLIC_URL, run_server

from spunto.auth import tokens

pytestmark = pytest.mark.anyio
ORIGIN = {"Origin": PUBLIC_URL}
PASSWORD = "correct horse battery"


async def setup(server):
    assert (
        await server.client.post(
            "/api/v1/setup", json={"code": server.services.setup_code, "password": PASSWORD}
        )
    ).status_code == 204


async def identity(server):
    async with server.services.store.read() as conn:
        owner = [dict(row) for row in await conn.execute_fetchall("SELECT * FROM owner")]
        credentials = [
            {key: row[key] for key in row.keys() if key != "last_used_at"}
            for row in await conn.execute_fetchall("SELECT * FROM credential ORDER BY id")
        ]
    return owner, credentials, (await server.client.get("/api/v1/auth/session")).json()


async def test_profile_trim_clear_restart_and_identity_are_independent(env):
    async with run_server() as server:
        await setup(server)
        before = await identity(server)
        initial = (await server.client.get("/api/v1/settings")).json()
        assert initial["owner_display_name"] == ""
        name = "Élodie 李 <owner>"
        saved = await server.client.put(
            "/api/v1/settings", json={"owner_display_name": f"  {name}  "}, headers=ORIGIN
        )
        assert saved.status_code == 200
        assert saved.json() == {**initial, "owner_display_name": name}
        assert await identity(server) == before
        async with server.services.store.read() as conn:
            row = await conn.execute_fetchall(
                "SELECT value FROM setting WHERE key='owner_display_name'"
            )
            assert json.loads(row[0]["value"]) == name
        changed = await server.client.put(
            "/api/v1/settings", json={"theme": "salvia"}, headers=ORIGIN
        )
        assert changed.json()["owner_display_name"] == name
        assert await identity(server) == before

    async with run_server() as server:
        assert (
            await server.client.post("/api/v1/auth/login", json={"password": PASSWORD})
        ).status_code == 204
        assert (await server.client.get("/api/v1/settings")).json()["owner_display_name"] == name
        before_clear = await identity(server)
        cleared = await server.client.put(
            "/api/v1/settings", json={"owner_display_name": " \t "}, headers=ORIGIN
        )
        assert cleared.status_code == 200
        assert cleared.json()["owner_display_name"] == ""
        assert cleared.json()["theme"] == "salvia"
        assert await identity(server) == before_clear


async def test_profile_validation_is_bounded_plain_text_and_atomic(server):
    await setup(server)
    name = "李" * 80
    assert (
        await server.client.put(
            "/api/v1/settings", json={"owner_display_name": name}, headers=ORIGIN
        )
    ).status_code == 200
    for invalid in (
        "a" * 81,
        "a\nb",
        "a\rb",
        "a\tb",
        "a\x00b",
        "a\u2028b",
        "a\u2029b",
        "a\ud800b",
        1,
        [],
        {},
        None,
    ):
        response = await server.client.put(
            "/api/v1/settings",
            content=json.dumps({"owner_display_name": invalid, "theme": "salvia"}),
            headers={**ORIGIN, "Content-Type": "application/json"},
        )
        assert response.status_code == 422
        assert response.json()["code"] == "validation_failed"
        settings = (await server.client.get("/api/v1/settings")).json()
        assert settings["owner_display_name"] == name
        assert settings["theme"] == "carta"


async def test_profile_is_owner_session_only_and_not_an_authentication_name(server):
    assert (await server.client.get("/api/v1/settings")).status_code == 401
    await setup(server)
    name = "Private fixture owner"
    for headers in ({}, {"Origin": "https://untrusted.example.test"}):
        denied = await server.client.put(
            "/api/v1/settings", json={"owner_display_name": name}, headers=headers
        )
        assert denied.status_code == 403
        assert denied.json()["code"] == "origin_mismatch"
    assert (
        await server.client.put(
            "/api/v1/settings", json={"owner_display_name": name}, headers=ORIGIN
        )
    ).status_code == 200
    session = (await server.client.get("/api/v1/auth/session")).json()
    assert "owner_display_name" not in session
    assert name not in (await server.client.get("/api/v1/meta")).text
    assert name not in (await server.client.get("/api/v1/plugins/manifest")).text
    issued = await server.client.post(
        "/api/v1/auth/devices", json={"password": PASSWORD, "name": "Profile fixture device"}
    )
    assert issued.status_code == 200
    _, pat = await tokens.create(
        server.services.store, name="Profile fixture", client="script", scope="write"
    )
    _, status = await tokens.create(
        server.services.store, name="Status fixture", client="monitor", scope="status"
    )
    for token in (issued.json()["token"], pat, status):
        headers = {"Authorization": "Bearer " + token}
        assert (await server.client.get("/api/v1/settings", headers=headers)).status_code == 403
        assert (
            await server.client.put(
                "/api/v1/settings", json={"owner_display_name": "Changed"}, headers=headers
            )
        ).status_code == 403
    assert (await server.client.post("/api/v1/auth/logout", headers=ORIGIN)).status_code == 204
    assert (await server.client.get("/api/v1/settings")).status_code == 401
    assert name not in (await server.client.get("/api/v1/meta")).text
    assert (
        await server.client.post("/api/v1/auth/login", json={"password": name})
    ).status_code == 401
    assert (
        await server.client.post("/api/v1/auth/login", json={"password": PASSWORD})
    ).status_code == 204
    assert (await server.client.get("/api/v1/auth/session")).json()["owner_id"] == session[
        "owner_id"
    ]
    assert (await server.client.get("/api/v1/settings")).json()["owner_display_name"] == name


async def test_profile_keeps_environment_managed_password(env):
    env(SPUNTO_OWNER_PASSWORD=PASSWORD)
    async with run_server() as server:
        assert (
            await server.client.post("/api/v1/auth/login", json={"password": PASSWORD})
        ).status_code == 204
        before = await identity(server)
        assert before[2]["password_managed"] is True
        assert (
            await server.client.put(
                "/api/v1/settings", json={"owner_display_name": "Fixture"}, headers=ORIGIN
            )
        ).status_code == 200
        assert await identity(server) == before
