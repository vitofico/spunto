"""The deliberately small async plugin registration and context API."""

import asyncio
import logging
import re
from functools import partial
from inspect import iscoroutinefunction

from spunto.host.manifest import ManifestError


class Plugin:
    def __init__(self, plugin_id: str):
        self.id = plugin_id
        self.handlers = {
            k: {}
            for k in (
                "hook",
                "job",
                "schedule",
                "command",
                "route",
                "tool",
                "migrate",
                "panel",
                "search_source",
            )
        }
        self.route_options = {}

    def _register(self, kind, key):
        def decorate(handler):
            if not iscoroutinefunction(handler) or key in self.handlers[kind]:
                raise ManifestError("plugin handlers must be unique async functions")
            self.handlers[kind][key] = handler
            return handler

        return decorate

    def on(self, event):
        if event not in {
            "note.saved",
            "note.trashed",
            "note.purged",
            "labels.changed",
            "suggestion.decided",
            "attachment.added",
            "plugin.enabled",
        }:
            raise ManifestError("unknown hook")
        return self._register("hook", event)

    def job(self, name):
        return self._register("job", name)

    def schedule(self, name):
        return self._register("schedule", name)

    def command(self, name):
        return self._register("command", name)

    def mcp_tool(self, name):
        return self._register("tool", name)

    def panel(self, name="panel"):
        return self._register("panel", name)

    def search_source(self, name="search"):
        return self._register("search_source", name)

    def route(self, path, *, method="GET", allow_tokens=False, write=False):
        if path.startswith("/"):
            path = path[1:]
        if ".." in path or "?" in path or method not in {"GET", "POST", "PUT", "DELETE", "PATCH"}:
            raise ManifestError("invalid route")
        key = (method, path)
        self.route_options[key] = {"allow_tokens": allow_tokens, "write": write or method != "GET"}
        return self._register("route", key)

    def migrate(self, old, new):
        return self._register("migrate", (old, new))


class ContextError(PermissionError):
    """A denied call or a handle from a previous enable generation."""


class Ctx:
    def __init__(
        self,
        host,
        plugin_id,
        *,
        principal=None,
        unattended=False,
        migration=False,
        capture_note_id=None,
        operation=None,
    ):
        self._host, self.plugin_id = host, plugin_id
        self.generation = host.generations[plugin_id]
        self.principal, self.unattended, self.migration = principal, unattended, migration
        self._borrowed_notes = {}
        self.capture_note_id, self.operation = capture_note_id, operation

    def check(self, permission=None):
        state = self._host.states[self.plugin_id]
        if (
            state["switch"] != "on"
            or self.generation != self._host.generations[self.plugin_id]
            or (
                state["state"] != "enabled" and not (self.migration and state["state"] == "waiting")
            )
        ):
            raise ContextError("plugin_disabled")
        if permission and (
            permission not in state["granted"]
            or permission not in self._host.manifests[self.plugin_id]["permissions"]
        ):
            raise ContextError("permission_denied")

    def check_write(self):
        self.check()
        if self.principal is not None and not self.principal.writable:
            raise ContextError("write_scope_required")

    @property
    def notes(self):
        self.check()
        return self._host.notes_factory(self)

    @property
    def capture(self):
        from spunto.core.plugin_capture import ScopedCapture

        self.check()
        return ScopedCapture(self)

    @property
    def ai(self):
        self.check()
        if self._host.gateway is None:
            raise ContextError("ai_unavailable")
        return self._host.gateway.for_context(self)

    @property
    def vectors(self):
        from spunto.host.vectors import ScopedVectors

        self.check("ai.embed")
        return ScopedVectors(self)

    @property
    def attachments(self):
        self.check()
        return self._host.attachments_factory(self)

    @property
    def storage(self):
        from spunto.host.storage import Storage

        self.check()
        return Storage(self)

    @property
    def settings(self):
        self.check()
        return self._host.context_settings(self.plugin_id)

    @property
    def zone(self):
        self.check()
        return self._host.settings.zone

    def clock(self):
        self.check()
        return self._host.clock()

    async def to_thread(self, fn, *args, **kwargs):
        self.check()
        result = await asyncio.get_running_loop().run_in_executor(
            self._host.pools[self.plugin_id], partial(fn, *args, **kwargs)
        )
        self.check()
        return result

    async def enqueue(self, name, args=None, *, note_id=None, delay_ms=0, priority=0):
        self.check_write()
        if self.principal is not None and not self.principal.owner:
            raise ContextError("agent_jobs_unavailable")
        return await self._host.enqueue(
            self.plugin_id,
            name,
            args,
            note_id=note_id,
            unattended=self.unattended,
            delay_ms=delay_ms,
            priority=priority,
        )

    async def fetch(self, url, **kwargs):
        self.check()
        if not any(
            p == "net.fetch" or p.startswith("net.out:")
            for p in self._host.states[self.plugin_id]["granted"]
        ):
            raise ContextError("permission_denied")
        result = await self._host.fetcher(self, url, **kwargs)
        self.check()
        return result

    async def imap_read(self, url, **kwargs):
        self.check()
        result = await self._host._imap_read(self, url, **kwargs)
        self.check()
        return result

    async def report_status(self, code=None):
        self.check_write()
        if code is not None and (
            not isinstance(code, str) or not re.fullmatch(r"[a-z][a-z0-9_]{0,79}", code)
        ):
            raise ValueError("status must be a stable lowercase code")
        row = self._host.states[self.plugin_id]
        if row["last_error"] != code:
            await self._host._state(self.plugin_id, row["state"], code, generation=self.generation)

    def log(self, code, **counts):
        self.check()
        if not code.replace("_", "").isalnum() or any(type(v) is not int for v in counts.values()):
            raise ValueError("plugin logs accept only codes and integer counts")
        logging.getLogger("spunto.plugin").info(
            "plugin=%s code=%s counts=%s", self.plugin_id, code, counts
        )
