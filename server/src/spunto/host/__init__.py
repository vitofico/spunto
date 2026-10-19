"""Public API for the reviewed, bundled plugin host."""

from spunto.ai.output import Output, field
from spunto.host.manifest import ManifestError, read_manifest, validate_manifest
from spunto.host.plugin import ContextError, Ctx, Plugin
from spunto.host.runtime import Host
from spunto.host.views import compile_filter
from spunto.host.vocabulary import validate_annotation, validate_component, validate_output

__all__ = [
    "ContextError",
    "Ctx",
    "Host",
    "ManifestError",
    "Plugin",
    "Output",
    "field",
    "compile_filter",
    "read_manifest",
    "validate_annotation",
    "validate_component",
    "validate_manifest",
    "validate_output",
]
