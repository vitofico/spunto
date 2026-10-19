"""Decision journal contracts use real SQLite and owner command authority."""

import asyncio
import json
import uuid

import pytest

from spunto.api.errors import Problem
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.host import ContextError, Ctx, validate_output
from spunto.host.storage import Records

pytestmark = pytest.mark.anyio
OWNER = Principal("owner", "session", "Owner", "write", private_ok=True)
DEVICE = Principal("phone", "device", "Phone", "write", private_ok=True)
AGENT = Principal("agent", "pat", "Agent", "write", private_ok=False)
PLUGIN = "decision-journal"
ORIGIN = {"Origin": "http://spunto.test:8080"}


@pytest.fixture
async def journal(server):
    host = server.services.host
    await host.enable(PLUGIN, permissions=host.manifests[PLUGIN]["permissions"])
    return host, Notes(host.store, OWNER)


def fields(**extra):
    return {
        "title": "Choose local SQLite",
        "decision": "Use one SQLite file per instance.",
        "alternatives": "PostgreSQL or a document database.",
        "rationale": "One owner, low maintenance and straightforward backups.",
        "expected": "A restore takes less than five minutes.",
        "review_on": "2026-12-01",
        "private": False,
        "request_id": str(uuid.uuid7()),
        **extra,
    }


async def command(host, name, args=None, *, principal=OWNER):
    result = await host.invoke_command(PLUGIN, name, args, principal=principal)
    validate_output(result)
    return result


async def record(host, **extra):
    args = fields(**extra)
    result = await command(host, "record-decision", args)
    state = await Ctx(host, PLUGIN, principal=OWNER).storage.records.get(
        "decisions", args["request_id"]
    )
    return result["ids"][0], state, args


def outcome(note_id, **extra):
    return {
        "note_id": note_id,
        "outcome": "Restore completed in three minutes.",
        "reflection": "Keep the single-writer constraint.",
        "status": "closed",
        "review_on": "",
        "base_version": 1,
        "request_id": str(uuid.uuid7()),
        **extra,
    }


async def test_manual_opt_in_no_ai_and_vocabulary_forms(journal):
    host, _ = journal
    manifest = host.manifests[PLUGIN]
    assert manifest["api"] == "1.0" and manifest["default"] == "off"
    assert manifest["requires"] == [] and manifest["creates"] == "owner"
    assert not any(p.startswith(("ai.", "net.")) for p in manifest["permissions"])
    assert len(manifest["contributes"]["commands"]) == 3
    form = await command(host, "record-decision")
    assert form["type"] == "form"
    assert form["schema"]["properties"]["rationale"]["maxLength"] == 700
    assert {"palette", "slash"} <= set(manifest["contributes"]["commands"][0]["where"])
    for principal in (AGENT, None):
        with pytest.raises(ContextError, match="owner_required"):
            await command(host, "record-decision", fields(), principal=principal)


async def test_decision_is_owner_note_with_offline_panel_and_view(journal):
    host, notes = journal
    note_id, state, _ = await record(host)
    note = await notes.get(note_id)
    assert note["origin"] == "owner" and note["machine"] is False
    assert note["source"] == {"kind": "plugin", "ref": PLUGIN}
    for key in ("decision", "alternatives", "rationale", "expected"):
        assert state["original"][key] in note["body"]
    assert len(note["body"]) < 4000
    assert {a["kind"] for a in note["annotations"]} == {"panel", "member:journal"}
    for annotation in note["annotations"]:
        assert len(json.dumps(annotation["payload"]).encode()) < 4096
    browsed = await command(host, "journal")
    assert browsed["items"][0]["items"][0]["id"] == note_id
    await command(host, "record-decision", fields(), principal=DEVICE)


async def test_outcome_does_not_overwrite_owner_edits_and_reopen_preserves_history(journal):
    host, notes = journal
    note_id, original, _ = await record(host)
    note = await notes.get(note_id)
    changed = await notes.edit(note_id, note["body"] + "\n\nOwner clarification.", note["version"])
    result = await command(host, "review-decision", outcome(note_id))
    review_id = result["ids"][0]
    reviewed = await notes.get(review_id)
    assert note_id in reviewed["body"] and "three minutes" in reviewed["body"]
    assert reviewed["origin"] == "owner" and not reviewed["machine"]
    assert (await notes.get(note_id))["body"] == changed["body"]
    state = await Ctx(host, PLUGIN).storage.records.get("decisions", original["id"])
    assert state["original"] == original["original"]
    assert state["status"] == "closed" and state["version"] == 2
    reopened = await command(
        host,
        "review-decision",
        outcome(
            review_id,
            base_version=2,
            status="open",
            review_on="2027-01-04",
            outcome="A new constraint warrants another review.",
        ),
    )
    assert reopened["ids"][0] != review_id
    state = await Ctx(host, PLUGIN).storage.records.get("decisions", original["id"])
    assert (
        state["status"] == "open" and state["version"] == 3 and state["review_date"] == "2027-01-04"
    )
    assert (await notes.get(review_id))["body"] == reviewed["body"]
    assert state["original"] == original["original"]


async def test_requests_are_idempotent_and_mismatched_reuse_conflicts(journal):
    host, notes = journal
    note_id, _, args = await record(host)
    assert (await command(host, "record-decision", args))["ids"] == [note_id]
    with pytest.raises(Problem, match="decision changed"):
        await command(host, "record-decision", {**args, "rationale": "Different reason"})
    review_args = outcome(note_id)
    first = await command(host, "review-decision", review_args)
    assert await command(host, "review-decision", review_args) == first
    with pytest.raises(Problem):
        await command(host, "review-decision", {**review_args, "outcome": "Different outcome"})
    assert len((await notes.list())["notes"]) == 2
    await notes.flags(first["ids"][0], {"trashed": True})
    replay = await command(host, "review-decision", review_args)
    assert replay["type"] == "text" and "unavailable" in replay["text"]
    assert len((await notes.list())["notes"]) == 1


async def test_stale_review_and_concurrent_outcomes_never_overwrite(journal):
    host, notes = journal
    note_id, state, _ = await record(host)
    responses = await asyncio.gather(
        command(host, "review-decision", outcome(note_id, outcome="Result A")),
        command(host, "review-decision", outcome(note_id, outcome="Result B")),
        return_exceptions=True,
    )
    successes = [value for value in responses if isinstance(value, dict)]
    failures = [value for value in responses if isinstance(value, Problem)]
    assert len(successes) == len(failures) == 1
    assert failures[0].status == 409
    assert len((await notes.list())["notes"]) == 2
    latest = await Ctx(host, PLUGIN).storage.records.get("decisions", state["id"])
    assert latest["version"] == 2 and latest["pending"] is None
    with pytest.raises(Problem) as stale:
        await command(host, "review-decision", outcome(note_id))
    assert stale.value.status == 409


async def test_interrupted_review_resumes_same_intent_without_duplicate_note(journal, monkeypatch):
    host, notes = journal
    note_id, state, _ = await record(host)
    original_cas = Records.compare_and_set
    failed = False

    async def interrupt(self, collection, record_id, expected_data, data, **kwargs):
        nonlocal failed
        if collection == "outcomes" and not failed:
            failed = True
            raise RuntimeError("synthetic interrupted outcome storage")
        return await original_cas(self, collection, record_id, expected_data, data, **kwargs)

    monkeypatch.setattr(Records, "compare_and_set", interrupt)
    args = outcome(note_id)
    with pytest.raises(RuntimeError, match="synthetic interrupted"):
        await command(host, "review-decision", args)
    assert len((await notes.list())["notes"]) == 2
    resume = await command(host, "review-decision", {"note_id": note_id})
    resume_args = resume["items"][1]["args"]
    assert resume_args == args
    result = await command(host, "review-decision", resume_args)
    assert len((await notes.list())["notes"]) == 2
    current = await Ctx(host, PLUGIN).storage.records.get("decisions", state["id"])
    assert current["pending"] is None and current["latest"]["note_id"] == result["ids"][0]


async def test_interrupted_initial_creation_retries_and_never_recreates_trashed_note(
    journal, monkeypatch
):
    host, notes = journal
    original_cas = Records.compare_and_set
    failed = False

    async def interrupt(self, collection, record_id, expected_data, data, **kwargs):
        nonlocal failed
        if collection == "decisions" and data["note_id"] and not failed:
            failed = True
            raise RuntimeError("synthetic interrupted completion")
        return await original_cas(self, collection, record_id, expected_data, data, **kwargs)

    monkeypatch.setattr(Records, "compare_and_set", interrupt)
    args = fields()
    with pytest.raises(RuntimeError, match="synthetic interrupted completion"):
        await command(host, "record-decision", args)
    assert len((await notes.list())["notes"]) == 1
    result = await command(host, "record-decision", args)
    assert len((await notes.list())["notes"]) == 1
    await notes.flags(result["ids"][0], {"trashed": True})
    assert (await command(host, "record-decision", args))["type"] == "text"
    assert not (await notes.list())["notes"]


async def test_review_privacy_race_is_rejected_then_resumed_privately(journal, monkeypatch):
    host, notes = journal
    note_id, _, _ = await record(host)
    original_create = PluginNotes.create
    flipped = False

    async def flip_private(self, body, **kwargs):
        nonlocal flipped
        if not flipped and kwargs.get("request_id", "").startswith("outcome:"):
            flipped = True
            await notes.flags(note_id, {"private": True})
        return await original_create(self, body, **kwargs)

    monkeypatch.setattr(PluginNotes, "create", flip_private)
    args = outcome(note_id)
    with pytest.raises(ContextError):
        await command(host, "review-decision", args)
    assert len((await notes.list())["notes"]) == 1
    result = await command(host, "review-decision", args)
    assert (await notes.get(result["ids"][0]))["private"]


async def test_initial_creation_privacy_race_retries_same_request_privately(journal, monkeypatch):
    host, notes = journal
    source = await notes.create("Synthetic public decision source")
    original_create = PluginNotes.create
    flipped = False

    async def flip_private(self, body, **kwargs):
        nonlocal flipped
        if not flipped and kwargs.get("request_id", "").startswith("decision:"):
            flipped = True
            await notes.flags(source["id"], {"private": True})
        return await original_create(self, body, **kwargs)

    monkeypatch.setattr(PluginNotes, "create", flip_private)
    args = fields(note_id=source["id"])
    with pytest.raises(ContextError):
        await command(host, "record-decision", args)
    assert len((await notes.list())["notes"]) == 1
    result = await command(host, "record-decision", args)
    assert (await notes.get(result["ids"][0]))["private"]
    state = await Ctx(host, PLUGIN).storage.records.get("decisions", args["request_id"])
    assert state["original"]["private"] is True
    assert state["original"]["requested_private"] is False
    assert (await command(host, "record-decision", args))["ids"] == result["ids"]
    assert len((await notes.list())["notes"]) == 2


async def test_reserved_inherited_privacy_does_not_drop_on_retry(journal, monkeypatch):
    host, notes = journal
    source = await notes.create("Synthetic private decision source", private=True)
    original_create = PluginNotes.create
    interrupted = False

    async def interrupt(self, body, **kwargs):
        nonlocal interrupted
        if not interrupted and kwargs.get("request_id", "").startswith("decision:"):
            interrupted = True
            await notes.flags(source["id"], {"private": False})
            raise RuntimeError("synthetic interrupted private creation")
        return await original_create(self, body, **kwargs)

    monkeypatch.setattr(PluginNotes, "create", interrupt)
    args = fields(note_id=source["id"], private=False)
    with pytest.raises(RuntimeError, match="synthetic interrupted private creation"):
        await command(host, "record-decision", args)
    result = await command(host, "record-decision", args)
    assert (await notes.get(result["ids"][0]))["private"]
    assert len((await notes.list())["notes"]) == 2


async def test_all_outcomes_are_browsable_and_request_ids_are_scoped_to_decision(journal):
    host, notes = journal
    note_id, _, _ = await record(host)
    second_id, _, _ = await record(host, title="Another decision")
    shared_request = str(uuid.uuid7())
    first = await command(host, "review-decision", outcome(note_id, request_id=shared_request))
    second = await command(host, "review-decision", outcome(second_id, request_id=shared_request))
    assert first["ids"][0] != second["ids"][0]
    outcome_ids = [first["ids"][0]]
    for revision in range(2, 11):
        result = await command(
            host,
            "review-decision",
            outcome(note_id, base_version=revision, outcome=f"Synthetic review {revision}"),
        )
        outcome_ids.append(result["ids"][0])
    page = await command(host, "journal", {"note_id": note_id})
    assert len(page["items"][2]["items"]) == 8
    more = page["items"][-1]
    assert more["title"] == "More outcomes"
    following = await command(host, more["id"], more["args"])
    refs = page["items"][2]["items"] + following["items"][2]["items"]
    assert {ref["id"] for ref in refs} == set(outcome_ids)
    await notes.flags(outcome_ids[0], {"trashed": True})
    refreshed = await command(host, "journal", {"note_id": note_id})
    assert outcome_ids[0] not in {ref["id"] for ref in refreshed["items"][2]["items"]}


async def test_private_source_inherited_and_trashed_sources_not_disclosed(journal):
    host, notes = journal
    source = await notes.create("Synthetic confidential source", private=True)
    note_id, state, _ = await record(host, note_id=source["id"], private=False)
    assert (await notes.get(note_id))["private"]
    review_result = await command(host, "review-decision", outcome(note_id))
    assert (await notes.get(review_result["ids"][0]))["private"]
    lower = Ctx(host, PLUGIN, principal=AGENT)
    from spunto.plugins.decision_journal import available

    assert await available(lower, state) is None
    await notes.flags(source["id"], {"trashed": True})
    assert (await command(host, "journal", {"note_id": note_id}))["type"] == "text"
    assert not any(item.get("items") for item in (await command(host, "journal"))["items"])


async def test_new_outcomes_inherit_source_that_became_private(journal):
    host, notes = journal
    source = await notes.create("Synthetic public source")
    note_id, _, _ = await record(host, note_id=source["id"])
    assert not (await notes.get(note_id))["private"]
    await notes.flags(source["id"], {"private": True})
    result = await command(host, "review-decision", outcome(note_id))
    assert (await notes.get(result["ids"][0]))["private"]


async def test_pagination_browse_reopen_and_disable_preserve_notes(journal):
    host, notes = journal
    for index in range(11):
        await record(host, title=f"Decision {index}")
    page = await command(host, "journal")
    assert len(page["items"][0]["items"]) == 10
    more = page["items"][1]
    assert more["id"] == "journal"
    second = await command(host, more["id"], more["args"])
    assert len(second["items"][0]["items"]) == 1
    first_ids = {item["id"] for item in page["items"][0]["items"]}
    assert second["items"][0]["items"][0]["id"] not in first_ids
    await host.disable(PLUGIN)
    with pytest.raises(ContextError):
        await command(host, "journal")
    assert len((await notes.list())["notes"]) == 11
    await host.enable(PLUGIN, permissions=host.manifests[PLUGIN]["permissions"])
    assert len((await command(host, "journal"))["items"][0]["items"]) == 10


@pytest.mark.parametrize(
    "changes",
    [
        {"title": " "},
        {"decision": "x" * 501},
        {"rationale": "\x00"},
        {"review_on": "2026-02-30"},
        {"review_on": "20261015"},
        {"private": 1},
        {"request_id": "not-a-uuid"},
    ],
)
async def test_validation_before_writing(journal, changes):
    host, notes = journal
    with pytest.raises(ValueError):
        await command(host, "record-decision", fields(**changes))
    assert not (await notes.list())["notes"]
    assert not await Ctx(host, PLUGIN).storage.records.list("decisions")


async def test_rest_owner_auth_origin_and_commands(server):
    client, host = server.client, server.services.host
    path = "/api/v1/plugins/decision-journal/commands/record-decision"
    assert (await client.post(path, json=fields(), headers=ORIGIN)).status_code == 401
    response = await client.post(
        "/api/v1/setup",
        json={"code": server.services.setup_code, "password": "correct horse battery"},
    )
    assert response.status_code == 204
    assert (await client.post(path, json=fields(), headers=ORIGIN)).status_code == 404
    response = await client.post(
        "/api/v1/plugins/decision-journal/enable",
        json={"permissions": host.manifests[PLUGIN]["permissions"]},
        headers=ORIGIN,
    )
    assert response.status_code == 200
    assert (await client.post(path, json=fields())).status_code == 403
    args = fields()
    response = await client.post(path, json=args, headers=ORIGIN)
    assert response.status_code == 200, response.text
    note_id = response.json()["ids"][0]
    response = await client.post(
        "/api/v1/plugins/decision-journal/commands/review-decision",
        json=outcome(note_id),
        headers=ORIGIN,
    )
    assert response.status_code == 200, response.text
    response = await client.post(
        "/api/v1/plugins/decision-journal/commands/review-decision",
        json=outcome(note_id),
        headers=ORIGIN,
    )
    assert response.status_code == 409, response.text
    assert response.json()["code"] == "decision_conflict"
    note = await Notes(host.store, OWNER).get(note_id)
    assert note["body"].startswith("# Choose local SQLite") and note["origin"] == "owner"
