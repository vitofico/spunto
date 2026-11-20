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
from spunto.jobs.maintenance import DAY_MS, prune

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
    assert form["schema"]["required"] == ["request_id", "decision"]
    assert {"palette", "slash"} <= set(manifest["contributes"]["commands"][0]["where"])
    for principal in (AGENT, None):
        with pytest.raises(ContextError, match="owner_required"):
            await command(host, "record-decision", fields(), principal=principal)


async def test_empty_journal_opens_record_form_and_decision_alone_creates_note(journal):
    host, notes = journal
    empty = await command(host, "journal")
    assert empty["items"][0]["text"] == "No decisions yet."
    record_action = empty["items"][1]
    form = await command(host, record_action["id"], record_action["args"])
    request_id = form["schema"]["properties"]["request_id"]["default"]
    saved = await command(
        host,
        "record-decision",
        {"request_id": request_id, "decision": "**Use SQLite**\n\nRetain the same backup format."},
    )
    note = await notes.get(saved["ids"][0])
    assert note["title"] == "Use SQLite"
    assert note["body"].startswith("# Use SQLite\n")
    assert "Retain the same backup format." in note["body"]
    assert all(
        label not in note["body"]
        for label in ("## Alternatives", "## Rationale", "## Expected outcome", "review date")
    )


async def test_recording_title_is_one_safe_markdown_heading(journal):
    host, notes = journal
    note_id, _, _ = await record(host, title="[name](/n/elsewhere)\n## Second heading")
    note = await notes.get(note_id, render=True)
    assert note["title"] == "[name](/n/elsewhere) ## Second heading"
    assert "<h1>[name](/n/elsewhere) ## Second heading</h1>" in note["html"]
    assert 'href="/n/elsewhere"' not in note["html"]


async def test_one_line_decisions_are_not_repeated_when_the_heading_preserves_them(journal):
    host, notes = journal
    short = "Use one SQLite file."
    long = ("Keep " + "the existing backup format and restore instructions. " * 3).strip()
    for decision in (short, long):
        result = await command(
            host, "record-decision", {"decision": decision, "review_on": "2030-11-18"}
        )
        note = await notes.get(result["ids"][0])
        assert note["body"].count(decision) == 1
        assert "Initial review date: 2030-11-18." in note["body"]
        assert ("## Decision" in note["body"]) == (decision == long)


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
    panel = next(a for a in note["annotations"] if a["kind"] == "panel")
    assert panel["payload"]["items"] == [
        {
            "type": "command",
            "id": "journal",
            "title": "Open decision journal",
            "args": {"note_id": note_id},
        },
    ]
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
    assert reviewed["title"] == "Outcome · Choose local SQLite"
    assert reviewed["origin"] == "owner" and not reviewed["machine"]
    assert [(a["kind"], a["payload"]["items"][0]["args"]) for a in reviewed["annotations"]] == [
        ("panel", {"note_id": review_id})
    ]
    assert (await notes.get(note_id))["body"] == changed["body"]
    state = await Ctx(host, PLUGIN).storage.records.get("decisions", original["id"])
    assert state["original"] == original["original"]
    assert state["status"] == "closed" and state["version"] == 2
    assert (await command(host, "journal", {"note_id": note_id}))["items"][0]["text"] == "Closed"
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
    assert (await notes.get(reopened["ids"][0]))["title"] == "Reopened · Choose local SQLite"
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


async def test_journal_hides_outcome_until_the_review_state_commits(journal, monkeypatch):
    host, notes = journal
    note_id, _, _ = await record(host, review_on="")
    original_cas = Records.compare_and_set

    async def interrupt(self, collection, record_id, expected_data, data, **kwargs):
        if collection == "decisions" and data["version"] == 2:
            raise RuntimeError("synthetic interrupted status update")
        return await original_cas(self, collection, record_id, expected_data, data, **kwargs)

    args = outcome(note_id)
    with monkeypatch.context() as patch:
        patch.setattr(Records, "compare_and_set", interrupt)
        with pytest.raises(RuntimeError, match="synthetic interrupted status update"):
            await command(host, "review-decision", args)
    detail = await command(host, "journal", {"note_id": note_id})
    assert detail["items"][0]["text"] == "Open"
    assert not any("items" in item for item in detail["items"])
    before = {note["id"] for note in (await notes.list())["notes"]}
    async with host.store.write() as conn:
        await conn.execute(
            "DELETE FROM applied_op WHERE credential_id='plugin:decision-journal' "
            "AND op_id LIKE 'outcome:%'"
        )
    saved = await command(host, "review-decision", args)
    detail = await command(host, "journal", {"note_id": note_id})
    assert detail["items"][0]["text"] == "Closed"
    assert detail["items"][2]["items"][0]["id"] == saved["ids"][0]
    assert {note["id"] for note in (await notes.list())["notes"]} == before


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


@pytest.mark.parametrize("interrupted", [False, True])
async def test_purged_decision_cannot_be_replayed_after_receipt_retention(
    journal, monkeypatch, interrupted
):
    host, notes = journal
    args = fields(private=True, decision="Confidential synthetic deployment decision.")
    if interrupted:
        original_cas = Records.compare_and_set

        async def interrupt(self, collection, record_id, expected_data, data, **kwargs):
            if collection == "decisions" and data["note_id"]:
                raise RuntimeError("synthetic interrupted completion")
            return await original_cas(self, collection, record_id, expected_data, data, **kwargs)

        with monkeypatch.context() as patch:
            patch.setattr(Records, "compare_and_set", interrupt)
            with pytest.raises(RuntimeError, match="synthetic interrupted completion"):
                await command(host, "record-decision", args)
    else:
        await command(host, "record-decision", args)
    [note] = (await notes.list())["notes"]
    await notes.flags(note["id"], {"trashed": True})
    await notes.delete(note["id"])
    records = Ctx(host, PLUGIN, principal=OWNER).storage.records
    assert args["decision"] not in json.dumps(await records.list("decisions"))
    immediate = await command(host, "record-decision", args)
    assert immediate["type"] == "text" and "unavailable" in immediate["text"]
    assert not await records.list("decisions")
    await prune(host.store, host.settings, now=host.clock() + 31 * DAY_MS)
    assert await command(host, "record-decision", args) == immediate
    assert not (await notes.list())["notes"]
    assert not await records.list("decisions")
    async with host.store.read() as conn:
        receipts = await conn.execute_fetchall(
            "SELECT result FROM applied_op WHERE credential_id='plugin:decision-journal'"
        )
    [receipt] = receipts
    assert args["decision"] not in receipt["result"]
    assert set(json.loads(receipt["result"])) == {
        "id",
        "version",
        "created_at",
        "origin",
        "machine",
    }


@pytest.mark.parametrize("interrupted", [False, True])
async def test_purged_outcome_cannot_be_replayed_after_receipt_retention(
    journal, monkeypatch, interrupted
):
    host, notes = journal
    note_id, state, _ = await record(host, private=True)
    args = outcome(note_id, outcome="Confidential synthetic outcome.")
    if interrupted:
        original_cas = Records.compare_and_set

        async def interrupt(self, collection, record_id, expected_data, data, **kwargs):
            if collection == "outcomes":
                raise RuntimeError("synthetic interrupted outcome storage")
            return await original_cas(self, collection, record_id, expected_data, data, **kwargs)

        with monkeypatch.context() as patch:
            patch.setattr(Records, "compare_and_set", interrupt)
            with pytest.raises(RuntimeError, match="synthetic interrupted outcome storage"):
                await command(host, "review-decision", args)
        outcome_id = next(n["id"] for n in (await notes.list())["notes"] if n["id"] != note_id)
    else:
        result = await command(host, "review-decision", args)
        outcome_id = result["ids"][0]
    await notes.flags(outcome_id, {"trashed": True})
    await notes.delete(outcome_id)
    immediate = await command(host, "review-decision", args)
    assert immediate["type"] == "text" and "unavailable" in immediate["text"]
    records = Ctx(host, PLUGIN, principal=OWNER).storage.records
    updated = await records.get("decisions", state["id"])
    assert updated["version"] == 2 and updated["status"] == "closed" and updated["pending"] is None
    assert not await records.list("outcomes")
    await prune(host.store, host.settings, now=host.clock() + 31 * DAY_MS)
    assert await command(host, "review-decision", args) == immediate
    assert (
        await command(
            host, "review-decision", {**args, "base_version": 2, "outcome": "Different outcome"}
        )
        == immediate
    )
    assert (await records.get("decisions", state["id"])) == updated
    assert [n["id"] for n in (await notes.list())["notes"]] == [note_id]


async def test_purging_legacy_linked_records_restores_content_free_acknowledgements(journal):
    host, notes = journal
    root_id, _, decision_args = await record(host, private=True)
    outcome_args = outcome(root_id)
    outcome_id, _ = (await command(host, "review-decision", outcome_args))["ids"]
    async with host.store.write() as conn:
        await conn.execute("DELETE FROM applied_op WHERE credential_id='plugin:decision-journal'")
    for note_id, name, args in (
        (outcome_id, "review-decision", outcome_args),
        (root_id, "record-decision", decision_args),
    ):
        await notes.flags(note_id, {"trashed": True})
        await notes.delete(note_id)
        await prune(host.store, host.settings, now=host.clock() + 31 * DAY_MS)
        replay = await command(host, name, args)
        assert replay["type"] == "text" and "unavailable" in replay["text"]
    async with host.store.read() as conn:
        rows = await conn.execute_fetchall(
            "SELECT result FROM applied_op WHERE credential_id='plugin:decision-journal'"
        )
    assert sorted(row["result"] for row in rows) == sorted(
        json.dumps({"id": note_id}) for note_id in (root_id, outcome_id)
    )
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


@pytest.mark.parametrize("make_private", ["decision", "source", "legacy"])
async def test_reserved_review_privacy_survives_interruption_and_declassification(
    journal, monkeypatch, make_private
):
    host, notes = journal
    source = await notes.create("Synthetic decision source")
    root_id, state, _ = await record(host, note_id=source["id"])
    suspended_id = source["id"] if make_private == "source" else root_id
    await notes.flags(suspended_id, {"private": True})

    async def interrupt(self, body, **kwargs):
        assert kwargs["request_id"].startswith("outcome:")
        assert kwargs["private"] is True
        raise RuntimeError("synthetic interrupted private outcome")

    args = outcome(root_id)
    with monkeypatch.context() as patch:
        patch.setattr(PluginNotes, "create", interrupt)
        with pytest.raises(RuntimeError, match="synthetic interrupted private outcome"):
            await command(host, "review-decision", args)
    records = Ctx(host, PLUGIN, principal=OWNER).storage.records
    current = await records.get("decisions", state["id"])
    assert current["pending"]["private"]
    if make_private == "legacy":
        pending = {key: value for key, value in current["pending"].items() if key != "private"}
        assert await records.compare_and_set(
            "decisions", state["id"], current, {**current, "pending": pending}, note_id=root_id
        )
    await notes.flags(suspended_id, {"private": False})
    result = await command(host, "review-decision", args)
    assert (await notes.get(result["ids"][0]))["private"]


async def test_review_reservation_guards_source_version(journal, monkeypatch):
    host, notes = journal
    source = await notes.create("Synthetic decision source")
    root_id, state, _ = await record(host, note_id=source["id"])
    original_cas = Records.compare_and_set

    async def changed(self, collection, record_id, expected_data, data, **kwargs):
        if collection == "decisions" and data["pending"]:
            await notes.flags(source["id"], {"private": True})
        return await original_cas(self, collection, record_id, expected_data, data, **kwargs)

    args = outcome(root_id)
    with monkeypatch.context() as patch:
        patch.setattr(Records, "compare_and_set", changed)
        with pytest.raises(Problem) as exc:
            await command(host, "review-decision", args)
        assert exc.value.code == "version_conflict"
    records = Ctx(host, PLUGIN, principal=OWNER).storage.records
    assert (await records.get("decisions", state["id"]))["pending"] is None
    result = await command(host, "review-decision", args)
    assert (await notes.get(result["ids"][0]))["private"]


async def test_stored_navigation_does_not_change_as_live_status_changes(journal, monkeypatch):
    host, notes = journal
    first_day = 1_792_600_320_000
    initial_clock = host.clock
    monkeypatch.setattr(host, "clock", lambda: first_day)
    root_id, _, _ = await record(host, review_on="2026-10-22")
    initial_note = await notes.get(root_id)
    root_args = {"note_id": root_id}
    assert (await command(host, "journal", root_args))["items"][0]["text"] == (
        "Open · Review on 2026-10-22"
    )
    monkeypatch.setattr(host, "clock", lambda: first_day + DAY_MS)
    assert (await command(host, "journal", root_args))["items"][0]["text"] == (
        "Open · Review due 2026-10-22"
    )
    monkeypatch.setattr(host, "clock", initial_clock)
    await command(host, "review-decision", outcome(root_id))
    assert (await command(host, "journal", root_args))["items"][0]["text"] == "Closed"
    assert (await notes.get(root_id))["annotations"] == initial_note["annotations"]


async def test_reviewed_decision_can_schedule_a_followup_without_carrying_old_dates(
    journal, monkeypatch
):
    host, notes = journal
    first_day = 1_792_600_320_000
    monkeypatch.setattr(host, "clock", lambda: first_day)
    root_id, _, _ = await record(host, review_on="2026-10-21")
    root_args = {"note_id": root_id}
    form = await command(host, "review-decision", root_args)
    assert form["schema"]["properties"]["review_on"]["default"] == ""
    reviewed = await command(
        host, "review-decision", outcome(root_id, status="reviewed", review_on="2026-10-22")
    )
    assert (await command(host, "journal", root_args))["items"][0]["text"] == (
        "Reviewed · Review on 2026-10-22"
    )
    monkeypatch.setattr(host, "clock", lambda: first_day + DAY_MS)
    assert (await command(host, "journal", root_args))["items"][0]["text"] == (
        "Reviewed · Review due 2026-10-22"
    )
    form = await command(host, "review-decision", root_args)
    assert form["schema"]["properties"]["review_on"]["default"] == ""
    closed = await command(
        host, "review-decision", outcome(root_id, base_version=2, review_on="2026-11-01")
    )
    assert (await command(host, "journal", root_args))["items"][0]["text"] == "Closed"
    assert "Initial review date: 2026-10-21." in (await notes.get(root_id))["body"]
    assert "Next review date: 2026-10-22." in (await notes.get(reviewed["ids"][0]))["body"]
    assert "Next review date" not in (await notes.get(closed["ids"][0]))["body"]


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


async def test_new_outcome_titles_do_not_reuse_removed_private_decision_title(journal):
    host, notes = journal
    note_id, _, _ = await record(host, private=True, title="Confidential old title")
    note = await notes.get(note_id)
    await notes.edit(
        note_id, "# Public decision title\n\nSafe public description.", note["version"]
    )
    await notes.flags(note_id, {"private": False})
    result = await command(host, "review-decision", outcome(note_id))
    reviewed = await notes.get(result["ids"][0])
    assert reviewed["private"] is False
    assert reviewed["title"] == "Outcome · Public decision title"
    assert "Confidential old title" not in reviewed["body"]


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
        {"decision": " "},
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


def test_decision_notes_date_entries_in_words():
    from spunto.plugins.decision_journal import stamp

    assert stamp(1_792_600_320_000) == "21 October 2026, 16:32 UTC"


async def test_manager_filters_entire_library_and_prioritizes_due_decisions(journal):
    host, notes = journal
    old, state, _ = await record(
        host, decision="RÉSERVE synthetic review slot", review_on="2000-01-01"
    )
    for i in range(11):
        await record(host, decision=f"Newer synthetic choice {i}", review_on="")
    first = await command(host, "journal", {"manager": True})
    assert first["items"][0]["items"][0]["id"] == old
    assert len(first["items"][0]["items"]) == 10
    more = first["items"][1]
    second = await command(host, more["id"], more["args"])
    assert len(second["items"][0]["items"]) == 2
    for filters in ({"query": "reserve review"}, {"due": True}):
        page = await command(host, "journal", {"manager": True, **filters})
        assert [ref["id"] for ref in page["items"][0]["items"]] == [old]
    opened = await command(host, "journal", {"manager": True, "note_id": old})
    schedule = opened["items"][-1]
    assert schedule["args"]["operation"] == "schedule"
    await command(
        host, "journal", {**schedule["args"], "base_version": state["version"], "status": "closed"}
    )
    assert (await command(host, "journal", {"manager": True, "due": True}))["items"][0][
        "type"
    ] == "text"
    assert (await notes.get(old))["body"].count("RÉSERVE synthetic review slot") == 1


async def test_manager_trash_and_restore_preserve_linked_outcome(journal):
    host, notes = journal
    original, _, _ = await record(host)
    saved = (await command(host, "review-decision", outcome(original)))["ids"][0]
    await notes.flags(original, {"trashed": True})
    assert (await command(host, "journal", {"manager": True, "note_id": original}))[
        "type"
    ] == "text"
    assert (await notes.get(saved))["id"] == saved
    await notes.flags(original, {"trashed": False})
    detail = await command(host, "journal", {"manager": True, "note_id": original})
    assert detail["items"][2]["items"][0]["id"] == saved


async def test_confirmed_assisted_decision_checks_versions_and_keeps_source_link(journal):
    host, notes = journal
    source = await notes.create("Synthetic test rollout evidence.")
    proof = await Ctx(host, PLUGIN, principal=OWNER).notes.evidence(source["id"])
    evidence = {
        source["id"]: {"version": proof["version"], "fingerprint": proof["evidence_fingerprint"]}
    }
    args = fields(questions="Is latency still acceptable?", evidence=evidence)
    decision = (await command(host, "record-decision", args))["ids"][0]
    assert source["id"] in (await notes.get(decision))["body"]
    await notes.edit(source["id"], "Updated synthetic evidence.", source["version"])
    with pytest.raises(Problem, match="evidence changed"):
        await command(host, "record-decision", {**args, "request_id": str(uuid.uuid7())})
    assert (await command(host, "record-decision", args))["ids"] == [decision]
