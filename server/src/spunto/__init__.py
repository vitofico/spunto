"""Spunto: a self-hosted, AI-first notes server."""

import os

# The image sets SPUNTO_VERSION to its release tag at build time; a checkout reports "dev".
__version__ = os.environ.get("SPUNTO_VERSION", "dev")
