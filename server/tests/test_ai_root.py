"""Owner boundaries, upgrades and vector snapshot compatibility for M4."""

from contextlib import closing

import pytest
from test_plugins_ai import OWNER
from test_plugins_ai import ai_host as ai_host

from spunto.ai.errors import AIError
from spunto.core.notes import Notes
from spunto.host import Ctx, Host
from spunto.jobs.backup import backup_now, restore
from spunto.store.db import connect

pytestmark = pytest.mark.anyio


async def test_vector_query_refuses_unfenced_borrowed_machine_body(ai_host):
    host, provider = ai_host
    note = await Notes(host.store, OWNER).create("machine fixture body canary")
    async with host.store.write() as conn:
        await conn.execute("UPDATE note SET machine=1 WHERE id=?", (note["id"],))
    ctx = Ctx(host, "ask", principal=OWNER)
    await ctx.notes.get(note["id"])
    calls = len(provider.requests)
    with pytest.raises(AIError, match="ai_unfenced_note"):
        await ctx.vectors.nearest(text=note["body"])
    assert len(provider.requests) == calls


async def test_vector_snapshot_restores_after_database_is_closed(ai_host):
    host, _ = ai_host
    note = await Notes(host.store, OWNER).create("SQLite vector snapshot fixture")
    await host.vectors.backfill()
    key = await host.vectors.active_key("fixture")
    snapshot = backup_now(host.settings)
    async with host.store.read() as conn:
        expected = await conn.execute_fetchall("SELECT note_id,model_key FROM chunk")
    # Restore into a separate stopped instance, never the live test server.
    destination = host.settings.model_copy(update={"data_dir": host.settings.data_dir / "restored"})
    restore(destination, snapshot)
    with closing(connect(destination.data_dir / "spunto.db")) as conn:
        assert [tuple(row) for row in conn.execute("SELECT note_id,model_key FROM chunk")] == [
            tuple(row) for row in expected
        ]
        assert (
            conn.execute(
                "SELECT active_key FROM embedding_route WHERE profile='fixture'"
            ).fetchone()[0]
            == key
        )
        assert (
            conn.execute("SELECT body FROM note WHERE id=?", (note["id"],)).fetchone()[0]
            == note["body"]
        )
        assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"


async def test_new_ai_plugins_on_upgrade_stay_off(server):
    store = server.services.store
    async with store.write() as conn:
        await conn.execute(
            "DELETE FROM plugin WHERE id IN ('ask','related','semantic-search','label-suggestions')"
        )
    host = Host(store, server.services.settings)
    try:
        await host.discover()
        for plugin_id in ("ask", "related", "semantic-search", "label-suggestions"):
            assert host.states[plugin_id]["switch"] == "off"
            assert host.states[plugin_id]["state"] == "available"
    finally:
        await host.close()


async def test_ai_off_has_waiting_defaults_but_no_ai_jobs(server):
    host = server.services.host
    assert server.services.ai.capabilities == []
    for item in host.catalog():
        if item["default"] == "on-with-ai":
            assert (item["switch"], item["state"]) == ("on", "waiting")
    async with host.store.read() as conn:
        assert not await conn.execute_fetchall(
            "SELECT 1 FROM job WHERE kind IN ('embed','ai-routes')"
        )


async def test_search_api_preserves_keyword_and_meaning_markers(server, ai_host):
    host, _ = ai_host
    server.services.host = host
    server.services.ai = host.gateway
    server.services.vectors = host.vectors
    host.store.plugin_host = host
    await server.client.post(
        "/api/v1/setup",
        json={"code": server.services.setup_code, "password": "correct horse battery"},
    )
    domain = Notes(host.store, OWNER)
    keyword = await domain.create("SQLite stores the notes")
    meaning = await domain.create("Le copie del database sono verificate")
    await host.vectors.backfill()
    response = await server.client.get("/api/v1/search", params={"q": "SQLite", "mode": "hybrid"})
    assert response.status_code == 200
    matches = {hit["id"]: hit["match"] for hit in response.json()["hits"]}
    assert matches[keyword["id"]] == "both"
    assert matches[meaning["id"]] == "meaning"
    keyword_page = await server.client.get(
        "/api/v1/search", params={"q": "SQLite", "mode": "keyword"}
    )
    assert keyword_page.json()["hits"][0]["match"] == "keyword"
    recent = await server.client.get("/api/v1/search")
    assert all(hit["match"] == "keyword" for hit in recent.json()["hits"])
