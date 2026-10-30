"""Real SQLite and fake ASGI provider acceptance for the four M4 plugins."""

import asyncio
import json
import re

import httpx
import pytest
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from spunto.ai.errors import AIError
from spunto.ai.gateway import Gateway
from spunto.ai.output import parse_output
from spunto.ai.profiles import Profile
from spunto.ai.vectors import VectorIndex, VectorUnavailable
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.host import Ctx, Host, ManifestError, validate_output
from spunto.plugins import ask, label_suggestions, related, semantic_search

pytestmark = pytest.mark.anyio
OWNER = Principal("owner", "session", "Owner", "write", private_ok=True)
AI_PLUGINS = ("label-suggestions", "semantic-search", "related", "ask")


class FakeProvider:
    """Only an in-memory ASGI app; these fixtures cannot reach live providers."""

    def __init__(self):
        self.mode = "valid"
        self.label_answer = {"labels": [{"path": "project/Spunto", "confidence": 0.95}]}
        self.requests = []
        self.ask_answer = None
        self.action_plan = None
        self.on_chat = None
        self.app = FastAPI()
        self.app.post("/v1/chat/completions")(self.chat)
        self.app.post("/v1/embeddings")(self.embed)

    async def failure(self):
        if self.mode == "slow":
            await asyncio.sleep(0.2)
        if self.mode == "500":
            return JSONResponse({}, status_code=500)
        return None

    async def chat(self, request: Request):
        body = await request.json()
        self.requests.append(body)
        if result := await self.failure():
            return result
        if self.mode == "reject-schema" and body["response_format"]["type"] == "json_schema":
            return JSONResponse({}, status_code=400)
        if self.on_chat:
            await self.on_chat()
        prompt = body["messages"][1]["content"]
        if "Plan a note action" in prompt:
            answer = self.action_plan or {
                "kind": "question",
                "target_ids": [],
                "selector": "",
                "body": "",
                "before": "",
                "after": "",
            }
        elif "Suggest up to three" in prompt:
            answer = self.label_answer
        elif "Question:" in prompt:
            ids = re.findall(r'<note id="([^"]+)"', prompt)
            answer = self.ask_answer or {
                "found": True,
                "answer": "Spunto uses SQLite.",
                "citations": ids[:1],
            }
        else:
            answer = {"ok": True}
        if self.mode in {"prose", "reject-schema"}:
            content = "This is prose rather than structured output."
        elif self.mode == "wrong-types":
            content = json.dumps({"found": "yes", "answer": 42, "citations": "wrong"})
        elif self.mode == "invented-ids":
            content = json.dumps({"found": True, "answer": "Invented", "citations": ["invented"]})
        elif self.mode == "label-spaces":
            content = json.dumps({"labels": [{"path": "has spaces", "confidence": 0.95}]})
        else:
            content = json.dumps(answer)
        return {"choices": [{"finish_reason": "stop", "message": {"content": content}}]}

    async def embed(self, request: Request):
        body = await request.json()
        self.requests.append(body)
        if result := await self.failure():
            return result
        # Modes concerning structured text have equivalent malformed-vector
        # fixtures for embedding-only plugins, rather than irrelevant chat calls.
        vector = [1.0, 0.2, 0.1]
        if self.mode in {"prose", "wrong-types", "invented-ids", "label-spaces", "reject-schema"}:
            vector = ["invalid", 0.2, 0.1]
        return {"data": [{"index": i, "embedding": vector} for i, _ in enumerate(body["input"])]}


@pytest.fixture
async def ai_host(server):
    provider = FakeProvider()
    profile = Profile(
        name="fixture",
        base_url="http://provider.test/v1",
        chat_model="fixture-chat",
        embed_model="fixture-embed",
        rate_per_min=100000,
        timeout_s=5,
    )
    settings = server.services.settings.model_copy(
        update={
            "ai_profiles": {"fixture": profile},
            "ai_chat_profile": "fixture",
            "ai_embed_profile": "fixture",
        }
    )
    gateway = Gateway(server.services.store, settings, transport=httpx.ASGITransport(provider.app))
    vectors = VectorIndex(server.services.store, gateway)
    host = Host(
        server.services.store, settings, notes_factory=PluginNotes, gateway=gateway, vectors=vectors
    )
    await host.discover()
    for plugin_id in AI_PLUGINS:
        await host.enable(plugin_id, permissions=host.manifests[plugin_id]["permissions"])
    await vectors.sync_routes({plugin_id: "fixture" for plugin_id in AI_PLUGINS})
    try:
        yield host, provider
    finally:
        await host.close()
        await gateway.close()


async def state(host):
    result = {}
    async with host.store.read() as conn:
        for table in ("note", "label", "note_label", "suggestion", "annotation"):
            rows = await conn.execute_fetchall("SELECT * FROM " + table + " ORDER BY rowid")  # noqa: S608
            result[table] = [tuple(row) for row in rows]
    return result


async def seed(host):
    notes = Notes(host.store, OWNER)
    vocabulary = await notes.create("Spunto project uses SQLite #project/Spunto")
    target = await notes.create("Spunto uses SQLite for notes")
    return notes, vocabulary, target


@pytest.mark.parametrize(
    "failure",
    [VectorUnavailable(), AIError("provider", "ai_provider_unreachable")],
)
async def test_labels_keep_working_while_optional_neighbors_are_unavailable(
    ai_host, monkeypatch, failure
):
    host, provider = ai_host
    notes, _, target = await seed(host)

    async def unavailable(**kwargs):
        raise failure

    monkeypatch.setattr(host.vectors, "nearest", unavailable)
    result = await host.invoke_command(
        "label-suggestions", "suggest-now", {"note_id": target["id"]}, principal=OWNER
    )
    assert "1 suggestions to review" in result["text"]
    assert (await notes.get(target["id"]))["body"] == target["body"]
    assert any("Suggest up to three" in str(request) for request in provider.requests)
    assert host.states["label-suggestions"]["state"] == "enabled"


async def test_label_output_limits_canonical_names_and_new_ancestor_case():
    model = label_suggestions.output_model(
        [{"path": "Project/Spunto", "count": 4}], [], [], "Use SQLite for notes"
    )
    result = parse_output(
        model,
        {"labels": [{"path": "project/spunto/SQLITE", "confidence": 0.8}]},
        set(),
    )
    assert result.labels[0].path == "Project/Spunto/SQLite"
    for labels in (
        [{"path": "has spaces", "confidence": 0.8}],
        [{"path": "one", "confidence": 0.8}, {"path": "two", "confidence": 0.8}],
        [{"path": "too/deep/new/path", "confidence": 0.8}],
        [{"path": "new", "confidence": 0.69}],
        [{"path": "new", "confidence": "0.9"}],
    ):
        with pytest.raises(ValueError):
            parse_output(model, {"labels": labels}, set())


async def test_labels_propose_only_then_owner_existing_auto_accept_and_dismiss(ai_host):
    host, provider = ai_host
    notes, vocabulary, target = await seed(host)
    ctx = Ctx(host, "label-suggestions")
    await label_suggestions.saved(ctx, target["id"], target["version"])
    current = await notes.get(target["id"])
    assert current["body"] == target["body"] and current["labels"] == []
    snapshot = await state(host)
    assert len(snapshot["suggestion"]) == 1
    await label_suggestions.saved(ctx, target["id"], target["version"])
    assert await state(host) == snapshot
    async with host.store.read() as conn:
        suggestion = (await conn.execute_fetchall("SELECT id FROM suggestion"))[0][0]
    from spunto.core.suggestions import decide

    await decide(host.store, OWNER, suggestion, "dismiss")
    await label_suggestions.decided(ctx, target["id"])
    assert await ctx.notes.suggestion_decisions(target["id"]) == ["project/Spunto"]
    await host.set_settings("label-suggestions", {"auto_accept_existing": True})
    another = await notes.create("Another useful Spunto database note")
    await label_suggestions.saved(Ctx(host, "label-suggestions"), another["id"], another["version"])
    assert (await notes.get(another["id"]))["labels"] == ["project/Spunto"]
    provider.label_answer = {"labels": [{"path": "brand-new", "confidence": 0.99}]}
    new = await notes.create("Notes mention a new subject")
    await label_suggestions.saved(Ctx(host, "label-suggestions"), new["id"], new["version"])
    assert (await notes.get(new["id"]))["labels"] == []
    assert vocabulary["id"] != target["id"]


async def test_ask_grounded_transient_result_sanitized_and_owner_save_idempotent(ai_host):
    host, provider = ai_host
    _, _, target = await seed(host)
    ctx = Ctx(host, "ask", principal=OWNER)
    before = await state(host)
    validate_output(await ask.ask(ctx))
    result = await ask.ask(ctx, "Spunto SQLite")
    validate_output(result)
    assert await state(host) == before
    command = next(item for item in result["items"] if item["type"] == "command")
    saved = await ask.save_answer(ctx, **command["args"])
    repeated = await ask.save_answer(ctx, **command["args"])
    assert saved == repeated
    note = await Notes(host.store, OWNER).get(saved["ids"][0])
    assert note["machine"] and note["origin"] == "plugin" and note["source"]["ref"] == "ask"
    assert target["body"] in json.dumps(provider.requests)
    prompt = next(r for r in provider.requests if r.get("messages"))["messages"][1]["content"]
    assert '<note id="' in prompt
    with pytest.raises(ValueError):
        await ask.ask(ctx, "x" * 501)
    with pytest.raises(ValueError):
        await ask.ask(ctx, " " * 501 + "x")


async def test_related_and_semantic_bounded_visibility_and_no_annotations(ai_host):
    host, _ = ai_host
    notes, _, target = await seed(host)
    private = await notes.create("Spunto private SQLite")
    await notes.flags(private["id"], {"private": True})
    for i in range(8):
        await notes.create(f"Spunto SQLite related note {i}")
    await host.vectors.backfill()
    before = await state(host)
    result = await related.panel(Ctx(host, "related"), target["id"])
    validate_output(result)
    assert len(result["items"]) <= 5
    assert all(item["id"] not in {target["id"], private["id"]} for item in result["items"])
    ranked = await semantic_search.search(Ctx(host, "semantic-search"), "Spunto SQLite")
    assert len(ranked) <= 50 and len({item["id"] for item in ranked}) == len(ranked)
    assert private["id"] not in {item["id"] for item in ranked}
    assert await state(host) == before
    with pytest.raises(ManifestError):
        await host.set_settings("semantic-search", {"model_family": "arbitrary"})


@pytest.mark.parametrize("plugin_id", AI_PLUGINS)
@pytest.mark.parametrize(
    "mode", ["prose", "wrong-types", "invented-ids", "label-spaces", "reject-schema", "slow", "500"]
)
async def test_every_plugin_provider_failures_leave_owner_and_derived_data_unchanged(
    ai_host, plugin_id, mode
):
    host, provider = ai_host
    _, _, target = await seed(host)
    before = await state(host)
    if mode == "slow":
        # Only the timeout contract needs an 80 ms deadline. Other scenarios do
        # real SQLite work and must tolerate concurrent build/CI scheduling.
        host.settings.ai_profiles["fixture"] = host.settings.ai_profiles["fixture"].model_copy(
            update={"timeout_s": 0.08}
        )
    provider.mode = mode
    ctx = Ctx(host, plugin_id, principal=OWNER)
    if plugin_id == "label-suggestions":
        host.settings.ai_embed_profile = None
    with pytest.raises((AIError, ValueError)):
        if plugin_id == "label-suggestions":
            await label_suggestions.saved(ctx, target["id"], target["version"])
        elif plugin_id == "semantic-search":
            await semantic_search.search(ctx, "Spunto SQLite")
        elif plugin_id == "related":
            await related.panel(ctx, target["id"])
        else:
            await ask.ask(ctx, "Spunto SQLite")
    assert await state(host) == before


async def test_ask_abstains_without_citations_and_never_offers_save(ai_host):
    host, provider = ai_host
    await seed(host)
    ctx = Ctx(host, "ask", principal=OWNER)
    provider.ask_answer = {"found": True, "answer": "Spunto uses SQLite.", "citations": []}
    before = await state(host)
    result = await ask.ask(ctx, "Spunto SQLite")
    assert result["items"][0] == {"type": "text", "text": ask.ABSTENTION}
    assert not any(item.get("id") == "save-answer" for item in result["items"])
    assert await state(host) == before


async def test_stale_label_job_and_changed_inference_source_do_not_write_suggestions(ai_host):
    host, provider = ai_host
    notes, _, target = await seed(host)
    ctx = Ctx(host, "label-suggestions")
    before = await state(host)
    await label_suggestions.saved(ctx, target["id"], target["version"] + 1)
    assert await state(host) == before
    host.settings.ai_embed_profile = None

    async def change_source():
        provider.on_chat = None
        await notes.edit(target["id"], "Owner revised this note", target["version"])

    provider.on_chat = change_source
    await label_suggestions.saved(ctx, target["id"], target["version"])
    after = await state(host)
    assert after["suggestion"] == before["suggestion"]
    assert after["annotation"] == before["annotation"]
    assert (await notes.get(target["id"]))["body"] == "Owner revised this note"


async def test_ask_changed_private_source_prevents_save_and_save_body_is_not_client_supplied(
    ai_host,
):
    host, _ = ai_host
    notes, _, _ = await seed(host)
    ctx = Ctx(host, "ask", principal=OWNER)
    result = await ask.ask(ctx, "Spunto SQLite")
    citation = next(item for item in result["items"] if item["type"] == "notes")["ids"][0]
    command = next(item for item in result["items"] if item["type"] == "command")
    await notes.flags(citation, {"private": True})
    before = await state(host)
    assert (await ask.save_answer(ctx, **command["args"]))["type"] == "text"
    assert await state(host) == before
    with pytest.raises(TypeError):
        await ask.save_answer(ctx, answer_id=command["args"]["answer_id"], body="arbitrary")


async def test_related_tool_is_read_only_closed_and_honors_caller_visibility(ai_host):
    host, _ = ai_host
    notes, _, target = await seed(host)
    outside = await notes.create("Spunto SQLite outside text")
    machine = await notes.create("Spunto SQLite machine text")
    async with host.store.write() as conn:
        await conn.execute("UPDATE note SET origin='external' WHERE id=?", (outside["id"],))
        await conn.execute("UPDATE note SET machine=1 WHERE id=?", (machine["id"],))
    await host.vectors.backfill()
    reader = Principal("fixture-reader", "pat", "Fixture", "read", external_ok=False)
    before = await state(host)
    result = await host.invoke_tool("related", "notes", {"id": target["id"]}, principal=reader)
    assert all(item["id"] not in {outside["id"], machine["id"]} for item in result["notes"])
    assert await state(host) == before
    tool = host.manifests["related"]["contributes"]["mcp_tools"][0]
    assert tool["read_only"] and tool["annotations"]["readOnlyHint"]
    assert tool["annotations"]["openWorldHint"] is False
    assert len("related_" + tool["name"]) < 40 and len(tool["description"]) < 300
    with pytest.raises(TypeError):
        await related.notes(
            Ctx(host, "related", principal=reader), id=target["id"], query="free text"
        )


async def test_virtual_label_ancestor_counts_as_new_and_ordinary_suffix_stays_lowercase():
    model = label_suggestions.output_model(
        [
            {"path": "Project", "count": 2, "own_count": 0},
            {"path": "Project/Spunto", "count": 2, "own_count": 2},
        ],
        [],
        [],
        "Notes discuss SQLite",
    )
    with pytest.raises(ValueError):
        parse_output(
            model,
            {
                "labels": [
                    {"path": "Project", "confidence": 0.9},
                    {"path": "project/notes", "confidence": 0.9},
                ]
            },
            set(),
        )
    answer = parse_output(model, {"labels": [{"path": "project/notes", "confidence": 0.9}]}, set())
    assert answer.labels[0].path == "Project/notes"


async def test_ask_real_api_validates_form_sanitizes_answer_and_saves_only_owner_command(
    ai_host, server
):
    from spunto.auth import sessions

    host, provider = ai_host
    _, _, target = await seed(host)
    provider.ask_answer = {
        "found": True,
        "answer": "Spunto uses SQLite. <script>alert(1)</script>",
        "citations": [target["id"]],
    }
    original = server.services.host
    server.services.host = host
    token = await sessions.create(host.store, "Fixture owner", host.clock())
    server.client.cookies.set("session", token)
    headers = {"Origin": host.settings.public_url}
    try:
        form = await server.client.post(
            "/api/v1/plugins/ask/commands/ask", json={}, headers=headers
        )
        assert form.status_code == 200 and form.json()["type"] == "form"
        before = await state(host)
        answer = await server.client.post(
            "/api/v1/plugins/ask/commands/ask", json={"question": "Spunto SQLite"}, headers=headers
        )
        assert answer.status_code == 200
        output = answer.json()
        markdown = next(item for item in output["items"] if item["type"] == "markdown")
        assert "<script" not in markdown["html"]
        assert await state(host) == before
        command = next(item for item in output["items"] if item["type"] == "command")
        result = await server.client.post(
            "/api/v1/plugins/ask/commands/save-answer", json=command["args"], headers=headers
        )
        assert result.status_code == 200
        saved = await Notes(host.store, OWNER).get(result.json()["ids"][0])
        assert saved["machine"] and saved["source"]["ref"] == "ask"
        fetched = await server.client.get("/api/v1/notes/" + saved["id"])
        assert fetched.status_code == 200
        assert f"[Note 1](/n/{target['id']})" in fetched.json()["body"]
        assert "/notes/" not in fetched.json()["body"]
        forged = await server.client.post(
            "/api/v1/plugins/ask/commands/save-answer",
            json={"answer_id": command["args"]["answer_id"], "body": "Forged"},
            headers=headers,
        )
        assert forged.status_code >= 400
    finally:
        server.services.host = original


async def test_mixed_invalid_label_output_and_disable_during_inference_fail_closed(ai_host):
    from spunto.host import ContextError

    host, provider = ai_host
    _, _, target = await seed(host)
    host.settings.ai_embed_profile = None
    provider.label_answer = {
        "labels": [
            {"path": "project/Spunto", "confidence": 0.95},
            {"path": "invalid spaces", "confidence": 0.9},
        ]
    }
    before = await state(host)
    with pytest.raises(AIError):
        await label_suggestions.saved(
            Ctx(host, "label-suggestions"), target["id"], target["version"]
        )
    assert await state(host) == before
    provider.label_answer = {"labels": [{"path": "project/Spunto", "confidence": 0.95}]}

    async def disable():
        provider.on_chat = None
        await host.disable("label-suggestions")

    provider.on_chat = disable
    with pytest.raises(ContextError):
        await label_suggestions.saved(
            Ctx(host, "label-suggestions"), target["id"], target["version"]
        )
    assert await state(host) == before
