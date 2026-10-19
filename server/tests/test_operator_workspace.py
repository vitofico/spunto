"""Stopped-service imports use one existing owner and real PostgreSQL RLS."""

import io
import os
import uuid
import zipfile

import psycopg
import pytest

from spunto.api.errors import Problem
from spunto.auth.accounts import Accounts
from spunto.auth.oidc import OIDCIdentity
from spunto.auth.tokens import Principal
from spunto.config import Settings
from spunto.core.notes import Notes
from spunto.errors import OperatorError
from spunto.host import Ctx
from spunto.operator_workspace import scoped_operator_services
from spunto.plugins.markdown_import import read_zip, run_import

pytestmark = pytest.mark.anyio


@pytest.fixture
async def operator_accounts(tmp_path):
    runtime = os.environ.get("SPUNTO_TEST_POSTGRES_URL")
    identity = os.environ.get("SPUNTO_TEST_IDENTITY_URL")
    admin = os.environ.get("SPUNTO_TEST_POSTGRES_ADMIN_URL")
    if not all((runtime, identity, admin)):
        pytest.skip("Set the three disposable PostgreSQL test URLs")
    settings = Settings(
        public_url="http://127.0.0.1:18081",
        data_dir=tmp_path,
        database_url=runtime,
        identity_database_url=identity,
        mcp_enabled=False,
    )
    accounts = await Accounts.open(identity)
    users = []
    try:
        for name in ("Operator fixture Alice", "Operator fixture Bob"):
            account, workspace = await accounts.sign_in(
                OIDCIdentity(
                    "https://operator.synthetic.invalid", uuid.uuid4().hex, None, False, name
                )
            )
            users.append((account, workspace))
        yield settings, accounts, users
    finally:
        await accounts.close()
        async with await psycopg.AsyncConnection.connect(admin) as conn:
            for account, _ in users:
                await conn.execute(
                    "DELETE FROM public.spunto_workspace WHERE created_by=%s", (account["id"],)
                )
            for account, _ in users:
                await conn.execute(
                    "DELETE FROM public.spunto_account WHERE id=%s", (account["id"],)
                )


async def test_operator_markdown_import_preserves_scope_files_times_and_owner_edits(
    operator_accounts,
):
    settings, accounts, users = operator_accounts
    alice, a_space = users[0]
    bob, b_space = users[1]
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as source:
        source.writestr(
            "first.md",
            "---\ncreated: 2020-10-14T10:11:12.123Z\nupdated: 2025-02-03T12:00:00Z\n"
            "pinned: true\narchived: true\n---\n"
            "Synthetic #project/spunto words [file](example.txt)",
        )
        source.writestr("second.md", "Synthetic second #retained note")
        source.writestr("example.txt", b"fixture")
    source = read_zip(archive.getvalue())
    async with scoped_operator_services(settings, alice["id"], a_space["id"]) as services:
        assert services.host._worker is None
        assert not services.host.core_schedules
        assert services.settings.data_dir == settings.data_dir / "workspaces" / a_space["id"]
        host = services.host
        await host.enable(
            "markdown-import", permissions=host.manifests["markdown-import"]["permissions"]
        )
        ctx = Ctx(host, "markdown-import")
        assert (await run_import(ctx, source))["notes"] == 2
        async with services.store.read() as conn:
            assert (await conn.execute_fetchall("SELECT count(*) FROM note"))[0][0] == 0
        imported = await run_import(ctx, source, dry_run=False)
        assert imported["created"] == 2 and imported["failed_notes"] == 0
        assert (await run_import(ctx, source, dry_run=False))["unchanged"] == 2
        async with services.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT id,body,created_at,updated_at,pinned,archived_at,import_key,version "
                "FROM note ORDER BY import_key"
            )
        assert len(rows) == 2 and rows[0]["import_key"] == "file:first.md"
        assert rows[0]["created_at"] == 1602670272123
        assert rows[0]["updated_at"] == 1738584000000
        assert rows[0]["pinned"] and rows[0]["archived_at"]
        assert "Synthetic #project/spunto words" in rows[0]["body"]
        assert "/a/" in rows[0]["body"]
        files = [p for p in services.settings.attachments_dir.rglob("*") if p.is_file()]
        assert len(files) == 1 and files[0].read_bytes() == b"fixture"
        owner = Notes(
            services.store, Principal(alice["id"], "session", "Alice", "full", private_ok=True)
        )
        await owner.edit(rows[0]["id"], "Owner edit #retained", rows[0]["version"])
        again = await run_import(ctx, source, dry_run=False)
        assert again["skipped_owner_edits"] == 1 and again["unchanged"] == 1
        other = services.store._root.for_workspace(b_space["id"], bob["id"], "owner")
        async with other.read() as conn:
            for query in (
                "SELECT count(*) FROM note",
                "SELECT count(*) FROM meta",
                "SELECT count(*) FROM plugin",
                "SELECT count(*) FROM credential",
            ):
                assert (await conn.execute_fetchall(query))[0][0] == 0
        assert not (settings.data_dir / "workspaces" / b_space["id"]).exists()
        session_count = await accounts.one(
            "SELECT count(*) AS n FROM public.spunto_login_session WHERE user_id=ANY(%s)",
            ([alice["id"], bob["id"]],),
        )
        assert session_count["n"] == 0


async def test_operator_import_refuses_foreign_workspace_and_editor(operator_accounts):
    settings, accounts, users = operator_accounts
    alice, a_space = users[0]
    bob, _ = users[1]
    with pytest.raises(Problem, match="unavailable"):
        async with scoped_operator_services(settings, bob["id"], a_space["id"]):
            pytest.fail("Another account's workspace must not be opened")
    async with accounts.transaction() as conn:
        await conn.execute(
            "INSERT INTO public.spunto_membership(workspace_id,user_id,role) "
            "VALUES(%s,%s,'editor')",
            (a_space["id"], bob["id"]),
        )
    with pytest.raises(OperatorError, match="existing owner"):
        async with scoped_operator_services(settings, bob["id"], a_space["id"]):
            pytest.fail("Editor membership must not enable operator imports")


async def test_operator_import_rejects_path_identifiers_before_opening_database():
    with pytest.raises(OperatorError, match="exact account"):
        async with scoped_operator_services(None, "../outside", str(uuid.uuid4())):
            pytest.fail("Unsafe identifiers must fail before accessing connection settings")
