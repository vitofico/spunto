"""Every cookie-authenticated unsafe route refuses a write without a matching Origin."""

import re

import pytest
from conftest import PUBLIC_URL

from spunto.main import create_app

pytestmark = pytest.mark.anyio
PASSWORD = "correct horse battery"
# Password- or code-authenticated entry points: Origin is checked only when present (D33).
PASSWORD_ROUTES = {
    "/api/v1/setup",
    "/api/v1/auth/login",
    "/api/v1/auth/devices",
    "/api/v1/auth/pairing/exchange",
}


def unsafe_routes(app):
    # The public OpenAPI document lists every mounted route, however routers nest.
    for path, operations in app.openapi()["paths"].items():
        if not path.startswith("/api/") or path in PASSWORD_ROUTES:
            continue
        for method in operations.keys() & {"post", "put", "patch", "delete"}:
            yield method.upper(), re.sub(r"\{[^}]+\}", "synthetic-id", path)


async def test_cookie_writes_without_matching_origin_are_refused_on_every_route(server):
    setup = await server.client.post(
        "/api/v1/setup", json={"code": server.services.setup_code, "password": PASSWORD}
    )
    assert setup.status_code == 204
    routes = sorted(set(unsafe_routes(create_app(server.services))))
    assert len(routes) > 20
    leaks = []
    for method, path in routes:
        for headers in (
            {},
            {"Origin": "http://other.test"},
            {"Origin": PUBLIC_URL.replace("spunto.test", "spunto.test.other.test")},
        ):
            response = await server.client.request(method, path, json={}, headers=headers)
            if response.status_code != 403:
                leaks.append((method, path, headers.get("Origin"), response.status_code))
    assert leaks == [], "\n".join(map(str, leaks))
    # The session survived the sweep: nothing above was accepted as a write.
    assert (await server.client.get("/api/v1/credentials")).status_code == 200
