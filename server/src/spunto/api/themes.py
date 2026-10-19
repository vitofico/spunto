"""Public, generated colour-only stylesheets for operator-installed packs."""

import hashlib

from fastapi import APIRouter, Request
from fastapi.responses import Response

from spunto.api.deps import ServicesDep
from spunto.api.errors import Problem

router = APIRouter(prefix="/api/v1/themes")


@router.get("/{theme_id}.css")
async def stylesheet(
    theme_id: str, request: Request, services: ServicesDep, accent: str | None = None
):
    try:
        body = services.host.themes.css(theme_id, accent)
    except KeyError:
        raise Problem(404, "not_found", "That colour pack or accent is unavailable.") from None
    etag = '"' + hashlib.sha256(body.encode()).hexdigest() + '"'
    headers = {"ETag": etag, "Cache-Control": "no-cache"}
    if request.headers.get("if-none-match") == etag:
        return Response(status_code=304, headers=headers)
    return Response(body, media_type="text/css", headers=headers)
