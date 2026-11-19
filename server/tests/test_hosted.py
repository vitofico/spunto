"""HTTP acceptance with real PostgreSQL and synthetic, distinct OIDC identities."""

import asyncio
import json
import os
import time
import uuid
from pathlib import Path
from urllib.parse import quote

import httpx
import psycopg
import pytest
from conftest import SYNTHETIC_ISSUER, HostedRows

from spunto.auth import sessions
from spunto.auth.oidc import OIDCIdentity
from spunto.auth.tokens import Principal
from spunto.config import Settings
from spunto.core.notes import Notes
from spunto.hosted import create_hosted_app, hosted_startup
from spunto.jobs.maintenance import DAY_MS, prune
from spunto.store.db import now_ms

pytestmark = [pytest.mark.anyio, pytest.mark.postgres]
ORIGIN = "http://127.0.0.1:18081"


@pytest.fixture
async def hosted(tmp_path, hosted_rows, pending_hosted_rows):
    runtime = os.environ.get("SPUNTO_TEST_POSTGRES_URL")
    identity = os.environ.get("SPUNTO_TEST_IDENTITY_URL")
    admin = os.environ.get("SPUNTO_TEST_POSTGRES_ADMIN_URL")
    if not all((runtime, identity, admin)):
        pytest.skip("Set the three disposable PostgreSQL test connection URLs")
    settings = Settings(
        public_url=ORIGIN,
        data_dir=tmp_path,
        database_url=runtime,
        identity_database_url=identity,
        oidc_issuer=SYNTHETIC_ISSUER,
        oidc_client_id="synthetic-spunto",
        oidc_allow_insecure_loopback=True,
        mcp_enabled=True,
    )
    hosted_rows.admin = admin
    pending_hosted_rows.append(hosted_rows)
    try:
        async with hosted_startup(settings) as services:
            manager = services.hosted
            users = []
            for name in ("Alice", "Bob", "Carol"):
                subject = "pytest-" + uuid.uuid4().hex
                hosted_rows.subjects.append(subject)
                account, workspace = await manager.accounts.sign_in(
                    OIDCIdentity(
                        SYNTHETIC_ISSUER, subject, f"{subject}@synthetic.invalid", True, name
                    )
                )
                token = await manager.accounts.issue_session(account["id"])
                users.append({"account": account, "workspace": workspace, "token": token})
            app = create_hosted_app(services)
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url=ORIGIN
            ) as client:
                yield client, manager, users, settings
    finally:
        await hosted_rows.clean()
        pending_hosted_rows.remove(hosted_rows)


def headers(user, workspace=None):
    result = {"Cookie": "session=" + user["token"], "Origin": ORIGIN}
    if workspace:
        result["X-Spunto-Workspace"] = workspace
    return result


async def test_hosted_link_insert_serializes_retries_and_obeys_workspace_visibility(hosted):
    client, _, users, _ = hosted
    alice, bob, _ = users
    catalog = (await client.get("/api/v1/plugins", headers=headers(alice))).json()
    links = next(plugin for plugin in catalog["plugins"] if plugin["id"] == "links")
    enabled = await client.post(
        "/api/v1/plugins/links/enable",
        headers=headers(alice),
        json={"permissions": links["permissions"]},
    )
    assert enabled.status_code == 200, enabled.text

    async def create_note(user, body, private=False):
        response = await client.post(
            "/api/v1/notes", headers=headers(user), json={"body": body, "private": private}
        )
        assert response.status_code == 201, response.text
        return response.json()

    source = await create_note(alice, "Synthetic source")
    target = await create_note(alice, "Synthetic private title", private=True)
    inaccessible = await create_note(bob, "Synthetic other workspace title")

    async def insert(destination):
        return await client.post(
            "/api/v1/plugins/links/commands/link",
            headers=headers(alice),
            json={
                "note_id": source["id"],
                "base_version": source["version"],
                "target_id": destination["id"],
                "target_version": destination["version"],
                "insert": True,
            },
        )

    rejected = await insert(inaccessible)
    assert rejected.status_code == 404 and "Synthetic other workspace title" not in rejected.text
    results = await asyncio.gather(insert(target), insert(target))
    assert all(result.status_code == 200 for result in results)
    assert {result.json()["text"] for result in results} == {
        "Link inserted.",
        "This note already links to the selected note.",
    }
    saved = (await client.get(f"/api/v1/notes/{source['id']}", headers=headers(alice))).json()
    assert saved["version"] == source["version"] + 1
    assert saved["body"] == f"Synthetic source\n\n[Linked note](/n/{target['id']})"


async def test_hosted_note_acceptance_checks_roles_and_preserves_provenance(hosted):
    client, manager, (alice, bob, carol), _ = hosted
    created = await client.post(
        "/api/v1/workspaces", headers=headers(alice), json={"name": "Synthetic review"}
    )
    assert created.status_code == 201, created.text
    space = created.json()["id"]
    owner, editor, viewer = (headers(member, space) for member in (alice, bob, carol))
    for member, role in ((bob, "editor"), (carol, "viewer")):
        added = await client.post(
            f"/api/v1/workspaces/{space}/members",
            headers=owner,
            json={"user_id": member["account"]["id"], "role": role},
        )
        assert added.status_code == 200, added.text
    owner_notes = Notes(
        manager._runtimes[space].store,
        Principal("plugin:ask", "plugin", "Ask", "plugin", user_id=alice["account"]["id"]),
    )
    async with owner_notes.store.write() as conn:
        machine = await owner_notes._insert(
            conn,
            "Synthetic Ask answer",
            str(uuid.uuid7()),
            now_ms(),
            {"kind": "plugin", "ref": "ask"},
            True,
            origin="plugin",
            machine=True,
        )
    adoption = f"/api/v1/notes/{machine['id']}/adopt"
    base = {"base_version": machine["version"]}
    denied = await client.post(adoption, headers=viewer, json=base)
    assert denied.status_code == 403 and denied.json()["code"] == "read_only_space"
    accepted = await client.post(adoption, headers=editor, json=base)
    assert accepted.status_code == 200, accepted.text
    assert not accepted.json()["machine"] and accepted.json()["private"]
    assert accepted.json()["origin"] == "plugin" and accepted.json()["body"] == machine["body"]

    token = await client.post(
        "/api/v1/tokens", headers=owner, json={"name": "Review fixture", "scope": "write"}
    )
    assert token.status_code == 201, token.text
    pat = {"Authorization": "Bearer " + token.json()["token"], "X-Spunto-Workspace": space}
    denied = await client.post(adoption, headers=pat, json=base)
    assert denied.status_code == 403 and denied.json()["code"] == "owner_required"
    note = await client.post("/api/v1/notes", headers=pat, json={"body": "Synthetic agent draft"})
    assert note.status_code == 201, note.text
    draft = note.json()
    plugin = "/api/v1/plugins/agent-inbox"
    enabled = await client.post(
        plugin + "/enable", headers=owner, json={"permissions": ["notes.read", "annotate"]}
    )
    assert enabled.status_code == 200, enabled.text
    command = plugin + "/commands/mark-reviewed"
    review = {"note_id": draft["id"], "base_version": draft["version"]}
    denied = await client.post(command, headers=editor, json=review)
    assert denied.status_code == 403 and denied.json()["code"] == "owner_required"
    receipt = await client.post(command, headers=owner, json=review)
    assert receipt.status_code == 200 and receipt.json()["text"] == "Note accepted"
    view = "/api/v1/notes?view=agent-inbox/reviewed"
    assert (await client.get(view, headers=owner)).json()["notes"] == []
    changed = await client.post(
        f"/api/v1/notes/{draft['id']}/append", headers=pat, json={"text": "New agent revision"}
    )
    assert changed.status_code == 200, changed.text
    [pending] = (await client.get(view, headers=owner)).json()["notes"]
    assert pending["id"] == draft["id"] and pending["origin"] == "agent"
    stale = await client.post(command, headers=owner, json=review)
    assert stale.status_code == 409 and stale.json()["code"] == "version_conflict"


async def test_hosted_decision_journal_owner_privacy_and_purge_replay(hosted):
    client, manager, users, settings = hosted
    alice, bob, carol = users
    created = await client.post(
        "/api/v1/workspaces", headers=headers(alice), json={"name": "Synthetic decisions"}
    )
    assert created.status_code == 201, created.text
    space = created.json()["id"]
    owner = headers(alice, space)
    for member, role in ((bob, "editor"), (carol, "viewer")):
        added = await client.post(
            f"/api/v1/workspaces/{space}/members",
            headers=owner,
            json={"user_id": member["account"]["id"], "role": role},
        )
        assert added.status_code == 200, added.text
    prefix = "/api/v1/plugins/decision-journal"
    enabled = await client.post(
        prefix + "/enable",
        headers=owner,
        json={"permissions": ["notes.read", "notes.create", "annotate"]},
    )
    assert enabled.status_code == 200, enabled.text

    async def journal_command(name, args, actor=owner, status=200):
        response = await client.post(prefix + "/commands/" + name, headers=actor, json=args)
        assert response.status_code == status, response.text
        return response.json()

    async def purge_note(note_id):
        path = f"/api/v1/notes/{note_id}"
        trashed = await client.post(path + "/flags", headers=owner, json={"trashed": True})
        assert trashed.status_code == 200, trashed.text
        removed = await client.delete(path, headers=owner)
        assert removed.status_code == 204, removed.text

    decision = {
        "decision": "Keep the confidential synthetic decision.",
        "private": True,
        "request_id": str(uuid.uuid7()),
    }
    [note_id] = (await journal_command("record-decision", decision))["ids"]
    outcome = {
        "note_id": note_id,
        "outcome": "The synthetic outcome is complete.",
        "status": "closed",
        "base_version": 1,
        "request_id": str(uuid.uuid7()),
    }
    outcome_id, _ = (await journal_command("review-decision", outcome))["ids"]
    detail = await journal_command("journal", {"note_id": note_id})
    assert detail["items"][0]["text"] == "Closed"
    for member, code in ((bob, "owner_required"), (carol, "read_only_space")):
        actor = headers(member, space)
        for name, args in (
            ("journal", {"note_id": note_id}),
            ("record-decision", decision),
            ("review-decision", outcome),
        ):
            rejected = await journal_command(name, args, actor, status=403)
            assert rejected["code"] == code
        for private_id in (note_id, outcome_id):
            # The private flag controls egress, while workspace membership controls note access.
            shared = await client.get(f"/api/v1/notes/{private_id}", headers=actor)
            assert shared.status_code == 200 and shared.json()["private"] is True
            hidden = await client.get(f"/api/v1/notes/{private_id}", headers=headers(member))
            assert hidden.status_code == 404

    runtime = manager._runtimes[space]
    async with runtime.store.write() as conn:
        await conn.execute("DELETE FROM applied_op WHERE credential_id='plugin:decision-journal'")
    await purge_note(outcome_id)
    outcome_replay = await journal_command("review-decision", outcome)
    assert outcome_replay["type"] == "text" and "unavailable" in outcome_replay["text"]
    await purge_note(note_id)
    await prune(runtime.store, settings, now=now_ms() + 31 * DAY_MS)
    decision_replay = await journal_command("record-decision", decision)
    assert decision_replay["type"] == "text" and "unavailable" in decision_replay["text"]
    assert (await client.get("/api/v1/notes", headers=owner)).json()["notes"] == []


async def test_http_policy_changes_atomic_account_allowance_without_resetting_usage(hosted):
    from spunto.ai.policy import HTTPPolicy
    from spunto.ai.quota import ACTIONS, AccountQuota, QuotaExceeded

    client, manager, users, settings = hosted
    alice, bob, _ = users
    now = [1_790_000_000]
    raised = [False]

    def policy_response(request):
        account_id = request.url.path.rsplit("/", 1)[1]
        limit = 3 if raised[0] and account_id == alice["account"]["id"] else 1
        return httpx.Response(
            200,
            json={
                "version": 1,
                "account_id": account_id,
                "expires_at": now[0] + 120,
                "limits": dict.fromkeys(ACTIONS, limit),
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(policy_response)) as http:
        policy = HTTPPolicy(
            "http://policy.example.test/v1/accounts",
            "synthetic",
            dict.fromkeys(ACTIONS, 1),
            client=http,
            clock=lambda: now[0],
        )
        quota = AccountQuota(
            manager.accounts, alice["account"]["id"], settings.ai_quota, policy=policy
        )
        # Two spaces owned by the same account cannot evade its authoritative counter.
        other_space = AccountQuota(
            manager.accounts, alice["account"]["id"], settings.ai_quota, policy=policy
        )
        await quota.reserve("ask")
        with pytest.raises(QuotaExceeded):
            await other_space.reserve("ask")
        raised[0] = True
        now[0] += 121
        await other_space.reserve("ask")
        status = await quota.status()
        ask = next(row for row in status["actions"] if row["action"] == "ask")
        assert (ask["used"], ask["limit"]) == (2, 3)
        assert (await policy.limits(bob["account"]["id"]))["ask"] == 1
        raised[0] = False
        now[0] += 121
        with pytest.raises(QuotaExceeded):
            await quota.reserve("ask")
        assert (await client.get("/api/v1/notes", headers=headers(alice))).status_code == 200
        assert (await client.get("/api/v1/export", headers=headers(alice))).status_code == 200


async def test_generic_account_limits_are_atomic_shared_and_keep_reads_export(hosted):
    from spunto.account_policy import AccountPolicy
    from spunto.ai.policy import HTTPPolicy
    from spunto.ai.quota import ACTIONS, AccountQuota, QuotaExceeded

    client, manager, users, settings = hosted
    alice, bob, carol = users
    resources = {
        "storage_bytes": 2000,
        "file_bytes": 50,
        "owned_shared_spaces": 1,
        "members_per_shared_space": 2,
        "mcp_writes_daily": 2,
        "ask_daily": 2,
        "transcription_seconds_monthly": 60,
    }
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda req: httpx.Response(
                200,
                json={
                    "version": 1,
                    "account_id": req.url.path.rsplit("/", 1)[1],
                    "expires_at": int(time.time()) + 120,
                    "limits": dict.fromkeys(ACTIONS, 100),
                    "resources": resources,
                },
            )
        )
    ) as http:
        source = HTTPPolicy(
            "http://policy.synthetic/accounts", "synthetic", settings.ai_quota, client=http
        )
        manager.ai_policy = source
        manager.account_policy = AccountPolicy(manager.accounts, source)
        manager.store.account_policy = manager.account_policy
        await manager.account_policy.prepare(alice["account"]["id"])
        made = await client.post("/api/v1/workspaces", headers=headers(alice), json={"name": "One"})
        assert made.status_code == 201, made.text
        workspace = made.json()["id"]
        assert (
            await client.post("/api/v1/workspaces", headers=headers(alice), json={"name": "Two"})
        ).status_code == 429
        assert (
            await client.post(
                f"/api/v1/workspaces/{workspace}/members",
                headers=headers(alice),
                json={"user_id": bob["account"]["id"], "role": "editor"},
            )
        ).status_code == 200
        assert (
            await client.post(
                f"/api/v1/workspaces/{workspace}/members",
                headers=headers(alice),
                json={"user_id": carol["account"]["id"], "role": "editor"},
            )
        ).status_code == 429
        first = await client.post("/api/v1/notes", headers=headers(alice), json={"body": "A" * 600})
        assert first.status_code == 201, first.text
        overflow = await client.post(
            "/api/v1/notes", headers=headers(bob, workspace), json={"body": "B" * 600}
        )
        assert overflow.status_code == 429, overflow.text
        # A different owner retains their own full storage allowance.
        assert (
            await client.post("/api/v1/notes", headers=headers(bob), json={"body": "B" * 600})
        ).status_code == 201
        assert (
            await client.post(
                "/api/v1/attachments",
                headers=headers(alice),
                files={"file": ("large.bin", b"x" * 51, "application/octet-stream")},
            )
        ).status_code == 413
        quota = AccountQuota(
            manager.accounts, alice["account"]["id"], settings.ai_quota, policy=source
        )
        await quota.reserve("ask")
        await quota.reserve("ask")
        with pytest.raises(QuotaExceeded):
            await quota.reserve("ask")
        reserved = await quota.reserve("transcribe", seconds=50)
        with pytest.raises(QuotaExceeded):
            await quota.reserve("transcribe", seconds=20)
        await quota.release(*reserved)
        await quota.reserve("transcribe", seconds=60)
        await manager.account_policy.take(alice["account"]["id"], "mcp_writes_daily")
        await manager.account_policy.take(alice["account"]["id"], "mcp_writes_daily")
        from spunto.api.errors import Problem

        with pytest.raises(Problem):
            await manager.account_policy.take(alice["account"]["id"], "mcp_writes_daily")
        assert (await client.get("/api/v1/notes", headers=headers(alice))).status_code == 200
        assert (
            await client.get("/api/v1/account/export", headers=headers(alice))
        ).status_code == 200


async def test_account_closure_requires_choice_revokes_credentials_and_purges_only_owned(
    hosted, hosted_rows
):
    client, manager, users, settings = hosted
    alice, bob, carol = users
    settings.account_service_token = "synthetic-lifecycle-service-token-0000"
    await client.post("/api/v1/notes", headers=headers(alice), json={"body": "Alice disposable"})
    await client.post("/api/v1/notes", headers=headers(bob), json={"body": "Bob retained"})
    shared = (
        await client.post(
            "/api/v1/workspaces", headers=headers(alice), json={"name": "Shared disposable"}
        )
    ).json()
    await client.post(
        f"/api/v1/workspaces/{shared['id']}/members",
        headers=headers(alice),
        json={"user_id": bob["account"]["id"], "role": "editor"},
    )
    body = {"confirm": "DELETE MY ACCOUNT"}
    assert (
        await client.post("/api/v1/auth/account/closure", headers=headers(alice), json=body)
    ).status_code == 409
    unverified = await client.post(
        "/api/v1/auth/account/closure",
        headers=headers(alice),
        json=body | {"delete_owned_shared": True},
    )
    assert unverified.status_code == 403
    await manager.accounts.grant_reauthentication(
        alice["account"]["id"], alice["token"], "account-delete"
    )
    await manager.accounts.one(
        "UPDATE public.spunto_account_reauthentication SET expires_at=0 "
        "WHERE user_id=%s RETURNING user_id",
        (alice["account"]["id"],),
    )
    assert (
        await client.post(
            "/api/v1/auth/account/closure",
            headers=headers(alice),
            json=body | {"delete_owned_shared": True},
        )
    ).status_code == 403
    await manager.accounts.grant_reauthentication(
        alice["account"]["id"], alice["token"], "account-delete"
    )
    prepared = await client.post(
        "/api/v1/auth/account/closure",
        headers=headers(alice),
        json=body | {"delete_owned_shared": True},
    )
    assert prepared.status_code == 200, prepared.text
    receipt = {"closure_id": prepared.json()["closure_id"]}
    hosted_rows.receipts.append((receipt["closure_id"], alice["account"]["id"]))
    url = f"/internal/v1/accounts/{alice['account']['id']}"
    assert (await client.post(url + "/close", json=receipt)).status_code == 401
    service = {"Authorization": "Bearer " + settings.account_service_token}
    assert (await client.post(url + "/purge", json=receipt, headers=service)).status_code == 409
    assert (await client.post(url + "/close", json=receipt, headers=service)).status_code == 200
    assert (await client.get("/api/v1/notes", headers=headers(alice))).status_code == 401
    assert (await client.post(url + "/close", json=receipt, headers=service)).status_code == 200
    purged = await client.post(url + "/purge", json=receipt, headers=service)
    assert purged.status_code == 200, purged.text
    assert (await client.post(url + "/purge", json=receipt, headers=service)).status_code == 200
    remaining = await client.get("/api/v1/notes", headers=headers(bob))
    assert remaining.status_code == 200 and remaining.json()["notes"][0]["body"] == "Bob retained"
    assert (
        await manager.accounts.one(
            "SELECT id FROM public.spunto_account WHERE id=%s", (alice["account"]["id"],)
        )
        is None
    )
    # Probe teardown in a rolled-back transaction. Carol's workspace and
    # receipt are independent even though the fixture owns both test accounts.
    bystander_receipt = "pytest-" + uuid.uuid4().hex
    hosted_rows.receipts.append((bystander_receipt, carol["account"]["id"]))
    assert hosted_rows.admin
    async with await psycopg.AsyncConnection.connect(hosted_rows.admin) as conn:
        await conn.execute(
            "INSERT INTO public.spunto_account_closure(id,user_id,shared_choice,created_at) "
            "VALUES(%s,%s,false,%s)",
            (bystander_receipt, carol["account"]["id"], now_ms()),
        )
        workspace_ids = [bob["workspace"]["id"], carol["workspace"]["id"]]
        receipt_ids = [receipt["closure_id"], bystander_receipt]
        workspace_query = "SELECT id FROM public.spunto_workspace WHERE id=ANY(%s)"
        receipt_query = "SELECT id FROM public.spunto_account_closure WHERE id=ANY(%s)"
        async with conn.transaction(force_rollback=True):
            await HostedRows(
                subjects=[bob["account"]["subject"]],
                receipts=[(receipt["closure_id"], alice["account"]["id"])],
            ).remove(conn)
            assert await (await conn.execute(workspace_query, (workspace_ids,))).fetchall() == [
                (carol["workspace"]["id"],)
            ], "cleanup must remove only the selected synthetic workspace owner"
            assert await (await conn.execute(receipt_query, (receipt_ids,))).fetchall() == [
                (bystander_receipt,)
            ]
        assert {
            row[0]
            for row in await (await conn.execute(workspace_query, (workspace_ids,))).fetchall()
        } == set(workspace_ids)
        assert {
            row[0] for row in await (await conn.execute(receipt_query, (receipt_ids,))).fetchall()
        } == set(receipt_ids)


async def test_ai_model_choices_are_owner_managed_scoped_and_survive_restart(hosted, monkeypatch):
    from spunto.ai.gateway import Gateway
    from spunto.ai.profiles import Profile

    client, manager, users, settings = hosted
    alice, bob, carol = users
    profile = Profile(name="fixture", base_url="http://provider.synthetic/v1", chat_model="old")
    settings.ai_profiles = {"fixture": profile}
    settings.ai_chat_profile = "fixture"

    async def models(*args, **kwargs):
        return [{"id": "new", "capabilities": ["chat"], "leaves_network": False}]

    async def health(self):
        return []

    monkeypatch.setattr("spunto.ai.selection.inventory", models)
    monkeypatch.setattr(Gateway, "health_check", health)
    current = (await client.get("/api/v1/ai", headers=headers(alice))).json()
    selection = {"chat": {"profile": "fixture", "model": "new"}}
    updated = await client.put(
        "/api/v1/ai/models",
        headers=headers(alice),
        json={"revision": current["model_selection"]["revision"], "selections": selection},
    )
    assert updated.status_code == 200, updated.text
    assert updated.json()["model_selection"]["selections"] == selection
    bob_status = await client.get("/api/v1/ai", headers=headers(bob))
    assert bob_status.json()["model_selection"]["selections"] == {}
    assert bob_status.json()["profiles"][0]["chat_model"] == "old"
    assert settings.ai_profiles["fixture"].chat_model == "old"
    assert (
        manager._runtimes[alice["workspace"]["id"]].ai.settings.ai_profiles
        is not settings.ai_profiles
    )

    shared = (
        await client.post(
            "/api/v1/workspaces", headers=headers(alice), json={"name": "Synthetic AI space"}
        )
    ).json()
    for user, role in ((bob, "editor"), (carol, "viewer")):
        member = await client.post(
            f"/api/v1/workspaces/{shared['id']}/members",
            headers=headers(alice),
            json={"user_id": user["account"]["id"], "role": role},
        )
        assert member.status_code == 200, member.text
        scoped = headers(user, shared["id"])
        status = await client.get("/api/v1/ai", headers=scoped)
        assert status.status_code == 200 and status.json()["can_manage"] is False
        assert (await client.get("/api/v1/ai/models", headers=scoped)).status_code == 403
        assert (
            await client.put(
                "/api/v1/ai/models",
                headers=scoped,
                json={"revision": status.json()["model_selection"]["revision"], "selections": {}},
            )
        ).status_code == 403
        assert (await client.post("/api/v1/ai/check", headers=scoped)).status_code == 403
        assert (await client.post("/api/v1/ai/reindex", headers=scoped, json={})).status_code == 403

    async with hosted_startup(settings) as restarted:
        runtime = restarted.hosted._runtimes[alice["workspace"]["id"]]
        assert runtime.ai.selection.status()["selections"] == selection
        assert runtime.ai.profile_for("chat").chat_model == "new"
        assert (
            restarted.hosted._runtimes[bob["workspace"]["id"]].ai.profile_for("chat").chat_model
            == "old"
        )


async def test_public_spa_uses_only_builtin_cookie_without_workspace_reads(
    hosted, tmp_path, monkeypatch
):
    from spunto.host.themes import theme_manifest

    client, manager, users, settings = hosted
    root = tmp_path / "built-web"
    root.mkdir()
    (root / "index.html").write_text(
        '<!doctype html><html lang="en"><head></head><body>Spunto public shell</body></html>'
    )
    settings.web_dir = root
    alice, bob, _ = users
    alice_runtime = await manager.services(
        {**alice["workspace"], "role": "owner"}, alice["account"]["id"]
    )
    registry = alice_runtime.host.themes
    registry.values["alice-paper"] = {**theme_manifest("carta"), "id": "alice-paper"}
    registry.cards["alice-paper"] = {"id": "alice-paper", "accents": []}
    for user, theme in ((alice, "alice-paper"), (bob, "salvia")):
        response = await client.put(
            "/api/v1/settings", headers=headers(user), json={"theme": theme}
        )
        assert response.status_code == 200, response.text

    def forbidden_read():
        raise AssertionError("The public shell must never read an unscoped workspace store")

    monkeypatch.setattr(manager.store, "read", forbidden_read)
    for route in ("/", "/n/synthetic-note", "/settings/plugins", "/index.html"):
        for user in (None, alice, bob):
            response = await client.get(route, headers=headers(user) if user else {})
            assert response.status_code == 200, response.text
            assert 'data-theme="carta"' in response.text
            assert "/api/v1/themes/" not in response.text
            assert response.headers["cache-control"] == "no-store"
            assert response.headers["vary"] == "Cookie"
    cookie = quote(
        json.dumps({"theme": "elettrica", "mode": "dark", "reading": "sans", "size": "l"})
    )
    response = await client.get(
        "/shared/n/duplicate?workspace_id=another-space",
        headers={
            "Cookie": "session=" + alice["token"] + "; theme=" + cookie,
            "X-Spunto-Workspace": bob["workspace"]["id"],
        },
    )
    assert response.status_code == 200
    for key, value in {
        "theme": "elettrica",
        "mode": "dark",
        "reading": "sans",
        "size": "l",
    }.items():
        assert f'data-{key}="{value}"' in response.text
    for value in (
        {"theme": "alice-paper", "accent": "extra"},
        {"theme": '"><script>unsafe</script>', "mode": ["dark"]},
        {"theme": ["salvia"], "accent": '" onload=unsafe'},
    ):
        response = await client.get("/", headers={"Cookie": "theme=" + quote(json.dumps(value))})
        assert response.status_code == 200
        assert 'data-theme="carta"' in response.text and "unsafe" not in response.text
        assert "data-accent=" not in response.text and "/api/v1/themes/" not in response.text
    # Operator styles remain authenticated and scoped after client hydration.
    custom = await client.get("/api/v1/themes/alice-paper.css", headers=headers(alice))
    assert custom.status_code == 200 and 'data-theme="alice-paper"' in custom.text
    assert (
        await client.get("/api/v1/themes/alice-paper.css", headers=headers(bob))
    ).status_code == 404


async def test_restart_activates_idle_space_jobs_without_a_login(hosted):
    client, manager, users, settings = hosted
    alice = users[0]
    created = await client.post(
        "/api/v1/notes", headers=headers(alice), json={"body": "synthetic trash"}
    )
    note_id = created.json()["id"]
    runtime = manager._runtimes[alice["workspace"]["id"]]
    await runtime.host.close()
    async with runtime.store.write() as connection:
        await connection.execute("UPDATE note SET trashed_at=1 WHERE id=?", (note_id,))
    await runtime.host.enqueue_core("purge")
    async with hosted_startup(settings) as restarted:
        assert alice["workspace"]["id"] in restarted.hosted._runtimes
        for _ in range(100):
            async with runtime.store.read() as connection:
                remaining = await connection.execute_fetchall(
                    "SELECT id FROM note WHERE id=?", (note_id,)
                )
            if not remaining:
                break
            await asyncio.sleep(0.02)
        assert not remaining


async def test_status_is_scoped_and_viewer_can_revoke_own_device(hosted):
    client, manager, users, _ = hosted
    alice, bob, _ = users
    assert (
        await client.post("/api/v1/notes", headers=headers(alice), json={"body": "one"})
    ).status_code == 201
    status = await client.get("/api/v1/status", headers=headers(alice))
    assert status.status_code == 200, status.text
    assert status.json()["database"]["notes"] == 1
    assert status.json()["database"]["size_kind"] == "note_rows"
    assert status.json()["backup"]["managed"]
    status_token = await client.post(
        "/api/v1/tokens",
        headers=headers(alice),
        json={"name": "synthetic monitor", "scope": "status"},
    )
    bearer = {"Authorization": "Bearer " + status_token.json()["token"]}
    assert (await client.get("/api/v1/status", headers=bearer)).status_code == 200
    assert (await client.get("/api/v1/auth/me", headers=bearer)).status_code == 403
    space = (
        await client.post("/api/v1/workspaces", headers=headers(alice), json={"name": "Shared"})
    ).json()
    assert (
        await client.post(
            f"/api/v1/workspaces/{space['id']}/members",
            headers=headers(alice),
            json={"user_id": bob["account"]["id"], "role": "viewer"},
        )
    ).status_code == 200
    code = (await client.post("/api/v1/auth/pairing", headers=headers(bob, space["id"]))).json()[
        "code"
    ]
    token = (
        await client.post(
            "/api/v1/auth/pairing/exchange", json={"code": code, "name": "synthetic viewer"}
        )
    ).json()["token"]
    device = {"Authorization": "Bearer " + token}
    assert (await client.get("/api/v1/notes", headers=device)).status_code == 200
    logged_out = await client.post("/api/v1/auth/logout", headers={**headers(bob), **device})
    assert logged_out.status_code == 204
    assert "set-cookie" not in logged_out.headers
    assert (await client.get("/api/v1/notes", headers=device)).status_code == 401
    assert (await client.get("/api/v1/auth/session", headers=headers(bob))).status_code == 200


async def test_durable_oidc_pending_attempts_are_bounded(hosted):
    from spunto.auth.accounts import OIDCTransactions
    from spunto.auth.oidc import OIDCError, OIDCTransaction

    _, manager, _, _ = hosted
    states = [uuid.uuid4().hex for _ in range(2)]
    count = await manager.accounts.one(
        "SELECT count(*) AS n FROM public.spunto_oidc_transaction WHERE expires_at>%s",
        (int(time.time() * 1000),),
    )
    transactions = OIDCTransactions(manager.accounts, capacity=count["n"] + 1)
    value = OIDCTransaction(
        "synthetic verifier", "synthetic nonce", "synthetic browser", time.time() + 300
    )
    await transactions.put(states[0], value)
    with pytest.raises(OIDCError, match="Too many pending"):
        await transactions.put(states[1], value)
    stored = await transactions.take(states[0])
    assert stored is not None
    assert stored.verifier == value.verifier and stored.nonce == value.nonce
    assert stored.browser_hash == value.browser_hash
    assert abs(stored.expires_at - value.expires_at) < 0.0011
    assert await transactions.take(states[0]) is None
    assert await transactions.take(states[1]) is None


async def test_hosted_two_accounts_http_and_search_isolation(hosted):
    client, _, users, _ = hosted
    alice, bob, _ = users
    assert (await client.get("/readyz")).status_code == 200
    assert (await client.get("/api/v1/notes")).status_code == 401
    created = await client.post(
        "/api/v1/notes", headers=headers(alice), json={"body": "Alice synthetic secret #alpha"}
    )
    assert created.status_code == 201, created.text
    note = created.json()
    assert (
        await client.get("/api/v1/notes/" + note["id"], headers=headers(bob))
    ).status_code == 404
    listed = await client.get("/api/v1/notes", headers=headers(bob))
    assert listed.status_code == 200 and listed.json()["notes"] == []
    search = await client.get("/api/v1/search?q=synthetic", headers=headers(bob))
    assert search.status_code == 200 and search.json()["hits"] == []
    wrong_space = await client.get("/api/v1/notes", headers=headers(bob, alice["workspace"]["id"]))
    assert wrong_space.status_code == 404
    bad_origin = {**headers(alice), "Origin": "https://attacker.invalid"}
    assert (
        await client.post("/api/v1/notes", headers=bad_origin, json={"body": "Forbidden"})
    ).status_code == 403


async def test_shared_space_roles_and_immediate_membership_revocation(hosted):
    client, _, users, _ = hosted
    alice, bob, _ = users
    created = await client.post(
        "/api/v1/workspaces", headers=headers(alice), json={"name": "Together"}
    )
    assert created.status_code == 201, created.text
    space = created.json()["id"]
    add = await client.post(
        f"/api/v1/workspaces/{space}/members",
        headers=headers(alice),
        json={"email": bob["account"]["email"], "role": "viewer"},
    )
    assert add.status_code == 200, add.text
    note = await client.post(
        "/api/v1/notes", headers=headers(alice, space), json={"body": "Team plan"}
    )
    assert note.status_code == 201, note.text
    assert (await client.get("/api/v1/notes", headers=headers(bob, space))).json()["notes"][0][
        "id"
    ] == note.json()["id"]
    assert (
        await client.post("/api/v1/notes", headers=headers(bob, space), json={"body": "No"})
    ).status_code == 403
    assert (
        await client.post(
            f"/api/v1/workspaces/{space}/members",
            headers=headers(bob, space),
            json={"user_id": alice["account"]["id"], "role": "editor"},
        )
    ).status_code == 403
    membership = f"/api/v1/workspaces/{space}/members/{bob['account']['id']}"
    owner_pat = await client.post(
        "/api/v1/tokens", headers=headers(alice), json={"name": "Owner agent", "scope": "write"}
    )
    assert owner_pat.status_code == 201, owner_pat.text
    bearer = {"Authorization": "Bearer " + owner_pat.json()["token"]}
    denied = await client.patch(membership, headers=bearer, json={"role": "editor"})
    assert denied.status_code == 403 and denied.json()["code"] == "session_required"
    assert (
        await client.patch(membership, headers=headers(bob, space), json={"role": "editor"})
    ).status_code == 403
    assert (
        await client.patch(
            membership,
            headers={**headers(alice), "Origin": "https://attacker.invalid"},
            json={"role": "editor"},
        )
    ).status_code == 403
    assert (
        await client.patch(
            f"/api/v1/workspaces/{space}/members/{alice['account']['id']}",
            headers=headers(alice),
            json={"role": "viewer"},
        )
    ).status_code == 409
    changed = await client.patch(
        membership,
        headers=headers(alice),
        json={"role": "editor"},
    )
    assert changed.status_code == 204, changed.text
    assert (
        await client.post(
            "/api/v1/notes", headers=headers(bob, space), json={"body": "Editor note"}
        )
    ).status_code == 201
    assert (await client.delete(membership, headers=headers(alice))).status_code == 204
    assert (
        await client.patch(membership, headers=headers(alice), json={"role": "viewer"})
    ).status_code == 404
    assert (await client.get("/api/v1/notes", headers=headers(bob, space))).status_code == 404


async def test_individual_share_does_not_grant_library_or_settings_access(hosted):
    client, _, users, _ = hosted
    alice, bob, carol = users
    a = (
        await client.post("/api/v1/notes", headers=headers(alice), json={"body": "Shared note"})
    ).json()
    b = (
        await client.post(
            "/api/v1/notes", headers=headers(alice), json={"body": "Other private note"}
        )
    ).json()
    url = "/api/v1/notes/" + a["id"] + "/shares"
    grant = await client.post(
        url, headers=headers(alice), json={"user_id": bob["account"]["id"], "role": "viewer"}
    )
    assert grant.status_code == 200, grant.text
    shared = await client.get("/api/v1/shared-notes", headers=headers(bob))
    assert shared.status_code == 200, shared.text
    assert [n["id"] for n in shared.json()["notes"]] == [a["id"]]
    assert (await client.get("/api/v1/notes/" + a["id"], headers=headers(bob))).status_code == 404
    adoption = await client.post(
        f"/api/v1/notes/{a['id']}/adopt", headers=headers(bob), json={"base_version": a["version"]}
    )
    assert adoption.status_code == 404
    assert (
        await client.get("/api/v1/shared-notes/" + b["id"], headers=headers(bob))
    ).status_code == 404
    assert (
        await client.get("/api/v1/shared-notes/" + a["id"], headers=headers(carol))
    ).status_code == 404
    edit = await client.patch(
        "/api/v1/shared-notes/" + a["id"],
        headers=headers(bob),
        json={"body": "Forbidden", "version": a["version"]},
    )
    assert edit.status_code == 403, edit.text
    recipient_grant = url + "/" + bob["account"]["id"]
    owner_pat = await client.post(
        "/api/v1/tokens", headers=headers(alice), json={"name": "Owner agent", "scope": "write"}
    )
    assert owner_pat.status_code == 201, owner_pat.text
    bearer = {"Authorization": "Bearer " + owner_pat.json()["token"]}
    denied = await client.patch(recipient_grant, headers=bearer, json={"role": "editor"})
    assert denied.status_code == 403 and denied.json()["code"] == "session_required"
    assert (
        await client.patch(recipient_grant, headers=headers(bob), json={"role": "editor"})
    ).status_code == 404
    assert (
        await client.patch(
            recipient_grant,
            headers={**headers(alice), "Origin": "https://attacker.invalid"},
            json={"role": "editor"},
        )
    ).status_code == 403
    changed = await client.patch(recipient_grant, headers=headers(alice), json={"role": "editor"})
    assert changed.status_code == 204, changed.text
    edit = await client.patch(
        "/api/v1/shared-notes/" + a["id"],
        headers=headers(bob),
        json={"body": "Bob edit", "version": a["version"]},
    )
    assert edit.status_code == 200, edit.text
    assert (await client.get("/api/v1/notes/" + a["id"], headers=headers(alice))).json()[
        "body"
    ] == "Bob edit"
    assert (await client.delete(recipient_grant, headers=headers(alice))).status_code == 204
    assert (
        await client.patch(recipient_grant, headers=headers(alice), json={"role": "viewer"})
    ).status_code == 404
    assert (
        await client.get("/api/v1/shared-notes/" + a["id"], headers=headers(bob))
    ).status_code == 404


async def test_session_identity_oidc_and_logout_replay(hosted):
    client, _, users, settings = hosted
    alice, bob, _ = users
    one = (await client.get("/api/v1/auth/session", headers=headers(alice))).json()
    two = (await client.get("/api/v1/auth/session", headers=headers(bob))).json()
    assert one["user_id"] != two["user_id"] and one["workspace_id"] != two["workspace_id"]
    assert one["auth_method"] == "oidc"
    options = await client.get("/api/v1/auth/options")
    assert options.json()["mode"] == "hosted"
    assert options.json()["password_enabled"] is True
    assert options.json()["oidc_enabled"] is True
    assert (
        await client.post(
            "/api/v1/auth/login",
            headers={"Origin": ORIGIN},
            json={"username": "unknown-fixture", "password": "fixture"},
        )
    ).status_code == 401
    assert (await client.post("/api/v1/auth/logout", headers=headers(alice))).status_code == 204
    assert (await client.get("/api/v1/auth/session", headers=headers(alice))).status_code == 401
    assert sessions.cookie_name(settings) == "session"


async def test_browser_get_without_origin_and_stable_space_session(hosted):
    client, _, users, _ = hosted
    alice = users[0]
    cookie = {"Cookie": "session=" + alice["token"]}
    assert (await client.get("/api/v1/notes", headers=cookie)).status_code == 200
    original = (await client.get("/api/v1/auth/session", headers=cookie)).json()
    space = (
        await client.post("/api/v1/workspaces", headers=headers(alice), json={"name": "Second"})
    ).json()["id"]
    changed = (
        await client.get("/api/v1/auth/session", headers={**cookie, "X-Spunto-Workspace": space})
    ).json()
    assert changed["workspace_id"] == space
    assert changed["credential_id"] == original["credential_id"]
    assert changed["workspace_id"] != original["workspace_id"]


async def test_read_and_status_pats_cannot_enter_account_sharing_controlplane(hosted):
    client, _, users, _ = hosted
    alice, bob, _ = users
    note = (
        await client.post("/api/v1/notes", headers=headers(alice), json={"body": "Granted only"})
    ).json()
    await client.post(
        f"/api/v1/notes/{note['id']}/shares",
        headers=headers(alice),
        json={"user_id": bob["account"]["id"], "role": "editor"},
    )
    for scope in ("status", "read", "write"):
        created = await client.post(
            "/api/v1/tokens", headers=headers(bob), json={"name": "Synthetic token", "scope": scope}
        )
        assert created.status_code == 201, created.text
        bearer = {"Authorization": "Bearer " + created.json()["token"]}
        for path in (
            "/api/v1/workspaces",
            "/api/v1/shared-notes",
            "/api/v1/shared-notes/" + note["id"],
        ):
            assert (await client.get(path, headers=bearer)).status_code == 403
        assert (
            await client.patch(
                "/api/v1/shared-notes/" + note["id"],
                headers=bearer,
                json={"body": "No", "version": note["version"]},
            )
        ).status_code == 403


async def _sync_phone(client, user):
    grant = await client.post("/api/v1/auth/pairing", headers=headers(user))
    assert grant.status_code == 200, grant.text
    paired = await client.post(
        "/api/v1/auth/pairing/exchange",
        json={"code": grant.json()["code"], "name": "Synthetic phone"},
    )
    assert paired.status_code == 200, paired.text
    bearer = {"Authorization": "Bearer " + paired.json()["token"]}
    meta = await client.get("/api/v1/meta", headers=bearer)
    assert meta.status_code == 200, meta.text
    return bearer, meta.json()["sync_epoch"]


def _sync_op(kind, note_id, **fields):
    return {"op_id": str(uuid.uuid4()), "type": kind, "note_id": note_id, **fields}


async def _sync_push(client, device, *ops):
    bearer, epoch = device
    response = await client.post(
        "/api/v1/sync/push", headers=bearer, json={"epoch": epoch, "ops": list(ops)}
    )
    assert response.status_code == 200, response.text
    return response.json()["results"]


async def _sync_pull(client, device):
    response = await client.get("/api/v1/sync/pull", headers=device[0])
    assert response.status_code == 200, response.text
    return response.json()["items"]


async def _assert_phone_conflict_receipts_tombstones_and_tag_removal(hosted):
    client, _, (alice, _, _), _ = hosted
    first, second = await _sync_phone(client, alice), await _sync_phone(client, alice)
    disputed, kept, gone = (str(uuid.uuid7()) for _ in range(3))
    assert [
        result["status"]
        for result in await _sync_push(
            client,
            first,
            _sync_op("note.create", disputed, body="one\ntwo"),
            _sync_op("note.create", kept, body="Kept #garden"),
            _sync_op("note.create", gone, body="Disposable"),
        )
    ] == ["applied"] * 3
    assert (
        await _sync_push(
            client,
            first,
            _sync_op("note.update", disputed, base_version=1, body="SERVER\ntwo"),
        )
    )[0]["status"] == "applied"
    stale = _sync_op("note.update", disputed, base_version=1, body="PHONE\ntwo")
    (conflict,) = await _sync_push(client, second, stale)
    assert conflict["status"] == "conflict"
    (receipt,) = await _sync_push(client, second, stale)
    assert receipt["status"] == "duplicate"
    assert receipt["original"]["status"] == "conflict"
    assert receipt["original"]["conflict_id"] == conflict["conflict_id"]
    bodies = {
        entry["data"]["body"]
        for entry in await _sync_pull(client, first)
        if entry["kind"] == "note"
    }
    assert {"SERVER\ntwo", "PHONE\ntwo"} <= bodies

    (trashed,) = await _sync_push(client, first, _sync_op("note.flags", gone, trashed=True))
    assert trashed["status"] == "applied" and trashed["note"]["trashed"]
    emptied = await client.post("/api/v1/trash/empty", headers=headers(alice))
    assert emptied.status_code == 200 and emptied.json() == {"deleted": 1}, emptied.text
    items = await _sync_pull(client, first)
    assert [
        (entry["data"]["kind"], entry["data"]["id"])
        for entry in items
        if entry["kind"] == "tombstone"
    ] == [("note", gone)]
    assert all(entry["kind"] != "note" or entry["data"]["id"] != gone for entry in items)

    (restored,) = await _sync_push(client, first, _sync_op("note.restore", gone, body="Disposable"))
    assert restored["status"] == "rejected" and restored["code"] == "not_found"
    (recreated,) = await _sync_push(client, first, _sync_op("note.create", gone, body="Disposable"))
    assert recreated["status"] == "rejected"
    removed = await client.post(
        "/api/v1/labels/remove", headers=headers(alice), json={"path": "garden"}
    )
    assert removed.status_code == 200 and removed.json() == {"rewritten": 1}, removed.text
    items = await _sync_pull(client, first)
    notes = {
        item["data"]["id"]: item["data"]["body"].strip() for item in items if item["kind"] == "note"
    }
    assert notes[kept] == "Kept"
    assert gone not in notes
    labels = await client.get("/api/v1/labels", headers=headers(alice))
    assert labels.status_code == 200 and labels.json()["labels"] == [], labels.text


async def test_pairing_metadata_sync_and_viewer_downgrade(hosted):
    client, _, users, _ = hosted
    alice, bob, _ = users
    space = (
        await client.post(
            "/api/v1/workspaces", headers=headers(alice), json={"name": "Device space"}
        )
    ).json()["id"]
    await client.post(
        f"/api/v1/workspaces/{space}/members",
        headers=headers(alice),
        json={"user_id": bob["account"]["id"], "role": "editor"},
    )
    grant = await client.post("/api/v1/auth/pairing", headers=headers(bob, space))
    assert grant.status_code == 200, grant.text
    paired = await client.post(
        "/api/v1/auth/pairing/exchange",
        json={"code": grant.json()["code"], "name": "Synthetic Android"},
    )
    assert paired.status_code == 200, paired.text
    device = paired.json()
    assert device["user_id"] == bob["account"]["id"] and device["workspace_id"] == space
    bearer = {"Authorization": "Bearer " + device["token"]}
    metadata = await client.get("/api/v1/meta", headers=bearer)
    assert metadata.status_code == 200, metadata.text
    assert metadata.json()["sync_epoch"] != "sign-in-required"
    assert metadata.json()["workspace_role"] == "editor"
    assert "workspace_role" not in (await client.get("/api/v1/meta")).json()
    identity = await client.get("/api/v1/auth/me", headers=bearer)
    assert identity.status_code == 200, identity.text
    assert identity.json()["account"]["id"] == bob["account"]["id"]
    assert identity.json()["account"]["display_name"] == "Bob"
    assert identity.json()["account"]["id"] != alice["account"]["id"]
    pull = await client.get("/api/v1/sync/pull", headers=bearer)
    assert pull.status_code == 200, pull.text
    assert pull.json()["epoch"] == metadata.json()["sync_epoch"]
    await client.post(
        f"/api/v1/workspaces/{space}/members",
        headers=headers(alice),
        json={"user_id": bob["account"]["id"], "role": "viewer"},
    )
    viewer_identity = await client.get("/api/v1/auth/me", headers=bearer)
    assert viewer_identity.status_code == 200
    assert viewer_identity.json()["account"]["id"] == bob["account"]["id"]
    viewer_metadata = await client.get("/api/v1/meta", headers=bearer)
    assert viewer_metadata.status_code == 200, viewer_metadata.text
    assert viewer_metadata.json()["workspace_role"] == "viewer"
    viewer_wait = await client.get(
        "/api/v1/changes/wait",
        headers=bearer,
        params={
            "timeout": 0,
            "cursor": pull.json()["cursor"],
            "plugins_rev": pull.json()["plugins_rev"],
        },
    )
    assert viewer_wait.status_code == 200, viewer_wait.text
    assert viewer_wait.json()["workspace_role"] == "viewer"
    browser_wait = await client.get(
        "/api/v1/changes/wait", headers=headers(bob, space), params={"timeout": 0}
    )
    assert browser_wait.status_code == 200 and "workspace_role" not in browser_wait.json()
    assert (await client.get("/api/v1/notes", headers=bearer)).status_code == 200
    assert (await client.get("/api/v1/sync/pull", headers=bearer)).status_code == 200
    viewer_sync = {"epoch": metadata.json()["sync_epoch"]}
    reconciled = await client.post(
        "/api/v1/sync/reconcile", headers=bearer, json={**viewer_sync, "notes": []}
    )
    assert reconciled.status_code == 200, reconciled.text
    assert reconciled.json()["restore_ids"] == []
    assert reconciled.json()["workspace_role"] == "viewer"
    for extra, code in (
        ({"Origin": "https://other.example"}, "origin_mismatch"),
        ({"X-Spunto-Workspace": "other-space"}, "workspace_mismatch"),
    ):
        denied = await client.post(
            "/api/v1/sync/reconcile", headers={**bearer, **extra}, json={**viewer_sync, "notes": []}
        )
        assert denied.status_code == 403 and denied.json()["code"] == code
    browser = await client.post(
        "/api/v1/sync/reconcile", headers=headers(bob, space), json={**viewer_sync, "notes": []}
    )
    assert browser.status_code == 403
    blocked = await client.post(
        "/api/v1/sync/push", headers=bearer, json={**viewer_sync, "ops": []}
    )
    assert blocked.status_code == 403 and blocked.json()["code"] == "read_only_space"
    epoch = metadata.json()["sync_epoch"]
    # Hosted cursors start at a database-wide sequence, so pin the change relative
    # to the preceding pull while retaining the response's actual epoch.
    _, baseline_seq = pull.json()["cursor"].rsplit(".", 1)
    wait_epoch, wait_seq = viewer_wait.json()["cursor"].rsplit(".", 1)
    hosted_contract = {
        "editor_meta": {**metadata.json(), "sync_epoch": "e"},
        "viewer_meta": {
            **viewer_metadata.json(),
            "sync_epoch": viewer_metadata.json()["sync_epoch"].replace(epoch, "e", 1),
        },
        "viewer_wait": {
            **viewer_wait.json(),
            "cursor": f"{wait_epoch.replace(epoch, 'e', 1)}.{int(wait_seq) - int(baseline_seq)}",
        },
        "viewer_reconcile": {
            **reconciled.json(),
            "epoch": reconciled.json()["epoch"].replace(epoch, "e", 1),
        },
        "read_only_push": blocked.json(),
    }
    # Capture the fields consumed by the Android sync adapter. Other metadata
    # includes version and deployment fields that are outside this contract.
    fields = {
        "editor_meta": ("sync_epoch", "workspace_role"),
        "viewer_meta": ("sync_epoch", "workspace_role"),
        "viewer_wait": ("cursor", "changed", "workspace_role"),
        "viewer_reconcile": ("epoch", "restore_ids", "workspace_role"),
        "read_only_push": ("code",),
    }
    actual = {
        name: {field: payload[field] for field in fields[name]}
        for name, payload in hosted_contract.items()
    }
    target = Path(__file__).parents[2] / "spec" / "fixtures" / "sync-api-hosted.json"
    if os.environ.get("UPDATE_SPUNTO_SYNC_FIXTURES") == "1":
        target.write_text(json.dumps(actual, ensure_ascii=False, indent=2) + "\n")
    assert actual == json.loads(target.read_text())
    await _assert_phone_conflict_receipts_tombstones_and_tag_removal(hosted)
    assert (
        await client.post("/api/v1/notes", headers=bearer, json={"body": "No"})
    ).status_code == 403
    replay = await client.post(
        "/api/v1/auth/pairing/exchange", json={"code": grant.json()["code"], "name": "Replay"}
    )
    assert replay.status_code == 401


async def test_browser_credential_revocation_and_logout_expire_pairing(hosted):
    client, _, users, _ = hosted
    alice, bob, _ = users
    assert (await client.get("/api/v1/credentials", headers=headers(alice))).status_code == 200
    grant = (await client.post("/api/v1/auth/pairing", headers=headers(alice))).json()
    credentials = (await client.get("/api/v1/credentials", headers=headers(alice))).json()
    own_session = next(row["id"] for row in credentials if row["kind"] == "session")
    assert (
        await client.delete("/api/v1/credentials/" + own_session, headers=headers(alice))
    ).status_code == 204
    assert (await client.get("/api/v1/notes", headers=headers(alice))).status_code == 401
    assert (
        await client.post(
            "/api/v1/auth/pairing/exchange", json={"code": grant["code"], "name": "No"}
        )
    ).status_code == 401
    grant = (await client.post("/api/v1/auth/pairing", headers=headers(bob))).json()
    assert (await client.post("/api/v1/auth/logout", headers=headers(bob))).status_code == 204
    assert (
        await client.post(
            "/api/v1/auth/pairing/exchange", json={"code": grant["code"], "name": "No"}
        )
    ).status_code == 401


async def test_shared_conflict_copy_and_grant_commit_atomically(hosted, monkeypatch):
    from spunto.store.postgres import Connection

    client, _, users, _ = hosted
    alice, bob, _ = users
    note = (
        await client.post("/api/v1/notes", headers=headers(alice), json={"body": "Base"})
    ).json()
    await client.post(
        f"/api/v1/notes/{note['id']}/shares",
        headers=headers(alice),
        json={"user_id": bob["account"]["id"], "role": "editor"},
    )
    await client.patch(
        "/api/v1/notes/" + note["id"],
        headers={**headers(alice), "If-Match": '"1"'},
        json={"body": "Alice changed"},
    )
    original = Connection.execute

    async def fail_grant(self, statement, parameters=()):
        if "grant_conflict_copy" in statement:
            raise RuntimeError("Synthetic grant failure")
        return await original(self, statement, parameters)

    with monkeypatch.context() as patch:
        patch.setattr(Connection, "execute", fail_grant)
        with pytest.raises(RuntimeError, match="Synthetic grant failure"):
            await client.patch(
                "/api/v1/shared-notes/" + note["id"],
                headers=headers(bob),
                json={"body": "Bob changed", "version": 1},
            )
    owner_notes = (await client.get("/api/v1/notes", headers=headers(alice))).json()["notes"]
    assert len(owner_notes) == 1 and owner_notes[0]["body"] == "Alice changed"
    edited = await client.patch(
        "/api/v1/shared-notes/" + note["id"],
        headers=headers(bob),
        json={"body": "Bob changed", "version": 1},
    )
    assert edited.status_code == 409, edited.text
    conflict = await client.get(
        "/api/v1/shared-notes/" + edited.json()["conflict_id"], headers=headers(bob)
    )
    assert conflict.status_code == 200, conflict.text
    assert conflict.json()["body"] == "Bob changed"


async def test_export_is_scoped_and_does_not_use_legacy_sqlite(hosted):
    import io
    import zipfile

    client, _, users, _ = hosted
    alice, bob, _ = users
    await client.post(
        "/api/v1/notes", headers=headers(alice), json={"body": "Alice export sentinel"}
    )
    await client.post("/api/v1/notes", headers=headers(bob), json={"body": "Bob export sentinel"})
    exported = await client.get("/api/v1/export", headers=headers(bob))
    assert exported.status_code == 200, exported.text
    with zipfile.ZipFile(io.BytesIO(exported.content)) as archive:
        text = "\n".join(archive.read(name).decode() for name in archive.namelist())
    assert "Bob export sentinel" in text and "Alice export sentinel" not in text


async def test_shared_metadata_and_attachment_link_privacy(hosted):
    import json

    client, manager, users, _ = hosted
    alice, bob, _ = users
    upload = await client.post(
        "/api/v1/attachments",
        headers=headers(alice),
        files={"file": ("private.html", b"synthetic html file", "text/html")},
    )
    assert upload.status_code == 200, upload.text
    attachment = upload.json()
    one = (
        await client.post(
            "/api/v1/notes",
            headers=headers(alice),
            json={"body": f"Shared attachment [file](/a/{attachment['id']})"},
        )
    ).json()
    two = (
        await client.post(
            "/api/v1/notes", headers=headers(alice), json={"body": "Other shared text"}
        )
    ).json()
    for note in (one, two):
        await client.post(
            f"/api/v1/notes/{note['id']}/shares",
            headers=headers(alice),
            json={"user_id": bob["account"]["id"], "role": "editor"},
        )
    owner = manager.store.for_workspace(alice["workspace"]["id"], alice["account"]["id"], "owner")
    async with owner.write() as conn:
        await conn.execute("UPDATE attachment SET mime='text/html' WHERE id=?", (attachment["id"],))
        await conn.execute("UPDATE plugin SET state='enabled',switch='on' WHERE id='related'")
        await conn.execute(
            "INSERT INTO annotation(plugin_id,note_id,kind,payload,change_seq,updated_at) "
            "VALUES ('related',?,'panel',?,1,1)",
            (
                one["id"],
                json.dumps({"type": "text", "text": "Unrelated private annotation sentinel"}),
            ),
        )
    shared = await client.get("/api/v1/shared-notes/" + one["id"], headers=headers(bob))
    assert shared.status_code == 200, shared.text
    assert shared.json()["annotations"] == [] and shared.json()["suggestions"] == []
    assert "Unrelated private annotation sentinel" not in shared.text
    assert "?workspace_id=" + alice["workspace"]["id"] in shared.json()["html"]
    download = await client.get(
        f"/api/v1/shared-notes/{one['id']}/attachments/{attachment['id']}", headers=headers(bob)
    )
    assert download.status_code == 200
    assert download.headers["content-type"] == "application/octet-stream"
    assert download.headers["content-disposition"].startswith("attachment")
    await client.delete(
        f"/api/v1/notes/{one['id']}/shares/{bob['account']['id']}", headers=headers(alice)
    )
    injected = await client.patch(
        "/api/v1/shared-notes/" + two["id"],
        headers=headers(bob),
        json={"body": f"Injected [file](/a/{attachment['id']})", "version": 1},
    )
    assert injected.status_code == 403, injected.text
    assert injected.json()["code"] == "attachment_not_shared"
    assert (
        await client.get(
            f"/api/v1/shared-notes/{two['id']}/attachments/{attachment['id']}", headers=headers(bob)
        )
    ).status_code == 404


async def test_duplicate_note_ids_keep_shared_grants_separate(hosted):
    client, _, users, _ = hosted
    alice, bob, carol = users
    note_id = str(uuid.uuid7())
    for owner, body in ((alice, "Alice copy"), (carol, "Carol copy")):
        response = await client.post(
            "/api/v1/notes", headers=headers(owner), json={"id": note_id, "body": body}
        )
        assert response.status_code == 201, response.text
        await client.post(
            f"/api/v1/notes/{note_id}/shares",
            headers=headers(owner),
            json={"user_id": bob["account"]["id"], "role": "editor"},
        )
    assert (
        await client.get("/api/v1/shared-notes/" + note_id, headers=headers(bob))
    ).status_code == 404
    for owner, expected in ((alice, "Alice copy"), (carol, "Carol copy")):
        url = f"/api/v1/shared-notes/{note_id}?workspace_id={owner['workspace']['id']}"
        response = await client.get(url, headers=headers(bob))
        assert response.status_code == 200 and response.json()["body"] == expected
    url = f"/api/v1/shared-notes/{note_id}?workspace_id={alice['workspace']['id']}"
    assert (
        await client.patch(
            url, headers=headers(bob), json={"body": "Only Alice copy edited", "version": 1}
        )
    ).status_code == 200
    carol_note = (await client.get("/api/v1/notes/" + note_id, headers=headers(carol))).json()
    assert carol_note["body"] == "Carol copy"


async def test_hosted_mcp_keeps_token_and_workspace_authority(hosted):
    client, _, users, _ = hosted
    alice, bob, _ = users
    note = (
        await client.post(
            "/api/v1/notes", headers=headers(alice), json={"body": "Alice MCP private sentinel"}
        )
    ).json()
    created = await client.post(
        "/api/v1/tokens", headers=headers(bob), json={"name": "Bob MCP", "scope": "read"}
    )
    token = created.json()["token"]
    response = await client.post(
        "/mcp",
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/json, text/event-stream",
            "MCP-Protocol-Version": "2025-06-18",
        },
        json={
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {"name": "get_notes", "arguments": {"ids": [note["id"]]}},
        },
    )
    assert response.status_code == 200, response.text
    assert "Alice MCP private sentinel" not in response.text
    assert (await client.post("/mcp", headers=headers(alice), json={})).status_code == 401


async def test_ai_allowance_is_per_account_across_spaces_and_pauses_with_a_reason(
    hosted, monkeypatch
):
    from datetime import UTC, datetime

    from ai_fake import FakeProvider

    from spunto.ai.errors import AIError
    from spunto.ai.gateway import Gateway, Probe
    from spunto.ai.profiles import Profile
    from spunto.ai.quota import PLANS, AccountQuota, QuotaExceeded

    client, manager, users, settings = hosted
    alice, bob, carol = users
    fake = FakeProvider()

    async def models(*args, **kwargs):
        return []

    async def health(self):
        return []

    monkeypatch.setattr("spunto.ai.selection.inventory", models)
    monkeypatch.setattr(Gateway, "health_check", health)
    settings.ai_profiles = {
        "fixture": Profile(
            name="fixture",
            base_url="http://provider.synthetic/v1",
            chat_model="chat-fixture",
            rate_per_min=1000,
        )
    }
    settings.ai_chat_profile = "fixture"
    settings.ai_quota_limits = {"ask": 2, "labels": 5}
    second = (
        await client.post(
            "/api/v1/workspaces", headers=headers(alice), json={"name": "Second synthetic space"}
        )
    ).json()
    spaces = [alice["workspace"]["id"], second["id"]]
    gateways = []
    for user, space in ((alice, spaces[0]), (alice, spaces[1]), (bob, bob["workspace"]["id"])):
        assert (await client.get("/api/v1/ai", headers=headers(user, space))).status_code == 200
        gateways.append(manager._runtimes[space].ai)
        gateways[-1].transport = httpx.ASGITransport(fake.app)
    first, other, bobs = gateways

    # A second space draws from the same monthly allowance as the first.
    await first.chat(Probe, "check", plugin_id="ask")
    await other.chat(Probe, "check", plugin_id="ask")
    for gateway in (first, other):
        with pytest.raises(QuotaExceeded) as refused:
            await gateway.chat(Probe, "check", plugin_id="ask")
    assert len(fake.requests) == 2, "a refused call must never reach the provider"
    assert 0 < refused.value.retry_after <= 3600
    assert refused.value.detail.startswith("Ask has used this month's allowance (2)")
    assert refused.value.detail.endswith("Notes and search keep working.")
    # Another account and another action keep their own allowances.
    await bobs.chat(Probe, "check", plugin_id="ask")
    await first.chat(Probe, "check", plugin_id="tidy")

    # Concurrent calls from both spaces cannot overshoot the shared allowance.
    results = await asyncio.gather(
        *(
            gateway.chat(Probe, "check", plugin_id="label-suggestions")
            for gateway in [first, other] * 6
        ),
        return_exceptions=True,
    )
    assert sum(not isinstance(result, BaseException) for result in results) == 5
    assert all(isinstance(r, QuotaExceeded) for r in results if isinstance(r, BaseException))

    # A call that never reached a provider gives its allowance back.
    with pytest.raises(AIError, match="ai_note_refused"):
        await first.chat(Probe, "check", notes=["missing-note"], plugin_id="tidy")

    # Owners see where the month stands; tokens and refusals are recorded per space.
    status = (await client.get("/api/v1/ai", headers=headers(alice, spaces[1]))).json()
    usage = {row["action"]: (row["used"], row["limit"]) for row in status["quota"]["actions"]}
    assert usage == {
        "labels": (5, 5),
        "ask": (2, 2),
        "chat": (1, 50),
        "vision": (0, 10),
        "transcribe": (0, 10),
        "embed": (0, None),
    }
    assert status["quota"]["resets_at"].endswith("-01T00:00:00Z")
    stats = {(row["plugin_id"], row["outcome"]): row for row in status["stats"]}
    assert (stats[("ask", "ok")]["n"], stats[("ask", "ok")]["input_tokens"]) == (1, 120)
    assert stats[("ask", "ok")]["output_tokens"] == 8
    assert stats[("ask", "quota")]["n"] == 1

    # Notes and search keep working; an interactive AI action pauses with the reason.
    created = await client.post(
        "/api/v1/notes",
        headers=headers(alice),
        json={"body": "Synthetic allowance note about tomato seedlings"},
    )
    assert created.status_code == 201, created.text
    note_id = created.json()["id"]
    found = await client.get("/api/v1/search?q=seedlings&mode=keyword", headers=headers(alice))
    assert [hit["id"] for hit in found.json()["hits"]] == [note_id]
    suggested = await client.post(
        "/api/v1/plugins/label-suggestions/commands/suggest-now",
        headers=headers(alice),
        json={"note_id": note_id},
    )
    assert suggested.status_code == 429, suggested.text
    assert suggested.json()["code"] == "ai_quota_exhausted"
    assert suggested.json()["detail"].startswith("Auto-labelling has used this month's allowance")
    assert 0 < int(suggested.headers["retry-after"]) <= 3600

    # The background run after the save waits for the allowance instead of failing.
    runtime = manager._runtimes[spaces[0]]
    async with runtime.store.write() as connection:
        await connection.execute(
            "UPDATE job SET run_after=0 WHERE plugin_id='label-suggestions' AND note_id=?",
            (note_id,),
        )
    runtime.host._wake.set()
    for _ in range(100):
        async with runtime.store.read() as connection:
            jobs = await connection.execute_fetchall(
                "SELECT status,last_error,run_after FROM job "
                "WHERE plugin_id='label-suggestions' AND note_id=?",
                (note_id,),
            )
        if jobs and jobs[0]["last_error"]:
            break
        await asyncio.sleep(0.05)
    assert (jobs[0]["status"], jobs[0]["last_error"]) == ("queued", "ai_quota_exhausted")
    assert jobs[0]["run_after"] > time.time() * 1000

    # Allowances renew with the UTC month; a zero limit refuses without counting.
    clock = [datetime(2026, 10, 31, 23, 59, tzinfo=UTC)]
    month = AccountQuota(
        manager.accounts,
        carol["account"]["id"],
        {**PLANS["free"], "ask": 1, "vision": 0},
        clock=lambda: clock[0],
    )
    await month.reserve("ask")
    for name in ("ask", "vision"):
        with pytest.raises(QuotaExceeded) as refused:
            await month.reserve(name)
    assert refused.value.retry_after == 60
    assert "resumes on 1 November" in refused.value.detail
    clock[0] = datetime(2026, 11, 1, tzinfo=UTC)
    assert await month.reserve("ask") == ("ask", "2026-11")


async def test_ai_account_allowance_is_visible_only_to_its_owner(hosted):
    client, manager, users, _ = hosted
    alice, bob, carol = users
    personal = await client.get("/api/v1/ai", headers=headers(alice))
    assert personal.status_code == 200
    await manager._runtimes[alice["workspace"]["id"]].ai.quota.reserve("ask")
    shared = await client.post(
        "/api/v1/workspaces", headers=headers(alice), json={"name": "Synthetic shared AI space"}
    )
    assert shared.status_code == 201
    workspace = shared.json()["id"]
    for user, role in ((bob, "editor"), (carol, "viewer")):
        member = await client.post(
            f"/api/v1/workspaces/{workspace}/members",
            headers=headers(alice),
            json={"user_id": user["account"]["id"], "role": role},
        )
        assert member.status_code == 200
        response = await client.get("/api/v1/ai", headers=headers(user, workspace))
        assert response.status_code == 200
        assert response.json()["can_manage"] is False
        assert response.json()["quota"] is None
    for space in (alice["workspace"]["id"], workspace):
        response = await client.get("/api/v1/ai", headers=headers(alice, space))
        assert response.status_code == 200
        status = response.json()
        assert status["can_manage"] is True
        assert (
            next(row["used"] for row in status["quota"]["actions"] if row["action"] == "ask") == 1
        )
    own_account = await client.get("/api/v1/ai", headers=headers(bob))
    assert own_account.status_code == 200
    assert all(row["used"] == 0 for row in own_account.json()["quota"]["actions"])


async def test_storage_quota_rejected_uploads_leave_no_final_or_staged_bytes(hosted):
    from pathlib import Path

    client, manager, users, settings = hosted
    alice, _, _ = users
    await manager.accounts.one(
        "INSERT INTO public.spunto_account_policy VALUES (%s,%s::jsonb,%s) RETURNING user_id",
        (alice["account"]["id"], json.dumps({"storage_bytes": 0, "file_bytes": 100}), 0),
    )
    for data in (b"first synthetic rejected blob", b"second synthetic rejected blob"):
        response = await client.post(
            "/api/v1/attachments",
            headers=headers(alice),
            files={"file": ("synthetic.bin", data, "application/octet-stream")},
        )
        assert response.status_code == 429, response.text
        assert response.json()["code"] == "account_quota_exhausted"
    directory = Path(settings.attachments_dir) / alice["workspace"]["id"]
    assert not [path for path in directory.rglob("*") if path.is_file()]
    store = manager._runtimes[alice["workspace"]["id"]].store
    async with store.read() as conn:
        assert not await conn.execute_fetchall("SELECT 1 FROM attachment")
    usage = await manager.accounts.one(
        "SELECT bytes FROM public.spunto_account_storage WHERE user_id=%s",
        (alice["account"]["id"],),
    )
    assert usage is None or usage["bytes"] == 0
