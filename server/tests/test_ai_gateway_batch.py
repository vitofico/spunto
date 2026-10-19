"""Actual leased note batches through SQLite vec0 and ASGI embedding responses."""

import asyncio

import httpx
import pytest
from ai_fake import FakeProvider
from test_ai_gateway import make_note, settings_for
from test_ai_gateway import profile as profile
from test_ai_gateway import store as store

from spunto.ai import Gateway
from spunto.ai.vectors import VectorIndex
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.host import Host

pytestmark = pytest.mark.anyio


async def services(store, profile, fake):
    gateway = Gateway(store, settings_for(profile), transport=httpx.ASGITransport(fake.app))
    index = VectorIndex(store, gateway)
    host = Host(store, settings_for(profile), gateway=gateway, vectors=index, catalog=[])
    await index.sync_routes({"semantic-search": "local"})
    fake.requests.clear()
    fake.started.clear()
    return gateway, index, host


async def count(store, table):
    async with store.read() as conn:
        return (await conn.execute_fetchall("SELECT count(*) FROM " + table))[0][0]  # noqa: S608


async def test_worker_claims_32_notes_one_lease_and_batches_remaining(store, profile):
    notes = [await make_note(store, "note " + str(i)) for i in range(40)]
    fake = FakeProvider()
    gateway, index, host = await services(store, profile, fake)
    try:
        first = await host._claim()
        assert len(first["_batch"]) == 32
        assert {row["priority"] for row in first["_batch"]} == {5}
        assert {row["lease_until"] for row in first["_batch"]} == {first["lease_until"]}
        await host._run(first)
        second = await host._claim()
        assert len(second["_batch"]) == 8
        await host._run(second)
        assert await count(store, "job") == 0
        assert await count(store, "embedding_note") == len(notes)
        assert [len(r["body"]["input"]) for r in fake.requests] == [32, 8]
        assert (await index.status())["routes"][0]["indexed"] == 40
    finally:
        await host.close()
        await gateway.close()


async def test_invalid_later_chunk_batch_writes_no_note_chunks(store, profile):
    long = "\n\n".join("paragraph " + str(i) + " " + "word " * 110 for i in range(5))
    notes = [
        await make_note(store, long),
        *[await make_note(store, "simple " + str(i)) for i in range(31)],
    ]
    fake = FakeProvider()
    gateway, index, host = await services(store, profile, fake)
    fake.modes.extend(["valid", "wrong_types"])
    try:
        job = await host._claim()
        await host._run(job)
        assert await count(store, "chunk") == 0
        assert await count(store, "embedding_note") == 0
        async with store.read() as conn:
            rows = await conn.execute_fetchall("SELECT status,attempts,lease_until FROM job")
        assert len(rows) == len(notes)
        assert all(tuple(row) == ("dead", 1, None) for row in rows)
        assert len(fake.requests[0]["body"]["input"]) == 32
        assert 1 <= len(fake.requests[1]["body"]["input"]) <= 32
    finally:
        await host.close()
        await gateway.close()


async def test_worker_batch_outage_preserves_all_attempts_and_leases(store, profile):
    for i in range(4):
        await make_note(store, "queued " + str(i))
    fake = FakeProvider()
    gateway, index, host = await services(store, profile, fake)
    fake.modes.append("500")
    try:
        job = await host._claim()
        await host._run(job)
        async with store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT status,attempts,lease_until,run_after FROM job"
            )
        assert len(rows) == 4
        assert all(tuple(row)[:3] == ("queued", 0, None) for row in rows)
        assert all(row["run_after"] > host.clock() for row in rows)
        assert not host.failures
        assert await count(store, "chunk") == 0
    finally:
        await host.close()
        await gateway.close()


async def test_worker_batch_cancellation_restores_all_jobs_without_attempt(store, profile):
    for i in range(3):
        await make_note(store, "cancelled " + str(i))
    fake = FakeProvider()
    gateway, index, host = await services(store, profile, fake)
    fake.modes.append("slow")
    try:
        job = await host._claim()
        running = asyncio.create_task(host._run(job))
        await fake.started.wait()
        running.cancel()
        await running
        async with store.read() as conn:
            rows = await conn.execute_fetchall("SELECT status,attempts,lease_until FROM job")
        assert len(rows) == 3
        assert all(tuple(row) == ("queued", 0, None) for row in rows)
        assert await count(store, "chunk") == 0
    finally:
        await host.close()
        await gateway.close()


async def test_stale_or_private_note_in_batch_cannot_repopulate_but_other_notes_write(
    store, profile
):
    first = await make_note(store, "will change")
    second = await make_note(store, "will be private")
    third = await make_note(store, "still eligible")
    fake = FakeProvider()
    gateway, index, host = await services(store, profile, fake)

    async def change_after_response():
        domain = Notes(
            store, Principal("fixture-owner", "session", "Owner", "full", private_ok=True)
        )
        await domain.edit(first["id"], "changed body", base_version=first["version"])
        await domain.flags(second["id"], {"private": True})

    fake.on_embed = change_after_response

    try:
        await index.index_notes([first["id"], second["id"], third["id"]])
        async with store.read() as conn:
            rows = await conn.execute_fetchall("SELECT note_id FROM chunk")
        assert [r[0] for r in rows] == [third["id"]]
    finally:
        await host.close()
        await gateway.close()


async def test_batch_claim_respects_priority_imports_and_keeps_interactive_on_backlog(
    store, profile
):
    imported = await make_note(store, "imported fixture", source={"kind": "import"})
    interactive = await make_note(store, "interactive fixture")
    fake = FakeProvider()
    gateway, index, host = await services(store, profile, fake)
    try:
        await index.changed(imported["id"])
        await index.changed(interactive["id"])
        await index.backlog()
        first = await host._claim()
        assert len(first["_batch"]) == 1
        assert first["priority"] == 0 and first["note_id"] == interactive["id"]
        await host._run(first)
        following = await host._claim()
        assert following["priority"] == 5 and following["note_id"] == imported["id"]
        await host._run(following)
        domain = Notes(
            store, Principal("fixture-owner", "session", "Owner", "full", private_ok=True)
        )
        await domain.edit(imported["id"], "owner changed import", base_version=imported["version"])
        await index.changed(imported["id"])
        assert (await host._claim())["priority"] == 0
    finally:
        await host.close()
        await gateway.close()


async def test_route_generation_change_discards_batch_and_returns_all_leases(store, profile):
    for i in range(3):
        await make_note(store, "generation " + str(i))
    fake = FakeProvider()
    gateway, index, host = await services(store, profile, fake)

    async def disable_route():
        await index.sync_routes({})

    fake.on_embed = disable_route
    try:
        job = await host._claim()
        await host._run(job)
        assert await count(store, "chunk") == 0
        async with store.read() as conn:
            rows = await conn.execute_fetchall("SELECT status,attempts,lease_until FROM job")
        assert len(rows) == 3
        assert all(tuple(row) == ("queued", 0, None) for row in rows)
    finally:
        await host.close()
        await gateway.close()


async def test_concurrent_route_sync_stale_generation_is_benign_for_host(store, profile):
    fake = FakeProvider()
    gateway, index, host = await services(store, profile, fake)
    host.manifests = {"fixture": {"id": "fixture", "permissions": ["ai.embed"]}}
    host.states = {"fixture": {"state": "enabled", "ai_route": {}}}
    started, resume = asyncio.Event(), asyncio.Event()
    original = gateway.dimension

    async def paused_dimension(profile=None):
        started.set()
        await resume.wait()
        return await original(profile)

    gateway.dimension = paused_dimension
    try:
        first = asyncio.create_task(host._sync_vectors())
        await started.wait()
        host.states["fixture"]["state"] = "disabled"
        await host._sync_vectors()
        assert not index.enabled
        resume.set()
        await first
        async with store.read() as conn:
            retry = await conn.execute_fetchall("SELECT kind FROM job WHERE status='queued'")
        assert [r[0] for r in retry] == ["ai-routes"]
    finally:
        await host.close()
        await gateway.close()


async def test_disabled_route_rechecked_after_waiting_for_provider_capacity(store, profile):
    note = await make_note(store, "queued outbound canary")
    fake = FakeProvider()
    gateway, index, host = await services(store, profile, fake)
    queue = gateway.queues[profile.name]
    queue.active = profile.concurrency
    try:
        task = asyncio.create_task(index.index_notes([note["id"]]))
        async with asyncio.timeout(3):
            async with queue.condition:
                await queue.condition.wait_for(lambda: bool(queue.pending))
        await index.sync_routes({})
        async with queue.condition:
            queue.active = 0
            queue.condition.notify_all()
        with pytest.raises(RuntimeError, match="vectors_unavailable"):
            await task
        assert not fake.requests
        assert await count(store, "chunk") == 0
    finally:
        await host.close()
        await gateway.close()


async def test_new_private_note_while_capacity_queued_releases_whole_lease_then_indexes_others(
    store, profile
):
    notes = [
        await make_note(store, "private queued canary"),
        *[await make_note(store, "public " + str(i)) for i in range(3)],
    ]
    fake = FakeProvider()
    gateway, index, host = await services(store, profile, fake)
    queue = gateway.queues[profile.name]
    queue.active = profile.concurrency
    try:
        first = await host._claim()
        task = asyncio.create_task(host._run(first))
        async with asyncio.timeout(3):
            async with queue.condition:
                await queue.condition.wait_for(lambda: bool(queue.pending))
        domain = Notes(
            store, Principal("fixture-owner", "session", "Owner", "full", private_ok=True)
        )
        await domain.flags(notes[0]["id"], {"private": True})
        async with queue.condition:
            queue.active = 0
            queue.condition.notify_all()
        await task
        assert not fake.requests
        async with store.write() as conn:
            rows = await conn.execute_fetchall("SELECT status,attempts,lease_until FROM job")
            assert all(tuple(row) == ("queued", 0, None) for row in rows)
            await conn.execute("UPDATE job SET run_after=0 WHERE status='queued'")
        await host._run(await host._claim())
        assert await count(store, "job") == 0
        assert await count(store, "embedding_note") == 3
        assert "private queued canary" not in str(fake.requests)
        assert len(fake.requests[-1]["body"]["input"]) == 3
    finally:
        await host.close()
        await gateway.close()


async def test_shared_vector_model_keeps_profile_credentials_on_each_route_and_restart(
    store, profile
):
    from pydantic import SecretStr

    first = profile.model_copy(update={"name": "first", "api_key": SecretStr("first-fixture")})
    second = profile.model_copy(update={"name": "second", "api_key": SecretStr("second-fixture")})
    settings = settings_for(first)
    settings.ai_profiles[second.name] = second
    fake = FakeProvider()
    gateway = Gateway(store, settings, transport=httpx.ASGITransport(fake.app))
    index = VectorIndex(store, gateway)
    await make_note(store, "same descriptor private routing contract")
    try:
        await index.sync_routes({"semantic-search": "first", "related": "second"})
        await index.backfill()
        assert await index.active_key("first") == await index.active_key("second")
        fake.requests.clear()
        await index.nearest(text="first routed query", profile="first")
        await index.nearest(text="second routed query", profile="second")
        assert [r["headers"]["authorization"] for r in fake.requests] == [
            "Bearer first-fixture",
            "Bearer second-fixture",
        ]
        # Descriptors persist without keys; reconstruction must use the requested
        # route even though embedding_model.profile records the first creator.
        restarted = VectorIndex(store, gateway)
        restarted.routes = dict(index.routes)
        fake.requests.clear()
        await restarted.nearest(text="second after restart", profile="second")
        assert fake.requests[0]["headers"]["authorization"] == "Bearer second-fixture"
    finally:
        await gateway.close()
