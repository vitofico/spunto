"""Collections keep intentional selections and preserve the legacy Context Packs contract."""

import hashlib
import json
import uuid

import pytest
from conftest import PUBLIC_URL

from spunto.api.errors import Problem
from spunto.auth import tokens
from spunto.host import Ctx
from spunto.plugins.context_packs import ask_context

pytestmark = pytest.mark.anyio
ORIGIN = {"Origin": PUBLIC_URL}


@pytest.fixture
async def collections_client(server):
    client = server.client
    password = "correct horse battery"
    setup = await client.post(
        "/api/v1/setup", json={"code": server.services.setup_code, "password": password}
    )
    assert setup.status_code == 204
    login = await client.post("/api/v1/auth/login", headers=ORIGIN, json={"password": password})
    assert login.status_code == 204
    host = server.services.host
    await host.enable("context-packs", permissions=host.manifests["context-packs"]["permissions"])
    return client, host


async def request(client, **values):
    return await client.post("/api/v1/collections", headers=ORIGIN, json=values)


async def synthetic_note(client, body, *, private=False):
    response = await client.post(
        "/api/v1/notes", headers=ORIGIN, json={"body": body, "private": private}
    )
    assert response.status_code == 201, response.text
    return response.json()


async def test_manual_collection_reorder_pin_and_legacy_export(collections_client):
    client, _ = collections_client
    first = await synthetic_note(client, "First seed")
    second = await synthetic_note(client, "Second seed")
    collection_id = str(uuid.uuid4())
    args = {
        "action": "save",
        "id": collection_id,
        "title": "Synthetic archive of plan notes",
        "intro": "Introduction to the plan",
        "sources": [{"id": item["id"], "version": item["version"]} for item in (first, second)],
    }
    initial = await request(client, **args)
    assert initial.status_code == 200, initial.text
    assert (await request(client, **args)).json() == initial.json()
    changed = await request(
        client,
        action="save",
        id=collection_id,
        title="Renamed collection",
        revision=1,
        sources=[{"id": second["id"], "pinned": True}, {"id": first["id"]}],
    )
    assert changed.status_code == 200, changed.text
    assert [(item["id"], item["pinned"]) for item in changed.json()["sources"]] == [
        (second["id"], True),
        (first["id"], False),
    ]
    assert (await request(client, query="renamed")).json()["collections"][0]["id"] == collection_id
    assert (await request(client, query="archive")).json()["collections"] == []
    stale = await request(client, action="save", id=collection_id, revision=1, title="Stale rename")
    assert stale.status_code == 409
    exported = await client.post(
        "/api/v1/plugins/context-packs/commands/packs",
        headers=ORIGIN,
        json={"pack_id": collection_id, "export": True},
    )
    assert exported.json()["items"][0]["text"] == changed.json()["markdown"]
    removed = await request(client, action="delete", id=collection_id, revision=2)
    assert removed.json() == {"deleted": True}
    assert (await client.get(f"/api/v1/notes/{second['id']}")).status_code == 200


async def test_default_private_collection_ask_is_scoped_and_never_includes_prose(
    collections_client,
):
    client, host = collections_client
    public = await synthetic_note(client, "An eligible fact")
    private = await synthetic_note(client, "Hidden detail", private=True)
    key = str(uuid.uuid4())
    result = await request(
        client,
        action="save",
        id=key,
        title="Private title",
        intro="Private intro",
        sources=[{"id": item["id"], "version": item["version"]} for item in (public, private)],
    )
    assert result.status_code == 200, result.text
    assert result.json()["private"] and result.json()["ask_ready"]
    owner = tokens.Principal("collection-owner", "session", "Synthetic collection owner", "full")
    ctx = Ctx(host, "context-packs", principal=owner, operation="command:test")
    result = await ask_context(ctx, key)
    assert result["source_ids"] == [public["id"]]
    assert result["brief"] == ""
    await client.post(f"/api/v1/notes/{public['id']}/flags", headers=ORIGIN, json={"private": True})
    with pytest.raises(Problem):
        await ask_context(ctx, key)


async def test_scoped_tokens_cannot_create_or_delete_collections(collections_client):
    client, host = collections_client
    _, token = await tokens.create(host.store, name="Synthetic reader", client="test", scope="read")
    key = str(uuid.uuid4())
    assert (
        await request(client, action="save", id=key, title="Owner collection")
    ).status_code == 200
    for action, values in [
        ("save", {"title": "Unauthorized edit", "revision": 1}),
        ("delete", {"revision": 1}),
    ]:
        response = await client.post(
            "/api/v1/collections",
            headers={**ORIGIN, "Authorization": "Bearer " + token},
            json={"action": action, "id": key, **values},
        )
        assert response.status_code == 403
    assert (await request(client, action="read", id=key)).json()["title"] == "Owner collection"


@pytest.mark.parametrize("legacy", [False, True])
async def test_acknowledging_note_version_keeps_sourced_introduction_stale(
    collections_client, legacy
):
    client, _ = collections_client
    note = await synthetic_note(client, "Exhibition delivery confirmed for Friday")
    collection_id = str(uuid.uuid4())
    fingerprint = hashlib.sha256(
        json.dumps(
            [note["id"], note["body"], []], ensure_ascii=False, separators=(",", ":")
        ).encode()
    ).hexdigest()
    saved = await request(
        client,
        action="save",
        id=collection_id,
        title="Exhibition plan",
        intro="The delivery is confirmed for Friday.",
        intro_citations={note["id"]: fingerprint},
        sources=[{"id": note["id"], "version": note["version"]}],
    )
    assert saved.status_code == 200, saved.text
    assert saved.json()["sources"][0]["status"] == "current"
    changed = await client.patch(
        f"/api/v1/notes/{note['id']}",
        headers={**ORIGIN, "If-Match": '"1"'},
        json={"body": "The exhibition delivery is now on Monday"},
    )
    assert changed.status_code == 200, changed.text
    if legacy:
        reviewed = await client.post(
            "/api/v1/plugins/context-packs/commands/sources",
            headers=ORIGIN,
            json={"pack_id": collection_id, "expected_revision": 1, "refresh": True},
        )
    else:
        reviewed = await request(
            client,
            action="save",
            id=collection_id,
            revision=1,
            sources=[{"id": note["id"], "version": changed.json()["version"]}],
        )
    assert reviewed.status_code == 200, reviewed.text
    preview = (await request(client, action="read", id=collection_id)).json()
    assert preview["intro_citations"] == {note["id"]: fingerprint}
    assert preview["sources"][0]["status"] == "changed"
    assert "Source revisions need review" in preview["markdown"]
    blocked = await client.post(
        "/api/v1/plugins/context-packs/commands/edit",
        headers=ORIGIN,
        json={"pack_id": collection_id, "expected_revision": 2, "action": "save-note"},
    )
    assert blocked.status_code == 409


async def test_collection_updates_uses_fresh_eligible_notes_and_rechecks_provider_receipt(
    collections_client, server
):
    import asyncio
    import re

    import httpx
    from fastapi import FastAPI, Request

    from spunto.ai.gateway import Gateway
    from spunto.config import Profile
    from spunto.core.plugin_notes import PluginNotes
    from spunto.host import Host

    client, original = collections_client
    provider = FastAPI()
    prompts = []
    invalidate = None

    @provider.post("/v1/chat/completions")
    async def chat(request: Request):
        body = await request.json()
        prompt = body["messages"][-1]["content"]
        prompts.append(prompt)
        ids = re.findall(r'<note id="([^"]+)"', prompt)
        if invalidate:
            result = await client.post(
                f"/api/v1/notes/{invalidate}/flags", headers=ORIGIN, json={"private": True}
            )
            assert result.status_code == 200
        content = json.dumps(
            {
                "title": "Exhibition update",
                "summary": "The delivery team confirmed the panels.",
                "matches": ids,
                "citations": ids,
            }
        )
        return {"choices": [{"finish_reason": "stop", "message": {"content": content}}]}

    profile = Profile(
        name="synthetic",
        base_url="http://provider.synthetic/v1",
        chat_model="synthetic-chat",
    )
    settings = server.services.settings.model_copy(
        update={
            "ai_profiles": {"synthetic": profile},
            "ai_chat_profile": "synthetic",
        }
    )
    gateway = Gateway(server.services.store, settings, transport=httpx.ASGITransport(provider))
    host = Host(server.services.store, settings, gateway=gateway, notes_factory=PluginNotes)
    await host.discover()
    await host.enable("note-assist", permissions=host.manifests["note-assist"]["permissions"])
    server.services.host = host
    try:
        old = await synthetic_note(client, "Exhibition plan\nOlder rejected proposal.")
        private = await synthetic_note(client, "Exhibition plan\nPRIVATE FIXTURE", private=True)
        key = str(uuid.uuid4())
        assert (
            await request(client, action="save", id=key, title="Exhibition", intro="PRIVATE BRIEF")
        ).status_code == 200
        await asyncio.sleep(0.01)
        latest = await synthetic_note(client, "Exhibition delivery\nThe panels arrive Friday.")
        args = {
            "goal": "Find exhibition notes about our delivery.",
            "mode": "updates",
            "selected": [private["id"]],
            "collection_id": key,
        }
        update = await client.post("/api/v1/note-assist/collections", headers=ORIGIN, json=args)
        assert update.status_code == 200, update.text
        assert [item["id"] for item in update.json()["sources"]] == [latest["id"]]
        assert "The panels arrive Friday." in update.json()["sources"][0]["passage"]
        assert old["id"] not in prompts[-1]
        assert "PRIVATE FIXTURE" not in prompts[-1] and "PRIVATE BRIEF" not in prompts[-1]
        proposal = update.json()
        candidate = proposal["sources"][0]
        rejected = await request(
            client,
            action="save",
            id=key,
            revision=1,
            intro=proposal["intro"],
            intro_citations=proposal["fingerprints"],
        )
        assert rejected.status_code == 422
        stale_proof = await request(
            client,
            action="save",
            id=key,
            revision=1,
            intro=proposal["intro"],
            sources=[{"id": latest["id"], "version": candidate["version"]}],
            intro_citations={latest["id"]: "a" * 64},
        )
        assert stale_proof.status_code == 409
        invalidate = latest["id"]
        stale = await client.post("/api/v1/note-assist/collections", headers=ORIGIN, json=args)
        assert stale.status_code == 409
        rejected = await request(
            client,
            action="save",
            id=key,
            revision=1,
            intro=proposal["intro"],
            sources=[{"id": latest["id"], "version": candidate["version"]}],
            intro_citations=proposal["fingerprints"],
        )
        assert rejected.status_code == 409
        assert (await request(client, action="read", id=key)).json()["sources"] == []
    finally:
        server.services.host = original
        await host.close()
        await gateway.close()
