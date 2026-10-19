"""Real-SQLite harness and deterministic clock for plugin contract tests."""

from contextlib import asynccontextmanager
from dataclasses import dataclass

from spunto.host.runtime import Host
from spunto.store.db import Store
from spunto.store.migrations import migrate


@dataclass
class FakeClock:
    now: int = 1800000000000

    def __call__(self):
        return self.now

    def advance(self, milliseconds):
        self.now += milliseconds


@asynccontextmanager
async def harness(directory, settings, *, catalog=None, **kwargs):
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "spunto.db"
    migrate(path, directory / "snapshots")
    store = await Store.open(path)
    host = Host(store, settings, catalog=catalog, **kwargs)
    try:
        await host.discover()
        yield host
    finally:
        await host.close()
        await store.close()
        if host.loop_violations:
            raise AssertionError(
                "Plugin blocked the event loop: " + ", ".join(sorted(host.loop_violations))
            )
