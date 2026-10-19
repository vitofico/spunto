"""Proposal validation and atomic decisions with real SQLite and REST auth."""

import asyncio

import httpx
import pytest
from conftest import PUBLIC_URL
from fastapi import FastAPI

from spunto.api import errors
from spunto.api.errors import Problem
from spunto.api.suggestions import router
from spunto.auth import tokens
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.core.suggestions import EXPIRY_MS, decide, pending, suggest
from spunto.host import Ctx, Host, ManifestError
from spunto.host.plugin import ContextError
from spunto.store.db import now_ms

pytestmark = pytest.mark.anyio
OWNER = Principal("fixture-owner", "session", "Owner", "full", private_ok=True)


def manifest(plugin_id="proposals", permissions=None):
    return {
        "id": plugin_id,
        "name": "Fixture proposals",
        "version": "1.0.0",
        "api": "0.4",
        "kind": "server",
        "runtime": "in-process",
        "description": "Fixture",
        "default": "off",
        "data_version": 0,
        "permissions": permissions or ["notes.read", "suggest"],
    }


@pytest.fixture
async def proposal_host(server):
    host = Host(
        server.services.store,
        server.services.settings,
        notes_factory=PluginNotes,
        catalog=[manifest(), manifest("outbound", ["suggest", "net.fetch"])],
    )
    await host.discover()
    for plugin_id in host.manifests:
        await host.enable(plugin_id, permissions=host.manifests[plugin_id]["permissions"])
    try:
        yield host
    finally:
        await host.close()


async def stored(host, suggestion_id):
    async with host.store.read() as conn:
        return dict(
            (await conn.execute_fetchall("SELECT * FROM suggestion WHERE id=?", (suggestion_id,)))[
                0
            ]
        )


async def test_closed_payloads_computed_labels_and_pending_budget(proposal_host):
    host = proposal_host
    ctx = Ctx(host, "proposals")
    note = await Notes(host.store, OWNER).create("#existing")
    first = await suggest(ctx, note["id"], "add_label", {"path": "existing"})
    assert not first["payload"]["is_new"]
    second = await suggest(ctx, note["id"], "add_label", {"path": "two"})
    third = await suggest(ctx, note["id"], "add_label", {"path": "three"})
    assert second["payload"]["is_new"] and third["payload"]["is_new"]
    with pytest.raises(ManifestError, match="three pending"):
        await suggest(ctx, note["id"], "append_text", {"text": "fourth"})
    replacement = await suggest(ctx, note["id"], "add_label", {"path": "TWO"})
    assert (await stored(host, second["id"]))["status"] == "superseded"
    assert replacement["status"] == "pending"
    for action, payload, options in [
        ("add_label", {"path": "five", "is_new": False}, {}),
        ("add_label", {"path": "123"}, {}),
        ("set_title", {"title": "bad\nheading"}, {}),
        ("replace_body", {"body": "Unversioned"}, {}),
        ("append_text", {"text": "a" * 4001}, {}),
        ("open", {"note_id": note["id"], "url": "https://example.com"}, {}),
        ("append_text", {"text": "x"}, {"confidence": float("nan")}),
        ("append_text", {"text": "x"}, {"reason": "r" * 281}),
    ]:
        with pytest.raises(ManifestError):
            await suggest(ctx, note["id"], action, payload, **options)
    assert len((await pending(host.store, OWNER))["suggestions"]) == 3


async def test_label_level_weekly_budget_counts_decided_and_superseded_history(proposal_host):
    host = proposal_host
    ctx = Ctx(host, "proposals")
    for n in range(3):
        proposal = await suggest(ctx, None, "label_change", {"old": "old", "new": f"new{n}"})
        await decide(host.store, OWNER, proposal["id"], "dismiss")
    with pytest.raises(ManifestError, match="per plugin and week"):
        await suggest(ctx, None, "label_change", {"old": "old", "new": "fourth"})


async def test_double_accept_is_atomic_idempotent_and_uses_deciding_credential(proposal_host):
    host = proposal_host
    note = await Notes(host.store, OWNER).create("Original")
    proposal = await suggest(
        Ctx(host, "proposals"), note["id"], "append_text", {"text": "Accepted"}
    )
    outcomes = await asyncio.gather(
        *(decide(host.store, OWNER, proposal["id"], "accept") for _ in range(2))
    )
    assert sum(outcome["applied"] for outcome in outcomes) == 1
    saved = await Notes(host.store, OWNER).get(note["id"])
    assert saved["body"] == "Original\n\nAccepted"
    assert saved["version"] == note["version"] + 1
    row = await stored(host, proposal["id"])
    assert row["status"] == "accepted" and row["decided_by"] == OWNER.id
    async with host.store.read() as conn:
        revision = (
            await conn.execute_fetchall(
                "SELECT changed_by FROM note_revision WHERE note_id=? "
                "ORDER BY version DESC LIMIT 1",
                (note["id"],),
            )
        )[0]
    assert revision["changed_by"] == OWNER.id
    with pytest.raises(Problem) as failure:
        await decide(host.store, OWNER, proposal["id"], "dismiss")
    assert failure.value.code == "suggestion_decided"


async def test_append_and_label_accept_against_latest_body_replace_is_stale(proposal_host):
    host = proposal_host
    domain = Notes(host.store, OWNER)
    note = await domain.create("Original")
    ctx = Ctx(host, "proposals")
    replace = await suggest(
        ctx, note["id"], "replace_body", {"body": "Replacement"}, note_version=note["version"]
    )
    append = await suggest(ctx, note["id"], "append_text", {"text": "New paragraph"})
    label = await suggest(ctx, note["id"], "add_label", {"path": "accepted"})
    note = await domain.edit(note["id"], "Owner edited", note["version"])
    with pytest.raises(Problem) as failure:
        await decide(host.store, OWNER, replace["id"], "accept")
    assert failure.value.code == "suggestion_stale"
    await decide(host.store, OWNER, append["id"], "accept")
    await decide(host.store, OWNER, label["id"], "accept")
    saved = await domain.get(note["id"])
    assert saved["body"].startswith("Owner edited\n\nNew paragraph") and saved["labels"] == [
        "accepted"
    ]
    assert (await stored(host, replace["id"]))["status"] == "superseded"


async def test_set_title_replace_open_and_dismiss_effects(proposal_host):
    host = proposal_host
    domain = Notes(host.store, OWNER)
    note = await domain.create("Body")
    ctx = Ctx(host, "proposals")
    title = await suggest(ctx, note["id"], "set_title", {"title": "Chosen title"})
    await decide(host.store, OWNER, title["id"], "accept")
    note = await domain.get(note["id"])
    assert note["body"] == "# Chosen title\n\nBody"
    replace = await suggest(
        ctx, note["id"], "replace_body", {"body": "Edited explicitly"}, note_version=note["version"]
    )
    await decide(host.store, OWNER, replace["id"], "accept")
    open_note = await suggest(ctx, note["id"], "open", {"note_id": note["id"]})
    assert (await decide(host.store, OWNER, open_note["id"], "accept"))["result"] == {
        "open": {"note_id": note["id"]}
    }
    dismissed = await suggest(ctx, note["id"], "append_text", {"text": "Never inserted"})
    await decide(host.store, OWNER, dismissed["id"], "dismiss")
    assert (await domain.get(note["id"]))["body"] == "Edited explicitly"


async def test_merge_updates_target_and_trashes_source_atomically_and_rejects_changed_versions(
    proposal_host,
):
    host = proposal_host
    domain = Notes(host.store, OWNER)
    source, target = await domain.create("Source #one"), await domain.create("Target #two")
    ctx = Ctx(host, "proposals")
    proposal = await suggest(
        ctx, target["id"], "merge_notes", {"source_id": source["id"], "target_id": target["id"]}
    )
    source = await domain.append(source["id"], "Changed")
    with pytest.raises(Problem) as failure:
        await decide(host.store, OWNER, proposal["id"], "accept")
    assert failure.value.code == "version_conflict"
    assert (await domain.get(target["id"]))["body"] == "Target #two"
    assert not (await domain.get(source["id"]))["trashed"]
    proposal = await suggest(
        ctx, target["id"], "merge_notes", {"source_id": source["id"], "target_id": target["id"]}
    )
    await decide(host.store, OWNER, proposal["id"], "accept")
    merged = await domain.get(target["id"])
    assert merged["body"] == "Target #two\n\nSource #one\n\nChanged"
    assert (await domain.get(source["id"], include_trashed=True))["trashed"]
    assert set(merged["labels"]) == {"one", "two"}


async def test_label_change_uses_tokenizer_all_notes_and_rejects_devices(proposal_host):
    host = proposal_host
    domain = Notes(host.store, OWNER)
    note = await domain.create("#old/a #oldish `#old`\n```\n#old\n```", private=True)
    another = await domain.create("#old")
    proposal = await suggest(
        Ctx(host, "proposals"), None, "label_change", {"old": "old", "new": "new"}
    )
    device = Principal("fixture-device", "device", "Device", "full", private_ok=True)
    with pytest.raises(Problem) as failure:
        await decide(host.store, device, proposal["id"], "accept")
    assert failure.value.code == "session_required"
    assert (await stored(host, proposal["id"]))["status"] == "pending"
    assert (await decide(host.store, OWNER, proposal["id"], "accept"))["result"] == {"changed": 2}
    assert (await domain.get(note["id"]))["body"] == "#new/a #oldish `#old`\n```\n#old\n```"
    assert (await domain.get(another["id"]))["labels"] == ["new"]


async def test_disabled_stale_generation_privacy_and_expiry(proposal_host):
    host = proposal_host
    domain = Notes(host.store, OWNER)
    note = await domain.create("Private", private=True)
    ctx = Ctx(host, "proposals")
    proposal = await suggest(ctx, note["id"], "append_text", {"text": "Pending"})
    pat = Principal("fixture-reader", "pat", "Reader", "read", private_ok=False)
    assert (await pending(host.store, pat))["suggestions"] == []
    with pytest.raises(Problem) as failure:
        await suggest(Ctx(host, "outbound"), note["id"], "append_text", {"text": "Hidden"})
    assert failure.value.status == 404
    await host.disable("proposals")
    assert (await pending(host.store, OWNER))["suggestions"] == []
    with pytest.raises(Problem) as failure:
        await decide(host.store, OWNER, proposal["id"], "accept")
    assert failure.value.status == 404
    await host.enable("proposals", permissions=host.manifests["proposals"]["permissions"])
    with pytest.raises(ContextError):
        await suggest(ctx, note["id"], "append_text", {"text": "Stale handle"})
    async with host.store.write() as conn:
        await conn.execute(
            "UPDATE suggestion SET created_at=? WHERE id=?",
            (now_ms() - EXPIRY_MS - 1, proposal["id"]),
        )
    with pytest.raises(Problem) as failure:
        await decide(host.store, OWNER, proposal["id"], "accept")
    assert failure.value.code == "suggestion_stale"
    assert (await stored(host, proposal["id"]))["status"] == "expired"
    assert (await domain.get(note["id"]))["body"] == "Private"


async def test_body_limit_failure_rolls_back_note_and_decision(proposal_host):
    host = proposal_host
    note = await Notes(host.store, OWNER).create("x" * 99_999)
    proposal = await suggest(Ctx(host, "proposals"), note["id"], "append_text", {"text": "xx"})
    with pytest.raises(Problem) as failure:
        await decide(host.store, OWNER, proposal["id"], "accept")
    assert failure.value.code == "validation_failed"
    assert (await stored(host, proposal["id"]))["status"] == "pending"
    assert (await Notes(host.store, OWNER).get(note["id"]))["version"] == note["version"]


async def test_rest_origin_read_write_scopes_and_private_visibility(proposal_host, server):
    host = proposal_host
    note = await Notes(host.store, OWNER).create("Owner note")
    proposal = await suggest(Ctx(host, "proposals"), note["id"], "append_text", {"text": "Allowed"})
    response = await server.client.post(
        "/api/v1/setup",
        json={"code": server.services.setup_code, "password": "correct horse battery"},
    )
    assert response.status_code == 204
    app = FastAPI()
    app.state.services = server.services
    errors.install(app)
    app.include_router(router)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app), base_url=PUBLIC_URL, cookies=server.client.cookies
    ) as client:
        assert (
            await client.post(f"/api/v1/suggestions/{proposal['id']}/accept")
        ).status_code == 403
        response = await client.post(
            f"/api/v1/suggestions/{proposal['id']}/accept", headers={"Origin": PUBLIC_URL}
        )
        assert response.status_code == 200 and response.json()["applied"]
        _, secret = await tokens.create(host.store, name="Reader", client="fixture", scope="read")
        headers = {"Authorization": "Bearer " + secret}
        assert (await client.get("/api/v1/suggestions", headers=headers)).status_code == 200
        assert (
            await client.post(f"/api/v1/suggestions/{proposal['id']}/dismiss", headers=headers)
        ).status_code == 403
        note = await Notes(host.store, OWNER).create("Write-token note")
        proposal = await suggest(
            Ctx(host, "proposals"), note["id"], "append_text", {"text": "PAT acceptance"}
        )
        _, secret = await tokens.create(host.store, name="Writer", client="fixture", scope="write")
        response = await client.post(
            f"/api/v1/suggestions/{proposal['id']}/accept",
            headers={"Authorization": "Bearer " + secret},
        )
        assert response.status_code == 200
        private = await Notes(host.store, OWNER).create("Secret", private=True)
        proposal = await suggest(
            Ctx(host, "proposals"), private["id"], "append_text", {"text": "Hidden"}
        )
        response = await client.post(
            f"/api/v1/suggestions/{proposal['id']}/accept",
            headers={"Authorization": "Bearer " + secret},
        )
        assert response.status_code == 404


async def test_private_related_merge_and_open_targets_are_hidden_from_read_tokens(proposal_host):
    host = proposal_host
    domain = Notes(host.store, OWNER)
    source, target = await domain.create("Source"), await domain.create("Target")
    ctx = Ctx(host, "proposals")
    await suggest(
        ctx, target["id"], "merge_notes", {"source_id": source["id"], "target_id": target["id"]}
    )
    await suggest(ctx, target["id"], "open", {"note_id": source["id"]})
    await domain.flags(source["id"], {"private": True})
    reader = Principal("fixture-reader", "pat", "Reader", "read", private_ok=False)
    assert (await pending(host.store, reader))["suggestions"] == []
    assert len((await pending(host.store, OWNER))["suggestions"]) == 2


async def test_egress_new_label_flag_does_not_reveal_private_label_vocabulary(proposal_host):
    host = proposal_host
    domain = Notes(host.store, OWNER)
    await domain.create("Private label #hidden", private=True)
    public = await domain.create("Public")
    proposal = await suggest(Ctx(host, "outbound"), public["id"], "add_label", {"path": "hidden"})
    assert proposal["payload"]["is_new"]
    public = await domain.append(public["id"], "Owner change")
    with pytest.raises(Problem) as failure:
        await suggest(
            Ctx(host, "proposals"),
            public["id"],
            "replace_body",
            {"body": "Stale"},
            note_version=public["version"] - 1,
        )
    assert failure.value.code == "version_conflict"


@pytest.mark.parametrize("action", ["merge_notes", "open"])
@pytest.mark.parametrize("decision", ["accept", "dismiss"])
async def test_decision_rechecks_related_private_note_before_mutation(
    proposal_host,
    action,
    decision,
):
    host = proposal_host
    domain = Notes(host.store, OWNER)
    source, target = await domain.create("Related"), await domain.create("Target")
    payload = (
        {"source_id": source["id"], "target_id": target["id"]}
        if action == "merge_notes"
        else {"note_id": source["id"]}
    )
    proposal = await suggest(Ctx(host, "proposals"), target["id"], action, payload)
    await domain.flags(source["id"], {"private": True})
    writer = Principal("fixture-writer", "pat", "Writer", "write", private_ok=False)
    assert (await pending(host.store, writer))["suggestions"] == []
    with pytest.raises(Problem) as failure:
        await decide(host.store, writer, proposal["id"], decision)
    assert failure.value.status == 404
    row = await stored(host, proposal["id"])
    assert row["status"] == "pending" and row["decided_by"] is None
    assert (await domain.get(target["id"]))["body"] == "Target"


@pytest.mark.parametrize("decision", ["accept", "dismiss"])
async def test_repeated_decision_rechecks_related_private_note_before_returning_payload(
    proposal_host,
    decision,
):
    host = proposal_host
    domain = Notes(host.store, OWNER)
    source, target = await domain.create("Related"), await domain.create("Target")
    proposal = await suggest(
        Ctx(host, "proposals"), target["id"], "open", {"note_id": source["id"]}
    )
    await decide(host.store, OWNER, proposal["id"], decision)
    before = await stored(host, proposal["id"])
    await domain.flags(source["id"], {"private": True})
    writer = Principal("fixture-writer", "pat", "Writer", "write", private_ok=False)
    with pytest.raises(Problem) as failure:
        await decide(host.store, writer, proposal["id"], decision)
    assert failure.value.status == 404
    assert await stored(host, proposal["id"]) == before


async def test_pat_cannot_dismiss_hidden_global_review_or_read_completed_payload(proposal_host):
    host = proposal_host
    ctx = Ctx(host, "proposals")
    writer = Principal("fixture-writer", "pat", "Writer", "write", private_ok=True)
    proposal = await suggest(ctx, None, "label_change", {"old": "owner-only", "new": "reviewed"})
    assert (await pending(host.store, writer, scope="review"))["suggestions"] == []
    with pytest.raises(Problem) as failure:
        await decide(host.store, writer, proposal["id"], "dismiss")
    assert failure.value.status == 404
    assert (await stored(host, proposal["id"]))["status"] == "pending"
    await decide(host.store, OWNER, proposal["id"], "accept")
    before = await stored(host, proposal["id"])
    with pytest.raises(Problem) as failure:
        await decide(host.store, writer, proposal["id"], "accept")
    assert failure.value.status == 404
    assert await stored(host, proposal["id"]) == before


async def test_repeated_merge_accept_still_checks_privacy_and_is_idempotent_after_trash(
    proposal_host,
):
    host = proposal_host
    domain = Notes(host.store, OWNER)
    source, target = await domain.create("Related"), await domain.create("Target")
    proposal = await suggest(
        Ctx(host, "proposals"),
        target["id"],
        "merge_notes",
        {"source_id": source["id"], "target_id": target["id"]},
    )
    await decide(host.store, OWNER, proposal["id"], "accept")
    assert not (await decide(host.store, OWNER, proposal["id"], "accept"))["applied"]
    await domain.flags(source["id"], {"private": True})
    writer = Principal("fixture-writer", "pat", "Writer", "write", private_ok=False)
    with pytest.raises(Problem) as failure:
        await decide(host.store, writer, proposal["id"], "accept")
    assert failure.value.status == 404
    assert (await domain.get(target["id"]))["body"] == "Target\n\nRelated"
