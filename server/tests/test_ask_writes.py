"""Confirmed Ask writes use real SQLite, opaque previews and guarded undo."""

import asyncio
import threading
from contextlib import asynccontextmanager
from dataclasses import replace

import httpx
import pytest
from test_plugins_ai import OWNER, FakeProvider
from test_plugins_ai import ai_host as ai_host
from test_postgres import pg as pg

from spunto.ai.errors import AIError
from spunto.ai.gateway import Gateway
from spunto.ai.profiles import Profile
from spunto.ai.vectors import VectorIndex
from spunto.api.errors import Problem
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.host import Ctx, Host, ManifestError, validate_manifest
from spunto.plugins import ask
from spunto.plugins.ask import writes

pytestmark = pytest.mark.anyio


def plan(kind, *, target=None, selector="", body="", before="", after=""):
    return {
        "kind": kind,
        "target_ids": [target] if target else [],
        "selector": selector,
        "body": body,
        "before": before,
        "after": after,
    }


def action(output):
    return next(item["args"] for item in output["items"] if "action_id" in item.get("args", {}))


def continuation(output):
    return next(item["args"] for item in output["items"] if item.get("id") == "ask")


async def request(host, question, *, principal=OWNER, **kwargs):
    return await host.invoke_command(
        "ask", "ask", {"question": question, **kwargs}, principal=principal
    )


async def confirm(host, args, principal=OWNER):
    return await host.invoke_command("ask", "save-answer", args, principal=principal)


async def snapshot(store):
    async with store.read() as conn:
        return {
            table: [tuple(row) for row in await conn.execute_fetchall("SELECT * FROM " + table)]  # noqa: S608
            for table in ("note", "note_revision", "applied_op", "note_attachment")
        }


async def edit_preview(host, provider, *, body="# Meeting\n\nMonday at 18:00."):
    note = await Notes(host.store, OWNER).create(body)
    provider.action_plan = plan(
        "edit", target=note["id"], selector="Meeting", before="Monday", after="Tuesday"
    )
    output = await request(host, "Change Monday to Tuesday in Meeting", note_actions=True)
    return note, output


async def test_create_empty_library_preview_confirm_replay_undo_and_terminal_replay(ai_host):
    host, provider = ai_host
    domain = Notes(host.store, OWNER)
    provider.action_plan = plan("create", body="Call Luca Monday at 18:00.")
    before = await snapshot(host.store)
    preview = await request(host, "Create a note: Call Luca Monday at 18:00.", note_actions=True)
    assert await snapshot(host.store) == before
    assert action(preview)["stage"] == "preview"
    assert (
        "Call Luca Monday at 18:00."
        in next(i for i in preview["items"] if i["type"] == "markdown")["text"]
    )
    applied = await confirm(host, action(preview))
    assert action(applied)["stage"] == "undo"
    note = (await domain.list())["notes"][0]
    assert note["body"] == "Call Luca Monday at 18:00." and note["machine"]
    assert note["origin"] == "plugin" and note["source"]["ref"] == "ask"
    committed = await snapshot(host.store)
    assert await confirm(host, action(preview)) == applied
    assert await snapshot(host.store) == committed
    undone = await confirm(host, action(applied))
    assert action(undone)["stage"] == "done"
    assert (await domain.list(view="trash"))["notes"][0]["id"] == note["id"]
    terminal = await snapshot(host.store)
    for args in (action(preview), action(applied), action(undone)):
        assert await confirm(host, args) == undone
    assert await snapshot(host.store) == terminal


async def test_edit_preserves_long_unseen_content_provenance_flags_and_revisions(ai_host):
    host, provider = ai_host
    body = (
        "# Meeting\n\n"
        + "untouched prefix " * 1200
        + "Monday at 18:00.\n"
        + "untouched tail " * 1200
    )
    note, preview = await edit_preview(host, provider, body=body)
    domain = Notes(host.store, OWNER)
    before = await domain.get(note["id"])
    assert "Before:" in next(i for i in preview["items"] if i["type"] == "markdown")["text"]
    applied = await confirm(host, action(preview))
    edited = await domain.get(note["id"])
    assert edited["body"] == body.replace("Monday", "Tuesday")
    assert not edited["machine"] and edited["source"] == before["source"]
    assert edited["origin"] == before["origin"] and edited["private"] == before["private"]
    undone = await confirm(host, action(applied))
    assert action(undone)["stage"] == "done"
    restored = await domain.get(note["id"])
    assert restored["body"] == body and restored["version"] == 3
    assert not restored["machine"]
    revisions = await domain.revisions(note["id"])
    assert {row["change_kind"] for row in revisions} == {"create", "ask_review", "ask_undo"}


async def test_created_machine_note_can_be_edited_by_receipt_without_becoming_ai_evidence(ai_host):
    host, provider = ai_host
    provider.action_plan = plan("create", body="Call Luca Monday at 18:00.")
    preview = await request(host, "Create a note: Call Luca Monday at 18:00.", note_actions=True)
    created = await confirm(host, action(preview))
    note = (await Notes(host.store, OWNER).list())["notes"][0]
    provider.requests.clear()
    provider.action_plan = plan("edit", before="Monday", after="Tuesday")
    edit = await request(host, "Change Monday to Tuesday in that note", **continuation(preview))
    assert all(
        "<note " not in item["messages"][1]["content"]
        for item in provider.requests
        if "messages" in item
    )
    changed = await confirm(host, action(edit))
    current = await Notes(host.store, OWNER).get(note["id"])
    assert current["machine"] and "Tuesday" in current["body"]
    undone = await confirm(host, action(changed))
    assert action(undone)["stage"] == "done"
    current = await Notes(host.store, OWNER).get(note["id"])
    assert current["machine"] and current["body"] == note["body"]
    with pytest.raises(Problem, match="version_conflict"):
        await confirm(host, action(created))


@pytest.mark.parametrize("stage", ["undo", "done"])
async def test_non_preview_stage_cannot_apply_a_new_receipt(ai_host, stage):
    host, provider = ai_host
    _, preview = await edit_preview(host, provider)
    before = await snapshot(host.store)
    with pytest.raises(Problem, match="ask_action_changed"):
        await confirm(host, {**action(preview), "stage": stage})
    assert await snapshot(host.store) == before


async def test_wrong_done_stage_after_apply_acknowledges_without_undo(ai_host):
    host, provider = ai_host
    _, preview = await edit_preview(host, provider)
    applied = await confirm(host, action(preview))
    before = await snapshot(host.store)
    assert await confirm(host, {**action(preview), "stage": "done"}) == applied
    assert await snapshot(host.store) == before


@pytest.mark.parametrize("change", ["edit", "private", "trashed", "archived", "extracted"])
async def test_apply_refuses_changed_sources_without_overwrite(ai_host, change):
    host, provider = ai_host
    note, preview = await edit_preview(host, provider)
    domain = Notes(host.store, OWNER)
    if change == "edit":
        await domain.edit(note["id"], "# Meeting\n\nOwner changed it.", note["version"])
    elif change == "extracted":
        async with host.store.write() as conn:
            await conn.execute(
                "UPDATE plugin SET switch='on',state='enabled' WHERE id='voice-memos'"
            )
            await conn.execute(
                "INSERT INTO note_text VALUES ('voice-memos',?,'new transcript',1)", (note["id"],)
            )
    else:
        await domain.flags(note["id"], {change: True})
    before = await snapshot(host.store)
    with pytest.raises(Problem):
        await confirm(host, action(preview))
    assert await snapshot(host.store) == before


async def test_undo_refuses_owner_edit_since_apply(ai_host):
    host, provider = ai_host
    note, preview = await edit_preview(host, provider)
    applied = await confirm(host, action(preview))
    await Notes(host.store, OWNER).edit(note["id"], "# Meeting\n\nOwner changed it.", 2)
    before = await snapshot(host.store)
    with pytest.raises(Problem, match="version_conflict"):
        await confirm(host, action(applied))
    assert await snapshot(host.store) == before


async def test_ambiguous_target_or_anchor_and_missing_fact_clarification_has_no_receipt(ai_host):
    host, provider = ai_host
    domain = Notes(host.store, OWNER)
    first = await domain.create("# Meeting\n\nMonday and Monday")
    provider.action_plan = plan(
        "edit", target=first["id"], selector="Meeting", before="Monday", after="Tuesday"
    )
    output = await request(host, "Change Monday to Tuesday in Meeting", note_actions=True)
    assert not any("action_id" in i.get("args", {}) for i in output["items"])
    await domain.create("# Meeting\n\nMonday at 18")
    output = await request(host, "Change Monday to Tuesday in Meeting", note_actions=True)
    assert not any("action_id" in i.get("args", {}) for i in output["items"])
    provider.action_plan = plan("clarify")
    before = await snapshot(host.store)
    output = await request(
        host, "Create a note with the date of my next appointment", note_actions=True
    )
    assert not any("action_id" in i.get("args", {}) for i in output["items"])
    assert await snapshot(host.store) == before


async def test_old_client_cannot_receive_action_receipts_or_escalate_a_conversation(ai_host):
    host, provider = ai_host
    provider.action_plan = plan("create", body="Buy milk")
    output = await request(host, "Create a note: Buy milk")
    assert not any("action_id" in i.get("args", {}) for i in output["items"])
    with pytest.raises(ValueError):
        await request(host, "Create a note: Buy milk", note_actions=True, **continuation(output))
    with pytest.raises(ValueError):
        await request(host, "Create a note: Buy milk", note_actions="true")


async def test_receipt_binds_caller_role_and_clear_revokes_pending_action(ai_host):
    host, provider = ai_host
    _, preview = await edit_preview(host, provider)
    for principal in (replace(OWNER, id="someone-else"), replace(OWNER, workspace_role="viewer")):
        with pytest.raises(Problem):
            await confirm(host, action(preview), principal)
    await host.invoke_command(
        "ask",
        "clear-conversation",
        {"conversation_id": continuation(preview)["conversation_id"]},
        principal=OWNER,
    )
    result = await confirm(host, action(preview))
    assert result["type"] == "text" and "expired" in result["text"]


async def test_clear_during_inference_never_publishes_a_live_preview(ai_host):
    host, provider = ai_host
    started = await host.invoke_command(
        "ask", "ask", {"start": True, "note_actions": True}, principal=OWNER
    )
    provider.action_plan = plan("create", body="Buy milk")

    async def clear():
        await ask.clear_conversation(
            Ctx(host, "ask", principal=OWNER), continuation(started)["conversation_id"]
        )

    provider.on_chat = clear
    before = await snapshot(host.store)
    with pytest.raises(Problem, match="ask_conversation_expired"):
        await request(host, "Create a note: Buy milk", **continuation(started))
    assert await snapshot(host.store) == before
    assert not ask.receipts(Ctx(host, "ask", principal=OWNER))


@pytest.mark.parametrize("revoke", ["clear", "permission"])
async def test_writer_end_guard_rolls_back_when_clear_or_permission_changes(
    ai_host, monkeypatch, revoke
):
    host, provider = ai_host
    _, preview = await edit_preview(host, provider)
    before = await snapshot(host.store)
    original = Notes._update

    async def revoke_after_update(self, *args, **kwargs):
        result = await original(self, *args, **kwargs)
        if revoke == "permission":
            host.states["ask"]["granted"].remove("notes.review")
        else:
            await ask.clear_conversation(
                Ctx(host, "ask", principal=OWNER), continuation(preview)["conversation_id"]
            )
        return result

    monkeypatch.setattr(Notes, "_update", revoke_after_update)
    with pytest.raises((Problem, PermissionError)):
        await confirm(host, action(preview))
    assert await snapshot(host.store) == before


async def test_commit_then_cancel_replay_recovers_same_undo_acknowledgement(ai_host, monkeypatch):
    host, provider = ai_host
    _, preview = await edit_preview(host, provider)
    write = host.store.write
    cancel_once = True

    @asynccontextmanager
    async def cancel_after_commit():
        nonlocal cancel_once
        async with write() as conn:
            yield conn
        if cancel_once:
            cancel_once = False
            raise asyncio.CancelledError

    monkeypatch.setattr(host.store, "write", cancel_after_commit)
    with pytest.raises(asyncio.CancelledError):
        await confirm(host, action(preview))
    committed = await snapshot(host.store)
    replay = await confirm(host, action(preview))
    assert action(replay)["stage"] == "undo"
    assert await snapshot(host.store) == committed
    undone = await confirm(host, action(replay))
    assert action(undone)["stage"] == "done"


async def test_review_receipts_have_count_and_byte_limits(ai_host, monkeypatch):
    host, provider = ai_host
    provider.action_plan = plan("create", body="Buy milk")
    monkeypatch.setattr(writes, "MAX_ACTIONS", 1)
    first = await request(host, "Create a note: Buy milk", note_actions=True)
    await request(host, "Create a note: Buy milk", note_actions=True)
    assert (await confirm(host, action(first)))["type"] == "text"
    monkeypatch.setattr(writes, "MAX_ACTION_BYTES", 100)
    with pytest.raises(Problem, match="ask_preview_too_large"):
        await request(host, "Create a note: Buy milk", note_actions=True)
    assert not (await Notes(host.store, OWNER).list())["notes"]


async def test_private_notes_never_enter_action_model_context(ai_host):
    host, provider = ai_host
    note = await Notes(host.store, OWNER).create("# Secret\n\nPRIVATE-ACTION-CANARY", private=True)
    provider.action_plan = plan(
        "edit", target=note["id"], selector="Secret", before="PRIVATE-ACTION-CANARY", after="public"
    )
    with pytest.raises(AIError):
        await request(host, "Change Secret to public", note_actions=True)
    assert all(
        "PRIVATE-ACTION-CANARY" not in item["messages"][1]["content"]
        for item in provider.requests
        if "messages" in item
    )


def test_review_permission_is_closed_to_ask_only():
    from test_host import manifest

    with pytest.raises(ManifestError, match="reserved for Ask"):
        validate_manifest(manifest("other", permissions=["notes.review"]))


@pytest.mark.parametrize(
    "question",
    [
        "Create a note: Buy milk",
        "Could you please edit Meeting?",
        "Please append bread",
        "Crea una nota",
        "Aggiungi pane",
        "Modifica la nota",
        "Puoi creare una nota?",
        "¿Puedes crear una nota?",
        "Añade pan",
        "Por favor modifica la nota",
        "Peux-tu créer une note?",
        "Ajoute du pain",
        "S’il vous plaît modifie la note",
        "Kannst du eine Notiz erstellen?",
        "Bitte ändere die Notiz",
        "Füge Milch hinzu",
    ],
)
def test_direct_authoring_forms_in_supported_languages(question):
    assert writes.wants_action(question)


async def test_generated_checklist_creation_works_without_corpus_or_literal_draft(ai_host):
    host, provider = ai_host
    draft = "# Camping checklist\n\n- [ ] Pack a tent\n- [ ] Bring water\n- [ ] Check the forecast"
    provider.action_plan = plan("create", body=draft)
    output = await request(host, "Draft a camping checklist note", note_actions=True)
    assert draft in next(item["text"] for item in output["items"] if item["type"] == "markdown")
    assert not (await Notes(host.store, OWNER).list())["notes"]
    await confirm(host, action(output))
    assert (await Notes(host.store, OWNER).list())["notes"][0]["body"] == draft


async def test_discussion_to_generated_checklist_binds_factual_sources_atomically(ai_host):
    host, provider = ai_host
    source = await Notes(host.store, OWNER).create(
        "# Deployment\n\nLuca owns testing. Marta owns release."
    )
    provider.ask_answer = {
        "found": True,
        "answer": "Luca owns testing. Marta owns release.",
        "citations": [source["id"]],
    }
    first = await request(host, "Who owns Deployment tasks?", note_actions=True)
    draft = "# Release checklist\n\n- [ ] Luca: run testing\n- [ ] Marta: release after testing"
    provider.action_plan = plan("create", body=draft)
    preview = await request(
        host, "Turn this discussion into a checklist note", **continuation(first)
    )
    content = [r for r in provider.requests if "messages" in r][-1]["messages"][1]["content"]
    assert "Luca owns testing" in content and "discussion" in content
    await Notes(host.store, OWNER).flags(source["id"], {"private": True})
    before = await snapshot(host.store)
    with pytest.raises(Problem):
        await confirm(host, action(preview))
    assert await snapshot(host.store) == before


@pytest.mark.parametrize("operation", ["create", "edit"])
async def test_undo_checks_unchanged_target_without_locking_unrelated_factual_sources(
    ai_host, operation
):
    host, provider = ai_host
    domain = Notes(host.store, OWNER)
    source = await domain.create("# Deployment\n\nRelease follows testing.")
    if operation == "create":
        provider.action_plan = plan("create", body="- [ ] Test\n- [ ] Release")
        preview = await request(host, "Draft a Deployment checklist", note_actions=True)
    else:
        note = await domain.create("# Meeting\n\nMonday planning.")
        provider.action_plan = plan(
            "edit",
            target=note["id"],
            selector="Meeting",
            before="Monday planning.",
            after="Tuesday: test before releasing.",
        )
        preview = await request(
            host, "Revise Monday planning in Meeting using Deployment", note_actions=True
        )
    applied = await confirm(host, action(preview))
    await domain.flags(source["id"], {"private": True})
    undone = await confirm(host, action(applied))
    assert action(undone)["stage"] == "done"
    if operation == "create":
        assert len((await domain.list(view="trash"))["notes"]) == 1
    else:
        assert (await domain.get(note["id"]))["body"] == note["body"]


async def test_generated_anchored_rewrite_and_confirmed_machine_draft_revision(ai_host):
    host, provider = ai_host
    note = await Notes(host.store, OWNER).create(
        "# Meeting\n\nCall Luca and then call Marta.\n\nUNTOUCHED"
    )
    replacement = "- [ ] Call Luca\n- [ ] Call Marta"
    provider.action_plan = plan(
        "edit",
        target=note["id"],
        selector="Meeting",
        before="Call Luca and then call Marta.",
        after=replacement,
    )
    preview = await request(host, "Rewrite Meeting as a concise checklist", note_actions=True)
    applied = await confirm(host, action(preview))
    assert (await Notes(host.store, OWNER).get(note["id"]))[
        "body"
    ] == "# Meeting\n\n" + replacement + "\n\nUNTOUCHED"
    await confirm(host, action(applied))
    provider.action_plan = plan("create", body="Remember to pack a tent and water.")
    created_preview = await request(host, "Draft a short camping note", note_actions=True)
    created = await confirm(host, action(created_preview))
    created_id = next(item["ids"][0] for item in created["items"] if item["type"] == "notes")
    provider.requests.clear()
    provider.action_plan = plan(
        "edit",
        before="Remember to pack a tent and water.",
        after="- [ ] Pack a tent\n- [ ] Bring water",
    )
    revised = await request(
        host, "Rewrite that note as a checklist", **continuation(created_preview)
    )
    content = next(r for r in provider.requests if "messages" in r)["messages"][1]["content"]
    assert '<authoring-data untrusted="true">' in content
    assert "Remember to pack a tent and water." in content
    assert f'<note id="{created_id}"' not in content
    revised_ack = await confirm(host, action(revised))
    current = await Notes(host.store, OWNER).get(created_id)
    assert current["machine"] and "- [ ] Pack" in current["body"]
    await confirm(host, action(revised_ack))
    assert (await Notes(host.store, OWNER).get(created_id))[
        "body"
    ] == "Remember to pack a tent and water."


async def test_pending_generated_draft_revision_uses_only_bound_authoring_context(ai_host):
    host, provider = ai_host
    provider.action_plan = plan("create", body="# Camping\n\nPack a tent and water.")
    first = await request(host, "Draft a short camping note", note_actions=True)
    provider.action_plan = plan("create", body="# Camping\n\n- [ ] Pack a tent\n- [ ] Bring water")
    revised = await request(host, "Turn that draft into a checklist", **continuation(first))
    content = [r for r in provider.requests if "messages" in r][-1]["messages"][1]["content"]
    assert '<authoring-data untrusted="true">' in content and "Pack a tent and water" in content
    assert not (await Notes(host.store, OWNER).list())["notes"]
    await confirm(host, action(revised))
    assert len((await Notes(host.store, OWNER).list())["notes"]) == 1


async def test_full_generated_draft_over_1500_chars_can_be_rewritten_and_undone(ai_host):
    host, provider = ai_host
    draft = "# Camping\n\n" + "Prepare the equipment carefully.\n" * 60
    assert 1500 < len(draft) < 3500
    provider.action_plan = plan("create", body=draft)
    preview = await request(host, "Draft a detailed camping preparation note", note_actions=True)
    created = await confirm(host, action(preview))
    note_id = next(i["ids"][0] for i in created["items"] if i["type"] == "notes")
    checklist = "# Camping\n\n- [ ] Prepare the equipment\n- [ ] Check supplies"
    provider.action_plan = plan("edit", before=draft, after=checklist)
    rewritten = await request(
        host, "Rewrite that whole note as a short checklist", **continuation(preview)
    )
    assert draft in next(i["text"] for i in rewritten["items"] if i["type"] == "markdown")
    applied = await confirm(host, action(rewritten))
    assert (await Notes(host.store, OWNER).get(note_id))["body"] == checklist
    await confirm(host, action(applied))
    assert (await Notes(host.store, OWNER).get(note_id))["body"] == draft


async def test_partial_anchor_cannot_claim_whole_note_rewrite(ai_host):
    host, provider = ai_host
    provider.action_plan = plan("create", body="Two separate paragraphs.\n\nKeep this content too.")
    first = await request(host, "Draft a short note", note_actions=True)
    await confirm(host, action(first))
    provider.action_plan = plan("edit", before="Two separate paragraphs.", after="- [ ] One task")
    output = await request(host, "Rewrite that whole note as a checklist", **continuation(first))
    assert not any("action_id" in i.get("args", {}) for i in output["items"])


async def test_generated_third_bullet_revision_preserves_other_authoring_content(ai_host):
    host, provider = ai_host
    draft = "# Camping\n\n- [ ] Pack a tent\n- [ ] Bring water\n- [ ] Check the lights"
    provider.action_plan = plan("create", body=draft)
    first = await request(host, "Draft a camping checklist note", note_actions=True)
    created = await confirm(host, action(first))
    note_id = next(i["ids"][0] for i in created["items"] if i["type"] == "notes")
    provider.action_plan = plan(
        "edit",
        before="- [ ] Check the lights",
        after="- [ ] Test the lantern and pack spare batteries",
    )
    revised = await request(
        host, "Revise the third bullet in that note to be more practical", **continuation(first)
    )
    applied = await confirm(host, action(revised))
    expected = draft.replace(
        "- [ ] Check the lights", "- [ ] Test the lantern and pack spare batteries"
    )
    assert (await Notes(host.store, OWNER).get(note_id))["body"] == expected
    await confirm(host, action(applied))
    assert (await Notes(host.store, OWNER).get(note_id))["body"] == draft


@pytest.mark.parametrize("edit_first", [False, True])
async def test_two_created_notes_keep_authoring_receipt_target_coherent(ai_host, edit_first):
    host, provider = ai_host
    provider.action_plan = plan("create", body="First note Monday")
    first = await request(host, "Create note: First note Monday", note_actions=True)
    first_ack = await confirm(host, action(first))
    first_id = next(i["ids"][0] for i in first_ack["items"] if i["type"] == "notes")
    latest = first
    if edit_first:
        provider.action_plan = plan("edit", before="Monday", after="Tuesday")
        latest = await request(host, "Change Monday to Tuesday in that note", **continuation(first))
        await confirm(host, action(latest))
    provider.action_plan = plan("create", body="Second note Friday")
    second = await request(host, "Create note: Second note Friday", **continuation(latest))
    second_ack = await confirm(host, action(second))
    second_id = next(i["ids"][0] for i in second_ack["items"] if i["type"] == "notes")
    if edit_first:
        with pytest.raises(Problem, match="version_conflict"):
            await confirm(host, action(first_ack))
    else:
        assert action(await confirm(host, action(first_ack)))["stage"] == "done"
    provider.action_plan = plan("edit", before="Friday", after="Saturday")
    revised = await request(host, "Change Friday to Saturday in that note", **continuation(second))
    await confirm(host, action(revised))
    assert (await Notes(host.store, OWNER).get(second_id))["body"] == "Second note Saturday"
    if edit_first:
        assert (await Notes(host.store, OWNER).get(first_id))["body"] == "First note Tuesday"


async def test_named_human_target_takes_precedence_after_machine_creation(ai_host):
    host, provider = ai_host
    domain = Notes(host.store, OWNER)
    human = await domain.create("# Meeting\n\nMonday at 18:00.")
    provider.action_plan = plan("create", body="Created draft Monday")
    first = await request(host, "Create note: Created draft Monday", note_actions=True)
    created = await confirm(host, action(first))
    created_id = next(i["ids"][0] for i in created["items"] if i["type"] == "notes")
    provider.action_plan = plan(
        "edit", target=human["id"], selector="Meeting", before="Monday", after="Tuesday"
    )
    preview = await request(
        host, "Change Monday to Tuesday in the note called Meeting", **continuation(first)
    )
    await confirm(host, action(preview))
    assert (await domain.get(human["id"]))["body"] == "# Meeting\n\nTuesday at 18:00."
    assert (await domain.get(created_id))["body"] == "Created draft Monday"


@pytest.mark.parametrize("duplicate_title", [False, True])
async def test_natural_note_qualifier_uses_only_globally_unique_authoritative_title(
    ai_host, duplicate_title
):
    host, provider = ai_host
    domain = Notes(host.store, OWNER)
    body = "# Expedition briefing\n\n" + "Prepare equipment carefully.\n" * 60
    note = await domain.create(body)
    if duplicate_title:
        await domain.create("# Expedition briefing\n\nA different expedition.")
    provider.action_plan = plan(
        "edit",
        target=note["id"],
        selector="Expedition briefing note",
        before=body,
        after="# Expedition briefing\n\n- [ ] Prepare equipment",
    )
    before = await snapshot(host.store)
    output = await request(
        host, "Rewrite the whole Expedition briefing note as a checklist", note_actions=True
    )
    assert await snapshot(host.store) == before
    if duplicate_title:
        assert not any("action_id" in i.get("args", {}) for i in output["items"])
    else:
        applied = await confirm(host, action(output))
        assert (await domain.get(note["id"]))[
            "body"
        ] == "# Expedition briefing\n\n- [ ] Prepare equipment"
        await confirm(host, action(applied))
        assert (await domain.get(note["id"]))["body"] == body


@pytest.mark.parametrize("matching_notes", [1, 2])
async def test_title_fallback_never_overrides_nonempty_wrong_selector_matches(
    ai_host, matching_notes
):
    host, provider = ai_host
    domain = Notes(host.store, OWNER)
    target = await domain.create("# Expedition briefing\n\nMonday.")
    for number in range(matching_notes):
        await domain.create(
            f"# Other {number}\n\nThe Expedition briefing note is a separate resource."
        )
    provider.action_plan = plan(
        "edit",
        target=target["id"],
        selector="Expedition briefing note",
        before="Monday",
        after="Tuesday",
    )
    output = await request(
        host, "Change Monday to Tuesday in Expedition briefing note", note_actions=True
    )
    assert not any("action_id" in i.get("args", {}) for i in output["items"])
    assert (await domain.get(target["id"]))["version"] == 1


async def test_title_fallback_refuses_substring_only_title_match(ai_host):
    host, provider = ai_host
    domain = Notes(host.store, OWNER)
    target = await domain.create("# Art\n\nCart draft for Monday.")
    provider.action_plan = plan(
        "edit", target=target["id"], selector="Cart note", before="Monday", after="Tuesday"
    )
    output = await request(host, "Change Monday to Tuesday in Cart note", note_actions=True)
    assert not any("action_id" in i.get("args", {}) for i in output["items"])
    assert (await domain.get(target["id"]))["version"] == 1


async def test_uppercase_it_does_not_send_a_created_note_as_authoring_context(ai_host):
    host, provider = ai_host
    provider.action_plan = plan("create", body="Private-context-independent camping draft")
    first = await request(host, "Draft a camping note", note_actions=True)
    await confirm(host, action(first))
    provider.requests.clear()
    provider.action_plan = plan("create", body="# IT checklist\n\n- [ ] Check network access")
    second = await request(host, "Draft an IT checklist", **continuation(first))
    assert action(second)["stage"] == "preview"
    content = next(r for r in provider.requests if "messages" in r)["messages"][1]["content"]
    assert "<authoring-data" not in content


@pytest.mark.parametrize("change", ["draft_source", "new_source", "evicted"])
async def test_review_worker_revalidates_all_factual_sources_and_receipt_before_sending(
    ai_host, monkeypatch, change
):
    host, provider = ai_host
    domain = Notes(host.store, OWNER)
    source = await domain.create("# SourceA\n\nInitial facts.")
    provider.action_plan = plan("create", body="&" * 3500)
    first = await request(host, "Draft a note from SourceA", note_actions=True)
    added = await domain.create("# Deployment\n\nAdditional facts.")
    provider.requests.clear()
    started, release = threading.Event(), threading.Event()
    select = writes.passages

    def pause(*args):
        started.set()
        assert release.wait(3)
        return select(*args)

    monkeypatch.setattr(writes, "passages", pause)
    pending = asyncio.create_task(
        request(host, "Rewrite that draft using Deployment", **continuation(first))
    )
    try:
        assert await asyncio.to_thread(started.wait, 3)
        if change == "evicted":
            del ask.receipts(Ctx(host, "ask", principal=OWNER))[action(first)["action_id"]]
        else:
            await domain.flags(
                source["id"] if change == "draft_source" else added["id"], {"private": True}
            )
    finally:
        release.set()
    with pytest.raises((AIError, Problem)):
        await pending
    assert not [r for r in provider.requests if "messages" in r]


async def test_committed_draft_with_lost_ack_cannot_be_recreated_as_pending_draft(
    ai_host, monkeypatch
):
    host, provider = ai_host
    provider.action_plan = plan("create", body="Pack a tent.")
    first = await request(host, "Draft a camping note", note_actions=True)
    write = host.store.write
    once = True

    @asynccontextmanager
    async def lose_ack():
        nonlocal once
        async with write() as conn:
            yield conn
        if once:
            once = False
            raise asyncio.CancelledError

    monkeypatch.setattr(host.store, "write", lose_ack)
    with pytest.raises(asyncio.CancelledError):
        await confirm(host, action(first))
    with pytest.raises(AIError, match="ai_authoring_changed"):
        await request(host, "Rewrite that draft as a checklist", **continuation(first))
    assert len((await Notes(host.store, OWNER).list())["notes"]) == 1
    recovered = await confirm(host, action(first))
    assert action(recovered)["stage"] == "undo"


async def test_review_context_requires_opt_in_and_cannot_make_machine_notes_factual(ai_host):
    host, provider = ai_host
    started = await host.invoke_command("ask", "ask", {"start": True}, principal=OWNER)
    ctx = Ctx(host, "ask", principal=OWNER, operation="command:ask")
    with pytest.raises(PermissionError, match="reviewed_conversation_required"):
        await ctx.ai.chat(
            writes.NoteActionPlan,
            writes.PLAN_PROMPT,
            context_query="draft",
            source_fingerprints={},
            review_conversation=continuation(started)["conversation_id"],
        )
    provider.action_plan = plan("create", body="Pack a tent.")
    first = await request(host, "Draft a camping note", note_actions=True)
    created = await confirm(host, action(first))
    note_id = next(i["ids"][0] for i in created["items"] if i["type"] == "notes")
    note = await Notes(host.store, OWNER).get(note_id)
    provider.requests.clear()
    with pytest.raises(AIError, match="ai_note_refused"):
        await ctx.ai.chat(
            writes.NoteActionPlan,
            writes.PLAN_PROMPT,
            notes=[note],
            context_query="draft",
            source_fingerprints={note_id: "0" * 64},
            review_conversation=continuation(first)["conversation_id"],
        )
    assert not provider.requests


@pytest.mark.parametrize(
    "question",
    [
        "What did I write about deployment?",
        "How do I edit a note?",
        "Where did I save it?",
        "Come posso modificare una nota?",
        "¿Cómo edito una nota?",
        "Comment modifier une note?",
        "Wie bearbeite ich eine Notiz?",
    ],
)
def test_ordinary_questions_do_not_call_action_classifier(question):
    assert not writes.wants_action(question)


@pytest.mark.parametrize(
    "question", ["What did I write about deployment?", "How do I edit a note?"]
)
async def test_ordinary_opt_in_question_costs_one_chat_and_one_ask_allowance(ai_host, question):
    host, provider = ai_host
    await Notes(host.store, OWNER).create("Deployment note: edit a note in the editor.")

    class Quota:
        def __init__(self):
            self.calls = []

        async def reserve(self, name, **kwargs):
            self.calls.append(name)
            return (name,)

        async def release(self, *args):
            pass

    quota = Quota()
    host.gateway.quota = quota
    provider.requests.clear()
    await request(host, question, note_actions=True)
    assert len([r for r in provider.requests if "messages" in r]) == 1
    assert quota.calls.count("ask") == 1


async def test_apply_near_preview_expiry_offers_full_undo_lifetime(ai_host, monkeypatch):
    host, provider = ai_host
    clock = [host.clock()]
    monkeypatch.setattr(host, "clock", lambda: clock[0])
    _, preview = await edit_preview(host, provider)
    clock[0] += ask.RECEIPT_TTL - 1
    applied = await confirm(host, action(preview))
    clock[0] += ask.RECEIPT_TTL - 1
    undone = await confirm(host, action(applied))
    assert action(undone)["stage"] == "done"


async def test_concurrent_confirmations_commit_once(ai_host):
    host, provider = ai_host
    note, preview = await edit_preview(host, provider)
    results = await asyncio.gather(*[confirm(host, action(preview)) for _ in range(4)])
    assert all(result == results[0] for result in results)
    assert (await Notes(host.store, OWNER).get(note["id"]))["version"] == 2


async def test_permission_increase_requires_current_consent(ai_host):
    host, _ = ai_host
    old = [
        permission
        for permission in host.manifests["ask"]["permissions"]
        if permission != "notes.review"
    ]
    with pytest.raises(ManifestError, match="current manifest permissions"):
        await host.enable("ask", old)


async def test_real_postgres_reviewed_edit_create_authoring_and_stale_undo(pg, settings):
    _, bounds, users, spaces, _ = pg
    principal = replace(OWNER, id=users[0], user_id=users[0], workspace_id=spaces[0])
    domain = Notes(bounds[0], principal)
    provider = FakeProvider()
    profile = Profile(
        name="fixture",
        base_url="http://provider.synthetic/v1",
        chat_model="fixture-chat",
        embed_model="fixture-embed",
        rate_per_min=100000,
    )
    configured = settings.model_copy(
        update={
            "ai_profiles": {"fixture": profile},
            "ai_chat_profile": "fixture",
            "ai_embed_profile": "fixture",
        }
    )
    gateway = Gateway(bounds[0], configured, transport=httpx.ASGITransport(provider.app))
    host = Host(
        bounds[0],
        configured,
        notes_factory=PluginNotes,
        gateway=gateway,
        vectors=VectorIndex(bounds[0], gateway),
    )
    await host.discover()
    try:
        await host.enable("ask", host.manifests["ask"]["permissions"])
        note = await domain.create("# Meeting\n\nMonday at 18:00.\nUnchanged tail.")
        await Notes(bounds[1], replace(principal, id=users[1])).create(
            "# Meeting\n\nPRIVATE-OTHER-SPACE"
        )
        provider.action_plan = plan(
            "edit", target=note["id"], selector="Meeting", before="Monday", after="Tuesday"
        )
        before = await snapshot(bounds[0])
        preview = await request(
            host, "Change Monday to Tuesday in Meeting", principal=principal, note_actions=True
        )
        assert await snapshot(bounds[0]) == before
        applied = await confirm(host, action(preview), principal)
        assert await confirm(host, action(preview), principal) == applied
        assert not (await domain.get(note["id"]))["machine"]
        undone = await confirm(host, action(applied), principal)
        assert action(undone)["stage"] == "done"
        assert (await domain.get(note["id"]))["body"] == note["body"]
        provider.action_plan = plan("create", body="Call Luca Monday")
        preview = await request(
            host, "Create a note: Call Luca Monday", principal=principal, note_actions=True
        )
        created = await confirm(host, action(preview), principal)
        provider.action_plan = plan("edit", before="Monday", after="Tuesday")
        preview = await request(
            host,
            "Change Monday to Tuesday in that note",
            principal=principal,
            **continuation(preview),
        )
        edited = await confirm(host, action(preview), principal)
        created_id = next(item["ids"][0] for item in created["items"] if item["type"] == "notes")
        assert (await domain.get(created_id))["machine"]
        await confirm(host, action(edited), principal)
        with pytest.raises(Problem, match="version_conflict"):
            await confirm(host, action(created), principal)
        assert all(
            "PRIVATE-OTHER-SPACE" not in r["messages"][1]["content"]
            for r in provider.requests
            if "messages" in r
        )
    finally:
        await host.close()
        await gateway.close()
