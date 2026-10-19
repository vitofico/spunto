"""Past-tag learning stays bounded, live, fenced and owner-controlled on real SQLite."""

import html
import re

import httpx
import pytest
from test_plugins_ai import FakeProvider

from spunto.ai.errors import AIError
from spunto.ai.gateway import Gateway
from spunto.ai.profiles import Profile
from spunto.api.errors import Problem
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.core.suggestions import decide
from spunto.host import ContextError, Ctx, Host, validate_output
from spunto.plugins import label_suggestions

pytestmark = pytest.mark.anyio
OWNER = Principal("owner", "session", "Owner", "write", private_ok=True)
PLUGIN = "label-suggestions"


@pytest.fixture
async def smart(server):
    provider = FakeProvider()
    profile = Profile(
        name="smart-fixture",
        base_url="http://provider.test/v1",
        chat_model="fixture-chat",
        rate_per_min=100000,
        timeout_s=1,
    )
    settings = server.services.settings.model_copy(
        update={
            "ai_profiles": {"smart-fixture": profile},
            "ai_chat_profile": "smart-fixture",
            "ai_embed_profile": None,
        }
    )
    gateway = Gateway(server.services.store, settings, transport=httpx.ASGITransport(provider.app))
    host = Host(server.services.store, settings, notes_factory=PluginNotes, gateway=gateway)
    await host.discover()
    await host.enable(PLUGIN, permissions=host.manifests[PLUGIN]["permissions"])
    provider.label_answer = {"labels": [{"path": "Project/Spunto", "confidence": 0.97}]}
    try:
        yield host, provider, Notes(host.store, OWNER)
    finally:
        await host.close()
        await gateway.close()


def ctx(host):
    return Ctx(host, PLUGIN)


async def proposals(host):
    return (await ctx(host).notes.suggestion_history())["suggestions"]


def chat_prompt(provider):
    return [request for request in provider.requests if "messages" in request][-1]["messages"][1][
        "content"
    ]


async def test_chat_only_learns_real_owner_usage_inside_fences(smart):
    host, provider, notes = smart
    used = await notes.create("SQLite maintenance and restore ownership #Project/Spunto")
    target = await notes.create("SQLite needs a reliable backup procedure")
    await label_suggestions.saved(ctx(host), target["id"])
    prompt = chat_prompt(provider)
    assert '"target_note_id": "' + target["id"] + '"' in prompt
    assert '"past_usage"' in prompt and '"Project/Spunto"' in prompt
    assert f'<note id="{used["id"]}"' in prompt
    assert "SQLite maintenance and restore ownership" in prompt
    assert prompt.index(f'<note id="{used["id"]}"') < prompt.index(
        "SQLite maintenance and restore ownership"
    )
    assert (await notes.get(target["id"]))["labels"] == []
    assert (await proposals(host))[0]["status"] == "pending"


async def test_live_feedback_preferences_exclude_hidden_and_machine_sources(smart):
    host, provider, notes = smart
    accepted = await notes.create("Past accepted SQLite decision #Project/Spunto")
    refused = await notes.create("Past refused database decision #Project/Spunto")
    hidden = await notes.create("PRIVATE-HISTORY-CANARY database note #hidden-history")
    trashed = await notes.create("TRASHED-HISTORY-CANARY database note #trashed-history")
    agent_notes = Notes(host.store, Principal("fixture-agent", "pat", "Synthetic agent", "write"))
    agent = await agent_notes.create("AGENT-EXAMPLE-CANARY #Project/Spunto")
    await host.enable("develop", permissions=host.manifests["develop"]["permissions"])
    machine = await Ctx(host, "develop").notes.create("MACHINE-EXAMPLE-CANARY #Project/Spunto")
    assert machine["machine"] is True
    for note, decision in (
        (accepted, "accept"),
        (refused, "dismiss"),
        (hidden, "dismiss"),
        (trashed, "dismiss"),
        (agent, "dismiss"),
    ):
        proposal = await ctx(host).notes.suggest(
            note["id"], "add_label", {"path": "past-preference"}, note_version=note["version"]
        )
        await decide(host.store, OWNER, proposal["id"], decision)
    await notes.flags(hidden["id"], {"private": True})
    await notes.flags(trashed["id"], {"trashed": True})
    target = await notes.create("SQLite decisions need a current target")
    await label_suggestions.saved(ctx(host), target["id"])
    prompt = chat_prompt(provider)
    assert '"decision": "accepted"' in prompt and '"decision": "dismissed"' in prompt
    for value in (
        hidden["id"],
        trashed["id"],
        agent["id"],
        machine["id"],
        "PRIVATE-HISTORY-CANARY",
        "TRASHED-HISTORY-CANARY",
        "AGENT-EXAMPLE-CANARY",
        "MACHINE-EXAMPLE-CANARY",
    ):
        assert value not in prompt
    assert '"note_id": "' + accepted["id"] + '"' in prompt
    assert '"note_id": "' + refused["id"] + '"' in prompt


async def test_only_high_confidence_existing_tags_autoapply_and_explicit_false_wins(smart):
    host, provider, notes = smart
    await notes.create("Prior personal vocabulary #Project/Spunto")
    await host.set_settings(PLUGIN, {"auto_accept_existing": True, "confidence_threshold": 0.9})
    provider.label_answer = {
        "labels": [
            {"path": "project/spunto", "confidence": 0.97},
            {"path": "new-topic", "confidence": 0.99},
        ]
    }
    target = await notes.create("A current SQLite project needs tagging")
    await label_suggestions.saved(ctx(host), target["id"])
    current = await notes.get(target["id"])
    assert current["labels"] == ["Project/Spunto"]
    statuses = {row["payload"]["path"]: row["status"] for row in await proposals(host)}
    assert statuses == {"Project/Spunto": "accepted", "new-topic": "pending"}
    await host.set_settings(PLUGIN, {"auto_accept_existing": False})
    other = await notes.create("Another SQLite project needs tagging")
    await label_suggestions.saved(ctx(host), other["id"])
    assert (await notes.get(other["id"]))["labels"] == []
    assert host.states[PLUGIN]["settings"]["auto_accept_existing"] is False


async def test_existing_label_below_owner_threshold_remains_pending(smart):
    host, provider, notes = smart
    await notes.create("Prior personal vocabulary #Project/Spunto")
    await host.set_settings(PLUGIN, {"auto_accept_existing": True, "confidence_threshold": 0.95})
    provider.label_answer = {"labels": [{"path": "Project/Spunto", "confidence": 0.9}]}
    target = await notes.create("SQLite should honor the owner's confidence threshold")
    await label_suggestions.saved(ctx(host), target["id"])
    assert (await notes.get(target["id"]))["labels"] == []
    assert (await proposals(host))[0]["status"] == "pending"


@pytest.mark.parametrize("change", ["body", "private", "trash"])
async def test_past_example_changed_during_provider_call_never_writes(smart, change):
    host, provider, notes = smart
    used = await notes.create("Past library maintenance evidence #Project/Spunto")
    target = await notes.create("SQLite should learn from this evidence")

    async def mutate():
        if change == "body":
            await notes.edit(
                used["id"], "Owner changed this source #Project/Spunto", used["version"]
            )
        else:
            await notes.flags(used["id"], {"private" if change == "private" else "trashed": True})

    provider.on_chat = mutate
    await host.set_settings(PLUGIN, {"auto_accept_existing": True})
    await label_suggestions.saved(ctx(host), target["id"])
    assert not await proposals(host)
    assert not await ctx(host).storage.records.get("processed", target["id"])
    assert (await notes.get(target["id"]))["body"] == target["body"]


async def test_atomic_source_guard_rejects_a_change_after_plugin_recheck(smart, monkeypatch):
    host, provider, notes = smart
    used = await notes.create("Past SQLite note has useful tags #Project/Spunto")
    target = await notes.create("SQLite needs a label after review")
    original = PluginNotes.suggest_many

    async def change_before_write(self, *args, **kwargs):
        await notes.flags(used["id"], {"private": True})
        return await original(self, *args, **kwargs)

    monkeypatch.setattr(PluginNotes, "suggest_many", change_before_write)
    with pytest.raises((ContextError, Problem)):
        await label_suggestions.saved(ctx(host), target["id"])
    assert not await proposals(host)
    assert not await ctx(host).storage.records.get("processed", target["id"])
    assert (await notes.get(target["id"]))["body"] == target["body"]


async def test_provider_failure_changes_no_suggestions_or_processing_receipts(smart):
    host, provider, notes = smart
    await notes.create("Prior personal vocabulary #Project/Spunto")
    target = await notes.create("SQLite notes should stay unchanged on failure")
    provider.mode = "500"
    with pytest.raises(AIError):
        await label_suggestions.saved(ctx(host), target["id"])
    assert not await proposals(host)
    assert not await ctx(host).storage.records.get("processed", target["id"])
    assert (await notes.get(target["id"]))["body"] == target["body"]


async def test_examples_and_fenced_excerpts_have_fixed_budgets(smart):
    host, provider, notes = smart
    for index in range(9):
        await notes.create(f"EXAMPLE-{index} " + "word " * 1000 + " #Project/Spunto")
    target = await notes.create("SQLite target receives bounded personal examples")
    await label_suggestions.saved(ctx(host), target["id"])
    prompt = chat_prompt(provider)
    assert prompt.count('<note id="') <= 1 + label_suggestions.MAX_PAST_NOTES
    assert len(prompt) < 20_000


async def test_long_target_reserves_real_fenced_past_examples_and_drops_each_tail(smart):
    host, provider, notes = smart
    examples = [
        await notes.create(
            f"PAST-START-{index} " + "past evidence " * 500 + f"PAST-TAIL-{index} #Project/Spunto"
        )
        for index in range(2)
    ]
    target = await notes.create("TARGET-START " + "SQLite target " * 1000 + "TARGET-TAIL")
    await label_suggestions.saved(ctx(host), target["id"])
    prompt = chat_prompt(provider)
    bodies = {
        match[1]: html.unescape(match[2])
        for match in re.finditer(r'<note id="([^"]+)"[^>]*>(.*?)</note>', prompt, re.DOTALL)
    }
    assert bodies[target["id"]].startswith("TARGET-START")
    assert len(bodies[target["id"]]) == label_suggestions.TARGET_CHARS
    assert "TARGET-TAIL" not in prompt
    for index, example in enumerate(examples):
        assert bodies[example["id"]].startswith(f"PAST-START-{index}")
        assert len(bodies[example["id"]]) == label_suggestions.EXAMPLE_CHARS
        assert f"PAST-TAIL-{index}" not in prompt
    assert sum(map(len, bodies.values())) <= 20_000


async def test_recent_feedback_after_sixty_old_decisions_is_used_and_paginates(smart):
    host, provider, notes = smart
    source = await notes.create("Owner feedback on a past SQLite decision")
    decision_ids = []
    for index in range(65):
        proposal = await ctx(host).notes.suggest(
            source["id"],
            "add_label",
            {"path": f"feedback/old-{index}"},
            note_version=source["version"],
        )
        await decide(host.store, OWNER, proposal["id"], "dismiss")
        decision_ids.append(proposal["id"])
    latest = await ctx(host).notes.suggest(
        source["id"],
        "add_label",
        {"path": "recent-owner-preference"},
        note_version=source["version"],
    )
    await decide(host.store, OWNER, latest["id"], "dismiss")
    decision_ids.append(latest["id"])
    oldest = await ctx(host).notes.suggestion_history(limit=2)
    assert [item["id"] for item in oldest["suggestions"]] == decision_ids[:2]
    recent = await ctx(host).notes.suggestion_history(limit=2, recent=True)
    following = await ctx(host).notes.suggestion_history(
        cursor=recent["next_cursor"], limit=2, recent=True
    )
    assert [item["id"] for item in recent["suggestions"] + following["suggestions"]] == list(
        reversed(decision_ids)
    )[:4]
    target = await notes.create("SQLite current target should use recent feedback")
    provider.label_answer = {"labels": []}
    await label_suggestions.saved(ctx(host), target["id"])
    prompt = chat_prompt(provider)
    assert '"path": "recent-owner-preference"' in prompt
    assert '"path": "feedback/old-0"' not in prompt


async def test_owner_suggest_now_reruns_unchanged_note_without_ignoring_dismissal(smart):
    host, provider, notes = smart
    await notes.create("Prior personal vocabulary #Project/Spunto")
    target = await notes.create("SQLite should support explicit label review")
    await label_suggestions.saved(ctx(host), target["id"])
    first = (await proposals(host))[0]
    await decide(host.store, OWNER, first["id"], "dismiss")
    provider.label_answer = {"labels": []}
    result = await host.invoke_command(
        PLUGIN, "suggest-now", {"note_id": target["id"]}, principal=OWNER
    )
    validate_output(result)
    assert "0 existing labels applied" in result["text"]
    assert '"dismissed": ["Project/Spunto"]' in chat_prompt(provider)
    assert (await notes.get(target["id"]))["labels"] == []
    assert (await proposals(host))[0]["status"] == "dismissed"
