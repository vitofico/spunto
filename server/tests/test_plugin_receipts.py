"""Synchronous toggle receipts bind actual host state/revision for session and device."""

import shutil

import pytest
from conftest import PUBLIC_URL, run_server
from test_removed_plugins import PERMISSIONS, PLUGIN, install

pytestmark = pytest.mark.anyio
PASSWORD = "correct horse battery"
ORIGIN = {"Origin": PUBLIC_URL}


@pytest.mark.parametrize("principal", ["session", "device"])
@pytest.mark.parametrize("lifecycle", ["installed", "removed"])
async def test_toggle_receipt_has_actual_catalog_revision_and_state(
    env, data_dir, principal, lifecycle
):
    folder = install(data_dir)
    async with run_server() as server:
        assert (
            await server.client.post(
                "/api/v1/setup", json={"code": server.services.setup_code, "password": PASSWORD}
            )
        ).status_code == 204
        assert (
            await server.client.post(
                f"/api/v1/plugins/{PLUGIN}/enable",
                json={"permissions": PERMISSIONS},
                headers=ORIGIN,
            )
        ).status_code == 200
        issued = await server.client.post(
            "/api/v1/auth/devices", json={"password": PASSWORD, "name": "Synthetic receipt phone"}
        )
        assert issued.status_code == 200
        bearer = {"Authorization": "Bearer " + issued.json()["token"]}
    if lifecycle == "removed":
        shutil.rmtree(folder)
    async with run_server() as server:
        if principal == "session":
            assert (
                await server.client.post("/api/v1/auth/login", json={"password": PASSWORD})
            ).status_code == 204
        headers = ORIGIN if principal == "session" else bearer
        actions = ["disable", "enable"] if lifecycle == "installed" else ["disable"]
        for action in actions:
            revision_before = server.services.host.plugins_rev
            response = await server.client.post(
                f"/api/v1/plugins/{PLUGIN}/{action}",
                json={"permissions": PERMISSIONS} if action == "enable" else None,
                headers=headers,
            )
            assert response.status_code == 200, response.text
            receipt = response.json()
            assert receipt["plugins_rev"] == server.services.host.plugins_rev
            assert receipt["plugins_rev"] > revision_before
            current = next(item for item in server.services.host.catalog() if item["id"] == PLUGIN)
            assert {key: value for key, value in receipt.items() if key != "plugins_rev"} == current
            catalog = (await server.client.get("/api/v1/plugins", headers=headers)).json()
            # Other plugins may finish deferred lifecycle work during the awaited GET.
            assert catalog["plugins_rev"] >= receipt["plugins_rev"]
            fetched = next(item for item in catalog["plugins"] if item["id"] == PLUGIN)
            assert fetched["state"] == receipt["state"] and fetched["switch"] == receipt["switch"]
            assert receipt["state"] == current["state"] and receipt["switch"] == current["switch"]
        if lifecycle == "removed":
            assert receipt["state"] == "removed" and receipt["switch"] == "off"
            assert receipt["can_enable"] is False
            assert (
                await server.client.post(
                    f"/api/v1/plugins/{PLUGIN}/enable",
                    json={"permissions": PERMISSIONS},
                    headers=headers,
                )
            ).status_code == 404
