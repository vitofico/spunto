"""Real PostgreSQL contract tests; run against an operator-owned disposable database."""

import asyncio
import os
import uuid
from contextlib import asynccontextmanager

import psycopg
import pytest

from spunto.api.errors import Problem
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.store.db import now_ms
from spunto.store.postgres import PostgresStore, _qmarks, _translate

pytestmark = pytest.mark.anyio


async def test_commit_cancellation_preserves_data_and_dispatches_event(pg, monkeypatch):
    root, bounds, _, _, _ = pg
    bound = bounds[0]
    commit_started, release_commit = asyncio.Event(), asyncio.Event()
    dispatched = []
    original = bound._transaction

    @asynccontextmanager
    async def delayed_exit(*, readonly):
        async with original(readonly=readonly) as connection:
            yield connection
            if not readonly:
                commit_started.set()
                await release_commit.wait()

    async def sink(event, **metadata):
        dispatched.append(event)

    monkeypatch.setattr(bound, "_transaction", delayed_exit)
    bound.event_sink = sink

    async def save():
        async with bound.write() as connection:
            await connection.execute("INSERT INTO setting(key,value) VALUES('commit-test','saved')")
            bound.emit("note.created", "synthetic-note", 1)

    task = asyncio.create_task(save())
    await commit_started.wait()
    task.cancel()
    release_commit.set()
    with pytest.raises(asyncio.CancelledError):
        await task
    await root.drain_events()
    async with bound.read() as connection:
        assert (
            await connection.execute_fetchall("SELECT value FROM setting WHERE key='commit-test'")
        )[0][0] == "saved"
    assert dispatched == ["note.created"]


async def test_child_event_registry_is_drained_by_root(pg):
    root, _, users, spaces, _ = pg
    child = root.for_workspace(spaces[0], users[0], "owner")
    entered, release = asyncio.Event(), asyncio.Event()

    async def sink(event, **metadata):
        entered.set()
        await release.wait()

    child.event_sink = sink
    async with child.write() as connection:
        await connection.execute("INSERT INTO setting(key,value) VALUES('child-event','saved')")
        child.emit("note.created", "synthetic-note", 1)
    await entered.wait()
    drain = asyncio.create_task(root.drain_events())
    await asyncio.sleep(0)
    assert not drain.done()
    release.set()
    await drain
    assert not root._dispatch_tasks


@pytest.fixture
async def pg():
    runtime = os.environ.get("SPUNTO_TEST_POSTGRES_URL")
    admin = os.environ.get("SPUNTO_TEST_POSTGRES_ADMIN_URL")
    if not runtime or not admin:
        pytest.skip("Set disposable SPUNTO_TEST_POSTGRES_URL and SPUNTO_TEST_POSTGRES_ADMIN_URL")
    store = await PostgresStore.open(runtime, admin_url=admin, max_size=1)
    users, spaces = [uuid.uuid4().hex for _ in range(2)], [uuid.uuid4().hex for _ in range(2)]
    async with await psycopg.AsyncConnection.connect(admin) as connection:
        for user, space in zip(users, spaces, strict=True):
            await connection.execute(
                "INSERT INTO public.spunto_account(id,issuer,subject) VALUES(%s,%s,%s)",
                (user, "https://synthetic.invalid", user),
            )
            await connection.execute(
                "INSERT INTO public.spunto_workspace(id,name,created_by,created_at) "
                "VALUES(%s,%s,%s,%s)",
                (space, "Synthetic workspace", user, now_ms()),
            )
            await connection.execute(
                "INSERT INTO public.spunto_membership VALUES(%s,%s,'owner')", (space, user)
            )
    bounds = [
        store.for_workspace(space, user, "owner") for user, space in zip(users, spaces, strict=True)
    ]
    for bound in bounds:
        await bound.initialize_workspace()
    try:
        yield store, bounds, users, spaces, runtime
    finally:
        await store.close()
        async with await psycopg.AsyncConnection.connect(admin) as connection:
            for space in spaces:
                await connection.execute(
                    "DELETE FROM public.spunto_workspace WHERE id=%s", (space,)
                )
            for user in users:
                await connection.execute("DELETE FROM public.spunto_account WHERE id=%s", (user,))


def notes(store, user):
    return Notes(store, Principal(user, "session", "Synthetic user", "full", private_ok=True))


async def test_postgres_error_detail_never_contains_note_values(pg):
    _, bounds, _, _, _ = pg
    secret = "synthetic private note that must never appear in an exception"
    with pytest.raises(psycopg.errors.NotNullViolation) as error:
        async with bounds[0].write() as connection:
            await connection.execute(
                "INSERT INTO note(id,body) VALUES (?,?)", (uuid.uuid4().hex, secret)
            )
    assert secret not in str(error.value)
    assert "23502" in str(error.value)
    assert error.value.diag.message_detail is None


async def test_notes_create_edit_list_search_revisions_flags_and_delete(pg):
    _, bounds, users, _, _ = pg
    domain = notes(bounds[0], users[0])
    created = await domain.create("Caffè planning\n- [ ] Call Marco #project/spunto")
    assert created["labels"] == ["project/spunto"]
    assert created["open_tasks"] == 1
    assert (await domain.list())["notes"][0]["id"] == created["id"]
    assert (await domain.search("caffe plan #project"))["hits"][0]["id"] == created["id"]
    assert (await domain.search('"Call Marco"'))["hits"][0]["id"] == created["id"]
    edited = await domain.edit(created["id"], "Replacement #new", created["version"])
    assert edited["version"] == 2 and edited["labels"] == ["new"]
    assert not (await domain.search("Marco"))["hits"]
    assert (await domain.search("Replac"))["hits"][0]["id"] == created["id"]
    assert [label["path"] for label in (await domain.labels())["labels"]] == ["new"]
    async with bounds[0].read() as connection:
        revisions = await connection.execute_fetchall(
            "SELECT version FROM note_revision ORDER BY version"
        )
        assert [row[0] for row in revisions] == [1, 2]
    private = await domain.flags(created["id"], {"private": True})
    assert private["private"]
    await domain.flags(created["id"], {"trashed": True})
    await domain.delete(created["id"])
    assert not (await domain.list(view="all"))["notes"]


async def test_workspace_isolation_connection_reuse_and_readonly(pg):
    root, bounds, users, spaces, runtime = pg
    first, second = [notes(bound, user) for bound, user in zip(bounds, users, strict=True)]
    a = await first.create("Workspace alpha secret #alpha")
    b = await second.create("Workspace beta secret #beta")
    for _ in range(3):
        assert [n["id"] for n in (await first.list())["notes"]] == [a["id"]]
        assert [n["id"] for n in (await second.list())["notes"]] == [b["id"]]
        assert not (await first.search("beta"))["hits"]
    with pytest.raises(Problem) as error:
        await first.get(b["id"])
    assert error.value.status == 404
    viewer = root.for_workspace(spaces[0], users[0], "viewer")
    with pytest.raises(PermissionError):
        await notes(viewer, users[0]).create("Forbidden")
    with pytest.raises(psycopg.errors.ReadOnlySqlTransaction):
        async with bounds[0].read() as connection:
            await connection.execute("DELETE FROM note")
    with pytest.raises(RuntimeError):
        async with root.read():
            pass
    with pytest.raises(ValueError):
        bounds[0].for_workspace(spaces[1], users[1], "owner")
    # The very same pooled physical connection has no workspace after rollback/commit.
    async with root._pool.connection() as connection:
        assert not (await (await connection.execute("SELECT id FROM spunto.note")).fetchall())
        context = await (
            await connection.execute("SELECT current_setting('spunto.workspace_id',true)")
        ).fetchone()
        assert context[0] in (None, "")
    # SQL without an application WHERE is still isolated by the runtime role's RLS.
    async with await psycopg.AsyncConnection.connect(runtime) as connection:
        await connection.execute(
            "SELECT set_config('spunto.workspace_id',%s,true),"
            "set_config('spunto.user_id',%s,true),set_config('spunto.role','viewer',true)",
            (spaces[0], users[0]),
        )
        assert await (await connection.execute("SELECT id FROM spunto.note")).fetchall() == [
            (a["id"],)
        ]
        assert (await connection.execute("DELETE FROM spunto.note")).rowcount == 0
        await connection.rollback()
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            await connection.execute("SELECT * FROM public.spunto_account")


async def test_events_are_transaction_scoped_and_rollback_does_not_dispatch(pg):
    _, bounds, _, _, _ = pg
    received = []

    async def sink(event, **kwargs):
        received.append((event, kwargs))

    bounds[0].event_sink = sink
    async with bounds[0].write():
        bounds[0].emit("note.saved", "synthetic", 1, private=False)
    await bounds[0].drain_events()
    assert [item[0] for item in received] == ["note.saved"]
    with pytest.raises(ValueError):
        async with bounds[0].write():
            bounds[0].emit("note.saved", "rollback", 2)
            raise ValueError("synthetic rollback")
    await bounds[0].drain_events()
    assert len(received) == 1


async def test_composite_keys_allow_same_plugin_keys_and_reject_cross_workspace_links(pg):
    _, bounds, users, _, _ = pg
    await asyncio.gather(
        *[
            notes(bound, user).create("A shared label #same")
            for bound, user in zip(bounds, users, strict=True)
        ]
    )
    for bound in bounds:
        async with bound.write() as connection:
            await connection.execute(
                "INSERT OR REPLACE INTO plugin_kv VALUES('synthetic','key','1')"
            )
            await connection.execute(
                "INSERT OR REPLACE INTO plugin_kv VALUES('synthetic','key','2')"
            )
        async with bound.read() as connection:
            assert (await connection.execute_fetchall("SELECT value FROM plugin_kv"))[0][0] == "2"
    other = (await notes(bounds[1], users[1]).list())["notes"][0]["id"]
    with pytest.raises(psycopg.errors.ForeignKeyViolation):
        async with bounds[0].write() as connection:
            await connection.execute(
                "INSERT INTO note_revision VALUES(?,1,'body','edit','synthetic',1)", (other,)
            )


def test_parameter_translation_preserves_literals():
    assert _qmarks("SELECT '?',?, 'What?'") == "SELECT '?',%s, 'What?'"
    sql, args, _ = _translate(
        "INSERT OR IGNORE INTO label(key,path,created_at) VALUES(?,?,?)", ["x", "X", 1]
    )
    assert sql.endswith("ON CONFLICT DO NOTHING RETURNING id")
    assert args == ["x", "X", 1]


async def test_pgvector_privacy_retrieval_model_switch_and_workspace_isolation(pg):
    from test_vectors import Embeddings

    from spunto.ai.vectors import VectorIndex

    _, bounds, users, _, _ = pg
    first, second = [notes(bound, user) for bound, user in zip(bounds, users, strict=True)]
    coffee = await first.create("Coffee in Rome")
    tea = await first.create("Tea in London")
    await first.create("Coffee private", private=True)
    other = await second.create("Coffee from another workspace")
    gateway = Embeddings()
    index = VectorIndex(bounds[0], gateway)
    await index.sync_routes({"semantic-search": "local"})
    await index.backfill()
    hits = await index.nearest(text="caffè", k=5)
    assert hits[0]["id"] == coffee["id"]
    assert {hit["id"] for hit in hits} == {coffee["id"], tea["id"]}
    assert other["id"] not in {hit["id"] for hit in hits}
    assert [hit["id"] for hit in await index.nearest(note_id=coffee["id"])] == [tea["id"]]
    old = (await index.status())["routes"][0]["active_key"]
    gateway.profile = gateway.profile.model_copy(update={"embed_model": "two"})
    await index.sync_routes({"semantic-search": "local"})
    await index.index_note(coffee["id"])
    assert (await index.status())["routes"][0]["active_key"] == old
    await index.index_note(tea["id"])
    assert (await index.status())["routes"][0]["active_key"] != old
    await first.flags(coffee["id"], {"private": True})
    assert [hit["id"] for hit in await index.nearest(text="caffè")] == [tea["id"]]
    async with bounds[0].read() as connection:
        assert not await connection.execute_fetchall(
            "SELECT chunk_id FROM chunk_vector WHERE model_key=?", (old,)
        )
    await index.reindex(drop=True)
    await index.backfill()
    assert [hit["id"] for hit in await index.nearest(text="tea")] == [tea["id"]]


async def test_pgvector_removed_active_model_replacement_preserves_other_workspace(pg):
    from test_vectors import Embeddings, removed_model_migration

    from spunto.ai.vectors import VectorIndex

    _, bounds, users, _, _ = pg
    other_notes = notes(bounds[1], users[1])
    other = await other_notes.create("Coffee in another workspace")
    other_index = VectorIndex(bounds[1], Embeddings())
    await other_index.sync_routes({"semantic-search": "local"})
    await other_index.backfill()
    other_status = await other_index.status()
    async with bounds[1].read() as connection:
        rows = await connection.execute_fetchall(
            "SELECT model_key,chunk_id,embedding FROM chunk_vector ORDER BY chunk_id"
        )
        other_vectors = [(row[0], row[1], row[2]) for row in rows]
    gateway = Embeddings()
    index = VectorIndex(bounds[0], gateway)
    old, _ = await removed_model_migration(notes(bounds[0], users[0]), bounds[0], index, gateway)
    assert other_status["routes"][0]["active_key"] == old
    assert await other_index.status() == other_status
    assert [row["id"] for row in await other_index.nearest(text="caffè")] == [other["id"]]
    async with bounds[1].read() as connection:
        rows = await connection.execute_fetchall(
            "SELECT model_key,chunk_id,embedding FROM chunk_vector ORDER BY chunk_id"
        )
        assert [(row[0], row[1], row[2]) for row in rows] == other_vectors
    async with bounds[0].read() as connection:
        assert not await connection.execute_fetchall(
            "SELECT chunk_id FROM chunk_vector WHERE model_key=?", (old,)
        )


async def test_host_storage_and_job_upsert_use_existing_domain_apis(pg, settings):
    from spunto.host.runtime import Host

    _, bounds, _, _, _ = pg
    host = Host(bounds[0], settings)
    first = await host._queue("synthetic", "job:work", {"value": 1}, priority=5)
    again = await host._queue("synthetic", "job:work", {"value": 2}, priority=0)
    assert type(first) is int and again == first
    async with bounds[0].read() as connection:
        rows = await connection.execute_fetchall(
            "SELECT priority,args FROM job WHERE id=?", (first,)
        )
        assert rows[0]["priority"] == 0 and '"value": 2' in rows[0]["args"]


async def test_host_catalog_lifecycle_and_core_lease_claims(pg, settings):
    from spunto.host.runtime import Host

    _, bounds, _, _, _ = pg
    hosts = [Host(bound, settings) for bound in bounds]
    try:
        for host in hosts:
            await host.discover()
            assert host.catalog()
            await host.enqueue_core("reindex")
            claimed = await host._claim()
            assert claimed["plugin_id"] == "core" and claimed["run_receipt"]
            async with host.store.write() as connection:
                await host._delete_job(connection, claimed)
            assert not await host._claim()
    finally:
        for host in hosts:
            await host.close()


async def test_host_counter_json_archive_and_ai_stat_upserts(pg):
    _, bounds, _, _, _ = pg
    async with bounds[0].write() as connection:
        for _ in range(2):
            await connection.execute(
                "INSERT INTO plugin_kv(plugin_id,key,value) VALUES('synthetic','host:counter','1') "
                "ON CONFLICT(plugin_id,key) DO UPDATE SET "
                "value=CAST(CAST(value AS INTEGER)+CAST(excluded.value AS INTEGER) AS TEXT)"
            )
            await connection.execute(
                "INSERT INTO ai_stat VALUES('2026-10-18','synthetic','chat','ok',1) "
                "ON CONFLICT(day,plugin_id,capability,outcome) DO UPDATE SET n=n+1"
            )
        await connection.execute(
            "INSERT INTO job(plugin_id,kind,args,priority,status,run_after,created_at,updated_at) "
            "VALUES('synthetic','job:archive',?,0,'queued',0,0,0)",
            ('{"archive_id":"synthetic"}',),
        )
    async with bounds[0].read() as connection:
        assert (await connection.execute_fetchall("SELECT value FROM plugin_kv"))[0][0] == "2"
        assert (await connection.execute_fetchall("SELECT n FROM ai_stat"))[0][0] == 2
        assert (
            await connection.execute_fetchall(
                "SELECT kind FROM job WHERE json_extract(args,'$.archive_id')=?", ("synthetic",)
            )
        )[0][0] == "job:archive"


async def test_cancelled_writer_rolls_back_and_pool_context_does_not_leak(pg):
    root, bounds, _, _, _ = pg
    started = asyncio.Event()

    async def cancelled():
        async with bounds[0].write() as connection:
            await connection.execute("INSERT INTO setting VALUES('cancelled','synthetic')")
            bounds[0].emit("note.saved", "cancelled", 1)
            started.set()
            await asyncio.Event().wait()

    task = asyncio.create_task(cancelled())
    await started.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    async with bounds[0].read() as connection:
        assert not await connection.execute_fetchall(
            "SELECT value FROM setting WHERE key='cancelled'"
        )
    async with root._pool.connection() as connection:
        assert (await (await connection.execute("SELECT count(*) FROM spunto.setting")).fetchone())[
            0
        ] == 0


async def test_runtime_rejects_admin_role_and_direct_cross_workspace_writes(pg):
    from spunto.errors import OperatorError

    _, bounds, users, spaces, runtime = pg
    with pytest.raises(OperatorError, match="restricted PostgreSQL runtime role"):
        await PostgresStore.open(os.environ["SPUNTO_TEST_POSTGRES_ADMIN_URL"])
    await notes(bounds[0], users[0]).create("Scoped note")
    async with await psycopg.AsyncConnection.connect(runtime) as connection:
        await connection.execute(
            "SELECT set_config('spunto.workspace_id',%s,true),"
            "set_config('spunto.user_id',%s,true),set_config('spunto.role','owner',true)",
            (spaces[0], users[0]),
        )
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            await connection.execute(
                "INSERT INTO spunto.setting(workspace_id,key,value) VALUES(%s,%s,%s)",
                (spaces[1], "cross", "forbidden"),
            )
