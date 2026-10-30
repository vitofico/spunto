"""Data-only, closed first-party manifests and generated settings."""

import copy
import re
import tomllib
from pathlib import Path

ID = re.compile(r"[a-z][a-z0-9-]{1,23}\Z")
PERMISSIONS = {
    "notes.read",
    "notes.create",
    "notes.update_own",
    "notes.review",
    "suggest",
    "annotate",
    "search.text",
    "ai.chat",
    "ai.embed",
    "ai.transcribe",
    "ai.vision",
    "net.fetch",
    "attachments.read",
    "attachments.write",
    "schedule",
    "routes",
    "mcp",
    "mic",
}
ROOT = {
    "id",
    "name",
    "version",
    "api",
    "kind",
    "runtime",
    "category",
    "description",
    "entry",
    "requires",
    "default",
    "data_version",
    "creates",
    "permissions",
    "contributes",
    "settings",
    "concurrency",
    "ui",
    "needs",
}
CONTRIBUTIONS = {
    "commands": ({"id", "title", "where", "online"}, 3),
    "views": ({"id", "title", "filter"}, 1),
    "panel": ({"title", "mode", "route"}, 1),
    "meta": ({"title"}, 1),
    "slip": ({"title"}, 1),
    "search_source": ({"title", "route"}, 1),
    "mcp_tools": ({"name", "description", "read_only", "annotations"}, 3),
    "schedules": ({"id", "weekday", "time", "daily"}, 10),
}
SCHEMA_KEYS = {
    "type",
    "properties",
    "required",
    "additionalProperties",
    "title",
    "description",
    "enum",
    "default",
    "minimum",
    "maximum",
    "minLength",
    "maxLength",
    "items",
    "format",
    "x-secret",
    "x-scope",
}


class ManifestError(ValueError):
    """A manifest or output violates the plugin contract."""


def closed(value: object, fields: set[str], name: str) -> dict:
    if not isinstance(value, dict) or set(value) - fields:
        raise ManifestError(f"{name} has unknown fields or is not an object")
    return value


def schema(value: dict, *, root: bool = True) -> None:
    closed(value, SCHEMA_KEYS, "settings")
    kind = value.get("type")
    if "x-secret" in value and type(value["x-secret"]) is not bool:
        raise ManifestError("x-secret must be boolean")
    if value.get("x-secret") and (kind != "string" or "default" in value):
        raise ManifestError("secret settings are strings without defaults")
    if root:
        if kind != "object":
            raise ManifestError("settings must be an object")
        if not isinstance(value.get("properties", {}), dict):
            raise ManifestError("settings properties must be an object")
        for child in value.get("properties", {}).values():
            schema(child, root=False)
        if set(value.get("required", [])) - value.get("properties", {}).keys():
            raise ManifestError("settings required names must exist")
    elif kind not in {"string", "boolean", "integer", "number", "array"}:
        raise ManifestError("unsupported settings type")
    if kind == "array":
        items = value.get("items", {})
        schema(items, root=False)
        if items.get("type") != "string":
            raise ManifestError("settings arrays contain strings only")
    if value.get("format") not in {None, "time", "label"}:
        raise ManifestError("unsupported settings format")
    if value.get("x-scope") not in {None, "device"}:
        raise ManifestError("unsupported setting scope")
    if "default" in value and not root:
        validate_value(value, value["default"])


def validate_value(prop: dict, value: object) -> None:
    kind = prop["type"]
    valid = {
        "string": isinstance(value, str),
        "integer": type(value) is int,
        "number": type(value) in {int, float},
        "boolean": type(value) is bool,
        "array": isinstance(value, list) and all(isinstance(v, str) for v in value),
    }.get(kind, False)
    if not valid or ("enum" in prop and value not in prop["enum"]):
        raise ManifestError("setting has an invalid value")
    if kind in {"integer", "number"}:
        if value < prop.get("minimum", float("-inf")) or value > prop.get("maximum", float("inf")):
            raise ManifestError("setting is outside its range")
    if kind == "string":
        if not prop.get("minLength", 0) <= len(value) <= prop.get("maxLength", 100000):
            raise ManifestError("setting length is outside its range")
        if prop.get("format") == "time" and not re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", value):
            raise ManifestError("setting time must be HH:MM")


def settings_values(manifest: dict, values: dict, *, defaults: bool = True) -> dict:
    props = manifest.get("settings", {}).get("properties", {})
    if set(values) - props.keys():
        raise ManifestError("unknown setting")
    result = {}
    for key, prop in props.items():
        if prop.get("x-secret") or prop.get("x-scope") == "device":
            if key in values:
                raise ManifestError("secret and device settings cannot be stored")
            continue
        if key in values:
            validate_value(prop, values[key])
            result[key] = values[key]
        elif defaults and "default" in prop:
            result[key] = copy.deepcopy(prop["default"])
    if (
        set(manifest.get("settings", {}).get("required", []))
        - result.keys()
        - {k for k, v in props.items() if v.get("x-secret") or v.get("x-scope") == "device"}
    ):
        raise ManifestError("required setting is missing")
    return result


def validate_manifest(value: dict) -> dict:
    from spunto.host.views import compile_filter

    closed(value, ROOT, "manifest")
    required = {"id", "name", "version", "api", "kind", "description", "permissions"}
    if required - value.keys() or not ID.fullmatch(value.get("id", "")):
        raise ManifestError("manifest requires a valid id and required fields")
    if any(
        not isinstance(value[key], str) or not 1 <= len(value[key]) <= maximum
        for key, maximum in (("name", 80), ("version", 80), ("description", 1000), ("api", 16))
    ):
        raise ManifestError("manifest name, version, description and API must be bounded text")
    if value["api"] not in {"0.4", "0.9", "1.0"}:
        raise ManifestError(
            f"plugin API {value['api']} is unsupported; host supports 1.0, 0.9 and 0.4"
        )
    if value["kind"] not in {"server", "device", "theme"}:
        raise ManifestError("unknown plugin kind")
    if value.get("runtime", "in-process") != "in-process":
        raise ManifestError("unsupported runtime")
    if value.get("default", "off") not in {"on", "on-with-ai", "off"}:
        raise ManifestError("unknown plugin default")
    if value.get("category", "data") not in {
        "capture",
        "organize",
        "recall",
        "develop",
        "agents",
        "data",
        "look",
    }:
        raise ManifestError("unknown category")
    if not isinstance(value["permissions"], list) or any(
        not isinstance(p, str)
        or (
            p not in PERMISSIONS
            and p != "net.out:<configured>"
            and not re.fullmatch(r"net\.out:[A-Za-z0-9._-]+", p)
        )
        for p in value["permissions"]
    ):
        raise ManifestError("unknown permission")
    if "mic" in value["permissions"] and value["kind"] != "device":
        raise ManifestError("mic requires a device plugin")
    if "notes.review" in value["permissions"] and (
        value["id"] != "ask" or value.get("entry") != "spunto.plugins.ask:plugin"
    ):
        raise ManifestError("reviewed note actions are reserved for Ask")
    if "notes.create" in value["permissions"] and value.get("creates") not in {
        "machine",
        "import",
        "external",
        "owner",
    }:
        raise ManifestError("notes.create requires creates")
    if value.get("creates") not in {None, "machine", "import", "external", "owner"}:
        raise ManifestError("unknown creates kind")
    if value.get("creates") == "owner" and any(
        permission.startswith("ai.") for permission in value["permissions"]
    ):
        raise ManifestError("owner-authored creation cannot include AI permissions")
    if value.get("creates") == "owner" and "notes.update_own" in value["permissions"]:
        raise ManifestError("owner-authored notes require reviewed edits")
    if type(value.get("data_version", 0)) is not int or value.get("data_version", 0) < 0:
        raise ManifestError("invalid data_version")
    if type(value.get("concurrency", 1)) is not int or not 1 <= value.get("concurrency", 1) <= 4:
        raise ManifestError("concurrency must be 1 to 4")
    if value.get("ui", 1) != 1:
        raise ManifestError("unsupported vocabulary")
    if value.get("entry") and not re.fullmatch(
        r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*:[A-Za-z_][A-Za-z0-9_]*",
        value["entry"],
    ):
        raise ManifestError("entry must be a Python module:object")
    if value.get("requires") and any(
        p not in PERMISSIONS and p != "attachments" for p in value["requires"]
    ):
        raise ManifestError("unknown requirement")
    contributes = closed(value.get("contributes", {}), set(CONTRIBUTIONS), "contributes")
    for contribution, permission in (("schedules", "schedule"), ("mcp_tools", "mcp")):
        if contributes.get(contribution) and permission not in value["permissions"]:
            raise ManifestError("contribution requires its permission")
    for name, items in contributes.items():
        fields, budget = CONTRIBUTIONS[name]
        items = items if isinstance(items, list) else [items]
        if len(items) > budget:
            raise ManifestError(f"{name} exceeds its budget of {budget}")
        seen = set()
        for item in items:
            closed(item, fields, name)
            key = item.get("id", item.get("name"))
            if name in {"commands", "views", "schedules", "mcp_tools"}:
                if (
                    not isinstance(key, str)
                    or not re.fullmatch(r"[a-z][a-z0-9-]*", key)
                    or key in seen
                ):
                    raise ManifestError(f"invalid or duplicate {name} id")
                seen.add(key)
            if name == "commands" and (
                not isinstance(item.get("title"), str)
                or not set(item.get("where", [])) <= {"palette", "slash", "note-actions"}
            ):
                raise ManifestError("invalid command")
            if name == "views":
                compile_filter(item.get("filter", {}), plugin_id=value["id"], now=0)
            if name == "mcp_tools":
                if (
                    len(value["id"] + "_" + key) >= 40
                    or not isinstance(item.get("description"), str)
                    or len(item["description"]) >= 300
                    or type(item.get("read_only")) is not bool
                ):
                    raise ManifestError("invalid MCP tool")
                closed(
                    item.get("annotations", {}),
                    {"readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint"},
                    "annotations",
                )
                annotations = item.get("annotations", {})
                if any(type(hint) is not bool for hint in annotations.values()):
                    raise ManifestError("MCP annotations must be boolean")
                if (
                    "readOnlyHint" in annotations
                    and annotations["readOnlyHint"] != item["read_only"]
                ):
                    raise ManifestError("MCP readOnlyHint must agree with read_only")
    if "settings" in value:
        schema(value["settings"])
    needs = value.get("needs", [])
    if not isinstance(needs, list) or len(needs) > 10:
        raise ManifestError("needs must contain at most 10 setting requirements")
    seen = set()
    for requirement in needs:
        closed(requirement, {"setting", "reason"}, "needs")
        key, reason = requirement.get("setting"), requirement.get("reason")
        if (
            not isinstance(key, str)
            or key in seen
            or key not in value.get("settings", {}).get("properties", {})
            or not isinstance(reason, str)
            or not 1 <= len(reason) <= 120
        ):
            raise ManifestError("needs must name distinct declared settings with bounded reasons")
        seen.add(key)
    settings_values(value, {})
    return copy.deepcopy(value)


def read_manifest(path: Path) -> dict:
    try:
        with (path / "plugin.toml" if path.is_dir() else path).open("rb") as handle:
            return validate_manifest(tomllib.load(handle))
    except (OSError, tomllib.TOMLDecodeError) as error:
        raise ManifestError("plugin.toml is not readable valid TOML") from error
