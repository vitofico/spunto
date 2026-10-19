"""Read-only IMAP over verified TLS, with checked DNS pinned through TCP/SNI."""

import asyncio
import heapq
import imaplib
import re
import socket
import ssl
import time
from urllib.parse import urlsplit

from spunto.net.fetch import (
    FetchError,
    _address,
    _host,
    _local_addresses,
    _resolve,
    _validate_address,
)

MAX_MESSAGE = 262_144


class MailboxError(OSError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


class _PinnedIMAP(imaplib.IMAP4_SSL):
    def _log(self, *args):
        # imaplib's optional debug ring otherwise retains raw LOGIN commands.
        pass

    def _mesg(self, *args, **kwargs):
        pass

    def __init__(self, hostname, addresses, port, deadline):
        self.addresses, self.deadline = addresses, deadline
        self.tls_hostname = hostname
        self._incoming = bytearray()
        super().__init__(hostname, port, ssl_context=ssl.create_default_context(), timeout=8)

    def _create_socket(self, timeout):
        for address in self.addresses:
            sock = None
            try:
                remaining = self.deadline - time.monotonic()
                if remaining <= 0:
                    raise MailboxError("imap_timeout")
                sock = socket.create_connection(
                    (address, self.port), timeout=min(timeout, remaining)
                )
                return self.ssl_context.wrap_socket(sock, server_hostname=self.tls_hostname)
            except ssl.SSLError:
                if sock is not None:
                    sock.close()
                raise
            except MailboxError:
                raise
            except OSError:
                if sock is not None:
                    sock.close()
        raise MailboxError("imap_connection_failed")

    def read(self, size):
        if size > MAX_MESSAGE + 1:
            raise MailboxError("imap_response_too_large")
        self.step()
        while len(self._incoming) < size:
            if not self._receive():
                break
        result = bytes(self._incoming[:size])
        del self._incoming[:size]
        self.step()
        return result

    def readline(self):
        self.step()
        while True:
            end = self._incoming.find(b"\n")
            if end >= 0:
                if end + 1 > imaplib._MAXLINE:
                    raise MailboxError("imap_response_too_large")
                result = bytes(self._incoming[: end + 1])
                del self._incoming[: end + 1]
                self.step()
                return result
            if len(self._incoming) >= imaplib._MAXLINE:
                raise MailboxError("imap_response_too_large")
            if not self._receive():
                result = bytes(self._incoming)
                self._incoming.clear()
                return result

    def _receive(self):
        self.step()
        data = self.sock.recv(8192)
        self.step()
        self._incoming.extend(data)
        return bool(data)

    def send(self, data):
        self.step()
        result = super().send(data)
        self.step()
        return result

    def step(self):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise MailboxError("imap_timeout")
        self.sock.settimeout(min(8, remaining))


def _poll(hostname, addresses, port, username, password, folder, after_uid, uidvalidity, limit):
    connection = None
    try:
        connection = _PinnedIMAP(hostname, addresses, port, time.monotonic() + 25)
        connection.step()
        if connection.login(username, password)[0] != "OK":
            raise MailboxError("imap_auth_failed")
        connection.step()
        if connection.select('"' + folder + '"', readonly=True)[0] != "OK":
            raise MailboxError("imap_folder_unavailable")
        response = connection.response("UIDVALIDITY")[1]
        validity = int(response[0]) if response and response[0] else None
        if validity is None or validity < 1:
            raise MailboxError("imap_invalid_response")
        after = after_uid if uidvalidity == validity else 0
        connection.step()
        status, matches = connection.uid("SEARCH", None, "UID", f"{after + 1}:*")
        if status != "OK" or not matches or not isinstance(matches[0], bytes):
            raise MailboxError("imap_invalid_response")
        # Some servers return the last UID for n:* when n is beyond the end.
        ids = heapq.nsmallest(
            limit, (int(uid) for uid in matches[0].split() if uid.isdigit() and int(uid) > after)
        )
        ids = sorted(set(ids))
        messages = []
        for uid in ids:
            connection.step()
            status, metadata = connection.uid("FETCH", str(uid), "(UID RFC822.SIZE)")
            sizes = [
                int(match[1])
                for item in metadata
                if isinstance(item, bytes) and (match := re.search(rb"RFC822.SIZE (\d+)", item))
            ]
            returned_uids = [
                int(match[1])
                for item in metadata
                if isinstance(item, bytes) and (match := re.search(rb"UID (\d+)", item))
            ]
            if status != "OK" or len(sizes) != 1 or returned_uids != [uid]:
                raise MailboxError("imap_invalid_response")
            if sizes[0] > MAX_MESSAGE:
                messages.append({"uid": uid, "error": "email_message_too_large"})
                continue
            connection.step()
            status, raw = connection.uid(
                "FETCH", str(uid), f"(UID BODY.PEEK[]<0.{MAX_MESSAGE + 1}>)"
            )
            bodies = [
                item[1]
                for item in raw
                if isinstance(item, tuple) and len(item) == 2 and isinstance(item[1], bytes)
            ]
            if (
                status != "OK"
                or len(bodies) != 1
                or len(bodies[0]) != sizes[0]
                or len(bodies[0]) > MAX_MESSAGE
                or not any(
                    isinstance(item, tuple)
                    and isinstance(item[0], bytes)
                    and re.search(rb"UID " + str(uid).encode() + rb"\b", item[0])
                    for item in raw
                )
            ):
                raise MailboxError("imap_invalid_response")
            messages.append({"uid": uid, "raw": bodies[0]})
        return {"uidvalidity": validity, "messages": messages}
    except MailboxError:
        raise
    except TimeoutError:
        raise MailboxError("imap_timeout") from None
    except ssl.SSLError:
        raise MailboxError("imap_tls_failed") from None
    except imaplib.IMAP4.error:
        raise MailboxError("imap_auth_or_protocol_failed") from None
    except OSError, ValueError, TypeError, IndexError:
        raise MailboxError("imap_invalid_response") from None
    finally:
        if connection is not None:
            # Close the socket directly: no STORE/EXPUNGE/CLOSE or mailbox changes.
            try:
                connection.shutdown()
            except OSError:
                pass


async def guarded_read(
    url,
    *,
    username,
    password,
    folder="INBOX",
    after_uid=0,
    uidvalidity=None,
    limit=5,
    check,
    run_blocking,
    own_hosts=(),
    resolver=_resolve,
):
    check()
    try:
        origin = urlsplit(url)
        hostname = _host(origin.hostname or "")
        port = origin.port if origin.port is not None else 993
        if (
            origin.scheme != "imaps"
            or not 1 <= port <= 65535
            or not origin.hostname
            or origin.username is not None
            or origin.path not in ("", "/")
            or origin.query
            or origin.fragment
            or hostname in {_host(host) for host in own_hosts if host}
            or type(limit) is not int
            or not 1 <= limit <= 5
            or type(after_uid) is not int
            or after_uid < 0
            or (uidvalidity is not None and (type(uidvalidity) is not int or uidvalidity < 1))
            or any(
                not isinstance(value, str)
                or not 1 <= len(value) <= 4096
                or any(ord(c) < 32 or ord(c) == 127 for c in value)
                for value in (username, password)
            )
            or not isinstance(folder, str)
            or not re.fullmatch(r"[A-Za-z0-9 ./_-]{1,128}", folder)
        ):
            raise MailboxError("imap_destination_or_options_invalid")
        async with asyncio.timeout(35):
            addresses = tuple(dict.fromkeys(await resolver(hostname, port)))
            check()
            own = await _local_addresses()
            check()
            for own_host in {_host(host) for host in own_hosts if host}:
                own.update(str(_address(value)) for value in await resolver(own_host, 443))
                check()
            if not addresses:
                raise MailboxError("imap_dns_failed")
            for address in addresses:
                _validate_address(address, True, own)
            check()
            result = await run_blocking(
                lambda: _poll(
                    hostname,
                    addresses,
                    port,
                    username,
                    password,
                    folder,
                    after_uid,
                    uidvalidity,
                    limit,
                )
            )
            check()
            return result
    except MailboxError:
        raise
    except FetchError:
        raise MailboxError("imap_destination_blocked") from None
    except TimeoutError:
        raise MailboxError("imap_timeout") from None
    except PermissionError:
        raise
    except OSError, ValueError, TypeError:
        raise MailboxError("imap_connection_failed") from None
