"""The startup sequence and the app factory (02 §10.2)."""

import asyncio
import contextlib
import logging
from collections.abc import AsyncIterator

from fastapi import FastAPI

from spunto import __version__
from spunto.ai.gateway import Gateway
from spunto.ai.vectors import VectorIndex
from spunto.api import (
    access,
    ai,
    attachments,
    auth,
    errors,
    health,
    imports,
    meta,
    notes,
    plugins,
    reference,
    slip,
    suggestions,
    sync,
    themes,
    web,
)
from spunto.api.deps import Services
from spunto.api.network import SecurityHeadersMiddleware, WrongHostMiddleware
from spunto.auth import owner
from spunto.config import OperatorError, Settings
from spunto.core.attachments import Attachments
from spunto.core.plugin_notes import PluginNotes
from spunto.host import Host
from spunto.jobs import backup, maintenance
from spunto.mcp import MCP
from spunto.store.db import DB_NAME, Store
from spunto.store.lock import DataLock
from spunto.store.migrations import LATEST, migrate

log = logging.getLogger(__name__)


@contextlib.asynccontextmanager
async def startup(settings: Settings) -> AsyncIterator[Services]:
    """Steps 2 to 6 of the startup sequence; the caller has validated settings and then serves.

    Each step raises OperatorError with a sentence for the operator, so a bad
    start stops before anything listens. Steps 4 and 5 (AI profiles, plugins)
    arrive with their milestones.
    """
    if (settings.data_dir / "RESTORE-QUARANTINE.json").exists():
        raise OperatorError("Restored data is quarantined pending account deletion reconciliation.")
    if settings.database_url:
        from spunto.hosted import hosted_startup

        async with hosted_startup(settings) as services:
            yield services
        return
    log.info("Spunto %s starting", __version__)
    with DataLock(settings.data_dir):
        db_path = settings.data_dir / DB_NAME
        previous = migrate(db_path, settings.backup_dir / backup.PRE_MIGRATION)
        if previous != LATEST:
            log.info("Database migrated from schema %d to %d", previous, LATEST)
        store = await Store.open(db_path)
        try:
            await owner.apply_environment(store, settings)
            services = Services(settings, store)
            if await owner.password_hash(store) is None:
                services.setup_code = owner.new_setup_code()
                log.warning(
                    "No owner yet. One-time setup code: %s (it changes at every start). "
                    "Exchange it for the owner password with POST %s/api/v1/setup.",
                    services.setup_code,
                    settings.public_url,
                )
            if not settings.secure:
                log.warning(
                    "SPUNTO_PUBLIC_URL is plain HTTP, so passwords and notes cross the network "
                    "unencrypted. Fine on your own network; put HTTPS in front before using it "
                    "anywhere else."
                )
            services.ai = Gateway(store, settings)
            await services.ai.selection.load()
            services.vectors = VectorIndex(store, services.ai)
            services.host = Host(
                store,
                settings,
                notes_factory=PluginNotes,
                attachments_factory=Attachments,
                gateway=services.ai,
                vectors=services.vectors,
            )
            await services.host.discover()
            store.plugin_host = services.host
            store.event_sink = services.host.emit

            async def daily_backup():
                await asyncio.to_thread(backup.backup_now, settings)

            async def daily_retention():
                await maintenance.prune(store, settings)

            services.host.register_core(
                "backup", daily_backup, at=settings.backup_time.strftime("%H:%M")
            )
            services.host.register_core("purge", daily_retention, at="04:00")
            await services.host.start()

            async def check_ai():
                try:
                    await services.ai.health_check()
                    await services.host.refresh_ai()
                except Exception:
                    log.warning("code=ai_startup_check_unavailable")

            ai_check = asyncio.create_task(check_ai(), name="ai-startup-check")
            services.mcp = MCP(services) if settings.mcp_enabled else None
            async with contextlib.AsyncExitStack() as stack:
                if services.mcp is not None:
                    await stack.enter_async_context(services.mcp.manager.run())
                try:
                    yield services
                finally:
                    ai_check.cancel()
                    await asyncio.gather(ai_check, return_exceptions=True)
                    await store.drain_events()
                    store.event_sink = None
                    await services.host.close()
        finally:
            if "services" in locals() and services.host is not None:
                store.event_sink = None
                await services.host.close()
            if "services" in locals() and services.ai is not None:
                await services.ai.close()
            await store.close()
    log.info("Spunto stopped")


def create_app(services: Services) -> FastAPI:
    if services.hosted is not None:
        from spunto.hosted import create_hosted_app

        return create_hosted_app(services)
    app = FastAPI(
        title="Spunto",
        version=__version__,
        openapi_url="/api/v1/openapi.json",
        docs_url=None,
        redoc_url=None,
    )
    app.state.services = services
    errors.install(app)
    for module in (
        health,
        meta,
        auth,
        access,
        ai,
        notes,
        attachments,
        imports,
        plugins,
        reference,
        suggestions,
        sync,
        slip,
        themes,
    ):
        app.include_router(module.router)
    if services.mcp is not None:
        app.add_route("/mcp", services.mcp.app, methods=["POST", "GET", "DELETE", "OPTIONS"])
    app.include_router(web.router)
    app.add_middleware(SecurityHeadersMiddleware)
    app.add_middleware(WrongHostMiddleware, settings=services.settings)
    return app
