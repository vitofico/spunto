"""Private, restorable scoped snapshots and leases against disposable PostgreSQL."""

import asyncio
import hashlib
import json
import stat
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest
from psycopg import sql
from test_host import bind
from test_postgres import notes
from test_postgres import pg as pg
from test_vectors import Embeddings

from spunto.ai.vectors import VectorIndex
from spunto.auth import tokens
from spunto.errors import OperatorError
from spunto.host.plugin import Plugin
from spunto.host.runtime import Host
from spunto.host.testing import FakeClock
from spunto.store import postgres_snapshot as snapshots
from spunto.store.postgres import PostgresStore

pytestmark = pytest.mark.anyio
NOW = datetime(2026, 10, 18, tzinfo=UTC)


async def rows(store, table):
    async with store.read() as scoped:
        return [
            row[0]
            for row in await (
                await scoped._connection.execute(
                    sql.SQL(
                        "SELECT row_to_json(t) FROM spunto.{} t ORDER BY row_to_json(t)::text"
                    ).format(sql.Identifier(table))
                )
            ).fetchall()
        ]


async def empty(store):
    async with store.write() as scoped:
        for table in reversed(snapshots.TABLES):
            await scoped._connection.execute(
                sql.SQL("DELETE FROM spunto.{}").format(sql.Identifier(table))
            )


def rewrite_manifest(path, manifest):
    content = json.dumps(manifest).encode()
    (path / "manifest.json").write_bytes(content)
    (path / "manifest.sha256").write_text(hashlib.sha256(content).hexdigest() + "  manifest.json\n")


async def test_snapshot_roundtrip_preserves_notes_plugins_vectors_and_invalidates_credentials(
    pg, tmp_path
):
    root, bounds, users, spaces, _ = pg
    first, other = bounds
    note = await notes(first, users[0]).create("Caffè private fixture #snapshot")
    await notes(other, users[1]).create("Another workspace must never enter the snapshot")
    plugin_host = Host(first, SimpleNamespace(data_dir=tmp_path, plugins=""), catalog=[])
    await plugin_host.discover()
    async with first.write() as conn:
        await conn.execute(
            "INSERT INTO plugin_kv VALUES('synthetic','fixture','private plugin state')"
        )
        await conn.execute(
            "INSERT INTO plugin_record VALUES('synthetic','data','entry',?,NULL,?,1)",
            (note["id"], '{"literal":"caffè"}'),
        )
        await conn.execute(
            "INSERT INTO credential(id,kind,name,scope,secret_hash,created_at) "
            "VALUES('plugin:synthetic','plugin','Internal','write','plugin-hash',1)"
        )
    member = root.for_workspace(spaces[0], users[1], "editor")
    credential_id, member_token = await tokens.create(
        member, name="Member token", client="script", scope="write"
    )
    _, other_token = await tokens.create(other, name="Other token", client="script", scope="write")
    async with first.write() as conn:
        await conn.execute(
            "INSERT INTO pairing VALUES('synthetic-pairing',?,1,9999999999999)", (credential_id,)
        )
    index = VectorIndex(first, Embeddings())
    await index.sync_routes({"semantic-search": "local"})
    await index.backfill()
    before = {table: await rows(first, table) for table in snapshots.TABLES}
    target = await snapshots.write_postgres_snapshot(first, tmp_path / "snapshot", NOW)
    manifest = json.loads((target / "manifest.json").read_text())
    assert len(manifest["tables"]) == 27
    assert stat.S_IMODE(target.stat().st_mode) == 0o700
    assert all(stat.S_IMODE(path.stat().st_mode) == 0o600 for path in target.iterdir())
    assert manifest["workspace_id"] == spaces[0]
    assert all(entry["rows"] == len(before[entry["table"]]) for entry in manifest["tables"])
    await empty(first)
    receipt = await snapshots.restore_postgres_snapshot(first, target)
    assert receipt["external_credentials_invalidated"] == 1 and receipt["pairings_cleared"] == 1
    for table in snapshots.TABLES:
        if table not in {"meta", "credential", "pairing"}:
            assert await rows(first, table) == before[table]
    old_meta = {row["key"]: row["value"] for row in before["meta"]}
    new_meta = {row["key"]: row["value"] for row in await rows(first, "meta")}
    assert new_meta["instance_id"] == old_meta["instance_id"]
    assert new_meta["sync_epoch"] != old_meta["sync_epoch"]
    assert not await rows(first, "pairing")
    credentials = {row["id"]: row for row in await rows(first, "credential")}
    assert credentials[credential_id]["secret_hash"] is None
    assert credentials[credential_id]["revoked_at"] is not None
    assert credentials["plugin:synthetic"]["secret_hash"] == "plugin-hash"
    assert await tokens.authenticate(first, member_token) is None
    assert await tokens.authenticate(other, other_token) is not None
    assert (await notes(first, users[0]).get(note["id"]))["body"] == note["body"]
    assert (await index.nearest(text="caffè"))[0]["id"] == note["id"]
    second = await snapshots.write_postgres_snapshot(first, tmp_path / "restored", NOW)
    restored_manifest = json.loads((second / "manifest.json").read_text())
    assert restored_manifest["tables"][-1]["sha256"] == manifest["tables"][-1]["sha256"]
    assert len(await rows(other, "note")) == 1
    await plugin_host.close()


async def test_snapshot_is_one_consistent_read_while_a_second_connection_writes(
    pg, tmp_path, monkeypatch
):
    _, bounds, users, spaces, runtime = pg
    first = bounds[0]
    await notes(first, users[0]).create("Before #before")
    writer_root = await PostgresStore.open(runtime)
    writer = writer_root.for_workspace(spaces[0], users[0], "owner")
    original = snapshots._dump_table
    wrote = False

    async def dump(connection, table, columns, workspace, path):
        nonlocal wrote
        result = await original(connection, table, columns, workspace, path)
        if not wrote:
            wrote = True
            await notes(writer, users[0]).create("After #after")
        return result

    monkeypatch.setattr(snapshots, "_dump_table", dump)
    try:
        target = await snapshots.write_postgres_snapshot(first, tmp_path / "consistent", NOW)
    finally:
        await writer_root.close()
    manifest = json.loads((target / "manifest.json").read_text())
    counts = {entry["table"]: entry["rows"] for entry in manifest["tables"]}
    assert counts["note"] == 1 and counts["label"] == 1 and counts["note_label"] == 1
    assert len(await rows(first, "note")) == 2
    await empty(first)
    await snapshots.restore_postgres_snapshot(first, target)
    assert [row["body"] for row in await rows(first, "note")] == ["Before #before"]


async def test_restore_rejects_another_workspace_nonempty_target_and_changed_bytes(pg, tmp_path):
    root, bounds, users, spaces, _ = pg
    note = await notes(bounds[0], users[0]).create("Synthetic snapshot")
    target = await snapshots.write_postgres_snapshot(bounds[0], tmp_path / "snapshot", NOW)
    with pytest.raises(OperatorError, match="another workspace"):
        await snapshots.restore_postgres_snapshot(bounds[1], target)
    with pytest.raises(OperatorError, match="must be empty"):
        await snapshots.restore_postgres_snapshot(bounds[0], target)
    with pytest.raises(OperatorError, match="owner-bound"):
        await snapshots.write_postgres_snapshot(
            root.for_workspace(spaces[0], users[0], "viewer"), tmp_path / "denied", NOW
        )
    (target / "03-note.copy").write_bytes(b"damaged")
    with pytest.raises(OperatorError, match="damaged"):
        await snapshots.restore_postgres_snapshot(bounds[0], target)
    assert (await notes(bounds[0], users[0]).get(note["id"]))["body"] == note["body"]


async def test_restore_bad_row_count_rolls_back_every_table_and_temp_tables(pg, tmp_path):
    _, bounds, users, _, _ = pg
    await notes(bounds[0], users[0]).create("Synthetic snapshot")
    target = await snapshots.write_postgres_snapshot(bounds[0], tmp_path / "snapshot", NOW)
    manifest = json.loads((target / "manifest.json").read_text())
    manifest["tables"][-1]["rows"] += 1
    rewrite_manifest(target, manifest)
    await empty(bounds[0])
    with pytest.raises(OperatorError, match="row counts"):
        await snapshots.restore_postgres_snapshot(bounds[0], target)
    assert not any([await rows(bounds[0], table) for table in snapshots.TABLES])
    manifest["tables"][-1]["rows"] -= 1
    rewrite_manifest(target, manifest)
    await snapshots.restore_postgres_snapshot(bounds[0], target)
    assert len(await rows(bounds[0], "note")) == 1


async def test_host_first_pg_plugin_migration_takes_snapshot_without_sqlite_file(
    pg, tmp_path, monkeypatch
):
    _, bounds, _, _, _ = pg
    plugin = Plugin("snapshot-fixture")

    @plugin.migrate(0, 1)
    async def migrate(ctx):
        await ctx.storage.kv.set("version", 1)

    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    host = Host(bounds[0], settings, catalog=[bind(monkeypatch, plugin, data_version=1)])
    try:
        await host.discover()
        await host.enable(plugin.id, [])
        await host.start()
        async with asyncio.timeout(5):
            while host.states[plugin.id]["state"] == "waiting":  # noqa: ASYNC110
                await asyncio.sleep(0.01)
        assert host.states[plugin.id]["state"] == "enabled"
        assert host.states[plugin.id]["data_version"] == 1
        assert list((tmp_path / "snapshots").rglob("manifest.json"))
        assert not (tmp_path / "spunto.db").exists()
    finally:
        await host.close()


async def test_pg_peer_start_preserves_active_lease_and_claim_recovers_expired_receipts(
    pg, tmp_path, monkeypatch
):
    _, bounds, _, _, _ = pg
    clock = FakeClock()
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    first, peer = [Host(bounds[0], settings, catalog=[], clock=clock) for _ in range(2)]
    entered = asyncio.Event()

    async def idle():
        entered.set()
        await asyncio.Event().wait()

    monkeypatch.setattr(peer, "_loop", idle)
    try:
        job_id = await first.enqueue_core("reindex")
        claimed = await first._claim()
        assert claimed["id"] == job_id
        await peer.start()
        await entered.wait()
        assert (await rows(bounds[0], "job"))[0]["run_receipt"] == claimed["run_receipt"]
        assert await peer._claim() is None
        clock.advance(300001)
        recovered = await peer._claim()
        assert recovered["id"] == job_id and recovered["run_receipt"] != claimed["run_receipt"]
        async with bounds[0].write() as conn:
            assert not await first._delete_job(conn, claimed)
        assert (await rows(bounds[0], "job"))[0]["run_receipt"] == recovered["run_receipt"]
    finally:
        if peer._worker:
            peer._worker.cancel()
            await asyncio.gather(peer._worker, return_exceptions=True)
        await first.close()
        peer._worker = None
        await peer.close()


async def test_expired_jobs_keep_queued_replacement_and_coalesce_multiple_expired_claims(
    pg, tmp_path
):
    _, bounds, _, _, _ = pg
    clock = FakeClock()
    host = Host(bounds[0], SimpleNamespace(data_dir=tmp_path, plugins=""), catalog=[], clock=clock)
    try:
        old_id = await host.enqueue_core("reindex", {"generation": "old"})
        old = await host._claim()
        replacement = await host.enqueue_core("reindex", {"generation": "new"})
        clock.advance(300001)
        current = await host._claim()
        assert current["id"] == replacement and current["id"] != old_id
        assert '"generation": "new"' in current["args"]
        async with bounds[0].write() as conn:
            assert not await host._delete_job(conn, old)
        # Two peers may have claimed the same coalescing key before both leases expired.
        newer = await host.enqueue_core("reindex", {"generation": "newest"})
        second = await host._claim()
        assert second["id"] == newer
        clock.advance(300001)
        final = await host._claim()
        assert final["id"] == newer
        assert len(await rows(bounds[0], "job")) == 1
    finally:
        await host.close()


async def test_pg_worker_polls_abandoned_lease_without_new_work(pg, tmp_path, monkeypatch):
    from spunto.host import runtime

    _, bounds, _, _, _ = pg
    clock = FakeClock()
    monkeypatch.setattr(runtime, "_PG_LEASE_POLL_SECONDS", 0.01)
    settings = SimpleNamespace(data_dir=tmp_path, plugins="", tz="UTC")
    stopped, peer = [Host(bounds[0], settings, catalog=[], clock=clock) for _ in range(2)]
    completed = asyncio.Event()

    async def recovered():
        completed.set()

    peer.register_core("recovery-fixture", recovered)
    stopped.register_core("recovery-fixture", recovered)
    await stopped.enqueue_core("recovery-fixture")
    old = await stopped._claim()
    try:
        await peer.start()
        await asyncio.sleep(0.02)
        assert not completed.is_set()
        assert (await rows(bounds[0], "job"))[0]["run_receipt"] == old["run_receipt"]
        clock.advance(300001)
        async with asyncio.timeout(2):
            await completed.wait()
    finally:
        await stopped.close()
        await peer.close()
