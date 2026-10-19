"""Retained operator identities: real SQLite lifecycle and authenticated local ASGI."""

import asyncio
import json
import os
import shutil
from contextlib import closing
from pathlib import Path

import pytest
from conftest import PUBLIC_URL, run_server

from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.host import ContextError, Ctx, ManifestError
from spunto.host.testing import harness
from spunto.store import migrations
from spunto.store.db import connect

pytestmark = pytest.mark.anyio
PLUGIN = "retained-demo"
PERMISSIONS = ["notes.read", "notes.create", "suggest", "annotate", "search.text"]
ORIGIN = {"Origin": PUBLIC_URL}
PASSWORD = "correct horse battery"


def install(directory, *, folder="demo", plugin_id=PLUGIN, api="1.0", extra=""):
    target = directory / "plugins" / folder
    target.mkdir(parents=True)
    permissions = json.dumps(PERMISSIONS)
    (target / "plugin.toml").write_text(
        f'id="{plugin_id}"\nname="Synthetic retained plugin"\nversion="1"\n'
        f'api="{api}"\nkind="server"\ndescription="Synthetic retention contract"\n'
        f'permissions={permissions}\ncreates="external"\ndefault="off"\n'
        'entry="plugin:plugin"\n[contributes.meta]\ntitle="Synthetic metadata"\n' + extra
    )
    (target / "plugin.py").write_text(
        "from spunto.host import Plugin\n"
        f"plugin = Plugin({plugin_id!r})\n"
        "@plugin.job('follow')\nasync def follow(ctx):\n    return None\n"
    )
    return target


def card(host, plugin_id=PLUGIN):
    return next(item for item in host.catalog() if item["id"] == plugin_id)


async def populate(host):
    ctx = Ctx(host, PLUGIN)
    plugin_notes = PluginNotes(ctx)
    note = await plugin_notes.create("Synthetic plugin-authored words")
    await ctx.storage.kv.set("retained", {"synthetic": True})
    await ctx.storage.records.put("retained", "item", {"synthetic": True}, note_id=note["id"])
    await plugin_notes.annotate(note["id"], "meta", {"type": "markdown", "text": "Synthetic"})
    suggestion = await plugin_notes.suggest(
        note["id"], "set_title", {"title": "Synthetic proposal"}, note_version=note["version"]
    )
    await plugin_notes.set_search_text(note["id"], "Synthetic searchable supplement")
    await host.enqueue(PLUGIN, "follow", delay_ms=3_600_000)
    async with host.store.write() as conn:
        await conn.execute("INSERT INTO plugin_kv VALUES('unowned-id','retained','true')")
    return note, suggestion


async def test_removed_catalog_owner_cleanup_preserves_notes_and_other_data(env, data_dir):
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
        note, suggestion = await populate(server.services.host)
        owner = Notes(
            server.services.store, Principal("owner", "session", "Owner", "full", private_ok=True)
        )
        owner_note = await owner.create("Synthetic unrelated owner words")
        issued = await server.client.post(
            "/api/v1/auth/devices", json={"password": PASSWORD, "name": "Synthetic retained phone"}
        )
        assert issued.status_code == 200
        device = {"Authorization": "Bearer " + issued.json()["token"]}
        before_rev = server.services.host.plugins_rev
    shutil.rmtree(folder)
    async with run_server() as server:
        assert (
            await server.client.post("/api/v1/auth/login", json={"password": PASSWORD})
        ).status_code == 204
        response = await server.client.get("/api/v1/plugins")
        assert response.status_code == 200
        removed = next(item for item in response.json()["plugins"] if item["id"] == PLUGIN)
        assert response.json()["plugins_rev"] > before_rev
        assert removed["state"] == "removed" and removed["switch"] == "off"
        assert removed["origin"] == "operator" and removed["source_kind"] == "folder"
        assert removed["name"] == "Synthetic retained plugin"
        assert removed["can_enable"] is False and removed["can_delete_data"] is True
        assert removed["granted"] == PERMISSIONS
        assert removed["data_count"] >= 5 and removed["data_size_bytes"] > 0
        assert all(
            removed["data_counts"][table] > 0
            for table in ("plugin_kv", "plugin_record", "suggestion", "annotation", "note_text")
        )
        assert "entry" not in removed and "contributes" not in removed
        assert removed["settings"] == {} and removed["secrets"] == {}
        assert removed["ai_destinations"] == {} and removed["network_destinations"] == []
        assert "source_identity" not in removed and str(data_dir) not in response.text
        output = os.environ.get("REMOVED_PLUGIN_CATALOG_OUTPUT")
        if output:
            await asyncio.to_thread(
                Path(output).write_text, json.dumps({"plugins": [removed]}, indent=2) + "\n"
            )
        assert (await server.client.get("/api/v1/plugins", headers=device)).status_code == 200
        for path in ("settings", "panel?note_id=" + note["id"]):
            assert (await server.client.get(f"/api/v1/plugins/{PLUGIN}/{path}")).status_code == 404
        assert (
            await server.client.post(
                f"/api/v1/plugins/{PLUGIN}/enable",
                json={"permissions": PERMISSIONS},
                headers=ORIGIN,
            )
        ).status_code == 404
        assert (
            await server.client.post(
                f"/api/v1/plugins/{PLUGIN}/commands/missing", json={}, headers=ORIGIN
            )
        ).status_code == 404
        manifest = (await server.client.get("/api/v1/plugins/manifest")).json()
        assert PLUGIN not in {item["id"] for item in manifest["plugins"]}
        # Catalog ordering includes the retained card, but its surfaces never become executable.
        order = (await server.client.get("/api/v1/settings")).json()["plugin_order"]
        assert PLUGIN in order
        assert (
            await server.client.put(
                "/api/v1/settings", json={"plugin_order": list(reversed(order))}, headers=ORIGIN
            )
        ).status_code == 200
        denied = await server.client.delete(f"/api/v1/plugins/{PLUGIN}/data", headers=device)
        assert denied.status_code == 403
        assert (await server.client.delete(f"/api/v1/plugins/{PLUGIN}/data")).status_code == 403
        assert (
            await server.client.delete(
                f"/api/v1/plugins/{PLUGIN}/data", headers={"Origin": "https://wrong.test"}
            )
        ).status_code == 403
        for plugin_id in ("unknown-id", "unowned-id"):
            assert (
                await server.client.delete(f"/api/v1/plugins/{plugin_id}/data", headers=ORIGIN)
            ).status_code == 404
        assert (
            await server.client.post(f"/api/v1/plugins/{PLUGIN}/disable", headers=ORIGIN)
        ).json()["state"] == "removed"
        assert (
            await server.client.delete(f"/api/v1/plugins/{PLUGIN}/data", headers=ORIGIN)
        ).status_code == 204
        cleaned = next(
            item
            for item in (await server.client.get("/api/v1/plugins")).json()["plugins"]
            if item["id"] == PLUGIN
        )
        assert cleaned["state"] == "removed" and cleaned["data_count"] == 0
        owner = Notes(
            server.services.store, Principal("owner", "session", "Owner", "full", private_ok=True)
        )
        kept = await owner.get(note["id"])
        assert kept["body"] == note["body"] and kept["origin"] == "external"
        assert (await owner.get(owner_note["id"]))["body"] == owner_note["body"]
        async with server.services.store.read() as conn:
            assert (
                await conn.execute_fetchall("SELECT created_by FROM note WHERE id=?", (note["id"],))
            )[0][0] == "plugin:" + PLUGIN
            assert await conn.execute_fetchall(
                "SELECT 1 FROM plugin_kv WHERE plugin_id='unowned-id'"
            )
            assert not await conn.execute_fetchall("SELECT 1 FROM job WHERE plugin_id=?", (PLUGIN,))
            tombstones = await conn.execute_fetchall(
                "SELECT kind,id FROM tombstone WHERE kind IN ('suggestion','annotation')"
            )
        assert ("suggestion", suggestion["id"]) in {tuple(row) for row in tombstones}
        assert ("annotation", f"{PLUGIN}/{note['id']}/meta") in {tuple(row) for row in tombstones}
    async with run_server() as server:
        assert card(server.services.host)["state"] == "removed"


@pytest.mark.parametrize("api", ["0.4", "0.9", "1.0"])
async def test_reinstall_is_off_and_cannot_revive_removed_jobs_or_new_grants(
    tmp_path, settings, api
):
    folder = install(tmp_path, api=api)
    config = settings.model_copy(update={"data_dir": tmp_path})
    async with harness(tmp_path, config) as host:
        await host.enable(PLUGIN, PERMISSIONS)
        await host.enqueue(PLUGIN, "follow", delay_ms=3_600_000)
    shutil.rmtree(folder)
    async with harness(tmp_path, config) as host:
        assert card(host)["state"] == "removed"
        async with host.store.read() as conn:
            job = (await conn.execute_fetchall("SELECT * FROM job WHERE plugin_id=?", (PLUGIN,)))[0]
        assert job["status"] == "dead" and job["last_error"] == "plugin_removed"
    folder = install(tmp_path, api=api)
    manifest = folder / "plugin.toml"
    manifest.write_text(
        manifest.read_text().replace(
            json.dumps(PERMISSIONS), json.dumps([*PERMISSIONS, "attachments.read"])
        )
    )
    async with harness(tmp_path, config) as host:
        current = card(host)
        assert current["switch"] == "off" and current["origin"] == "operator"
        assert "attachments.read" not in current["granted"]
        with pytest.raises(ManifestError, match="current manifest permissions"):
            await host.enable(PLUGIN, PERMISSIONS)
        await host.enable(PLUGIN, [*PERMISSIONS, "attachments.read"])
        assert host.states[PLUGIN]["state"] == "enabled"
        async with host.store.read() as conn:
            job = (await conn.execute_fetchall("SELECT * FROM job WHERE plugin_id=?", (PLUGIN,)))[0]
        assert job["status"] == "dead" and job["last_error"] == "plugin_removed"


async def test_new_source_with_same_id_does_not_inherit_consent_or_execute(tmp_path, settings):
    folder = install(tmp_path)
    config = settings.model_copy(update={"data_dir": tmp_path})
    async with harness(tmp_path, config) as host:
        await host.enable(PLUGIN, PERMISSIONS)
        stale = Ctx(host, PLUGIN)
        generation = host.generations[PLUGIN]
        notes = PluginNotes(stale)
        note = await notes.create("Synthetic source-change note")
        await notes.set_search_text(note["id"], "Retired-source supplement")
        await host.enqueue(PLUGIN, "follow", delay_ms=3_600_000)
        folder.rename(folder.parent / "replacement")
        await host.discover()
        current = card(host)
        assert current["switch"] == "off" and current["granted"] == []
        assert current["origin"] == "operator" and current["source_kind"] == "folder"
        assert host.generations[PLUGIN] > generation
        assert PLUGIN not in host.plugins
        with pytest.raises(ContextError, match="plugin_disabled"):
            stale.check()
        async with host.store.read() as conn:
            jobs = await conn.execute_fetchall("SELECT * FROM job WHERE plugin_id=?", (PLUGIN,))
            text = await conn.execute_fetchall(
                "SELECT extra FROM note_fts WHERE rowid=(SELECT rowid FROM note WHERE id=?)",
                (note["id"],),
            )
        assert text[0][0] == ""
        assert all(
            job["status"] == "dead" and job["last_error"] == "plugin_removed" for job in jobs
        )


async def test_bundled_and_colliding_operator_origins_are_loader_established(tmp_path, settings):
    install(tmp_path, plugin_id="tasks")
    install(tmp_path, folder="custom")
    async with harness(tmp_path, settings.model_copy(update={"data_dir": tmp_path})) as host:
        assert card(host, "tasks")["origin"] == "bundled"
        assert card(host, "tasks")["source_kind"] == "bundled"
        assert card(host)["origin"] == "operator"
        failure = next(item for item in host.catalog() if item["id"].startswith("failed-"))
        assert failure["origin"] == "operator" and failure["source_kind"] == "folder"
        assert failure["state"] == "failed" and failure["permissions"] == []
        assert "entry" not in failure and "contributes" not in failure


async def test_schema_five_upgrade_retains_unknown_origin_without_unsafe_metadata(
    tmp_path, settings
):
    path = tmp_path / "spunto.db"
    with closing(connect(path)) as conn:
        for version in range(1, 6):
            migrations._apply(conn, version)
        conn.execute(
            "INSERT INTO plugin(id,version,kind,switch,state,granted,settings,position) "
            "VALUES('legacy-plugin','1','server','on','enabled','[\"notes.read\"]',?,0)",
            (json.dumps({"old_secret_setting": "not-catalog-metadata"}),),
        )
    async with harness(tmp_path, settings.model_copy(update={"data_dir": tmp_path})) as host:
        legacy = card(host, "legacy-plugin")
        assert legacy["origin"] == "unknown" and legacy["source_kind"] == "unknown"
        assert legacy["name"] == "Removed plugin" and legacy["state"] == "removed"
        assert legacy["switch"] == "off" and legacy["granted"] == []
        assert legacy["settings"] == {} and "not-catalog-metadata" not in json.dumps(legacy)
        async with host.store.read() as conn:
            persisted = (
                await conn.execute_fetchall("SELECT * FROM plugin WHERE id='legacy-plugin'")
            )[0]
        assert persisted["state"] == "removed" and persisted["switch"] == "off"
        assert json.loads(persisted["settings"])["old_secret_setting"] == "not-catalog-metadata"
        assert list((tmp_path / "snapshots").glob("*"))


async def test_tampered_retained_snapshot_cannot_inject_missing_code_surfaces(tmp_path, settings):
    folder = install(tmp_path)
    config = settings.model_copy(update={"data_dir": tmp_path})
    async with harness(tmp_path, config) as host:
        async with host.store.write() as conn:
            await conn.execute(
                "UPDATE plugin SET manifest_snapshot=? WHERE id=?",
                (
                    json.dumps({**host.manifests[PLUGIN], "settings": {"unsafe": "old-secret"}}),
                    PLUGIN,
                ),
            )
    shutil.rmtree(folder)
    async with harness(tmp_path, config) as host:
        retained = card(host)
        assert retained["name"] == "Removed plugin" and retained["permissions"] == []
        assert "contributes" not in retained and "entry" not in retained
        assert "old-secret" not in json.dumps(retained)
        assert retained["can_enable"] is False


async def test_entrypoint_origin_survives_removal_without_importing_its_code(
    tmp_path, settings, monkeypatch
):
    import importlib.metadata
    from types import SimpleNamespace

    package = tmp_path / "installed_retained"
    package.mkdir()
    marker = tmp_path / "must-not-import"
    (package / "__init__.py").write_text(
        f"from pathlib import Path\nPath({str(marker)!r}).touch()\nraise RuntimeError('unused')\n"
    )
    (package / "plugin.toml").write_text(
        'id="installed-retained"\nname="Synthetic entrypoint"\nversion="1"\napi="1.0"\n'
        'kind="server"\ndescription="Synthetic installed package"\npermissions=[]\n'
        'entry="installed_retained:plugin"\n'
    )
    distribution = SimpleNamespace(name="retention-test", locate_file=lambda path: tmp_path / path)
    entry = SimpleNamespace(name="entry", value="installed_retained:plugin", dist=distribution)
    monkeypatch.setattr(importlib.metadata, "entry_points", lambda **kwargs: [entry])
    config = settings.model_copy(update={"data_dir": tmp_path})
    async with harness(tmp_path, config) as host:
        installed = card(host, "installed-retained")
        assert installed["origin"] == "operator" and installed["source_kind"] == "entrypoint"
        assert installed["state"] == "available" and not marker.exists()
    monkeypatch.setattr(importlib.metadata, "entry_points", lambda **kwargs: [])
    async with harness(tmp_path, config) as host:
        removed = card(host, "installed-retained")
        assert removed["origin"] == "operator" and removed["source_kind"] == "entrypoint"
        assert removed["state"] == "removed" and removed["can_enable"] is False
        assert "entry" not in removed and not marker.exists()


@pytest.mark.parametrize("pin", ["+", "-"])
async def test_retained_environment_pin_stays_locked_off_but_owner_can_delete(env, data_dir, pin):
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
        await Ctx(server.services.host, PLUGIN).storage.kv.set("retained", {"synthetic": True})
    env(SPUNTO_PLUGINS=pin + PLUGIN)
    async with run_server() as server:
        installed = card(server.services.host)
        assert installed["pinned_by_env"] == 1
        assert installed["switch"] == ("on" if pin == "+" else "off")
    shutil.rmtree(folder)
    async with run_server() as server:
        assert (
            await server.client.post("/api/v1/auth/login", json={"password": PASSWORD})
        ).status_code == 204
        removed = next(
            item
            for item in (await server.client.get("/api/v1/plugins")).json()["plugins"]
            if item["id"] == PLUGIN
        )
        assert removed["state"] == "removed" and removed["switch"] == "off"
        assert removed["pinned_by_env"] == 1 and removed["can_enable"] is False
        assert removed["can_delete_data"] is True and removed["data_count"] > 0
        denied = await server.client.post(f"/api/v1/plugins/{PLUGIN}/disable", headers=ORIGIN)
        assert denied.status_code == 409 and denied.json()["code"] == "pinned_by_environment"
        assert (
            await server.client.post(
                f"/api/v1/plugins/{PLUGIN}/enable",
                json={"permissions": PERMISSIONS},
                headers=ORIGIN,
            )
        ).status_code == 404
        async with server.services.store.read() as conn:
            stored = (await conn.execute_fetchall("SELECT * FROM plugin WHERE id=?", (PLUGIN,)))[0]
        assert stored["state"] == "removed" and stored["switch"] == "off"
        assert stored["pinned_by_env"] == 1
        assert (
            await server.client.delete(f"/api/v1/plugins/{PLUGIN}/data", headers=ORIGIN)
        ).status_code == 204
        assert card(server.services.host)["pinned_by_env"] == 1
    async with run_server() as server:
        restarted = card(server.services.host)
        assert restarted["state"] == "removed" and restarted["switch"] == "off"
        assert restarted["pinned_by_env"] == 1


@pytest.mark.parametrize("pin", ["+", "-"])
async def test_unknown_environment_pin_still_rejects_startup(tmp_path, settings, pin):
    from spunto.errors import OperatorError

    config = settings.model_copy(update={"data_dir": tmp_path, "plugins": pin + "unknown-plugin"})
    with pytest.raises(
        OperatorError, match="SPUNTO_PLUGINS names unavailable plugins: unknown-plugin"
    ):
        async with harness(tmp_path, config):
            pytest.fail("An unknown environment pin must not start the host")


@pytest.mark.parametrize("change", ["removed", "replacement"])
@pytest.mark.parametrize("outcome", ["pause", "cancel", "provider", "validation"])
async def test_late_old_job_outcomes_cannot_revive_retired_receipts(
    tmp_path, settings, change, outcome
):
    from spunto.ai.errors import AIError

    folder = install(tmp_path)
    config = settings.model_copy(update={"data_dir": tmp_path})
    async with harness(tmp_path, config) as host:
        await host.enable(PLUGIN, PERMISSIONS)
        await host.enqueue(PLUGIN, "follow")
        row = await host._claim()
        assert row and row["plugin_id"] == PLUGIN and row["status"] == "running"
        async with host.store.write() as conn:
            await conn.execute(
                "UPDATE plugin_kv SET value='4' WHERE plugin_id=? "
                "AND key='host:consecutive_failures'",
                (PLUGIN,),
            )
        host.failures[PLUGIN] = 4
        entered, released, canceled = asyncio.Event(), asyncio.Event(), asyncio.Event()
        task = None
        if outcome != "pause":

            async def late_handler(ctx):
                entered.set()
                try:
                    await released.wait()
                except asyncio.CancelledError:
                    canceled.set()
                    await released.wait()
                if outcome == "cancel":
                    raise asyncio.CancelledError
                if outcome == "provider":
                    raise AIError("unavailable", "ai_temporary_unavailable", retry_after=1)
                raise ManifestError("Synthetic delayed validation failure")

            host.plugins[PLUGIN].handlers["job"]["follow"] = late_handler
            task = asyncio.create_task(host._run(row))
            host.running[row["id"]] = (PLUGIN, task)
            await asyncio.wait_for(entered.wait(), 2)
        try:
            if change == "removed":
                shutil.rmtree(folder)
            else:
                folder.rename(folder.parent / "replacement")
            await host.discover()
            if task:
                await asyncio.wait_for(canceled.wait(), 2)
            async with host.store.read() as conn:
                before = dict(
                    (await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (row["id"],)))[0]
                )
                failures_before = await conn.execute_fetchall(
                    "SELECT value FROM plugin_kv WHERE plugin_id=? "
                    "AND key='host:consecutive_failures'",
                    (PLUGIN,),
                )
            assert before["status"] == "dead" and before["last_error"] == "plugin_removed"
            state_before = dict(host.states[PLUGIN])
            if task:
                released.set()
                await asyncio.wait_for(task, 2)
            else:
                assert await host._pause(row) is False
            async with host.store.read() as conn:
                after = dict(
                    (await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (row["id"],)))[0]
                )
                failures_after = await conn.execute_fetchall(
                    "SELECT value FROM plugin_kv WHERE plugin_id=? "
                    "AND key='host:consecutive_failures'",
                    (PLUGIN,),
                )
            assert after == before
            assert [tuple(value) for value in failures_after] == [
                tuple(value) for value in failures_before
            ]
            assert host.states[PLUGIN] == state_before
            assert host.failures[PLUGIN] == 4
            if change == "removed":
                install(tmp_path)
                await host.discover()
            await host.enable(PLUGIN, PERMISSIONS)
            assert host.states[PLUGIN]["state"] == "enabled"
            assert await host._claim() is None
            # A marker surviving an old buggy queue transition is also never executable.
            async with host.store.write() as conn:
                await conn.execute("UPDATE job SET status='queued' WHERE id=?", (row["id"],))
            assert await host._claim() is None
            async with host.store.write() as conn:
                await conn.execute("UPDATE job SET status='dead' WHERE id=?", (row["id"],))
            # Fresh work is permitted; re-enable/coalescing must retain the retired row.
            fresh = await host.enqueue(PLUGIN, "follow")
            await host.enable(PLUGIN, PERMISSIONS)
            claimed = await host._claim()
            assert claimed and claimed["id"] == fresh and claimed["id"] != row["id"]
            async with host.store.read() as conn:
                retained = dict(
                    (await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (row["id"],)))[0]
                )
            assert retained == before
        finally:
            released.set()
            if task and not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)


@pytest.mark.parametrize("change", ["removed", "unchanged"])
@pytest.mark.parametrize(
    "outcome", ["pause", "cancel", "provider", "validation", "success", "migration"]
)
async def test_cleanup_reused_job_id_cannot_own_fresh_claim(tmp_path, settings, change, outcome):
    from spunto.ai.errors import AIError

    folder = install(tmp_path)
    manifest = folder / "plugin.toml"
    manifest.write_text(
        manifest.read_text().replace(
            'entry="plugin:plugin"', 'entry="plugin:plugin"\nconcurrency=2'
        )
    )
    config = settings.model_copy(update={"data_dir": tmp_path})
    async with harness(tmp_path, config) as host:
        await host.enable(PLUGIN, PERMISSIONS)
        entered, canceled, released = asyncio.Event(), asyncio.Event(), asyncio.Event()
        fresh_entered, fresh_released = asyncio.Event(), asyncio.Event()
        old_task = fresh_task = None

        async def late_handler(ctx):
            entered.set()
            try:
                await released.wait()
            except asyncio.CancelledError:
                canceled.set()
                try:
                    await released.wait()
                except asyncio.CancelledError:
                    await released.wait()
            if outcome == "cancel":
                raise asyncio.CancelledError
            if outcome == "provider":
                raise AIError("unavailable", "ai_temporary_unavailable", retry_after=1)
            if outcome == "validation":
                raise ManifestError("Synthetic delayed validation failure")

        async def fresh_handler(ctx):
            fresh_entered.set()
            await fresh_released.wait()
            await ctx.storage.kv.set("fresh-result", {"completed": True})

        if outcome == "migration":
            host.plugins[PLUGIN].handlers["migrate"][(0, 1)] = late_handler
            await host._queue(PLUGIN, "migrate", {"old": 0, "new": 1})
        else:
            host.plugins[PLUGIN].handlers["job"]["follow"] = late_handler
            await host.enqueue(PLUGIN, "follow")
        old = await host._claim()
        assert old and old["plugin_id"] == PLUGIN and old["run_receipt"]
        if outcome != "pause":
            old_task = asyncio.create_task(host._run(old))
            host.running[old["id"]] = (PLUGIN, old_task)
            await asyncio.wait_for(entered.wait(), 2)
        try:
            if change == "removed":
                shutil.rmtree(folder)
                await host.discover()
                if old_task:
                    await asyncio.wait_for(canceled.wait(), 2)
            await host.delete_data(PLUGIN)
            if old_task:
                await asyncio.wait_for(canceled.wait(), 2)
            if change == "removed":
                folder = install(tmp_path)
                manifest = folder / "plugin.toml"
                manifest.write_text(
                    manifest.read_text().replace(
                        'entry="plugin:plugin"', 'entry="plugin:plugin"\nconcurrency=2'
                    )
                )
                await host.discover()
                await host.enable(PLUGIN, PERMISSIONS)
            host.plugins[PLUGIN].handlers["job"]["follow"] = fresh_handler
            fresh_id = await host.enqueue(PLUGIN, "follow")
            assert fresh_id == old["id"], "Exercise SQLite's deleted-max-rowid reuse"
            async with host.store.read() as conn:
                queued = dict(
                    (await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (fresh_id,)))[0]
                )
            assert queued["run_receipt"] is None
            assert await host._pause(old) is False
            async with host.store.read() as conn:
                assert (
                    dict(
                        (await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (fresh_id,)))[
                            0
                        ]
                    )
                    == queued
                )
            fresh = await host._claim()
            assert fresh and fresh["id"] == old["id"] and fresh["run_receipt"] != old["run_receipt"]
            fresh_task = asyncio.create_task(host._run(fresh))
            host.running[fresh_id] = (PLUGIN, fresh_task)
            await asyncio.wait_for(fresh_entered.wait(), 2)
            async with host.store.read() as conn:
                before = dict(
                    (await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (fresh_id,)))[0]
                )
            state_before = dict(host.states[PLUGIN])
            failures_before = host.failures.get(PLUGIN)
            if old_task:
                released.set()
                await asyncio.wait_for(old_task, 2)
            else:
                assert await host._pause(old) is False
            async with host.store.read() as conn:
                after = dict(
                    (await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (fresh_id,)))[0]
                )
            assert after == before
            assert host.states[PLUGIN] == state_before
            assert host.failures.get(PLUGIN) == failures_before
            assert host.running[fresh_id] == (PLUGIN, fresh_task)
            assert host.claim_receipts[fresh_id] == fresh["run_receipt"]
            fresh_released.set()
            await asyncio.wait_for(fresh_task, 2)
            async with host.store.read() as conn:
                assert not await conn.execute_fetchall("SELECT 1 FROM job WHERE id=?", (fresh_id,))
                result = (
                    await conn.execute_fetchall(
                        "SELECT value FROM plugin_kv WHERE plugin_id=? AND key='fresh-result'",
                        (PLUGIN,),
                    )
                )[0][0]
            assert json.loads(result) == {"completed": True}
        finally:
            released.set()
            fresh_released.set()
            for task in (old_task, fresh_task):
                if task and not task.done():
                    task.cancel()
                    await asyncio.gather(task, return_exceptions=True)


async def test_reclaim_and_coalescing_rotate_receipt_without_losing_new_payload(tmp_path, settings):
    install(tmp_path)
    async with harness(tmp_path, settings.model_copy(update={"data_dir": tmp_path})) as host:
        await host.enable(PLUGIN, PERMISSIONS)
        await host.enqueue(PLUGIN, "follow")
        original = await host._claim()
        assert await host._pause(original) is True
        async with host.store.read() as conn:
            paused = (
                await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (original["id"],))
            )[0]
        assert paused["run_receipt"] == original["run_receipt"]
        same_id = await host.enqueue(PLUGIN, "follow", {"revision": "newer"})
        assert same_id == original["id"]
        async with host.store.read() as conn:
            newer = dict(
                (await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (same_id,)))[0]
            )
        assert newer["run_receipt"] is None and json.loads(newer["args"])["revision"] == "newer"
        assert await host._pause(original) is False
        reclaimed = await host._claim()
        assert reclaimed["run_receipt"] and reclaimed["run_receipt"] != original["run_receipt"]
        async with host.store.write() as conn:
            await host._delete_job(conn, original, running=True)
        async with host.store.read() as conn:
            assert (
                await conn.execute_fetchall("SELECT run_receipt FROM job WHERE id=?", (same_id,))
            )[0][0] == reclaimed["run_receipt"]


@pytest.mark.parametrize("successor", [False, True])
async def test_failed_run_writer_fault_keeps_revocation_and_successor_state(
    tmp_path, settings, monkeypatch, successor
):
    import sqlite3

    from test_vector_lifecycle import LifecycleGateway

    from spunto.ai.vectors import VectorIndex

    install(tmp_path)
    async with harness(tmp_path, settings.model_copy(update={"data_dir": tmp_path})) as host:
        await host.enable(PLUGIN, PERMISSIONS)
        await host.enqueue(PLUGIN, "follow")
        row = await host._claim()
        stale = Ctx(host, PLUGIN)
        async with host.store.write() as conn:
            await conn.execute(
                "UPDATE job SET status='dead',last_error='plugin_job_failed' WHERE id=?",
                (row["id"],),
            )
        gateway = LifecycleGateway()
        index = VectorIndex(host.store, gateway)
        host.gateway, host.vectors = gateway, index
        host.manifests[PLUGIN]["permissions"].append("ai.embed")
        host.states[PLUGIN]["granted"].append("ai.embed")
        assert host._set_vector_routes()
        assert index.profile_for(PLUGIN) == "local"
        vector_generation = index.generation
        owner_fenced = asyncio.Event()
        owner_task = None
        real_set_routes = host._set_vector_routes

        def observe_owner():
            changed = real_set_routes()
            if host.states[PLUGIN]["state"] == "disabled":
                owner_fenced.set()
            return changed

        monkeypatch.setattr(host, "_set_vector_routes", observe_owner)

        async def writer_fault(conn):
            nonlocal owner_task
            # The caller has validated the dead receipt and executed real SQLite UPDATE.
            assert host.states[PLUGIN]["state"] == "failed"
            assert index.profile_for(PLUGIN) is None
            updated = (
                await conn.execute_fetchall("SELECT state FROM plugin WHERE id=?", (PLUGIN,))
            )[0][0]
            assert updated == "failed"
            if successor:
                owner_task = asyncio.create_task(host.disable(PLUGIN))
                await asyncio.wait_for(owner_fenced.wait(), 2)
            raise sqlite3.OperationalError("Synthetic SQLite writer fault")

        monkeypatch.setattr(host, "_bump", writer_fault)
        try:
            with pytest.raises(sqlite3.OperationalError, match="Synthetic SQLite writer fault"):
                await host._state(
                    PLUGIN,
                    "failed",
                    "plugin_circuit_open",
                    generation=stale.generation,
                    run=row,
                    failures=5,
                )
            assert host.generations[PLUGIN] > stale.generation
            with pytest.raises(ContextError, match="plugin_disabled"):
                stale.check()
            assert index.profile_for(PLUGIN) is None and index.generation > vector_generation
            if successor:
                # Restore the real metadata bump so the pending owner's writer can finish.
                monkeypatch.undo()
                await asyncio.wait_for(owner_task, 2)
                assert host.states[PLUGIN]["state"] == "disabled"
                assert host.states[PLUGIN]["switch"] == "off"
            else:
                assert host.states[PLUGIN]["state"] == "failed"
                assert host.states[PLUGIN]["last_error"] == "plugin_circuit_open"
                async with host.store.read() as conn:
                    assert (
                        await conn.execute_fetchall(
                            "SELECT state FROM plugin WHERE id=?", (PLUGIN,)
                        )
                    )[0][0] == "enabled"
            with pytest.raises(ContextError, match="plugin_disabled"):
                Ctx(host, PLUGIN).check()
            assert not gateway.dimension_calls
        finally:
            if owner_task and not owner_task.done():
                owner_task.cancel()
                await asyncio.gather(owner_task, return_exceptions=True)
