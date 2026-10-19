"""Tag cleanup remains a bounded, explicit Review flow on real SQLite."""

import json
from datetime import UTC, datetime
from pathlib import Path

import httpx
import pytest
from fastapi import FastAPI, Request

from spunto.ai.errors import AIError
from spunto.ai.gateway import Gateway
from spunto.ai.output import closed_schema, parse_output
from spunto.ai.profiles import Profile
from spunto.ai.vectors import VectorIndex
from spunto.api.errors import Problem
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.core.suggestions import decide, pending
from spunto.host import Ctx, Host, validate_output
from spunto.host.testing import FakeClock
from spunto.plugins import label_gardener as gardener

pytestmark = pytest.mark.anyio
OWNER = Principal("curation-owner", "session", "Owner", "full", private_ok=True)


class Provider:
    def __init__(self):
        self.app = FastAPI()
        self.app.post("/v1/chat/completions")(self.chat)
        self.answer = {"proposals": []}
        self.requests = []
        self.callback = None

    async def chat(self, request: Request):
        body = await request.json()
        self.requests.append(body)
        if self.callback:
            callback, self.callback = self.callback, None
            await callback()
        return {
            "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(self.answer)}}]
        }


@pytest.fixture
async def curation(server):
    provider = Provider()
    profile = Profile(
        name="fixture",
        base_url="http://provider.test/v1",
        chat_model="fixture-chat",
        embed_model="fixture-embed",
        rate_per_min=100000,
        timeout_s=1,
    )
    settings = server.services.settings.model_copy(
        update={
            "ai_profiles": {"fixture": profile},
            "ai_chat_profile": "fixture",
            "ai_embed_profile": "fixture",
        }
    )
    gateway = Gateway(server.services.store, settings, transport=httpx.ASGITransport(provider.app))
    clock = FakeClock(now=int(datetime.now(UTC).timestamp() * 1000))
    host = Host(
        server.services.store,
        settings,
        notes_factory=PluginNotes,
        gateway=gateway,
        vectors=VectorIndex(server.services.store, gateway),
        catalog=[Path(gardener.__file__).parent],
        clock=clock,
    )
    await host.discover()
    await host.enable("label-gardener", permissions=host.manifests["label-gardener"]["permissions"])
    original = server.services.host
    server.services.host = host
    try:
        yield host, provider, Notes(host.store, OWNER), clock
    finally:
        server.services.host = original
        await host.close()
        await gateway.close()


def context(host, unattended=False):
    return Ctx(
        host,
        "label-gardener",
        principal=OWNER,
        unattended=unattended,
        operation="command:review-now",
    )


async def proposals(host):
    return (await pending(host.store, OWNER, scope="review"))["suggestions"]


def proposal(old, new, *, note_ids=None, reason="Sampled usage supports this clearer tag."):
    return {"old": old, "new": new, "note_ids": note_ids or [], "reason": reason}


def prompt(provider):
    return provider.requests[-1]["messages"][1]["content"]


async def test_nullable_new_label_has_closed_schema_and_validates_actual_paths():
    closed_schema(gardener.Garden)
    parsed = parse_output(
        gardener.Garden,
        {"proposals": [proposal("obsolete", None), proposal("ideas", "knowledge")]},
        set(),
    )
    assert parsed.proposals[0].new is None
    for invalid in [
        proposal(None, None),
        proposal("bad tag", None),
        proposal("ideas", "bad tag"),
        proposal("ideas", None, reason="   "),
    ]:
        with pytest.raises(ValueError):
            parse_output(gardener.Garden, {"proposals": [invalid]}, set())


async def test_rename_to_new_valid_name_is_a_reasoned_proposal_until_owner_accepts(curation):
    host, provider, notes, _ = curation
    note = await notes.create("Architecture experiments #misc")
    provider.answer = {
        "proposals": [
            proposal(
                "misc",
                "architecture",
                reason="The sampled misc notes describe architecture experiments.",
            )
        ]
    }
    result = await host.invoke_command("label-gardener", "review-now", principal=OWNER)
    validate_output(result)
    assert "bounded sample" in result["text"] and "1 tag proposal" in result["text"]
    offered = await proposals(host)
    assert len(offered) == 1
    assert offered[0]["payload"] == {"old": "misc", "new": "architecture"}
    assert offered[0]["reason"] == provider.answer["proposals"][0]["reason"]
    assert (await notes.get(note["id"]))["body"] == note["body"]
    await decide(host.store, OWNER, offered[0]["id"], "accept")
    assert (await notes.get(note["id"]))["body"] == "Architecture experiments #architecture"


async def test_remove_tag_proposal_keeps_words_and_nested_tags_until_review(curation):
    host, provider, notes, _ = curation
    note = await notes.create("Keep these words #obsolete #obsolete/nested #useful")
    provider.answer = {
        "proposals": [
            proposal("obsolete", None, reason="The sampled obsolete tag is redundant with useful.")
        ]
    }
    await gardener.review_now(context(host))
    offered = (await proposals(host))[0]
    assert offered["payload"] == {"old": "obsolete", "new": None}
    assert (await notes.get(note["id"]))["body"] == note["body"]
    await decide(host.store, OWNER, offered["id"], "accept")
    current = await notes.get(note["id"])
    assert "Keep these words" in current["body"] and "#useful" in current["body"]
    assert "obsolete" not in current["body"]


@pytest.mark.parametrize("new", [None, "better-name"])
async def test_dismissed_cleanup_is_not_reoffered_by_manual_or_later_week(curation, new):
    host, provider, notes, clock = curation
    await notes.create("Synthetic old grouping #old")
    provider.answer = {"proposals": [proposal("old", new)]}
    await gardener.review_now(context(host))
    await decide(host.store, OWNER, (await proposals(host))[0]["id"], "dismiss")
    await gardener.review_now(context(host))
    assert await proposals(host) == []
    clock.advance(8 * 86400000)
    await gardener.weekly(context(host, unattended=True))
    assert await proposals(host) == []
    history = await context(host).notes.suggestion_history()
    assert len(history["suggestions"]) == 1 and history["suggestions"][0]["status"] == "dismissed"


async def test_manual_and_weekly_share_three_proposal_cap_and_do_not_autoapply(curation):
    host, provider, notes, clock = curation
    note = await notes.create("Separate concepts #one #two #three #four")
    provider.answer = {
        "proposals": [proposal("one", "first"), proposal("two", "second"), proposal("three", None)]
    }
    await gardener.review_now(context(host))
    assert len(await proposals(host)) == 3
    count = len(provider.requests)
    await gardener.weekly(context(host, unattended=True))
    limited = await gardener.review_now(context(host))
    assert "limit" in limited["text"] and len(provider.requests) == count
    for item in await proposals(host):
        await decide(host.store, OWNER, item["id"], "dismiss")
    assert "limit" in (await gardener.review_now(context(host)))["text"]
    assert len(provider.requests) == count
    assert (await notes.get(note["id"]))["body"] == note["body"]
    clock.advance(8 * 86400000)
    provider.answer = {"proposals": [proposal("four", "fourth")]}
    await gardener.weekly(context(host, unattended=True))
    assert len(await proposals(host)) == 1


async def test_usage_context_contains_past_dates_and_only_eligible_note_examples(curation):
    host, provider, notes, _ = curation
    old = await notes.create("Historical architecture #architecture", created_at=1609459200000)
    recent = await notes.create("Current architecture #architecture")
    private = await notes.create("PRIVATE-SYNTHETIC-ONLY #private-only", private=True)
    machine = await notes.create("MACHINE-SYNTHETIC-ONLY #machine-only")
    async with host.store.write() as conn:
        await conn.execute("UPDATE note SET machine=1 WHERE id=?", (machine["id"],))
    await gardener.review_now(context(host))
    sent = prompt(provider)
    assert old["id"] in sent and recent["id"] in sent
    assert private["id"] not in sent and machine["id"] not in sent
    assert "PRIVATE-SYNTHETIC-ONLY" not in sent and "MACHINE-SYNTHETIC-ONLY" not in sent
    assert "private-only" not in sent and "machine-only" not in sent
    data = sent.split("Vocabulary and past usage data: ", 1)[1].split("\n<note", 1)[0]
    usage = json.loads(data)
    assert usage[0]["path"] == "architecture" and usage[0]["sample_note_count"] == 2
    assert usage[0]["sample_first_note_created_at"].startswith("2021-01-01")
    assert usage[0]["sample_last_note_created_at"] == recent["created_at"]
    assert "never infer" in sent.lower() and "not the full taxonomy" in sent
    assert "not when tags were added" in sent


@pytest.mark.parametrize("change", ["body", "private"])
async def test_source_changes_during_inference_cannot_commit_cleanup(curation, change):
    host, provider, notes, _ = curation
    note = await notes.create("Historical source #old")
    provider.answer = {"proposals": [proposal("old", "clearer")]}

    async def mutate():
        if change == "body":
            await notes.edit(note["id"], "Owner changed source #old", note["version"])
        else:
            await notes.flags(note["id"], {"private": True})

    provider.callback = mutate
    with pytest.raises(Problem, match="source changed"):
        await gardener.review_now(context(host))
    assert await proposals(host) == []
    assert await context(host).storage.kv.get("week") is None


async def test_unknown_or_ungrounded_old_label_and_invalid_new_path_are_rejected(curation):
    host, provider, notes, _ = curation
    await notes.create("Known evidence #old")
    provider.answer = {"proposals": [proposal("invented", None)]}
    with pytest.raises(ValueError, match="invalid_label_rename"):
        await gardener.review_now(context(host))
    provider.answer = {"proposals": [proposal("old", "invalid tag path")]}
    with pytest.raises(AIError):
        await gardener.review_now(context(host))
    assert await proposals(host) == []


async def test_conflicting_cleanup_choices_for_same_old_tag_never_commit_partial_batch(curation):
    host, provider, notes, _ = curation
    await notes.create("Grounded tag #old")
    provider.answer = {"proposals": [proposal("old", "clearer"), proposal("old", None)]}
    with pytest.raises(ValueError, match="invalid_label_rename"):
        await gardener.review_now(context(host))
    assert await proposals(host) == []
    assert (await context(host).notes.suggestion_history())["suggestions"] == []
    assert await context(host).storage.kv.get("week") is None


async def test_full_note_context_budget_keeps_labeled_grounding_without_truncating_examples(
    curation,
):
    host, provider, notes, _ = curation
    for index in range(3):
        await notes.create(f"{index} " + "u" * 19998)
    labeled = await notes.create("l" * 19995 + " #old")
    oversized = await notes.create("x" * 21000 + " #old")
    provider.answer = {"proposals": [proposal("old", "clearer")]}
    await gardener.review_now(context(host))
    sent = prompt(provider)
    assert labeled["id"] in sent and oversized["id"] not in sent
    assert labeled["body"] in sent
    assert sent.count("<note id=") == 2
    assert len(sent) < 52000
    assert len(await proposals(host)) == 1


async def test_context_honestly_limits_recent_notes_and_label_vocabulary(curation):
    host, provider, notes, _ = curation
    oldest = await notes.create("Outside recent sample #topic", created_at=1609459200000)
    for index in range(501):
        await notes.create(f"Recent source {index} #topic #tag-{index % 55}")
    result = await gardener.review_now(context(host))
    validate_output(result)
    assert "500 recent" in result["text"] and "50 top labels" in result["text"]
    sent = prompt(provider)
    assert oldest["id"] not in sent
    data = json.loads(sent.split("Vocabulary and past usage data: ", 1)[1].split("\n<note", 1)[0])
    assert len(data) == 50
    topic = next(item for item in data if item["path"] == "topic")
    assert topic["sample_note_count"] == 500 and topic["count"] == 502
    assert sent.count("<note id=") <= 50


async def test_review_now_command_is_opt_in_online_and_no_new_note_or_tool_surface(curation):
    host, _, _, _ = curation
    manifest = host.manifests["label-gardener"]
    assert manifest["api"] == "0.9" and manifest["default"] == "off"
    assert manifest["contributes"]["commands"] == [
        {
            "id": "review-now",
            "title": "Review tags now",
            "where": ["palette", "slash"],
            "online": True,
        }
    ]
    assert not manifest["contributes"].get("mcp_tools")
    assert "notes.create" not in manifest["permissions"]
    result = await host.invoke_command(
        "label-gardener",
        "review-now",
        {"note_id": "01980000-0000-7000-8000-000000000099"},
        principal=OWNER,
    )
    validate_output(result)
    assert "Not enough eligible usage" in result["text"]
