"""Stats stay within hosted workspace RLS and existing plugin command authority."""

import pytest
from test_hosted import headers
from test_hosted import hosted as hosted_fixture

from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.host import Ctx

hosted = hosted_fixture
pytestmark = pytest.mark.anyio
BASE = "/api/v1/plugins/library-stats"


async def enable(client, user, workspace=None):
    response = await client.post(
        BASE + "/enable", headers=headers(user, workspace), json={"permissions": ["notes.read"]}
    )
    assert response.status_code == 200, response.text


async def test_library_stats_isolate_workspaces_and_note_grants(hosted):
    client, _, users, _ = hosted
    alice, bob, _ = users
    await enable(client, alice)
    await enable(client, bob)
    source = (
        await client.post(
            "/api/v1/notes", headers=headers(alice), json={"body": "Alice private", "private": True}
        )
    ).json()
    for i in range(2):
        assert (
            await client.post(
                "/api/v1/notes", headers=headers(bob), json={"body": f"Bob source {i}"}
            )
        ).status_code == 201
    shared = await client.post(
        f"/api/v1/notes/{source['id']}/shares",
        headers=headers(alice),
        json={"user_id": bob["account"]["id"], "role": "viewer"},
    )
    assert shared.status_code == 200, shared.text
    for user, count in ((alice, 1), (bob, 2)):
        result = await client.post(BASE + "/commands/summary", headers=headers(user), json={})
        assert result.status_code == 200, result.text
        assert f"Saved notes: {count} " in result.text
        assert "Alice private" not in result.text and "Bob source" not in result.text
    assert (
        await client.post(
            BASE + "/commands/summary", headers=headers(bob, alice["workspace"]["id"]), json={}
        )
    ).status_code == 404


async def test_library_stats_preserve_private_external_and_membership_fences(hosted):
    client, manager, users, _ = hosted
    alice, bob, _ = users
    await enable(client, alice)
    services = await manager.services(
        {**alice["workspace"], "role": "owner"}, alice["account"]["id"]
    )
    owner = Principal("stats-owner", "session", "Owner", "full", private_ok=True)
    notes = Notes(services.store, owner)
    await notes.create("Ordinary source")
    await notes.create("Private source", private=True)
    external = await notes.create("External source")
    async with services.store.write() as conn:
        await conn.execute("UPDATE note SET origin='external' WHERE id=?", (external["id"],))
    restricted = Principal("stats-reader", "session", "Owner", "full", external_ok=False)
    counts = await Ctx(services.host, "library-stats", principal=restricted).notes.stats(
        machine=True
    )
    assert counts["saved"] == 1 and counts["active"] == 1
    counts = await Ctx(services.host, "library-stats", principal=owner).notes.stats(machine=True)
    assert counts["saved"] == 3

    team = await client.post(
        "/api/v1/workspaces", headers=headers(alice), json={"name": "Synthetic stats space"}
    )
    workspace = team.json()["id"]
    await enable(client, alice, workspace)
    for role in ("editor", "viewer"):
        response = await client.post(
            f"/api/v1/workspaces/{workspace}/members",
            headers=headers(alice),
            json={"user_id": bob["account"]["id"], "role": role},
        )
        assert response.status_code == 200, response.text
        assert (
            await client.post(BASE + "/commands/summary", headers=headers(bob, workspace), json={})
        ).status_code == 403
    await client.delete(
        f"/api/v1/workspaces/{workspace}/members/{bob['account']['id']}", headers=headers(alice)
    )
    assert (
        await client.post(BASE + "/commands/summary", headers=headers(bob, workspace), json={})
    ).status_code == 404
