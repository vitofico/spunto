import asyncio
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from pathlib import Path

import httpx
import psycopg
import pytest

from spunto.api.deps import Services
from spunto.config import Settings, load_settings
from spunto.main import create_app, startup

PUBLIC_URL = "http://spunto.test:8080"
CLIENT = "203.0.113.7"  # a documentation address, standing in for a browser on the LAN


SYNTHETIC_ISSUER = "http://127.0.0.1:18082"


@dataclass(eq=False)
class HostedRows:
    admin: str | None = field(default=None, repr=False)
    subjects: list[str] = field(default_factory=list)
    receipts: list[tuple[str, str]] = field(default_factory=list)

    async def remove(self, conn):
        if self.receipts:
            receipt_ids, account_ids = zip(*self.receipts, strict=True)
            await conn.execute(
                "DELETE FROM public.spunto_account_closure AS receipt "
                "USING unnest(%s::text[],%s::text[]) AS owned(id,user_id) "
                "WHERE receipt.id=owned.id AND receipt.user_id=owned.user_id",
                (list(receipt_ids), list(account_ids)),
            )
        if self.subjects:
            owners = (SYNTHETIC_ISSUER, self.subjects)
            await conn.execute(
                "DELETE FROM public.spunto_workspace WHERE created_by IN ("
                "SELECT id FROM public.spunto_account WHERE issuer=%s AND subject=ANY(%s))",
                owners,
            )
            await conn.execute(
                "DELETE FROM public.spunto_account WHERE issuer=%s AND subject=ANY(%s)", owners
            )

    async def clean(self):
        if self.admin and (self.subjects or self.receipts):
            async with await psycopg.AsyncConnection.connect(self.admin) as conn:
                await self.remove(conn)


@pytest.fixture(scope="session")
def pending_hosted_rows():
    pending = []
    yield pending
    # Fallback for setup/teardown failures. Entries leave this list only after commit.
    if pending:

        async def clean_pending():
            for rows in pending:
                await rows.clean()

        asyncio.run(clean_pending())


@pytest.fixture
def hosted_rows():
    return HostedRows()


@pytest.hookimpl(tryfirst=True)
def pytest_collection_modifyitems(items):
    required = {"pg", "pg_admin_url", "hosted", "browsers", "operator_accounts"}
    missing = [
        item.nodeid
        for item in items
        if (
            required.intersection(item.fixturenames)
            or (item.path.name == "test_sqlite_import.py" and "target" in item.fixturenames)
        )
        and item.get_closest_marker("postgres") is None
    ]
    if missing:
        raise pytest.UsageError("Missing @pytest.mark.postgres:\n" + "\n".join(missing))


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@pytest.fixture
def pg_admin_url() -> str | None:
    return os.environ.get("SPUNTO_TEST_POSTGRES_ADMIN_URL")


@pytest.fixture
def data_dir(tmp_path: Path) -> Path:
    return tmp_path / "data"


@pytest.fixture
def env(monkeypatch: pytest.MonkeyPatch, data_dir: Path):
    """A clean environment for one test; call the result to set more variables."""
    for name in list(os.environ):
        if name.startswith("SPUNTO_") or name == "TZ":
            monkeypatch.delenv(name)
    monkeypatch.setenv("SPUNTO_PUBLIC_URL", PUBLIC_URL)
    monkeypatch.setenv("SPUNTO_DATA_DIR", str(data_dir))

    def set_env(**variables: str) -> None:
        for name, value in variables.items():
            monkeypatch.setenv(name, value)

    return set_env


@pytest.fixture
def settings(env) -> Settings:
    return load_settings()


@dataclass
class Running:
    services: Services
    client: httpx.AsyncClient


@asynccontextmanager
async def run_server(client_address: str = CLIENT) -> AsyncIterator[Running]:
    """Start the server from the current environment and talk to it at its public URL."""
    settings = load_settings()
    async with startup(settings) as services:
        transport = httpx.ASGITransport(create_app(services), client=(client_address, 50000))
        async with httpx.AsyncClient(transport=transport, base_url=settings.public_url) as client:
            yield Running(services, client)


@pytest.fixture
async def server(env) -> AsyncIterator[Running]:
    async with run_server() as running:
        yield running
