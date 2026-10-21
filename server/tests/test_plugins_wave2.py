"""Second-wave contracts on real SQLite with a fenced, synthetic ASGI provider."""

import json
import re
from pathlib import Path

import httpx
import pytest
from fastapi import FastAPI, Request

from spunto.ai.errors import AIError
from spunto.ai.gateway import Gateway
from spunto.ai.profiles import Profile
from spunto.ai.vectors import VectorIndex
from spunto.api.errors import Problem
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.core.suggestions import decide, pending
from spunto.host import ContextError, Ctx, Host, validate_output
from spunto.plugins import (
    develop,
    digest,
    duplicates,
    label_gardener,
    links,
    tidy,
    translate,
)

pytestmark = pytest.mark.anyio
OWNER = Principal("owner", "session", "Owner", "write", private_ok=True)
PLUGINS = [
    "title-summary",
    "tidy",
    "duplicates",
    "label-gardener",
    "links",
    "digest",
    "develop",
    "translate",
]


class Provider:
    def __init__(self):
        self.app = FastAPI()
        self.app.post("/v1/chat/completions")(self.chat)
        self.app.post("/v1/embeddings")(self.embed)
        self.requests = []
        self.override = None
        self.callback = None

    async def chat(self, request: Request):
        value = await request.json()
        self.requests.append(value)
        prompt = value["messages"][1]["content"]
        ids = re.findall(r'<note id="([^"]+)"', prompt)
        if self.callback:
            await self.callback()
        if "Propose a concise" in prompt:
            answer = {"title": "Synthetic long note", "summary": "A short faithful summary."}
        elif "Tidy punctuation" in prompt:
            answer = {"body": "Synthetic sentence.\n\nSecond paragraph.", "meaning_preserved": True}
        elif "Translate between" in prompt:
            answer = {"body": "Testo sintetico."}
        elif "label improvements" in prompt:
            answer = {
                "proposals": [
                    {
                        "old": None,
                        "new": "synthesis",
                        "note_ids": ids[:2],
                        "reason": "Two notes share a theme.",
                    }
                ]
            }
        else:
            answer = {"body": "Synthetic grounded follow-up.", "citations": ids[:2]}
        content = self.override if self.override is not None else json.dumps(answer)
        return {"choices": [{"finish_reason": "stop", "message": {"content": content}}]}

    async def embed(self, request: Request):
        value = await request.json()
        self.requests.append(value)
        return {
            "data": [
                {"index": i, "embedding": [1.0, 0.2, 0.1]} for i, _ in enumerate(value["input"])
            ]
        }


@pytest.fixture
async def wave(server):
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
    vectors = VectorIndex(server.services.store, gateway)
    host = Host(
        server.services.store, settings, notes_factory=PluginNotes, gateway=gateway, vectors=vectors
    )
    await host.discover()
    for plugin_id in PLUGINS:
        await host.enable(plugin_id, permissions=host.manifests[plugin_id]["permissions"])
    await vectors.sync_routes(
        {id: "fixture" for id in PLUGINS if "ai.embed" in host.manifests[id]["permissions"]}
    )
    provider.requests.clear()
    try:
        yield host, provider, Notes(host.store, OWNER)
    finally:
        await host.close()
        await gateway.close()


def ctx(host, id, *, unattended=False):
    return Ctx(host, id, principal=OWNER, unattended=unattended)


async def review(host):
    return (await pending(host.store, OWNER, scope="review"))["suggestions"]


async def test_wave_manifests_opt_in_and_api(wave):
    host, _, _ = wave
    assert all(
        host.manifests[id]["api"] == "0.9" and host.manifests[id]["default"] == "off"
        for id in PLUGINS
    )
    assert host.manifests["duplicates"]["permissions"] == ["notes.read", "suggest", "ai.embed"]
    assert not any(x.startswith(("ai.", "net.")) for x in host.manifests["links"]["permissions"])


async def test_title_summary_and_tidy_are_proposals_with_ordinary_history(wave):
    host, provider, notes = wave
    note = await notes.create(" ".join(["synthetic"] * 300))
    result = await host.invoke_command(
        "title-summary", "summarize", {"note_id": note["id"]}, principal=OWNER
    )
    validate_output(result)
    current = await notes.get(note["id"])
    assert current["body"] == note["body"] and current["version"] == 1
    assert current["annotations"][0]["payload"]["text"] == "A short faithful summary."
    await decide(host.store, OWNER, result["suggestion_id"], "accept")
    assert (await notes.get(note["id"]))["body"].startswith("# Synthetic long note")
    short = await notes.create("synthetic sentence second paragraph")
    before = await notes.get(short["id"])
    result = await host.invoke_command("tidy", "tidy", {"note_id": short["id"]}, principal=OWNER)
    assert result["items"][0]["type"] == "diff"
    assert (await notes.get(short["id"]))["body"] == before["body"]
    await decide(host.store, OWNER, result["items"][1]["suggestion_id"], "dismiss")
    count = len(provider.requests)
    await tidy.tidy(ctx(host, "tidy"), short["id"])
    assert len(provider.requests) == count


async def test_tidy_preview_focuses_on_changed_lines_and_keeps_full_proposal(wave):
    host, provider, notes = wave
    before = [f"Synthetic unchanged line {index}." for index in range(24)]
    after = [*before]
    after[12] = "Synthetic corrected sentence."
    note = await notes.create("\n".join(before))
    provider.override = json.dumps({"body": "\n".join(after), "meaning_preserved": True})
    result = await host.invoke_command("tidy", "tidy", {"note_id": note["id"]}, principal=OWNER)
    hunks = result["items"][0]["hunks"]
    assert len(hunks) == 1
    assert hunks[0]["old_start"] == hunks[0]["new_start"] == 11
    assert hunks[0]["old_lines"] == before[10:15]
    assert hunks[0]["new_lines"] == after[10:15]
    assert result["items"][1]["label"] == "Apply changes"
    await decide(host.store, OWNER, result["items"][1]["suggestion_id"], "accept")
    assert (await notes.get(note["id"]))["body"] == "\n".join(after)


@pytest.mark.parametrize("many_changes", [False, True])
async def test_tidy_preview_keeps_final_newlines_and_native_hunk_budget(wave, many_changes):
    host, provider, notes = wave
    if many_changes:
        before, after = [], []
        for index in range(101):
            before.append(f"Sentence {index}")
            after.append(f"Sentence {index}.")
            context = [f"Same {index}.{line}" for line in range(5)]
            before.extend(context)
            after.extend(context)
        original, proposed = "\n".join(before), "\n".join(after)
    else:
        original, proposed = "Synthetic sentence.", "Synthetic sentence.\n"
    note = await notes.create(original)
    provider.override = json.dumps({"body": proposed, "meaning_preserved": True})
    result = await host.invoke_command("tidy", "tidy", {"note_id": note["id"]}, principal=OWNER)
    preview = result["items"][0]
    assert preview["type"] == "diff" and 1 <= len(preview["hunks"]) <= 100
    assert preview["hunks"][0]["old_lines"] != preview["hunks"][0]["new_lines"]
    if not many_changes:
        assert preview["hunks"][0]["new_lines"] == ["Synthetic sentence.", ""]
    await decide(host.store, OWNER, result["items"][1]["suggestion_id"], "accept")
    assert (await notes.get(note["id"]))["body"] == proposed


async def test_links_choose_notes_by_title_and_search_with_revision_guards(wave):
    host, _, notes = wave
    source = await notes.create("Source note for links")
    destination = await notes.create("# Synthetic chosen target\nUseful evidence")
    await notes.create("# Synthetic chosen target\nDifferent note, same title")
    result = await host.invoke_command(
        "links", "link", {"note_id": source["id"], "query": "chosen"}, principal=OWNER
    )
    validate_output(result)
    schema = result["items"][1]["schema"]
    assert "target_id" not in schema["properties"]
    choices = schema["properties"]["target"]["enum"]
    assert len(choices) == len(set(choices)) == 3
    assert all("Synthetic chosen target" in choice for choice in choices[1:])
    assert all(destination["id"] not in choice for choice in choices)
    args = {key: value["default"] for key, value in schema["properties"].items()}
    index = next(
        index
        for index, choice in enumerate(args["source_choices"], 1)
        if choice.startswith(destination["id"])
    )
    args["target"] = choices[index]
    await notes.edit(destination["id"], "Changed target words", destination["version"])
    with pytest.raises(Problem, match="note changed"):
        await host.invoke_command("links", "link", args, principal=OWNER)
    assert not await review(host)
    await notes.edit(source["id"], "Changed source words", source["version"])
    with pytest.raises(Problem, match="This note changed"):
        await host.invoke_command("links", "link", args, principal=OWNER)


async def test_links_direct_target_compatibility_and_duplicate_protection(wave):
    host, _, notes = wave
    source = await notes.create("Source note for linking")
    destination = await notes.create("Destination note")
    args = {"note_id": source["id"], "target_id": destination["id"]}
    result = await host.invoke_command("links", "link", args, principal=OWNER)
    await decide(host.store, OWNER, result["suggestion_id"], "accept")
    assert "[Destination note]" in (await notes.get(source["id"]))["body"]
    result = await host.invoke_command("links", "link", args, principal=OWNER)
    assert result["text"] == "This note already links to the selected note."
    assert len(links.targets((await notes.get(source["id"]))["body"])) == 1


async def test_link_captions_escape_markdown_and_do_not_publish_private_titles(wave):
    host, _, notes = wave
    source = await notes.create("Public source")
    for private in (False, True):
        destination = await notes.create(r"A \[bracket\] and backslash \\", private=private)
        result = await host.invoke_command(
            "links",
            "link",
            {"note_id": source["id"], "target_id": destination["id"]},
            principal=OWNER,
        )
        await decide(host.store, OWNER, result["suggestion_id"], "accept")
        body = (await notes.get(source["id"]))["body"]
        assert destination["id"] in links.targets(body)
        if private:
            assert f"[Linked note](/n/{destination['id']})" in body
        else:
            assert r"[A \[bracket\] and backslash \\]" in body


async def test_private_machine_and_stale_sources_never_mutate(wave):
    host, provider, notes = wave
    private = await notes.create("synthetic secret", private=True)
    machine = await ctx(host, "develop").notes.create("synthetic prior output")
    for id in (private["id"], machine["id"]):
        assert (await translate.translate(ctx(host, "translate"), id, "it"))["type"] == "text"
        result = await host.invoke_command(
            "title-summary", "summarize", {"note_id": id}, principal=OWNER
        )
        assert result["text"] == "This note is unavailable for Title and summary."
    trashed = await notes.create("synthetic trashed note")
    await notes.flags(trashed["id"], {"trashed": True})
    result = await host.invoke_command(
        "title-summary", "summarize", {"note_id": trashed["id"]}, principal=OWNER
    )
    assert result["text"] == "This note is unavailable for Title and summary."
    assert not provider.requests
    note = await notes.create("synthetic sentence")

    async def change():
        await notes.edit(note["id"], "new owner body", base_version=note["version"])

    provider.callback = change
    with pytest.raises(Problem, match="source changed"):
        await translate.translate(ctx(host, "translate"), note["id"], "it")
    assert not (await ctx(host, "translate").storage.records.list("results"))


async def test_translate_live_explicit_save_idempotent_and_develop_links(wave):
    host, provider, notes = wave
    seed = await notes.create("Synthetic Italian translation seed")
    result = await host.invoke_command(
        "translate", "translate", {"note_id": seed["id"], "language": "it"}, principal=OWNER
    )
    assert result["items"][0]["html"]
    assert len((await notes.list())["notes"]) == 1
    key = result["items"][-1]["args"]["result_id"]
    first = await translate.save(ctx(host, "translate"), key)
    second = await translate.save(ctx(host, "translate"), key)
    assert first == second
    saved = await notes.get(first["ids"][0])
    assert (
        saved["machine"] and saved["source"]["ref"] == "translate" and seed["id"] in saved["body"]
    )
    developed = await develop.develop(ctx(host, "develop"), seed["id"])
    assert developed == await develop.develop(ctx(host, "develop"), seed["id"])
    assert (await notes.get(seed["id"]))["body"] == seed["body"]
    assert (await notes.get(developed["ids"][0]))["machine"]


async def test_gardener_cluster_atomic_and_refusal_permanent(wave):
    host, provider, notes = wave
    a = await notes.create("synthetic theme one")
    b = await notes.create("synthetic theme two")
    await label_gardener.weekly(ctx(host, "label-gardener", unattended=True))
    proposal = (await review(host))[0]
    assert set(proposal["payload"]["note_ids"]) == {a["id"], b["id"]}
    await decide(host.store, OWNER, proposal["id"], "dismiss")
    await ctx(host, "label-gardener").storage.kv.delete("week")
    await label_gardener.weekly(ctx(host, "label-gardener", unattended=True))
    assert not await review(host)
    proposal = await ctx(host, "label-gardener").notes.suggest(
        None, "label_change", {"new": "shared", "note_ids": [a["id"], b["id"]]}
    )
    await notes.edit(b["id"], "changed synthetic body", base_version=b["version"])
    with pytest.raises(Problem):
        await decide(host.store, OWNER, proposal["id"], "accept")
    assert (await notes.get(a["id"]))["labels"] == []


@pytest.mark.parametrize("invalid", ["wrong_types", "label_spaces"])
async def test_gardener_validation_retry_spells_schema_and_can_abstain(wave, invalid):
    host, provider, notes = wave
    first = await notes.create("Synthetic first unlabeled evidence")
    second = await notes.create("Synthetic second unlabeled evidence")
    attempts = 0

    async def answer():
        nonlocal attempts
        attempts += 1
        provider.override = json.dumps(
            {"proposals": []}
            if attempts > 1
            else {"proposals": "No useful improvements"}
            if invalid == "wrong_types"
            else {
                "proposals": [
                    {
                        "old": None,
                        "new": "has spaces",
                        "note_ids": [first["id"], second["id"]],
                        "reason": "Synthetic shared theme.",
                    }
                ]
            }
        )

    provider.callback = answer
    result = await host.invoke_command("label-gardener", "review-now", principal=OWNER)
    assert "0 tag proposals added to Review" in result["text"]
    chats = [request for request in provider.requests if "messages" in request]
    assert len(chats) == 2
    assert "Required schema" in chats[1]["messages"][0]["content"]
    assert "without # or spaces" in chats[0]["messages"][1]["content"]
    assert 'Return {"proposals":[]}' in chats[0]["messages"][1]["content"]
    assert not await review(host)
    assert (await notes.get(first["id"]))["body"] == first["body"]
    assert (await notes.get(second["id"]))["body"] == second["body"]
    stats = (await host.gateway.status())["stats"]
    assert any(
        row["plugin_id"] == "label-gardener" and row["outcome"] == "retried_ok" for row in stats
    )


async def test_cluster_accept_ordinary_edits_and_history_scope(wave):
    host, _, notes = wave
    a = await notes.create("synthetic one")
    b = await notes.create("synthetic two")
    proposal = await ctx(host, "label-gardener").notes.suggest(
        None, "label_change", {"new": "shared", "note_ids": [a["id"], b["id"]]}
    )
    result = await decide(host.store, OWNER, proposal["id"], "accept")
    assert result["result"] == {"changed": 2}
    assert (await notes.get(a["id"]))["labels"] == ["shared"]
    assert (await ctx(host, "label-gardener").notes.suggestion_history())["suggestions"][0][
        "status"
    ] == "accepted"
    assert (await ctx(host, "tidy").notes.suggestion_history())["suggestions"] == []


async def test_duplicates_vector_only_and_backlinks_machine_privacy(wave):
    host, provider, notes = wave
    a = await notes.create("synthetic duplicate one")
    b = await notes.create("synthetic duplicate two")
    await host.vectors.index_notes([a["id"], b["id"]])
    await duplicates.compare(ctx(host, "duplicates"), a["id"])
    assert (await review(host))[0]["action"] == "merge_notes"
    assert all("messages" not in value for value in provider.requests)
    machine = await ctx(host, "develop").notes.create(f"Follow up [seed](/n/{a['id']})")
    private = await notes.create(f"Private [seed](/n/{a['id']})", private=True)
    await links.index(ctx(host, "links"), machine["id"])
    await links.index(ctx(host, "links"), private["id"])
    panel = next(x for x in (await notes.get(a["id"]))["annotations"] if x["plugin_id"] == "links")
    assert panel["payload"]["ids"] == [machine["id"]]
    await notes.edit(machine["id"], "Owner removed link", base_version=machine["version"])
    await links.index(ctx(host, "links"), machine["id"])
    assert not (await notes.get(a["id"]))["annotations"]


async def test_digest_quiet_excluded_sources_schedule_idempotence(wave):
    host, provider, notes = wave
    assert (await digest.digest(ctx(host, "digest")))["type"] == "text"
    assert not provider.requests
    for i in range(3):
        await notes.create(f"Synthetic weekly theme {i}")
    private = await notes.create("hidden weekly theme", private=True)
    await notes.create("Excluded weekly theme #exclude")
    host.states["digest"]["settings"]["leave_out"] = ["exclude"]
    result = await digest.weekly(ctx(host, "digest", unattended=True))
    assert result == await digest.digest(ctx(host, "digest"))
    note = await notes.get(result["ids"][0])
    assert note["machine"] and "Coverage:" in note["body"]
    chat = next(x for x in provider.requests if "messages" in x)
    assert private["id"] not in json.dumps(chat) and "Excluded weekly" not in json.dumps(chat)
    latest = await digest.latest(ctx(host, "digest"))
    validate_output(latest)
    assert latest["items"][-1] == result


async def test_atomic_source_guard_and_delayed_jobs(wave):
    host, _, notes = wave
    note = await notes.create("dictated synthetic", source={"kind": "android", "ref": "dictation"})
    await tidy.saved(ctx(host, "tidy"), note["id"])
    async with host.store.read() as conn:
        jobs = await conn.execute_fetchall(
            "SELECT run_after,args FROM job WHERE plugin_id='tidy' AND kind='job:tidy'"
        )
    assert jobs and jobs[0]["run_after"] >= host.clock() + 59_000
    await notes.edit(note["id"], "later synthetic edit", base_version=note["version"])
    with pytest.raises(Problem):
        await ctx(host, "develop").notes.create(
            "stale machine output", source_versions={note["id"]: note["version"]}
        )
    with pytest.raises(Problem):
        await ctx(host, "title-summary").notes.annotate(
            note["id"], "panel", {"type": "text", "text": "stale"}, note_version=note["version"]
        )
    with pytest.raises(ValueError):
        await ctx(host, "tidy").enqueue("tidy", delay_ms=86_400_001)


@pytest.mark.parametrize(
    "id,command,args",
    [
        ("translate", "translate", {"language": "it"}),
        ("tidy", "tidy", {}),
        ("title-summary", "summarize", {}),
    ],
)
async def test_malformed_output_fails_closed(wave, id, command, args):
    host, provider, notes = wave
    note = await notes.create(" ".join(["synthetic"] * 300))
    provider.override = "not structured JSON"
    with pytest.raises(AIError):
        await host.invoke_command(id, command, {"note_id": note["id"], **args}, principal=OWNER)
    current = await notes.get(note["id"])
    assert (
        current["body"] == note["body"]
        and not current["annotations"]
        and not current["suggestions"]
    )


async def test_import_metadata_preserves_provenance_and_canonical_labels(wave):
    host, _, notes = wave
    await host.enable(
        "markdown-import", permissions=host.manifests["markdown-import"]["permissions"]
    )
    importer = ctx(host, "markdown-import")
    metadata = {
        "source": {"kind": "plugin", "ref": "ask"},
        "origin": "plugin",
        "machine": True,
        "conflict_of": None,
        "labels": ["Ideas"],
    }
    note = await importer.notes.create(
        "Synthetic prior answer #IDEAS", import_key="metadata-fixture", import_metadata=metadata
    )
    assert note["machine"] and note["origin"] == "plugin" and note["source"]["ref"] == "ask"
    assert note["body"].endswith("#IDEAS") and note["labels"] == ["Ideas"]
    owner = await notes.create("Owner canonical #Existing")
    metadata["labels"] = ["EXISTING"]
    second = await importer.notes.create("Imported #existing", import_metadata=metadata)
    assert second["labels"] == owner["labels"] == ["Existing"]
    async with host.store.read() as conn:
        row = (
            await conn.execute_fetchall("SELECT created_by FROM note WHERE id=?", (note["id"],))
        )[0]
        assert row["created_by"] == "plugin:markdown-import"
    with pytest.raises(ContextError, match="owner_import_only"):
        await ctx(host, "markdown-import", unattended=True).notes.create(
            "Synthetic", import_metadata=metadata
        )
    with pytest.raises(ContextError, match="owner_import_only"):
        await ctx(host, "develop").notes.create("Synthetic", import_metadata=metadata)
    with pytest.raises(ValueError, match="must match"):
        await importer.notes.create("No labels", import_metadata=metadata)


async def test_persistent_tidy_delay_and_weekly_slots_survive_host_restart(wave):
    host, _, notes = wave
    note = await notes.create("Synthetic dictation", source={"kind": "android", "ref": "dictation"})
    await tidy.saved(ctx(host, "tidy"), note["id"])
    restart = Host(
        host.store,
        host.settings,
        notes_factory=PluginNotes,
        gateway=host.gateway,
        vectors=host.vectors,
    )
    await restart.discover()
    try:
        async with restart.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT args,run_after FROM job WHERE plugin_id='tidy' AND kind='job:tidy'"
            )
        assert len(rows) == 1 and json.loads(rows[0]["args"])["note_id"] == note["id"]
        assert rows[0]["run_after"] > restart.clock()
        for id in ("digest", "label-gardener"):
            schedule = restart.manifests[id]["contributes"]["schedules"][0]
            slot, next_slot = restart._scheduled_slot(
                schedule, restart.states[id]["settings"], restart.clock()
            )
            assert next_slot - slot == 7 * 86_400_000
    finally:
        await restart.close()


async def test_unattended_machine_creation_budget_and_command_exemption(wave):
    host, _, _ = wave
    unattended = ctx(host, "develop", unattended=True)
    for i in range(5):
        await unattended.notes.create(f"Synthetic budget {i}")
    with pytest.raises(ContextError, match="creation_daily_limit"):
        await unattended.notes.create("Over budget")
    assert (await ctx(host, "develop").notes.create("Explicit command remains available"))[
        "machine"
    ]


async def test_links_parser_ignores_code_and_supports_reference_links(wave):
    _, _, notes = wave
    target = await notes.create("Synthetic target")
    body = f"`[example](/n/{target['id']})`\n\n[real][target]\n\n[target]: /n/{target['id']}"
    assert links.targets(body) == [target["id"]]
    assert links.targets(f"```\n[example](/n/{target['id']})\n```") == []


async def test_shared_cluster_fixture(wave):
    host, _, notes = wave
    fixture = json.loads(
        (Path(__file__).parents[2] / "spec/fixtures/wave2-cluster.json").read_text()
    )
    for input in fixture["notes"]:
        await notes.create(**input)
    proposal = await ctx(host, "label-gardener").notes.suggest(
        None, "label_change", fixture["proposal"]
    )
    device = Principal("fixture-phone", "device", "Fixture", "write", private_ok=True)
    with pytest.raises(Problem) as failure:
        await decide(host.store, device, proposal["id"], "accept")
    assert failure.value.code == fixture["expected"]["device_decision"]
    result = await decide(host.store, OWNER, proposal["id"], "accept")
    assert result["result"]["changed"] == fixture["expected"]["changed"]
    for input in fixture["notes"]:
        current = await notes.get(input["id"])
        assert current["labels"] == fixture["expected"]["labels"]
        assert current["body"].startswith(input["body"])


@pytest.mark.parametrize(
    "original,replacement", [("x" * 6000, "y" * 6000), ("x\n" * 5001, "y\n" * 5001)]
)
async def test_tidy_honest_bounded_preview_keeps_full_proposal(wave, original, replacement):
    host, provider, notes = wave
    note = await notes.create(original)
    provider.override = json.dumps({"body": replacement, "meaning_preserved": True})
    result = await tidy.tidy(ctx(host, "tidy"), note["id"])
    validate_output(result)
    assert result["items"][0]["type"] == "text"
    assert "full original" in result["items"][0]["text"]
    assert (await notes.get(note["id"]))["body"] == original
    suggestion = (await review(host))[0]
    assert suggestion["payload"]["body"] == replacement
    await decide(host.store, OWNER, suggestion["id"], "accept")
    assert (await notes.get(note["id"]))["body"] == replacement


@pytest.mark.parametrize("invalid", ["unknown", "duplicate"])
async def test_gardener_mixed_valid_invalid_batch_has_no_persistent_effect(wave, invalid):
    host, provider, notes = wave
    await notes.create("Existing vocabulary #ideas #idea")
    a = await notes.create("Synthetic unlabeled one")
    b = await notes.create("Synthetic unlabeled two")
    ids = (
        [a["id"], b["id"], a["id"]]
        if invalid == "duplicate"
        else [a["id"], "01980000-0000-7000-8000-000000000099"]
    )
    provider.override = json.dumps(
        {
            "proposals": [
                {"old": "idea", "new": "ideas", "note_ids": [], "reason": "Merge variants."},
                {"old": None, "new": "shared", "note_ids": ids, "reason": "Shared cluster."},
            ]
        }
    )
    with pytest.raises(AIError):
        await label_gardener.weekly(ctx(host, "label-gardener", unattended=True))
    assert not await review(host)
    assert (await ctx(host, "label-gardener").notes.suggestion_history())["suggestions"] == []
    assert await ctx(host, "label-gardener").storage.kv.get("week") is None


async def test_suggestion_batch_rolls_back_on_late_budget_failure(wave):
    host, _, notes = wave
    a = await notes.create("Synthetic unlabeled one")
    b = await notes.create("Synthetic unlabeled two")
    adapter = ctx(host, "label-gardener").notes
    for old, new in (("one", "two"), ("three", "four")):
        await adapter.suggest(None, "label_change", {"old": old, "new": new})
    with pytest.raises(ValueError, match="at most three"):
        await adapter.suggest_batch(
            [
                {
                    "note_id": None,
                    "action": "label_change",
                    "payload": {"old": "five", "new": "six"},
                },
                {
                    "note_id": None,
                    "action": "label_change",
                    "payload": {"new": "shared", "note_ids": [a["id"], b["id"]]},
                },
            ]
        )
    assert len((await adapter.suggestion_history())["suggestions"]) == 2
    assert len(await review(host)) == 2


async def test_develop_chat_only_approved_permissions(wave):
    host, provider, notes = wave
    profile = host.settings.ai_profiles["fixture"].model_copy(update={"embed_model": None})
    host.gateway.settings.ai_profiles["fixture"] = profile
    host.gateway.settings.ai_embed_profile = None
    seed = await notes.create("Synthetic development seed #project")
    await notes.create("Synthetic related planning #project")
    result = await develop.develop(ctx(host, "develop"), seed["id"])
    assert result["type"] == "notes"
    assert host.manifests["develop"]["permissions"] == ["notes.read", "notes.create", "ai.chat"]
    assert host.manifests["develop"]["requires"] == ["ai.chat"]
    assert all("input" not in value for value in provider.requests)


@pytest.mark.parametrize("id", ["digest", "develop"])
async def test_existing_unsupplied_citations_fail_before_machine_creation(wave, id):
    from spunto.plugins._wave2 import write_machine

    host, provider, notes = wave
    seed = await notes.create("Synthetic bounded source")
    unrelated = await notes.create("Existing but unrelated citation #exclude")
    provider.override = json.dumps({"body": "Unsupported claim.", "citations": [unrelated["id"]]})
    if id == "digest":
        host.states["digest"]["settings"]["min_notes"] = 1
        host.states["digest"]["settings"]["leave_out"] = ["exclude"]
        operation = digest.digest(ctx(host, id))
    else:
        operation = develop.develop(ctx(host, id), seed["id"])
    with pytest.raises(AIError):
        await operation
    with pytest.raises(ValueError, match="citations_outside"):
        await write_machine(
            ctx(host, id), "Unsupported claim.", [unrelated["id"]], [seed], "outside"
        )
    assert len((await notes.list())["notes"]) == 2
    assert not await ctx(host, id).storage.kv.get("outside")
