"""Local lifecycle fencing never waits on the dimension/model preparation job."""

import asyncio

import httpx
import pytest
from conftest import PUBLIC_URL
from test_host import manifest
from test_vectors import Embeddings

from spunto.ai.errors import AIError
from spunto.ai.vectors import VectorIndex, VectorUnavailable
from spunto.api.deps import Services
from spunto.auth.tokens import Principal
from spunto.config import Settings
from spunto.core.notes import Notes
from spunto.host import Host
from spunto.host.plugin import ContextError
from spunto.main import create_app
from spunto.store.db import Store
from spunto.store.migrations import migrate

pytestmark = pytest.mark.anyio


class LifecycleGateway(Embeddings):
    health = []

    def __init__(self):
        super().__init__()
        self.alternate = self.profile.model_copy(update={"name": "alternate", "embed_model": "two"})
        self.dimension_calls = []
        self.dimension_pause = None
        self.unavailable = False

    def profile_for(self, capability, plugin_route=None, profile=None):
        assert capability.removeprefix("ai.") == "embed"
        name = profile or (plugin_route or {}).get("embed", "local")
        return self.embedding_profile(name)

    def embedding_profile(self, profile=None):
        if profile is None or profile == "local":
            return self.profile
        if profile == "alternate":
            return self.alternate
        return super().embedding_profile(profile)

    async def dimension(self, profile=None):
        self.dimension_calls.append((profile.name, profile.embed_model))
        if self.dimension_pause:
            self.dimension_pause[0].set()
            await self.dimension_pause[1].wait()
        if self.unavailable:
            raise AIError("unavailable", "ai_unavailable", retry_after=60)
        return await super().dimension(profile)


@pytest.fixture
async def lifecycle(tmp_path):
    settings = Settings(public_url=PUBLIC_URL, data_dir=tmp_path / "data")
    settings.data_dir.mkdir()
    path = settings.data_dir / "spunto.db"
    migrate(path, settings.backup_dir / "pre-migration")
    store = await Store.open(path)
    assert store.vector_available
    gateway = LifecycleGateway()
    index = VectorIndex(store, gateway)
    catalog = [
        manifest("tasks", name="Tasks", permissions=["notes.read", "annotate"]),
        manifest("semantic-search", permissions=["ai.embed"], requires=["ai.embed"], default="on"),
        manifest("related", permissions=["ai.embed"], requires=["ai.embed"], default="on"),
    ]
    host = Host(store, settings, catalog=catalog, gateway=gateway, vectors=index)
    await host.discover()
    services = Services(settings, store, host=host, ai=gateway, vectors=index)
    services.setup_code = "synthetic-lifecycle-code"
    transport = httpx.ASGITransport(create_app(services), client=("203.0.113.7", 50000))
    async with httpx.AsyncClient(transport=transport, base_url=PUBLIC_URL) as client:
        response = await client.post(
            "/api/v1/setup",
            json={"code": services.setup_code, "password": "synthetic lifecycle owner password"},
        )
        assert response.status_code == 204
        try:
            yield host, index, gateway, client
        finally:
            await host.close()
            await store.close()


async def switch(client, plugin_id, on, permissions=None):
    response = await client.post(
        f"/api/v1/plugins/{plugin_id}/{'enable' if on else 'disable'}",
        json={"permissions": permissions} if on else None,
        headers={"Origin": PUBLIC_URL},
    )
    assert response.status_code == 200, response.text
    return response.json()


async def run_routes_job(host):
    row = await host._claim()
    assert row["kind"] == "ai-routes" and row["plugin_id"] == "core"
    await host._run(row)


async def test_tasks_and_all_off_complete_during_paused_dimension_without_stale_commit(lifecycle):
    host, index, gateway, client = lifecycle
    started, release = asyncio.Event(), asyncio.Event()
    gateway.dimension_pause = started, release
    preparation = asyncio.create_task(host._sync_vectors())
    try:
        await started.wait()
        vector_generation = index.generation
        tasks_generation = host.generations["tasks"]
        permissions = host.manifests["tasks"]["permissions"]
        async with asyncio.timeout(1):
            enabled = await switch(client, "tasks", True, permissions)
            disabled = await switch(client, "tasks", False)
        assert enabled["granted"] == disabled["granted"] == permissions
        assert disabled["switch"] == "off" and disabled["state"] == "disabled"
        assert host.generations["tasks"] == tasks_generation + 1
        assert index.generation == vector_generation
        assert not preparation.done() and len(gateway.dimension_calls) == 1
        async with asyncio.timeout(1):
            await switch(client, "semantic-search", False)
            await switch(client, "related", False)
        assert index.routes == {} and index.generation > vector_generation
        catalog = (await client.get("/api/v1/plugins")).json()["plugins"]
        assert all(item["switch"] == "off" for item in catalog)
        assert all(item["granted"] == item["permissions"] for item in catalog)
        assert not preparation.done()
        release.set()
        await preparation
        async with host.store.read() as conn:
            assert not await conn.execute_fetchall("SELECT * FROM embedding_model")
            assert not await conn.execute_fetchall("SELECT * FROM embedding_route")
        await run_routes_job(host)
        assert len(gateway.dimension_calls) == 1
    finally:
        release.set()
        await asyncio.gather(preparation, return_exceptions=True)


async def test_lifecycle_queues_durable_model_preparation_and_existing_fingerprint_swap(lifecycle):
    host, index, gateway, _ = lifecycle
    assert gateway.dimension_calls == []
    async with host.store.read() as conn:
        jobs = await conn.execute_fetchall("SELECT kind,attempts FROM job WHERE status='queued'")
        assert [tuple(row) for row in jobs] == [("ai-routes", 0)]
        assert not await conn.execute_fetchall("SELECT * FROM embedding_model")
    await run_routes_job(host)
    old = await index.active_key()
    assert old and gateway.dimension_calls == [("local", "one")]
    gateway.profile = gateway.profile.model_copy(update={"embed_model": "two"})
    await host.refresh_ai()
    assert gateway.dimension_calls == [("local", "one")]
    await run_routes_job(host)
    assert await index.active_key() != old
    assert gateway.dimension_calls == [("local", "one"), ("local", "two")]
    async with host.store.read() as conn:
        dimensions = await conn.execute_fetchall("SELECT dimension FROM embedding_model")
        assert [row[0] for row in dimensions] == [4]


async def test_profile_change_fences_paused_preparation_before_any_model_write(lifecycle):
    host, index, gateway, client = lifecycle
    # Use one embedding consumer so the old profile is actually revoked.
    await switch(client, "related", False)
    started, release = asyncio.Event(), asyncio.Event()
    gateway.dimension_pause = started, release
    preparation = asyncio.create_task(host._sync_vectors())
    try:
        await started.wait()
        generation = index.generation
        async with asyncio.timeout(1):
            response = await client.put(
                "/api/v1/plugins/semantic-search/ai-route",
                json={"embed": "alternate"},
                headers={"Origin": PUBLIC_URL},
            )
        assert response.status_code == 200
        assert index.routes == {"semantic-search": "alternate"}
        assert index.generation > generation
        with pytest.raises(VectorUnavailable):
            await index.nearest(text="synthetic", profile="local")
        release.set()
        await preparation
        gateway.dimension_pause = None
        async with host.store.read() as conn:
            assert not await conn.execute_fetchall("SELECT * FROM embedding_model")
        await run_routes_job(host)
        async with host.store.read() as conn:
            rows = await conn.execute_fetchall("SELECT profile,dimension FROM embedding_model")
            assert [tuple(row) for row in rows] == [("alternate", 4)]
    finally:
        release.set()
        await asyncio.gather(preparation, return_exceptions=True)


async def test_unavailable_dimension_preserves_job_retry_and_does_not_block_tasks(lifecycle):
    host, _, gateway, client = lifecycle
    gateway.unavailable = True
    await run_routes_job(host)
    async with host.store.read() as conn:
        rows = await conn.execute_fetchall(
            "SELECT status,attempts,last_error,run_after FROM job WHERE kind='ai-routes'"
        )
        assert len(rows) == 1
        assert tuple(rows[0])[:3] == ("queued", 0, "ai_unavailable")
        assert rows[0]["run_after"] > host.clock() + 50000
    calls = list(gateway.dimension_calls)
    async with asyncio.timeout(1):
        await switch(client, "tasks", True, host.manifests["tasks"]["permissions"])
        await switch(client, "tasks", False)
    assert gateway.dimension_calls == calls
    async with host.store.read() as conn:
        retried = await conn.execute_fetchall(
            "SELECT status,attempts,last_error,run_after FROM job WHERE kind='ai-routes'"
        )
        assert [tuple(row) for row in retried] == [tuple(row) for row in rows]


@pytest.mark.parametrize("disable_embeddings", [False, True])
async def test_tasks_preserve_paused_query_but_all_off_invalidates_its_generation(
    lifecycle, disable_embeddings
):
    host, index, gateway, client = lifecycle
    owner = Principal("owner-fixture", "session", "Owner", "full", private_ok=True)
    notes = Notes(host.store, owner)
    note = await notes.create("Coffee fixture")
    await index.sync_routes(index.routes)
    await index.backfill()
    started, release = asyncio.Event(), asyncio.Event()
    gateway.pause = started, release
    query = asyncio.create_task(index.nearest(text="coffee"))
    try:
        await started.wait()
        generation = index.generation
        async with asyncio.timeout(1):
            await switch(client, "tasks", True, host.manifests["tasks"]["permissions"])
            await switch(client, "tasks", False)
            if disable_embeddings:
                await switch(client, "semantic-search", False)
                await switch(client, "related", False)
        assert (index.generation > generation) == disable_embeddings
        release.set()
        if disable_embeddings:
            with pytest.raises(VectorUnavailable):
                await query
        else:
            assert [result["id"] for result in await query] == [note["id"]]
    finally:
        release.set()
        await asyncio.gather(query, return_exceptions=True)


@pytest.mark.parametrize("write_kind", ["model", "note"])
async def test_route_revocation_during_real_sql_write_rolls_back_before_commit(
    lifecycle, monkeypatch, write_kind
):
    host, index, _, _ = lifecycle
    note = None
    if write_kind == "note":
        await index.sync_routes(index.routes)
        owner = Principal("owner-fixture", "session", "Owner", "full", private_ok=True)
        note = await Notes(host.store, owner).create("Coffee write fixture")
    captured, release = asyncio.Event(), asyncio.Event()
    execute = host.store._writer.execute
    statement = (
        "INSERT INTO embedding_route" if write_kind == "model" else "INSERT INTO embedding_note"
    )

    async def paused_execute(sql, *args, **kwargs):
        result = await execute(sql, *args, **kwargs)
        if sql.startswith(statement):
            captured.set()
            await release.wait()
        return result

    # Delegate all SQL to the real connection; pause only after its actual insert.
    monkeypatch.setattr(host.store._writer, "execute", paused_execute)
    operation = asyncio.create_task(
        index.prepare_routes() if note is None else index.index_note(note["id"])
    )
    disabling = None
    try:
        await captured.wait()
        generation = index.generation
        revoked = asyncio.Event()
        set_routes = index.set_routes

        def observed_routes(routes):
            changed = set_routes(routes)
            if index.generation != generation:
                revoked.set()
            return changed

        monkeypatch.setattr(index, "set_routes", observed_routes)
        disabling = asyncio.create_task(host.disable("semantic-search"))
        async with asyncio.timeout(1):
            await revoked.wait()
        assert "semantic-search" not in index.routes
        assert not disabling.done()  # Its local DB write waits for the one writer.
        release.set()
        with pytest.raises(VectorUnavailable):
            await operation
        await disabling
        async with host.store.read() as conn:
            assert not await conn.execute_fetchall("SELECT * FROM chunk")
            assert not await conn.execute_fetchall("SELECT * FROM embedding_note")
            if write_kind == "model":
                assert not await conn.execute_fetchall("SELECT * FROM embedding_model")
                assert not await conn.execute_fetchall("SELECT * FROM embedding_route")
    finally:
        release.set()
        await asyncio.gather(operation, return_exceptions=True)
        if disabling is not None:
            await asyncio.gather(disabling, return_exceptions=True)


async def test_health_revocation_fences_shared_profile_before_waiting_for_vector_writer(
    lifecycle, monkeypatch
):
    host, index, gateway, _ = lifecycle
    await index.sync_routes(index.routes)
    owner = Principal("owner-fixture", "session", "Owner", "full", private_ok=True)
    note = await Notes(host.store, owner).create("Coffee health fixture")
    captured, release, revoked = asyncio.Event(), asyncio.Event(), asyncio.Event()
    execute, set_routes = host.store._writer.execute, index.set_routes
    generation = index.generation
    plugin_generations = dict(host.generations)
    before = {item["id"]: item["granted"] for item in host.catalog()}

    async def paused_execute(sql, *args, **kwargs):
        result = await execute(sql, *args, **kwargs)
        if sql.startswith("INSERT INTO embedding_note"):
            captured.set()
            await release.wait()
        return result

    def observed_routes(routes, **kwargs):
        changed = set_routes(routes, **kwargs)
        if index.generation != generation:
            revoked.set()
        return changed

    monkeypatch.setattr(host.store._writer, "execute", paused_execute)
    monkeypatch.setattr(index, "set_routes", observed_routes)
    operation = asyncio.create_task(index.index_note(note["id"]))
    revoking = None
    try:
        await captured.wait()
        gateway.health = [
            {
                "profile": "local",
                "capability": "embed",
                "model": gateway.profile.embed_model,
                "ok": False,
            }
        ]
        revoking = asyncio.create_task(host.refresh_ai())
        async with asyncio.timeout(1):
            await revoked.wait()
        assert host.states["semantic-search"]["state"] == "waiting"
        assert index.routes == {"related": "local"} and index.enabled
        assert not revoking.done()  # Health metadata waits, but core generation is already fenced.
        release.set()
        with pytest.raises(VectorUnavailable):
            await operation
        await revoking
        assert not index.enabled and index.routes == {}
        assert host.generations["tasks"] == plugin_generations["tasks"]
        for plugin_id in ("semantic-search", "related"):
            assert host.generations[plugin_id] == plugin_generations[plugin_id] + 1
            assert host.states[plugin_id]["state"] == "waiting"
            assert host.states[plugin_id]["switch"] == "on"
        assert {item["id"]: item["granted"] for item in host.catalog()} == before
        async with host.store.read() as conn:
            assert not await conn.execute_fetchall("SELECT * FROM chunk")
            assert not await conn.execute_fetchall("SELECT * FROM embedding_note")
            rows = await conn.execute_fetchall(
                "SELECT state,last_error FROM plugin WHERE id IN ('semantic-search','related')"
            )
            reason = (
                "The embed model failed its connection check. "
                "Run the connection check in Settings → AI."
            )
            assert [tuple(row) for row in rows] == [("waiting", reason), ("waiting", reason)]
    finally:
        release.set()
        await asyncio.gather(operation, return_exceptions=True)
        if revoking is not None:
            await asyncio.gather(revoking, return_exceptions=True)


@pytest.mark.parametrize("superseder", ["state", "disable"])
async def test_superseded_state_cannot_commit_or_restore_over_newer_owner(
    lifecycle, monkeypatch, superseder
):
    host, index, _, _ = lifecycle
    plugin_id = "semantic-search"
    await host._state(plugin_id, "waiting", "previous_waiting")
    expected = host.generations[plugin_id]
    enabled, superseded = asyncio.Event(), asyncio.Event()
    set_routes = index.set_routes

    def observed_routes(routes, **kwargs):
        changed = set_routes(routes, **kwargs)
        if host.states[plugin_id]["state"] == "enabled":
            enabled.set()
        elif enabled.is_set():
            superseded.set()
        return changed

    monkeypatch.setattr(index, "set_routes", observed_routes)
    older = newer = None
    try:
        # Hold the actual writer while desired states are published; no mocked store.
        async with host.store.write():
            older = asyncio.create_task(host._state(plugin_id, "enabled", generation=expected))
            async with asyncio.timeout(1):
                await enabled.wait()
            newer = asyncio.create_task(
                host._state(plugin_id, "waiting", "new_transition", generation=expected)
                if superseder == "state"
                else host.disable(plugin_id)
            )
            async with asyncio.timeout(1):
                await superseded.wait()
            assert plugin_id not in index.routes
        with pytest.raises(ContextError, match="plugin_disabled"):
            await older
        await newer
        expected_state = "waiting" if superseder == "state" else "disabled"
        expected_error = "new_transition" if superseder == "state" else None
        assert host.states[plugin_id]["state"] == expected_state
        assert host.states[plugin_id]["last_error"] == expected_error
        assert host.generations[plugin_id] == expected + (superseder == "disable")
        async with host.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT state,last_error,switch FROM plugin WHERE id=?", (plugin_id,)
            )
            assert tuple(rows[0]) == (
                expected_state,
                expected_error,
                "on" if superseder == "state" else "off",
            )
    finally:
        tasks = [task for task in (older, newer) if task is not None]
        await asyncio.gather(*tasks, return_exceptions=True)


async def test_superseded_activation_cannot_mark_newer_owner_off_as_failed(lifecycle, monkeypatch):
    host, index, gateway, _ = lifecycle
    plugin_id = "semantic-search"
    started, revoked = asyncio.Event(), asyncio.Event()
    profile_for, set_routes = gateway.profile_for, index.set_routes

    def observed_profile(*args, **kwargs):
        profile = profile_for(*args, **kwargs)
        started.set()
        return profile

    def observed_routes(routes, **kwargs):
        changed = set_routes(routes, **kwargs)
        if plugin_id not in index.routes:
            revoked.set()
        return changed

    monkeypatch.setattr(gateway, "profile_for", observed_profile)
    monkeypatch.setattr(index, "set_routes", observed_routes)
    activation = disabling = None
    try:
        async with host.store.write():
            activation = asyncio.create_task(host._activate(plugin_id))
            async with asyncio.timeout(1):
                await started.wait()
            disabling = asyncio.create_task(host.disable(plugin_id))
            async with asyncio.timeout(1):
                await revoked.wait()
        with pytest.raises(ContextError, match="plugin_disabled"):
            await activation
        await disabling
        assert host.states[plugin_id]["switch"] == "off"
        assert host.states[plugin_id]["state"] == "disabled"
        assert host.states[plugin_id]["last_error"] is None
        async with host.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT switch,state,last_error FROM plugin WHERE id=?", (plugin_id,)
            )
            assert tuple(rows[0]) == ("off", "disabled", None)
    finally:
        tasks = [task for task in (activation, disabling) if task is not None]
        await asyncio.gather(*tasks, return_exceptions=True)


async def test_newer_state_rolls_back_older_metadata_after_its_awaited_sql(lifecycle, monkeypatch):
    host, index, _, _ = lifecycle
    plugin_id = "semantic-search"
    captured, release, superseded = asyncio.Event(), asyncio.Event(), asyncio.Event()
    execute, set_routes = host.store._writer.execute, index.set_routes
    async with host.store.read() as conn:
        revision = await conn.execute_fetchall("SELECT value FROM meta WHERE key='plugins_rev'")
        before = revision[0][0]

    async def paused_execute(sql, *args, **kwargs):
        result = await execute(sql, *args, **kwargs)
        if sql.startswith("UPDATE plugin SET state=?,last_error=?"):
            captured.set()
            await release.wait()
        return result

    def observed_routes(routes, **kwargs):
        changed = set_routes(routes, **kwargs)
        if captured.is_set() and host.states[plugin_id]["state"] == "enabled":
            superseded.set()
        return changed

    monkeypatch.setattr(host.store._writer, "execute", paused_execute)
    monkeypatch.setattr(index, "set_routes", observed_routes)
    older = asyncio.create_task(host._state(plugin_id, "waiting", "older_transition"))
    newer = None
    try:
        await captured.wait()
        newer = asyncio.create_task(host._state(plugin_id, "enabled", "latest_transition"))
        async with asyncio.timeout(1):
            await superseded.wait()
        release.set()
        with pytest.raises(ContextError, match="plugin_disabled"):
            await older
        await newer
        async with host.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT state,last_error FROM plugin WHERE id=?", (plugin_id,)
            )
            assert tuple(rows[0]) == ("enabled", "latest_transition")
            revision = await conn.execute_fetchall("SELECT value FROM meta WHERE key='plugins_rev'")
            after = revision[0][0]
            assert int(after) == int(before) + 1
        assert host.states[plugin_id]["last_error"] == "latest_transition"
        assert index.routes == {"semantic-search": "local", "related": "local"}
    finally:
        release.set()
        tasks = [task for task in (older, newer) if task is not None]
        await asyncio.gather(*tasks, return_exceptions=True)
