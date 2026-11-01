"""Library metadata counts use the same visibility and lifecycle as note reads."""

from pathlib import Path

import pytest

from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.host import ContextError, Ctx, Host, read_manifest, validate_output
from spunto.host.testing import FakeClock
from spunto.plugins import library_stats

pytestmark = pytest.mark.anyio
OWNER = Principal("stats-owner", "session", "Owner", "full", private_ok=True)
DAY = 86_400_000


@pytest.fixture
async def host(server):
    host = Host(
        server.services.store,
        server.services.settings,
        notes_factory=PluginNotes,
        catalog=[Path(library_stats.__file__).parent],
        clock=FakeClock(now=1_790_000_000_000),
    )
    await host.discover()
    await host.enable("library-stats", permissions=["notes.read"])
    try:
        yield host
    finally:
        await host.close()


def ctx(host, principal=OWNER):
    return Ctx(host, "library-stats", principal=principal, operation="command:summary")


async def test_empty_manifest_and_read_only_result(host):
    manifest = read_manifest(Path(library_stats.__file__).parent)
    assert manifest["default"] == "off"
    assert manifest["permissions"] == ["notes.read"]
    assert manifest["contributes"]["commands"] == [
        {"id": "summary", "title": "Library stats", "where": ["palette"], "online": True}
    ]
    assert await ctx(host).notes.stats(machine=True) == dict.fromkeys(
        ["saved", "active", "archived", "pinned", "unlabeled", "open_tasks", "created_30_days"], 0
    )
    tables = ("note", "note_revision", "plugin_record", "plugin_kv", "annotation")
    async with host.store.read() as conn:
        before = await conn.execute_fetchall("SELECT key,value FROM meta ORDER BY key")
        before_counts = {}
        for table in tables:
            before_counts[table] = (
                await conn.execute_fetchall(f"SELECT count(*) FROM {table}")  # noqa: S608
            )[0][0]
    result = await host.invoke_command("library-stats", "summary", principal=OWNER)
    validate_output(result)
    assert "Saved notes: 0" in str(result)
    async with host.store.read() as conn:
        after = await conn.execute_fetchall("SELECT key,value FROM meta ORDER BY key")
        assert [tuple(row) for row in before] == [tuple(row) for row in after]
        for table in tables:
            assert (
                await conn.execute_fetchall(f"SELECT count(*) FROM {table}")  # noqa: S608
            )[0][0] == before_counts[table]


async def test_counts_status_labels_tasks_and_rolling_time_boundaries(host, monkeypatch):
    notes, now = Notes(host.store, OWNER), host.clock()
    first = await notes.create("#work\n- [ ] first\n- [ ] second", created_at=now)
    archive = await notes.create("- [ ] archived task", created_at=now - 31 * DAY)
    await notes.create("Private body\n- [ ] private task", private=True, created_at=now - DAY)
    external = await notes.create("External body", created_at=now - 2 * DAY)
    machine = await notes.create("Machine body #machine", created_at=now - 3 * DAY)
    await notes.create("Future body", created_at=now + 1)
    await notes.create("Window starts here", created_at=now - 30 * DAY)
    await notes.create("Outside the window", created_at=now - 30 * DAY - 1)
    trash = await notes.create("- [ ] trashed task", created_at=now)
    async with host.store.write() as conn:
        await conn.execute("UPDATE note SET pinned=1 WHERE id=?", (first["id"],))
        await conn.execute(
            "UPDATE note SET archived_at=?,pinned=1 WHERE id=?", (now, archive["id"])
        )
        await conn.execute("UPDATE note SET trashed_at=? WHERE id=?", (now, trash["id"]))
        await conn.execute("UPDATE note SET origin='external' WHERE id=?", (external["id"],))
        await conn.execute("UPDATE note SET machine=1 WHERE id=?", (machine["id"],))

    async def no_bodies(*args, **kwargs):
        raise AssertionError("Stats must not serialize note content")

    monkeypatch.setattr(Notes, "_serialize_many", no_bodies)
    assert await ctx(host).notes.stats(machine=True) == {
        "saved": 8,
        "active": 7,
        "archived": 1,
        "pinned": 1,
        "unlabeled": 5,
        "open_tasks": 3,
        "created_30_days": 5,
    }
    assert (await ctx(host).notes.stats())["saved"] == 7
    restricted = Principal("stats-restricted", "session", "Owner", "full", external_ok=False)
    counts = await ctx(host, restricted).notes.stats(machine=True)
    assert counts["saved"] == 6 and counts["created_30_days"] == 3
    assert counts["unlabeled"] == 3 and counts["open_tasks"] == 2
    result = await library_stats.summary(ctx(host, restricted))
    validate_output(result)
    assert "Saved notes: 6" in str(result)
    assert "Private body" not in str(result) and "External body" not in str(result)


async def test_stats_require_read_permission_and_current_generation(host):
    reader = ctx(host).notes
    host.states["library-stats"]["granted"] = []
    with pytest.raises(ContextError, match="permission_denied"):
        await reader.stats()
    host.states["library-stats"]["granted"] = ["notes.read"]
    with pytest.raises(ValueError, match="boolean"):
        await reader.stats(machine=1)
    await host.disable("library-stats")
    with pytest.raises(ContextError, match="plugin_disabled"):
        await reader.stats()


async def test_stats_preserve_ai_private_and_machine_fences(host):
    notes = Notes(host.store, OWNER)
    await notes.create("Private source", private=True)
    machine = await notes.create("Machine source")
    async with host.store.write() as conn:
        await conn.execute("UPDATE note SET machine=1 WHERE id=?", (machine["id"],))
    host.manifests["library-stats"]["permissions"].append("ai.chat")
    reader = ctx(host).notes
    assert (await reader.stats())["saved"] == 0
    with pytest.raises(ContextError, match="machine_note_forbidden"):
        await reader.stats(machine=True)
