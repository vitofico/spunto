"""Synthetic SQLite files and operator-owned disposable PostgreSQL only."""

import asyncio
import hashlib
import json
import os
import secrets
import struct
import uuid
from contextlib import closing
from dataclasses import replace

import psycopg
import pytest

from spunto.auth import tokens
from spunto.core.notes import Notes
from spunto.core.text import fold
from spunto.errors import OperatorError
from spunto.store import sqlite_import
from spunto.store.db import connect, now_ms
from spunto.store.migrations import LATEST, migrate
from spunto.store.postgres import _COLUMNS, PostgresStore
from spunto.store.sqlite_import import (
    import_sqlite_snapshot,
    reconcile_import_sequences,
    verify_attachment_files,
)


@pytest.fixture
def snapshot(tmp_path):
    path = tmp_path / "snapshot.db"
    migrate(path, tmp_path / "backups")
    stamp = now_ms()
    note_id, conflict_id = str(uuid.uuid7()), str(uuid.uuid7())
    credential_id, secret = secrets.token_hex(8), secrets.token_urlsafe(32)
    pat_id, pat_secret = secrets.token_hex(8), secrets.token_urlsafe(32)
    browser_id = secrets.token_hex(8)
    key = hashlib.sha256(b"synthetic-model").hexdigest()
    payload = b"synthetic attachment bytes"
    sha = hashlib.sha256(payload).hexdigest()
    source_files = tmp_path / "attachments"
    attachment_path = source_files / sha[:2] / sha[2:4] / sha
    attachment_path.parent.mkdir(parents=True)
    attachment_path.write_bytes(payload)

    def insert(connection, table, **values):
        columns = tuple(values)
        connection.execute(
            f"INSERT INTO {table}({','.join(columns)}) VALUES({','.join('?' for _ in columns)})",
            tuple(values.values()),
        )

    with closing(connect(path)) as connection:
        insert(
            connection,
            "owner",
            id=1,
            password_hash="oidc-only",
            password_source="ui",
            created_at=stamp,
        )
        for cid, kind, token_secret in (
            (credential_id, "device", secret),
            (pat_id, "pat", pat_secret),
            (browser_id, "session", secrets.token_urlsafe(32)),
            ("plugin-fixture", "plugin", None),
        ):
            insert(
                connection,
                "credential",
                id=cid,
                kind=kind,
                name="Synthetic credential",
                client="android",
                scope="write" if kind == "pat" else "full",
                private_ok=1,
                secret_hash=hashlib.sha256(token_secret.encode()).hexdigest()
                if token_secret
                else None,
                created_at=stamp,
                expires_at=stamp + 86400000,
                last_sync_at=stamp - 1,
            )
        for nid, rowid, conflict in ((note_id, 110000, None), (conflict_id, 110001, note_id)):
            body = "Caffè synthetic migration #old /a/" + str(uuid.uuid7())
            values = dict(
                id=nid,
                body=body,
                title="Caffè synthetic",
                body_hash=hashlib.sha256(body.encode()).hexdigest(),
                version=2,
                change_seq=17,
                created_at=stamp,
                updated_at=stamp,
                source_kind="android",
                origin="owner",
                created_by=credential_id,
                updated_by=credential_id,
                conflict_of=conflict,
            )
            insert(connection, "note", **values, rowid=rowid)
            insert(
                connection,
                "note_revision",
                note_id=nid,
                version=1,
                body="Original synthetic text",
                change_kind="create",
                changed_by=credential_id,
                changed_at=stamp - 2,
            )
            insert(
                connection,
                "note_revision",
                note_id=nid,
                version=2,
                body=body,
                change_kind="edit",
                changed_by=credential_id,
                changed_at=stamp,
            )
            insert(
                connection,
                "note_fts",
                rowid=rowid,
                title=fold(values["title"]),
                body=fold(body),
                extra="fixture extra",
            )
        insert(
            connection,
            "tombstone",
            kind="note",
            id=str(uuid.uuid7()),
            change_seq=23,
            deleted_at=stamp,
        )
        insert(connection, "label", id=70000, key="old", path="old", created_at=stamp)
        insert(connection, "note_label", note_id=note_id, label_id=70000)
        attachment_id = str(uuid.uuid7())
        insert(
            connection,
            "attachment",
            id=attachment_id,
            sha256=sha,
            mime="application/octet-stream",
            size=len(payload),
            filename="synthetic.bin",
            created_at=stamp,
        )
        insert(connection, "note_attachment", note_id=note_id, attachment_id=attachment_id)
        insert(
            connection,
            "applied_op",
            credential_id=credential_id,
            op_id=str(uuid.uuid4()),
            result=json.dumps({"status": "applied", "note_id": note_id, "version": 2}),
            applied_at=stamp,
        )
        insert(
            connection,
            "plugin",
            id="fixture",
            version="1.0.0",
            kind="server",
            switch="on",
            state="enabled",
            granted='["read_notes"]',
            settings='{"enabled":true}',
            position=0,
            data_version=3,
            manifest_snapshot='{"id":"fixture"}',
            source_kind="builtin",
            source_identity="fixture",
        )
        insert(
            connection, "plugin_kv", plugin_id="fixture", key="checkpoint", value='{"cursor":17}'
        )
        insert(
            connection,
            "plugin_record",
            plugin_id="fixture",
            collection="items",
            id="one",
            note_id=note_id,
            key="item",
            data='{"done":false}',
            updated_at=stamp,
        )
        insert(
            connection,
            "suggestion",
            id=str(uuid.uuid7()),
            rowid=120000,
            plugin_id="fixture",
            note_id=note_id,
            note_version=2,
            action="add_label",
            payload='{"path":"test"}',
            confidence=0.875,
            status="pending",
            change_seq=19,
            created_at=stamp,
        )
        insert(
            connection,
            "annotation",
            plugin_id="fixture",
            note_id=note_id,
            kind="meta",
            payload='{"type":"text","text":"fixture"}',
            change_seq=20,
            updated_at=stamp,
        )
        insert(
            connection,
            "note_text",
            plugin_id="fixture",
            note_id=note_id,
            text="fixture extra",
            updated_at=stamp,
        )
        insert(
            connection,
            "job",
            id=90000,
            plugin_id="fixture",
            kind="job:fixture",
            note_id=note_id,
            args='{"next":1}',
            priority=5,
            status="running",
            attempts=2,
            run_after=stamp,
            lease_until=stamp + 30000,
            created_at=stamp,
            updated_at=stamp,
            run_receipt='{"step":"done"}',
        )
        insert(connection, "setting", key="theme", value='"bosco"')
        insert(
            connection,
            "embedding_model",
            model_key=key,
            profile="fixture",
            descriptor='{"dimension":3}',
            dimension=3,
            created_at=stamp,
        )
        insert(connection, "embedding_route", profile="fixture", active_key=key, target_key=key)
        insert(
            connection, "chunk", id=80000, note_id=note_id, ordinal=0, text_hash=sha, model_key=key
        )
        insert(
            connection,
            "embedding_note",
            note_id=note_id,
            model_key=key,
            text_hash=sha,
            body_hash=sha,
        )
        insert(
            connection,
            "ai_stat",
            day="2026-10-18",
            plugin_id="fixture",
            capability="embed",
            outcome="ok",
            n=4,
        )
        insert(
            connection,
            "pairing",
            code_hash=hashlib.sha256(secrets.token_bytes(32)).hexdigest(),
            credential_id=browser_id,
            created_at=stamp,
            expires_at=stamp + 300000,
        )
        connection.execute(
            f"CREATE VIRTUAL TABLE chunk_vec_{key} "
            "USING vec0(embedding float[3] distance_metric=cosine)"
        )
        connection.execute(
            f"INSERT INTO chunk_vec_{key}(rowid,embedding) VALUES(?,?)",
            (80000, struct.pack("<fff", 0.25, -0.5, 1.0)),
        )
        connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    return {
        "path": path,
        "files": source_files,
        "note_id": note_id,
        "device": f"spunto_device_{credential_id}_{secret}",
        "pat": f"spunto_pat_{pat_id}_{pat_secret}",
        "browser_id": browser_id,
        "key": key,
    }


@pytest.fixture
async def target():
    runtime = os.environ.get("SPUNTO_TEST_POSTGRES_URL")
    identity = os.environ.get("SPUNTO_TEST_IDENTITY_URL")
    admin = os.environ.get("SPUNTO_TEST_POSTGRES_ADMIN_URL")
    if not all((runtime, identity, admin)):
        pytest.skip("Set the three disposable PostgreSQL connection URLs")
    user, workspace = uuid.uuid4().hex, uuid.uuid4().hex
    async with await psycopg.AsyncConnection.connect(admin) as connection:
        await connection.execute(
            "INSERT INTO public.spunto_account(id,issuer,subject) VALUES(%s,%s,%s)",
            (user, "https://synthetic.invalid", user),
        )
        await connection.execute(
            "INSERT INTO public.spunto_workspace(id,name,created_by,created_at,personal) "
            "VALUES(%s,'Imported',%s,%s,true)",
            (workspace, user, now_ms()),
        )
        await connection.execute(
            "INSERT INTO public.spunto_membership VALUES(%s,%s,'owner')", (workspace, user)
        )
    try:
        yield {
            "database_url": runtime,
            "identity_database_url": identity,
            "workspace_id": workspace,
            "user_id": user,
            "admin": admin,
        }
    finally:
        async with await psycopg.AsyncConnection.connect(admin) as connection:
            await connection.execute(
                "DELETE FROM public.spunto_workspace WHERE id=%s", (workspace,)
            )
            await connection.execute("DELETE FROM public.spunto_account WHERE id=%s", (user,))


async def run_import(snapshot, target):
    return await import_sqlite_snapshot(
        snapshot["path"], **{key: value for key, value in target.items() if key != "admin"}
    )


@pytest.mark.anyio
async def test_operator_cli_imports_from_secret_files_and_reconciles_sequences(
    snapshot, target, tmp_path, monkeypatch, capsys
):
    from spunto import cli

    for variable, key in (
        ("SPUNTO_DATABASE_URL", "database_url"),
        ("SPUNTO_IDENTITY_DATABASE_URL", "identity_database_url"),
        ("SPUNTO_DATABASE_ADMIN_URL", "admin"),
    ):
        path = tmp_path / (key + ".secret")
        path.write_text(target[key] + "\n")
        path.chmod(0o600)
        monkeypatch.delenv(variable, raising=False)
        monkeypatch.setenv(variable + "_FILE", str(path))
    result = await asyncio.to_thread(
        cli.main,
        [
            "database",
            "import-sqlite",
            "--snapshot",
            str(snapshot["path"]),
            "--workspace",
            target["workspace_id"],
            "--user",
            target["user_id"],
        ],
    )
    assert result == 0
    report = json.loads(capsys.readouterr().out)
    assert report["tables"]["note"]["count"] == 2
    assert report["sequence_reconciliation_required"]
    assert await asyncio.to_thread(cli.main, ["database", "reconcile-sequences"]) == 0
    floors = json.loads(capsys.readouterr().out)
    assert floors and all(value > 0 for value in floors.values())


@pytest.mark.anyio
async def test_full_snapshot_preserves_rows_epoch_tokens_vectors_and_files(snapshot, target):
    before = snapshot["path"].read_bytes()
    snapshot["path"].chmod(0o400)
    report = await run_import(snapshot, target)
    assert report.sequence_reconciliation_required
    assert len(report.tables) == len(_COLUMNS) + 2
    assert all(audit.count > 0 for audit in report.tables.values())
    assert report.tables["note"].count == 2 and report.tables["chunk_vector"].count == 1
    for table, audit in report.tables.items():
        assert (audit.source_sha256 == audit.imported_sha256) == (
            table not in {"credential", "pairing"}
        )
    assert report.revoked_browser_sessions == 1 and report.expired_pairing_grants == 1
    assert verify_attachment_files(snapshot["files"], report.attachments) == 1
    assert snapshot["path"].read_bytes() == before
    store = await PostgresStore.open(target["database_url"])
    try:
        scoped = store.for_workspace(target["workspace_id"], target["user_id"], "owner")
        assert await tokens.authenticate(scoped, snapshot["device"])
        assert await tokens.authenticate(scoped, snapshot["pat"])
        async with scoped.read() as connection:
            assert (
                dict(await connection.execute_fetchall("SELECT key,value FROM meta"))["sync_epoch"]
                == report.sync_epoch
            )
            browser = await connection.execute_fetchall(
                "SELECT revoked_at FROM credential WHERE id=?", (snapshot["browser_id"],)
            )
            assert browser[0][0] is not None
        async with await psycopg.AsyncConnection.connect(target["admin"]) as connection:
            locators = await (
                await connection.execute(
                    "SELECT user_id FROM public.spunto_credential_locator WHERE workspace_id=%s",
                    (target["workspace_id"],),
                )
            ).fetchall()
            assert locators and all(row[0] == target["user_id"] for row in locators)
        sequence = await reconcile_import_sequences(target["admin"])
        assert sequence["note.rowid"] > 110001 and sequence["job.id"] > 90000
        domain = Notes(
            scoped, tokens.Principal("new", "session", "Synthetic", "full", private_ok=True)
        )
        created = await domain.create("New after migration #brandnew")
        assert created["id"] != snapshot["note_id"]
        async with scoped.write() as connection:
            label = await connection.execute_fetchall("SELECT id FROM label WHERE key='brandnew'")
            assert label[0][0] > 70000
            cursor = await connection.execute(
                "INSERT INTO job(plugin_id,kind,priority,status,run_after,created_at,updated_at) "
                "VALUES('core','test',0,'queued',1,1,1)"
            )
            assert cursor.lastrowid > 90000
    finally:
        await store.close()
    with pytest.raises(OperatorError, match="not empty"):
        await run_import(snapshot, target)


@pytest.mark.anyio
async def test_verification_failure_rolls_back_every_table_and_locator(
    snapshot, target, monkeypatch
):
    original = sqlite_import._verify_table
    close = PostgresStore.close
    closed = []

    async def record_close(store):
        await close(store)
        closed.append(store._pool.closed)

    async def fail(connection, table, expected):
        if table == "meta":
            await connection.execute(
                "UPDATE spunto.meta SET value='injected' WHERE key='sync_epoch'"
            )
        await original(connection, table, expected)

    monkeypatch.setattr(PostgresStore, "close", record_close)
    monkeypatch.setattr(sqlite_import, "_verify_table", fail)
    with pytest.raises(OperatorError, match="digest verification"):
        await run_import(snapshot, target)
    assert closed == [True]
    store = await PostgresStore.open(target["database_url"])
    try:
        async with store.for_workspace(
            target["workspace_id"], target["user_id"], "owner"
        ).read() as connection:
            await sqlite_import._target_is_empty(connection._connection)
        async with await psycopg.AsyncConnection.connect(target["admin"]) as connection:
            assert not await (
                await connection.execute(
                    "SELECT 1 FROM public.spunto_credential_locator WHERE workspace_id=%s",
                    (target["workspace_id"],),
                )
            ).fetchall()
    finally:
        await store.close()


@pytest.mark.anyio
async def test_nonempty_metadata_only_target_refused(snapshot, target):
    store = await PostgresStore.open(target["database_url"])
    try:
        async with store.for_workspace(
            target["workspace_id"], target["user_id"], "owner"
        ).write() as connection:
            await connection.execute("INSERT INTO setting VALUES('only','row')")
        with pytest.raises(OperatorError, match="not empty"):
            await run_import(snapshot, target)
    finally:
        await store.close()


@pytest.mark.parametrize("change", ["newer", "older", "extension", "lookalike", "column"])
def test_unknown_or_changed_schema_refused(snapshot, change):
    with closing(connect(snapshot["path"])) as connection:
        if change in {"newer", "older"}:
            connection.execute(f"PRAGMA user_version={LATEST + (1 if change == 'newer' else -1)}")
        elif change in {"extension", "lookalike"}:
            name = "plugin_private_rows" if change == "extension" else "sqliteXprivate"
            connection.execute(f"CREATE TABLE {name}(data TEXT)")
        else:
            connection.execute("ALTER TABLE note ADD COLUMN unknown TEXT")
        with pytest.raises(OperatorError, match="schema"):
            sqlite_import._validate_source(connection)


def test_attachment_manifest_and_copied_files_fail_closed(snapshot, tmp_path):
    with closing(connect(snapshot["path"])) as connection:
        manifest = sqlite_import._manifest(connection)
        connection.execute("UPDATE attachment SET sha256='../escape'")
        with pytest.raises(OperatorError, match="unsafe"):
            sqlite_import._manifest(connection)
    entry = manifest[0]
    with pytest.raises(OperatorError, match="unsafe"):
        verify_attachment_files(snapshot["files"], (replace(entry, relative_path="../escape"),))
    (snapshot["files"] / entry.relative_path).write_bytes(b"different")
    with pytest.raises(OperatorError, match="checksum"):
        verify_attachment_files(snapshot["files"], manifest)
    external = tmp_path / "external"
    external.write_bytes(b"outside")
    path = snapshot["files"] / entry.relative_path
    path.unlink()
    path.symlink_to(external)
    with pytest.raises(OperatorError, match="unsafe"):
        verify_attachment_files(snapshot["files"], manifest)


@pytest.mark.anyio
@pytest.mark.parametrize("change", ["personal", "owner"])
async def test_target_mapping_requires_personal_owner(snapshot, target, change):
    async with await psycopg.AsyncConnection.connect(target["admin"]) as connection:
        if change == "personal":
            await connection.execute(
                "UPDATE public.spunto_workspace SET personal=false WHERE id=%s",
                (target["workspace_id"],),
            )
        else:
            await connection.execute(
                "UPDATE public.spunto_membership SET role='viewer' WHERE workspace_id=%s",
                (target["workspace_id"],),
            )
    with pytest.raises(OperatorError, match="personal workspace"):
        await run_import(snapshot, target)


@pytest.mark.anyio
async def test_active_wal_is_refused_without_opening_target(snapshot):
    source = snapshot["path"]
    before = source.read_bytes()
    source.with_name(source.name + "-wal").write_bytes(b"uncheckpointed fixture")
    with pytest.raises(OperatorError, match="active WAL"):
        await import_sqlite_snapshot(
            source, "unused", "unused", "unused", identity_database_url="unused"
        )
    assert source.read_bytes() == before


@pytest.mark.anyio
async def test_access_metadata_only_target_refused(snapshot, target):
    async with await psycopg.AsyncConnection.connect(target["admin"]) as connection:
        await connection.execute(
            "INSERT INTO public.spunto_pairing(code_hash,workspace_id,user_id,"
            "credential_id,created_at,expires_at) "
            "VALUES(%s,%s,%s,'historical',1,2)",
            (
                hashlib.sha256(secrets.token_bytes(32)).hexdigest(),
                target["workspace_id"],
                target["user_id"],
            ),
        )
    with pytest.raises(OperatorError, match="not empty"):
        await run_import(snapshot, target)


@pytest.mark.anyio
async def test_negative_zero_embedding_preserves_float32_digest(snapshot, target):
    key = snapshot["key"]
    with closing(connect(snapshot["path"])) as connection:
        connection.execute(f"DELETE FROM chunk_vec_{key}")  # noqa: S608 - fixture SHA-256 key
        connection.execute(
            f"INSERT INTO chunk_vec_{key}(rowid,embedding) VALUES(?,?)",
            (80000, struct.pack("<fff", -0.0, 1.0, 0.0)),
        )
    report = await run_import(snapshot, target)
    vector = report.tables["chunk_vector"]
    assert vector.count == 1
    assert vector.source_sha256 == vector.imported_sha256


@pytest.mark.anyio
async def test_new_wal_during_import_rolls_back_target(snapshot, target, monkeypatch):
    original = sqlite_import._copy
    writer = []

    async def mutate_source(source, connection, vectors, imported_at):
        result = await original(source, connection, vectors, imported_at)
        before = sqlite_import._file_hash(snapshot["path"])
        external = connect(snapshot["path"])
        writer.append(external)
        external.execute("UPDATE setting SET value='changed' WHERE key='theme'")
        assert sqlite_import._file_hash(snapshot["path"]) == before
        assert snapshot["path"].with_name(snapshot["path"].name + "-wal").stat().st_size > 0
        return result

    monkeypatch.setattr(sqlite_import, "_copy", mutate_source)
    try:
        with pytest.raises(OperatorError, match="changed during import"):
            await run_import(snapshot, target)
    finally:
        for external in writer:
            external.close()
    store = await PostgresStore.open(target["database_url"])
    try:
        async with store.for_workspace(
            target["workspace_id"], target["user_id"], "owner"
        ).read() as connection:
            await sqlite_import._target_is_empty(connection._connection)
    finally:
        await store.close()


@pytest.mark.anyio
@pytest.mark.parametrize("change", ["mtime", "inode"])
async def test_source_metadata_change_rolls_back_import(snapshot, target, monkeypatch, change):
    original = sqlite_import._copy

    async def mutate_source(source, connection, vectors, imported_at):
        result = await original(source, connection, vectors, imported_at)
        path = snapshot["path"]
        digest = sqlite_import._file_hash(path)
        if change == "mtime":
            info = path.stat()
            os.utime(path, ns=(info.st_atime_ns, info.st_mtime_ns + 1_000_000))
        else:
            data = path.read_bytes()
            path.rename(path.with_suffix(".prior"))
            path.write_bytes(data)
        assert sqlite_import._file_hash(path) == digest
        return result

    monkeypatch.setattr(sqlite_import, "_copy", mutate_source)
    with pytest.raises(OperatorError, match="changed during import"):
        await run_import(snapshot, target)
