"""Operator-only discovery: read metadata now, import trusted code on enable."""

import ast
import hashlib
import importlib
import importlib.metadata
import re
import sys
from dataclasses import dataclass
from importlib.resources import files
from pathlib import Path
from types import ModuleType

from spunto.host.manifest import ManifestError, read_manifest

ENTRY = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*:[A-Za-z_][A-Za-z0-9_]*\Z")


@dataclass
class Source:
    identity: str
    path: Path
    local: bool = False
    entry: str | None = None
    error: str | None = None
    source_kind: str = "unknown"

    @property
    def origin(self):
        if self.source_kind == "bundled":
            return "bundled"
        if self.source_kind in {"folder", "entrypoint"}:
            return "operator"
        return "unknown"

    def load(self, entry):
        module, name = entry.split(":")
        if self.local:
            namespace = (
                "_spunto_operator_" + hashlib.sha256(self.identity.encode()).hexdigest()[:24]
            )
            if namespace not in sys.modules:
                package = ModuleType(namespace)
                package.__path__ = [str(self.path)]
                package.__package__ = namespace
                sys.modules[namespace] = package
            module = namespace + "." + module
        return getattr(importlib.import_module(module), name)


def _local_module(directory, entry):
    if not isinstance(entry, str) or not ENTRY.fullmatch(entry):
        raise ManifestError("entry must be a Python module:object")
    relative = Path(*entry.split(":")[0].split("."))
    candidates = (directory / relative.with_suffix(".py"), directory / relative / "__init__.py")
    for path in candidates:
        if path.is_file() and path.resolve().is_relative_to(directory.resolve()):
            return path
    raise ManifestError("entry module is missing from the plugin folder")


def check_folder(directory: Path):
    """The CLI's static half; never execute/import the candidate's Python."""
    path = directory / "plugin.toml"
    if not path.is_file() or path.stat().st_size > 65_536:
        raise ManifestError("plugin.toml is missing or exceeds 64 KiB")
    value = read_manifest(directory)
    if value["kind"] != "server" or not value.get("entry"):
        raise ManifestError("operator plugins require kind=server and a local entry")
    entry_path = _local_module(directory, value["entry"])
    if entry_path.stat().st_size > 1_048_576:
        raise ManifestError("entry Python source exceeds 1 MiB")
    try:
        ast.parse(entry_path.read_text(), filename=entry_path.name)
    except OSError, UnicodeError, SyntaxError:
        raise ManifestError("entry Python source is unreadable or has invalid syntax") from None
    return value


def _failed(source, reason, reserved):
    salt = 0
    while True:
        plugin_id = (
            "failed-" + hashlib.sha256(f"{source.identity}:{salt}".encode()).hexdigest()[:16]
        )
        if plugin_id not in reserved:
            break
        salt += 1
    # Never expose unvalidated names, permissions, settings or contributions.
    return {
        "id": plugin_id,
        "name": "Unavailable operator plugin",
        "version": "0",
        "api": "1.0",
        "kind": "server",
        "description": reason,
        "permissions": [],
        "default": "off",
    }


def _reason(error):
    # Error strings are host-authored, except a supplied API version.
    if "plugin API" in str(error):
        version = re.search(r"plugin API ([0-9.]{1,16}) is unsupported", str(error))
        return (
            f"Plugin API {version[1] if version else 'invalid'} is unsupported; "
            "host supports 1.0, 0.9 and 0.4."
        )
    return "Plugin manifest or entry is invalid; run spunto plugin check on its folder."


def discover(settings):
    """Return validated manifests, import bindings and isolated failure reasons."""
    manifests, bindings, failures = [], {}, {}
    reserved = set()
    bundled = files("spunto.plugins")
    sources = [
        Source("bundled:" + path.name, Path(str(path)), source_kind="bundled")
        for path in sorted(bundled.iterdir(), key=lambda p: p.name)
        if path.is_dir() and path.joinpath("plugin.toml").is_file()
    ]
    directory = getattr(settings, "plugins_dir", None) or settings.data_dir / "plugins"
    if directory.is_dir():
        try:
            sources.extend(
                Source("folder:" + str(path.resolve()), path, local=True, source_kind="folder")
                for path in sorted(directory.iterdir())
                if path.is_dir()
            )
        except OSError:
            sources.append(
                Source(
                    "folder:" + str(directory),
                    directory,
                    error="Plugin directory is unreadable.",
                    source_kind="folder",
                )
            )
    for entry in sorted(
        importlib.metadata.entry_points(group="spunto.plugins"), key=lambda e: e.name
    ):
        identity = f"entrypoint:{entry.dist.name if entry.dist else ''}:{entry.name}:{entry.value}"
        module = entry.value.split(":")[0]
        path = Path(*module.split("."))
        module_file = None
        if entry.dist is not None and ENTRY.fullmatch(entry.value):
            for relative in (path.with_suffix(".py"), path / "__init__.py"):
                candidate = Path(entry.dist.locate_file(relative))
                if candidate.is_file():
                    module_file = candidate
                    break
        sources.append(
            Source(
                identity,
                module_file.parent if module_file else directory,
                entry=entry.value,
                error=None if module_file else "Installed entry module is missing or invalid.",
                source_kind="entrypoint",
            )
        )
    for source in sources:
        try:
            if source.error:
                raise ManifestError(source.error)
            if (source.path / "plugin.toml").stat().st_size > 65_536:
                raise ManifestError("plugin.toml exceeds 64 KiB")
            value = check_folder(source.path) if source.local else read_manifest(source.path)
            if source.entry is not None:
                if value["kind"] != "server" or value.get("entry") != source.entry:
                    raise ManifestError("entry point does not match its sidecar manifest")
            if value["id"] in reserved:
                raise ManifestError("plugin ID conflicts with an already installed plugin")
            reserved.add(value["id"])
            if source.local or source.entry is not None:
                value["default"] = "off"
        except (ManifestError, TypeError, ValueError, OSError) as error:
            reason = (
                "Plugin ID conflicts with an already installed plugin."
                if "ID conflicts" in str(error)
                else _reason(error)
            )
            value = _failed(source, reason, reserved)
            failures[value["id"]] = reason
            reserved.add(value["id"])
        bindings[value["id"]] = source
        manifests.append(value)
    return manifests, bindings, failures
