"""Shared manual plugin workflows on real SQLite, including competing writers."""

import asyncio

import pytest
from test_host import bind, manifest

from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.host import ContextError, Ctx, ManifestError, Plugin, validate_manifest
from spunto.host.testing import harness

pytestmark = pytest.mark.anyio
OWNER = Principal("owner", "session", "Owner", "write", private_ok=True)


@pytest.fixture
async def workflow(tmp_path, settings, monkeypatch):
    plugin = Plugin("manual-example")

    @plugin.command("create")
    async def create(ctx, body, private=False, source_versions=None, request_id=None):
        note = await ctx.notes.create(
            body, private=private, source_versions=source_versions, request_id=request_id
        )
        return {"type": "notes", "ids": [note["id"]]}

    value = bind(
        monkeypatch,
        plugin,
        api="1.0",
        creates="owner",
        permissions=["notes.read", "notes.create"],
        contributes={"commands": [{"id": "create", "title": "Create", "where": ["palette"]}]},
    )
    async with harness(
        tmp_path / "workflow",
        settings,
        catalog=[value, manifest("other")],
        notes_factory=PluginNotes,
    ) as host:
        await host.enable(plugin.id, value["permissions"])
        await host.enable("other", [])
        yield host


def context(host, **kwargs):
    return Ctx(host, "manual-example", principal=OWNER, **kwargs)


async def test_record_cas_has_one_winner_and_preserves_typed_json(workflow):
    first, second = context(workflow).storage.records, context(workflow).storage.records
    initial = {"revision": 1, "done": True}
    assert await first.compare_and_set("entries", "one", None, initial)
    assert not await first.compare_and_set("entries", "one", None, {"revision": 9})
    assert not await first.compare_and_set("entries", "one", {"revision": 1, "done": 1}, {})
    winners = await asyncio.gather(
        first.compare_and_set("entries", "one", {"done": True, "revision": 1}, {"revision": 2}),
        second.compare_and_set("entries", "one", initial, {"revision": 3}),
    )
    assert sum(winners) == 1
    assert (await first.get("entries", "one"))["revision"] in {2, 3}
    assert (
        await Ctx(workflow, "other", principal=OWNER).storage.records.get("entries", "one") is None
    )


async def test_record_delete_is_fenced_and_pagination_never_repeats(workflow):
    records = context(workflow).storage.records
    for name in ("a", "b", "c"):
        await records.put("entries", name, {"name": name})
    assert [row["id"] for row in await records.list("entries", limit=2)] == ["a", "b"]
    assert [row["id"] for row in await records.list("entries", after="b", limit=2)] == ["c"]
    assert not await records.compare_and_delete("entries", "a", {"name": "changed"})
    assert await records.compare_and_delete("entries", "a", {"name": "a"})
    assert not await records.compare_and_delete("entries", "a", {"name": "a"})
    with pytest.raises(ManifestError):
        await records.list("entries", after="_host_cursor")


async def test_record_cas_rejects_read_scope_oversize_and_expired_generation(workflow):
    readonly = Principal("reader", "pat", "Reader", "read")
    records = Ctx(workflow, "manual-example", principal=readonly).storage.records
    with pytest.raises(ContextError):
        await records.compare_and_set("entries", "one", None, {})
    with pytest.raises(ContextError):
        await records.compare_and_delete("entries", "one", {})
    records = context(workflow).storage.records
    with pytest.raises(ManifestError):
        await records.compare_and_set("entries", "one", None, "x" * 65536)
    await workflow.disable("manual-example")
    await workflow.enable("manual-example", ["notes.read", "notes.create"])
    with pytest.raises(ContextError):
        await records.compare_and_set("entries", "one", None, {})


@pytest.mark.parametrize("kind", ["session", "device"])
async def test_manual_notes_keep_owner_provenance_and_idempotency(workflow, kind):
    principal = Principal("owner", kind, "Owner", "write", private_ok=True)
    args = {"body": "A manual decision", "private": True, "request_id": "manual-request"}
    first = await workflow.invoke_command("manual-example", "create", args, principal=principal)
    second = await workflow.invoke_command("manual-example", "create", args, principal=principal)
    assert first == second
    note = await Notes(workflow.store, principal).get(first["ids"][0])
    assert note["origin"] == "owner" and note["machine"] is False and note["private"] is True
    assert note["source"]["kind"] == "plugin" and note["source"]["ref"] == "manual-example"


@pytest.mark.parametrize(
    "operation", [None, "route:POST", "tool:create", "job:create", "hook:save"]
)
async def test_manual_note_creation_requires_an_actual_owner_command(workflow, operation):
    with pytest.raises(ContextError, match="owner_command_required"):
        await context(workflow, operation=operation).notes.create("Not a manual command")


async def test_manual_mode_refuses_agent_unattended_and_import_overrides(workflow):
    writer = Principal("writer", "pat", "Writer", "write", private_ok=True)
    for ctx in (
        Ctx(workflow, "manual-example", operation="command:create"),
        Ctx(workflow, "manual-example", principal=writer, operation="command:create"),
        context(workflow, operation="command:create", unattended=True),
    ):
        with pytest.raises(ContextError, match="owner_command_required"):
            await ctx.notes.create("Not an owner entry")
    ctx = context(workflow, operation="command:create")
    for overrides in ({"private": "yes"}, {"source_ref": "other"}, {"pinned": True}):
        with pytest.raises(ContextError, match="manual_creation_fields_invalid"):
            await ctx.notes.create("Invalid manual fields", **overrides)
    with pytest.raises(ContextError, match="import_only_field"):
        await ctx.notes.create("Invalid backdate", created_at=0)


async def test_private_source_race_cannot_publish_a_public_manual_snapshot(workflow):
    notes = Notes(workflow.store, OWNER)
    source = await notes.create("Synthetic private decision context")
    await notes.flags(source["id"], {"private": True})
    current = await notes.get(source["id"])
    versions = {source["id"]: current["version"]}
    ctx = context(workflow, operation="command:create")
    with pytest.raises(ContextError, match="private_source_requires_private_output"):
        await ctx.notes.create("A derived manual entry", source_versions=versions)
    private = await ctx.notes.create(
        "A derived manual entry", private=True, source_versions=versions
    )
    assert private["private"] is True


def test_owner_creation_manifest_cannot_launder_ai_output():
    value = manifest(permissions=["notes.create"], creates="owner", api="1.0")
    assert validate_manifest(value)["creates"] == "owner"
    with pytest.raises(ManifestError, match="AI permissions"):
        validate_manifest({**value, "permissions": ["notes.create", "ai.chat"]})
    with pytest.raises(ManifestError, match="reviewed edits"):
        validate_manifest({**value, "permissions": ["notes.create", "notes.update_own"]})
