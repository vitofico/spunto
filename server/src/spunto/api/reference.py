"""An offline rendered OpenAPI reference with no remote scripts or assets."""

import html
import json

from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse

from spunto.api.deps import ServicesDep

router = APIRouter()


@router.get("/api/v1/docs", response_class=HTMLResponse, include_in_schema=False)
async def reference(request: Request, services: ServicesDep):
    document = request.app.openapi()
    warning = (
        "<p><strong>Not encrypted</strong>: this instance uses plain HTTP.</p>"
        if not services.settings.secure
        else ""
    )
    sections = []
    for path, operations in document["paths"].items():
        for method, operation in operations.items():
            schema = {
                key: operation[key]
                for key in ("parameters", "requestBody", "responses")
                if key in operation
            }
            sections.append(
                f"<section><h2>{html.escape(method.upper())} {html.escape(path)}</h2>"
                f"<p>{html.escape(operation.get('description', operation.get('summary', '')))}</p>"
                f"<pre>{html.escape(json.dumps(schema, indent=2, ensure_ascii=False))}</pre>"
                "</section>"
            )
    models = html.escape(json.dumps(document.get("components", {}), indent=2, ensure_ascii=False))
    return HTMLResponse(
        (
            '<!doctype html><html lang="en"><meta charset="utf-8"><meta '
            'name="viewport" '
            'content="width=device-width,initial-scale=1"><title>Spunto '
            "API</title><body><h1>Spunto API v1</h1>"
        )
        + warning
        + (
            "<p>Authenticate with a session cookie or an Authorization bearer "
            "token. Cookie writes require a matching Origin. Creates accept "
            "Idempotency-Key; edits require If-Match.</p><p><a "
            'href="/api/v1/openapi.json">OpenAPI JSON</a></p>'
        )
        + "".join(sections)
        + "<h2>Schemas</h2><pre>"
        + models
        + "</pre></body></html>"
    )
