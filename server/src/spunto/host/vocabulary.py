"""Validate every stored and live UI payload before clients see it."""

import json

from spunto.host.manifest import ManifestError, closed, schema

FIELDS = {
    "text": {"text"},
    "markdown": {"text", "markdown"},
    "notes": {"ids", "items"},
    "pill": {"suggestion_id", "label", "action"},
    "command": {"id", "args", "title"},
    "form": {"schema"},
    "diff": {"hunks", "base_version", "text"},
}


def _text(value: object, limit: int) -> None:
    if not isinstance(value, str) or len(value) > limit:
        raise ManifestError(f"component text must contain at most {limit} characters")


def validate_component(value: dict) -> dict:
    if not isinstance(value, dict) or value.get("type") not in FIELDS:
        raise ManifestError("unknown component")
    kind = value["type"]
    closed(value, FIELDS[kind] | {"type", "fallback"}, "component")
    if kind in {"text", "markdown"}:
        _text(value.get("text", value.get("markdown")), 280 if kind == "text" else 4000)
    elif kind == "notes":
        entries = value.get("ids", value.get("items"))
        if not isinstance(entries, list) or len(entries) > 10:
            raise ManifestError("notes component allows at most 10 notes")
        for entry in entries:
            if isinstance(entry, str):
                _text(entry, 128)
            else:
                closed(entry, {"id", "reason"}, "note reference")
                _text(entry.get("id"), 128)
                if "reason" in entry:
                    _text(entry["reason"], 280)
    elif kind == "pill":
        _text(value.get("suggestion_id"), 128)
        _text(value.get("label"), 40)
        _text(value.get("action"), 40)
    elif kind == "command":
        _text(value.get("id"), 128)
        _text(value.get("title"), 24)
        if not isinstance(value.get("args", {}), dict):
            raise ManifestError("command args must be an object")
    elif kind == "form":
        schema(value.get("schema", {}))
    elif kind == "diff":
        if type(value.get("base_version")) is not int or not isinstance(value.get("hunks"), list):
            raise ManifestError("diff requires hunks and a base version")
        for hunk in value["hunks"]:
            closed(hunk, {"old_start", "new_start", "old_lines", "new_lines", "lines"}, "diff hunk")
            for key, entry in hunk.items():
                if key.endswith("start") and type(entry) is not int:
                    raise ManifestError("diff start must be an integer")
                if key.endswith("lines"):
                    if not isinstance(entry, list) or any(not isinstance(x, str) for x in entry):
                        raise ManifestError("diff lines must be strings")
    if "fallback" in value:
        fallback = value["fallback"]
        if fallback != "drop" and fallback != "Open on the web":
            validate_component(fallback)
    json.dumps(value, allow_nan=False)
    return value


def validate_output(value: object) -> object:
    if isinstance(value, list):
        if len(value) > 10:
            raise ManifestError("panel allows at most 10 items")
        for item in value:
            validate_component(item)
    elif isinstance(value, dict) and "type" in value:
        validate_component(value)
    elif isinstance(value, dict):
        closed(value, {"ui", "items", "components"}, "output")
        if value.get("ui", 1) != 1:
            raise ManifestError("unsupported vocabulary version")
        validate_output(value.get("items", value.get("components", [])))
    else:
        raise ManifestError("output must use the component vocabulary")
    return value


def validate_annotation(kind: str, value: object) -> None:
    if len(json.dumps(value, ensure_ascii=False, allow_nan=False).encode()) > 4096:
        raise ManifestError("annotation exceeds 4 KB")
    if kind.startswith("member:"):
        if value not in ({}, True, {"member": True}):
            raise ManifestError("membership annotation has an invalid payload")
    elif kind in {"panel", "meta", "slip"}:
        validate_output(value)
    else:
        raise ManifestError("unknown annotation kind")


def render_output(value):
    """Only the server adds HTML; plugins submit the closed Markdown vocabulary."""
    from spunto.core.render import render_markdown

    if isinstance(value, list):
        return [render_output(item) for item in value]
    if isinstance(value, dict):
        result = {key: render_output(item) for key, item in value.items()}
        if value.get("type") == "markdown":
            result["html"] = render_markdown(value.get("text", value.get("markdown", "")))
        return result
    return value
