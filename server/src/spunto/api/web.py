"""Read-only SPA hosting with safe theme attributes before the first paint."""

import asyncio
import json
import re
from pathlib import Path
from urllib.parse import unquote

from fastapi import APIRouter, Request
from fastapi.responses import FileResponse, HTMLResponse

from spunto.api.deps import ServicesDep
from spunto.api.errors import Problem
from spunto.host.themes import theme_ids

router = APIRouter(include_in_schema=False)
SUFFIXES = {
    ".js",
    ".css",
    ".woff2",
    ".svg",
    ".ico",
    ".png",
    ".jpg",
    ".jpeg",
    ".webp",
    ".gif",
    ".txt",
    ".json",
}


async def attributes(services, request):
    theme = "carta"
    accent = None
    # Public hosted routes have no workspace context, even with a session cookie.
    # Preferences and operator packs hydrate through the authenticated client API.
    public_hosted = (
        services.hosted is not None and getattr(services.store, "_context", None) is None
    )
    registry = None if public_hosted else services.host.themes
    if registry is not None:
        async with services.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT key,value FROM setting WHERE key IN ('theme','theme_accent')"
            )
            for row in rows:
                try:
                    chosen = json.loads(row["value"])
                    if row["key"] == "theme" and registry.available(chosen):
                        theme = chosen
                    elif row["key"] == "theme_accent" and isinstance(chosen, str):
                        accent = chosen
                except ValueError, TypeError:
                    pass
    cookie = {}
    raw = request.cookies.get("theme", "")
    if len(raw) <= 1024:
        try:
            parsed = json.loads(unquote(raw))
            if isinstance(parsed, dict):
                cookie = parsed
        except ValueError, TypeError:
            pass
    value = cookie.get("theme")
    available = (
        registry.available(value) if registry else isinstance(value, str) and value in theme_ids()
    )
    if available:
        if value != theme:
            accent = None
        theme = value
    if "accent" in cookie:
        accent = cookie["accent"]
    if registry is None or not isinstance(accent, str) or not registry.valid_accent(theme, accent):
        accent = None
    mode = cookie.get("mode")
    reading = cookie.get("reading")
    size = cookie.get("size")
    return dict(
        theme=theme,
        **({"accent": accent} if accent is not None else {}),
        mode=mode if mode in ("system", "light", "dark") else "system",
        reading=reading if reading in ("serif", "sans") else "serif",
        size=size if size in ("s", "m", "l") else "m",
    )


@router.api_route(
    "/{path:path}", methods=["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"]
)
async def spa(path: str, request: Request, services: ServicesDep):
    if (
        request.method not in ("GET", "HEAD")
        or path.startswith(("api/", "a/"))
        or path in ("api", "a", "mcp", "healthz", "readyz")
    ):
        raise Problem(404, "not_found", "That endpoint is unavailable.")
    if path == "index.html":
        path = ""
    root = services.settings.web_dir.resolve()
    target = (root / path).resolve()
    if not target.is_relative_to(root) or any(part.startswith(".") for part in Path(path).parts):
        raise Problem(404, "not_found", "That file is unavailable.")
    if path and await asyncio.to_thread(target.is_file):
        if target.suffix not in SUFFIXES:
            raise Problem(404, "not_found", "That file is unavailable.")
        hashed = bool(re.search(r"[-.][A-Za-z0-9_-]{8,}\.", target.name))
        return FileResponse(
            target,
            headers={
                "Cache-Control": "public, max-age=31536000, immutable"
                if hashed
                else "public, max-age=3600"
            },
        )
    if Path(path).suffix or path.startswith(("assets/", "fonts/")):
        raise Problem(404, "not_found", "That file is unavailable.")
    index = root / "index.html"
    if not await asyncio.to_thread(index.is_file):
        raise Problem(404, "web_not_built", "Build the local web client before opening this page.")
    html = await asyncio.to_thread(index.read_text)
    attrs = await attributes(services, request)
    opening = (
        '<html lang="en" ' + " ".join(f'data-{key}="{value}"' for key, value in attrs.items()) + ">"
    )
    html = re.sub(r"<html\b[^>]*>", opening, html, count=1, flags=re.IGNORECASE)
    if attrs["theme"] not in theme_ids():
        stylesheet = "/api/v1/themes/" + attrs["theme"] + ".css"
        if attrs.get("accent"):
            stylesheet += "?accent=" + attrs["accent"]
        html = re.sub(
            r"</head>",
            f'<link rel="stylesheet" href="{stylesheet}"></head>',
            html,
            count=1,
            flags=re.IGNORECASE,
        )
    return HTMLResponse(html, headers={"Cache-Control": "no-store", "Vary": "Cookie"})
