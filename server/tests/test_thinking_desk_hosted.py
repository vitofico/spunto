"""Thinking desk fences against the real hosted PostgreSQL transports and store."""

import asyncio
import uuid

import pytest
from test_hosted import headers
from test_hosted import hosted as hosted_fixture

from spunto.api.errors import Problem
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.host import Ctx
from spunto.plugins import thinking_desk as desk

hosted = hosted_fixture

pytestmark = pytest.mark.anyio
BASE = "/api/v1/plugins/thinking-desk/commands/"


async def enable(client, user, workspace=None):
    catalog = (await client.get("/api/v1/plugins", headers=headers(user, workspace))).json()
    declaration = next(p for p in catalog["plugins"] if p["id"] == "thinking-desk")
    response = await client.post(
        "/api/v1/plugins/thinking-desk/enable",
        headers=headers(user, workspace),
        json={"permissions": declaration["permissions"]},
    )
    assert response.status_code == 200, response.text


async def create(client, user, workspace=None, note_id=None):
    desk_id = str(uuid.uuid4())
    response = await client.post(
        BASE + "edit",
        headers=headers(user, workspace),
        json={
            "title": "Synthetic hosted thinking",
            "request_id": desk_id,
            **({"note_id": note_id} if note_id else {}),
        },
    )
    assert response.status_code == 200, response.text
    return desk_id


async def test_thinking_desk_cross_space_and_membership_revocation(hosted):
    client, _, users, _ = hosted
    alice, bob, _ = users
    await enable(client, alice)
    await enable(client, bob)
    source = (
        await client.post(
            "/api/v1/notes",
            headers=headers(alice),
            json={"body": "Alice-only source", "private": True},
        )
    ).json()
    desk_id = await create(client, alice, note_id=source["id"])
    response = await client.post(BASE + "desk", headers=headers(bob), json={"desk_id": desk_id})
    assert response.status_code == 404 and "Alice-only" not in response.text
    response = await client.post(
        BASE + "edit",
        headers=headers(bob),
        json={
            "title": "Cross-space attempt",
            "note_id": source["id"],
        },
    )
    assert response.status_code == 422 and "Alice-only" not in response.text
    created = await client.post(
        "/api/v1/workspaces", headers=headers(alice), json={"name": "Synthetic thinking team"}
    )
    space = created.json()["id"]
    response = await client.post(
        f"/api/v1/workspaces/{space}/members",
        headers=headers(alice),
        json={"user_id": bob["account"]["id"], "role": "editor"},
    )
    assert response.status_code == 200
    await enable(client, alice, space)
    team_desk = await create(client, alice, space)
    assert (
        await client.post(BASE + "desk", headers=headers(bob, space), json={"desk_id": team_desk})
    ).status_code == 403
    await client.delete(
        f"/api/v1/workspaces/{space}/members/{bob['account']['id']}", headers=headers(alice)
    )
    for command, args in [
        ("desk", {"desk_id": team_desk}),
        ("edit", {"desk_id": team_desk, "expected_revision": 1, "action": "snapshot"}),
    ]:
        response = await client.post(BASE + command, headers=headers(bob, space), json=args)
        assert response.status_code == 404


async def test_thinking_desk_private_and_external_source_fences(hosted):
    client, manager, users, _ = hosted
    alice = users[0]
    await enable(client, alice)
    runtime = await manager.services(
        {**alice["workspace"], "role": "owner"}, alice["account"]["id"]
    )
    full = Principal(alice["account"]["id"], "session", "Synthetic owner", "full", private_ok=True)
    notes = Notes(runtime.store, full)
    private = await notes.create("Private source words", private=True)
    external = await notes.create("External source words")
    # Synthetic fixture classification uses the actual workspace SQL path, not a mocked reader.
    async with runtime.store.write() as conn:
        await conn.execute("UPDATE note SET origin='external' WHERE id=?", (external["id"],))
    for source, flags in [(private, {"private_ok": False}), (external, {"external_ok": False})]:
        principal = Principal(
            alice["account"]["id"],
            "session",
            "Restricted owner",
            "full",
            private_ok=flags.get("private_ok", True),
            external_ok=flags.get("external_ok", True),
        )
        restricted = Ctx(
            runtime.host, "thinking-desk", principal=principal, operation="command:test"
        )
        assert await restricted.notes.get(source["id"]) is None
        with pytest.raises(ValueError, match="unavailable"):
            await desk.edit(restricted, title="Restricted selection", note_id=source["id"])
        desk_id = await create(client, alice, note_id=source["id"])
        rendered = await desk.desk(restricted, desk_id)
        assert source["body"] not in str(rendered)
        assert rendered["items"][1]["ids"] == []
        with pytest.raises(Problem):
            await desk.edit(restricted, desk_id, 1, action="snapshot")


@pytest.mark.parametrize("change", ["edit", "revoke"])
async def test_thinking_desk_snapshot_rechecks_sources_inside_creation_transaction(
    hosted, monkeypatch, change
):
    client, manager, users, _ = hosted
    alice = users[0]
    await enable(client, alice)
    source = (
        await client.post(
            "/api/v1/notes",
            headers=headers(alice),
            json={"body": "Source before snapshot", "private": True},
        )
    ).json()
    desk_id = await create(client, alice, note_id=source["id"])
    reached, release = asyncio.Event(), asyncio.Event()
    original = PluginNotes.create

    async def paused_create(self, *args, **kwargs):
        if self.ctx.plugin_id == "thinking-desk":
            reached.set()
            await release.wait()
        return await original(self, *args, **kwargs)

    # Scheduling barrier only: the original production SQL guard and write still execute.
    monkeypatch.setattr(PluginNotes, "create", paused_create)
    pending = asyncio.create_task(
        client.post(
            BASE + "edit",
            headers=headers(alice),
            json={
                "desk_id": desk_id,
                "expected_revision": 1,
                "action": "snapshot",
            },
        )
    )
    try:
        await asyncio.wait_for(reached.wait(), 5)
        if change == "edit":
            response = await client.patch(
                "/api/v1/notes/" + source["id"],
                headers={**headers(alice), "If-Match": '"1"'},
                json={"body": "Source changed after preview"},
            )
        else:
            response = await client.post(
                "/api/v1/notes/" + source["id"] + "/flags",
                headers=headers(alice),
                json={"trashed": True},
            )
        assert response.status_code == 200, response.text
    finally:
        release.set()
    result = await pending
    assert result.status_code in {404, 409}, result.text
    runtime = await manager.services(
        {**alice["workspace"], "role": "owner"}, alice["account"]["id"]
    )
    async with runtime.store.read() as conn:
        count = await conn.execute_fetchall(
            "SELECT count(*) FROM note WHERE created_by='plugin:thinking-desk'"
        )
    assert count[0][0] == 0
