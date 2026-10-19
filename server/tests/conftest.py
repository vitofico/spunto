import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path

import httpx
import pytest

from spunto.api.deps import Services
from spunto.config import Settings, load_settings
from spunto.main import create_app, startup

PUBLIC_URL = "http://spunto.test:8080"
CLIENT = "203.0.113.7"  # a documentation address, standing in for a browser on the LAN


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


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
