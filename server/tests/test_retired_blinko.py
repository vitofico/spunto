"""Retiring bundled code hides its card without deleting the owner's imported library."""

import json

import pytest
from conftest import PUBLIC_URL, run_server

from spunto.auth.tokens import Principal
from spunto.core.attachments import Attachments, path_for
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.errors import OperatorError
from spunto.host import ContextError, Ctx
from spunto.host.testing import harness

pytestmark = pytest.mark.anyio
PLUGIN = "blinko-import"
OTHER = "retained-fixture"
ORIGIN = {"Origin": PUBLIC_URL}
OWNER = Principal("owner-fixture", "session", "Owner", "full", private_ok=True)
PASSWORD = "correct horse battery"
PERMISSIONS = ["net.out:<configured>", "notes.create", "notes.update_own", "attachments.write"]


async def library(store):
    async with store.read() as conn:
        return {
            table: [
                dict(row)
                for row in await conn.execute_fetchall(f"SELECT * FROM {table} ORDER BY {order}")  # noqa: S608
            ]
            for table, order in (
                ("note", "id"),
                ("note_revision", "note_id,version"),
                ("attachment", "id"),
            )
        }


@pytest.mark.parametrize(
    "origin,source_kind,hidden",
    [("bundled", "bundled", True), ("operator", "folder", False), ("unknown", "unknown", False)],
)
async def test_retired_importer_keeps_library_settings_and_jobs_but_no_executable_surfaces(
    settings, data_dir, monkeypatch, origin, source_kind, hidden
):
    async def no_fetch(*args, **kwargs):
        pytest.fail("Retired import code must never fetch or expose an executable route")

    monkeypatch.setattr("spunto.net.guarded_fetch", no_fetch)
    # A display-only synthetic old manifest seeds exactly the historical host contracts;
    # no removed module or network fixture is imported or executed.
    old = {
        "id": PLUGIN,
        "name": "Import from Blinko",
        "version": "1.0.0",
        "api": "0.4",
        "kind": "server",
        "description": "Synthetic prior import receipt",
        "permissions": PERMISSIONS,
        "creates": "import",
        "default": "off",
        "settings": {"type": "object", "properties": {"url": {"type": "string"}}},
    }
    other = {
        **old,
        "id": OTHER,
        "name": "Synthetic retained operator plugin",
        "permissions": [],
    }
    async with harness(
        data_dir,
        settings,
        catalog=[old, other],
        notes_factory=PluginNotes,
        attachments_factory=Attachments,
    ) as host:
        await host.enable(PLUGIN, PERMISSIONS)
        await host.set_settings(PLUGIN, {"url": "https://source.synthetic.invalid"})
        ctx = Ctx(host, PLUGIN)
        attached = await ctx.attachments.write(b"synthetic retained file", filename="retained.txt")
        note = await ctx.notes.create(
            "Synthetic imported #retained words [file](" + attached["url"] + ")",
            import_key="blinko:synthetic-1",
            created_at=1602670272123,
            updated_at=1738584000000,
            pinned=True,
            archived=True,
        )
        await ctx.storage.records.put(
            "imports", "blinko:synthetic-1", {"version": note["version"]}, note_id=note["id"]
        )
        await Notes(host.store, OWNER).flags(note["id"], {"private": True})
        await ctx.storage.kv.set("last_report", {"created": 1})
        await host._queue(PLUGIN, "job:import", {})
        claimed = await host._claim()
        assert claimed["plugin_id"] == PLUGIN and claimed["status"] == "running"
        await host._queue(PLUGIN, "job:import", {})
        async with host.store.write() as conn:
            await conn.execute(
                "UPDATE plugin SET origin=?,source_kind=? WHERE id=?", (origin, source_kind, PLUGIN)
            )
            await conn.execute(
                "UPDATE plugin SET origin='operator',source_kind='folder' WHERE id=?", (OTHER,)
            )
        before = await library(host.store)
        saved_settings = host.states[PLUGIN]["settings"]
        path = path_for(settings.attachments_dir, attached["sha256"])
        assert path.read_bytes() == b"synthetic retained file"

    # A prior operator pin cannot resurrect the code or prevent the library starting.
    async with harness(data_dir, settings.model_copy(update={"plugins": "+" + PLUGIN})) as host:
        assert host.states[PLUGIN]["state"] == "removed"
        assert host.states[PLUGIN]["switch"] == "off"
        assert host.states[PLUGIN]["pinned_by_env"] == 1
        assert PLUGIN not in host.plugins and PLUGIN not in host.manifests
        assert await host._claim() is None
        with pytest.raises(ContextError, match="plugin_disabled"):
            Ctx(host, PLUGIN).check()
        assert await library(host.store) == before

    async with run_server() as server:
        host = server.services.host
        assert (
            await server.client.post(
                "/api/v1/setup", json={"code": server.services.setup_code, "password": PASSWORD}
            )
        ).status_code == 204
        response = await server.client.get("/api/v1/plugins")
        ids = {item["id"] for item in response.json()["plugins"]}
        assert (PLUGIN not in ids) == hidden
        assert OTHER in ids
        assert (
            next(item for item in host.catalog(include_retired=True) if item["id"] == PLUGIN)[
                "state"
            ]
            == "removed"
        )
        assert PLUGIN not in {item["id"] for item in host.manifest()["plugins"]}
        status_ids = {
            item["id"] for item in (await server.client.get("/api/v1/status")).json()["plugins"]
        }
        assert (PLUGIN not in status_ids) == hidden
        for action in ("settings", "panel?note_id=" + note["id"]):
            assert (
                await server.client.get(f"/api/v1/plugins/{PLUGIN}/{action}")
            ).status_code == 404
        assert (
            await server.client.post(
                f"/api/v1/plugins/{PLUGIN}/enable",
                json={"permissions": PERMISSIONS},
                headers=ORIGIN,
            )
        ).status_code == 404
        assert (
            await server.client.post(
                f"/api/v1/plugins/{PLUGIN}/commands/import", json={}, headers=ORIGIN
            )
        ).status_code == 404
        async with server.services.store.read() as conn:
            saved = (
                await conn.execute_fetchall("SELECT settings FROM plugin WHERE id=?", (PLUGIN,))
            )[0][0]
            jobs = await conn.execute_fetchall(
                "SELECT status,last_error,lease_until FROM job WHERE plugin_id=?", (PLUGIN,)
            )
            records = await conn.execute_fetchall(
                "SELECT data FROM plugin_record WHERE plugin_id=?", (PLUGIN,)
            )
        assert json.loads(saved) == saved_settings
        assert len(jobs) == 2 and all(
            row["status"] == "dead"
            and row["last_error"] == "plugin_removed"
            and row["lease_until"] is None
            for row in jobs
        )
        assert len(records) == 1
        assert await library(server.services.store) == before
        order = (await server.client.get("/api/v1/settings")).json()["plugin_order"]
        assert (PLUGIN not in order) == hidden
        assert (
            await server.client.put(
                "/api/v1/settings", json={"plugin_order": list(reversed(order))}, headers=ORIGIN
            )
        ).status_code == 200
        disabled = await server.client.post(f"/api/v1/plugins/{PLUGIN}/disable", headers=ORIGIN)
        assert disabled.status_code == 200 and disabled.json()["state"] == "removed"
        assert (await server.client.delete(f"/api/v1/plugins/{PLUGIN}/data")).status_code == 403
        assert (
            await server.client.delete(f"/api/v1/plugins/{PLUGIN}/data", headers=ORIGIN)
        ).status_code == 204
        assert await library(server.services.store) == before
        assert path.read_bytes() == b"synthetic retained file"
        assert (PLUGIN not in {item["id"] for item in host.catalog()}) == hidden


async def test_fresh_unknown_retired_pin_still_requires_operator_correction(settings, data_dir):
    with pytest.raises(OperatorError, match="SPUNTO_PLUGINS names unavailable plugins"):
        async with harness(data_dir, settings.model_copy(update={"plugins": "+" + PLUGIN})):
            pytest.fail("A fresh unknown pin must retain the operator configuration error")
