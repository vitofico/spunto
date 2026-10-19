"""Device switches travel in the manifest without executing Android code on the host."""

import json
from pathlib import Path

import pytest
from conftest import PUBLIC_URL

from spunto import plugins
from spunto.auth import tokens
from spunto.host import read_manifest
from spunto.host.testing import harness

pytestmark = pytest.mark.anyio
DEVICES = {"dictation", "quick-capture"}


async def test_fresh_catalog_enables_device_defaults_without_server_jobs(tmp_path, settings):
    async with harness(tmp_path, settings) as host:
        catalog = {item["id"]: item for item in host.catalog()}
        discovered = [read_manifest(p) for p in Path(plugins.__file__).parent.glob("*/plugin.toml")]
        assert set(catalog) == {p["id"] for p in discovered}
        assert {p["id"] for p in discovered if p["kind"] == "device"} == DEVICES
        assert {
            "title-summary",
            "tidy",
            "duplicates",
            "label-gardener",
            "links",
            "digest",
            "develop",
            "translate",
        } <= set(catalog)
        for plugin_id in DEVICES:
            assert catalog[plugin_id]["kind"] == "device"
            assert catalog[plugin_id]["switch"] == "on"
            assert catalog[plugin_id]["state"] == "enabled"
            assert plugin_id not in host.plugins
        assert {item["id"] for item in host.manifest()["plugins"]} == DEVICES
        async with host.store.read() as conn:
            assert not await conn.execute_fetchall(
                "SELECT * FROM job WHERE plugin_id IN ('dictation','quick-capture')"
            )


async def test_device_switch_removes_manifest_surface_and_persists_after_restart(
    tmp_path, settings
):
    async with harness(tmp_path, settings) as host:
        revision = host.plugins_rev
        await host.disable("dictation")
        assert host.plugins_rev > revision
        assert {item["id"] for item in host.manifest()["plugins"]} == {"quick-capture"}
    async with harness(tmp_path, settings) as host:
        assert host.states["dictation"]["switch"] == "off"
        assert {item["id"] for item in host.manifest()["plugins"]} == {"quick-capture"}
        await host.enable("dictation", permissions=["mic"])
        assert {item["id"] for item in host.manifest()["plugins"]} == DEVICES


async def test_new_device_plugins_start_off_on_upgrade(tmp_path, settings):
    async with harness(tmp_path, settings, catalog=[]) as host:
        assert not host.catalog()
    async with harness(tmp_path, settings) as host:
        for plugin_id in DEVICES:
            assert host.states[plugin_id]["switch"] == "off"
            assert host.states[plugin_id]["state"] == "available"
        assert not host.manifest()["plugins"]


async def test_device_environment_pins_win_without_server_entrypoints(tmp_path, settings):
    settings.plugins = "+dictation,-quick-capture"
    async with harness(tmp_path, settings) as host:
        assert host.states["dictation"]["pinned_by_env"]
        assert host.states["dictation"]["state"] == "enabled"
        assert host.states["quick-capture"]["pinned_by_env"]
        assert host.states["quick-capture"]["switch"] == "off"
        assert set(host.plugins) == set()


async def test_real_device_has_owner_reads_and_switches_with_session_settings_boundaries(server):
    password = "correct horse battery"
    assert (
        await server.client.post(
            "/api/v1/setup",
            json={"code": server.services.setup_code, "password": password},
        )
    ).status_code == 204
    issued = await server.client.post(
        "/api/v1/auth/devices", json={"password": password, "name": "M5 fixture phone"}
    )
    assert issued.status_code == 200
    bearer = {"Authorization": "Bearer " + issued.json()["token"]}
    for path in ("/api/v1/plugins/tasks/settings", "/api/v1/plugins/manifest", "/api/v1/ai"):
        assert (await server.client.get(path, headers=bearer)).status_code == 200
    for path in ("/api/v1/settings", "/api/v1/status"):
        assert (await server.client.get(path, headers=bearer)).status_code == 403
    assert (
        await server.client.put("/api/v1/plugins/tasks/settings", json={}, headers=bearer)
    ).status_code == 403
    assert (
        await server.client.post("/api/v1/plugins/dictation/disable", headers=bearer)
    ).status_code == 200

    assert (
        await server.client.post(
            "/api/v1/plugins/dictation/enable", json={"permissions": ["mic"]}, headers=bearer
        )
    ).status_code == 200
    assert (
        await server.client.put(
            "/api/v1/settings",
            json={"theme": "carta"},
            headers={**bearer, "Origin": "https://untrusted.example.test"},
        )
    ).status_code == 403
    for path in ("/api/v1/ai/check", "/api/v1/ai/reindex", "/api/v1/auth/pairing"):
        assert (await server.client.post(path, json={}, headers=bearer)).status_code == 403
    _, token = await tokens.create(
        server.services.store, name="Agent", client="script", scope="write"
    )
    agent = {"Authorization": "Bearer " + token}
    for path in ("/api/v1/settings", "/api/v1/ai"):
        assert (await server.client.get(path, headers=agent)).status_code == 403
    assert (
        await server.client.put("/api/v1/settings", json={"theme": "carta"}, headers=agent)
    ).status_code == 403
    assert (
        await server.client.put("/api/v1/plugins/tasks/settings", json={}, headers=agent)
    ).status_code == 403
    assert (
        await server.client.put(
            "/api/v1/settings", json={"theme": "carta"}, headers={"Origin": PUBLIC_URL}
        )
    ).status_code == 200


async def test_native_manifest_matches_shared_theme_tokens_and_invalidates_cached_theme(server):
    password = "correct horse battery"
    assert (
        await server.client.post(
            "/api/v1/setup", json={"code": server.services.setup_code, "password": password}
        )
    ).status_code == 204
    issued = await server.client.post(
        "/api/v1/auth/devices", json={"password": password, "name": "Theme fixture"}
    )
    device = {"Authorization": "Bearer " + issued.json()["token"]}
    shared = json.loads((Path(__file__).parents[2] / "web/tokens.json").read_text())["themes"]
    prior_etag = None
    for theme in shared:
        response = await server.client.put(
            "/api/v1/settings",
            json={"theme": theme, "hidden_views": ["tasks/tasks"]},
            headers={"Origin": PUBLIC_URL},
        )
        assert response.status_code == 200
        manifest = await server.client.get(
            "/api/v1/plugins/manifest",
            headers={**device, **({"If-None-Match": prior_etag} if prior_etag else {})},
        )
        assert manifest.status_code == 200
        assert manifest.json()["theme"] == {"id": theme, "tokens": 1, **shared[theme]}
        assert manifest.json()["hidden_views"] == ["tasks/tasks"]
        prior_etag = manifest.headers["etag"]
        assert (
            await server.client.get(
                "/api/v1/plugins/manifest", headers={**device, "If-None-Match": prior_etag}
            )
        ).status_code == 304
