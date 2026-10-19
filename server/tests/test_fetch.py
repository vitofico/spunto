import asyncio
import gzip
import logging
import ssl

import httpcore
import httpx
import pytest

from spunto.net import FetchError, guarded_fetch
from spunto.net import fetch as network

pytestmark = pytest.mark.anyio
PUBLIC = "93.184.216.34"


@pytest.fixture(autouse=True)
def no_local_dns(monkeypatch):
    async def local():
        return set()

    monkeypatch.setattr(network, "_local_addresses", local)


async def public_dns(host, port):
    return [PUBLIC]


class Fixture:
    def __init__(self, responses=None):
        self.requests = []
        self.responses = list(responses or [httpx.Response(200, json={"ok": True})])
        self.transport = httpx.MockTransport(self.send)
        self.revoked = False

    def check(self):
        if self.revoked:
            raise FetchError("plugin_generation_expired")

    async def send(self, request):
        self.requests.append(request)
        return self.responses.pop(0)

    async def fetch(self, url="https://public.test/resource", **kwargs):
        options = {"permissions": ["net.fetch"], "resolver": public_dns}
        options.update(kwargs)
        return await guarded_fetch(url, check=self.check, transport=self.transport, **options)


@pytest.mark.parametrize(
    "address",
    [
        "127.0.0.1",
        "127.5.6.7",
        "0.0.0.0",  # noqa: S104 -- forbidden destination fixture, never a bind
        "10.0.0.1",
        "172.16.1.2",
        "192.168.1.1",
        "169.254.169.254",
        "169.254.1.1",
        "100.100.100.200",
        "100.64.0.1",
        "224.0.0.1",
        "240.1.1.1",
        "168.63.129.16",
        "::",
        "::1",
        "fe80::1",
        "fd00::1",
        "ff02::1",
        "::ffff:127.0.0.1",
        "::ffff:169.254.169.254",
        "::ffff:10.0.0.1",
        "64:ff9b::a00:1",
        "2002:0a00:0001::1",
    ],
)
async def test_non_public_dns_answers_never_connect(address):
    fixture = Fixture()

    async def resolve(host, port):
        return [address]

    with pytest.raises(FetchError, match="fetch_address_blocked"):
        await fixture.fetch(resolver=resolve)
    assert not fixture.requests


@pytest.mark.parametrize(
    "address", ["127.0.0.1", "169.254.169.254", "::ffff:127.0.0.1", "fd00:ec2::254"]
)
async def test_dangerous_literals_block_even_with_configured_net_out(address):
    fixture = Fixture()
    host = f"[{address}]" if ":" in address else address
    with pytest.raises(FetchError, match="fetch_address_blocked"):
        await fixture.fetch(
            f"http://{host}:8080/",
            permissions=[f"net.out:{address}"],
            configured_hosts=[address],
        )
    assert not fixture.requests


@pytest.mark.parametrize("answers", [[PUBLIC, "10.0.0.1"], ["::1", PUBLIC]])
async def test_every_dns_answer_is_validated(answers):
    fixture = Fixture()

    async def resolve(host, port):
        return answers

    with pytest.raises(FetchError, match="fetch_address_blocked"):
        await fixture.fetch(resolver=resolve)
    assert not fixture.requests


async def test_configured_exact_destination_allows_lan_and_service_port():
    fixture = Fixture()

    async def resolve(host, port):
        assert (host, port) == ("source.lan", 8080)
        return ["192.168.10.20", "fd00::20"]

    response = await fixture.fetch(
        "http://source.lan:8080/api",
        permissions=["net.out:source.lan"],
        configured_hosts=["source.lan"],
        resolver=resolve,
        headers={"Authorization": "Bearer plugin-test-token"},
    )
    assert response.json() == {"ok": True}
    assert fixture.requests[0].headers["authorization"] == "Bearer plugin-test-token"


@pytest.mark.parametrize(
    ("host", "permissions", "configured", "code"),
    [
        ("source.lan", ["net.out:source.lan"], [], "fetch_port_blocked"),
        ("source.lan", ["net.fetch"], ["source.lan"], "fetch_port_blocked"),
        ("evil.source.lan", ["net.out:source.lan"], ["source.lan"], "fetch_permission_denied"),
        ("source.lan.evil.test", ["net.out:source.lan"], ["source.lan"], "fetch_permission_denied"),
    ],
)
async def test_lan_exception_requires_exact_grant_and_configured_destination(
    host, permissions, configured, code
):
    fixture = Fixture()
    with pytest.raises(FetchError, match=code):
        await fixture.fetch(
            f"http://{host}:8080/", permissions=permissions, configured_hosts=configured
        )
    assert not fixture.requests


async def test_unconfigured_net_out_does_not_allow_private_default_port():
    fixture = Fixture()

    async def resolve(host, port):
        return ["192.168.1.1"]

    with pytest.raises(FetchError, match="fetch_address_blocked"):
        await fixture.fetch(permissions=["net.out:public.test"], resolver=resolve)


@pytest.mark.parametrize(
    "url",
    [
        "file:///etc/passwd",
        "ftp://public.test/",
        "http://user:secret@public.test/",
        "http://@public.test/",
        "http://public.test:99999/",
        "http://[fe80::1%eth0]/",
        "http://public.test/\nsecret",
        "http://public.test\\@evil.test/",
        "/relative",
    ],
)
async def test_url_input_is_strict_and_errors_contain_no_secrets(url):
    fixture = Fixture()
    with pytest.raises(FetchError) as raised:
        await fixture.fetch(url)
    assert str(raised.value) == raised.value.code
    assert "secret" not in str(raised.value)
    assert not fixture.requests


class WireStream(httpcore.AsyncNetworkStream):
    def __init__(self):
        self.input = b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}"
        self.writes = []
        self.tls = []
        self.closed = False

    async def read(self, max_bytes, timeout=None):  # noqa: ASYNC109
        result, self.input = self.input[:max_bytes], self.input[max_bytes:]
        return result

    async def write(self, buffer, timeout=None):  # noqa: ASYNC109
        self.writes.append(buffer)

    async def start_tls(self, ssl_context, server_hostname=None, timeout=None):  # noqa: ASYNC109
        self.tls.append((ssl_context, server_hostname))
        return self

    def get_extra_info(self, info):
        return None

    async def aclose(self):
        self.closed = True


class WireBackend:
    def __init__(self):
        self.connections = []
        self.stream = WireStream()

    async def connect_tcp(self, host, port, **kwargs):
        self.connections.append((host, port))
        return self.stream


async def test_actual_transport_pins_dns_preserves_host_and_verified_tls(monkeypatch):
    backend = WireBackend()
    monkeypatch.setattr(network.httpcore, "AnyIOBackend", lambda: backend)
    calls = []

    async def rebinding_resolver(host, port):
        calls.append((host, port))
        return [PUBLIC] if len(calls) == 1 else ["127.0.0.1"]

    response = await guarded_fetch(
        "https://public.test/path?private-query",
        permissions=["net.fetch"],
        check=lambda: None,
        resolver=rebinding_resolver,
    )
    assert response.json() == {}
    assert calls == [("public.test", 443)]
    assert backend.connections == [(PUBLIC, 443)]
    assert b"Host: public.test\r\n" in b"".join(backend.stream.writes)
    context, sni = backend.stream.tls[0]
    assert sni == "public.test"
    assert context.check_hostname and context.verify_mode == ssl.CERT_REQUIRED
    assert backend.stream.closed


async def test_permission_failure_and_expired_generation_do_not_connect():
    fixture = Fixture()
    with pytest.raises(FetchError, match="fetch_permission_denied"):
        await fixture.fetch(permissions=[])
    fixture.revoked = True
    with pytest.raises(FetchError, match="plugin_generation_expired"):
        await fixture.fetch()
    assert not fixture.requests


async def test_generation_rechecked_after_resolution():
    fixture = Fixture()

    async def resolve(host, port):
        fixture.revoked = True
        return [PUBLIC]

    with pytest.raises(FetchError, match="plugin_generation_expired"):
        await fixture.fetch(resolver=resolve)
    assert not fixture.requests


async def test_same_host_rebinding_on_redirect_is_blocked():
    fixture = Fixture([httpx.Response(302, headers={"Location": "/next"})])
    calls = 0

    async def resolve(host, port):
        nonlocal calls
        calls += 1
        return [PUBLIC] if calls == 1 else ["10.0.0.1"]

    with pytest.raises(FetchError, match="fetch_address_blocked"):
        await fixture.fetch(resolver=resolve)
    assert calls == 2 and len(fixture.requests) == 1


@pytest.mark.parametrize("location", ["http://127.0.0.1/", "http://169.254.169.254/"])
async def test_redirect_into_internal_addresses_never_connects(location):
    fixture = Fixture([httpx.Response(307, headers={"Location": location})])
    with pytest.raises(FetchError, match="fetch_address_blocked"):
        await fixture.fetch()
    assert len(fixture.requests) == 1


async def test_redirect_permission_and_port_rechecked():
    fixture = Fixture([httpx.Response(302, headers={"Location": "https://other.test/"})])
    with pytest.raises(FetchError, match="fetch_permission_denied"):
        await fixture.fetch(permissions=["net.out:public.test"])
    fixture = Fixture([httpx.Response(302, headers={"Location": "https://public.test:8080/"})])
    with pytest.raises(FetchError, match="fetch_port_blocked"):
        await fixture.fetch()


@pytest.mark.parametrize("location", ["https://user:secret@public.test/", "http://@public.test/"])
async def test_redirect_userinfo_is_not_sent(location):
    fixture = Fixture([httpx.Response(302, headers={"Location": location})])
    with pytest.raises(FetchError):
        await fixture.fetch()
    assert len(fixture.requests) == 1


async def test_redirects_stop_after_five_hops():
    fixture = Fixture([httpx.Response(302, headers={"Location": "/next"}) for _ in range(6)])
    with pytest.raises(FetchError, match="fetch_too_many_redirects"):
        await fixture.fetch()
    assert len(fixture.requests) == 6


async def test_cross_origin_redirect_drops_credentials_and_response_cookies(monkeypatch, caplog):
    monkeypatch.setenv("HTTP_PROXY", "http://proxy.invalid:8888")
    monkeypatch.setenv("HTTPS_PROXY", "http://proxy.invalid:8888")
    fixture = Fixture(
        [
            httpx.Response(
                302, headers={"Location": "https://other.test/", "Set-Cookie": "secret=1"}
            ),
            httpx.Response(200, content=b"ok"),
        ]
    )
    with caplog.at_level(logging.DEBUG):
        await fixture.fetch(
            "https://public.test/?private-query",
            headers={"Authorization": "Bearer secret-token", "X-API-Key": "secret-api-key"},
        )
    first, second = fixture.requests
    assert "authorization" in first.headers
    assert not {"cookie", "authorization", "x-api-key", "referer"} & set(second.headers)
    assert second.headers["user-agent"].startswith("Spunto/")
    assert not any(
        value in caplog.text for value in ["private-query", "secret-token", "secret-api-key"]
    )


@pytest.mark.parametrize("code", [301, 302, 303])
async def test_post_redirect_rewrites_method_and_clears_body(code):
    fixture = Fixture(
        [
            httpx.Response(code, headers={"Location": "/next"}),
            httpx.Response(200),
        ]
    )
    await fixture.fetch(method="POST", body=b"payload", headers={"Content-Type": "text/plain"})
    assert fixture.requests[1].method == "GET"
    assert fixture.requests[1].content == b""
    assert "content-type" not in fixture.requests[1].headers


@pytest.mark.parametrize("code", [307, 308])
async def test_preserving_redirect_keeps_same_origin_body_but_blocks_cross_origin(code):
    fixture = Fixture([httpx.Response(code, headers={"Location": "/next"}), httpx.Response(200)])
    await fixture.fetch(method="POST", body=b"payload")
    assert fixture.requests[1].method == "POST" and fixture.requests[1].content == b"payload"
    fixture = Fixture([httpx.Response(code, headers={"Location": "https://other.test/"})])
    with pytest.raises(FetchError, match="fetch_redirect_body_blocked"):
        await fixture.fetch(method="POST", body=b"payload")
    assert len(fixture.requests) == 1


@pytest.mark.parametrize("code", [300, 304, 404])
async def test_only_actual_redirect_codes_follow_location(code):
    fixture = Fixture([httpx.Response(code, headers={"Location": "https://other.test/"})])
    result = await fixture.fetch()
    assert result.status_code == code and len(fixture.requests) == 1


class Chunks(httpx.AsyncByteStream):
    def __init__(self, chunks, delay=0, callback=None):
        self.chunks, self.delay, self.callback = chunks, delay, callback
        self.closed = False

    async def __aiter__(self):
        for chunk in self.chunks:
            if self.delay:
                await asyncio.sleep(self.delay)
            if self.callback:
                self.callback()
            yield chunk

    async def aclose(self):
        self.closed = True


async def test_declared_and_streamed_size_are_capped():
    for response in [
        httpx.Response(200, headers={"Content-Length": "100"}, stream=Chunks([b"x"])),
        httpx.Response(200, stream=Chunks([b"12345", b"67890"])),
    ]:
        fixture = Fixture([response])
        with pytest.raises(FetchError, match="fetch_too_large"):
            await fixture.fetch(max_bytes=8)
        assert response.is_closed


async def test_slow_body_and_slow_dns_share_total_deadline():
    stream = Chunks([b"ok"], delay=0.05)
    fixture = Fixture([httpx.Response(200, stream=stream)])
    with pytest.raises(FetchError, match="fetch_timeout"):
        await fixture.fetch(timeout=0.005)
    assert stream.closed

    async def resolve(host, port):
        await asyncio.sleep(0.05)
        return [PUBLIC]

    fixture = Fixture()
    with pytest.raises(FetchError, match="fetch_timeout"):
        await fixture.fetch(timeout=0.005, resolver=resolve)
    assert not fixture.requests


async def test_generation_expiry_mid_body_discards_result_and_closes_stream():
    fixture = Fixture()
    stream = Chunks([b"private body"], callback=lambda: setattr(fixture, "revoked", True))
    fixture.responses = [httpx.Response(200, stream=stream)]
    with pytest.raises(FetchError, match="plugin_generation_expired"):
        await fixture.fetch()
    assert stream.closed


@pytest.mark.parametrize("header", ["Cookie", "Referer", "Host", "Proxy-Authorization"])
async def test_forbidden_ambient_headers_never_connect(header):
    fixture = Fixture()
    with pytest.raises(FetchError, match="fetch_invalid_headers"):
        await fixture.fetch(headers={header: "secret-value"})
    assert not fixture.requests


@pytest.mark.parametrize(
    "options",
    [
        {"max_bytes": 5_000_001},
        {"max_bytes": 0},
        {"timeout": 11},
        {"timeout": float("nan")},
        {"timeout": -1},
    ],
)
async def test_callers_cannot_raise_global_limits(options):
    fixture = Fixture()
    with pytest.raises(FetchError, match="fetch_invalid_limits"):
        await fixture.fetch(**options)
    assert not fixture.requests


async def test_own_public_addresses_and_aliases_are_blocked():
    fixture = Fixture()
    with pytest.raises(FetchError, match="fetch_address_blocked"):
        await fixture.fetch(own_addresses=[PUBLIC])
    with pytest.raises(FetchError, match="fetch_address_blocked"):
        await fixture.fetch(own_hosts=["spunto.test"])
    with pytest.raises(FetchError, match="fetch_host_blocked"):
        await fixture.fetch(own_hosts=["public.test"])
    assert not fixture.requests


async def test_remote_network_errors_are_sanitized(caplog):
    fixture = Fixture()

    async def fail(request):
        raise httpx.ConnectError("https://public.test/?secret-query header: secret-token")

    fixture.transport = httpx.MockTransport(fail)
    with caplog.at_level(logging.DEBUG), pytest.raises(FetchError) as raised:
        await fixture.fetch()
    assert str(raised.value) == "fetch_connection_failed"
    assert "secret" not in caplog.text


async def test_json_error_does_not_echo_remote_content():
    fixture = Fixture([httpx.Response(200, content=b"invalid secret remote body")])
    response = await fixture.fetch()
    with pytest.raises(FetchError, match="fetch_invalid_json"):
        response.json()


async def test_accounting_counts_each_validated_destination_but_not_blocked_redirect():
    fixture = Fixture(
        [
            httpx.Response(302, headers={"Location": "https://other.test/"}),
            httpx.Response(302, headers={"Location": "http://169.254.169.254/"}),
        ]
    )
    hosts = []
    with pytest.raises(FetchError, match="fetch_address_blocked"):
        await fixture.fetch(on_destination=hosts.append)
    assert hosts == ["public.test", "other.test"]
    assert len(fixture.requests) == 2


async def test_actual_transport_rejects_compression_before_decompression(monkeypatch):
    backend = WireBackend()
    compressed = gzip.compress(b"x" * 20_000_000)
    backend.stream.input = (
        b"HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: "
        + str(len(compressed)).encode()
        + b"\r\n\r\n"
        + compressed
    )
    monkeypatch.setattr(network.httpcore, "AnyIOBackend", lambda: backend)

    async def decoding_is_forbidden(*args, **kwargs):
        raise AssertionError("Compressed response reached HTTPX decoding")
        yield b""  # Async iterator matching the streaming response API.

    monkeypatch.setattr(httpx.Response, "aiter_bytes", decoding_is_forbidden)
    with pytest.raises(FetchError, match="fetch_encoding_blocked"):
        await guarded_fetch(
            "https://public.test/",
            permissions=["net.fetch"],
            check=lambda: None,
            resolver=public_dns,
        )
    assert b"Accept-Encoding: identity\r\n" in b"".join(backend.stream.writes)
    assert backend.stream.closed


async def test_actual_transport_debug_logs_never_contain_remote_headers(monkeypatch, caplog):
    backend = WireBackend()
    backend.stream.input = (
        b"HTTP/1.1 200 OK\r\nSet-Cookie: provider-key-secret=abc\r\n"
        b"Location: /?private-query-secret\r\nContent-Length: 2\r\n\r\n{}"
    )
    monkeypatch.setattr(network.httpcore, "AnyIOBackend", lambda: backend)
    with caplog.at_level(logging.DEBUG, logger="httpcore"):
        await guarded_fetch(
            "https://public.test/?request-query-secret",
            permissions=["net.fetch"],
            headers={"Authorization": "Bearer request-token-secret"},
            check=lambda: None,
            resolver=public_dns,
        )
        logging.getLogger("httpcore.http11").debug("outside guarded request")
    assert "outside guarded request" in caplog.text
    assert not any(
        value in caplog.text
        for value in [
            "provider-key-secret",
            "private-query-secret",
            "request-query-secret",
            "request-token-secret",
        ]
    )


@pytest.mark.parametrize("grant_host", ["bücher.test", "xn--bcher-kva.test"])
async def test_idna_grant_configuration_dns_host_and_tls_use_one_canonical_host(
    monkeypatch, grant_host
):
    backend = WireBackend()
    monkeypatch.setattr(network.httpcore, "AnyIOBackend", lambda: backend)
    hosts = []

    async def resolve(host, port):
        hosts.append(host)
        return ["192.168.1.20"]

    await guarded_fetch(
        "https://bücher.test:8443/",
        permissions=[f"net.out:{grant_host}"],
        configured_hosts=["BÜCHER.TEST."],
        check=lambda: None,
        resolver=resolve,
    )
    assert hosts == ["xn--bcher-kva.test"]
    assert backend.connections == [("192.168.1.20", 8443)]
    assert b"Host: xn--bcher-kva.test:8443\r\n" in b"".join(backend.stream.writes)
    assert backend.stream.tls[0][1] == "xn--bcher-kva.test"


@pytest.mark.parametrize(
    "destination",
    ["http://public.test/next", "https://public.test:8443/next", "https://other.test/next"],
)
async def test_exact_origin_allowlist_rejects_redirect_scheme_port_host(destination):
    fixture = Fixture([httpx.Response(302, headers={"Location": destination})])
    with pytest.raises(FetchError, match="fetch_origin_blocked"):
        await fixture.fetch(
            allowed_origins=["https://public.test/approved"],
            headers={"Authorization": "Bearer synthetic-guard-token"},
        )
    assert len(fixture.requests) == 1


async def test_allowlisted_origin_change_strips_authorization():
    fixture = Fixture(
        [
            httpx.Response(302, headers={"Location": "https://other.test/next"}),
            httpx.Response(200, text="synthetic"),
        ]
    )
    await fixture.fetch(
        allowed_origins=["https://public.test/approved", "https://other.test/receiver"],
        headers={"Authorization": "Bearer synthetic-guard-token", "X-Fixture": "value"},
    )
    assert "authorization" in fixture.requests[0].headers
    assert "authorization" not in fixture.requests[1].headers
    assert "x-fixture" not in fixture.requests[1].headers


async def test_unconfigured_origin_is_rejected_before_dns():
    fixture = Fixture()
    called = []

    async def resolve(host, port):
        called.append(host)
        return [PUBLIC]

    with pytest.raises(FetchError, match="fetch_origin_blocked"):
        await fixture.fetch(
            "https://public.test:8443/", allowed_origins=["https://public.test/"], resolver=resolve
        )
    assert not called and not fixture.requests


async def test_pre_send_guard_is_rechecked_after_each_redirect_dns():
    fixture = Fixture([httpx.Response(302, headers={"Location": "/next"}), httpx.Response(200)])
    dns_calls, guards = [], []

    async def resolve(host, port):
        dns_calls.append(host)
        return [PUBLIC]

    async def guard():
        guards.append(len(dns_calls))
        if len(dns_calls) == 2:
            raise PermissionError("synthetic source changed during redirect DNS")

    with pytest.raises(PermissionError, match="source changed"):
        await fixture.fetch(resolver=resolve, pre_send=guard)
    assert guards == [1, 2] and len(fixture.requests) == 1


async def test_pinned_stream_rechecks_async_source_after_connect_before_write(monkeypatch):
    backend = WireBackend()
    monkeypatch.setattr(network.httpcore, "AnyIOBackend", lambda: backend)
    checks = []

    async def guard():
        checks.append(bool(backend.connections))
        if backend.connections:
            raise PermissionError("synthetic source changed while connecting")

    with pytest.raises(PermissionError, match="source changed"):
        await guarded_fetch(
            "https://public.test/",
            permissions=["net.fetch"],
            check=lambda: None,
            pre_send=guard,
            resolver=public_dns,
        )
    assert checks == [False, True]
    assert backend.connections and not backend.stream.writes
    assert backend.stream.closed
