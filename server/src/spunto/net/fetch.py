"""Guard HTTP destinations and pin the checked DNS answer through TCP and TLS.

The host supplies a generation-bound ``check`` and grants derived from owner
settings. Injected resolvers/transports are for the host's offline test harness;
plugins never receive those controls.
"""

import asyncio
import ipaddress
import json
import logging
import math
import socket
import ssl
from collections.abc import AsyncIterable, AsyncIterator, Awaitable, Callable, Mapping, Sequence
from contextvars import ContextVar
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlsplit

import httpcore
import httpx

from spunto import __version__

MAX_BYTES = 5_000_000
MAX_TIMEOUT = 10.0
MAX_REDIRECTS = 5
Resolver = Callable[[str, int], Awaitable[Sequence[str]]]
Check = Callable[[], None]
PreSend = Callable[[], Awaitable[None]]
_guarded_request = ContextVar("spunto_guarded_request", default=False)


class _GuardedLogFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        # httpcore's DEBUG traces include raw response headers and exception
        # text. Suppress those only in this task's guarded network operation;
        # changing a shared logger's level would race with other requests.
        return not _guarded_request.get()


def _guard_http_logs() -> None:
    for name in tuple(logging.Logger.manager.loggerDict):
        if name == "httpcore" or name.startswith("httpcore.") or name == "httpx":
            logger = logging.getLogger(name)
            if not any(isinstance(item, _GuardedLogFilter) for item in logger.filters):
                logger.addFilter(_GuardedLogFilter())


class FetchError(Exception):
    """A stable, safe error: neither the destination nor remote text is included."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


@dataclass(frozen=True)
class FetchResponse:
    status_code: int
    headers: httpx.Headers
    body: bytes

    def json(self) -> Any:
        try:
            return json.loads(self.body)
        except ValueError, UnicodeError, RecursionError:
            raise FetchError("fetch_invalid_json") from None


def _host(value: str) -> str:
    try:
        # Use the same IDNA implementation as the HTTP origin, Host and TLS SNI.
        return httpx.URL(scheme="http", host=value.rstrip(".")).raw_host.decode("ascii").lower()
    except ValueError, UnicodeError, httpx.InvalidURL:
        raise FetchError("fetch_host_blocked") from None


def _url(value: str) -> httpx.URL:
    try:
        if any(ord(character) <= 32 or ord(character) == 127 for character in value):
            raise ValueError
        url = httpx.URL(value)
        # Even an empty userinfo field is forbidden; no URL-derived credentials.
        if (
            url.scheme not in {"http", "https"}
            or not url.host
            or b"@" in url.netloc
            or "@" in urlsplit(value).netloc
            or url.userinfo
            or "%" in url.host
            or "\\" in value
        ):
            raise ValueError
        return url.copy_with(fragment=None)
    except ValueError, TypeError, httpx.InvalidURL:
        raise FetchError("fetch_invalid_url") from None


def _address(value: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address:
    try:
        if "%" in value:
            raise ValueError
        result = ipaddress.ip_address(value)
        if isinstance(result, ipaddress.IPv6Address) and result.ipv4_mapped:
            return result.ipv4_mapped
        return result
    except ValueError:
        raise FetchError("fetch_address_blocked") from None


def _validate_address(value: str, allow_private: bool, own: set[str]) -> None:
    address = _address(value)
    if (
        address.is_loopback
        or address.is_link_local
        or address.is_multicast
        or address.is_unspecified
        or address.is_reserved
        or str(address) in own
        or address == ipaddress.ip_address("168.63.129.16")
        or address == ipaddress.ip_address("fd00:ec2::254")
    ):
        raise FetchError("fetch_address_blocked")
    # Mapped IPv6 has been reduced above. Avoid transition mechanisms that can
    # conceal a non-public IPv4 destination from the ordinary IPv6 checks.
    if isinstance(address, ipaddress.IPv6Address) and (
        address.sixtofour or address.teredo or address in ipaddress.ip_network("64:ff9b::/96")
    ):
        raise FetchError("fetch_address_blocked")
    if not address.is_global and not (allow_private and address.is_private):
        raise FetchError("fetch_address_blocked")


async def _resolve(host: str, port: int) -> Sequence[str]:
    answers = await asyncio.get_running_loop().getaddrinfo(host, port, type=socket.SOCK_STREAM)
    return tuple(dict.fromkeys(answer[4][0] for answer in answers))


async def _local_addresses() -> set[str]:
    try:
        return {str(_address(value)) for value in await _resolve(socket.gethostname(), 0)}
    except OSError, FetchError:
        return set()


class _CheckedStream(httpcore.AsyncNetworkStream):
    def __init__(self, stream: httpcore.AsyncNetworkStream, check: Check, pre_send=None) -> None:
        self.stream, self.check, self.pre_send = stream, check, pre_send

    async def read(self, max_bytes: int, timeout: float | None = None) -> bytes:  # noqa: ASYNC109
        self.check()
        result = await self.stream.read(max_bytes, timeout)
        self.check()
        return result

    async def write(self, buffer: bytes, timeout: float | None = None) -> None:  # noqa: ASYNC109
        self.check()
        if self.pre_send is not None:
            await self.pre_send()
            self.check()
        await self.stream.write(buffer, timeout)
        self.check()

    async def start_tls(
        self,
        ssl_context,
        server_hostname=None,
        timeout=None,  # noqa: ASYNC109
    ) -> httpcore.AsyncNetworkStream:
        self.check()
        stream = await self.stream.start_tls(ssl_context, server_hostname, timeout)
        try:
            self.check()
        except BaseException:
            await stream.aclose()
            raise
        return _CheckedStream(stream, self.check, self.pre_send)

    def get_extra_info(self, info: str) -> Any:
        return self.stream.get_extra_info(info)

    async def aclose(self) -> None:
        await self.stream.aclose()


class _PinnedBackend(httpcore.AsyncNetworkBackend):
    def __init__(self, host: str, addresses: Sequence[str], check: Check, pre_send=None) -> None:
        self.pre_send = pre_send
        self.host = host
        self.addresses = addresses
        self.check = check
        self.backend = httpcore.AnyIOBackend()

    async def connect_tcp(
        self,
        host,
        port,
        timeout=None,  # noqa: ASYNC109 -- httpcore backend contract
        local_address=None,
        socket_options=None,
    ) -> httpcore.AsyncNetworkStream:
        self.check()
        if _host(host) != self.host:
            raise FetchError("fetch_host_blocked")
        for address in self.addresses:
            self.check()
            try:
                # The original hostname stays on the HTTP origin: httpcore uses
                # it for Host, TLS SNI and certificate hostname verification.
                stream = await self.backend.connect_tcp(
                    address,
                    port,
                    timeout=timeout,
                    local_address=local_address,
                    socket_options=socket_options,
                )
            except httpcore.ConnectError, httpcore.ConnectTimeout:
                continue
            try:
                self.check()
            except BaseException:
                await stream.aclose()
                raise
            return _CheckedStream(stream, self.check, self.pre_send)
        raise FetchError("fetch_connection_failed")


class _ResponseStream(httpx.AsyncByteStream):
    def __init__(self, stream: AsyncIterable[bytes]) -> None:
        self.stream = stream

    async def __aiter__(self) -> AsyncIterator[bytes]:
        async for chunk in self.stream:
            yield chunk

    async def aclose(self) -> None:
        await self.stream.aclose()


class _PinnedTransport(httpx.AsyncBaseTransport):
    def __init__(self, host: str, addresses: Sequence[str], check: Check, pre_send=None) -> None:
        self.pool = httpcore.AsyncConnectionPool(
            ssl_context=ssl.create_default_context(),
            network_backend=_PinnedBackend(host, addresses, check, pre_send),
            max_connections=1,
            max_keepalive_connections=0,
        )

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        response = await self.pool.handle_async_request(
            httpcore.Request(
                method=request.method,
                url=httpcore.URL(
                    scheme=request.url.raw_scheme,
                    host=request.url.raw_host,
                    port=request.url.port,
                    target=request.url.raw_path,
                ),
                headers=request.headers.raw,
                content=request.stream,
                extensions=request.extensions,
            )
        )
        return httpx.Response(
            response.status,
            headers=response.headers,
            stream=_ResponseStream(response.stream),
        )

    async def aclose(self) -> None:
        await self.pool.aclose()


def _headers(headers: Mapping[str, str] | None) -> httpx.Headers:
    try:
        supplied = httpx.Headers(headers)
        for key, value in supplied.multi_items():
            if key.lower() in {
                "cookie",
                "cookie2",
                "referer",
                "host",
                "proxy-authorization",
                "connection",
                "transfer-encoding",
                "content-length",
                "upgrade",
            } or any(ord(character) < 32 or ord(character) == 127 for character in value):
                raise ValueError
        supplied["User-Agent"] = f"Spunto/{__version__}"
        supplied["Accept-Encoding"] = "identity"
        return supplied
    except TypeError, ValueError, UnicodeError:
        raise FetchError("fetch_invalid_headers") from None


async def guarded_fetch(
    url: str,
    *,
    permissions: Sequence[str],
    check: Check,
    pre_send: PreSend | None = None,
    method: str = "GET",
    headers: Mapping[str, str] | None = None,
    body: bytes | None = None,
    max_bytes: int = MAX_BYTES,
    timeout: float = MAX_TIMEOUT,  # noqa: ASYNC109 -- public per-request total deadline
    configured_hosts: Sequence[str] = (),
    allowed_origins: Sequence[str] | None = None,
    own_addresses: Sequence[str] = (),
    own_hosts: Sequence[str] = (),
    on_destination: Callable[[str], None] | None = None,
    resolver: Resolver | None = None,
    transport: httpx.AsyncBaseTransport | None = None,
) -> FetchResponse:
    """Fetch with a total deadline, bounded bytes, and every redirect rechecked.

    ``configured_hosts`` is populated by the host from owner settings, never by
    an API/MCP caller. Explicit caller headers may include the plugin's own
    credentials; no cookies or ambient credentials are attached. Credentials
    and custom headers are discarded on a change of origin.
    """
    check()
    if pre_send is not None and not callable(pre_send):
        raise FetchError("fetch_invalid_guard")

    async def send_guard():
        check()
        if pre_send is not None:
            await pre_send()
        check()

    if (
        not isinstance(max_bytes, int)
        or isinstance(max_bytes, bool)
        or not 0 < max_bytes <= MAX_BYTES
        or not isinstance(timeout, (int, float))
        or isinstance(timeout, bool)
        or not math.isfinite(timeout)
        or not 0 < timeout <= MAX_TIMEOUT
    ):
        raise FetchError("fetch_invalid_limits")
    if method not in {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"}:
        raise FetchError("fetch_invalid_method")
    if body is not None and (not isinstance(body, bytes) or len(body) > max_bytes):
        raise FetchError("fetch_too_large")
    request_headers = _headers(headers)
    current = _url(url)

    def exact_origin(value):
        return (
            value.scheme,
            _host(value.host),
            value.port or (443 if value.scheme == "https" else 80),
        )

    origins = (
        {exact_origin(_url(value)) for value in allowed_origins}
        if allowed_origins is not None
        else None
    )
    configured = {_host(host) for host in configured_hosts}
    granted_hosts = {
        _host(value.removeprefix("net.out:"))
        for value in permissions
        if value.startswith("net.out:")
    }
    owned_hosts = {_host(host) for host in own_hosts}
    own = {str(_address(value)) for value in own_addresses}
    resolve = resolver or _resolve
    _guard_http_logs()
    guard_token = _guarded_request.set(True)
    try:
        async with asyncio.timeout(timeout):
            own.update(await _local_addresses())
            for own_host in owned_hosts:
                own.update(str(_address(value)) for value in await resolve(own_host, 443))
            for hop in range(MAX_REDIRECTS + 1):
                check()
                if origins is not None and exact_origin(current) not in origins:
                    raise FetchError("fetch_origin_blocked")
                host = _host(current.host)
                if host in owned_hosts:
                    raise FetchError("fetch_host_blocked")
                explicit = host in granted_hosts
                configured_destination = explicit and host in configured
                if not explicit and "net.fetch" not in permissions:
                    raise FetchError("fetch_permission_denied")
                port = current.port or (443 if current.scheme == "https" else 80)
                if not configured_destination and port not in {80, 443}:
                    raise FetchError("fetch_port_blocked")
                try:
                    addresses = (str(ipaddress.ip_address(host)),)
                except ValueError:
                    addresses = tuple(await resolve(host, port))
                check()
                if not addresses:
                    raise FetchError("fetch_dns_failed")
                for address in addresses:
                    _validate_address(address, configured_destination, own)
                active_transport = transport or _PinnedTransport(host, addresses, check, send_guard)
                response = None
                try:
                    await send_guard()
                    if on_destination is not None:
                        on_destination(host)
                    request = httpx.Request(
                        method,
                        current,
                        headers=request_headers,
                        content=body,
                        extensions={
                            "timeout": dict.fromkeys(("connect", "read", "write", "pool"), timeout)
                        },
                    )
                    response = await active_transport.handle_async_request(request)
                    check()
                    # Do not let HTTPX decompress a chunk before the byte cap
                    # can inspect it. A tiny compressed body can expand without
                    # bound, and the request explicitly asks for identity.
                    encoding = response.headers.get("content-encoding", "identity")
                    if encoding.strip().lower() != "identity":
                        raise FetchError("fetch_encoding_blocked")
                    if response.status_code in {301, 302, 303, 307, 308}:
                        if hop == MAX_REDIRECTS:
                            raise FetchError("fetch_too_many_redirects")
                        location = response.headers.get("location")
                        if not location:
                            raise FetchError("fetch_invalid_redirect")
                        # Parse before joining as URL parsers can erase control
                        # characters that the guard must reject.
                        if (
                            any(ord(c) <= 32 or ord(c) == 127 for c in location)
                            or "@" in urlsplit(location).netloc
                            or "\\" in location
                        ):
                            raise FetchError("fetch_invalid_redirect")
                        try:
                            destination = _url(str(current.join(location)))
                        except httpx.InvalidURL:
                            raise FetchError("fetch_invalid_redirect") from None
                        if (
                            response.status_code == 303
                            and method != "HEAD"
                            or (response.status_code in {301, 302} and method == "POST")
                        ):
                            method, body = "GET", None
                            request_headers.pop("content-type", None)
                        origin = (current.scheme, _host(current.host), current.port)
                        next_origin = (
                            destination.scheme,
                            _host(destination.host),
                            destination.port,
                        )
                        if origin != next_origin:
                            if body:
                                raise FetchError("fetch_redirect_body_blocked")
                            request_headers = httpx.Headers(
                                {
                                    key: value
                                    for key, value in request_headers.items()
                                    if key in {"accept", "accept-encoding", "user-agent"}
                                }
                            )
                        current = destination
                        continue
                    length = response.headers.get("content-length")
                    if length and (
                        not length.isdecimal()
                        or len(length) > len(str(max_bytes))
                        or int(length) > max_bytes
                    ):
                        raise FetchError("fetch_too_large")
                    chunks = []
                    size = 0
                    # Test transports may return an already-buffered response.
                    # Production always reads raw identity bytes from the wire.
                    chunks_stream = (
                        response.aiter_bytes()
                        if response.is_stream_consumed
                        else response.aiter_raw()
                    )
                    async for chunk in chunks_stream:
                        check()
                        size += len(chunk)
                        if size > max_bytes:
                            raise FetchError("fetch_too_large")
                        chunks.append(chunk)
                    check()
                    return FetchResponse(response.status_code, response.headers, b"".join(chunks))
                finally:
                    if response is not None:
                        await response.aclose()
                    if transport is None:
                        await active_transport.aclose()
    except FetchError:
        raise
    except TimeoutError:
        raise FetchError("fetch_timeout") from None
    except httpx.TimeoutException, httpcore.TimeoutException:
        raise FetchError("fetch_timeout") from None
    except PermissionError:
        # Source/generation denials are cancellation, not transient I/O errors.
        raise
    except OSError, httpx.HTTPError, httpcore.NetworkError, httpcore.ProtocolError:
        raise FetchError("fetch_connection_failed") from None
    finally:
        _guarded_request.reset(guard_token)
    raise FetchError("fetch_connection_failed")
