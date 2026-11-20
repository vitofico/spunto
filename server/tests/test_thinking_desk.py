"""Manual histories and collision desks through real SQLite and the plugin host."""

import uuid
from pathlib import Path

import pytest

from spunto.api.errors import Problem
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.host import Ctx, Host, read_manifest
from spunto.host.language import use_language
from spunto.host.plugin import ContextError
from spunto.host.vocabulary import validate_output
from spunto.plugins import thinking_desk as desk

pytestmark = pytest.mark.anyio
OWNER = Principal("desk-owner", "session", "Owner", "full", private_ok=True)


@pytest.fixture
async def host(server):
    host = Host(
        server.services.store,
        server.services.settings,
        notes_factory=PluginNotes,
        catalog=[Path(desk.__file__).parent],
    )
    await host.discover()
    await host.enable("thinking-desk", permissions=host.manifests["thinking-desk"]["permissions"])
    try:
        yield host
    finally:
        await host.close()


def ctx(host, principal=OWNER):
    return Ctx(host, "thinking-desk", principal=principal, operation="command:fixture")


async def create(host, mode="history", note=None):
    desk_id = str(uuid.uuid4())
    result = await desk.edit(
        ctx(host),
        title="Synthetic topic",
        mode=mode,
        request_id=desk_id,
        note_id=note["id"] if note else None,
    )
    validate_output(result)
    return desk_id


async def add(host, desk_id, note):
    old = await desk.load(ctx(host), desk_id)
    result = await desk.sources(
        ctx(host),
        desk_id,
        old["revision"],
        add=desk.caption(note, 1),
        source_choices=[f"{note['id']}@{note['version']}"],
    )
    validate_output(result)


def markdown(result):
    return "\n".join(item["text"] for item in result["items"] if item["type"] == "markdown")


def action(result, key, value):
    item = next(item for item in result["items"] if item.get("args", {}).get(key) == value)
    return item["args"]


def form_schema(result):
    return next(item["schema"] for item in result["items"] if item["type"] == "form")


@pytest.mark.parametrize("locale", ["en", "it", "es", "fr", "de"])
async def test_guided_desk_actions_and_copy_in_each_language(host, locale):
    notes = Notes(host.store, OWNER)
    first = await notes.create(
        "# Pruning raspberry plants\n\nRemove branches that receive too little light.", private=True
    )
    second = await notes.create("Plan the product launch")
    with use_language(locale):
        workspace = ctx(host)
        landing = await desk.desk(workspace, note_id=first["id"])
        validate_output(landing)
        options = [x["args"] for x in landing["items"] if x.get("id") == "edit"]
        assert [args["mode"] for args in options] == ["history", "collision"]
        start = options[1]
        new = await desk.edit(workspace, **start)
        validate_output(new)
        schema = form_schema(new)
        assert list(schema["properties"]) == ["title", "request_id"]
        assert "default" not in schema["properties"]["title"]
        desk_id = schema["properties"]["request_id"]["default"]
        created = await desk.edit(workspace, **start, title="Smaller launch", request_id=desk_id)
        validate_output(created)
        assert first["id"] in markdown(created)
        assert not any(x.get("args", {}).get("action") == "snapshot" for x in created["items"])
        picker = await desk.sources(
            workspace,
            **created["items"][2]["args"],
            picker=True,
            add_id=second["id"],
            add_version=second["version"],
        )
        validate_output(picker)
        assert form_schema(picker)["properties"]["selected_ids"]["default"] == [
            first["id"],
            second["id"],
        ]
        selected = await desk.desk(workspace, desk_id)
        write = action(selected, "action", "write")
        editor = await desk.edit(workspace, **write)
        validate_output(editor)
        assert list(form_schema(editor)["properties"]) == ["thinking"]
        references = next(item["items"] for item in editor["items"] if item["type"] == "notes")
        assert references[0] == {
            "id": first["id"],
            "reason": "Remove branches that receive too little light.",
        }
        assert references[1]["id"] == second["id"]
        assert "Remove branches" not in str(await desk.load(workspace, desk_id))
        written = await desk.edit(workspace, **write, thinking="Trim the optional launch tasks.")
        validate_output(written)
        assert "Trim the optional launch tasks." in markdown(written)
        save_copy = action(written, "action", "snapshot")
        saved = await desk.edit(workspace, **save_copy)
        validate_output(saved)
        snapshot_id = saved["items"][1]["ids"][0]
        snapshot = await notes.get(snapshot_id)
        assert snapshot["private"] and not snapshot["machine"]
        assert "Trim the optional launch tasks." in snapshot["body"]
        assert first["id"] in snapshot["body"] and second["id"] in snapshot["body"]
        assert desk.PROMPTS[0] not in snapshot["body"]
        returned = await desk.desk(workspace, **saved["items"][2]["args"])
        assert "Trim the optional launch tasks." in markdown(returned)
        assert action(await desk.desk(workspace), "desk_id", desk_id)


async def test_manifest_and_forms(host):
    manifest = read_manifest(Path(desk.__file__).parent)
    assert manifest["default"] == "off"
    assert manifest["permissions"] == ["notes.read", "notes.create"]
    validate_output(await desk.desk(ctx(host)))
    validate_output(await desk.edit(ctx(host)))
    desk_id = await create(host)
    validate_output(await desk.sources(ctx(host), desk_id, 1))
    validate_output(await desk.relate(ctx(host), desk_id, 1))


async def test_desk_picker_keeps_committed_revision_and_skips_legacy_search(host):
    notes = Notes(host.store, OWNER)
    first = await notes.create("Initial source")
    second = await notes.create("New source")
    desk_id = await create(host, note=first)

    async def picker(revision, **args):
        result = await desk.sources(ctx(host), desk_id, revision, picker=True, **args)
        validate_output(result)
        props = next(
            item["schema"]["properties"] for item in result["items"] if item["type"] == "form"
        )
        assert "query" not in props and "source_choices" not in props
        return props

    props = await picker(1)
    assert props["selected_ids"]["default"] == [first["id"]]
    assert props["expected_revision"]["default"] == 1
    newer = await notes.edit(second["id"], "Updated before selection", second["version"])
    with pytest.raises(Problem, match="changed"):
        await picker(1, add_id=second["id"], add_version=second["version"])
    props = await picker(1, add_id=newer["id"], add_version=newer["version"])
    assert props["selected_ids"]["default"] == [first["id"], newer["id"]]
    assert props["expected_revision"]["default"] == 2
    with pytest.raises(Problem, match="changed"):
        await picker(1)
    props = await picker(2, remove=props["remove"]["enum"][1])
    assert props["expected_revision"]["default"] == 3
    assert props["selected_ids"]["default"] == [newer["id"]]


async def test_history_relations_stale_and_originals(host):
    notes = Notes(host.store, OWNER)
    first = await notes.create("Original idea #science")
    second = await notes.create("Revised idea #science")
    desk_id = await create(host, note=first)
    await add(host, desk_id, second)
    connection_form = await desk.sources(ctx(host), desk_id, 2, action="connect")
    validate_output(connection_form)
    assert "disconnect" in connection_form["items"][2]["schema"]["properties"]
    result = await desk.sources(
        ctx(host),
        desk_id,
        2,
        action="connect",
        from_note=second["id"],
        to_note=first["id"],
        kind="challenges",
    )
    validate_output(result)
    assert "challenges" in markdown(result)
    assert (await notes.get(first["id"]))["body"] == first["body"]
    with pytest.raises(Problem, match="changed"):
        await desk.edit(ctx(host), desk_id, 2, title="stale")
    with pytest.raises(ValueError):
        await desk.relate(ctx(host), desk_id, 3, first["id"], first["id"])
    await desk.sources(ctx(host), desk_id, 3, remove=first["id"])
    assert (await desk.load(ctx(host), desk_id))["relations"] == []


async def test_collision_private_snapshot_and_revision_guard(host):
    notes = Notes(host.store, OWNER)
    first = await notes.create("Patterns in gardens #gardening", private=True)
    second = await notes.create("Patterns in networks #computing")
    desk_id = await create(host, "collision", first)
    await add(host, desk_id, second)
    result = await desk.edit(
        ctx(host),
        desk_id,
        2,
        title="Garden networks",
        mode="collision",
        thinking="Try pruning weak network connections.",
    )
    assert "Try pruning" in markdown(result)
    result = await desk.edit(ctx(host), desk_id, 3, action="snapshot")
    saved = await notes.get(result["items"][1]["ids"][0])
    assert saved["private"] and "Try pruning" in saved["body"]
    await notes.edit(second["id"], "Changed network idea", 1)
    with pytest.raises(Problem):
        await desk.edit(ctx(host), desk_id, 3, action="snapshot")
    rendered = await desk.render(ctx(host), desk_id)
    assert "changed — review sources" in markdown(rendered)
    assert not any(x.get("args", {}).get("action") == "snapshot" for x in rendered["items"])
    await desk.sources(ctx(host), desk_id, 3, refresh=True)
    validate_output(await desk.edit(ctx(host), desk_id, 4, action="snapshot"))


async def test_nonowner_denied_and_no_source_bodies_stored(host):
    note = await Notes(host.store, OWNER).create("Secret source text", private=True)
    desk_id = await create(host, note=note)
    data = await desk.load(ctx(host), desk_id)
    assert "Secret" not in str(data)
    reader = Principal("reader", "agent", "Reader", "read")
    with pytest.raises(ContextError):
        await desk.desk(ctx(host, reader), desk_id)


async def test_deleted_sources_have_no_cached_text_and_block_snapshot(host):
    notes = Notes(host.store, OWNER)
    note = await notes.create("Distinct source title\nSensitive source body")
    desk_id = await create(host, note=note)
    await notes.flags(note["id"], {"trashed": True})
    await notes.delete(note["id"])
    result = await desk.render(ctx(host), desk_id)
    validate_output(result)
    assert "Distinct source" not in str(result) and "Sensitive" not in str(result)
    assert "unavailable" in str(result)
    assert "/n/" + note["id"] not in markdown(result)
    with pytest.raises(Problem):
        await desk.edit(ctx(host), desk_id, 1, action="snapshot")


async def test_collision_requires_combination_and_written_idea(host):
    desk_id = await create(host, "collision")
    with pytest.raises(ValueError, match="at least two"):
        await desk.edit(ctx(host), desk_id, 1, action="snapshot")


async def test_source_selection_rejects_stale_choice(host):
    notes = Notes(host.store, OWNER)
    note = await notes.create("Original subject")
    desk_id = await create(host)
    await notes.edit(note["id"], "Changed subject", 1)
    with pytest.raises(Problem):
        await add(host, desk_id, note)
    assert (await desk.load(ctx(host), desk_id))["sources"] == []


async def test_rest_commands_round_trip(server, host):
    from conftest import PUBLIC_URL

    original = server.services.host
    server.services.host = host
    try:
        response = await server.client.post(
            "/api/v1/setup",
            json={
                "code": server.services.setup_code,
                "password": "correct horse battery",
            },
        )
        assert response.status_code == 204
        headers = {"Origin": PUBLIC_URL}
        url = "/api/v1/plugins/thinking-desk/commands/"
        response = await server.client.post(url + "edit", json={}, headers=headers)
        assert response.status_code == 200, response.text
        validate_output(response.json())
        request_id = response.json()["items"][1]["schema"]["properties"]["request_id"]["default"]
        response = await server.client.post(
            url + "edit",
            json={
                "title": "REST topic",
                "mode": "history",
                "request_id": request_id,
            },
            headers=headers,
        )
        assert response.status_code == 200, response.text
        args = response.json()["items"][2]["args"]
        response = await server.client.post(url + "sources", json=args, headers=headers)
        assert response.status_code == 200, response.text
        validate_output(response.json())
        await host.disable("thinking-desk")
        assert (await server.client.post(url + "desk", json={}, headers=headers)).status_code == 404
    finally:
        server.services.host = original


async def test_concurrent_desk_edits_preserve_one_winner(host):
    import asyncio

    desk_id = await create(host)
    results = await asyncio.gather(
        desk.edit(ctx(host), desk_id, 1, title="First writer"),
        desk.edit(ctx(host), desk_id, 1, title="Second writer"),
        return_exceptions=True,
    )
    assert sum(isinstance(result, Problem) for result in results) == 1
    assert (await desk.load(ctx(host), desk_id))["revision"] == 2


@pytest.mark.parametrize("kind", desk.RELATIONS)
async def test_directional_connection_can_be_removed(host, kind):
    notes = Notes(host.store, OWNER)
    first = await notes.create("First synthetic idea")
    second = await notes.create("Second synthetic idea")
    desk_id = await create(host, note=first)
    await add(host, desk_id, second)
    await desk.sources(
        ctx(host),
        desk_id,
        2,
        action="connect",
        from_note=first["id"],
        to_note=second["id"],
        kind=kind,
    )
    data = await desk.load(ctx(host), desk_id)
    assert data["relations"] == [{"from": first["id"], "to": second["id"], "kind": kind}]
    await desk.sources(
        ctx(host),
        desk_id,
        3,
        action="connect",
        from_note=first["id"],
        to_note=second["id"],
        kind=kind,
        disconnect=True,
    )
    assert (await desk.load(ctx(host), desk_id))["relations"] == []
