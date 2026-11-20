"""REST authentication, lifecycle visibility, revisions and plugin dispatch."""

import asyncio
from contextlib import asynccontextmanager
from pathlib import Path

import pytest
from conftest import PUBLIC_URL

import spunto.plugins as bundled
from spunto.ai.errors import AIError
from spunto.ai.vectors import VectorUnavailable
from spunto.api import plugins
from spunto.api.errors import Problem
from spunto.auth import tokens
from spunto.host import Plugin, read_manifest

pytestmark = pytest.mark.anyio
ORIGIN = {"Origin": PUBLIC_URL}


@pytest.mark.parametrize(
    ("failure", "status", "code", "guidance"),
    [
        (
            AIError("provider", "ai_provider_http_error", status=401),
            503,
            "ai_provider_http_error",
            "credentials",
        ),
        (
            AIError("provider", "ai_provider_http_error", status=404),
            503,
            "ai_provider_http_error",
            "not found",
        ),
        (AIError("validation", "ai_invalid_output"), 422, "ai_invalid_output", "invalid answer"),
        (
            AIError("unavailable", "ai_capability_unbound"),
            503,
            "ai_capability_unbound",
            "Choose a model",
        ),
        (VectorUnavailable(), 503, "vectors_unavailable", "still preparing"),
    ],
)
async def test_plugin_ai_failures_include_safe_actionable_guidance(failure, status, code, guidance):
    async def fail():
        raise failure

    with pytest.raises(Problem) as error:
        await plugins._call(fail())
    assert error.value.status == status
    assert error.value.code == code
    assert guidance in error.value.detail


@pytest.fixture
async def plugin_server(server):
    # Router ownership is separate from startup during parallel M2 implementation.
    app = server.client._transport.app
    if not any(getattr(route, "path", None) == "/api/v1/plugins" for route in app.routes):
        app.include_router(plugins.router)
    return server


async def setup(server):
    response = await server.client.post(
        "/api/v1/setup",
        json={"code": server.services.setup_code, "password": "correct horse battery"},
    )
    assert response.status_code == 204


async def enable(server, plugin_id):
    permissions = server.services.host.manifests[plugin_id]["permissions"]
    response = await server.client.post(
        f"/api/v1/plugins/{plugin_id}/enable", json={"permissions": permissions}, headers=ORIGIN
    )
    assert response.status_code == 200, response.text


async def test_manifest_auth_csrf_permissions_and_etag(plugin_server):
    server = plugin_server
    assert (await server.client.get("/api/v1/plugins")).status_code == 401
    await setup(server)
    catalog = (await server.client.get("/api/v1/plugins")).json()
    discovered = [read_manifest(p) for p in Path(bundled.__file__).parent.glob("*/plugin.toml")]
    assert {p["id"] for p in catalog["plugins"]} == {p["id"] for p in discovered}
    assert {p["id"] for p in catalog["plugins"] if p["has_settings"]} == {
        p["id"] for p in discovered if p.get("settings", {}).get("properties")
    }
    assert {
        "tasks",
        "agent-inbox",
        "resurface",
        "label-suggestions",
        "semantic-search",
        "related",
        "ask",
        "dictation",
        "quick-capture",
        "title-summary",
        "tidy",
        "duplicates",
        "label-gardener",
        "links",
        "digest",
        "develop",
        "translate",
    } <= {p["id"] for p in catalog["plugins"]}
    before = await server.client.get("/api/v1/plugins/manifest")
    assert {item["id"] for item in before.json()["plugins"]} == {"dictation", "quick-capture"}
    denied = await server.client.post(
        "/api/v1/plugins/tasks/enable", json={"permissions": ["notes.read", "annotate"]}
    )
    assert denied.status_code == 403
    assert denied.json()["code"] == "origin_mismatch"
    changed = await server.client.post(
        "/api/v1/plugins/tasks/enable", json={"permissions": []}, headers=ORIGIN
    )
    assert changed.status_code == 409
    assert changed.json()["code"] == "permissions_changed"
    assert changed.json()["permissions"] == ["notes.read", "annotate"]
    assert changed.headers["content-type"] == "application/problem+json"
    await enable(server, "tasks")
    manifest = await server.client.get("/api/v1/plugins/manifest")
    assert {p["id"] for p in manifest.json()["plugins"]} == {"tasks", "dictation", "quick-capture"}
    assert manifest.headers["etag"] != before.headers["etag"]
    cached = await server.client.get(
        "/api/v1/plugins/manifest", headers={"If-None-Match": manifest.headers["etag"]}
    )
    assert cached.status_code == 304
    assert (
        await server.client.post("/api/v1/plugins/tasks/disable", headers=ORIGIN)
    ).status_code == 200
    assert {
        item["id"]
        for item in (await server.client.get("/api/v1/plugins/manifest")).json()["plugins"]
    } == {"dictation", "quick-capture"}
    assert (await server.client.get("/api/v1/plugins/tasks/route")).status_code == 404


@pytest.mark.parametrize("plugin_id", ["context-packs", "decision-journal"])
async def test_catalog_revision_keeps_its_switch_snapshot(plugin_server, monkeypatch, plugin_id):
    server = plugin_server
    await setup(server)
    host, store = server.services.host, server.services.store
    initial_revision = host.plugins_rev
    catalog = host.catalog
    read = store.read
    paused = asyncio.Event()
    resume = asyncio.Event()
    captured = False

    def capture_catalog(**kwargs):
        nonlocal captured
        if asyncio.current_task() is request:
            captured = True
        return catalog(**kwargs)

    @asynccontextmanager
    async def pause_catalog_stats():
        if asyncio.current_task() is request and captured:
            paused.set()
            await resume.wait()
        async with read() as conn:
            yield conn

    monkeypatch.setattr(host, "catalog", capture_catalog)
    monkeypatch.setattr(store, "read", pause_catalog_stats)
    request = asyncio.create_task(server.client.get("/api/v1/plugins"))
    try:
        await asyncio.wait_for(paused.wait(), 5)
        await enable(server, plugin_id)
        manifest = (await server.client.get("/api/v1/plugins/manifest")).json()
        assert manifest["plugins_rev"] > initial_revision
        assert plugin_id in {row["id"] for row in manifest["plugins"]}
    finally:
        resume.set()
        old_catalog = (await request).json()

    old_row = next(row for row in old_catalog["plugins"] if row["id"] == plugin_id)
    assert old_row["switch"] == "off"
    assert old_catalog["plugins_rev"] == initial_revision
    updated = (await server.client.get("/api/v1/plugins")).json()
    assert updated["plugins_rev"] == manifest["plugins_rev"]
    assert next(row for row in updated["plugins"] if row["id"] == plugin_id)["switch"] == "on"


@pytest.mark.parametrize("path", ["/api/v1/plugins", "/api/v1/plugins/manifest"])
async def test_plugin_snapshot_waits_for_in_flight_switch(plugin_server, monkeypatch, path):
    server = plugin_server
    await setup(server)
    plugin_id = "decision-journal"
    await enable(server, plugin_id)
    host, store = server.services.host, server.services.store
    initial_revision = host.plugins_rev
    write = store.write
    paused = asyncio.Event()
    resume = asyncio.Event()
    snapshot_waiting = asyncio.Event()

    class ObservedLock(asyncio.Lock):
        async def acquire(self):
            if self.locked():
                snapshot_waiting.set()
            return await super().acquire()

    monkeypatch.setitem(host.lifecycle_locks, plugin_id, ObservedLock())

    @asynccontextmanager
    async def pause_switch_write():
        if asyncio.current_task() is toggle:
            paused.set()
            await resume.wait()
        async with write() as conn:
            yield conn

    monkeypatch.setattr(store, "write", pause_switch_write)
    toggle = asyncio.create_task(host.disable(plugin_id))
    request = None
    try:
        await asyncio.wait_for(paused.wait(), 5)
        assert host.states[plugin_id]["switch"] == "off"
        assert host.plugins_rev == initial_revision
        request = asyncio.create_task(server.client.get(path))
        await asyncio.wait_for(snapshot_waiting.wait(), 5)
        assert not request.done()
    finally:
        resume.set()
        await toggle
        snapshot = (await request).json() if request is not None else None

    assert snapshot["plugins_rev"] > initial_revision
    rows = {row["id"]: row for row in snapshot["plugins"]}
    if path.endswith("/manifest"):
        assert plugin_id not in rows
    else:
        assert rows[plugin_id]["switch"] == "off"


async def test_settings_secrets_and_management_pat_rejection(plugin_server, monkeypatch):
    server = plugin_server
    await setup(server)
    monkeypatch.setenv("SPUNTO_PLUGIN_EMAIL_IN_PASSWORD", "api-contract-sentinel")
    current = await server.client.get("/api/v1/plugins/email-in/settings")
    assert current.json()["secrets"]["password"]["set"] is True
    assert "api-contract-sentinel" not in current.text
    denied = await server.client.put(
        "/api/v1/plugins/email-in/settings",
        json={"password": "api-contract-sentinel"},
        headers=ORIGIN,
    )
    assert denied.status_code == 422
    response = await server.client.put(
        "/api/v1/plugins/email-in/settings",
        json={"url": "imaps://mail.synthetic.example:993"},
        headers=ORIGIN,
    )
    assert response.json()["values"]["url"] == "imaps://mail.synthetic.example:993"
    _, token = await tokens.create(
        server.services.store, name="Agent", client="script", scope="write"
    )
    bearer = {"Authorization": "Bearer " + token}
    for path in (
        "/api/v1/plugins",
        "/api/v1/plugins/manifest",
        "/api/v1/plugins/tasks/settings",
        "/api/v1/settings",
    ):
        assert (await server.client.get(path, headers=bearer)).status_code == 403
    assert (
        await server.client.post(
            "/api/v1/plugins/tasks/enable",
            json={"permissions": ["notes.read", "annotate"]},
            headers=bearer,
        )
    ).status_code == 403
    assert (
        await server.client.put("/api/v1/plugins/tasks/settings", json={}, headers=bearer)
    ).status_code == 403
    assert (
        await server.client.delete("/api/v1/plugins/tasks/data", headers=bearer)
    ).status_code == 403


async def test_device_switches_and_read_only_settings(plugin_server):
    server = plugin_server
    await setup(server)
    credential_id, pat = await tokens.create(
        server.services.store, name="Phone", client="script", scope="write"
    )
    async with server.services.store.write() as conn:
        await conn.execute(
            "UPDATE credential SET kind='device',scope='full' WHERE id=?", (credential_id,)
        )
    device = pat.replace("spunto_pat_", "spunto_device_")
    headers = {"Authorization": "Bearer " + device}
    assert (await server.client.get("/api/v1/plugins", headers=headers)).status_code == 200
    assert (
        await server.client.get("/api/v1/plugins/tasks/settings", headers=headers)
    ).status_code == 200
    assert (
        await server.client.post(
            "/api/v1/plugins/tasks/enable",
            json={"permissions": ["notes.read", "annotate"]},
            headers=headers,
        )
    ).status_code == 200
    assert (
        await server.client.post("/api/v1/plugins/tasks/disable", headers=headers)
    ).status_code == 200
    assert (
        await server.client.put("/api/v1/plugins/tasks/settings", json={}, headers=headers)
    ).status_code == 403


async def test_instance_preferences_order_and_hide_closed(plugin_server):
    server = plugin_server
    await setup(server)
    prior = (await server.client.get("/api/v1/settings")).json()
    order = list(reversed(prior["plugin_order"]))
    rev = server.services.host.plugins_rev
    result = await server.client.put(
        "/api/v1/settings",
        json={"plugin_order": order, "hidden_views": ["tasks/tasks"]},
        headers=ORIGIN,
    )
    assert result.status_code == 200
    assert result.json()["plugin_order"] == order
    assert result.json()["hidden_views"] == ["tasks/tasks"]
    assert server.services.host.plugins_rev > rev
    assert (
        await server.client.put(
            "/api/v1/settings", json={"plugin_order": ["tasks"]}, headers=ORIGIN
        )
    ).status_code == 422
    assert (
        await server.client.put("/api/v1/settings", json={"notification": True}, headers=ORIGIN)
    ).status_code == 422
    assert (
        await server.client.put(
            "/api/v1/settings", json={"hidden_mcp_tools": ["invented_tool"]}, headers=ORIGIN
        )
    ).status_code == 422


async def test_long_poll_wakes_on_plugin_switch(plugin_server):
    server = plugin_server
    await setup(server)
    initial = (await server.client.get("/api/v1/changes/wait", params={"timeout": 0})).json()
    wait = asyncio.create_task(
        server.client.get(
            "/api/v1/changes/wait",
            params={
                "cursor": initial["cursor"],
                "plugins_rev": initial["plugins_rev"],
                "timeout": 2,
            },
        )
    )
    await asyncio.sleep(0)
    await enable(server, "tasks")
    changed = await asyncio.wait_for(wait, 2)
    assert changed.status_code == 200
    assert changed.json()["changed"] is True
    assert changed.json()["plugins_rev"] > initial["plugins_rev"]
    assert (
        await server.client.get("/api/v1/changes/wait", params={"cursor": "bad"})
    ).status_code == 422


async def test_enabled_dispatch_placeholder_and_token_access(plugin_server):
    server = plugin_server
    await setup(server)
    await enable(server, "tasks")
    plugin = Plugin("tasks")

    @plugin.route("for/{note_id}", allow_tokens=True)
    async def related(ctx, note_id):
        return {"type": "text", "text": note_id}

    @plugin.route("owner")
    async def owner_route(ctx):
        return {"type": "text", "text": "Owner"}

    server.services.host.plugins["tasks"] = plugin
    response = await server.client.get(
        "/api/v1/plugins/tasks/for/abc", params={"note_id": "cannot-override-path"}
    )
    assert response.json() == {"type": "text", "text": "abc"}
    _, token = await tokens.create(
        server.services.store, name="Agent", client="script", scope="read"
    )
    headers = {"Authorization": "Bearer " + token}
    assert (
        await server.client.get("/api/v1/plugins/tasks/for/abc", headers=headers)
    ).status_code == 200
    assert (
        await server.client.get("/api/v1/plugins/tasks/owner", headers=headers)
    ).status_code == 403
    await server.services.host.disable("tasks")
    assert (
        await server.client.get("/api/v1/plugins/tasks/for/abc", headers=headers)
    ).status_code == 404


async def test_unconfigured_plugins_stay_off_and_catalog_explains_requirements(plugin_server):
    server = plugin_server
    await setup(server)
    catalog = (await server.client.get("/api/v1/plugins")).json()["plugins"]
    rows = {row["id"]: row for row in catalog}
    for plugin_id, reason in (
        ("email-in", "Needs a list of allowed senders"),
        ("image-text", "Needs a vision model. Choose one in Settings → AI."),
    ):
        row = rows[plugin_id]
        assert row["switch"] == "off"
        assert row["can_enable"] is False
        assert row["availability_reason"] == reason
        before = server.services.host.plugins_rev
        response = await server.client.post(
            f"/api/v1/plugins/{plugin_id}/enable",
            json={"permissions": row["permissions"]},
            headers=ORIGIN,
        )
        assert response.status_code == 409, response.text
        assert response.json()["code"] == "plugin_requirements_missing"
        assert response.json()["detail"] == reason
        assert server.services.host.states[plugin_id]["switch"] == "off"
        assert server.services.host.plugins_rev == before
    assert rows["tasks"]["can_enable"] is True
    assert rows["tasks"]["availability_reason"] is None


async def test_mode_patch_merges_only_behavior_and_rejects_other_settings(plugin_server):
    server = plugin_server
    await setup(server)
    url = "/api/v1/plugins/label-suggestions/settings"
    advanced = {"confidence_threshold": 0.95}
    assert (await server.client.put(url, json=advanced, headers=ORIGIN)).status_code == 200
    response = await server.client.patch(url, json={"change_mode": "automatic"}, headers=ORIGIN)
    assert response.status_code == 200
    assert response.json()["values"]["confidence_threshold"] == 0.95
    assert response.json()["values"]["change_mode"] == "automatic"
    assert (await server.client.patch(url, json=advanced, headers=ORIGIN)).status_code == 422


async def test_network_consent_tracks_configured_destination(plugin_server):
    server = plugin_server
    await setup(server)
    host = server.services.host
    plugin = "webhooks"

    def catalog_row():
        return next(item for item in host.catalog() if item["id"] == plugin)

    await host.set_settings(plugin, {"urls": ["https://first.example.org/notes"]})
    reviewed = catalog_row()
    assert reviewed["review_required"] and reviewed["network_destinations"] == ["first.example.org"]
    await host.set_settings(plugin, {"urls": ["https://second.example.org/notes"]})
    url = f"/api/v1/plugins/{plugin}/enable"
    consent = {"permissions": reviewed["permissions"], "review_token": reviewed["review_token"]}
    response = await server.client.post(url, json=consent, headers=ORIGIN)
    assert response.status_code == 409
    current = response.json()["plugin"]
    assert current["switch"] == "off" and current["network_destinations"] == ["second.example.org"]
    assert response.json()["code"] == "plugin_review_changed"
    consent["review_token"] = current["review_token"]
    assert (await server.client.post(url, json=consent, headers=ORIGIN)).status_code == 200
