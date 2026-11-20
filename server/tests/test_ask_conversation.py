"""Ephemeral Ask turns, caller boundaries and explicit per-answer saving."""

import asyncio
import html
from dataclasses import replace

import pytest
from test_plugins_ai import OWNER, state
from test_plugins_ai import ai_host as ai_host

from spunto.ai.errors import AIError
from spunto.api.errors import Problem
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.host import ContextError, Ctx, validate_output
from spunto.plugins import ask

pytestmark = pytest.mark.anyio


def continuation(output):
    return next(item["args"] for item in output["items"] if item.get("id") == "ask")


def save_args(output):
    return next(item["args"] for item in output["items"] if item.get("id") == "save-answer")


def prompt(provider):
    return provider.chat_bodies[-1]["messages"][1]["content"]


async def test_allocate_then_follow_up_with_fresh_sources_without_persisting_history(ai_host):
    host, provider = ai_host
    notes = Notes(host.store, OWNER)
    original = "Spunto uses SQLite so its notes work offline."
    note = await notes.create(original)
    provider.ask_answer = {"found": True, "answer": original, "citations": [note["id"]]}
    ctx = Ctx(host, "ask", principal=OWNER)
    before = await state(host)
    provider.requests.clear()
    started = await ask.ask(ctx, start=True)
    validate_output(started)
    assert not provider.requests and await state(host) == before
    first = await ask.ask(ctx, "What database does Spunto use?", **continuation(started))
    assert await state(host) == before
    updated = "Spunto now uses PostgreSQL to share notes."
    await notes.edit(note["id"], updated, note["version"])
    provider.ask_answer = {"found": True, "answer": updated, "citations": [note["id"]]}
    second = await ask.ask(ctx, "Why did that change?", **continuation(first))
    validate_output(second)
    outbound = html.unescape(prompt(provider))
    assert updated in outbound and original not in outbound
    assert "What database does Spunto use?" in outbound
    assert '<conversation-data untrusted="true">' in outbound
    assert continuation(second)["after_turn"] == 2
    assert len((await notes.list())["notes"]) == 1


async def test_unchanged_answer_can_resolve_follow_up_but_is_fenced_as_history(ai_host):
    host, provider = ai_host
    note = await Notes(host.store, OWNER).create("Spunto uses SQLite.")
    provider.ask_answer = {"found": True, "answer": note["body"], "citations": [note["id"]]}
    ctx = Ctx(host, "ask", principal=OWNER)
    first = await ask.ask(ctx, "What database does Spunto use?")
    await ask.ask(ctx, "Why?", **continuation(first))
    outbound = prompt(provider)
    assert '<conversation-data untrusted="true">' in outbound
    assert "&quot;answer&quot;: &quot;Spunto uses SQLite.&quot;" in outbound
    assert f'<note id="{note["id"]}"' in outbound


async def test_previous_answer_does_not_turn_an_unrelated_question_into_coverage(ai_host):
    host, provider = ai_host
    note = await Notes(host.store, OWNER).create("Spunto uses SQLite.")
    provider.ask_answer = {"found": True, "answer": note["body"], "citations": [note["id"]]}
    ctx = Ctx(host, "ask", principal=OWNER)
    first = await ask.ask(ctx, "What database does Spunto use?")
    provider.ask_answer = {"found": False, "answer": "", "citations": []}
    second = await ask.ask(
        ctx, "What is the capital of an imaginary country?", **continuation(first)
    )
    assert not any(item.get("id") == "save-answer" for item in second["items"])
    assert "fresh supplied note data" in prompt(provider)
    assert continuation(second)["after_turn"] == 2


async def test_hidden_previous_source_and_its_answer_do_not_enter_follow_up(ai_host):
    host, provider = ai_host
    notes = Notes(host.store, OWNER)
    hidden = await notes.create("Spunto SQLite DISTINCT-OLD-FACT")
    other = await notes.create("Spunto SQLite DISTINCT-CURRENT-FACT")
    provider.ask_answer = {"found": True, "answer": hidden["body"], "citations": [hidden["id"]]}
    ctx = Ctx(host, "ask", principal=OWNER)
    first = await ask.ask(ctx, "What did Spunto decide?")
    await notes.flags(hidden["id"], {"private": True})
    provider.ask_answer = {"found": True, "answer": other["body"], "citations": [other["id"]]}
    await ask.ask(ctx, "Why?", **continuation(first))
    outbound = html.unescape(prompt(provider))
    assert hidden["id"] not in outbound and "DISTINCT-OLD-FACT" not in outbound
    assert other["id"] in outbound


@pytest.mark.parametrize(
    "change",
    [
        {"id": "another-session"},
        {"user_id": "another-user"},
        {"workspace_id": "another-space"},
        {"external_ok": False},
    ],
)
async def test_conversation_and_save_receipt_are_bound_to_the_caller(ai_host, change):
    host, _ = ai_host
    await Notes(host.store, OWNER).create("Spunto uses SQLite")
    ctx = Ctx(host, "ask", principal=OWNER)
    result = await ask.ask(ctx, "What database does Spunto use?")
    other = Ctx(host, "ask", principal=replace(OWNER, **change))
    with pytest.raises(Problem) as error:
        await ask.ask(other, "Why?", **continuation(result))
    assert error.value.code == "ask_conversation_expired"
    assert (await ask.save_answer(other, **save_args(result)))["type"] == "text"
    await ask.clear_conversation(other, continuation(result)["conversation_id"])
    assert continuation(await ask.ask(ctx, "Why?", **continuation(result)))["after_turn"] == 2


@pytest.mark.parametrize("bad_turn", [True, False, -1, 1.5, "0", None])
async def test_continuation_requires_a_strict_turn_number(ai_host, bad_turn):
    host, _ = ai_host
    ctx = Ctx(host, "ask", principal=OWNER)
    args = continuation(await ask.ask(ctx, start=True))
    with pytest.raises(ValueError):
        await ask.ask(ctx, "Why?", conversation_id=args["conversation_id"], after_turn=bad_turn)


async def test_old_turn_is_rejected_without_another_provider_call(ai_host):
    host, provider = ai_host
    await Notes(host.store, OWNER).create("Spunto uses SQLite")
    ctx = Ctx(host, "ask", principal=OWNER)
    args = continuation(await ask.ask(ctx, start=True))
    await ask.ask(ctx, "What database does Spunto use?", **args)
    count = len(provider.requests)
    with pytest.raises(Problem) as error:
        await ask.ask(ctx, "Why?", **args)
    assert error.value.code == "ask_conversation_changed" and len(provider.requests) == count


async def test_clear_fences_in_flight_answer_and_concurrent_turns(ai_host):
    host, provider = ai_host
    await Notes(host.store, OWNER).create("Spunto uses SQLite")
    ctx = Ctx(host, "ask", principal=OWNER)
    args = continuation(await ask.ask(ctx, start=True))
    entered, release = asyncio.Event(), asyncio.Event()

    async def pause():
        entered.set()
        await release.wait()

    provider.on_chat = pause
    task = asyncio.create_task(ask.ask(ctx, "What database does Spunto use?", **args))
    await entered.wait()
    with pytest.raises(Problem) as error:
        await ask.ask(ctx, "Why?", **args)
    assert error.value.code == "ask_question_in_progress"
    await ask.clear_conversation(ctx, args["conversation_id"])
    release.set()
    with pytest.raises(Problem) as cleared:
        await task
    assert cleared.value.code == "ask_conversation_expired"
    assert args["conversation_id"] not in ask.memory(ctx)[0]
    assert not ask.receipts(ctx)


async def test_clear_invalidates_future_saves_but_preserves_saved_notes(ai_host):
    host, _ = ai_host
    notes = Notes(host.store, OWNER)
    await notes.create("Spunto uses SQLite")
    ctx = Ctx(host, "ask", principal=OWNER)
    first = await ask.ask(ctx, "What database does Spunto use?")
    second = await ask.ask(ctx, "Why?", **continuation(first))
    saved = await ask.save_answer(ctx, **save_args(first))
    await ask.clear_conversation(ctx, continuation(second)["conversation_id"])
    assert (await ask.save_answer(ctx, **save_args(second)))["type"] == "text"
    assert (await notes.get(saved["ids"][0]))["machine"]


async def test_explicit_save_already_accepted_may_complete_after_clear(ai_host, monkeypatch):
    host, _ = ai_host
    notes = Notes(host.store, OWNER)
    await notes.create("Spunto uses SQLite")
    ctx = Ctx(host, "ask", principal=OWNER)
    result = await ask.ask(ctx, "What database does Spunto use?")
    entered, release = asyncio.Event(), asyncio.Event()
    create = PluginNotes.create

    async def delayed_create(self, *args, **kwargs):
        entered.set()
        await release.wait()
        return await create(self, *args, **kwargs)

    monkeypatch.setattr(PluginNotes, "create", delayed_create)
    task = asyncio.create_task(ask.save_answer(ctx, **save_args(result)))
    await entered.wait()
    await ask.clear_conversation(ctx, continuation(result)["conversation_id"])
    release.set()
    saved = await task
    assert (await notes.get(saved["ids"][0]))["machine"]
    assert not ask.receipts(ctx)


async def test_idle_expiry_and_bounded_history(ai_host, monkeypatch):
    host, _ = ai_host
    await Notes(host.store, OWNER).create("Spunto uses SQLite")
    ctx = Ctx(host, "ask", principal=OWNER)
    result = await ask.ask(ctx, "What database does Spunto use?")
    for _ in range(ask.MAX_TURNS + 1):
        result = await ask.ask(ctx, "Why?", **continuation(result))
    key = continuation(result)["conversation_id"]
    assert len(ask.conversation(ctx, key)["history"]) == ask.MAX_TURNS
    now = host.clock()
    monkeypatch.setattr(host, "clock", lambda: now + ask.RECEIPT_TTL + 1)
    expired = Ctx(host, "ask", principal=OWNER)
    with pytest.raises(Problem) as error:
        await ask.ask(expired, "Why?", **continuation(result))
    assert error.value.code == "ask_conversation_expired"
    assert not ask.receipts(expired)


async def test_newer_generation_survives_old_in_flight_cleanup(ai_host):
    host, provider = ai_host
    await Notes(host.store, OWNER).create("Spunto uses SQLite")
    old = Ctx(host, "ask", principal=OWNER)
    entered, release = asyncio.Event(), asyncio.Event()

    async def pause():
        entered.set()
        await release.wait()

    provider.on_chat = pause
    task = asyncio.create_task(ask.ask(old, "What database does Spunto use?"))
    await entered.wait()
    await host.set_ai_route("ask", {"chat": "fixture", "embed": "fixture"})
    current = Ctx(host, "ask", principal=OWNER)
    args = continuation(await ask.ask(current, start=True))
    release.set()
    with pytest.raises(ContextError):
        await task
    assert ask.conversation(current, args["conversation_id"])["turn"] == 0


def test_escaped_history_budget_and_reference_numbers():
    turns = [
        {
            "question": "&" * 500,
            "answer": "<" * 1200,
            "citations": ["source"],
            "fingerprints": {"source": "fingerprint"},
        }
    ] * ask.MAX_TURNS
    raw = ask.history_context(turns, [{"id": "source", "evidence_fingerprint": "fingerprint"}])
    assert len(html.escape(raw)) <= ask.MAX_HISTORY
    with pytest.raises(ValueError, match="invalid_citation_number"):
        ask.Answer(found=True, answer="SQLite [2]", citations=["source"])
    assert ask.Answer(
        found=True,
        answer="Use arr[0], `[42]`, and code below [1].\n```\n[99]\n```",
        citations=["source"],
    ).found


async def test_modern_discussion_without_notes_keeps_followups_and_explicit_save(ai_host):
    host, provider = ai_host
    notes = Notes(host.store, OWNER)
    ctx = Ctx(host, "ask", principal=OWNER)
    started = await ask.ask(ctx, start=True, conversational=True)
    reasoning = ("A reversible experiment lets you learn before you commit. " * 26).strip()
    assert len(reasoning) > 1200
    provider.ask_answer = {
        "mode": "discussion",
        "paragraphs": [reasoning],
        "citations": [],
    }
    initial = await host.invoke_command(
        "ask",
        "ask",
        {"question": "Come posso provare un approccio diverso?", **continuation(started)},
        principal=OWNER,
    )
    assert all(item["type"] != "notes" for item in initial["items"])
    assert not (await notes.list())["notes"]
    second = await ask.ask(ctx, "E se invece fossimo in due?", **continuation(initial))
    assert reasoning in html.unescape(prompt(provider))
    assert continuation(second)["after_turn"] == 2
    saved = await ask.save_answer(ctx, **save_args(initial))
    note = await notes.get(saved["ids"][0])
    assert note["body"].endswith(reasoning)
    assert note["machine"]
    assert (await ask.save_answer(ctx, **save_args(initial))) == saved


async def test_modern_notes_only_without_evidence_uses_no_chat(ai_host):
    host, provider = ai_host
    ctx = Ctx(host, "ask", principal=OWNER)
    started = await ask.ask(ctx, start=True, conversational=True)
    chat_count = len(provider.chat_bodies)
    result = await ask.ask(ctx, "What did I agree?", **continuation(started), notes_only=True)
    assert len(provider.chat_bodies) == chat_count
    assert all(item.get("id") != "save-answer" for item in result["items"])
    assert continuation(result)["after_turn"] == 1


async def test_modern_grounded_passage_is_the_provider_evidence(ai_host):
    host, provider = ai_host
    notes = Notes(host.store, OWNER)
    note = await notes.create(
        "Synthetic workshop note. The spare key is stored in a blue envelope."
    )
    provider.ask_answer = {
        "mode": "notes",
        "paragraphs": ["The spare key is in the blue envelope [1]."],
        "citations": [note["id"]],
    }
    ctx = Ctx(host, "ask", principal=OWNER)
    started = await ask.ask(ctx, start=True, conversational=True)
    result = await ask.ask(ctx, "Where is the spare key?", **continuation(started))
    validate_output(result)
    source = next(item["ids"][0] for item in result["items"] if item["type"] == "notes")
    assert source["id"] == note["id"]
    assert source["reason"] in html.unescape(prompt(provider))
    assert "blue envelope" in source["reason"]
    for payload in (
        {"mode": "notes", "paragraphs": ["Unsupported"], "citations": []},
        {"mode": "discussion", "paragraphs": ["Wrong."], "citations": [note["id"]]},
        {
            "mode": "discussion",
            "paragraphs": ["Invented [source](/n/missing)"],
            "citations": [],
        },
        {"mode": "insufficient", "paragraphs": ["Invented"], "citations": []},
    ):
        with pytest.raises(ValueError):
            ask.ConversationAnswer.model_validate(payload)


async def test_collection_scope_rejects_stale_receipts_and_excludes_unrelated_notes(
    ai_host, monkeypatch
):
    host, provider = ai_host
    notes = Notes(host.store, OWNER)
    source = await notes.create("The workshop checklist has blue tags.")
    unrelated = await notes.create("The workshop checklist has red tags.")
    snapshot = {
        "id": "collection",
        "revision": 2,
        "brief": "Workshop brief",
        "source_ids": [source["id"]],
    }

    async def context(ctx, collection_id):
        assert collection_id == snapshot["id"]
        return dict(snapshot)

    monkeypatch.setattr(ask, "collection_context", context)
    ctx = Ctx(host, "ask", principal=OWNER)
    provider.ask_answer = {
        "mode": "notes",
        "paragraphs": ["The tags are blue [1]."],
        "citations": [source["id"]],
    }
    started = await ask.ask(ctx, start=True, conversational=True, collection_id="collection")
    response = await ask.ask(
        ctx, "What colors are the workshop checklist tags?", **continuation(started)
    )
    outbound = html.unescape(prompt(provider))
    assert source["id"] in outbound and unrelated["id"] not in outbound
    assert '<collection-data untrusted="true">Workshop brief</collection-data>' in outbound
    snapshot["revision"] = 3
    with pytest.raises(Problem) as failure:
        await ask.ask(ctx, "E se invece?", **continuation(response))
    assert failure.value.code == "ask_collection_changed"
    with pytest.raises(Problem):
        await ask.save_answer(ctx, **save_args(response))


async def test_discussion_context_cannot_replay_passages_from_a_now_private_note(ai_host):
    host, provider = ai_host
    notes = Notes(host.store, OWNER)
    private_later = await notes.create("Synthetic workshop uses STALE-MARKER as a code name.")
    ctx = Ctx(host, "ask", principal=OWNER)
    provider.ask_answer = {
        "mode": "discussion",
        "paragraphs": ["For a synthetic workshop, brainstorm variations on STALE-MARKER."],
        "citations": [],
    }
    started = await ask.ask(ctx, start=True, conversational=True)
    first = await ask.ask(ctx, "Brainstorm the synthetic workshop", **continuation(started))
    assert private_later["id"] in prompt(provider)
    await notes.flags(private_later["id"], {"private": True})
    provider.ask_answer = {
        "mode": "discussion",
        "paragraphs": ["Consider a reversible approach."],
        "citations": [],
    }
    await ask.ask(ctx, "What if we need a smaller approach?", **continuation(first))
    assert private_later["id"] not in prompt(provider)
    assert "STALE-MARKER" not in prompt(provider)
    refused = await ask.save_answer(ctx, **save_args(first))
    assert refused["type"] == "text" and "changed" in refused["text"]
    assert len((await notes.list())["notes"]) == 1


async def test_chat_choice_reaches_provider_and_is_scoped_to_one_conversation(ai_host):
    host, provider = ai_host
    available = ["alternative", "fixture-chat"]

    @provider.app.get("/v1/models")
    async def inventory():
        return {"data": [{"id": model} for model in available]}

    ctx = Ctx(host, "ask", principal=OWNER)
    provider.ask_answer = {"mode": "discussion", "paragraphs": ["Try a sketch."], "citations": []}
    first = continuation(await ask.ask(ctx, start=True, conversational=True))
    answer = await ask.ask(ctx, "How can I brainstorm?", **first, chat_model="alternative")
    next_turn = continuation(answer)
    with pytest.raises(Problem) as changed:
        await ask.ask(ctx, "And then?", **next_turn, chat_model="fixture-chat")
    assert changed.value.code == "ask_conversation_changed"
    await ask.ask(ctx, "And then?", **next_turn)
    other = continuation(await ask.ask(ctx, start=True, conversational=True))
    await ask.ask(ctx, "Any ideas?", **other)
    assert [body["model"] for body in provider.chat_bodies] == [
        "alternative",
        "alternative",
        "fixture-chat",
    ]
    available.remove("alternative")
    with pytest.raises(AIError, match="ai_model_invalid"):
        await ask.ask(
            ctx,
            "What's next?",
            **continuation(
                await ask.ask(ctx, start=True, conversational=True, chat_model="alternative")
            ),
        )
    assert len(provider.chat_bodies) == 3


@pytest.mark.parametrize("default_external", [False, True])
async def test_chat_choice_respects_effective_destination_locality(
    ai_host, monkeypatch, default_external
):
    host, _ = ai_host
    profile = host.gateway.profile_for("chat").model_copy(
        update={"base_url": "http://router.svc/v1"}
    )

    async def models(connection, *, transport):
        assert connection == profile
        return [
            {"id": "fixture-chat", "capabilities": ["chat"], "leaves_network": default_external},
            {"id": "local", "capabilities": ["chat"], "leaves_network": False},
            {"id": "remote", "capabilities": ["chat"], "leaves_network": True},
            {"id": "vector", "capabilities": ["embed"], "leaves_network": False},
        ]

    monkeypatch.setattr("spunto.ai.selection.inventory", models)
    choices = await host.gateway.selection.ask_models(profile)
    assert choices["external"] == default_external
    assert choices["models"] == (
        ["fixture-chat", "local", "remote"] if default_external else ["fixture-chat", "local"]
    )
    assert choices["external_models"] == (["fixture-chat", "remote"] if default_external else [])


async def test_local_choice_rejects_router_drift_on_follow_up(ai_host, monkeypatch):
    host, provider = ai_host
    ctx = Ctx(host, "ask", principal=OWNER)
    catalog = {"models": ["flex"], "external_models": []}

    async def inventory(profile):
        return catalog

    monkeypatch.setattr(host.gateway.selection, "ask_models", inventory)
    provider.ask_answer = {"mode": "discussion", "paragraphs": ["Try a sketch."], "citations": []}
    first = await ask.ask(
        ctx, start=True, conversational=True, chat_model="flex", chat_model_local_only=True
    )
    response = await ask.ask(ctx, "Where can I start?", **continuation(first))
    catalog["external_models"] = ["flex"]
    with pytest.raises(AIError, match="ai_model_locality_changed"):
        await ask.ask(ctx, "And then?", **continuation(response))
    assert [body["model"] for body in provider.chat_bodies] == ["flex"]
    chat = next(row for row in provider.requests if row["path"] == "/v1/chat/completions")
    assert chat["headers"]["x-svincolo-require-locality"] == "local"
