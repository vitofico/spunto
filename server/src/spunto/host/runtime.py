"""First-party lifecycle, persistent coalesced jobs, and sleeping scheduler."""

import asyncio
import copy
import hashlib
import importlib
import inspect
import json
import logging
import os
import re
import sqlite3
import time
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from functools import wraps
from pathlib import Path
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

from spunto.ai.errors import AIError
from spunto.host.manifest import ManifestError, read_manifest, settings_values, validate_manifest
from spunto.host.plugin import ContextError, Ctx, Plugin
from spunto.host.vocabulary import render_output, validate_output
from spunto.store.db import now_ms

_PG_LEASE_POLL_SECONDS = 30
_LOOP_BLOCK_BUDGET_SECONDS = 0.1


def _log_failure(plugin_id, operation, error):
    # Messages/tracebacks can contain SQL parameters, provider bodies or notes.
    sqlstate = getattr(error, "sqlstate", None)
    sqlstate = (
        sqlstate if isinstance(sqlstate, str) and re.fullmatch(r"[0-9A-Z]{5}", sqlstate) else "-"
    )
    logging.getLogger("spunto.plugin").warning(
        "plugin=%s operation=%s code=plugin_call_failed error=%s sqlstate=%s",
        plugin_id,
        operation,
        type(error).__name__,
        sqlstate,
    )


CATALOG_FIELDS = {
    "id",
    "name",
    "version",
    "api",
    "kind",
    "description",
    "permissions",
    "category",
    "creates",
}


def _catalog_snapshot(manifest):
    return {key: copy.deepcopy(value) for key, value in manifest.items() if key in CATALOG_FIELDS}


def _retained_manifest(row):
    # Revalidate the closed display-only snapshot. Never resurrect code, settings or surfaces.
    try:
        snapshot = json.loads(row["manifest_snapshot"])
        if not isinstance(snapshot, dict) or set(snapshot) - CATALOG_FIELDS:
            raise ManifestError("invalid retained catalog metadata")
        snapshot = validate_manifest(snapshot)
        if snapshot["id"] != row["id"]:
            raise ManifestError("retained catalog identity mismatch")
        return snapshot
    except ManifestError, TypeError, ValueError:
        return {
            "id": row["id"],
            "name": "Removed plugin",
            "version": "0",
            "api": "1.0",
            "kind": "server",
            "description": "Plugin code is no longer installed.",
            "permissions": [],
        }


def _network_hostname(value):
    try:
        if not isinstance(value, str) or any(ord(c) <= 32 or ord(c) == 127 for c in value):
            return None
        url = urlsplit(value)
        if (
            url.scheme not in {"https", "http", "imaps"}
            or not url.hostname
            or url.username is not None
            or (url.port is not None and not 1 <= url.port <= 65535)
        ):
            return None
        return url.hostname
    except ValueError:
        return None


def _needs_reason(manifest, configured):
    for item in manifest.get("needs", []):
        value = configured.get(item["setting"])
        if not value or (
            item["setting"] == "url"
            and "net.out:<configured>" in manifest["permissions"]
            and not _network_hostname(value)
        ):
            return item["reason"]
    return None


def serialized(method):
    """One lifecycle mutation at a time for each plugin."""

    @wraps(method)
    async def invoke(self, plugin_id, *args, **kwargs):
        async with self.lifecycle_locks.setdefault(plugin_id, asyncio.Lock()):
            return await method(self, plugin_id, *args, **kwargs)

    return invoke


class Host:
    def __init__(
        self,
        store,
        settings,
        *,
        notes_factory=None,
        attachments_factory=None,
        fetcher=None,
        clock=now_ms,
        catalog=None,
        gateway=None,
        vectors=None,
    ):
        self.store, self.settings, self.clock = store, settings, clock
        self.notes_factory, self.attachments_factory = notes_factory, attachments_factory
        self.fetcher = fetcher or self._fetch
        self._catalog = catalog
        self.gateway, self.vectors = gateway, vectors
        self.invalid_manifests = set()
        self.plugin_sources = {}
        self.discovery_errors = {}
        from spunto.host.themes import ThemeRegistry

        self.themes = ThemeRegistry(settings)
        self.lifecycle_locks = {}
        self.manifests, self.states, self.plugins = {}, {}, {}
        self.removed_manifests, self.catalog_metadata = {}, {}
        self.generations, self.pools, self.running, self.failures = {}, {}, {}, {}
        self._state_tokens = {}
        self.claim_receipts = {}
        self.plugins_rev = 0
        self.loop_violations = set()
        self._wake = asyncio.Event()
        self._model_selection_epoch = 0
        self._model_selection_refreshed = 0
        self._model_refresh_lock = asyncio.Lock()
        self._ai_refresh_lock = asyncio.Lock()
        if selection := getattr(gateway, "selection", None):
            selection.on_change = self._model_selection_changed
        self._worker = None
        self._closed = False
        self.core_handlers = {"reindex": self._core_reindex}
        self.core_schedules = {}
        if vectors is not None:
            vectors.enqueue = self._enqueue_embed
            self.register_core("embed", vectors.index_note)
            self.register_core("ai-routes", self._sync_vectors)

    async def _enqueue_embed(self, note_id, *, priority=0):
        await self.enqueue_core("embed", {"note_id": note_id}, note_id=note_id, priority=priority)

    def _vector_routes(self):
        if self.vectors is None:
            return {}
        routes = {}
        for plugin_id, state in self.states.items():
            if (
                state["state"] == "enabled"
                and "ai.embed" in self.manifests[plugin_id]["permissions"]
            ):
                try:
                    profile = self.gateway.profile_for("embed", state.get("ai_route"))
                    routes[plugin_id] = profile.name
                except AIError:
                    continue  # noqa: S112 - an optional embedding binding may be absent
        return routes

    def _set_vector_routes(self):
        return self.vectors is not None and self.vectors.set_routes(self._vector_routes())

    async def _queue_vector_routes(self, *, force=False):
        if self.vectors is not None and (self._set_vector_routes() or force):
            await self.enqueue_core("ai-routes", priority=5)

    async def _sync_vectors(self):
        if self.vectors is None:
            return
        self._set_vector_routes()
        from spunto.ai.vectors import VectorUnavailable

        try:
            await self.vectors.prepare_routes()
        except VectorUnavailable:
            # A concurrent enable/disable or health refresh superseded this generation.
            await self.enqueue_core("ai-routes", priority=5)

    async def refresh_ai(self):
        async with self._ai_refresh_lock:
            await self._refresh_ai()

    async def _refresh_ai(self):
        for plugin_id, state in self.states.items():
            if state["state"] == "enabled" and not self._requirements_met(
                self.manifests[plugin_id]
            ):
                self.generations[plugin_id] += 1
                await self._state(plugin_id, "waiting")
            if state["switch"] == "on" and state["state"] == "waiting":
                await self._activate(plugin_id)
        await self._queue_vector_routes(force=True)

    def _model_selection_changed(self):
        self._model_selection_epoch += 1
        if self.vectors is not None:
            self.vectors.set_routes(self.vectors.routes, force=True)
        self._wake.set()

    async def refresh_models(self, *, changed=False):
        async with self._model_refresh_lock:
            await self._refresh_models(changed=changed)

    async def _refresh_models(self, *, changed=False):
        selection = getattr(self.gateway, "selection", None)
        loaded = await selection.load() if selection else False
        epoch = self._model_selection_epoch
        if changed or loaded or epoch != self._model_selection_refreshed:
            try:
                await self.refresh_ai()
            except ContextError as exc:
                if str(exc) != "plugin_disabled":
                    raise
                # A newer owner action superseded activation. Keep the worker
                # alive and reconcile again without reviving that generation.
                self._model_selection_epoch += 1
                self._wake.set()
                return
            async with self.store.write() as conn:
                await self._bump(conn)
            # Cancellation/failure leaves work pending; a newer callback cannot
            # be acknowledged by an older refresh finishing after it.
            self._model_selection_refreshed = max(self._model_selection_refreshed, epoch)

    def register_core(self, name, callback, *, at=None):
        from inspect import iscoroutinefunction

        if not iscoroutinefunction(callback) or name in self.core_handlers:
            raise ValueError("core jobs need unique async callbacks")
        if at is not None:
            self._scheduled_slot({"time": at}, {}, self.clock())
            self.core_schedules[name] = {"time": at}
        self.core_handlers[name] = callback
        self._wake.set()

    async def enqueue_core(self, name, args=None, *, note_id=None, priority=5):
        if name not in self.core_handlers:
            raise KeyError("unknown core job")
        return await self._queue("core", name, args or {}, note_id=note_id, priority=priority)

    async def _core_reindex(self, note_id):
        async with self.store.write() as conn:
            await self._reindex(conn, note_id)

    async def _fetch(self, ctx, url, **kwargs):
        from spunto.net import guarded_fetch

        if set(kwargs) - {"method", "headers", "body", "max_bytes", "timeout", "pre_send"}:
            raise ContextError("fetch_option_denied")
        if kwargs.get("pre_send") is not None and not callable(kwargs["pre_send"]):
            raise ContextError("fetch_option_denied")

        permissions = list(
            set(self.states[ctx.plugin_id]["granted"])
            & set(self.manifests[ctx.plugin_id]["permissions"])
        )
        configured = []
        allowed_origins = None
        if "net.out:<configured>" in permissions:
            urls = ctx.settings.get("urls", [])
            if (
                not isinstance(urls, list)
                or len(urls) > 10
                or any(not isinstance(u, str) for u in urls)
            ):
                raise ContextError("network_destination_unconfigured")
            legacy = ctx.settings.get("url")
            if legacy:
                urls = [*urls, legacy]
            allowed_origins = list(dict.fromkeys(urls))
            if len(allowed_origins) > 10:
                raise ContextError("network_destination_unconfigured")
            configured = [_network_hostname(value) for value in allowed_origins]
            if not configured or not all(configured):
                raise ContextError("network_destination_unconfigured")
            permissions.remove("net.out:<configured>")
            permissions.extend("net.out:" + hostname for hostname in configured)
        destinations = []
        try:
            return await guarded_fetch(
                url,
                permissions=permissions,
                check=ctx.check,
                configured_hosts=configured,
                allowed_origins=allowed_origins,
                own_hosts=[urlsplit(self.settings.public_url).hostname]
                if getattr(self.settings, "public_url", None)
                else [],
                on_destination=destinations.append,
                **kwargs,
            )
        finally:
            if destinations:
                async with self.store.write() as conn:
                    for host, count in Counter(destinations).items():
                        await conn.execute(
                            "INSERT INTO plugin_kv(plugin_id,key,value) VALUES(?,?,?) "
                            "ON CONFLICT(plugin_id,key) DO UPDATE SET "
                            "value=CAST(CAST(value AS INTEGER)+CAST(excluded.value AS INTEGER) "
                            "AS TEXT)",
                            (ctx.plugin_id, "host:fetch:" + host, str(count)),
                        )

    async def _imap_read(self, ctx, url, **kwargs):
        from spunto.net.imap import guarded_read

        if set(kwargs) - {"username", "password", "folder", "after_uid", "uidvalidity", "limit"}:
            raise ContextError("imap_option_denied")
        granted = set(self.states[ctx.plugin_id]["granted"]) & set(
            self.manifests[ctx.plugin_id]["permissions"]
        )
        hostname = _network_hostname(url)
        if (
            not hostname
            or ("net.out:<configured>" not in granted and f"net.out:{hostname}" not in granted)
            or ctx.settings.get("url") != url
        ):
            raise ContextError("network_destination_unconfigured")
        return await guarded_read(
            url,
            check=ctx.check,
            run_blocking=ctx.to_thread,
            own_hosts=[urlsplit(self.settings.public_url).hostname],
            **kwargs,
        )

    async def _bump(self, conn):
        rows = await conn.execute_fetchall(
            "UPDATE meta SET value=CAST(value AS INTEGER)+1 WHERE key='plugins_rev' RETURNING value"
        )
        self.plugins_rev = int(rows[0][0])
        await conn.execute("UPDATE meta SET value=CAST(value AS INTEGER)+1 WHERE key='change_seq'")

    def catalog(self, *, include_retired=False):
        result = []
        for plugin_id, manifest in self.manifests.items():
            row = self.states[plugin_id]
            item = {**copy.deepcopy(manifest), **copy.deepcopy(row)}
            item.update(self.catalog_metadata[plugin_id])
            item["can_enable"] = plugin_id not in self.invalid_manifests
            item["can_delete_data"] = True
            item["settings"] = self.get_settings(plugin_id)
            configured = self.context_settings(plugin_id)
            urls = configured.get("urls", [])
            urls = urls[:10] if isinstance(urls, list) else []
            if configured.get("url"):
                urls = [*urls, configured["url"]]
            hosts = list(
                dict.fromkeys(host for value in urls if (host := _network_hostname(value)))
            )
            item["network_destinations"] = list(
                dict.fromkeys(
                    destination
                    for permission in manifest["permissions"]
                    if permission.startswith("net.out:")
                    for destination in (
                        hosts if permission == "net.out:<configured>" else [permission[8:]]
                    )
                )
            )
            item["ai_destinations"] = {}
            if self.gateway:
                for permission in manifest["permissions"]:
                    if permission.startswith("ai."):
                        try:
                            profile = self.gateway.profile_for(permission, row.get("ai_route"))
                            item["ai_destinations"][permission[3:]] = {
                                "profile": profile.name,
                                "host": urlsplit(profile.base_url).hostname,
                                "model": getattr(profile, permission[3:] + "_model"),
                                "leaves_network": profile.leaves_network(permission[3:]),
                            }
                        except AIError:
                            continue  # noqa: S112 - unbound capabilities have no destination
            result.append(item)
        for plugin_id, snapshot in self.removed_manifests.items():
            # Retired bundled import code leaves its data and lifecycle receipt in place.
            if (
                not include_retired
                and plugin_id == "blinko-import"
                and self.catalog_metadata[plugin_id]
                == {"origin": "bundled", "source_kind": "bundled"}
            ):
                continue
            result.append(
                {
                    **copy.deepcopy(snapshot),
                    **copy.deepcopy(self.states[plugin_id]),
                    **self.catalog_metadata[plugin_id],
                    "settings": {},
                    "can_enable": False,
                    "can_delete_data": True,
                    "network_destinations": [],
                    "ai_destinations": {},
                }
            )
        return sorted(result, key=lambda item: item["position"])

    def manifest(self):
        return {
            "ui": 1,
            "plugins_rev": self.plugins_rev,
            "plugins": [
                {
                    **copy.deepcopy(self.manifests[item["id"]]),
                    "settings_values": self.get_settings(item["id"]),
                    "position": item["position"],
                }
                for item in self.catalog()
                if item["state"] == "enabled"
            ],
        }

    def get_settings(self, plugin_id):
        return settings_values(self.manifests[plugin_id], self.states[plugin_id]["settings"])

    def context_settings(self, plugin_id):
        result = self.get_settings(plugin_id)
        for name, prop in (
            self.manifests[plugin_id].get("settings", {}).get("properties", {}).items()
        ):
            if not prop.get("x-secret"):
                continue
            variable = f"SPUNTO_PLUGIN_{plugin_id.replace('-', '_').upper()}_{name.upper()}"
            direct, filename = os.environ.get(variable), os.environ.get(variable + "_FILE")
            if direct and filename:
                raise ContextError("secret_sources_conflict")
            if filename:
                try:
                    direct = Path(filename).read_text().strip()
                except OSError:
                    raise ContextError("secret_file_unreadable") from None
            if direct is not None:
                result[name] = direct
        return result

    async def discover(self):
        async with self.store.write() as conn:
            await conn.execute("INSERT OR IGNORE INTO meta(key,value) VALUES('plugins_rev','0')")
        if self._catalog is None:
            from spunto.host.loader import discover

            candidates, self.plugin_sources, self.discovery_errors = discover(self.settings)
        else:
            candidates = self._catalog
        manifests, invalid = [], set(self.discovery_errors)
        self.invalid_manifests.update(invalid)
        for item in candidates:
            try:
                manifests.append(
                    validate_manifest(item) if isinstance(item, dict) else read_manifest(Path(item))
                )
            except ManifestError, TypeError, ValueError:
                from spunto.host.manifest import ID

                name = (
                    item.get("id", "invalid-plugin")
                    if isinstance(item, dict)
                    else Path(item).name.replace("_", "-")
                )
                if name == "plugin.toml":
                    name = Path(item).parent.name.replace("_", "-")
                name = name if isinstance(name, str) and ID.fullmatch(name) else "invalid-plugin"
                manifests.append(
                    {
                        "id": name,
                        "name": name,
                        "version": "0",
                        "api": "0.4",
                        "kind": "server",
                        "description": "Invalid manifest",
                        "permissions": [],
                    }
                )
                invalid.add(name)
                self.invalid_manifests.add(name)
        if len({m["id"] for m in manifests}) != len(manifests):
            raise ManifestError("duplicate plugin id")
        async with self.store.read() as conn:
            existing = await conn.execute_fetchall("SELECT * FROM plugin")
            revision = await conn.execute_fetchall("SELECT value FROM meta WHERE key='plugins_rev'")
            initialized = await conn.execute_fetchall(
                "SELECT value FROM meta WHERE key='plugin_catalog_initialized'"
            )
        self.plugins_rev = int(revision[0][0]) if revision else 0
        rows = {row["id"]: dict(row) for row in existing}
        pins = {}
        for pin in filter(None, getattr(self.settings, "plugins", "").split(",")):
            pins[pin[1:]] = pin[0] == "+"
        unknown = set(pins) - {manifest["id"] for manifest in manifests} - rows.keys()
        if unknown:
            from spunto.errors import OperatorError

            raise OperatorError(
                "SPUNTO_PLUGINS names unavailable plugins: " + ", ".join(sorted(unknown)) + "."
            )
        self.manifests = {manifest["id"]: manifest for manifest in manifests}
        self.removed_manifests = {}
        self.catalog_metadata = {}
        self.invalid_manifests = invalid
        for plugin_id in rows.keys() - self.manifests.keys():
            old = rows[plugin_id]
            self.generations[plugin_id] = self.generations.get(plugin_id, 0) + 1
            for _, (pid, task) in list(self.running.items()):
                if pid == plugin_id:
                    task.cancel()
            self.plugins.pop(plugin_id, None)
            self.removed_manifests[plugin_id] = _retained_manifest(old)
            known_origin = (old["origin"], old["source_kind"]) in {
                ("bundled", "bundled"),
                ("operator", "folder"),
                ("operator", "entrypoint"),
            }
            self.catalog_metadata[plugin_id] = {
                "origin": old["origin"] if known_origin else "unknown",
                "source_kind": old["source_kind"] if known_origin else "unknown",
            }
            self.states[plugin_id] = {
                key: old[key] for key in ("id", "position", "data_version", "last_run_at")
            } | {
                "version": self.removed_manifests[plugin_id]["version"],
                "kind": self.removed_manifests[plugin_id]["kind"],
                "state": "removed",
                "switch": "off",
                "pinned_by_env": int(plugin_id in pins),
                "settings": {},
                "ai_route": {},
                "granted": [],
                "last_error": "plugin_removed",
            }
            # Access metadata is historical, never an executable permission grant.
            try:
                granted = json.loads(old["granted"])
                self.states[plugin_id]["granted"] = [
                    permission
                    for permission in self.removed_manifests[plugin_id]["permissions"]
                    if isinstance(granted, list) and permission in granted
                ]
            except TypeError, ValueError:
                pass
        self._set_vector_routes()
        for manifest in manifests:
            plugin_id = manifest["id"]
            self.manifests[plugin_id] = manifest
            self.generations.setdefault(plugin_id, 0)
            self.pools.setdefault(
                plugin_id,
                ThreadPoolExecutor(max_workers=2, thread_name_prefix="plugin-" + plugin_id),
            )
            old = rows.get(plugin_id)
            source = self.plugin_sources.get(plugin_id)
            metadata = {
                "origin": source.origin if source else "unknown",
                "source_kind": source.source_kind if source else "unknown",
            }
            identity = hashlib.sha256(source.identity.encode()).hexdigest() if source else None
            changed_source = bool(
                old
                and (
                    (old["source_identity"] and old["source_identity"] != identity)
                    or (not old["source_identity"] and metadata["origin"] == "operator")
                )
            )
            self.catalog_metadata[plugin_id] = metadata
            switched_on = (
                old["switch"] == "on"
                if old
                else not initialized and manifest.get("default", "off") != "off"
            )
            if changed_source:
                switched_on = False
                self.generations[plugin_id] += 1
                self.plugins.pop(plugin_id, None)
                for _, (pid, task) in list(self.running.items()):
                    if pid == plugin_id:
                        task.cancel()
            if plugin_id in pins:
                switched_on = pins[plugin_id]
            granted = (
                json.loads(old["granted"])
                if old
                else (manifest["permissions"] if switched_on else [])
            )
            if changed_source:
                granted = []
            state = {
                "id": plugin_id,
                "version": manifest["version"],
                "kind": manifest["kind"],
                "switch": "on" if switched_on else "off",
                "state": "waiting" if switched_on else ("disabled" if old else "available"),
                "pinned_by_env": int(plugin_id in pins),
                "granted": granted,
                "settings": settings_values(
                    manifest,
                    {
                        k: v
                        for k, v in json.loads(old["settings"]).items()
                        if k in manifest.get("settings", {}).get("properties", {})
                        and not manifest["settings"]["properties"][k].get("x-secret")
                        and manifest["settings"]["properties"][k].get("x-scope") != "device"
                    }
                    if old and not changed_source
                    else {},
                ),
                "position": old["position"] if old else len(self.states),
                "data_version": old["data_version"] if old else 0,
                "last_run_at": old["last_run_at"] if old else None,
                "last_error": old["last_error"] if old else None,
                "ai_route": json.loads(old["ai_route"] or "{}")
                if old and not changed_source
                else {},
            }
            if plugin_id in invalid:
                state["state"] = "failed"
                state["last_error"] = self.discovery_errors.get(
                    plugin_id, "plugin_manifest_invalid"
                )
            if old and not changed_source and old["state"] == "failed" and switched_on:
                state["state"] = "failed"
            self.states[plugin_id] = state
            if changed_source:
                self._set_vector_routes()
            async with self.store.write() as conn:
                await conn.execute(
                    "INSERT INTO plugin(id,version,kind,switch,state,pinned_by_env,granted,"
                    "settings,position,data_version,last_run_at,last_error,ai_route,manifest_snapshot,"
                    "origin,source_kind,source_identity) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                    "ON CONFLICT(id) DO UPDATE SET version=excluded.version,"
                    "switch=excluded.switch,state=excluded.state,"
                    "pinned_by_env=excluded.pinned_by_env,settings=excluded.settings,"
                    "granted=excluded.granted,ai_route=excluded.ai_route,"
                    "manifest_snapshot=excluded.manifest_snapshot,origin=excluded.origin,"
                    "source_kind=excluded.source_kind,source_identity=excluded.source_identity",
                    (
                        plugin_id,
                        manifest["version"],
                        manifest["kind"],
                        state["switch"],
                        state["state"],
                        state["pinned_by_env"],
                        json.dumps(granted),
                        json.dumps(state["settings"]),
                        state["position"],
                        state["data_version"],
                        state["last_run_at"],
                        state["last_error"],
                        json.dumps(state["ai_route"]),
                        json.dumps(_catalog_snapshot(manifest)),
                        metadata["origin"],
                        metadata["source_kind"],
                        identity,
                    ),
                )
                if changed_source:
                    await conn.execute(
                        "UPDATE job SET status='dead',lease_until=NULL,last_error='plugin_removed',"
                        "updated_at=? WHERE plugin_id=?",
                        (self.clock(), plugin_id),
                    )
                    texts = await conn.execute_fetchall(
                        "SELECT note_id FROM note_text WHERE plugin_id=?",
                        (plugin_id,),
                    )
                    for text in texts:
                        await self._reindex(conn, text["note_id"])
            if switched_on and state["state"] != "failed":
                await self._activate(plugin_id)
        for plugin_id in self.removed_manifests:
            async with self.store.write() as conn:
                await conn.execute(
                    "UPDATE plugin SET switch='off',state='removed',pinned_by_env=?,"
                    "last_error='plugin_removed' WHERE id=?",
                    (self.states[plugin_id]["pinned_by_env"], plugin_id),
                )
                await conn.execute(
                    "UPDATE job SET status='dead',lease_until=NULL,last_error='plugin_removed',"
                    "updated_at=? WHERE plugin_id=?",
                    (self.clock(), plugin_id),
                )
                texts = await conn.execute_fetchall(
                    "SELECT note_id FROM note_text WHERE plugin_id=?",
                    (plugin_id,),
                )
                for text in texts:
                    await self._reindex(conn, text["note_id"])
        async with self.store.write() as conn:
            await conn.execute(
                "INSERT OR REPLACE INTO meta(key,value) VALUES('plugin_catalog_initialized','1')"
            )
            fingerprint = hashlib.sha256(
                json.dumps(
                    {
                        "plugins": self.manifests,
                        "removed": self.removed_manifests,
                        "origins": self.catalog_metadata,
                        "themes": self.themes.catalog(),
                    },
                    sort_keys=True,
                    allow_nan=False,
                ).encode()
            ).hexdigest()
            previous = await conn.execute_fetchall(
                "SELECT value FROM meta WHERE key='host_catalog_hash'"
            )
            if not previous or previous[0]["value"] != fingerprint:
                await conn.execute(
                    "INSERT OR REPLACE INTO meta(key,value) VALUES('host_catalog_hash',?)",
                    (fingerprint,),
                )
                await self._bump(conn)
        if self.vectors is not None and any(
            state["state"] == "enabled" and "ai.embed" in self.manifests[plugin_id]["permissions"]
            for plugin_id, state in self.states.items()
        ):
            await self.enqueue_core("ai-routes", priority=5)
        return self.catalog()

    def _requirements_met(self, manifest):
        return self._requirements_reason(manifest) is None

    def _requirements_reason(self, manifest):
        if manifest.get("needs"):
            try:
                configured = self.context_settings(manifest["id"])
            except ContextError:
                return "Plugin secrets are unavailable; check the environment."
            if reason := _needs_reason(manifest, configured):
                return reason
        for requirement in manifest.get("requires", []):
            if requirement == "attachments" and self.attachments_factory is None:
                return "Needs attachment storage."
            if requirement.startswith("ai."):
                capability = requirement[3:]
                title = {
                    "chat": "a chat model",
                    "embed": "an embedding model",
                    "transcribe": "a transcription model",
                    "vision": "a vision model",
                }[capability]
                if self.gateway is None:
                    return f"Needs {title}. AI is off."
                try:
                    profile = self.gateway.profile_for(
                        requirement, self.states[manifest["id"]].get("ai_route")
                    )
                except AIError:
                    return f"Needs {title}. Choose one in Settings → AI."
                checked = [
                    row
                    for row in self.gateway.health
                    if row["profile"] == profile.name
                    and row["capability"] == requirement[3:]
                    and row.get("model") == getattr(profile, requirement[3:] + "_model")
                ]
                if checked and not checked[-1]["ok"]:
                    hint = (
                        checked[-1].get("fix_hint") or "Run the connection check in Settings → AI."
                    )
                    return f"The {capability} model failed its connection check. {hint}"
                if requirement == "ai.embed" and (
                    self.vectors is None or not self.store.vector_available
                ):
                    return "Needs the note search index. Check indexing in Settings → AI."
        return None

    async def _state(
        self, plugin_id, state, error=None, *, generation=None, run=None, failures=None
    ):
        if run is not None:
            return await self._state_for_run(plugin_id, state, error, generation, run, failures)
        expected = self.generations[plugin_id] if generation is None else generation

        def valid_generation():
            if expected != self.generations[plugin_id]:
                raise ContextError("plugin_disabled")
            if state == "enabled" and self.states[plugin_id]["switch"] != "on":
                raise ContextError("plugin_disabled")

        valid_generation()
        token = object()
        previous = self.states[plugin_id]["state"], self.states[plugin_id]["last_error"]
        self._state_tokens[plugin_id] = token
        self.states[plugin_id]["state"] = state
        self.states[plugin_id]["last_error"] = error
        # Revocation must fence core calls even while another vector write owns SQLite.
        vectors_changed = self._set_vector_routes()

        def current():
            valid_generation()
            if self._state_tokens.get(plugin_id) is not token:
                raise ContextError("plugin_disabled")

        try:
            async with self.store.write() as conn:
                current()
                await conn.execute(
                    "UPDATE plugin SET state=?,last_error=? WHERE id=?", (state, error, plugin_id)
                )
                await self._bump(conn)
                current()
        except BaseException:
            if (
                state == "enabled"
                and self._state_tokens.get(plugin_id) is token
                and expected == self.generations[plugin_id]
            ):
                self.states[plugin_id]["state"], self.states[plugin_id]["last_error"] = previous
                self._set_vector_routes()
            # A failed revocation stays closed; never restore a superseded owner's state.
            raise
        current()
        self._wake.set()
        await self._queue_vector_routes(force=vectors_changed)

    async def _state_for_run(self, plugin_id, state, error, expected, row, failures):
        self._check_run(row)
        previous = self.states[plugin_id]["state"], self.states[plugin_id]["last_error"]
        token = object()
        generation = expected
        vectors_changed = False

        def current():
            self._check_run(row)
            if (
                self.generations[plugin_id] != generation
                or self._state_tokens.get(plugin_id) is not token
            ):
                raise ContextError("plugin_disabled")

        try:
            async with self.store.write() as conn:
                self._check_run(row)
                if (
                    self.generations[plugin_id] != expected
                    or self.states[plugin_id]["switch"] != "on"
                ):
                    raise ContextError("plugin_disabled")
                live = await conn.execute_fetchall(
                    "SELECT status,last_error FROM job WHERE id=? AND plugin_id=? "
                    "AND run_receipt IS ?",
                    (row["id"], plugin_id, row.get("run_receipt")),
                )
                self._check_run(row)
                if (
                    not live
                    or live[0]["last_error"] == "plugin_removed"
                    or live[0]["status"] != ("dead" if state == "failed" else "running")
                    or self.generations[plugin_id] != expected
                ):
                    raise ContextError("plugin_disabled")
                generation = expected + (state == "failed")
                self.generations[plugin_id] = generation
                self._state_tokens[plugin_id] = token
                self.states[plugin_id]["state"], self.states[plugin_id]["last_error"] = state, error
                vectors_changed = self._set_vector_routes()
                await conn.execute(
                    "UPDATE plugin SET state=?,last_error=? WHERE id=?", (state, error, plugin_id)
                )
                await self._bump(conn)
                current()
        except BaseException:
            if (
                state in {"enabled", "waiting"}
                and self._state_tokens.get(plugin_id) is token
                and self.generations[plugin_id] == generation
                and self.claim_receipts.get(row["id"]) == row.get("run_receipt")
            ):
                self.states[plugin_id]["state"], self.states[plugin_id]["last_error"] = previous
                self._set_vector_routes()
            # Failed revocation stays closed even if SQLite cannot persist it.
            raise
        current()
        if failures is not None:
            self.failures[plugin_id] = failures
        self._wake.set()
        await self._queue_vector_routes(force=vectors_changed)

    async def _activate(self, plugin_id):
        manifest, state = self.manifests[plugin_id], self.states[plugin_id]
        generation = self.generations[plugin_id]
        reason = self._requirements_reason(manifest)
        if not set(manifest["permissions"]) <= set(state["granted"]):
            reason = "Review and accept this plugin's permissions to enable it."
        if reason:
            await self._state(plugin_id, "waiting", reason, generation=generation)
            return
        try:
            async with self.store.write() as conn:
                await conn.execute(
                    "INSERT INTO credential(id,kind,name,client,scope,created_at) "
                    "VALUES(?,'plugin',?,?,'plugin',?) ON CONFLICT(id) "
                    "DO UPDATE SET name=excluded.name",
                    ("plugin:" + plugin_id, manifest["name"], plugin_id, self.clock()),
                )
            if manifest["kind"] == "server" and manifest.get("entry"):
                module, name = manifest["entry"].split(":")
                source = self.plugin_sources.get(plugin_id)
                plugin = (
                    source.load(manifest["entry"])
                    if source is not None
                    else getattr(importlib.import_module(module), name)
                )
                if not isinstance(plugin, Plugin) or plugin.id != plugin_id:
                    raise ManifestError("entry must export the matching Plugin")
                self._bindings(manifest, plugin)
                self.plugins[plugin_id] = plugin
            for schedule in manifest.get("contributes", {}).get("schedules", []):
                self._scheduled_slot(schedule, self.get_settings(plugin_id), self.clock())
            target = manifest.get("data_version", 0)
            if target > state["data_version"] and plugin_id in self.plugins:
                migrations = self.plugins[plugin_id].handlers["migrate"]
                migration = (state["data_version"], target)
                if migration in migrations:
                    await self._state(plugin_id, "waiting", generation=generation)
                    await self._queue(
                        plugin_id, "migrate", {"old": migration[0], "new": target}, priority=5
                    )
                    return
                if state["data_version"]:
                    raise ManifestError("data migration is missing")
            state["data_version"] = target
            async with self.store.write() as conn:
                await conn.execute(
                    "UPDATE plugin SET data_version=? WHERE id=?", (target, plugin_id)
                )
            await self._state(plugin_id, "enabled", generation=generation)
            if (
                plugin_id in self.plugins
                and "plugin.enabled" in self.plugins[plugin_id].handlers["hook"]
            ):
                await self._queue(plugin_id, "hook:plugin.enabled", {}, priority=5)
        except Exception as error:
            if isinstance(error, ContextError) and str(error) == "plugin_disabled":
                raise
            await self._state(plugin_id, "failed", "plugin_load_failed", generation=generation)

    def _bindings(self, manifest, plugin):
        contributions = manifest.get("contributes", {})
        for kind, field, identifier in (
            ("command", "commands", "id"),
            ("schedule", "schedules", "id"),
            ("tool", "mcp_tools", "name"),
        ):
            declared = {item[identifier] for item in contributions.get(field, [])}
            if set(plugin.handlers[kind]) != declared:
                raise ManifestError("code bindings do not match manifest")
        for kind, permission in (("route", "routes"), ("schedule", "schedule"), ("tool", "mcp")):
            if plugin.handlers[kind] and permission not in manifest["permissions"]:
                raise ManifestError(f"{kind} requires permission")
        for kind in ("panel", "search_source"):
            definition = contributions.get(kind)
            handlers = plugin.handlers[kind]
            if handlers and not definition:
                raise ManifestError(f"{kind} must be declared")
            if definition and (
                (kind == "panel" and definition.get("mode") == "live") or kind == "search_source"
            ):
                if len(handlers) != 1:
                    raise ManifestError(f"{kind} needs one handler")

    @serialized
    async def enable(self, plugin_id, permissions=None):
        state, manifest = self.states[plugin_id], self.manifests[plugin_id]
        if plugin_id in self.invalid_manifests:
            raise ManifestError("plugin manifest is invalid")
        if state["pinned_by_env"] and state["switch"] == "off":
            raise ContextError("plugin_pinned_by_environment")
        if permissions is not None:
            if not isinstance(permissions, list) or set(permissions) != set(
                manifest["permissions"]
            ):
                raise ManifestError("accept the current manifest permissions")
            state["granted"] = list(permissions)
        state["switch"] = "on"
        self.failures[plugin_id] = 0
        async with self.store.write() as conn:
            await conn.execute(
                "INSERT OR REPLACE INTO plugin_kv(plugin_id,key,value) VALUES(?,"
                "'host:consecutive_failures','0')",
                (plugin_id,),
            )
            await conn.execute(
                "UPDATE plugin SET switch='on',granted=? WHERE id=?",
                (json.dumps(state["granted"]), plugin_id),
            )
            await conn.execute(
                "DELETE FROM job WHERE plugin_id=? AND status='dead' "
                "AND coalesce(job.last_error,'')!='plugin_removed' "
                "AND EXISTS (SELECT 1 FROM job q WHERE q.plugin_id=job.plugin_id "
                "AND q.kind=job.kind AND ifnull(q.note_id,'')=ifnull(job.note_id,'') "
                "AND q.status='queued')",
                (plugin_id,),
            )
            retired_runs = await conn.execute_fetchall(
                "SELECT id,run_receipt FROM job WHERE plugin_id=? AND status='dead' "
                "AND coalesce(last_error,'')!='plugin_removed'",
                (plugin_id,),
            )
            await conn.execute(
                "UPDATE job SET status='queued',attempts=0,run_after=?,last_error=NULL,"
                "run_receipt=NULL "
                "WHERE plugin_id=? AND status='dead' "
                "AND coalesce(last_error,'')!='plugin_removed'",
                (self.clock(), plugin_id),
            )
        for job in retired_runs:
            if self.claim_receipts.get(job["id"]) == job["run_receipt"]:
                self.claim_receipts.pop(job["id"], None)
        await self._activate(plugin_id)
        async with self.store.write() as conn:
            texts = await conn.execute_fetchall(
                "SELECT note_id FROM note_text WHERE plugin_id=?", (plugin_id,)
            )
            for text in texts:
                await self._reindex(conn, text["note_id"])
        await self._queue_vector_routes(force="ai.embed" in manifest["permissions"])
        return copy.deepcopy(state)

    @serialized
    async def disable(self, plugin_id):
        state = self.states[plugin_id]
        if state["pinned_by_env"]:
            raise ContextError("plugin_pinned_by_environment")
        self.generations[plugin_id] += 1
        state["switch"] = "off"
        state["state"] = "removed" if plugin_id in self.removed_manifests else "disabled"
        vectors_changed = self._set_vector_routes()
        for _, (pid, task) in list(self.running.items()):
            if pid == plugin_id:
                task.cancel()
        async with self.store.write() as conn:
            await conn.execute(
                "UPDATE plugin SET switch='off',state=?,last_error=? WHERE id=?",
                (state["state"], state["last_error"], plugin_id),
            )
            await self._bump(conn)
            notes = await conn.execute_fetchall(
                "SELECT note_id FROM note_text WHERE plugin_id=?", (plugin_id,)
            )
        for note in notes:
            await self.enqueue_core(
                "reindex", {"note_id": note["note_id"]}, note_id=note["note_id"]
            )
        if self.vectors is not None:
            await self._queue_vector_routes(force=vectors_changed)
            for note in notes:
                await self.vectors.changed(note["note_id"])
        self._wake.set()
        return copy.deepcopy(state)

    @serialized
    async def set_ai_route(self, plugin_id, route):
        if self.gateway is None or not isinstance(route, dict):
            raise ManifestError("AI routing is unavailable")
        allowed = {
            permission[3:]
            for permission in self.manifests[plugin_id]["permissions"]
            if permission.startswith("ai.")
        }
        if set(route) - allowed:
            raise ManifestError("route contains an undeclared capability")
        for capability, name in route.items():
            if not isinstance(name, str) or not name:
                raise ManifestError("route must name a profile")
            self.gateway.profile_for(capability, profile=name)
        self.generations[plugin_id] += 1
        for _, (pid, task) in list(self.running.items()):
            if pid == plugin_id:
                task.cancel()
        state = self.states[plugin_id]
        state["ai_route"] = dict(route)
        vectors_changed = self._set_vector_routes()
        async with self.store.write() as conn:
            await conn.execute(
                "UPDATE plugin SET ai_route=? WHERE id=?", (json.dumps(route), plugin_id)
            )
            await self._bump(conn)
        if state["switch"] == "on" and state["state"] != "failed":
            await self._activate(plugin_id)
        await self._queue_vector_routes(force=vectors_changed)
        return dict(route)

    @serialized
    async def set_settings(self, plugin_id, values):
        sanitized = settings_values(self.manifests[plugin_id], values)
        if "net.out:<configured>" in self.manifests[plugin_id]["permissions"]:
            destinations = [*sanitized.get("urls", [])]
            if sanitized.get("url"):
                destinations.append(sanitized["url"])
            if len(set(destinations)) > 10 or any(
                not _network_hostname(value) for value in destinations
            ):
                raise ManifestError("Configured network destination is invalid.")
        for schedule in self.manifests[plugin_id].get("contributes", {}).get("schedules", []):
            self._scheduled_slot(schedule, sanitized, self.clock())
        self.states[plugin_id]["settings"] = sanitized
        async with self.store.write() as conn:
            await conn.execute(
                "UPDATE plugin SET settings=? WHERE id=?", (json.dumps(sanitized), plugin_id)
            )
            await self._bump(conn)
        self._wake.set()
        if self.manifests[plugin_id].get("needs") and self.states[plugin_id]["switch"] == "on":
            self.generations[plugin_id] += 1
            for _, (pid, task) in list(self.running.items()):
                if pid == plugin_id:
                    task.cancel()
            if self.states[plugin_id]["state"] != "failed":
                await self._activate(plugin_id)
        return sanitized

    @serialized
    async def delete_data(self, plugin_id):
        self.generations[plugin_id] += 1
        for _, (pid, task) in list(self.running.items()):
            if pid == plugin_id:
                task.cancel()
        from spunto.core.notes import sequence

        async with self.store.write() as conn:
            for table in ("suggestion", "annotation"):
                rows = await conn.execute_fetchall(
                    "SELECT id FROM suggestion WHERE plugin_id=?"
                    if table == "suggestion"
                    else "SELECT plugin_id || '/' || note_id || '/' || kind AS id FROM annotation "
                    "WHERE plugin_id=?",
                    (plugin_id,),
                )
                for row in rows:
                    await conn.execute(
                        "INSERT OR REPLACE INTO tombstone(kind,id,change_seq,deleted_at) VALUES(?,"
                        "?,?,?)",
                        (table, row["id"], await sequence(conn), self.clock()),
                    )
            notes = await conn.execute_fetchall(
                "SELECT note_id FROM note_text WHERE plugin_id=?", (plugin_id,)
            )
            for table in (
                "plugin_kv",
                "plugin_record",
                "suggestion",
                "annotation",
                "note_text",
                "job",
            ):
                await conn.execute(f"DELETE FROM {table} WHERE plugin_id=?", (plugin_id,))  # noqa: S608
            for row in notes:
                await self._reindex(conn, row["note_id"])
            await self._bump(conn)
        self._wake.set()

    async def _reindex(self, conn, note_id):
        await conn.execute(
            "UPDATE note_fts SET extra=coalesce((SELECT group_concat(nt.text,' ') "
            "FROM note_text nt JOIN plugin p ON p.id=nt.plugin_id WHERE nt.note_id=? "
            "AND p.state='enabled'),'') WHERE rowid=(SELECT rowid FROM note WHERE "
            "id=?)",
            (note_id, note_id),
        )
        self.store.emit("note.embed", note_id=note_id)
        await conn.execute("DELETE FROM embedding_note WHERE note_id=?", (note_id,))

    async def enqueue(
        self, plugin_id, name, args=None, *, note_id=None, unattended=True, delay_ms=0, priority=0
    ):
        Ctx(self, plugin_id).check()
        if type(delay_ms) is not int or not 0 <= delay_ms <= 86_400_000:
            raise ManifestError("job delay must be between zero and one day")
        if type(priority) is not int or not 0 <= priority <= 5:
            raise ManifestError("job priority must be between zero and five")
        if name not in self.plugins[plugin_id].handlers["job"]:
            raise ManifestError("unknown job")
        return await self._queue(
            plugin_id,
            "job:" + name,
            args or {},
            note_id=note_id,
            unattended=unattended,
            delay=delay_ms,
            priority=priority,
        )

    async def _queue(
        self, plugin_id, kind, args, *, note_id=None, priority=0, delay=0, unattended=True
    ):
        now = self.clock()
        if not isinstance(args, dict) or any(k.startswith("_host_") for k in args):
            raise ManifestError("job arguments must be an object without reserved host fields")
        payload = json.dumps({**args, "_host_unattended": unattended}, allow_nan=False)
        if len(payload.encode()) > 65536:
            raise ManifestError("job arguments exceed 64 KB")
        async with self.store.write() as conn:
            existing = await conn.execute_fetchall(
                "SELECT id,run_receipt FROM job WHERE plugin_id=? AND kind=? AND ifnull(note_id,"
                "'')=ifnull(?,'') AND status='queued'",
                (plugin_id, kind, note_id),
            )
            if existing:
                job_id = existing[0][0]
                await conn.execute(
                    "UPDATE job SET args=?,priority=min(priority,?),run_after=?,updated_at=?,"
                    "run_receipt=NULL "
                    "WHERE id=?",
                    (payload, priority, now + delay, now, job_id),
                )
            else:
                cursor = await conn.execute(
                    "INSERT INTO job(plugin_id,kind,note_id,args,priority,status,run_after,"
                    "created_at,updated_at) VALUES(?,?,?,?,?,'queued',?,?,?)",
                    (plugin_id, kind, note_id, payload, priority, now + delay, now, now),
                )
                job_id = cursor.lastrowid
        if existing and self.claim_receipts.get(job_id) == existing[0]["run_receipt"]:
            self.claim_receipts.pop(job_id, None)
        self._wake.set()
        return job_id

    async def emit(self, event, note_id=None, note_version=None, **args):
        if event == "note.embed":
            if self.vectors is not None:
                await self.vectors.changed(note_id)
            return
        for plugin_id, plugin in self.plugins.items():
            if self.states[plugin_id]["state"] == "enabled" and event in plugin.handlers["hook"]:
                await self._queue(
                    plugin_id,
                    "hook:" + event,
                    {
                        **args,
                        **({"note_id": note_id, "note_version": note_version} if note_id else {}),
                    },
                    note_id=note_id,
                    priority=5 if event == "plugin.enabled" else 0,
                    delay=3000 if event == "note.saved" else 0,
                )

    async def invoke_command(self, plugin_id, name, args=None, *, principal=None):
        if principal is not None and not principal.owner:
            raise ContextError("owner_required")
        return await self._invoke(
            plugin_id, "command", name, args, principal=principal, vocabulary=True
        )

    async def invoke_route(self, plugin_id, path, args=None, *, method="GET", principal=None):
        plugin = self.plugins.get(plugin_id)
        key = (method, path.lstrip("/"))
        if plugin is None:
            raise KeyError("route_not_found")
        if key not in plugin.handlers["route"]:
            import re

            for candidate in plugin.handlers["route"]:
                if candidate[0] != method:
                    continue
                pattern = re.escape(candidate[1])
                pattern = re.sub(
                    r"\\\{([a-z_][a-z0-9_]*)\\\}",
                    lambda match: "(?P<" + match[1] + ">[^/]+)",
                    pattern,
                )
                match = re.fullmatch(pattern, key[1])
                if match:
                    key, args = candidate, {**(args or {}), **match.groupdict()}
                    break
            else:
                raise KeyError("route_not_found")
        options = plugin.route_options[key]
        if principal is not None and not principal.owner and not options["allow_tokens"]:
            raise ContextError("route_token_denied")
        if principal is not None and options["write"] and not principal.writable:
            raise ContextError("write_scope_required")
        return await self._invoke(
            plugin_id, "route", key, args, principal=principal, vocabulary=True
        )

    async def invoke_tool(self, plugin_id, name, args=None, *, principal=None):
        definition = next(
            item
            for item in self.manifests[plugin_id].get("contributes", {}).get("mcp_tools", [])
            if item["name"] == name
        )
        if principal is not None and not definition["read_only"] and not principal.writable:
            raise ContextError("write_scope_required")
        return await self._invoke(plugin_id, "tool", name, args, principal=principal)

    async def invoke_panel(self, plugin_id, note_id, *, principal):
        definition = self.manifests[plugin_id].get("contributes", {}).get("panel", {})
        if definition.get("mode") != "live":
            raise KeyError("panel_not_found")
        # Core visibility is checked before even an ID is given to a live panel.
        from spunto.core.notes import Notes

        await Notes(self.store, principal).get(note_id)
        key = next(iter(self.plugins[plugin_id].handlers["panel"]))
        return await self._invoke(
            plugin_id, "panel", key, {"id": note_id}, principal=principal, vocabulary=True
        )

    async def search_sources(self, query, *, principal):
        async def call(plugin_id, plugin):
            key = next(iter(plugin.handlers["search_source"]))
            try:
                async with asyncio.timeout(2):
                    result = await self._invoke(
                        plugin_id, "search_source", key, {"query": query}, principal=principal
                    )
                if not isinstance(result, list) or len(result) > 50:
                    raise ManifestError("search source must return at most 50 references")
                ids = [item["id"] for item in result]
                if not all(isinstance(value, str) and len(value) == 36 for value in ids):
                    raise ManifestError("search source returned invalid references")
                return list(dict.fromkeys(ids)), True
            except Exception:
                return [], False

        sources = [
            call(plugin_id, plugin)
            for plugin_id, plugin in self.plugins.items()
            if self.states[plugin_id]["state"] == "enabled" and plugin.handlers["search_source"]
        ]
        return await asyncio.gather(*sources) if sources else []

    async def _call_handler(self, handler, ctx, args, *, hook=False):
        if hook:
            parameters = inspect.signature(handler).parameters
            if not any(p.kind == inspect.Parameter.VAR_KEYWORD for p in parameters.values()):
                args = {key: value for key, value in args.items() if key in parameters}
        loop = asyncio.get_running_loop()
        due = time.monotonic()
        reported = False
        handle = None

        def report():
            nonlocal reported
            if not reported:
                reported = True
                self.loop_violations.add(ctx.plugin_id)
                logging.getLogger("spunto.plugin").warning(
                    "plugin=%s code=plugin_loop_blocked", ctx.plugin_id
                )

        def probe():
            nonlocal due, handle
            now = time.monotonic()
            if now - due > _LOOP_BLOCK_BUDGET_SECONDS:
                report()
            due = now + 0.05
            handle = loop.call_later(0.05, probe)

        handle = loop.call_soon(probe)
        try:
            return await handler(ctx, **args)
        finally:
            if time.monotonic() - due > _LOOP_BLOCK_BUDGET_SECONDS:
                report()
            handle.cancel()

    async def _invoke(self, plugin_id, kind, key, args, *, principal=None, vocabulary=False):
        capture_note_id = None
        if kind == "command" and principal is not None and principal.owner:
            capture_note_id = (args or {}).get("note_id", (args or {}).get("id"))
        ctx = Ctx(
            self,
            plugin_id,
            principal=principal,
            unattended=principal is not None and not principal.owner,
            capture_note_id=capture_note_id,
            operation=f"{kind}:{key}",
        )
        ctx.check()
        handler = self.plugins.get(plugin_id)
        if handler is None or key not in handler.handlers[kind]:
            raise KeyError("handler_not_found")
        try:
            result = await self._call_handler(handler.handlers[kind][key], ctx, args or {})
        except Exception as error:
            _log_failure(plugin_id, kind, error)
            raise
        ctx.check()
        if vocabulary and result is not None:
            validate_output(result)
            result = render_output(result)
        return result

    async def _recover_expired(self, conn, now):
        # Another PostgreSQL worker may still own a valid lease and receipt.
        await conn.execute(
            "DELETE FROM job WHERE status='running' AND (lease_until IS NULL OR lease_until<=?) "
            "AND EXISTS (SELECT 1 FROM job q WHERE q.plugin_id=job.plugin_id "
            "AND q.kind=job.kind AND ifnull(q.note_id,'')=ifnull(job.note_id,'') "
            "AND (q.status='queued' OR (q.status='running' "
            "AND (q.lease_until IS NULL OR q.lease_until<=?) "
            "AND (q.updated_at,q.id)>(job.updated_at,job.id))))",
            (now, now),
        )
        await conn.execute(
            "UPDATE job SET status='queued',lease_until=NULL,run_receipt=NULL "
            "WHERE status='running' AND (lease_until IS NULL OR lease_until<=?)",
            (now,),
        )

    async def start(self):
        async with self.store.write() as conn:
            if getattr(self.store, "backend", None) == "postgres":
                await self._recover_expired(conn, self.clock())
            else:
                # The SQLite data lock proves running rows belong to the stopped process.
                await conn.execute(
                    "DELETE FROM job WHERE status='running' AND EXISTS "
                    "(SELECT 1 FROM job q WHERE q.plugin_id=job.plugin_id AND q.kind=job.kind "
                    "AND ifnull(q.note_id,'')=ifnull(job.note_id,'') AND q.status='queued')"
                )
                await conn.execute(
                    "UPDATE job SET status='queued',lease_until=NULL,run_receipt=NULL "
                    "WHERE status='running'"
                )
        self._closed = False
        self._worker = asyncio.create_task(self._loop(), name="plugin-worker")

    async def close(self):
        self._closed = True
        self._wake.set()
        if self._worker:
            try:
                await self._worker
            except sqlite3.Error, ValueError:
                logging.getLogger("spunto.plugin").error("code=plugin_worker_database_unavailable")
        tasks = [task for _, task in self.running.values()]
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        for pool in self.pools.values():
            pool.shutdown(wait=False, cancel_futures=True)

    async def _claim(self):
        allowed = [
            pid
            for pid, state in self.states.items()
            if state["state"] in {"enabled", "waiting"}
            and sum(p == pid for p, _ in self.running.values())
            < self.manifests[pid].get("concurrency", 1)
        ]
        if not any(pid == "core" for pid, _ in self.running.values()):
            allowed.append("core")
        if not allowed:
            return None
        marks = ",".join("?" for _ in allowed)
        now = self.clock()
        receipt = uuid.uuid4().hex
        async with self.store.write() as conn:
            if getattr(self.store, "backend", None) == "postgres":
                await self._recover_expired(conn, now)
            rows = await conn.execute_fetchall(
                "WITH next AS MATERIALIZED (SELECT j.id,j.plugin_id,j.kind,j.priority FROM job j "  # noqa: S608
                "LEFT JOIN plugin p ON p.id=j.plugin_id WHERE j.status='queued' "
                "AND j.run_after<=? AND coalesce(j.last_error,'')!='plugin_removed' "
                f"AND j.plugin_id IN ({marks}) "  # noqa: S608
                "AND (j.plugin_id='core' OR p.state='enabled' "
                "OR (p.state='waiting' AND j.kind='migrate')) "
                "ORDER BY j.priority,j.run_after,j.id LIMIT 1) "
                "UPDATE job SET status='running',lease_until=?,updated_at=?,run_receipt=? "
                "WHERE id IN ("
                "SELECT j.id FROM job j,next n WHERE j.id=n.id OR "
                "(n.plugin_id='core' AND n.kind='embed' AND j.plugin_id='core' "
                "AND j.kind='embed' AND j.status='queued' AND j.priority=n.priority "
                "AND coalesce(j.last_error,'')!='plugin_removed' "
                "AND j.run_after<=?) ORDER BY j.priority,j.run_after,j.id "
                "LIMIT CASE WHEN (SELECT plugin_id='core' AND kind='embed' FROM next) "
                "THEN ? ELSE 1 END) RETURNING *",
                (
                    now,
                    *allowed,
                    now + 300000,
                    now,
                    receipt,
                    now,
                    32 if self.vectors is not None else 1,
                ),
            )
        if not rows:
            return None
        for row in rows:
            self.claim_receipts[row["id"]] = row["run_receipt"]
        rows = sorted(rows, key=lambda row: (row["priority"], row["run_after"], row["id"]))
        first = dict(rows[0])
        if first["plugin_id"] == "core" and first["kind"] == "embed" and self.vectors is not None:
            first["_batch"] = [dict(row) for row in rows]
        return first

    async def _run_embeds(self, rows):
        try:
            await self.vectors.index_notes(
                [row["note_id"] for row in rows], priority=rows[0]["priority"]
            )
            async with self.store.write() as conn:
                for row in rows:
                    await self._delete_job(conn, row)
        except asyncio.CancelledError:
            for row in rows:
                await self._pause(row)
        except Exception as error:
            from spunto.ai.errors import AIError
            from spunto.ai.vectors import VectorUnavailable

            _log_failure("core", "embed", error)
            eligibility_changed = isinstance(error, AIError) and error.code == "ai_note_refused"
            if (
                isinstance(error, VectorUnavailable)
                or eligibility_changed
                or (isinstance(error, AIError) and error.category in {"provider", "unavailable"})
            ):
                for row in rows:
                    await self._pause(row)
                delay = 1 if eligibility_changed else getattr(error, "retry_after", None) or 30
                async with self.store.write() as conn:
                    for row in rows:
                        if not await self._job_live(conn, row):
                            continue
                        await conn.execute(
                            "UPDATE job SET run_after=?,last_error=? WHERE id=? "
                            "AND run_receipt IS ?",
                            (
                                self.clock() + int(delay * 1000),
                                getattr(error, "code", "vectors_unavailable"),
                                row["id"],
                                row.get("run_receipt"),
                            ),
                        )
            else:
                validation = isinstance(error, (ValueError, ManifestError)) or (
                    isinstance(error, AIError) and error.category in {"validation", "refused"}
                )
                async with self.store.write() as conn:
                    for row in rows:
                        if not await self._job_live(conn, row):
                            continue
                        attempts = row["attempts"] + 1
                        dead = validation or attempts >= 5
                        newer = await conn.execute_fetchall(
                            "SELECT id FROM job WHERE plugin_id='core' AND kind='embed' "
                            "AND note_id=? AND status='queued'",
                            (row["note_id"],),
                        )
                        if newer and not dead:
                            await self._delete_job(conn, row)
                        else:
                            await conn.execute(
                                "UPDATE job SET status=?,attempts=?,run_after=?,lease_until=NULL,"
                                "last_error=?,updated_at=? WHERE id=? AND run_receipt IS ?",
                                (
                                    "dead" if dead else "queued",
                                    attempts,
                                    self.clock() + min(1800000, 30000 * 2 ** (attempts - 1)),
                                    getattr(error, "code", "embedding_job_failed"),
                                    self.clock(),
                                    row["id"],
                                    row.get("run_receipt"),
                                ),
                            )
        finally:
            async with self.store.write() as conn:
                for row in rows:
                    await self._delete_job(conn, row, running=True)
            for row in rows:
                self._release_run(row)
            self._wake.set()

    async def _run(self, row):
        if "_batch" in row:
            return await self._run_embeds(row["_batch"])

        plugin_id, job_id = row["plugin_id"], row["id"]
        args = json.loads(row["args"] or "{}")
        unattended = args.pop("_host_unattended", True)
        ctx = (
            None
            if plugin_id == "core"
            else Ctx(
                self,
                plugin_id,
                unattended=unattended,
                migration=row["kind"] == "migrate",
                capture_note_id=row["note_id"]
                if row["kind"].startswith(("hook:", "job:"))
                else None,
                operation=row["kind"],
            )
        )
        try:
            self._check_run(row)
            if ctx:
                ctx.check()
            async with self.store.read() as conn:
                if not await self._job_live(conn, row):
                    return
            self._check_run(row)
            if ctx:
                ctx.check()
            if plugin_id == "core":
                await self.core_handlers[row["kind"]](**args)
            elif row["kind"] == "migrate":
                await self._snapshot(plugin_id)
                handler = self.plugins[plugin_id].handlers["migrate"][(args["old"], args["new"])]
                await self._call_handler(handler, ctx, {})
                ctx.check()
                async with self.store.write() as conn:
                    ctx.check()
                    self._check_run(row)
                    if not await self._job_live(conn, row):
                        return
                    ctx.check()
                    await conn.execute(
                        "UPDATE plugin SET data_version=? WHERE id=?", (args["new"], plugin_id)
                    )
                    ctx.check()
                    self._check_run(row)
                ctx.check()
                self._check_run(row)
                self.states[plugin_id]["data_version"] = args["new"]
                await self._state(plugin_id, "enabled", generation=ctx.generation, run=row)
                if "plugin.enabled" in self.plugins[plugin_id].handlers["hook"]:
                    await self._queue(plugin_id, "hook:plugin.enabled", {}, priority=5)
            else:
                kind, name = row["kind"].split(":", 1)
                if (
                    kind == "hook"
                    and name != "note.purged"
                    and row["note_id"]
                    and args.get("note_version") is not None
                ):
                    async with self.store.read() as conn:
                        note = await conn.execute_fetchall(
                            "SELECT version FROM note WHERE id=?", (row["note_id"],)
                        )
                    if not note or note[0][0] != args["note_version"]:
                        return
                await self._call_handler(
                    self.plugins[plugin_id].handlers[kind][name], ctx, args, hook=kind == "hook"
                )
                ctx.check()
            ran_at = self.clock()
            async with self.store.write() as conn:
                self._check_run(row)
                if not await self._job_live(conn, row):
                    return
                self._check_run(row)
                if ctx:
                    ctx.check()
                    await conn.execute(
                        "UPDATE plugin SET last_run_at=? WHERE id=?", (ran_at, plugin_id)
                    )
                    await conn.execute(
                        "INSERT OR REPLACE INTO plugin_kv(plugin_id,key,value) VALUES(?,"
                        "'host:consecutive_failures','0')",
                        (plugin_id,),
                    )
                await self._delete_job(conn, row)
                self._check_run(row)
                if ctx:
                    ctx.check()
            self._check_run(row)
            if ctx:
                ctx.check()
                self.states[plugin_id]["last_run_at"] = ran_at
            self.failures[plugin_id] = 0
        except asyncio.CancelledError:
            await self._pause(row)
        except Exception as error:
            try:
                self._check_run(row)
                if ctx:
                    ctx.check()
                from spunto.ai.errors import AIError
                from spunto.ai.vectors import VectorUnavailable

                _log_failure(plugin_id, row["kind"], error)
                eligibility_changed = (
                    isinstance(error, AIError)
                    and error.category == "refused"
                    and error.code == "ai_note_refused"
                    and row["kind"].startswith("hook:")
                )
                if (
                    eligibility_changed
                    or isinstance(error, VectorUnavailable)
                    or (
                        isinstance(error, AIError) and error.category in {"provider", "unavailable"}
                    )
                ):
                    if not await self._pause(row):
                        return
                    delay = 1 if eligibility_changed else getattr(error, "retry_after", None) or 30
                    async with self.store.write() as conn:
                        if ctx:
                            ctx.check()
                        if not await self._job_live(conn, row):
                            return
                        if ctx:
                            ctx.check()
                        await conn.execute(
                            "UPDATE job SET run_after=?,last_error=? WHERE id=? AND plugin_id=? "
                            "AND run_receipt IS ?",
                            (
                                self.clock() + int(delay * 1000),
                                error.code,
                                job_id,
                                plugin_id,
                                row.get("run_receipt"),
                            ),
                        )
                        if ctx:
                            ctx.check()
                        self._check_run(row)
                    return
                validation = isinstance(error, (ManifestError, ValueError, ContextError)) or (
                    isinstance(error, AIError) and error.category in {"validation", "refused"}
                )
                attempts = row["attempts"] + 1
                dead = validation or attempts >= 5 or row["kind"] == "migrate"
                async with self.store.write() as conn:
                    if ctx:
                        ctx.check()
                    if not await self._job_live(conn, row):
                        return
                    if ctx:
                        ctx.check()
                    failures = await conn.execute_fetchall(
                        "SELECT value FROM plugin_kv WHERE plugin_id=? AND "
                        "key='host:consecutive_failures'",
                        (plugin_id,),
                    )
                    failure_count = int(failures[0][0]) + 1 if failures else 1
                    await conn.execute(
                        "INSERT OR REPLACE INTO plugin_kv(plugin_id,key,value) VALUES(?,"
                        "'host:consecutive_failures',?)",
                        (plugin_id, str(failure_count)),
                    )
                    newer = await conn.execute_fetchall(
                        "SELECT id FROM job WHERE plugin_id=? AND kind=? AND "
                        "ifnull(note_id,'')=ifnull(?,'') AND status='queued'",
                        (plugin_id, row["kind"], row["note_id"]),
                    )
                    if newer and not dead:
                        # A newer payload owns the key; retrying stale arguments replaces it.
                        await self._delete_job(conn, row)
                    else:
                        await conn.execute(
                            "UPDATE job SET status=?,attempts=?,run_after=?,lease_until=NULL,"
                            "last_error='plugin_job_failed',updated_at=? WHERE id=? "
                            "AND plugin_id=? "
                            "AND run_receipt IS ?",
                            (
                                "dead" if dead else "queued",
                                attempts,
                                self.clock() + min(1800000, 30000 * 2 ** (attempts - 1)),
                                self.clock(),
                                job_id,
                                plugin_id,
                                row.get("run_receipt"),
                            ),
                        )
                    if ctx:
                        ctx.check()
                    self._check_run(row)
                if ctx:
                    ctx.check()
                self._check_run(row)
                if plugin_id != "core" and (failure_count >= 5 or row["kind"] == "migrate"):
                    await self._state(
                        plugin_id,
                        "failed",
                        "plugin_migration_failed"
                        if row["kind"] == "migrate"
                        else "plugin_circuit_open",
                        generation=ctx.generation,
                        run=row,
                        failures=failure_count,
                    )
                else:
                    self.failures[plugin_id] = failure_count
            except ContextError, asyncio.CancelledError:
                # A retired generation may pause its old receipt, never mutate its successor.
                await self._pause(row)
        finally:
            async with self.store.write() as conn:
                await self._delete_job(conn, row, running=True)
            self._release_run(row)
            self._wake.set()

    def _check_run(self, row):
        receipt = row.get("run_receipt")
        if receipt is not None and self.claim_receipts.get(row["id"]) != receipt:
            raise ContextError("plugin_disabled")

    async def _delete_job(self, conn, row, *, running=False):
        query = (
            "DELETE FROM job WHERE id=? AND plugin_id=? AND run_receipt IS ? AND status='running'"
            if running
            else "DELETE FROM job WHERE id=? AND plugin_id=? AND run_receipt IS ?"
        )
        await conn.execute(
            query,
            (row["id"], row["plugin_id"], row.get("run_receipt")),
        )

    def _release_run(self, row):
        entry = self.running.get(row["id"])
        if entry is not None and entry[1] is asyncio.current_task():
            self.running.pop(row["id"], None)
        if self.claim_receipts.get(row["id"]) == row.get("run_receipt"):
            self.claim_receipts.pop(row["id"], None)

    async def _job_live(self, conn, row):
        live = await conn.execute_fetchall(
            "SELECT last_error FROM job WHERE id=? AND plugin_id=? AND run_receipt IS ?",
            (row["id"], row["plugin_id"], row.get("run_receipt")),
        )
        return bool(live and live[0]["last_error"] != "plugin_removed")

    async def _pause(self, row):
        async with self.store.write() as conn:
            if not await self._job_live(conn, row):
                return False
            existing = await conn.execute_fetchall(
                "SELECT id FROM job WHERE plugin_id=? AND kind=? AND ifnull(note_id,"
                "'')=ifnull(?,'') AND status='queued'",
                (row["plugin_id"], row["kind"], row["note_id"]),
            )
            if existing and existing[0]["id"] != row["id"]:
                await self._delete_job(conn, row)
            else:
                await conn.execute(
                    "UPDATE job SET status='queued',lease_until=NULL WHERE id=? AND plugin_id=? "
                    "AND run_receipt IS ?",
                    (row["id"], row["plugin_id"], row.get("run_receipt")),
                )
        return True

    async def _snapshot(self, plugin_id):
        from contextlib import closing

        from spunto.store.db import connect
        from spunto.store.snapshot import write_snapshot

        directory = (
            self.settings.data_dir / "snapshots" / ("plugin-" + plugin_id) / uuid.uuid4().hex
        )

        if getattr(self.store, "backend", None) == "postgres":
            from spunto.store.postgres_snapshot import write_postgres_snapshot

            await write_postgres_snapshot(
                self.store,
                directory,
                datetime.fromtimestamp(self.clock() / 1000, UTC),
            )
            return

        def take():
            with closing(connect(self.settings.data_dir / "spunto.db")) as conn:
                write_snapshot(conn, directory, datetime.fromtimestamp(self.clock() / 1000, UTC))

        await asyncio.get_running_loop().run_in_executor(self.pools[plugin_id], take)

    def _scheduled_slot(self, schedule, values, now):
        zone = ZoneInfo(getattr(self.settings, "tz", "UTC"))
        local = datetime.fromtimestamp(now / 1000, zone)

        def resolve(value):
            return (
                values.get(value[1:-1], "")
                if isinstance(value, str) and value.startswith("{") and value.endswith("}")
                else value
            )

        moment = resolve(schedule.get("time", "00:00"))
        try:
            hour, minute = map(int, moment.split(":"))
            if not 0 <= hour <= 23 or not 0 <= minute <= 59:
                raise ValueError
            slot = local.replace(hour=hour, minute=minute, second=0, microsecond=0)
            weekday = resolve(schedule.get("weekday"))
            if weekday:
                day = [
                    "monday",
                    "tuesday",
                    "wednesday",
                    "thursday",
                    "friday",
                    "saturday",
                    "sunday",
                ].index(weekday.lower())
                slot -= timedelta(days=(slot.weekday() - day) % 7)
                if slot > local:
                    slot -= timedelta(days=7)
                next_slot = slot + timedelta(days=7)
            else:
                if slot > local:
                    slot -= timedelta(days=1)
                next_slot = slot + timedelta(days=1)
            return int(slot.timestamp() * 1000), int(next_slot.timestamp() * 1000)
        except ValueError, TypeError, AttributeError:
            raise ManifestError("schedule needs a valid weekday and HH:MM time") from None

    async def _internal_get(self, plugin_id, key):
        async with self.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT value FROM plugin_kv WHERE plugin_id=? AND key=?", (plugin_id, key)
            )
        return json.loads(rows[0][0]) if rows else None

    async def _internal_set(self, plugin_id, key, value):
        async with self.store.write() as conn:
            await conn.execute(
                "INSERT OR REPLACE INTO plugin_kv(plugin_id,key,value) VALUES(?,?,?)",
                (plugin_id, key, json.dumps(value)),
            )

    async def _schedules(self):
        next_at = None
        for name, schedule in self.core_schedules.items():
            slot, upcoming = self._scheduled_slot(schedule, {}, self.clock())
            next_at = min(next_at or upcoming, upcoming)
            key = "host:schedule:" + name
            async with self.store.read() as conn:
                rows = await conn.execute_fetchall(
                    "SELECT value FROM plugin_kv WHERE plugin_id='core' AND key=?", (key,)
                )
            if rows and int(rows[0][0]) < slot:
                await self.enqueue_core(name)
            if not rows or int(rows[0][0]) != slot:
                async with self.store.write() as conn:
                    await conn.execute(
                        "INSERT OR REPLACE INTO plugin_kv(plugin_id,key,value) VALUES('core',?,?)",
                        (key, str(slot)),
                    )
        for plugin_id, manifest in self.manifests.items():
            if self.states[plugin_id]["state"] != "enabled":
                continue
            for schedule in manifest.get("contributes", {}).get("schedules", []):
                slot, upcoming = self._scheduled_slot(
                    schedule, self.get_settings(plugin_id), self.clock()
                )
                next_at = min(next_at or upcoming, upcoming)
                key = "host:schedule:" + schedule["id"]
                last = await self._internal_get(plugin_id, key)
                if last != slot:
                    await self._queue(plugin_id, "schedule:" + schedule["id"], {})
                    await self._internal_set(plugin_id, key, slot)
        return next_at

    async def _loop(self):
        while not self._closed:
            self._wake.clear()
            await self.refresh_models()
            next_schedule = await self._schedules()
            while (row := await self._claim()) is not None:
                task = asyncio.create_task(self._run(row))
                self.running[row["id"]] = (row["plugin_id"], task)
            async with self.store.read() as conn:
                rows = await conn.execute_fetchall(
                    "SELECT min(j.run_after) FROM job j LEFT JOIN plugin p ON "
                    "p.id=j.plugin_id WHERE j.status='queued' AND (j.plugin_id='core' OR "
                    "p.state='enabled' OR (p.state='waiting' AND j.kind='migrate'))"
                )
            future = [
                int(t) for t in [rows[0][0], next_schedule] if t is not None and t > self.clock()
            ]
            timeout = max(0, (min(future) - self.clock()) / 1000) if future else None
            if getattr(self.store, "backend", None) == "postgres":
                timeout = (
                    min(timeout, _PG_LEASE_POLL_SECONDS)
                    if timeout is not None
                    else _PG_LEASE_POLL_SECONDS
                )
            try:
                async with asyncio.timeout(timeout):
                    await self._wake.wait()
            except TimeoutError:
                pass
