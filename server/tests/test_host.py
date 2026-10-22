"""Host contracts against the actual SQLite schema, with no mocked store."""

import asyncio
import json
import sys
import threading
from pathlib import Path
from types import ModuleType, SimpleNamespace

import pytest

from spunto.host import Ctx, ManifestError, Plugin, read_manifest, validate_manifest
from spunto.host.plugin import ContextError
from spunto.host.testing import FakeClock, harness
from spunto.host.vocabulary import validate_annotation, validate_output


def manifest(plugin_id="example", **values):
    return {
        "id": plugin_id,
        "name": "Example",
        "version": "1.0.0",
        "api": "0.4",
        "kind": "server",
        "description": "Contract example",
        "permissions": [],
        **values,
    }


def bind(monkeypatch, plugin, **values):
    module = ModuleType("spunto.plugins." + plugin.id.replace("-", "_"))
    module.plugin = plugin
    monkeypatch.setitem(sys.modules, module.__name__, module)
    return manifest(plugin.id, entry=module.__name__ + ":plugin", **values)


async def finish(host):
    async with asyncio.timeout(3):
        while True:
            async with host.store.read() as conn:
                rows = await conn.execute_fetchall(
                    "SELECT count(*) FROM job WHERE status='running'"
                )
            if not host.running and not rows[0][0]:
                return
            await asyncio.sleep(0.005)


def test_closed_manifest_and_budgets(tmp_path):
    value = manifest()
    for forbidden in ("toolbar", "icon", "notification", "colour"):
        with pytest.raises(ManifestError):
            validate_manifest({**value, forbidden: "x"})
    with pytest.raises(ManifestError):
        validate_manifest({**value, "permissions": ["notes.write"]})
    with pytest.raises(ManifestError):
        validate_manifest({**value, "permissions": ["notes.create"]})
    commands = [{"id": "do-" + str(i), "title": "Do", "where": ["palette"]} for i in range(4)]
    with pytest.raises(ManifestError):
        validate_manifest({**value, "contributes": {"commands": commands}})
    with pytest.raises(ManifestError):
        validate_manifest(
            {
                **value,
                "settings": {
                    "type": "object",
                    "properties": {"x": {"type": "string", "pattern": "x"}},
                },
            }
        )
    directory = tmp_path / "plugin"
    directory.mkdir()
    (directory / "plugin.toml").write_text(
        "\n".join(f"{key} = {json.dumps(val)}" for key, val in value.items())
    )
    (directory / "__init__.py").write_text("raise RuntimeError('must never import')")
    assert read_manifest(directory)["id"] == "example"


def test_vocabulary_rejects_unknown_fields_and_caps():
    validate_output([{"type": "text", "text": "Hello"}, {"type": "notes", "ids": ["note"]}])
    validate_annotation("member:reviewed", {})
    for value in (
        {"type": "text", "text": "x" * 281},
        {"type": "text", "text": "ok", "expanded": True},
        {"type": "html", "html": "<script>"},
    ):
        with pytest.raises(ManifestError):
            validate_output(value)
    with pytest.raises(ManifestError):
        validate_annotation("panel", [{"type": "markdown", "text": "é" * 3000}])


def test_plugin_failure_logs_include_safe_diagnostics_without_error_messages(caplog):
    from spunto.host.runtime import _log_failure

    class SyntheticDatabaseError(Exception):
        sqlstate = "42803"

    _log_failure(
        "label-suggestions",
        "hook:note.saved",
        SyntheticDatabaseError("synthetic private note and provider-key canary"),
    )
    assert "plugin=label-suggestions" in caplog.text
    assert "error=SyntheticDatabaseError sqlstate=42803" in caplog.text
    assert "synthetic private note" not in caplog.text
    assert "provider-key canary" not in caplog.text


@pytest.mark.anyio
async def test_waiting_vector_index_pauses_plugin_jobs_without_spending_retry_budget(
    tmp_path, settings, monkeypatch
):
    from spunto.ai.vectors import VectorUnavailable

    plugin = Plugin("vector-example")

    @plugin.job("compare")
    async def compare(ctx):
        raise VectorUnavailable()

    value = bind(monkeypatch, plugin)
    async with harness(tmp_path, settings, catalog=[value]) as host:
        await host.enable(plugin.id, [])
        await host._queue(plugin.id, "job:compare", {})
        row = await host._claim()
        await host._run(row)
        async with host.store.read() as conn:
            jobs = await conn.execute_fetchall(
                "SELECT status,attempts,last_error FROM job WHERE plugin_id=?", (plugin.id,)
            )
        assert [tuple(row) for row in jobs] == [("queued", 0, "vectors_unavailable")]
        assert host.states[plugin.id]["state"] == "enabled"
        assert host.failures.get(plugin.id, 0) == 0


@pytest.mark.anyio
async def test_lifecycle_secrets_generation_and_isolated_storage(tmp_path, monkeypatch):
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    schema = {
        "type": "object",
        "properties": {
            "token": {"type": "string", "x-secret": True},
            "language": {"type": "string", "x-scope": "device"},
            "enabled": {"type": "boolean", "default": True},
        },
    }
    monkeypatch.setenv("SPUNTO_PLUGIN_EXAMPLE_TOKEN", "host-contract-sentinel")
    async with harness(
        tmp_path,
        settings,
        catalog=[
            manifest(settings=schema),
            manifest("second"),
            manifest(
                "ai-wait", permissions=["ai.chat"], requires=["ai.chat"], default="on-with-ai"
            ),
        ],
    ) as host:
        assert host.states["ai-wait"]["state"] == "waiting"
        assert host.states["ai-wait"]["last_error"] == "Needs a chat model. AI is off."
        assert host.manifest()["plugins"] == []
        await host.enable("example", [])
        await host.enable("second", [])
        ctx, other = Ctx(host, "example"), Ctx(host, "second")
        assert ctx.settings["token"] == "host-contract-sentinel"
        assert "host-contract-sentinel" not in json.dumps(host.catalog())
        assert "host-contract-sentinel" not in json.dumps(host.manifest())
        for setting in ("token", "language"):
            with pytest.raises(ManifestError):
                await host.set_settings("example", {setting: "unsafe"})
        await ctx.storage.kv.set("same", {"one": 1})
        assert await other.storage.kv.get("same") is None
        await ctx.storage.records.put("items", "one", {"hello": True}, key="group")
        assert await other.storage.records.list("items") == []
        assert (await ctx.storage.records.list("items", key="group"))[0]["data"] == {"hello": True}
        with pytest.raises(ManifestError):
            await ctx.storage.kv.set("large", "x" * 65536)
        with pytest.raises(ContextError):
            ctx.check("notes.read")
        rev = host.plugins_rev
        await host.disable("example")
        assert host.plugins_rev > rev
        assert "example" not in [p["id"] for p in host.manifest()["plugins"]]
        await host.enable("example", [])
        with pytest.raises(ContextError):
            await ctx.storage.kv.get("same")
        fresh = Ctx(host, "example")
        assert await fresh.storage.kv.get("same") == {"one": 1}
        await host.delete_data("example")
        assert await Ctx(host, "example").storage.kv.get("same") is None
        assert await Ctx(host, "example").storage.records.list("items") == []


@pytest.mark.anyio
async def test_default_pins_and_new_permissions_wait(tmp_path):
    settings = SimpleNamespace(data_dir=tmp_path, plugins="+example,-second", tz="UTC")
    async with harness(
        tmp_path, settings, catalog=[manifest(), manifest("second", default="on")]
    ) as host:
        assert host.states["example"]["state"] == "enabled"
        assert host.states["second"]["switch"] == "off"
        assert host.states["example"]["pinned_by_env"]
        with pytest.raises(ContextError):
            await host.disable("example")
    settings.plugins = ""
    async with harness(
        tmp_path,
        settings,
        catalog=[
            manifest(permissions=["notes.read"]),
            manifest("second"),
            manifest("upgrade-new", default="on"),
        ],
    ) as host:
        assert host.states["example"]["state"] == "waiting"
        assert host.states["upgrade-new"]["state"] == "available"
        await host.enable("example", ["notes.read"])
        assert host.states["example"]["state"] == "enabled"


@pytest.mark.anyio
async def test_queue_coalescing_disable_and_no_attempt(tmp_path, monkeypatch):
    clock = FakeClock()
    plugin = Plugin("example")
    started, release = asyncio.Event(), asyncio.Event()

    @plugin.job("work")
    async def work(ctx, value):
        started.set()
        await release.wait()
        await ctx.storage.kv.set("result", value)

    value = bind(monkeypatch, plugin)
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    async with harness(tmp_path, settings, catalog=[value], clock=clock) as host:
        await host.enable("example", [])
        first = await host.enqueue("example", "work", {"value": 1})
        assert first == await host.enqueue("example", "work", {"value": 2})
        await host.start()
        await asyncio.wait_for(started.wait(), 2)
        await host.disable("example")
        await finish(host)
        async with host.store.read() as conn:
            rows = await conn.execute_fetchall("SELECT status,attempts FROM job")
        assert [(r[0], r[1]) for r in rows] == [("queued", 0)]
        release.set()
        await host.enable("example", [])
        async with asyncio.timeout(2):
            while await Ctx(host, "example").storage.kv.get("result") != 2:  # noqa: ASYNC110
                await asyncio.sleep(0.005)


@pytest.mark.anyio
async def test_thread_result_is_discarded_after_disable(tmp_path, monkeypatch):
    plugin = Plugin("example")
    entered, release = threading.Event(), threading.Event()

    def blocked():
        entered.set()
        release.wait(3)
        return "stale"

    @plugin.command("thread")
    async def command(ctx):
        return await ctx.to_thread(blocked)

    value = bind(
        monkeypatch,
        plugin,
        contributes={"commands": [{"id": "thread", "title": "Thread", "where": ["palette"]}]},
    )
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    async with harness(tmp_path, settings, catalog=[value]) as host:
        await host.enable("example", [])
        task = asyncio.create_task(host.invoke_command("example", "thread"))
        try:
            async with asyncio.timeout(2):
                while not entered.is_set():  # noqa: ASYNC110
                    await asyncio.sleep(0.005)
            await host.disable("example")
            await host.enable("example", [])
            release.set()
            with pytest.raises(ContextError):
                await task
        finally:
            release.set()


@pytest.mark.anyio
async def test_failed_migration_does_not_stop_other_plugins(tmp_path, monkeypatch):
    plugin = Plugin("example")

    @plugin.migrate(0, 1)
    async def migration(ctx):
        await ctx.storage.kv.set("resume", 1)
        raise RuntimeError("content must never reach status")

    value = bind(monkeypatch, plugin, data_version=1)
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    async with harness(tmp_path, settings, catalog=[value, manifest("second")]) as host:
        await host.enable("example", [])
        await host.enable("second", [])
        assert host.states["example"]["state"] == "waiting"
        await host.start()
        async with asyncio.timeout(3):
            while host.states["example"]["state"] != "failed":  # noqa: ASYNC110
                await asyncio.sleep(0.005)
        assert host.states["second"]["state"] == "enabled"
        assert host.states["example"]["last_error"] == "plugin_migration_failed"
        assert list((tmp_path / "snapshots" / "plugin-example").rglob("*.db"))
        async with host.store.read() as conn:
            rows = await conn.execute_fetchall("SELECT data_version FROM plugin WHERE id='example'")
        assert rows[0][0] == 0


@pytest.mark.anyio
async def test_shared_view_fixtures_run_on_real_schema(tmp_path):
    from spunto.auth.tokens import Principal
    from spunto.core.notes import Notes
    from spunto.host import compile_filter

    fixture = json.loads((Path(__file__).parents[2] / "spec/fixtures/views.json").read_text())
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    async with harness(tmp_path, settings, catalog=[manifest()]) as host:
        owner = Principal("owner", "session", "Owner", "full", private_ok=True)
        notes = Notes(host.store, owner)
        names = {}
        for item in fixture["notes"]:
            note = await notes.create(
                item["body"],
                created_at=fixture["now"] - item["age_days"] * 86400000,
                source={"kind": item.get("source", "web"), "ref": item.get("source_ref")},
            )
            names[note["id"]] = item["key"]
            async with host.store.write() as conn:
                await conn.execute(
                    "UPDATE note SET pinned=?,archived_at=?,updated_by=? WHERE id=?",
                    (
                        int(item.get("pinned", False)),
                        fixture["now"] if item.get("archived") else None,
                        "agent" if item.get("latest_agent") else "owner",
                        note["id"],
                    ),
                )
                for member in item.get("members", []):
                    await conn.execute(
                        "INSERT INTO annotation(plugin_id,note_id,kind,payload,change_seq,"
                        "updated_at) "
                        "VALUES(?,?,?,'{}',0,?)",
                        ("example", note["id"], "member:" + member, fixture["now"]),
                    )
        async with host.store.write() as conn:
            await conn.execute(
                "INSERT INTO credential(id,kind,name,scope,created_at) "
                "VALUES('agent','pat','Agent','write',?)",
                (fixture["now"],),
            )
        for case in fixture["cases"]:
            sql, args = compile_filter(
                case["filter"], plugin_id=fixture["plugin_id"], now=fixture["now"]
            )
            async with host.store.read() as conn:
                rows = await conn.execute_fetchall("SELECT n.id FROM note n WHERE " + sql, args)  # noqa: S608
            assert sorted(names[row[0]] for row in rows) == sorted(case["expected"])


def test_shared_vocabulary_fixtures():
    for path in (Path(__file__).parents[2] / "spec/fixtures/vocabulary").glob("*.json"):
        validate_output(json.loads(path.read_text()))


@pytest.mark.anyio
async def test_broken_manifest_does_not_stop_catalog(tmp_path):
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    async with harness(
        tmp_path,
        settings,
        catalog=[manifest(toolbar="forbidden"), manifest("second", default="on")],
    ) as host:
        assert host.states["example"]["state"] == "failed"
        assert host.states["second"]["state"] == "enabled"
        assert [p["id"] for p in host.manifest()["plugins"]] == ["second"]


@pytest.mark.anyio
async def test_core_schedule_missed_once_and_queue_lease_recovery(tmp_path):
    clock = FakeClock()
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    calls = []

    async def backup():
        calls.append(clock())

    async with harness(tmp_path, settings, catalog=[], clock=clock) as host:
        host.register_core("backup", backup, at="03:30")
        await host._schedules()
        async with host.store.read() as conn:
            assert not await conn.execute_fetchall("SELECT id FROM job")
        clock.advance(86400000 * 3)
        await host._schedules()
        await host._schedules()
        async with host.store.write() as conn:
            rows = await conn.execute_fetchall("SELECT * FROM job")
            assert len(rows) == 1
            await conn.execute(
                "UPDATE job SET status='running',lease_until=? WHERE id=?",
                (clock() - 1, rows[0]["id"]),
            )
        await host.start()
        async with asyncio.timeout(2):
            while not calls:  # noqa: ASYNC110
                await asyncio.sleep(0.005)
        await finish(host)
        assert len(calls) == 1


@pytest.mark.anyio
async def test_retry_backoff_and_five_failure_circuit(tmp_path, monkeypatch):
    plugin = Plugin("example")

    @plugin.job("fail")
    async def fail(ctx):
        raise RuntimeError("secret note content must not be saved")

    clock = FakeClock()
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    async with harness(
        tmp_path, settings, catalog=[bind(monkeypatch, plugin)], clock=clock
    ) as host:
        await host.enable("example", [])
        job_id = await host.enqueue("example", "fail")
        for attempt in range(1, 6):
            row = await host._claim()
            assert row["id"] == job_id
            await host._run(row)
            async with host.store.read() as conn:
                rows = await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (job_id,))
            assert rows[0]["attempts"] == attempt
            assert rows[0]["last_error"] == "plugin_job_failed"
            assert rows[0]["run_after"] == clock() + 30000 * 2 ** (attempt - 1)
            clock.advance(30000 * 2 ** (attempt - 1))
        assert rows[0]["status"] == "dead"
        assert host.states["example"]["state"] == "failed"
        assert host.manifest()["plugins"] == []


@pytest.mark.anyio
async def test_owner_queue_provenance_survives_persistence(tmp_path, monkeypatch):
    plugin = Plugin("example")

    @plugin.command("queue")
    async def queue(ctx):
        await ctx.enqueue("work")
        return {"type": "text", "text": "Queued"}

    @plugin.job("work")
    async def work(ctx):
        await ctx.storage.kv.set("unattended", ctx.unattended)

    value = bind(
        monkeypatch,
        plugin,
        contributes={"commands": [{"id": "queue", "title": "Queue", "where": ["palette"]}]},
    )
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    async with harness(tmp_path, settings, catalog=[value]) as host:
        await host.enable("example", [])
        await host.invoke_command("example", "queue")
        await host._run(await host._claim())
        assert await Ctx(host, "example").storage.kv.get("unattended") is False


@pytest.mark.anyio
async def test_concurrent_enable_disable_is_serialized(tmp_path):
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    async with harness(tmp_path, settings, catalog=[manifest()]) as host:
        original = host._state
        entered, release = asyncio.Event(), asyncio.Event()

        async def gated(plugin_id, state, error=None, **kwargs):
            if state == "enabled":
                entered.set()
                await release.wait()
            await original(plugin_id, state, error, **kwargs)

        host._state = gated
        enabling = asyncio.create_task(host.enable("example", []))
        await asyncio.wait_for(entered.wait(), 2)
        disabling = asyncio.create_task(host.disable("example"))
        await asyncio.sleep(0)
        assert not disabling.done()
        release.set()
        await asyncio.gather(enabling, disabling)
        assert (host.states["example"]["switch"], host.states["example"]["state"]) == (
            "off",
            "disabled",
        )
        with pytest.raises(ContextError):
            Ctx(host, "example").check()


@pytest.mark.anyio
async def test_current_snapshot_failure_is_not_masked_by_old_backup(tmp_path, monkeypatch):
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    old = tmp_path / "snapshots/plugin-example/old.db"
    old.parent.mkdir(parents=True)
    old.write_bytes(b"existing backup")

    def failed_snapshot(*args):
        raise OSError("read-only disk")

    monkeypatch.setattr("spunto.store.snapshot.write_snapshot", failed_snapshot)
    async with harness(tmp_path, settings, catalog=[manifest()]) as host:
        with pytest.raises(OSError):
            await host._snapshot("example")


@pytest.mark.anyio
async def test_retry_and_restart_preserve_newer_coalesced_arguments(tmp_path, monkeypatch):
    plugin = Plugin("example")
    values = []

    @plugin.job("work")
    async def work(ctx, value):
        values.append(value)

    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    async with harness(tmp_path, settings, catalog=[bind(monkeypatch, plugin)]) as host:
        await host.enable("example", [])
        old = await host.enqueue("example", "work", {"value": "old"})
        async with host.store.write() as conn:
            await conn.execute("UPDATE job SET status='dead' WHERE id=?", (old,))
        new = await host.enqueue("example", "work", {"value": "new"})
        await host.enable("example", [])
        async with host.store.read() as conn:
            rows = await conn.execute_fetchall("SELECT id FROM job")
        assert [row[0] for row in rows] == [new]
        async with host.store.write() as conn:
            await conn.execute(
                "UPDATE job SET status='running',lease_until=? WHERE id=?",
                (host.clock() + 300000, new),
            )
        await host.start()
        async with asyncio.timeout(2):
            while not values:  # noqa: ASYNC110
                await asyncio.sleep(0.005)
        await finish(host)
        assert values == ["new"]


@pytest.mark.anyio
async def test_fetch_own_host_and_internal_options_are_closed(tmp_path):
    from spunto.net import FetchError

    settings = SimpleNamespace(
        data_dir=tmp_path, plugins="", tz="UTC", public_url="https://spunto.example.org"
    )
    async with harness(tmp_path, settings, catalog=[manifest(permissions=["net.fetch"])]) as host:
        await host.enable("example", ["net.fetch"])
        ctx = Ctx(host, "example")
        for option in (
            "own_hosts",
            "own_addresses",
            "resolver",
            "transport",
            "permissions",
            "check",
        ):
            with pytest.raises(ContextError, match="fetch_option_denied"):
                await ctx.fetch("https://public.example.org", **{option: []})
        with pytest.raises(FetchError):
            await ctx.fetch("https://spunto.example.org/a")
        async with host.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT kind,client FROM credential WHERE id='plugin:example'"
            )
        assert (rows[0]["kind"], rows[0]["client"]) == ("plugin", "example")


@pytest.mark.anyio
async def test_internal_budgets_are_inaccessible_to_plugin_storage(tmp_path, settings):
    from spunto.core.plugin_notes import PluginNotes
    from spunto.host.plugin import ContextError

    settings.data_dir = tmp_path
    value = manifest(permissions=["notes.create"], creates="machine")
    async with harness(tmp_path, settings, catalog=[value], notes_factory=PluginNotes) as host:
        await host.enable("example", ["notes.create"])
        ctx = Ctx(host, "example", unattended=True)
        for number in range(5):
            await ctx.notes.create(f"Machine note {number}")
        for reserved in (
            "_host_created:2026-10-14",
            "_host_slip_day",
            "host:consecutive_failures",
            "host:schedule:daily",
        ):
            for method in (ctx.storage.kv.get, ctx.storage.kv.delete):
                with pytest.raises(ManifestError):
                    await method(reserved)
            with pytest.raises(ManifestError):
                await ctx.storage.kv.set(reserved, 0)
            with pytest.raises(ManifestError):
                await ctx.storage.records.put("ordinary", "ordinary", {}, key=reserved)
        with pytest.raises(ContextError):
            await ctx.notes.create("Sixth note is forbidden")
        async with host.store.read() as conn:
            rows = await conn.execute_fetchall("SELECT count(*) FROM note")
        assert rows[0][0] == 5


@pytest.mark.anyio
async def test_pat_context_is_capped_and_cannot_queue_or_write_storage(tmp_path, monkeypatch):
    from spunto.auth.tokens import Principal

    plugin = Plugin("example")
    observed = []

    @plugin.mcp_tool("run")
    async def run(ctx):
        observed.append((ctx.unattended, ctx.principal.private_ok, ctx.principal.external_ok))
        await ctx.storage.kv.set("test", True)
        return {"ok": True}

    @plugin.job("work")
    async def work(ctx):
        pass

    value = bind(
        monkeypatch,
        plugin,
        permissions=["mcp"],
        contributes={
            "mcp_tools": [
                {"name": "run", "description": "Exercise caller permissions", "read_only": True}
            ]
        },
    )
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    async with harness(tmp_path, settings, catalog=[value]) as host:
        await host.enable("example", ["mcp"])
        read = Principal("agent", "pat", "Agent", "read", private_ok=False, external_ok=False)
        with pytest.raises(ContextError, match="write_scope_required"):
            await host.invoke_tool("example", "run", principal=read)
        assert observed == [(True, False, False)]
        with pytest.raises(ContextError, match="write_scope_required"):
            await Ctx(host, "example", principal=read).enqueue("work")
        writer = Principal("agent", "pat", "Agent", "write", private_ok=False, external_ok=False)
        await host.invoke_tool("example", "run", principal=writer)
        assert observed[-1] == (True, False, False)
        with pytest.raises(ContextError, match="agent_jobs_unavailable"):
            await Ctx(host, "example", principal=writer).enqueue("work")


@pytest.mark.anyio
async def test_fetch_accounting_stores_hosts_only(tmp_path, monkeypatch):
    async def fetch(url, **kwargs):
        kwargs["on_destination"]("public.example.org")
        kwargs["on_destination"]("redirect.example.org")
        return {"ok": True}

    monkeypatch.setattr("spunto.net.guarded_fetch", fetch)
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    async with harness(tmp_path, settings, catalog=[manifest(permissions=["net.fetch"])]) as host:
        await host.enable("example", ["net.fetch"])
        await Ctx(host, "example").fetch("https://public.example.org/private-path?secret=hidden")
        async with host.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT key,value FROM plugin_kv WHERE key LIKE 'host:fetch:%'"
            )
        assert {row["key"]: row["value"] for row in rows} == {
            "host:fetch:public.example.org": "1",
            "host:fetch:redirect.example.org": "1",
        }
        assert "private-path" not in json.dumps([dict(row) for row in rows])


@pytest.mark.anyio
async def test_failure_retry_coalesces_with_newer_queued_job(tmp_path, monkeypatch):
    plugin = Plugin("example")

    @plugin.job("work")
    async def work(ctx, value):
        raise RuntimeError("temporary failure")

    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    async with harness(tmp_path, settings, catalog=[bind(monkeypatch, plugin)]) as host:
        await host.enable("example", [])
        older = await host.enqueue("example", "work", {"value": "old"})
        claimed = await host._claim()
        latest = await host.enqueue("example", "work", {"value": "latest"})
        assert latest != older
        await host._run(claimed)
        async with host.store.read() as conn:
            rows = await conn.execute_fetchall("SELECT id,status,args FROM job")
        assert len(rows) == 1
        assert (rows[0]["id"], rows[0]["status"]) == (latest, "queued")
        assert json.loads(rows[0]["args"])["value"] == "latest"


@pytest.mark.anyio
async def test_contract_harness_detects_blocking_after_an_initial_yield(tmp_path, monkeypatch):
    import time

    plugin = Plugin("example")

    @plugin.command("block")
    async def block(ctx):
        await asyncio.sleep(0)
        time.sleep(0.16)  # noqa: ASYNC251 -- intentional contract violation
        return {"type": "text", "text": "Done"}

    value = bind(
        monkeypatch,
        plugin,
        contributes={"commands": [{"id": "block", "title": "Block", "where": ["palette"]}]},
    )
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    with pytest.raises(AssertionError, match="blocked the event loop"):
        async with harness(tmp_path, settings, catalog=[value]) as host:
            await host.enable("example", [])
            await host.invoke_command("example", "block")


def test_mcp_annotations_are_boolean_and_match_access_contract():
    base = manifest(
        permissions=["mcp"],
        contributes={
            "mcp_tools": [{"name": "read", "description": "Read data", "read_only": True}]
        },
    )
    validate_manifest(base)
    for hints in (
        {"readOnlyHint": False},
        {"readOnlyHint": "true"},
        {"destructiveHint": 1},
        {"openWorldHint": "false"},
    ):
        tool = {**base["contributes"]["mcp_tools"][0], "annotations": hints}
        with pytest.raises(ManifestError):
            validate_manifest({**base, "contributes": {"mcp_tools": [tool]}})
    tool = {
        **base["contributes"]["mcp_tools"][0],
        "annotations": {
            "readOnlyHint": True,
            "destructiveHint": False,
            "idempotentHint": True,
            "openWorldHint": False,
        },
    }
    validate_manifest({**base, "contributes": {"mcp_tools": [tool]}})


@pytest.mark.anyio
async def test_capture_context_binding_hook_compatibility_and_strict_commands(
    tmp_path, settings, monkeypatch
):
    from spunto.auth.tokens import Principal
    from spunto.core.notes import Notes
    from spunto.core.plugin_notes import PluginNotes

    plugin = Plugin("capture-contract")
    observed = []

    @plugin.on("note.saved")
    async def saved(ctx, note_id, note_version=None):
        observed.append((ctx.capture_note_id, ctx.operation, note_id, note_version))
        assert (await ctx.capture.note(note_id))["id"] == note_id

    @plugin.on("note.purged")
    async def purged(ctx, note_id, private=None):
        observed.append((ctx.capture_note_id, ctx.operation, note_id, private))
        assert await ctx.capture.note(note_id) is None

    @plugin.command("capture")
    async def command(ctx, note_id):
        return {
            "type": "text",
            "text": ctx.operation + ":" + str(bool(await ctx.capture.note(note_id))),
        }

    owner = Principal("owner", "session", "Owner", "full", private_ok=True)
    value = bind(
        monkeypatch,
        plugin,
        permissions=["net.fetch"],
        contributes={"commands": [{"id": "capture", "title": "Capture", "where": []}]},
    )
    async with harness(tmp_path, settings, catalog=[value], notes_factory=PluginNotes) as host:
        await host.enable(plugin.id, permissions=["net.fetch"])
        note = await Notes(host.store, owner).create("Synthetic scoped note")
        job_id = await host._queue(
            plugin.id,
            "hook:note.saved",
            {
                "note_id": note["id"],
                "note_version": note["version"],
                "attachment_id": "ignored additive metadata",
            },
            note_id=note["id"],
        )
        async with host.store.read() as conn:
            row = dict((await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (job_id,)))[0])
        await host._run(row)
        assert observed[0] == (note["id"], "hook:note.saved", note["id"], note["version"])
        result = await host.invoke_command(
            plugin.id, "capture", {"note_id": note["id"]}, principal=owner
        )
        assert result["text"] == "command:capture:True"
        with pytest.raises(TypeError):
            await host.invoke_command(
                plugin.id, "capture", {"note_id": note["id"], "unexpected": True}, principal=owner
            )
        agent = Principal("agent", "pat", "Agent", "write")
        with pytest.raises(ContextError, match="owner_required"):
            await host.invoke_command(
                plugin.id, "capture", {"note_id": note["id"]}, principal=agent
            )
        async with host.store.write() as conn:
            await conn.execute("DELETE FROM note WHERE id=?", (note["id"],))
        job_id = await host._queue(
            plugin.id,
            "hook:note.purged",
            {
                "note_id": note["id"],
                "note_version": note["version"],
                "private": False,
                "change_seq": 1,
            },
            note_id=note["id"],
        )
        async with host.store.read() as conn:
            row = dict((await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (job_id,)))[0])
        await host._run(row)
        assert observed[-1] == (note["id"], "hook:note.purged", note["id"], False)


@pytest.mark.anyio
async def test_configured_network_origins_include_ports_and_legacy_url(
    tmp_path, settings, monkeypatch
):
    import spunto.net

    plugin = Plugin("origin-contract")
    calls = []

    async def fetch(url, **kwargs):
        calls.append((url, kwargs))
        return "synthetic response"

    async def pre_send():
        return None

    monkeypatch.setattr(spunto.net, "guarded_fetch", fetch)
    value = bind(
        monkeypatch,
        plugin,
        permissions=["net.out:<configured>"],
        settings={
            "type": "object",
            "properties": {
                "urls": {"type": "array", "items": {"type": "string"}, "default": []},
                "url": {"type": "string", "default": ""},
            },
        },
    )
    async with harness(tmp_path, settings, catalog=[value]) as host:
        await host.enable(plugin.id, permissions=value["permissions"])
        await host.set_settings(
            plugin.id,
            {
                "urls": ["https://example.test:9443/hook", "https://other.test/hook"],
                "url": "https://legacy.test:8443/hook",
            },
        )
        await Ctx(host, plugin.id).fetch(
            "https://example.test:9443/hook", method="POST", body=b"{}", pre_send=pre_send
        )
        assert calls[0][1]["pre_send"] is pre_send
        assert calls[0][1]["check"].__self__.plugin_id == plugin.id
        assert calls[0][1]["allowed_origins"] == [
            "https://example.test:9443/hook",
            "https://other.test/hook",
            "https://legacy.test:8443/hook",
        ]
        assert set(calls[0][1]["configured_hosts"]) == {"example.test", "other.test", "legacy.test"}
        assert set(host.catalog()[0]["network_destinations"]) == {
            "example.test",
            "other.test",
            "legacy.test",
        }
        with pytest.raises(ContextError, match="fetch_option_denied"):
            await Ctx(host, plugin.id).fetch(
                "https://example.test", allowed_origins=["https://arbitrary.test"]
            )
        with pytest.raises(ContextError, match="fetch_option_denied"):
            await Ctx(host, plugin.id).fetch("https://example.test", pre_send="invalid")
        with pytest.raises(ContextError, match="fetch_option_denied"):
            await Ctx(host, plugin.id).fetch("https://example.test", check=pre_send)


@pytest.mark.anyio
async def test_plugin_note_pages_batch_metadata_and_keep_borrowed_sources(
    tmp_path, settings, monkeypatch
):
    import aiosqlite

    from spunto.auth.tokens import Principal
    from spunto.core.notes import Notes
    from spunto.core.plugin_notes import PluginNotes

    value = manifest(permissions=["notes.read", "net.fetch"])
    async with harness(tmp_path, settings, catalog=[value], notes_factory=PluginNotes) as host:
        await host.enable("example", ["notes.read", "net.fetch"])
        owner = Notes(host.store, Principal("owner", "session", "Owner", "full", private_ok=True))
        created = [
            await owner.create(f"Synthetic plugin page {index} #label") for index in range(12)
        ]
        await owner.create("Synthetic private plugin page", private=True)
        async with host.store.write() as conn:
            await conn.execute(
                "UPDATE note SET import_key='synthetic-import' WHERE id=?", (created[-1]["id"],)
            )
        await host.store.drain_events()
        queries = []
        original = aiosqlite.Connection.execute_fetchall

        async def counted(conn, sql, *args, **kwargs):
            queries.append(sql)
            return await original(conn, sql, *args, **kwargs)

        monkeypatch.setattr(aiosqlite.Connection, "execute_fetchall", counted)
        ctx = Ctx(host, "example")
        page = await ctx.notes.query(limit=10)
        assert len(page["notes"]) == 10 and page["next_cursor"]
        assert len(queries) == 5
        assert page["notes"][0]["id"] == created[-1]["id"]
        assert page["notes"][0]["import_key"] == "synthetic-import"
        assert all(note["labels"] == ["label"] and not note["private"] for note in page["notes"])
        assert ctx._borrowed_notes == {note["id"]: note["body"] for note in page["notes"]}
