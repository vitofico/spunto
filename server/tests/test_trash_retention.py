import pytest

from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.jobs import maintenance

ORIGIN = {"Origin": "http://spunto.test:8080"}
OWNER = Principal("test-owner", "session", "Synthetic owner", "full", private_ok=True)
DAY_MS = 86_400_000


@pytest.mark.anyio
async def test_empty_trash_purges_only_trashed_notes_and_leaves_tombstones(server):
    setup = await server.client.post(
        "/api/v1/setup",
        json={"code": server.services.setup_code, "password": "correct horse 1"},
    )
    assert setup.status_code == 204
    kept = await server.client.post("/api/v1/notes", json={"body": "Keep"}, headers=ORIGIN)
    gone = await server.client.post("/api/v1/notes", json={"body": "Gone"}, headers=ORIGIN)
    assert kept.status_code == gone.status_code == 201
    kept_id, gone_id = kept.json()["id"], gone.json()["id"]
    trashed = await server.client.post(
        f"/api/v1/notes/{gone_id}/flags", json={"trashed": True}, headers=ORIGIN
    )
    assert trashed.status_code == 200

    response = await server.client.post("/api/v1/trash/empty", headers=ORIGIN)
    assert response.status_code == 200
    assert response.json() == {"deleted": 1}
    kept = await server.client.get(f"/api/v1/notes/{kept_id}")
    assert kept.status_code == 200 and kept.json()["body"] == "Keep"
    async with server.services.store.read() as conn:
        notes = await conn.execute_fetchall("SELECT id FROM note")
        stones = await conn.execute_fetchall("SELECT id FROM tombstone WHERE kind='note'")
    assert [row[0] for row in notes] == [kept_id]
    assert [row[0] for row in stones] == [gone_id]


@pytest.mark.anyio
async def test_trash_purge_keeps_a_note_until_its_window_ends(server):
    store = server.services.store
    settings = server.services.settings
    domain = Notes(store, OWNER)
    note = await domain.create("Trashed today")
    assert (await domain.flags(note["id"], {"trashed": True}))["trashed"]
    async with store.read() as conn:
        [(trashed_at,)] = await conn.execute_fetchall(
            "SELECT trashed_at FROM note WHERE id=?", (note["id"],)
        )

    deadline = trashed_at + settings.trash_days * DAY_MS
    for when in (deadline - DAY_MS, deadline - 1):
        early = await maintenance.prune(store, settings, now=when)
        assert early["notes"] == 0
        assert (await domain.get(note["id"], include_trashed=True))["trashed"]

    due = await maintenance.prune(store, settings, now=deadline)
    assert due["notes"] == 1
    async with store.read() as conn:
        notes = await conn.execute_fetchall("SELECT id FROM note")
        stones = await conn.execute_fetchall("SELECT id FROM tombstone WHERE kind='note'")
    assert not notes
    assert [row[0] for row in stones] == [note["id"]]
