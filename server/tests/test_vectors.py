"""Real sqlite-vec fixtures exercise atomic privacy and coherent model replacement."""

import asyncio
import json
import math
from contextlib import asynccontextmanager

import pytest

from spunto.ai.errors import AIError
from spunto.ai.profiles import Profile
from spunto.ai.vectors import VectorIndex, VectorUnavailable, chunks, table_name, vector_blob
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.store.db import Store, connect
from spunto.store.migrations import migrate


class Embeddings:
    def __init__(self):
        self.profile = Profile(
            name="local", base_url="http://localhost:11434/v1", embed_model="one"
        )
        self.calls = []
        self.invalid = False
        self.pause = None

    def embedding_profile(self, profile=None):
        return profile if isinstance(profile, Profile) else self.profile

    async def dimension(self, profile=None):
        return 3 if profile.embed_model == "one" else 4

    async def embed(self, texts, *, profile, kind, notes=None, **kwargs):
        self.calls.append((profile.embed_model, kind, texts, notes))
        if self.pause:
            self.pause[0].set()
            await self.pause[1].wait()
        if self.invalid:
            return [[math.nan, 1, 2] for _ in texts]
        vectors = []
        for text in texts:
            lowered = text.lower()
            category = 0 if any(word in lowered for word in ("coffee", "caffè")) else 1
            value = [0.1, 0.1, 0.1]
            value[category] = 1
            if profile.embed_model != "one":
                value.append(0.2)
            vectors.append(value)
        return vectors


@asynccontextmanager
async def indexed(tmp_path):
    path = tmp_path / "spunto.db"
    migrate(path, tmp_path / "snapshots")
    store = await Store.open(path)
    assert store.vector_available
    gateway = Embeddings()
    index = VectorIndex(store, gateway)
    notes = Notes(store, Principal("test-owner", "session", "Owner", "full", private_ok=True))
    try:
        yield notes, store, index, gateway, path
    finally:
        await store.close()


@pytest.mark.anyio
async def test_retrieval_privacy_trash_purge_and_adoption(tmp_path):
    async with indexed(tmp_path) as (notes, store, index, gateway, path):
        coffee = await notes.create("# Coffee\n\nA caffè in Rome #travel")
        tea = await notes.create("Tea in London")
        private = await notes.create("coffee secret", private=True)
        machine = await notes.create("coffee generated")
        async with store.write() as conn:
            await conn.execute("UPDATE note SET machine=1 WHERE id=?", (machine["id"],))
        await index.sync_routes({"related": "local"})
        await index.backfill()
        result = await index.nearest(text="caffè", k=5)
        assert result[0]["id"] == coffee["id"]
        assert {row["id"] for row in result} == {coffee["id"], tea["id"]}
        assert all(
            call[3][0]["id"] != private["id"] for call in gateway.calls if call[1] == "document"
        )
        await notes.flags(coffee["id"], {"private": True})
        async with store.read() as conn:
            assert not await conn.execute_fetchall(
                "SELECT * FROM chunk WHERE note_id=?", (coffee["id"],)
            )
        await notes.flags(tea["id"], {"trashed": True})
        await notes.delete(tea["id"])
        assert not await index.nearest(text="tea")
        await notes.edit(machine["id"], "Coffee adopted", base_version=1)
        await index.index_note(machine["id"])
        assert not await index.nearest(note_id=machine["id"])
        async with store.read() as conn:
            assert await conn.execute_fetchall(
                "SELECT id FROM chunk WHERE note_id=?", (machine["id"],)
            )
        # Startup/CLI connections have the extension too, with loading disabled again.
        conn = connect(path)
        assert conn.execute("SELECT vec_version()").fetchone()[0] == "v0.1.9"
        conn.close()


@pytest.mark.anyio
async def test_model_switch_searches_old_then_atomically_activates(tmp_path):
    async with indexed(tmp_path) as (notes, store, index, gateway, _):
        first = await notes.create("Coffee")
        second = await notes.create("Tea")
        await index.sync_routes({"semantic-search": "local"})
        await index.backfill()
        old = (await index.status())["routes"][0]["active_key"]
        gateway.profile = gateway.profile.model_copy(update={"embed_model": "two"})
        await index.sync_routes({"semantic-search": "local"})
        pending = (await index.status())["routes"][0]
        assert pending["active_key"] == old != pending["target_key"]
        assert (await index.nearest(text="caffè"))[0]["id"] == first["id"]
        assert gateway.calls[-1][:2] == ("one", "query")
        await index.index_note(first["id"])
        assert (await index.status())["routes"][0]["active_key"] == old
        await index.index_note(second["id"])
        active = (await index.status())["routes"][0]
        assert active["active_key"] == active["target_key"] != old
        assert (await index.nearest(text="caffè"))[0]["id"] == first["id"]
        assert gateway.calls[-1][:2] == ("two", "query")
        async with store.read() as conn:
            assert not await conn.execute_fetchall(
                "SELECT name FROM sqlite_master WHERE name=?", (table_name(old),)
            )


async def removed_model_migration(notes, store, index, gateway):
    """Exercise the same replacement contract on sqlite-vec and shared pgvector."""
    first = await notes.create("Coffee in Rome")
    second = await notes.create("Tea in London")
    await index.sync_routes({"semantic-search": "local"})
    await index.backfill()
    old = await index.active_key()
    assert (await index.nearest(text="caffè"))[0]["id"] == first["id"]
    async with store.read() as conn:
        old_vectors = [
            row[0]
            for row in await conn.execute_fetchall(
                f"SELECT v.embedding FROM {table_name(old)} v ORDER BY v.rowid"  # noqa: S608
            )
        ]
    gateway.profile = gateway.profile.model_copy(
        update={
            "embed_model": "two",
            "embed_query_prefix": "new query: ",
            "embed_document_prefix": "new document: ",
        }
    )
    await index.sync_routes({"semantic-search": "local"})
    pending = (await index.status())["routes"][0]
    target = pending["target_key"]
    assert pending["active_key"] == old != target
    async with store.read() as conn:
        descriptor = json.loads(
            (
                await conn.execute_fetchall(
                    "SELECT descriptor FROM embedding_model WHERE model_key=?", (target,)
                )
            )[0][0]
        )
    assert descriptor["dimension"] == 4  # Synthetic model two; model one has three dimensions.
    assert descriptor["embed_query_prefix"] == "new query: "
    assert descriptor["embed_document_prefix"] == "new document: "
    # The historical descriptor remains usable while its provider still has that model.
    assert (await index.nearest(text="coffee"))[0]["id"] == first["id"]
    assert gateway.calls[-1][:2] == ("one", "query")
    original_embed = gateway.embed
    calls = []

    async def removed_old(texts, **kwargs):
        model = kwargs["profile"]
        calls.append((model.embed_model, kwargs["kind"], model.embed_document_prefix))
        if model.embed_model == "one":
            raise AIError("provider", "ai_provider_http_error", status=404)
        return await original_embed(texts, **kwargs)

    gateway.embed = removed_old
    first = await notes.edit(first["id"], "Coffee edited once", base_version=first["version"])
    third = await notes.create("Caffè in Florence")
    await index.index_note(first["id"])
    assert (await index.status())["routes"][0]["active_key"] == old
    async with store.read() as conn:
        assert [
            row[0]
            for row in await conn.execute_fetchall(
                f"SELECT v.embedding FROM {table_name(old)} v ORDER BY v.rowid"  # noqa: S608
            )
        ] == old_vectors
    # Cached queries keep using the coherent historical index, not new-dimension vectors.
    assert (await index.nearest(text="caffè"))[0]["id"] == first["id"]
    first = await notes.edit(first["id"], "Coffee edited twice", base_version=first["version"])
    await index.index_note(second["id"])
    await index.index_note(third["id"])
    assert (await index.status())["routes"][0]["active_key"] == old
    # A fresh note and intervening edit must both be indexed before activation.
    await index.index_note(first["id"])
    active = (await index.status())["routes"][0]
    assert active["active_key"] == active["target_key"] == target
    assert {row["id"] for row in await index.nearest(text="caffè")} == {
        first["id"],
        second["id"],
        third["id"],
    }
    assert gateway.calls[-1][:2] == ("two", "query")
    assert all(prefix == "new document: " for model, _, prefix in calls if model == "two")
    assert any(model == "one" for model, _, _ in calls)
    async with store.read() as conn:
        assert not await conn.execute_fetchall(
            "SELECT model_key FROM embedding_model WHERE model_key=?", (old,)
        )
        assert not await conn.execute_fetchall("SELECT id FROM chunk WHERE model_key=?", (old,))
    return old, target


@pytest.mark.anyio
async def test_removed_active_model_does_not_block_coherent_replacement(tmp_path):
    async with indexed(tmp_path) as (notes, store, index, gateway, _):
        old, _ = await removed_model_migration(notes, store, index, gateway)
        async with store.read() as conn:
            assert not await conn.execute_fetchall(
                "SELECT name FROM sqlite_master WHERE name=?", (table_name(old),)
            )


@pytest.mark.anyio
async def test_unavailable_target_does_not_update_either_index(tmp_path):
    async with indexed(tmp_path) as (notes, store, index, gateway, _):
        note = await notes.create("Coffee")
        await index.sync_routes({"related": "local"})
        await index.backfill()
        old = await index.active_key()
        gateway.profile = gateway.profile.model_copy(update={"embed_model": "two"})
        await index.sync_routes({"related": "local"})
        target = (await index.status())["routes"][0]["target_key"]
        await notes.edit(note["id"], "Coffee edited", base_version=note["version"])

        async def unavailable_target(*args, **kwargs):
            assert kwargs["profile"].embed_model == "two"
            raise AIError("provider", "ai_provider_http_error", status=404)

        gateway.embed = unavailable_target
        with pytest.raises(AIError):
            await index.index_note(note["id"])
        assert await index.active_key() == old
        async with store.read() as conn:
            assert not await conn.execute_fetchall(
                "SELECT note_id FROM embedding_note WHERE model_key=?", (target,)
            )
            old_note = await conn.execute_fetchall(
                "SELECT body_hash FROM embedding_note WHERE model_key=?", (old,)
            )
            assert old_note[0][0] == note["body_hash"]


@pytest.mark.anyio
@pytest.mark.parametrize("change", ["private", "generation"])
async def test_replacement_keeps_privacy_and_generation_fences(tmp_path, change):
    async with indexed(tmp_path) as (notes, store, index, gateway, _):
        note = await notes.create("Coffee")
        await index.sync_routes({"related": "local"})
        await index.backfill()
        gateway.profile = gateway.profile.model_copy(update={"embed_model": "two"})
        await index.sync_routes({"related": "local"})
        target = (await index.status())["routes"][0]["target_key"]
        await notes.edit(note["id"], "Coffee edited", base_version=note["version"])
        started, resume = asyncio.Event(), asyncio.Event()
        original_embed = gateway.embed

        async def blocked_target(texts, **kwargs):
            if kwargs["profile"].embed_model == "one":
                raise AIError("provider", "ai_provider_http_error", status=404)
            started.set()
            await resume.wait()
            return await original_embed(texts, **kwargs)

        gateway.embed = blocked_target
        pending = asyncio.create_task(index.index_note(note["id"]))
        await started.wait()
        if change == "private":
            await notes.flags(note["id"], {"private": True})
        else:
            index.set_routes(index.routes, force=True)
        resume.set()
        if change == "generation":
            with pytest.raises(VectorUnavailable):
                await pending
        else:
            await pending
        async with store.read() as conn:
            assert not await conn.execute_fetchall(
                "SELECT note_id FROM embedding_note WHERE model_key=?", (target,)
            )
            if change == "private":
                assert not await conn.execute_fetchall("SELECT id FROM chunk")


@pytest.mark.anyio
async def test_hot_selection_fences_activation_waiting_for_the_writer(tmp_path):
    async with indexed(tmp_path) as (notes, store, index, gateway, _):
        first = await notes.create("Coffee")
        second = await notes.create("Tea")
        await index.sync_routes({"related": "local"})
        await index.backfill()
        old = await index.active_key()
        gateway.profile = gateway.profile.model_copy(update={"embed_model": "two"})
        await index.sync_routes({"related": "local"})
        await index.index_note(first["id"])
        entered, resume = asyncio.Event(), asyncio.Event()
        original_activate, original_write = index._activate, store.write
        activating_task = None

        async def mark_activation(**kwargs):
            nonlocal activating_task
            activating_task = asyncio.current_task()
            try:
                await original_activate(**kwargs)
            finally:
                activating_task = None

        @asynccontextmanager
        async def delayed_writer():
            if asyncio.current_task() is activating_task:
                entered.set()
                await resume.wait()
            async with original_write() as connection:
                yield connection

        index._activate, store.write = mark_activation, delayed_writer
        pending = asyncio.create_task(index.index_note(second["id"]))
        await entered.wait()
        index.set_routes(index.routes, force=True)
        resume.set()
        with pytest.raises(VectorUnavailable):
            await pending
        assert await index.active_key() == old
        # Complete target rows remain reusable by a current-generation refresh.
        await index._activate()
        assert await index.active_key() != old


@pytest.mark.anyio
async def test_invalid_batch_and_disable_write_nothing(tmp_path):
    async with indexed(tmp_path) as (notes, store, index, gateway, _):
        note = await notes.create("Coffee")
        await index.sync_routes({"related": "local"})
        gateway.invalid = True
        with pytest.raises(ValueError, match="embedding_invalid"):
            await index.index_note(note["id"])
        async with store.read() as conn:
            assert not await conn.execute_fetchall("SELECT * FROM chunk")
        gateway.invalid = False
        started, resume = asyncio.Event(), asyncio.Event()
        gateway.pause = started, resume
        task = asyncio.create_task(index.index_note(note["id"]))
        await started.wait()
        await index.sync_routes({})
        resume.set()
        with pytest.raises(VectorUnavailable):
            await task
        async with store.read() as conn:
            assert not await conn.execute_fetchall("SELECT * FROM chunk")


@pytest.mark.anyio
async def test_privacy_changed_while_embedding_cannot_repopulate(tmp_path):
    async with indexed(tmp_path) as (notes, store, index, gateway, _):
        note = await notes.create("Coffee")
        await index.sync_routes({"related": "local"})
        started, resume = asyncio.Event(), asyncio.Event()
        gateway.pause = started, resume
        task = asyncio.create_task(index.index_note(note["id"]))
        await started.wait()
        await notes.flags(note["id"], {"private": True})
        resume.set()
        await task
        async with store.read() as conn:
            assert not await conn.execute_fetchall("SELECT * FROM chunk")


def test_chunk_plain_text_overlap_and_validation():
    assert chunks("# Hello\n\n**Coffee** [book](https://example.org) #travel") == [
        "Hello\n\nCoffee book travel"
    ]
    paragraphs = [f"Paragraph {i}: " + "x" * 400 for i in range(6)]
    split = chunks("\n\n".join(paragraphs))
    assert len(split) > 1
    assert split[0].split("\n\n")[-1] == split[1].split("\n\n")[0]
    with pytest.raises(ValueError):
        vector_blob([0, 0], 2)
    with pytest.raises(ValueError):
        table_name("malicious;DROP TABLE note")


@pytest.mark.anyio
async def test_all_batches_validate_before_any_chunk_is_written(tmp_path):
    async with indexed(tmp_path) as (notes, store, index, gateway, _):
        note = await notes.create("\n\n".join(f"P{i} " + "x" * 600 for i in range(70)))
        await index.sync_routes({"related": "local"})
        original = gateway.embed
        calls = 0

        async def embed(texts, **kwargs):
            nonlocal calls
            calls += 1
            assert len(texts) <= 32
            if calls == 3:
                return [[1, 2] for _ in texts]
            return await original(texts, **kwargs)

        gateway.embed = embed
        with pytest.raises(ValueError, match="embedding_dimension_mismatch"):
            await index.index_note(note["id"])
        assert calls == 3
        async with store.read() as conn:
            assert not await conn.execute_fetchall("SELECT * FROM chunk")
            assert not await conn.execute_fetchall("SELECT * FROM embedding_note")


@pytest.mark.anyio
async def test_multi_route_dimensions_and_external_filter(tmp_path):
    async with indexed(tmp_path) as (notes, store, index, gateway, _):
        own = await notes.create("Coffee owner")
        external = await notes.create("Coffee sender")
        async with store.write() as conn:
            await conn.execute("UPDATE note SET origin='external' WHERE id=?", (external["id"],))
        alternate = gateway.profile.model_copy(update={"name": "alternate", "embed_model": "two"})
        original = gateway.embedding_profile
        gateway.embedding_profile = lambda profile=None: (
            alternate if profile == "alternate" else original(profile)
        )
        await index.sync_routes({"related": "local", "semantic-search": "alternate"})
        await index.backfill()
        assert len((await index.status())["routes"]) == 2
        for name in ("local", "alternate"):
            hits = await index.nearest(text="caffè", profile=name, external_ok=False)
            assert [row["id"] for row in hits] == [own["id"]]
        assert [call[0] for call in gateway.calls if call[1] == "query"] == ["one", "two"]


@pytest.mark.anyio
async def test_restart_pending_switch_and_on_demand_final_note(tmp_path):
    async with indexed(tmp_path) as (notes, store, index, gateway, _):
        first = await notes.create("Coffee")
        second = await notes.create("Tea")
        await index.sync_routes({"related": "local"})
        await index.backfill()
        old = (await index.status())["routes"][0]["active_key"]
        gateway.profile = gateway.profile.model_copy(update={"embed_model": "two"})
        await index.sync_routes({"related": "local"})
        await index.index_note(first["id"])
        # An intervening edit must prevent completing the switch with stale target rows.
        await notes.edit(first["id"], "Coffee edited", base_version=1)
        await index.index_note(second["id"])
        assert (await index.status())["routes"][0]["active_key"] == old
        restarted = VectorIndex(store, gateway)
        await restarted.sync_routes({"related": "local"})
        assert (await restarted.nearest(text="caffè"))[0]["id"] == first["id"]
        assert gateway.calls[-1][:2] == ("one", "query")
        hits = await restarted.nearest(note_id=first["id"])
        assert hits[0]["id"] == second["id"]
        assert (await restarted.status())["routes"][0]["active_key"] != old


@pytest.mark.anyio
async def test_drop_disabled_removes_vectors_and_descriptors(tmp_path):
    async with indexed(tmp_path) as (notes, store, index, gateway, _):
        await notes.create("Coffee")
        await index.sync_routes({"related": "local"})
        await index.backfill()
        await index.sync_routes({})
        calls = len(gateway.calls)
        with pytest.raises(VectorUnavailable):
            await index.nearest(text="coffee")
        await index.reindex(drop=True)
        assert len(gateway.calls) == calls
        async with store.read() as conn:
            for table in ("chunk", "embedding_note", "embedding_route", "embedding_model"):
                assert not await conn.execute_fetchall(f"SELECT * FROM {table}")  # noqa: S608
            assert not await conn.execute_fetchall(
                "SELECT name FROM sqlite_master WHERE name GLOB 'chunk_vec_*'"
            )


@pytest.mark.anyio
async def test_seed_conflict_family_and_eligibility_filtered_in_core(tmp_path):
    async with indexed(tmp_path) as (notes, store, index, gateway, _):
        seed = await notes.create("Coffee seed")
        sibling = await notes.create("Coffee sibling")
        other = await notes.create("Coffee other")
        async with store.write() as conn:
            await conn.execute(
                "UPDATE note SET conflict_of=? WHERE id=?", (seed["id"], sibling["id"])
            )
        await index.sync_routes({"related": "local"})
        await index.backfill()
        for note_id in (seed["id"], sibling["id"]):
            assert [row["id"] for row in await index.nearest(note_id=note_id, k=5)] == [other["id"]]
        await notes.flags(seed["id"], {"private": True})
        with pytest.raises(VectorUnavailable):
            await index.nearest(note_id=seed["id"])


@pytest.mark.anyio
async def test_query_lease_retains_old_table_until_knn_finishes_during_activation(tmp_path):
    async with indexed(tmp_path) as (notes, store, index, gateway, _):
        first = await notes.create("Coffee")
        await notes.create("Tea")
        await index.sync_routes({"semantic-search": "local"})
        await index.backfill()
        old = await index.active_key()
        gateway.profile = gateway.profile.model_copy(update={"embed_model": "two"})
        await index.sync_routes({"semantic-search": "local"})
        target = (await index.status())["routes"][0]["target_key"]
        captured, release = asyncio.Event(), asyncio.Event()
        original_routes = index._routes
        count = 0

        async def pause_after_current_route(profile=None):
            nonlocal count
            value = await original_routes(profile)
            count += 1
            # Text nearest reads route once, then again immediately before KNN.
            if count == 2:
                captured.set()
                await release.wait()
            return value

        index._routes = pause_after_current_route
        query = asyncio.create_task(index.nearest(text="caffè"))
        await captured.wait()
        await index.backfill()
        assert await index.active_key() == target
        async with store.read() as conn:
            assert await conn.execute_fetchall(
                "SELECT name FROM sqlite_master WHERE name=?", (table_name(old),)
            )
        release.set()
        assert (await query)[0]["id"] == first["id"]
        assert not index._users
        async with store.read() as conn:
            assert not await conn.execute_fetchall(
                "SELECT name FROM sqlite_master WHERE name=?", (table_name(old),)
            )
        assert (
            gateway.calls[-1][0] == "two"
        )  # Backfill used the new dimension; paused query retained old bytes.


@pytest.mark.anyio
async def test_parallel_index_plans_pin_model_fk_until_all_old_writes_finish(tmp_path):
    async with indexed(tmp_path) as (notes, store, index, gateway, _):
        first = await notes.create("Coffee")
        await notes.create("Tea")
        await index.sync_routes({"semantic-search": "local"})
        await index.backfill()
        old = await index.active_key()
        gateway.profile = gateway.profile.model_copy(update={"embed_model": "two"})
        await index.sync_routes({"semantic-search": "local"})
        # Both indexing tasks must embed old+new plans. Pause only the first old request.
        await notes.edit(first["id"], "Coffee edited", base_version=first["version"])
        captured, release = asyncio.Event(), asyncio.Event()
        original_embed = gateway.embed
        paused = False

        async def pause_first_old(texts, **kwargs):
            nonlocal paused
            if kwargs["profile"].embed_model == "one" and not paused:
                paused = True
                captured.set()
                await release.wait()
            return await original_embed(texts, **kwargs)

        gateway.embed = pause_first_old
        pending = asyncio.create_task(index.index_note(first["id"]))
        await captured.wait()
        await index.backfill()
        assert await index.active_key() != old
        async with store.read() as conn:
            assert await conn.execute_fetchall(
                "SELECT model_key FROM embedding_model WHERE model_key=?", (old,)
            )
        release.set()
        await pending
        assert not index._users
        async with store.read() as conn:
            assert not await conn.execute_fetchall(
                "SELECT model_key FROM embedding_model WHERE model_key=?", (old,)
            )
            assert not await conn.execute_fetchall("PRAGMA foreign_key_check")
        assert (await index.nearest(text="coffee"))[0]["id"] == first["id"]


@pytest.mark.anyio
async def test_explicit_drop_invalidates_pending_query_then_retires_pinned_table(tmp_path):
    async with indexed(tmp_path) as (notes, store, index, gateway, _):
        await notes.create("Coffee")
        await index.sync_routes({"related": "local"})
        await index.backfill()
        old = await index.active_key()
        captured, release = asyncio.Event(), asyncio.Event()
        original_embed = gateway.embed

        async def pause_query(texts, **kwargs):
            if kwargs["kind"] == "query":
                captured.set()
                await release.wait()
            return await original_embed(texts, **kwargs)

        gateway.embed = pause_query
        query = asyncio.create_task(index.nearest(text="coffee"))
        await captured.wait()
        await index.sync_routes({})
        await index.reindex(drop=True)
        release.set()
        with pytest.raises(VectorUnavailable):
            await query
        assert not index._users
        async with store.read() as conn:
            assert not await conn.execute_fetchall(
                "SELECT name FROM sqlite_master WHERE name=?", (table_name(old),)
            )
            assert not await conn.execute_fetchall("SELECT * FROM embedding_model")
