"""Ephemeral Ask turns, caller boundaries and explicit per-answer saving."""

import asyncio
import html
from dataclasses import replace

import pytest
from test_plugins_ai import OWNER, state
from test_plugins_ai import ai_host as ai_host

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
    return [item for item in provider.requests if "messages" in item][-1]["messages"][1]["content"]


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
