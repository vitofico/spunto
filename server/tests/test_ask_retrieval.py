"""Conversational recall and source lifecycle regression tests with synthetic notes."""

import html
import re

import pytest
from test_plugins_ai import OWNER
from test_plugins_ai import ai_host as ai_host

from spunto.ai.errors import AIError
from spunto.ai.vectors import VectorUnavailable
from spunto.api.errors import Problem
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.core.text import search_query
from spunto.host import Ctx
from spunto.plugins import ask
from spunto.store.postgres import _fts_query

pytestmark = pytest.mark.anyio


@pytest.mark.parametrize("semantic_enabled", [True, False])
async def test_question_uses_ask_vectors_independently_of_search_extension(
    ai_host, monkeypatch, semantic_enabled
):
    host, provider = ai_host
    note = await Notes(host.store, OWNER).create("Spunto uses SQLite for notes")
    monkeypatch.setattr(host.store, "plugin_host", host)
    await host.vectors.backfill()
    if not semantic_enabled:
        await host.disable("semantic-search")

    async def search_source_must_not_run(*args, **kwargs):
        pytest.fail("Ask must not depend on the search extension")

    monkeypatch.setattr(host, "search_sources", search_source_must_not_run)
    nearest = host.vectors.nearest
    calls = []

    async def traced_nearest(**kwargs):
        calls.append(kwargs)
        return await nearest(**kwargs)

    monkeypatch.setattr(host.vectors, "nearest", traced_nearest)
    result = await ask.ask(Ctx(host, "ask", principal=OWNER), "What database does Spunto use?")
    assert any(item["type"] == "markdown" for item in result["items"])
    assert next(item for item in result["items"] if item["type"] == "notes")["ids"] == [note["id"]]
    assert calls[0]["plugin_id"] == "ask" and calls[0]["profile"] == "fixture"
    assert any("messages" in request for request in provider.requests)


@pytest.mark.parametrize(
    "failure",
    [VectorUnavailable(), AIError("provider", "ai_provider_timeout")],
)
async def test_conversational_keyword_fallback_can_still_answer(ai_host, monkeypatch, failure):
    host, _ = ai_host
    await Notes(host.store, OWNER).create("Spunto uses SQLite for notes")

    async def unavailable(**kwargs):
        raise failure

    monkeypatch.setattr(host.vectors, "nearest", unavailable)
    result = await ask.ask(Ctx(host, "ask", principal=OWNER), "What database does Spunto use?")
    assert any(item["type"] == "markdown" for item in result["items"])


async def test_unavailable_retrieval_is_not_reported_as_no_coverage(ai_host, monkeypatch):
    host, provider = ai_host

    async def unavailable(**kwargs):
        raise VectorUnavailable()

    monkeypatch.setattr(host.vectors, "nearest", unavailable)
    provider.requests.clear()
    result = await ask.ask(Ctx(host, "ask", principal=OWNER), "A question with no matches?")
    assert result["items"][0] == {"type": "text", "text": ask.RETRIEVAL_UNAVAILABLE}
    assert not any(item.get("id") == "save-answer" for item in result["items"])
    assert not provider.requests


@pytest.mark.parametrize("code", ["ai_quota_exhausted", "ai_configuration_changed"])
async def test_retrieval_does_not_swallow_quota_or_authority_errors(ai_host, monkeypatch, code):
    host, provider = ai_host
    await Notes(host.store, OWNER).create("Spunto uses SQLite")

    async def denied(**kwargs):
        raise AIError("unavailable", code)

    monkeypatch.setattr(host.vectors, "nearest", denied)
    provider.requests.clear()
    with pytest.raises(AIError, match=code):
        await ask.ask(Ctx(host, "ask", principal=OWNER), "Spunto SQLite")
    assert not provider.requests


async def test_long_source_cannot_starve_other_sources(ai_host):
    host, provider = ai_host
    notes = Notes(host.store, OWNER)
    first = await notes.create("Spunto SQLite LONG-SOURCE " + "context " * 1800)
    second = await notes.create("Spunto SQLite SECOND-SOURCE " + "evidence " * 1800)
    await ask.ask(Ctx(host, "ask", principal=OWNER), "Spunto SQLite")
    content = next(r for r in provider.requests if "messages" in r)["messages"][1]["content"]
    bodies = {
        match[1]: html.unescape(match[2])
        for match in re.finditer(r'<note id="([^"]+)"[^>]*>(.*?)</note>', content, re.DOTALL)
    }
    assert set(bodies) == {first["id"], second["id"]}
    assert all(0 < len(body) <= ask.MAX_CONTEXT // 2 for body in bodies.values())
    assert sum(map(len, bodies.values())) <= ask.MAX_CONTEXT


async def test_semantic_sources_preserve_inline_label_scope_and_descendants(ai_host, monkeypatch):
    host, provider = ai_host
    notes = Notes(host.store, OWNER)
    wanted = await notes.create("Spunto SQLite #Project/Spunto #Database")
    wrong = await notes.create("Spunto SQLite #other #Database")
    partial = await notes.create("Spunto SQLite #Project/Spunto")

    async def candidates(**kwargs):
        return [
            {"id": wrong["id"], "score": 0.99},
            {"id": partial["id"], "score": 0.95},
            {"id": wanted["id"], "score": 0.9},
        ]

    monkeypatch.setattr(host.vectors, "nearest", candidates)
    await ask.ask(Ctx(host, "ask", principal=OWNER), "Spunto SQLite #project #database")
    content = next(r for r in provider.requests if "messages" in r)["messages"][1]["content"]
    assert wanted["id"] in content
    assert wrong["id"] not in content and partial["id"] not in content


async def test_abstention_rechecks_sources_before_returning_closest_notes(ai_host):
    host, provider = ai_host
    notes = Notes(host.store, OWNER)
    note = await notes.create("Spunto uses SQLite")
    provider.ask_answer = {"found": False, "answer": "", "citations": []}

    async def hide_source():
        await notes.flags(note["id"], {"private": True})

    provider.on_chat = hide_source
    result = await ask.ask(Ctx(host, "ask", principal=OWNER), "Spunto SQLite")
    assert result["items"][0] == {"type": "text", "text": ask.SOURCE_CHANGED}
    assert not any(item["type"] == "notes" for item in result["items"])


async def test_save_rechecks_versions_inside_creation_transaction(ai_host, monkeypatch):
    host, _ = ai_host
    notes = Notes(host.store, OWNER)
    note = await notes.create("Spunto uses SQLite")
    ctx = Ctx(host, "ask", principal=OWNER)
    result = await ask.ask(ctx, "What database does Spunto use?")
    command = next(item for item in result["items"] if item["type"] == "command")
    create = PluginNotes.create

    async def change_before_transaction(self, *args, **kwargs):
        await notes.edit(note["id"], "Spunto switched databases", note["version"])
        return await create(self, *args, **kwargs)

    monkeypatch.setattr(PluginNotes, "create", change_before_transaction)
    with pytest.raises(Problem) as error:
        await ask.save_answer(ctx, **command["args"])
    assert error.value.code == "version_conflict"
    assert not any(note["machine"] for note in (await notes.list())["notes"])


async def test_explicit_save_keeps_question_heading_and_is_idempotent(ai_host):
    host, _ = ai_host
    notes = Notes(host.store, OWNER)
    await notes.create("Spunto uses SQLite")
    ctx = Ctx(host, "ask", principal=OWNER)
    result = await ask.ask(ctx, "What database does Spunto use?")
    assert len((await notes.list())["notes"]) == 1
    command = next(item for item in result["items"] if item["type"] == "command")
    saved = await ask.save_answer(ctx, **command["args"])
    assert saved == await ask.save_answer(ctx, **command["args"])
    note = await notes.get(saved["ids"][0])
    assert note["body"].startswith("# What database does Spunto use?\n\n")
    assert note["machine"] and "/n/" in note["body"]


async def test_save_does_not_depend_on_uncited_candidates(ai_host):
    host, provider = ai_host
    notes = Notes(host.store, OWNER)
    cited = await notes.create("Spunto uses SQLite")
    uncited = await notes.create("Spunto uses SQLite for another project")
    provider.ask_answer = {
        "found": True,
        "answer": "Spunto uses SQLite.",
        "citations": [cited["id"]],
    }
    ctx = Ctx(host, "ask", principal=OWNER)
    result = await ask.ask(ctx, "Spunto SQLite")
    await notes.flags(uncited["id"], {"private": True})
    command = next(item for item in result["items"] if item["type"] == "command")
    saved = await ask.save_answer(ctx, **command["args"])
    assert (await notes.get(saved["ids"][0]))["machine"]


async def test_any_term_keyword_mode_preserves_default_search_and_label_filters(ai_host):
    host, _ = ai_host
    notes = Notes(host.store, OWNER)
    wanted = await notes.create("Spunto uses SQLite #project/spunto")
    await notes.create("Spunto uses SQLite #other")
    await notes.create("What database does another application use? #project/spunto")
    question = "What database does Spunto use? #project/spunto"
    assert wanted["id"] not in {
        note["id"] for note in (await notes.search(question, mode="keyword"))["hits"]
    }
    hits = await notes.search(question, mode="keyword", match_any=True)
    assert wanted["id"] in {note["id"] for note in hits["hits"]}
    assert all("project/spunto" in note["labels"] for note in hits["hits"])


def test_any_term_fts_translation_keeps_operators_outside_quoted_data():
    query, _ = search_query('"red OR blue" green', match_any=True)
    assert query == '"red OR blue" OR "green"*'
    assert _fts_query(query) == "('red' <-> 'OR' <-> 'blue') | ('green':*)"
    default, _ = search_query('"red OR blue" green')
    assert _fts_query(default) == "('red' <-> 'OR' <-> 'blue') & ('green':*)"
