"""Pinned TLS/read-only protocol tests; no real mailbox or network account."""

import asyncio
import time
from types import SimpleNamespace

import pytest

from spunto.net import imap


@pytest.mark.anyio
@pytest.mark.parametrize(
    "url",
    [
        "imap://mail.invalid",
        "imaps://mail.invalid:0",
        "imaps://owner.invalid",
        "imaps://user:synthetic@mail.invalid",
    ],
)
async def test_invalid_imap_origin_never_resolves_or_connects(url):
    async def resolve(*args):
        raise AssertionError("invalid destination must not resolve")

    with pytest.raises(imap.MailboxError, match="imap_destination_or_options_invalid"):
        await imap.guarded_read(
            url,
            username="owner",
            password="synthetic",
            check=lambda: None,
            run_blocking=asyncio.to_thread,
            resolver=resolve,
            own_hosts=["owner.invalid"],
        )


@pytest.mark.anyio
@pytest.mark.parametrize(
    "address", ["127.0.0.1", "169.254.169.254", "168.63.129.16", "::1", "fd00:ec2::254"]
)
async def test_imap_blocks_loopback_metadata_and_link_local(address):
    called = []

    async def resolve(host, port):
        return [address]

    async def run(work):
        called.append(True)

    with pytest.raises(imap.MailboxError, match="imap_destination_blocked"):
        await imap.guarded_read(
            "imaps://mail.invalid",
            username="owner",
            password="synthetic",
            check=lambda: None,
            run_blocking=run,
            resolver=resolve,
        )
    assert called == []


@pytest.mark.anyio
async def test_imap_private_configured_dns_is_pinned_and_cancellation_propagates(monkeypatch):
    calls = []

    async def resolve(host, port):
        calls.append((host, port))
        return ["192.168.99.20"]

    monkeypatch.setattr(imap, "_poll", lambda *args: {"addresses": args[1]})
    result = await imap.guarded_read(
        "imaps://mail.invalid:9993",
        username="owner",
        password="synthetic",
        check=lambda: None,
        run_blocking=asyncio.to_thread,
        resolver=resolve,
    )
    assert result == {"addresses": ("192.168.99.20",)} and calls == [("mail.invalid", 9993)]
    count = 0

    def check():
        nonlocal count
        count += 1
        if count > 1:
            raise PermissionError("synthetic generation retired")

    with pytest.raises(PermissionError, match="synthetic generation retired"):
        await imap.guarded_read(
            "imaps://mail.invalid",
            username="owner",
            password="synthetic",
            check=check,
            run_blocking=asyncio.to_thread,
            resolver=resolve,
        )


def test_pinned_socket_uses_numeric_tcp_original_sni_and_caps_literal(monkeypatch):
    calls = []
    sock = SimpleNamespace(close=lambda: None)
    monkeypatch.setattr(
        imap.socket,
        "create_connection",
        lambda address, timeout: calls.append((address, timeout)) or sock,
    )
    connection = imap._PinnedIMAP.__new__(imap._PinnedIMAP)
    connection.addresses, connection.port = ["192.168.99.20"], 993
    connection.deadline = time.monotonic() + 25
    connection.tls_hostname = "mail.example.invalid"
    connection.ssl_context = SimpleNamespace(
        wrap_socket=lambda raw, server_hostname: calls.append(server_hostname) or raw
    )
    assert connection._create_socket(8) is sock
    assert calls == [(("192.168.99.20", 993), 8), "mail.example.invalid"]
    with pytest.raises(imap.MailboxError, match="imap_response_too_large"):
        connection.read(imap.MAX_MESSAGE + 2)


def test_protocol_readonly_peek_uid_rollover_and_oversize(monkeypatch):
    calls = []

    class Connection:
        def __init__(self, *args):
            pass

        def step(self):
            pass

        def login(self, *args):
            return "OK", []

        def select(self, folder, readonly):
            calls.append(("select", folder, readonly))
            return "OK", []

        def response(self, key):
            return key, [b"2"]

        def uid(self, command, *args):
            calls.append((command, args))
            if command == "SEARCH":
                return "OK", [b"1 2"]
            if args[1] == "(UID RFC822.SIZE)":
                return "OK", [
                    b"1 (UID "
                    + args[0].encode()
                    + b" RFC822.SIZE "
                    + (b"3" if args[0] == "1" else b"999999")
                    + b")"
                ]
            return "OK", [(b"1 (UID 1 BODY[] {3}", b"abc")]

        def shutdown(self):
            calls.append("shutdown")

    monkeypatch.setattr(imap, "_PinnedIMAP", Connection)
    result = imap._poll(
        "mail.invalid", ["192.168.99.20"], 993, "owner", "synthetic", "INBOX", 90, 1, 5
    )
    assert result == {
        "uidvalidity": 2,
        "messages": [{"uid": 1, "raw": b"abc"}, {"uid": 2, "error": "email_message_too_large"}],
    }
    assert calls[0] == ("select", '"INBOX"', True)
    assert calls[1] == ("SEARCH", (None, "UID", "1:*"))
    assert any("BODY.PEEK" in str(call) for call in calls)
    assert not any("STORE" in str(call) or "EXPUNGE" in str(call) for call in calls)
    assert calls[-1] == "shutdown"


@pytest.mark.anyio
async def test_own_public_alias_is_rejected_before_worker_starts(monkeypatch):
    resolved, started = [], []

    async def resolve(host, port):
        resolved.append((host, port))
        return ["93.184.216.34"]

    async def local():
        return {"192.168.99.10"}

    async def worker(work):
        started.append(True)

    monkeypatch.setattr(imap, "_local_addresses", local)
    with pytest.raises(imap.MailboxError, match="imap_destination_blocked"):
        await imap.guarded_read(
            "imaps://alias.invalid",
            username="synthetic",
            password="synthetic",
            check=lambda: None,
            run_blocking=worker,
            resolver=resolve,
            own_hosts=["owner.invalid"],
        )
    assert resolved == [("alias.invalid", 993), ("owner.invalid", 443)]
    assert not started


@pytest.mark.parametrize("operation", ["read", "readline"])
def test_expired_and_trickled_receive_respect_absolute_worker_deadline(operation):
    calls = []

    class Trickle:
        def settimeout(self, value):
            calls.append(value)

        def recv(self, size):
            time.sleep(0.01)
            return b"x"

    connection = imap._PinnedIMAP.__new__(imap._PinnedIMAP)
    connection.sock, connection._incoming = Trickle(), bytearray()
    connection.deadline = time.monotonic() - 1

    def receive():
        return connection.read(100) if operation == "read" else connection.readline()

    with pytest.raises(imap.MailboxError, match="imap_timeout"):
        receive()
    assert not calls
    connection.deadline = time.monotonic() + 0.04
    started = time.monotonic()
    with pytest.raises(imap.MailboxError, match="imap_timeout"):
        receive()
    assert time.monotonic() - started < 0.15
    assert calls[-1] < calls[0]


def test_socket_fragment_buffer_preserves_lines_and_literal_boundaries():
    chunks = iter([b"* OK hello\r\nabc", b"def\r\n"])
    connection = imap._PinnedIMAP.__new__(imap._PinnedIMAP)
    connection.sock = SimpleNamespace(settimeout=lambda _: None, recv=lambda _: next(chunks, b""))
    connection.deadline, connection._incoming = time.monotonic() + 1, bytearray()
    assert connection.readline() == b"* OK hello\r\n"
    assert connection.read(5) == b"abcde"
    assert connection.readline() == b"f\r\n"
