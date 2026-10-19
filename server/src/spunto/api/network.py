"""Who is asking, and at which address (02 §9.3, §9.4)."""

import html
from ipaddress import ip_address

from starlette.requests import Request
from starlette.responses import HTMLResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from spunto.api.errors import problem_response
from spunto.config import Settings

# Orchestrators probe the pod's own address, never the public one.
_ANY_HOST = {"/healthz", "/readyz"}


def from_trusted_proxy(request: Request, settings: Settings) -> bool:
    return bool(request.client) and _trusted(request.client.host, settings)


def client_ip(request: Request, settings: Settings) -> str:
    """The client's address; X-Forwarded-For counts only when a trusted proxy sent it."""
    peer = request.client.host if request.client else ""
    if not _trusted(peer, settings):
        return peer
    hops = [hop.strip() for hop in request.headers.get("x-forwarded-for", "").split(",")]
    # Each proxy appends the address it saw, so the client is the last hop no trusted proxy added.
    for hop in reversed([hop for hop in hops if hop]):
        if not _trusted(hop, settings):
            return hop
    return peer


class WrongHostMiddleware:
    """Answer a request for any other host with the public address (D33).

    A browser that opened the server by its LAN address while the public URL
    names a hostname would otherwise reach a login whose cookie and Origin
    checks fail without a word.
    """

    def __init__(self, app: ASGIApp, settings: Settings):
        self.app = app
        self.settings = settings

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope["path"] in _ANY_HOST:
            return await self.app(scope, receive, send)
        request = Request(scope)
        host = request.headers.get("host", "")
        if from_trusted_proxy(request, self.settings):
            host = request.headers.get("x-forwarded-host", host)
        if _normal_host(host, self.settings.secure) == self.settings.public_host:
            return await self.app(scope, receive, send)
        url = self.settings.public_url
        if scope["path"].startswith("/api/"):
            response = problem_response(421, "wrong_host", f"This server lives at {url}.")
        else:
            link = html.escape(url)
            response = HTMLResponse(_PAGE.format(link=link), status_code=421)
        await response(scope, receive, send)


_PAGE = """<!doctype html>
<html lang="en">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Spunto</title>
<p>This server lives at <a href="{link}">{link}</a>. Open it there to sign in.</p>
</html>
"""


def _normal_host(host: str, secure: bool) -> str:
    host = host.strip().lower()
    return host.removesuffix(":443" if secure else ":80")


def _trusted(address: str, settings: Settings) -> bool:
    try:
        parsed = ip_address(address)
    except ValueError:
        return False
    return any(parsed in network for network in settings.trusted_proxies)


class SecurityHeadersMiddleware:
    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        async def with_headers(message):
            if message["type"] == "http.response.start":
                message.setdefault("headers", []).extend(
                    [
                        (b"referrer-policy", b"same-origin"),
                        (b"x-content-type-options", b"nosniff"),
                        (
                            b"content-security-policy",
                            (
                                b"default-src 'self'; script-src 'self'; "
                                b"img-src 'self' data: blob:; "
                                b"object-src 'none'; frame-ancestors 'none'"
                            ),
                        ),
                    ]
                )
            await send(message)

        await self.app(scope, receive, with_headers)
