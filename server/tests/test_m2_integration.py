"""M2 host boundaries through real SQLite, note APIs and MCP over HTTP."""

import asyncio
import json
import sys
from types import ModuleType

import pytest

from spunto.auth import tokens
from spunto.auth.tokens import Principal
from spunto.core.attachments import Attachments
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.host import ContextError, Ctx, Host, Plugin
from spunto.mcp.output import MAX_CHARS

pytestmark = pytest.mark.anyio
OWNER = Principal("fixture-owner", "session", "Owner", "full", private_ok=True)


@pytest.fixture
async def toy(server, monkeypatch):
    await server.services.host.close()
    plugin = Plugin("boundary")

    @plugin.mcp_tool("read")
    async def read(ctx, note_id: str):
        return await ctx.notes.get(note_id)

    @plugin.mcp_tool("write")
    async def write(ctx, body: str):
        return await ctx.notes.create(body)

    @plugin.mcp_tool("raw")
    async def raw(ctx, body: str = "outside fixture"):
        return {"content": body, "nested": [body]}

    module = ModuleType("spunto.plugins.boundary")
    module.plugin = plugin
    monkeypatch.setitem(sys.modules, module.__name__, module)
    manifest = {
        "id": "boundary",
        "name": "Boundary",
        "version": "1.0.0",
        "api": "0.4",
        "kind": "server",
        "description": "Contract fixture",
        "entry": module.__name__ + ":plugin",
        "permissions": [
            "notes.read",
            "notes.create",
            "notes.update_own",
            "annotate",
            "search.text",
            "attachments.read",
            "attachments.write",
            "mcp",
        ],
        "creates": "machine",
        "contributes": {
            "meta": {"title": "Meta"},
            "panel": {"title": "Panel", "mode": "stored"},
            "mcp_tools": [
                {"name": "read", "description": "Read", "read_only": True},
                {"name": "raw", "description": "Raw", "read_only": True},
                {
                    "name": "write",
                    "description": "Write",
                    "read_only": False,
                    "annotations": {"destructiveHint": True, "idempotentHint": False},
                },
            ],
        },
    }
    host = Host(
        server.services.store,
        server.services.settings,
        catalog=[manifest],
        notes_factory=PluginNotes,
        attachments_factory=Attachments,
    )
    await host.discover()
    await host.enable("boundary", permissions=manifest["permissions"])
    server.services.host = host
    server.services.store.plugin_host = host
    server.services.store.event_sink = host.emit
    yield server, host
    await server.services.store.drain_events()


async def rpc(server, token, method, params=None):
    response = await server.client.post(
        "/mcp",
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/json, text/event-stream",
            "MCP-Protocol-Version": "2025-06-18",
        },
        json={"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}},
    )
    assert response.status_code == 200, response.text
    return response.json()["result"]


async def test_plugin_tools_scope_hints_hiding_disabled_and_output_budget(toy):
    server, host = toy
    _, read = await tokens.create(host.store, name="Reader", client="fixture", scope="read")
    _, write = await tokens.create(host.store, name="Writer", client="fixture", scope="write")
    tools = {item["name"]: item for item in (await rpc(server, write, "tools/list"))["tools"]}
    assert tools["boundary_write"]["annotations"]["destructiveHint"] is True
    assert tools["boundary_write"]["annotations"]["idempotentHint"] is False
    assert tools["boundary_read"]["inputSchema"]["additionalProperties"] is False
    assert "boundary_write" not in {
        t["name"] for t in (await rpc(server, read, "tools/list"))["tools"]
    }
    result = await rpc(server, read, "tools/call", {"name": "boundary_raw", "arguments": {}})
    payload = json.loads(result["content"][0]["text"])
    assert "external_data" in payload["data"]["content"]
    assert "external_data" in payload["data"]["nested"][0]
    large = await rpc(
        server, read, "tools/call", {"name": "boundary_raw", "arguments": {"body": "x" * 50000}}
    )
    assert len(large["content"][0]["text"]) <= MAX_CHARS
    async with host.store.write() as conn:
        await conn.execute(
            "INSERT INTO setting VALUES ('hidden_mcp_tools',?)", ('["boundary_raw"]',)
        )
    assert "boundary_raw" not in {
        t["name"] for t in (await rpc(server, read, "tools/list"))["tools"]
    }
    assert (await rpc(server, read, "tools/call", {"name": "boundary_raw"}))["isError"]
    await host.disable("boundary")
    assert not any(
        t["name"].startswith("boundary_") for t in (await rpc(server, write, "tools/list"))["tools"]
    )


async def test_caller_visibility_readonly_writes_and_generation_fences(toy):
    server, host = toy
    domain = Notes(host.store, OWNER)
    private = await domain.create("Private fixture", private=True)
    public = await domain.create("Public fixture")
    readonly = Ctx(host, "boundary", principal=Principal("reader", "pat", "Reader", "read"))
    assert await readonly.notes.get(private["id"]) is None
    for operation in (
        lambda: readonly.notes.create("Denied"),
        lambda: readonly.notes.update_own(public["id"], "Denied"),
        lambda: readonly.notes.annotate(public["id"], "meta", {"type": "text", "text": "Denied"}),
        lambda: readonly.notes.set_search_text(public["id"], "Denied"),
        lambda: readonly.attachments.write(b"Denied"),
    ):
        with pytest.raises(ContextError, match="read_only_token"):
            await operation()
    ctx = Ctx(host, "boundary")
    machine = await ctx.notes.create("Machine fixture")
    assert machine["machine"]
    assert not (await ctx.notes.query())["notes"][-1]["machine"]
    edited = await domain.edit(machine["id"], "Adopted by owner", machine["version"])
    assert not edited["machine"]
    assert await ctx.notes.update_own(machine["id"], "Cannot overwrite") is None
    await host.disable("boundary")
    await host.enable("boundary", permissions=host.manifests["boundary"]["permissions"])
    with pytest.raises(ContextError, match="plugin_disabled"):
        await ctx.notes.create("Old context")


async def test_derived_output_visibility_fts_and_cleanup(toy):
    server, host = toy
    domain = Notes(host.store, OWNER)
    note = await domain.create("Plain fixture")
    ctx = Ctx(host, "boundary")
    await ctx.notes.annotate(note["id"], "meta", {"type": "text", "text": "Derived fixture"})
    await ctx.notes.set_search_text(note["id"], "ornitorinco")
    assert len((await domain.get(note["id"]))["annotations"]) == 1
    assert (await domain.search("ornitorinco"))["hits"][0]["id"] == note["id"]
    await host.disable("boundary")
    assert (await domain.get(note["id"]))["annotations"] == []
    # Queue workers remove stale extra text; the enable state is always checked by serialization.
    await host.start()
    async with asyncio.timeout(3):
        while (await domain.search("ornitorinco"))["hits"]:  # noqa: ASYNC110
            await asyncio.sleep(0.01)
    await host.enable("boundary", permissions=host.manifests["boundary"]["permissions"])
    await domain.flags(note["id"], {"trashed": True})
    await Ctx(host, "boundary").notes.annotate(note["id"], "meta", None)
    await domain.delete(note["id"])
    async with host.store.read() as conn:
        assert not await conn.execute_fetchall(
            "SELECT * FROM annotation WHERE note_id=?", (note["id"],)
        )
        assert await conn.execute_fetchall("SELECT * FROM tombstone WHERE kind='annotation'")


async def test_default_host_postcommit_hook_and_plugin_view(server):
    host = server.services.host
    assert all(item["switch"] == "off" for item in host.catalog() if item["default"] == "off")
    assert all(
        item["state"] == "waiting" for item in host.catalog() if item["default"] == "on-with-ai"
    )
    await host.enable("tasks", permissions=host.manifests["tasks"]["permissions"])
    note = await Notes(host.store, OWNER).create("- [ ] One\n- [x] Two")
    await host.store.drain_events()
    # Advance the persisted hook deadline, keeping execution on the real worker.
    async with host.store.write() as conn:
        await conn.execute("UPDATE job SET run_after=0 WHERE plugin_id='tasks'")
    host._wake.set()
    async with asyncio.timeout(3):
        while not (await Notes(host.store, OWNER).get(note["id"]))["annotations"]:  # noqa: ASYNC110
            await asyncio.sleep(0.01)
    _, token = await tokens.create(host.store, name="Reader", client="fixture", scope="read")
    response = await server.client.get(
        "/api/v1/notes?view=tasks/tasks", headers={"Authorization": "Bearer " + token}
    )
    assert response.status_code == 200, response.text
    assert response.json()["notes"][0]["id"] == note["id"]
    await host.disable("tasks")
    response = await server.client.get(
        "/api/v1/notes?view=tasks/tasks", headers={"Authorization": "Bearer " + token}
    )
    assert response.status_code == 404


async def test_attachment_download_visibility_and_active_content_is_downloaded(toy):
    server, host = toy
    file = await Ctx(host, "boundary").attachments.write(
        b'<svg onload="alert(1)"></svg>', filename="fixture.svg", mime="image/svg+xml"
    )
    note = await Notes(host.store, OWNER).create(file["snippet"], private=True)
    _, read = await tokens.create(host.store, name="Reader", client="fixture", scope="read")
    _, private = await tokens.create(
        host.store, name="Owner reader", client="fixture", scope="read", private_ok=True
    )
    response = await server.client.get(file["url"], headers={"Authorization": "Bearer " + read})
    assert response.status_code == 404
    response = await server.client.get(file["url"], headers={"Authorization": "Bearer " + private})
    assert response.status_code == 200
    assert response.headers["content-type"] == "application/octet-stream"
    assert response.headers["content-disposition"].startswith("attachment;")
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.content.startswith(b"<svg")
    await Notes(host.store, OWNER).flags(note["id"], {"trashed": True})
    assert (
        await server.client.get(file["url"], headers={"Authorization": "Bearer " + private})
    ).status_code == 404


async def test_plugin_markdown_rendering_and_invalid_schema_does_not_break_core_tools(toy):
    server, host = toy
    note = await Notes(host.store, OWNER).create("Fixture")
    ctx = Ctx(host, "boundary")
    await ctx.notes.annotate(
        note["id"],
        "panel",
        {
            "type": "markdown",
            "text": "<script>alert(1)</script> ![x](https://outside.example/x.png)",
        },
    )
    rendered = await Notes(host.store, OWNER).get(note["id"], render=True)
    html = rendered["annotations"][0]["payload"]["html"]
    assert "<script" not in html and "<img" not in html
    handler = host.plugins["boundary"].handlers["tool"]["read"]
    handler.__annotations__["note_id"] = "UnknownFixtureType"
    _, read = await tokens.create(host.store, name="Reader", client="fixture", scope="read")
    listed = await rpc(server, read, "tools/list")
    assert "get_notes" in {tool["name"] for tool in listed["tools"]}
    assert "boundary_read" not in {tool["name"] for tool in listed["tools"]}
    assert not (await rpc(server, read, "tools/call", {"name": "list_labels"})).get("isError")


async def test_postcommit_events_are_absent_on_rollback_and_visible_after_commit(server):
    events = []

    async def sink(event, **kwargs):
        async with server.services.store.read() as conn:
            assert await conn.execute_fetchall(
                "SELECT value FROM setting WHERE key='fixture-event'"
            )
        events.append(event)

    store = server.services.store
    original = store.event_sink
    store.event_sink = sink
    with pytest.raises(RuntimeError, match="rollback"):
        async with store.write() as conn:
            await conn.execute("INSERT INTO setting VALUES ('fixture-event','1')")
            store.emit("note.saved")
            raise RuntimeError("rollback")
    await store.drain_events()
    assert not events
    async with store.write() as conn:
        await conn.execute("INSERT INTO setting VALUES ('fixture-event','1')")
        store.emit("note.saved")
    await store.drain_events()
    assert events == ["note.saved"]
    store.event_sink = original


async def test_unknown_plugin_environment_pin_refuses_startup(settings):
    from spunto.errors import OperatorError
    from spunto.main import startup

    settings.plugins = "+resruface"
    with pytest.raises(OperatorError, match="SPUNTO_PLUGINS.*resruface"):
        async with startup(settings):
            pytest.fail("unknown pin accepted")


async def test_m2_plugin_storage_and_outputs_survive_verified_restore(settings):
    from spunto.jobs import backup
    from spunto.main import startup

    async with startup(settings) as services:
        host = services.host
        await host.enable("tasks", permissions=host.manifests["tasks"]["permissions"])
        await Ctx(host, "tasks").storage.kv.set("fixture", {"kept": True})
        note = await Notes(services.store, OWNER).create("- [ ] Fixture")
        await Ctx(host, "tasks").notes.annotate(
            note["id"], "meta", {"type": "text", "text": "Fixture output"}
        )
        snapshot = await asyncio.to_thread(backup.backup_now, settings)
        await Ctx(host, "tasks").storage.kv.set("fixture", {"kept": False})
    report = await asyncio.to_thread(backup.restore, settings, snapshot)
    assert "Notes restored: 1;" in report
    async with startup(settings) as restored:
        assert restored.host.states["tasks"]["state"] == "enabled"
        assert await Ctx(restored.host, "tasks").storage.kv.get("fixture") == {"kept": True}
        # The restored backfill may refresh derived text before this read.
        assert (await Notes(restored.store, OWNER).get(note["id"]))["annotations"][0][
            "plugin_id"
        ] == "tasks"


async def test_resurface_calendar_uses_configured_timezone(server):
    from datetime import UTC, datetime

    from spunto.plugins import resurface

    host = server.services.host
    # This test invokes the handler itself; its scheduled worker must not race
    # that unleased call or consume the daily slot before the notes are seeded.
    await host.close()
    host.settings.tz = "Europe/Rome"
    host.clock = lambda: int(datetime(2026, 10, 13, 23, 30, tzinfo=UTC).timestamp() * 1000)
    await host.enable("resurface", permissions=host.manifests["resurface"]["permissions"])
    local_anniversary = await Notes(host.store, OWNER).create(
        "Local anniversary",
        created_at=int(datetime(2020, 10, 14, 12, tzinfo=UTC).timestamp() * 1000),
    )
    await Notes(host.store, OWNER).create(
        "UTC yesterday", created_at=int(datetime(2020, 10, 13, 12, tzinfo=UTC).timestamp() * 1000)
    )
    ctx = Ctx(host, "resurface")
    await resurface.daily(ctx)
    first = (await Notes(host.store, OWNER).get(local_anniversary["id"]))["annotations"]
    assert first[0]["payload"]["items"][0]["reason"] == "On this day"
    assert await ctx.storage.kv.get("daily") == {
        "day": "2026-10-14",
        "note_id": local_anniversary["id"],
    }
    await resurface.daily(ctx)
    assert (await Notes(host.store, OWNER).get(local_anniversary["id"]))["annotations"] == first
