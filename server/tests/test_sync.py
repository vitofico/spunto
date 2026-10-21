"""Real SQLite conformance scenarios for two devices, retries and restores."""

import hashlib
import json
import uuid
from pathlib import Path

import pytest
from test_suggestions import proposal_host as proposal_host

from spunto.auth import tokens
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes, sequence
from spunto.store.db import now_ms

pytestmark = pytest.mark.anyio
PASSWORD = "synthetic device password"
FIXTURES = Path(__file__).parents[2] / "spec" / "fixtures"
OWNER = Principal("test-owner", "session", "Synthetic owner", "full", private_ok=True)


async def signed(server, name="Synthetic phone"):
    if server.services.setup_code:
        await server.client.post(
            "/api/v1/setup", json={"code": server.services.setup_code, "password": PASSWORD}
        )
    response = await server.client.post(
        "/api/v1/auth/devices", json={"password": PASSWORD, "name": name}
    )
    assert response.status_code == 200
    return {"Authorization": "Bearer " + response.json()["token"]}


async def epoch(server):
    return (await server.client.get("/api/v1/meta")).json()["sync_epoch"]


async def push(server, headers, ops, value=None):
    response = await server.client.post(
        "/api/v1/sync/push",
        headers=headers,
        json={"epoch": value or await epoch(server), "ops": ops},
    )
    assert response.status_code == 200, response.text
    return response.json()["results"]


def op(kind, note_id, **fields):
    return {"op_id": str(uuid.uuid4()), "type": kind, "note_id": note_id, **fields}


async def test_password_devices_names_scope_hashes_and_backoff(server):
    headers = await signed(server)
    credential = await tokens.authenticate(server.services.store, headers["Authorization"][7:])
    assert credential.kind == "device" and credential.private_ok
    assert (await server.client.get("/api/v1/credentials", headers=headers)).status_code == 403
    async with server.services.store.read() as conn:
        row = (
            await conn.execute_fetchall("SELECT * FROM credential WHERE id=?", (credential.id,))
        )[0]
        assert row["name"] == "Synthetic phone" and row["client"] == "android"
        assert row["secret_hash"] not in headers["Authorization"]
    for _ in range(5):
        assert (
            await server.client.post(
                "/api/v1/auth/devices", json={"password": "wrong", "name": "Phone"}
            )
        ).status_code == 401
    assert (
        await server.client.post(
            "/api/v1/auth/devices", json={"password": PASSWORD, "name": "Phone"}
        )
    ).status_code == 429


async def test_qr_session_origin_one_use_expiry_and_revocation(server):
    await signed(server)
    assert (await server.client.post("/api/v1/auth/pairing")).status_code == 403
    origin = {"Origin": server.services.settings.public_url}
    grant = (await server.client.post("/api/v1/auth/pairing", headers=origin)).json()
    async with server.services.store.read() as conn:
        row = (await conn.execute_fetchall("SELECT * FROM pairing"))[0]
        assert (
            row["code_hash"] != grant["code"] and row["expires_at"] - row["created_at"] == 300_000
        )
    body = {"code": grant["code"], "name": "Paired phone"}
    assert (await server.client.post("/api/v1/auth/pairing/exchange", json=body)).status_code == 200
    assert (await server.client.post("/api/v1/auth/pairing/exchange", json=body)).json()[
        "code"
    ] == "invalid_pairing_code"
    grant = (await server.client.post("/api/v1/auth/pairing", headers=origin)).json()
    async with server.services.store.write() as conn:
        await conn.execute("UPDATE pairing SET expires_at=?", (now_ms() - 1,))
    assert (
        await server.client.post(
            "/api/v1/auth/pairing/exchange", json={**body, "code": grant["code"]}
        )
    ).status_code == 401
    grant = (await server.client.post("/api/v1/auth/pairing", headers=origin)).json()
    await server.client.post("/api/v1/auth/logout", headers=origin)
    assert (
        await server.client.post(
            "/api/v1/auth/pairing/exchange", json={**body, "code": grant["code"]}
        )
    ).status_code == 401


async def test_push_order_partial_failures_atomic_retries_and_namespace(server):
    first, second = await signed(server), await signed(server, "Second phone")
    note_id = str(uuid.uuid7())
    create = op("note.create", note_id, body="synthetic #sync", private=True)
    results = await push(
        server,
        first,
        [
            create,
            op("note.update", note_id, base_version=1, body="updated #sync"),
            {"op_id": "bad", "type": "note.create"},
            op("note.flags", note_id, pinned=True, at=now_ms()),
        ],
    )
    assert [r["status"] for r in results] == ["applied", "applied", "rejected", "applied"]
    assert results[-1]["version"] == 3 and results[-1]["note"]["private"]
    duplicate = (await push(server, first, [create]))[0]
    assert duplicate["status"] == "duplicate" and duplicate["original"]["version"] == 1
    assert (await push(server, second, [create]))[0]["code"] == "id_conflict"
    async with server.services.store.read() as conn:
        assert (await conn.execute_fetchall("SELECT count(*) FROM note"))[0][0] == 1
        assert (await conn.execute_fetchall("SELECT count(*) FROM applied_op"))[0][0] == 3
    huge = (
        await push(server, first, [op("note.update", note_id, base_version=3, body="x" * 100001)])
    )[0]
    assert huge["code"] == "too_large"
    response = await server.client.post(
        "/api/v1/sync/push",
        headers=first,
        json={"epoch": await epoch(server), "ops": [create] * 101},
    )
    assert response.status_code == 422


@pytest.mark.parametrize(
    "scenario",
    json.loads((FIXTURES / "sync-scenarios.json").read_text())["scenarios"],
    ids=lambda row: row["name"],
)
async def test_two_offline_devices_shared_scenarios(server, scenario):
    first, second = await signed(server), await signed(server, "Second phone")
    note_id = str(uuid.uuid7())
    await push(server, first, [op("note.create", note_id, body=scenario["base"])])
    await push(server, first, [op("note.update", note_id, base_version=1, body=scenario["server"])])
    update = op("note.update", note_id, base_version=1, body=scenario["incoming"])
    result = (await push(server, second, [update]))[0]
    assert result["status"] == scenario["status"]
    # A retry of a conflicting write must not produce a second copy.
    repeated = (await push(server, second, [update]))[0]
    assert (
        repeated["status"] == "duplicate" and repeated["original"]["status"] == scenario["status"]
    )
    async with server.services.store.read() as conn:
        assert sorted(
            row[0] for row in await conn.execute_fetchall("SELECT body FROM note")
        ) == sorted(scenario["bodies"])


async def test_flags_use_action_time_and_clamp_future_clock(server):
    headers = await signed(server)
    note_id, now = str(uuid.uuid7()), now_ms()
    await push(server, headers, [op("note.create", note_id, body="Flags")])
    await push(server, headers, [op("note.flags", note_id, archived=True, at=now - 1000)])
    newer = (
        await push(server, headers, [op("note.flags", note_id, archived=False, at=now - 500)])
    )[0]
    older = (
        await push(server, headers, [op("note.flags", note_id, archived=True, at=now - 2000)])
    )[0]
    assert not older["note"]["archived"] and older["version"] == newer["version"]
    await push(server, headers, [op("note.flags", note_id, pinned=True, at=now + 1_000_000)])
    async with server.services.store.read() as conn:
        assert (await conn.execute_fetchall("SELECT pinned_clock FROM note"))[0][0] <= now_ms()


async def test_pull_pages_disabled_plugin_rows_private_notes_gzip_and_tombstones(server):
    headers = await signed(server)
    domain = Notes(server.services.store, OWNER)
    first = await domain.create("Private replica", private=True)
    second = await domain.create("Trashed replica")
    await domain.flags(second["id"], {"trashed": True})
    async with server.services.store.write() as conn:
        await conn.execute(
            "INSERT INTO annotation VALUES ('tasks',?,'member:tasks',?,?,?)",
            (first["id"], '{"value":true}', await sequence(conn), now_ms()),
        )
        await conn.execute(
            "INSERT INTO suggestion VALUES (?, 'tasks', ?,1,'append_text',?,NULL,'',"
            "'pending',NULL,?,?,NULL,NULL)",
            (str(uuid.uuid7()), first["id"], '{"text":"Proposed"}', await sequence(conn), now_ms()),
        )
    response = await server.client.get("/api/v1/sync/pull?limit=2", headers=headers)
    assert response.headers["content-encoding"] == "gzip"
    page = response.json()
    assert len(page["items"]) == 2 and page["has_more"]
    following = (
        await server.client.get(
            "/api/v1/sync/pull", params={"cursor": page["cursor"]}, headers=headers
        )
    ).json()
    assert {item["kind"] for item in following["items"]} == {"annotation", "suggestion"}
    assert page["items"][0]["data"]["private"] and page["items"][1]["data"]["trashed"]
    await domain.flags(first["id"], {"trashed": True})
    await domain.delete(first["id"])
    deleted = (
        await server.client.get(
            "/api/v1/sync/pull", params={"cursor": following["cursor"]}, headers=headers
        )
    ).json()
    assert [item["data"]["kind"] for item in deleted["items"]] == [
        "suggestion",
        "annotation",
        "note",
    ]
    assert all(item["kind"] == "tombstone" for item in deleted["items"])
    assert (
        await server.client.get("/api/v1/sync/pull", params={"cursor": "bad"}, headers=headers)
    ).status_code == 422
    assert (
        await server.client.get(
            "/api/v1/sync/pull", params={"cursor": await epoch(server) + ".999999"}, headers=headers
        )
    ).status_code == 422


async def test_final_pull_page_passes_sequence_numbers_without_rows(server):
    headers = await signed(server)
    await Notes(server.services.store, OWNER).create("Synthetic synced note")
    async with server.services.store.write() as conn:  # what a plugin switch does
        await conn.execute("UPDATE meta SET value=CAST(value AS INTEGER)+1 WHERE key='change_seq'")
    page = (await server.client.get("/api/v1/sync/pull", headers=headers)).json()
    wait = (
        await server.client.get(
            "/api/v1/changes/wait",
            params={"cursor": page["cursor"], "plugins_rev": page["plugins_rev"], "timeout": 0},
            headers=headers,
        )
    ).json()
    assert wait["changed"] is False and wait["cursor"] == page["cursor"]
    again = (
        await server.client.get(
            "/api/v1/sync/pull", params={"cursor": page["cursor"]}, headers=headers
        )
    ).json()
    assert again["items"] == [] and again["cursor"] == page["cursor"]


async def test_sync_requires_device_and_epoch_restore_recovers_without_loss(server):
    headers = await signed(server)
    assert (await server.client.get("/api/v1/sync/pull")).status_code == 403
    old_epoch = await epoch(server)
    note_id = str(uuid.uuid7())
    await push(server, headers, [op("note.create", note_id, body="server restored text")])
    async with server.services.store.write() as conn:
        await conn.execute("UPDATE meta SET value='00000000' WHERE key='sync_epoch'")
    response = await server.client.post(
        "/api/v1/sync/push", headers=headers, json={"epoch": old_epoch, "ops": []}
    )
    assert response.status_code == 409 and response.json()["code"] == "resync_required"
    assert (
        await server.client.get(
            "/api/v1/sync/pull", headers=headers, params={"cursor": old_epoch + ".0"}
        )
    ).status_code == 409
    missing = str(uuid.uuid7())
    response = await server.client.post(
        "/api/v1/sync/reconcile",
        headers=headers,
        json={
            "epoch": "00000000",
            "notes": [
                {
                    "id": value,
                    "body_hash": hashlib.sha256(b"device survivor").hexdigest(),
                    "updated_at": "2099-01-01T00:00:00Z",
                }
                for value in [note_id, missing]
            ],
        },
    )
    assert response.json()["restore_ids"] == [note_id, missing]
    restored = await push(
        server,
        headers,
        [
            op("note.restore", note_id, body="device survivor", base_version=8),
            op("note.restore", missing, body="missing survivor"),
        ],
        "00000000",
    )
    assert [result["status"] for result in restored] == ["conflict", "applied"]
    async with server.services.store.read() as conn:
        assert sorted(row[0] for row in await conn.execute_fetchall("SELECT body FROM note")) == [
            "device survivor",
            "missing survivor",
            "server restored text",
        ]


async def test_sync_wire_golden(server, monkeypatch):
    """The Android DTO suite reads these exact, synthetic server payloads."""
    import os

    from spunto.api import sync
    from spunto.core import notes

    headers = await signed(server)
    moment = 1760000000000
    monkeypatch.setattr(sync, "now_ms", lambda: moment)
    monkeypatch.setattr(notes, "now_ms", lambda: moment)
    async with server.services.store.write() as conn:
        await conn.execute("UPDATE meta SET value='00000000' WHERE key='sync_epoch'")
        await conn.execute("UPDATE meta SET value='0' WHERE key IN ('change_seq','plugins_rev')")
    note_id = "01920000-0000-7000-8000-000000000001"
    operation = {
        "op_id": "00000000-0000-4000-8000-000000000001",
        "type": "note.create",
        "note_id": note_id,
        "body": "Synthetic offline note #fixture",
        "created_at": moment,
    }
    push_body = {"epoch": "00000000", "ops": [operation]}
    pushed = await server.client.post("/api/v1/sync/push", headers=headers, json=push_body)
    pulled = await server.client.get("/api/v1/sync/pull", headers=headers)
    reconcile_body = {
        "epoch": "00000000",
        "notes": [
            {
                "id": note_id,
                "body_hash": hashlib.sha256(operation["body"].encode()).hexdigest(),
                "updated_at": "2025-10-09T08:53:20.000Z",
            }
        ],
    }
    reconciled = await server.client.post(
        "/api/v1/sync/reconcile", headers=headers, json=reconcile_body
    )
    actual = {
        "format": 1,
        "synthetic": True,
        "push_request": push_body,
        "push_response": pushed.json(),
        "pull_response": pulled.json(),
        "reconcile_request": reconcile_body,
        "reconcile_response": reconciled.json(),
    }
    duplicate = await server.client.post("/api/v1/sync/push", headers=headers, json=push_body)
    copy_id = "01920000-0000-7000-8000-000000000002"
    monkeypatch.setattr(notes.uuid, "uuid7", lambda: uuid.UUID(copy_id))
    first_edit = {
        **operation,
        "op_id": "00000000-0000-4000-8000-000000000002",
        "type": "note.update",
        "base_version": 1,
        "body": operation["body"] + "\nServer append",
    }
    await server.client.post(
        "/api/v1/sync/push", headers=headers, json={"epoch": "00000000", "ops": [first_edit]}
    )
    merge_edit = {
        **first_edit,
        "op_id": "00000000-0000-4000-8000-000000000003",
        "body": "Synthetic OFFLINE note #fixture",
    }
    merged = await server.client.post(
        "/api/v1/sync/push", headers=headers, json={"epoch": "00000000", "ops": [merge_edit]}
    )
    conflict_edit = {
        **first_edit,
        "op_id": "00000000-0000-4000-8000-000000000004",
        "base_version": 2,
        "body": "Phone conflicting text",
    }
    conflicted = await server.client.post(
        "/api/v1/sync/push", headers=headers, json={"epoch": "00000000", "ops": [conflict_edit]}
    )
    rejected = await server.client.post(
        "/api/v1/sync/push",
        headers=headers,
        json={
            "epoch": "00000000",
            "ops": [{"op_id": "00000000-0000-4000-8000-000000000005", "type": "unsupported"}],
        },
    )
    async with server.services.store.write() as conn:
        await conn.execute(
            "INSERT INTO annotation VALUES ('tasks',?,'member:tasks',?,?,?)",
            (note_id, '{"value":true}', await sequence(conn), moment),
        )
        await conn.execute(
            "INSERT INTO suggestion VALUES (?, 'tasks', ?,3,'append_text',?,NULL,'',"
            "'pending',NULL,?,?,NULL,NULL)",
            (
                "01920000-0000-7000-8000-000000000003",
                note_id,
                '{"text":"Proposal"}',
                await sequence(conn),
                moment,
            ),
        )
    outputs = await server.client.get(
        "/api/v1/sync/pull", headers=headers, params={"cursor": "00000000.4"}
    )
    domain = Notes(server.services.store, OWNER)
    await domain.flags(note_id, {"trashed": True})
    await domain.delete(note_id)
    tombstones = await server.client.get(
        "/api/v1/sync/pull", headers=headers, params={"cursor": outputs.json()["cursor"]}
    )
    actual.update(
        push_duplicate_response=duplicate.json(),
        push_merged_response=merged.json(),
        push_conflict_response=conflicted.json(),
        push_rejected_response=rejected.json(),
        pull_plugins_response=outputs.json(),
        pull_tombstone_response=tombstones.json(),
    )
    target = FIXTURES / "sync-api.json"
    if os.environ.get("UPDATE_SPUNTO_SYNC_FIXTURES") == "1":
        target.write_text(json.dumps(actual, ensure_ascii=False, indent=2) + "\n")
    assert actual == json.loads(target.read_text())


async def test_operation_failure_rolls_back_domain_receipt_and_change_sequence(server, monkeypatch):
    from spunto.api import sync
    from spunto.api.errors import Problem

    headers = await signed(server)
    failed_id, accepted_id = str(uuid.uuid7()), str(uuid.uuid7())
    original = sync.apply
    async with server.services.store.read() as conn:
        before = int(
            (await conn.execute_fetchall("SELECT value FROM meta WHERE key='change_seq'"))[0][0]
        )

    async def failing_after_write(conn, domain, operation):
        result = await original(conn, domain, operation)
        if operation.note_id == failed_id:
            raise Problem(422, "synthetic_failure", "Synthetic transactional failure.")
        return result

    monkeypatch.setattr(sync, "apply", failing_after_write)
    results = await push(
        server,
        headers,
        [
            op("note.create", failed_id, body="Rolled back"),
            op("note.create", accepted_id, body="Committed"),
        ],
    )
    assert [result["status"] for result in results] == ["rejected", "applied"]
    async with server.services.store.read() as conn:
        assert [row[0] for row in await conn.execute_fetchall("SELECT id FROM note")] == [
            accepted_id
        ]
        assert (await conn.execute_fetchall("SELECT count(*) FROM applied_op"))[0][0] == 1
        assert (await conn.execute_fetchall("SELECT value FROM meta WHERE key='change_seq'"))[0][
            0
        ] == str(before + 1)


async def test_pull_byte_budget_and_changes_during_paging(server):
    headers = await signed(server)
    ids = [str(uuid.uuid7()) for _ in range(7)]
    await push(server, headers, [op("note.create", note_id, body="界" * 100000) for note_id in ids])
    first = (await server.client.get("/api/v1/sync/pull", headers=headers)).json()
    assert len(first["items"]) == 6 and first["has_more"]
    assert len(json.dumps(first, ensure_ascii=False).encode()) < 2_000_000
    updated = (
        await push(
            server,
            headers,
            [op("note.update", ids[0], body="Changed during paging", base_version=1)],
        )
    )[0]
    following = (
        await server.client.get(
            "/api/v1/sync/pull", headers=headers, params={"cursor": first["cursor"]}
        )
    ).json()
    assert [item["data"]["id"] for item in following["items"]] == [ids[-1], ids[0]]
    assert following["items"][-1]["data"]["version"] == updated["version"]


async def test_offline_suggestions_append_and_replace_use_latest_note_and_receipts(
    server, proposal_host
):
    from spunto.core.suggestions import suggest
    from spunto.host.runtime import Ctx

    headers = await signed(server)
    host = proposal_host
    domain = Notes(server.services.store, OWNER)
    note = await domain.create("one\ntwo")
    proposal = await suggest(
        Ctx(host, "proposals"), note["id"], "replace_body", {"body": "one\nTWO"}, note_version=1
    )
    await domain.edit(note["id"], "ONE\ntwo", 1)
    result = (
        await push(
            server,
            headers,
            [
                {
                    "op_id": str(uuid.uuid4()),
                    "type": "suggestion.decide",
                    "suggestion_id": proposal["id"],
                    "decision": "accept",
                }
            ],
        )
    )[0]
    assert result["status"] == "merged" and result["note"]["body"] == "ONE\nTWO"
    append = await suggest(
        Ctx(host, "proposals"),
        note["id"],
        "append_text",
        {"text": "Owner approved"},
        note_version=result["version"],
    )
    decision = {
        "op_id": str(uuid.uuid4()),
        "type": "suggestion.decide",
        "suggestion_id": append["id"],
        "decision": "accept",
    }
    assert (await push(server, headers, [decision]))[0]["note"][
        "body"
    ] == "ONE\nTWO\n\nOwner approved"
    assert (await push(server, headers, [decision]))[0]["status"] == "duplicate"


async def test_restore_reconcile_flag_only_change_and_preserve_newer_server_flags(server):
    from spunto.core.notes import iso

    headers = await signed(server)
    note_id, now = str(uuid.uuid7()), now_ms()
    await push(server, headers, [op("note.create", note_id, body="Unchanged text")])
    local = (
        await push(
            server, headers, [op("note.flags", note_id, archived=True, private=True, at=now)]
        )
    )[0]["note"]
    # A synthetic old-backup state has the same body and older flags/version.
    async with server.services.store.write() as conn:
        await conn.execute(
            "UPDATE note SET version=1,archived_at=NULL,private=0,archived_clock=0,"
            "private_clock=0,updated_at=? WHERE id=?",
            (now - 1000, note_id),
        )
        await conn.execute("DELETE FROM note_revision WHERE note_id=? AND version>1", (note_id,))
        await conn.execute("UPDATE meta SET value='00000000' WHERE key='sync_epoch'")
    manifest = {
        "epoch": "00000000",
        "notes": [
            {"id": note_id, "body_hash": local["body_hash"], "updated_at": local["updated_at"]}
        ],
    }
    assert (
        await server.client.post("/api/v1/sync/reconcile", headers=headers, json=manifest)
    ).json()["restore_ids"] == [note_id]
    restored = (
        await push(
            server,
            headers,
            [
                op(
                    "note.restore",
                    note_id,
                    body=local["body"],
                    base_version=local["version"],
                    archived=True,
                    private=True,
                    archived_at=now,
                    private_at=now,
                )
            ],
            "00000000",
        )
    )[0]["note"]
    assert restored["archived"] and restored["private"]
    assert restored["flag_times"]["archived"] == now
    newer = (
        await push(
            server, headers, [op("note.flags", note_id, archived=False, at=now_ms())], "00000000"
        )
    )[0]["note"]
    old = (
        await push(
            server,
            headers,
            [
                op(
                    "note.restore",
                    note_id,
                    body=local["body"],
                    archived=True,
                    private=True,
                    archived_at=now - 1,
                    private_at=now,
                )
            ],
            "00000000",
        )
    )[0]["note"]
    assert not old["archived"] and old["private"]
    assert old["flag_times"]["archived"] == newer["flag_times"]["archived"]
    manifest["notes"][0]["updated_at"] = iso(now - 2000)
    assert (
        await server.client.post("/api/v1/sync/reconcile", headers=headers, json=manifest)
    ).json()["restore_ids"] == []


async def test_restore_respects_known_purge_but_recovers_when_backup_lacks_tombstone(server):
    headers = await signed(server)
    domain = Notes(server.services.store, OWNER)
    note = await domain.create("Synthetic purge")
    await domain.flags(note["id"], {"trashed": True})
    await domain.delete(note["id"])
    manifest = {
        "epoch": await epoch(server),
        "notes": [
            {"id": note["id"], "body_hash": note["body_hash"], "updated_at": note["updated_at"]}
        ],
    }
    assert (
        await server.client.post("/api/v1/sync/reconcile", headers=headers, json=manifest)
    ).json()["restore_ids"] == []
    restore = op("note.restore", note["id"], body=note["body"])
    assert (await push(server, headers, [restore]))[0]["code"] == "not_found"
    async with server.services.store.write() as conn:
        await conn.execute("DELETE FROM tombstone WHERE kind='note' AND id=?", (note["id"],))
    assert (
        await server.client.post("/api/v1/sync/reconcile", headers=headers, json=manifest)
    ).json()["restore_ids"] == [note["id"]]
    assert (await push(server, headers, [restore]))[0]["status"] == "applied"


async def test_restore_keeps_machine_and_origin_provenance_without_ai_feedback(server):
    headers = await signed(server)
    machine_id, owner_id = str(uuid.uuid7()), str(uuid.uuid7())
    results = await push(
        server,
        headers,
        [
            op(
                "note.restore",
                machine_id,
                body="Synthetic saved Ask answer",
                source={"kind": "plugin", "ref": "ask"},
                machine=True,
                origin="plugin",
            ),
            op(
                "note.restore",
                owner_id,
                body="Synthetic owner note",
                source={"kind": "android"},
                machine=False,
                origin="owner",
            ),
            op(
                "note.restore",
                str(uuid.uuid7()),
                body="Invalid machine",
                machine=True,
                origin="owner",
            ),
            op(
                "note.restore",
                str(uuid.uuid7()),
                body="Missing metadata",
                source={"kind": "plugin", "ref": "ask"},
            ),
        ],
    )
    assert [result["status"] for result in results] == [
        "applied",
        "applied",
        "rejected",
        "rejected",
    ]
    assert results[0]["note"]["machine"] and results[0]["note"]["origin"] == "plugin"
    assert not results[1]["note"]["machine"] and results[1]["note"]["origin"] == "owner"
    async with server.services.store.read() as conn:
        assert (
            await conn.execute_fetchall("SELECT machine,origin FROM note WHERE id=?", (machine_id,))
        )[0][:] == (1, "plugin")
        assert not await conn.execute_fetchall(
            "SELECT id FROM job WHERE plugin_id='core' AND kind='embed' AND note_id=?",
            (machine_id,),
        )
    adopted = (
        await push(
            server,
            headers,
            [op("note.update", machine_id, base_version=1, body="Owner adopted and edited")],
        )
    )[0]
    assert not adopted["note"]["machine"] and adopted["note"]["origin"] == "plugin"


async def test_latest_agent_actor_round_trips_rest_sync_and_owner_adoption(server):
    headers = await signed(server)
    response = await server.client.post(
        "/api/v1/notes",
        headers={"Origin": server.services.settings.public_url},
        json={"body": "Synthetic owner note"},
    )
    assert response.status_code == 201
    note = response.json()
    assert note["origin"] == "owner" and not note["updated_via_agent"]
    _, token = await tokens.create(
        server.services.store, name="Synthetic agent", client="script", scope="write"
    )
    edited = await server.client.patch(
        f"/api/v1/notes/{note['id']}",
        headers={"Authorization": f"Bearer {token}", "If-Match": '"1"'},
        json={"body": "Synthetic owner note with an agent edit"},
    )
    assert edited.status_code == 200
    assert edited.json()["updated_via_agent"] and edited.json()["origin"] == "owner"
    agent_pull = (await server.client.get("/api/v1/sync/pull", headers=headers)).json()
    assert agent_pull["items"][0]["data"]["updated_via_agent"]
    adopted = (
        await push(
            server,
            headers,
            [op("note.update", note["id"], base_version=2, body="Owner adopted and edited")],
        )
    )[0]
    assert not adopted["note"]["updated_via_agent"]
    owner_pull = (
        await server.client.get(
            "/api/v1/sync/pull", headers=headers, params={"cursor": agent_pull["cursor"]}
        )
    ).json()
    assert not owner_pull["items"][0]["data"]["updated_via_agent"]
    assert not (await server.client.get(f"/api/v1/notes/{note['id']}")).json()["updated_via_agent"]


@pytest.mark.parametrize("kind", ["note.create", "note.restore"])
@pytest.mark.parametrize("clock_field", ["private_at", "at", "created_at"])
async def test_private_offline_insertion_does_not_defeat_later_unprivate_action(
    server, kind, clock_field
):
    headers = await signed(server)
    note_id, now = str(uuid.uuid7()), now_ms()
    created, changed = now - 10_000, now - 1_000
    insertion = op(
        kind, note_id, body="Synthetic offline privacy", private=True, created_at=created
    )
    insertion[clock_field] = created
    results = await push(
        server, headers, [insertion, op("note.flags", note_id, private=False, at=changed)]
    )
    assert [result["status"] for result in results] == ["applied", "applied"]
    assert results[0]["note"]["flag_times"]["private"] == created
    assert not results[1]["note"]["private"]
    assert results[1]["note"]["flag_times"]["private"] == changed
    async with server.services.store.read() as conn:
        row = (
            await conn.execute_fetchall(
                "SELECT private,private_clock FROM note WHERE id=?", (note_id,)
            )
        )[0]
        assert tuple(row) == (0, changed)
