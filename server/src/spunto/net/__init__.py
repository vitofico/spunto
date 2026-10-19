"""The plugin host's only outbound HTTP service."""

from spunto.net.fetch import FetchError, FetchResponse, guarded_fetch

__all__ = ["FetchError", "FetchResponse", "guarded_fetch"]
