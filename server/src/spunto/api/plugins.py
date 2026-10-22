"""Owner plugin management and the enabled-only plugin dispatcher."""

import asyncio
import hashlib
import json
import math
import os
import unicodedata
from typing import Annotated

from fastapi import APIRouter, Body, Query, Request, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator

from spunto.ai.errors import AIError
from spunto.ai.quota import QuotaExceeded
from spunto.ai.vectors import VectorUnavailable
from spunto.api.deps import PrincipalDep, ServicesDep, SessionDep
from spunto.api.errors import Problem, problem_response
from spunto.host import ContextError, ManifestError

router = APIRouter(prefix="/api/v1", tags=["Plugins"])


class EnableBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    permissions: list[str] = Field(max_length=30)


class AIRoute(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    chat: str | None = None
    embed: str | None = None
    transcribe: str | None = None
    vision: str | None = None


class Preferences(BaseModel):
    model_config = ConfigDict(extra="forbid")
    plugin_order: list[str] | None = Field(None, max_length=100)
    hidden_mcp_tools: list[str] | None = Field(None, max_length=300)
    hidden_views: list[str] | None = Field(None, max_length=100)
    theme: str | None = Field(None, min_length=2, max_length=24)
    theme_accent: str | None = Field(None, min_length=2, max_length=24)
    owner_display_name: str | None = Field(None, max_length=80, strict=True)

    @field_validator("owner_display_name", mode="before")
    @classmethod
    def display_name(cls, value):
        if isinstance(value, str):
            value = value.strip()
            if any(unicodedata.category(char) in {"Cc", "Cs", "Zl", "Zp"} for char in value):
                raise ValueError("Use a single line without control characters.")
        return value


def _host(services, plugin_id=None, *, enabled=False, retained=False):
    host = services.host
    if host is None or (
        plugin_id is not None
        and plugin_id not in host.manifests
        and not (retained and plugin_id in host.removed_manifests)
    ):
        raise Problem(404, "not_found", "That plugin is unavailable.")
    if enabled and host.states[plugin_id]["state"] != "enabled":
        raise Problem(404, "not_found", "That plugin is unavailable.")
    return host


def _owner(principal):
    if not principal.owner:
        raise Problem(403, "owner_required", "Use an owner session or device.")


async def _call(awaitable):
    try:
        return await awaitable
    except Problem:
        raise
    except QuotaExceeded as error:
        wait = {"Retry-After": str(math.ceil(error.retry_after))}
        raise Problem(429, error.code, error.detail, wait) from None
    except AIError as error:
        status = {"validation": 422, "refused": 403}.get(error.category, 503)
        raise Problem(status, error.code, error.detail) from None
    except VectorUnavailable:
        raise Problem(
            503,
            "vectors_unavailable",
            "The note search index is still preparing. "
            "Check indexing in Settings → AI and try again.",
        ) from None
    except KeyError:
        raise Problem(404, "not_found", "That plugin action is unavailable.") from None
    except ContextError as error:
        code = str(error)
        if code == "plugin_disabled":
            raise Problem(404, "not_found", "That plugin is unavailable.") from None
        if code == "plugin_pinned_by_environment":
            raise Problem(409, "pinned_by_environment", "Set in the environment.") from None
        known = {
            "owner_required",
            "route_token_denied",
            "write_scope_required",
            "permission_denied",
            "fetch_option_denied",
            "network_destination_unconfigured",
        }
        raise Problem(
            403,
            code if code in known else "plugin_call_denied",
            "This plugin call is not permitted.",
        ) from None
    except ManifestError, ValueError, TypeError:
        raise Problem(
            422, "validation_failed", "The plugin request does not match its contract."
        ) from None
    except Exception:
        raise Problem(
            503, "plugin_call_failed", "The plugin could not complete this action."
        ) from None


def _permission_sentences(manifest):
    descriptions = {
        "notes.read": "Reads your notes",
        "notes.create": "Creates new notes",
        "notes.update_own": "Updates its own notes while they remain unedited",
        "suggest": "Proposes edits for your review",
        "annotate": "Adds note information",
        "search.text": "Attaches searchable text",
        "schedule": "Runs scheduled jobs",
        "routes": "Adds plugin routes",
        "mcp": "Adds tools for agents",
        "attachments.read": "Reads attachments",
        "attachments.write": "Writes attachments",
        "mic": "Uses your device microphone",
        "net.fetch": "Fetches public web pages",
    }
    sentences = []
    for permission in manifest["permissions"]:
        if permission.startswith("net.out:"):
            destination = next(iter(manifest.get("network_destinations", [])), None)
            sentences.append("Connects to " + (destination or "the configured host"))
        elif permission.startswith("ai."):
            destination = manifest.get("ai_destinations", {}).get(permission[3:])
            if destination:
                sentences.append(
                    "Uses "
                    + destination["model"]
                    + " on "
                    + (destination["host"] or destination["profile"])
                    + (
                        "; leaves your network"
                        if destination["leaves_network"]
                        else "; stays on your network"
                    )
                )
            else:
                sentences.append("Requires the " + permission[3:] + " model")
        else:
            sentences.append(descriptions[permission])
    if any(permission.startswith(("ai.", "net.")) for permission in manifest["permissions"]):
        sentences.append("Never reads private notes")
    return sentences


def _secret_status(manifest):
    result = {}
    for key, prop in manifest.get("settings", {}).get("properties", {}).items():
        if not prop.get("x-secret"):
            continue
        variable = f"SPUNTO_PLUGIN_{manifest['id'].replace('-', '_').upper()}_{key.upper()}"
        result[key] = {
            "set": bool(os.environ.get(variable) or os.environ.get(variable + "_FILE")),
            "source": "environment",
        }
    return result


@router.get("/plugins")
async def catalog(principal: PrincipalDep, services: ServicesDep):
    _owner(principal)
    host = _host(services)
    result = host.catalog()
    async with services.store.read() as conn:
        for item in result:
            rows = await conn.execute_fetchall(
                "SELECT coalesce(sum(length(CAST(value AS BLOB))),0) AS n FROM plugin_kv "
                "WHERE plugin_id=? "
                "UNION ALL SELECT coalesce(sum(length(CAST(data AS BLOB))),0) FROM plugin_record "
                "WHERE plugin_id=? "
                "UNION ALL SELECT coalesce(sum(length(CAST(payload AS BLOB))),0) FROM annotation "
                "WHERE plugin_id=? "
                "UNION ALL SELECT coalesce(sum(length(CAST(payload AS BLOB))),0) FROM suggestion "
                "WHERE plugin_id=? "
                "UNION ALL SELECT coalesce(sum(length(CAST(text AS BLOB))),0) FROM note_text "
                "WHERE plugin_id=?",
                [item["id"]] * 5,
            )
            item["data_size_bytes"] = sum(row[0] for row in rows)
            counts = {}
            for table in ("plugin_kv", "plugin_record", "annotation", "suggestion", "note_text"):
                counted = await conn.execute_fetchall(
                    f"SELECT count(*) FROM {table} WHERE plugin_id=?",  # noqa: S608
                    (item["id"],),
                )
                counts[table] = counted[0][0]
            item["data_counts"] = counts
            item["data_count"] = sum(counts.values())
            item["permission_sentences"] = _permission_sentences(item)
            item["secrets"] = _secret_status(host.manifests.get(item["id"], {}))
    return {"plugins": result, "plugins_rev": host.plugins_rev}


@router.get("/plugins/manifest")
async def manifest(request: Request, principal: PrincipalDep, services: ServicesDep):
    _owner(principal)
    host = _host(services)
    from fastapi.responses import JSONResponse

    preferences = await _preferences(services)
    payload = {
        **host.manifest(),
        "theme": host.themes.selected(preferences["theme"], preferences["theme_accent"]),
        "theme_catalog": host.themes.catalog(),
        "hidden_views": preferences["hidden_views"],
    }
    response = JSONResponse(payload)
    # Theme preferences are read asynchronously; hash the exact response so a
    # concurrent preference change cannot retain an older body under a new rev.
    etag = f'"{hashlib.sha256(response.body).hexdigest()}"'
    if request.headers.get("if-none-match") == etag:
        return Response(status_code=304, headers={"ETag": etag})
    response.headers["ETag"] = etag
    return response


def _toggle_receipt(host, plugin_id):
    row = next(item for item in host.catalog(include_retired=True) if item["id"] == plugin_id)
    return {**row, "plugins_rev": host.plugins_rev}


@router.post("/plugins/{plugin_id}/enable")
async def enable(plugin_id: str, body: EnableBody, principal: PrincipalDep, services: ServicesDep):
    _owner(principal)
    host = _host(services, plugin_id)
    permissions = host.manifests[plugin_id]["permissions"]
    if set(body.permissions) != set(permissions) or len(body.permissions) != len(
        set(body.permissions)
    ):
        response = problem_response(
            409, "permissions_changed", "Accept the current permission list."
        )
        payload = json.loads(response.body)
        payload["permissions"] = permissions
        from fastapi.responses import JSONResponse

        return JSONResponse(payload, 409, media_type="application/problem+json")
    await _call(host.enable(plugin_id, body.permissions))
    return _toggle_receipt(host, plugin_id)


@router.post("/plugins/{plugin_id}/disable")
async def disable(plugin_id: str, principal: PrincipalDep, services: ServicesDep):
    _owner(principal)
    await _call(_host(services, plugin_id, retained=True).disable(plugin_id))
    return _toggle_receipt(services.host, plugin_id)


@router.get("/plugins/{plugin_id}/settings")
async def get_settings(plugin_id: str, principal: PrincipalDep, services: ServicesDep):
    _owner(principal)
    host = _host(services, plugin_id)
    return {
        "schema": host.manifests[plugin_id].get("settings", {"type": "object", "properties": {}}),
        "values": host.get_settings(plugin_id),
        "secrets": _secret_status(host.manifests[plugin_id]),
    }


@router.put("/plugins/{plugin_id}/settings")
async def put_settings(
    plugin_id: str, session: SessionDep, services: ServicesDep, body: Annotated[dict, Body()]
):
    host = _host(services, plugin_id)
    await _call(host.set_settings(plugin_id, body))
    return {
        "values": host.get_settings(plugin_id),
        "secrets": _secret_status(host.manifests[plugin_id]),
    }


@router.delete("/plugins/{plugin_id}/data", status_code=204)
async def delete_data(plugin_id: str, session: SessionDep, services: ServicesDep):
    await _call(_host(services, plugin_id, retained=True).delete_data(plugin_id))
    return Response(status_code=204)


@router.put("/plugins/{plugin_id}/ai-route")
async def ai_route(plugin_id: str, body: AIRoute, session: SessionDep, services: ServicesDep):
    values = body.model_dump(exclude_unset=True)
    if any(value is None for value in values.values()):
        raise Problem(422, "validation_failed", "Omit capabilities to use their default profiles.")
    host = _host(services, plugin_id)
    return {"ai_route": await _call(host.set_ai_route(plugin_id, values))}


@router.get("/plugins/{plugin_id}/panel")
async def panel(plugin_id: str, note_id: str, principal: PrincipalDep, services: ServicesDep):
    host = _host(services, plugin_id, enabled=True)
    return await _call(host.invoke_panel(plugin_id, note_id, principal=principal))


@router.post("/plugins/{plugin_id}/commands/{command}")
async def command(
    plugin_id: str,
    command: str,
    principal: PrincipalDep,
    services: ServicesDep,
    body: Annotated[dict | None, Body()] = None,
):
    _owner(principal)
    host = _host(services, plugin_id, enabled=True)
    _arguments(body or {})
    return await _call(host.invoke_command(plugin_id, command, body or {}, principal=principal))


async def _preferences(services):
    host = _host(services)
    result = {
        "plugin_order": [item["id"] for item in host.catalog()],
        "hidden_mcp_tools": [],
        "hidden_views": [],
        "theme": "carta",
        "theme_accent": None,
        "owner_display_name": "",
    }
    async with services.store.read() as conn:
        rows = await conn.execute_fetchall(
            "SELECT key,value FROM setting WHERE key IN "
            "('hidden_mcp_tools','hidden_views','theme','theme_accent','owner_display_name')"
        )
    for row in rows:
        result[row["key"]] = json.loads(row["value"])
    return result


@router.get("/settings")
async def settings(session: SessionDep, services: ServicesDep):
    return await _preferences(services)


@router.put("/settings")
async def put_preferences(body: Preferences, session: SessionDep, services: ServicesDep):
    host = _host(services)
    values = body.model_dump(exclude_unset=True)
    if any(
        (value is None and key != "theme_accent")
        or (isinstance(value, list) and len(value) != len(set(value)))
        for key, value in values.items()
    ):
        raise Problem(422, "validation_failed", "Preferences require unique lists.")
    current = await _preferences(services)
    theme = values.get("theme", current["theme"])
    if "theme" in values and values["theme"] != current["theme"] and "theme_accent" not in values:
        values["theme_accent"] = None
    accent = values.get("theme_accent", current["theme_accent"])
    if ("theme" in values or "theme_accent" in values) and (
        not host.themes.available(theme) or not host.themes.valid_accent(theme, accent)
    ):
        raise Problem(422, "validation_failed", "That colour pack or accent is unavailable.")
    if "plugin_order" in values and set(values["plugin_order"]) != {
        item["id"] for item in host.catalog()
    }:
        raise Problem(422, "validation_failed", "plugin_order must name each plugin once.")
    declared_tools = {
        m["id"] + "_" + tool["name"]
        for m in host.manifests.values()
        for tool in m.get("contributes", {}).get("mcp_tools", [])
    }
    declared_views = {
        m["id"] + "/" + view["id"]
        for m in host.manifests.values()
        for view in m.get("contributes", {}).get("views", [])
    }
    if (
        set(values.get("hidden_mcp_tools", [])) - declared_tools
        or set(values.get("hidden_views", [])) - declared_views
    ):
        raise Problem(422, "validation_failed", "Preferences name an unknown contribution.")
    async with services.store.write() as conn:
        for key, value in values.items():
            await conn.execute(
                "INSERT INTO setting(key,value) VALUES(?,?) ON CONFLICT(key) "
                "DO UPDATE SET value=excluded.value",
                (key, json.dumps(value)),
            )
        for position, plugin_id in enumerate(values.get("plugin_order", [])):
            await conn.execute("UPDATE plugin SET position=? WHERE id=?", (position, plugin_id))
            host.states[plugin_id]["position"] = position
        await host._bump(conn)
    return await _preferences(services)


def _arguments(value):
    try:
        invalid = (
            not isinstance(value, dict) or len(json.dumps(value, allow_nan=False).encode()) > 65536
        )
    except ValueError, TypeError:
        invalid = True
    if invalid:
        raise Problem(422, "validation_failed", "Plugin arguments must be an object under 64 KB.")


@router.get("/changes/wait")
async def changes(
    services: ServicesDep,
    principal: PrincipalDep,
    cursor: str | None = None,
    plugins_rev: int = Query(0, ge=0),
    wait_seconds: float = Query(25, ge=0, le=25, alias="timeout"),
):
    supplied_epoch, supplied_seq = None, 0
    if cursor:
        try:
            supplied_epoch, raw_seq = cursor.rsplit(".", 1)
            supplied_seq = int(raw_seq)
            if supplied_seq < 0 or not supplied_epoch:
                raise ValueError
        except ValueError:
            raise Problem(
                422, "validation_failed", "cursor must be sync_epoch.change_seq."
            ) from None
    deadline = asyncio.get_running_loop().time() + wait_seconds
    event = services.store.change_event
    while True:
        event.clear()
        async with services.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT key,value FROM meta WHERE key IN ('sync_epoch','change_seq','plugins_rev')"
            )
        meta = {row["key"]: row["value"] for row in rows}
        seq, rev = int(meta["change_seq"]), int(meta["plugins_rev"])
        changed = bool(
            (supplied_epoch is not None and supplied_epoch != meta["sync_epoch"])
            or seq > supplied_seq
            or rev > plugins_rev
        )
        remaining = deadline - asyncio.get_running_loop().time()
        if changed or remaining <= 0:
            return {
                "cursor": meta["sync_epoch"] + "." + str(seq),
                "plugins_rev": rev,
                "changed": changed,
            }
        try:
            async with asyncio.timeout(remaining):
                await event.wait()
        except TimeoutError:
            continue


@router.api_route(
    "/plugins/{plugin_id}/{path:path}",
    methods=["GET", "POST", "PUT", "PATCH", "DELETE"],
    include_in_schema=False,
)
async def dispatch(
    plugin_id: str, path: str, request: Request, principal: PrincipalDep, services: ServicesDep
):
    host = _host(services, plugin_id, enabled=True)
    args = dict(request.query_params)
    if request.method != "GET":
        try:
            body = await request.json() if await request.body() else {}
        except ValueError:
            raise Problem(
                422, "validation_failed", "Use a JSON object for plugin arguments."
            ) from None
        if not isinstance(body, dict):
            raise Problem(422, "validation_failed", "Use a JSON object for plugin arguments.")
        args.update(body)
    _arguments(args)
    return await _call(
        host.invoke_route(plugin_id, path, args, method=request.method, principal=principal)
    )
