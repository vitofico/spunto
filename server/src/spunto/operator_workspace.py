"""Explicit owner scope for stopped-service PostgreSQL operator commands."""

import uuid
from contextlib import AsyncExitStack, asynccontextmanager

from spunto.ai.gateway import Gateway
from spunto.ai.vectors import VectorIndex
from spunto.api.deps import Services
from spunto.auth.accounts import Accounts
from spunto.core.attachments import Attachments
from spunto.core.plugin_notes import PluginNotes
from spunto.errors import OperatorError
from spunto.host import Host
from spunto.store.postgres import PostgresStore


def _identifier(value):
    try:
        parsed = uuid.UUID(value)
        if value in (str(parsed), parsed.hex):
            return value
    except ValueError, TypeError, AttributeError:
        pass
    raise OperatorError("Provide an exact account ID and workspace ID.")


@asynccontextmanager
async def scoped_operator_services(settings, user_id, workspace_id):
    """The operator must stop the service before invoking this bounded host.

    This opens no browser session, starts no worker or provider health checks,
    and never enumerates other workspaces. Imported-note events remain queued
    for the application to process when the operator resumes it.
    """
    user_id, workspace_id = _identifier(user_id), _identifier(workspace_id)
    if not settings.database_url or not settings.identity_database_url:
        raise OperatorError("Workspace operator commands require PostgreSQL connection files.")
    async with AsyncExitStack() as resources:
        root = await PostgresStore.open(settings.database_url)
        resources.push_async_callback(root.close)
        accounts = await Accounts.open(settings.identity_database_url)
        resources.push_async_callback(accounts.close)
        workspace = await accounts.workspace(user_id, workspace_id)
        if workspace["role"] != "owner":
            raise OperatorError("Workspace operator commands require its existing owner.")
        store = root.for_workspace(workspace_id, user_id, "owner")
        await store.initialize_workspace()
        data_dir = settings.data_dir / "workspaces" / workspace_id
        attachments_dir = settings.attachments_dir / workspace_id
        data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        attachments_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        scoped = settings.model_copy(
            update={
                "data_dir": data_dir,
                "attachments_dir": attachments_dir,
                "backup_dir": settings.backup_dir / workspace_id,
            }
        )
        gateway = Gateway(store, scoped)
        await gateway.selection.load()
        resources.push_async_callback(gateway.close)
        vectors = VectorIndex(store, gateway)
        host = Host(
            store,
            scoped,
            notes_factory=PluginNotes,
            attachments_factory=Attachments,
            gateway=gateway,
            vectors=vectors,
        )
        resources.push_async_callback(host.close)

        async def finish_events():
            try:
                await store.drain_events()
            finally:
                store.event_sink = store.plugin_host = None

        resources.push_async_callback(finish_events)
        await host.discover()
        store.plugin_host, store.event_sink = host, host.emit
        yield Services(scoped, store, host=host, ai=gateway, vectors=vectors)
