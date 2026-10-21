"""Bundled non-AI plugins and shared import contracts against real SQLite."""

import json
from datetime import UTC, datetime
from pathlib import Path

import pytest

from spunto.auth import tokens
from spunto.auth.tokens import Principal
from spunto.core.attachments import Attachments
from spunto.core.export import _rewrite_links
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.host import Ctx, Host
from spunto.host.plugin import ContextError
from spunto.host.vocabulary import validate_output
from spunto.plugins import agent_inbox, resurface, tasks
from spunto.plugins._notes import iter_notes

pytestmark = pytest.mark.anyio
OWNER = Principal("fixture-owner", "session", "Owner", "full", private_ok=True)
NOW = int(datetime(2026, 10, 14, 9, tzinfo=UTC).timestamp() * 1000)


@pytest.fixture
async def plugin_host(server):
    host = Host(
        server.services.store,
        server.services.settings,
        notes_factory=PluginNotes,
        attachments_factory=Attachments,
        clock=lambda: NOW,
        catalog=[Path(module.__file__).parent for module in (tasks, agent_inbox, resurface)],
    )
    await host.discover()
    for plugin_id in host.manifests:
        assert host.manifests[plugin_id]["default"] == "off"
        await host.enable(plugin_id, permissions=host.manifests[plugin_id]["permissions"])
    try:
        yield host
    finally:
        await host.close()


async def rows(host, sql, args=()):
    async with host.store.read() as conn:
        return [dict(row) for row in await conn.execute_fetchall(sql, args)]


async def test_firstparty_manifests_budgets_and_disabled_context_contract(plugin_host):
    host = plugin_host
    assert set(host.manifests) == {"agent-inbox", "tasks", "resurface"}
    for plugin_id, manifest in host.manifests.items():
        contributions = manifest.get("contributes", {})
        assert len(contributions.get("views", [])) <= 1
        assert len(contributions.get("mcp_tools", [])) <= 3
        assert len(contributions.get("commands", [])) <= 3
        ctx = Ctx(host, plugin_id)
        await ctx.storage.kv.set("fixture", {"retained": True})
        with pytest.raises(ContextError, match="permission_denied"):
            await ctx.fetch("https://synthetic.example")
        await host.disable(plugin_id)
        with pytest.raises(ContextError, match="plugin_disabled"):
            await ctx.storage.kv.get("fixture")
        await host.enable(plugin_id, permissions=manifest["permissions"])
        assert await Ctx(host, plugin_id).storage.kv.get("fixture") == {"retained": True}
        with pytest.raises(ContextError, match="plugin_disabled"):
            await ctx.storage.kv.get("fixture")


async def test_tasks_meta_counts_core_open_tasks_and_drops_stale_token(plugin_host):
    host = plugin_host
    domain = Notes(host.store, OWNER)
    note = await domain.create("- [ ] Call\n- [x] Done\n```\n- [ ] Example\n```")
    ctx = Ctx(host, "tasks")
    await tasks.update(ctx, note["id"])
    annotations = await rows(host, "SELECT payload FROM annotation WHERE plugin_id='tasks'")
    assert json.loads(annotations[0]["payload"]) == {"type": "text", "text": "1 of 2 done"}
    assert [n["id"] async for n in iter_notes(ctx, view="tasks")] == [note["id"]]
    edited = await domain.edit(note["id"], "plain note", note["version"])
    await tasks.update(ctx, edited["id"])
    assert await rows(host, "SELECT * FROM annotation WHERE plugin_id='tasks'") == []


async def test_agent_inbox_matches_latest_pat_and_review_is_synced(plugin_host):
    host = plugin_host
    domain = Notes(host.store, OWNER)
    note = await domain.create("Owner thought")
    credential_id, _ = await tokens.create(
        host.store, name="Fixture agent", client="fixture", scope="write"
    )
    agent = Notes(host.store, Principal(credential_id, "pat", "Fixture", "write"))
    note = await agent.append(note["id"], "agent addition")
    ctx = Ctx(host, "agent-inbox")
    assert [n["id"] async for n in iter_notes(ctx, view="reviewed")] == [note["id"]]
    validate_output(await agent_inbox.mark_reviewed(ctx, note["id"]))
    assert [n["id"] async for n in iter_notes(ctx, view="reviewed")] == []
    note = await domain.edit(note["id"], "owner revision", note["version"])
    await agent_inbox.changed(ctx, note["id"], note["version"])
    assert len(await rows(host, "SELECT * FROM annotation WHERE plugin_id='agent-inbox'")) == 1
    note = await agent.append(note["id"], "new agent addition")
    await agent_inbox.changed(ctx, note["id"], note["version"])
    assert [n["id"] async for n in iter_notes(ctx, view="reviewed")] == [note["id"]]


async def test_resurface_anniversary_machine_exclusion_daily_idempotency(plugin_host):
    host = plugin_host
    domain = Notes(host.store, OWNER)
    anniversary = await domain.create(
        "Anniversary",
        created_at=int(datetime(2020, 10, 14, 10, 11, 12, 123000, tzinfo=UTC).timestamp() * 1000),
    )
    await domain.create(
        "Other old note", created_at=int(datetime(2020, 1, 1, tzinfo=UTC).timestamp() * 1000)
    )
    machine = await domain.create(
        "Machine anniversary", created_at=int(datetime(2021, 10, 14, tzinfo=UTC).timestamp() * 1000)
    )
    async with host.store.write() as conn:
        await conn.execute("UPDATE note SET machine=1 WHERE id=?", (machine["id"],))
    ctx = Ctx(host, "resurface")
    await resurface.daily(ctx)
    slip = await rows(host, "SELECT * FROM annotation WHERE plugin_id='resurface'")
    assert len(slip) == 1 and slip[0]["note_id"] == anniversary["id"]
    assert json.loads(slip[0]["payload"])["items"][0]["reason"] == "On this day"
    before = slip[0]["change_seq"]
    await resurface.daily(ctx)
    assert (await rows(host, "SELECT * FROM annotation WHERE plugin_id='resurface'"))[0][
        "change_seq"
    ] == before
    assert (await ctx.storage.records.get("shown", anniversary["id"]))["last_shown"] == NOW


async def test_resurface_never_picks_an_archived_note(plugin_host):
    host = plugin_host
    domain = Notes(host.store, OWNER)
    archived = await domain.create(
        "Archived anniversary",
        created_at=int(datetime(2020, 10, 14, tzinfo=UTC).timestamp() * 1000),
    )
    await domain.flags(archived["id"], {"archived": True})
    other = await domain.create(
        "Other old note", created_at=int(datetime(2020, 1, 1, tzinfo=UTC).timestamp() * 1000)
    )
    await resurface.daily(Ctx(host, "resurface"))
    slip = await rows(host, "SELECT * FROM annotation WHERE plugin_id='resurface'")
    assert [row["note_id"] for row in slip] == [other["id"]]
    assert json.loads(slip[0]["payload"])["items"][0]["reason"] == "From your notes"


def test_import_links_preserve_markdown_code_escapes_and_plain_mentions():
    relative = "/attachments/fixture.png"
    absolute = "https://source.example/attachments/fixture.png"
    original = (
        f"[Real]({relative})\n\n"
        f"    [Indented]({relative})\n\n"
        f"```md\n[Fenced]({relative})\n```\n\n"
        f"`[Inline]({relative})`\n\n"
        f"\\[Escaped]({relative})\n\n"
        f"Plain {relative}\n\n"
        f"[Reference][r]\n\n[r]: {relative}\n\n"
        f"[Delimited](<{absolute}>)\n\n"
        f"[Absolute reference][a]\n\n[a]: <{absolute}>\n\n"
        f"<{absolute}>\n"
    )
    rewritten = _rewrite_links(original, {relative: "/a/fixture", absolute: "/a/absolute"})
    assert "[Real](/a/fixture)" in rewritten
    assert f"    [Indented]({relative})" in rewritten
    assert f"[Fenced]({relative})" in rewritten
    assert f"`[Inline]({relative})`" in rewritten
    assert f"\\[Escaped]({relative})" in rewritten
    assert f"Plain {relative}" in rewritten
    assert "[r]: /a/fixture" in rewritten
    assert f"[{absolute}](/a/absolute)" in rewritten
    assert "[Delimited](</a/absolute>)" in rewritten
    assert "[a]: </a/absolute>" in rewritten
    from spunto.core.render import render_markdown

    assert render_markdown(rewritten).count('href="/a/absolute"') == 3


async def test_tasks_ignore_multiline_inline_code_in_view_and_progress(plugin_host):
    host = plugin_host
    code = "`\n[ ] literal code\n[x] literal done\n`"
    domain = Notes(host.store, OWNER)
    note = await domain.create(code)
    assert note["open_tasks"] == 0 and tasks.task_total(code) == 0
    ctx = Ctx(host, "tasks")
    await tasks.update(ctx, note["id"])
    assert (await domain.get(note["id"]))["annotations"] == []
    assert (await ctx.notes.query(view="tasks"))["notes"] == []
    body = "- [ ] Real task\n- [x] Done task\n\n" + code
    changed = await domain.edit(note["id"], body, note["version"])
    assert changed["open_tasks"] == 1 and tasks.task_total(body) == 2
    await tasks.update(ctx, note["id"])
    assert (await domain.get(note["id"]))["annotations"][0]["payload"]["text"] == "1 of 2 done"
