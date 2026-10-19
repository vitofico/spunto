import json
import logging
import re

import pytest
from starlette.requests import Request

from spunto.api.network import client_ip
from spunto.auth import owner, passwords
from spunto.auth.backoff import MAX_WAIT, Backoff
from spunto.config import load_settings
from spunto.logs import JsonFormatter


class Clock:
    def __init__(self) -> None:
        self.now = 1_000_000.0

    def __call__(self) -> float:
        return self.now


def test_backoff_gives_five_free_tries_then_doubles_up_to_fifteen_minutes():
    clock = Clock()
    backoff = Backoff(clock)
    waits = []
    for _ in range(16):
        assert backoff.retry_after("a") == 0
        backoff.failed("a")
        waits.append(backoff.retry_after("a"))
        clock.now += backoff.retry_after("a")
    assert waits == [0, 0, 0, 0, 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, MAX_WAIT, MAX_WAIT]
    assert backoff.retry_after("b") == 0


def test_backoff_resets_on_success_and_after_an_hour_of_quiet():
    clock = Clock()
    backoff = Backoff(clock)
    for _ in range(5):
        backoff.failed("a")
    backoff.succeeded("a")
    assert backoff.retry_after("a") == 0
    for _ in range(8):
        backoff.failed("a")
    clock.now += 3601
    backoff.failed("a")
    assert backoff.retry_after("a") == 0


def test_backoff_slows_everyone_above_a_hundred_failures_an_hour():
    clock = Clock()
    backoff = Backoff(clock)
    for i in range(100):
        backoff.failed(f"10.0.{i // 250}.{i % 250}")
    assert backoff.slowdown() == 0
    backoff.failed("10.9.9.9")
    assert backoff.slowdown() == 1.0
    clock.now += 3600
    assert backoff.slowdown() == 0


@pytest.mark.anyio
async def test_passwords_hash_with_argon2id_and_verify():
    stored = await passwords.hash_password("correct horse battery")
    assert stored.startswith("$argon2id$v=19$m=65536,t=3,p=4$")
    assert await passwords.verify_password(stored, "correct horse battery")
    assert not await passwords.verify_password(stored, "Correct horse battery")
    assert not await passwords.verify_password(stored, "x" * (passwords.MAX_BYTES + 1))
    assert not await passwords.verify_password("not a hash", "correct horse battery")


def test_password_rules():
    assert passwords.problem("short") == "The password needs at least 8 characters."
    assert passwords.problem("é" * 600) == "The password must fit in 1024 bytes."
    assert passwords.problem("correct horse battery") is None


def test_setup_code_has_160_random_bits_and_tolerates_how_it_is_typed():
    codes = {owner.new_setup_code() for _ in range(100)}
    assert len(codes) == 100
    code = codes.pop()
    assert re.fullmatch(r"([A-Z2-7]{4}-){7}[A-Z2-7]{4}", code)  # 32 base32 letters = 160 bits
    assert owner.same_code(code.lower().replace("-", " "), code)
    assert not owner.same_code(code[:-1] + ("A" if code[-1] != "A" else "B"), code)
    assert not owner.same_code("", code)


def request_from(peer: str, forwarded: str | None = None) -> Request:
    headers = [(b"x-forwarded-for", forwarded.encode())] if forwarded else []
    return Request({"type": "http", "client": (peer, 1234), "headers": headers})


def test_client_ip_believes_forwarded_for_only_from_trusted_proxies(env):
    env(SPUNTO_TRUSTED_PROXIES="10.42.0.0/16")
    settings = load_settings()
    assert client_ip(request_from("198.51.100.4", "203.0.113.9"), settings) == "198.51.100.4"
    assert client_ip(request_from("10.42.0.7", "203.0.113.9"), settings) == "203.0.113.9"
    # A client can prepend anything; the hop the trusted proxy appended is what counts.
    spoofed = "1.2.3.4, 203.0.113.9, 10.42.0.3"
    assert client_ip(request_from("10.42.0.7", spoofed), settings) == "203.0.113.9"
    assert client_ip(request_from("10.42.0.7"), settings) == "10.42.0.7"


def test_json_log_lines():
    record = logging.LogRecord("spunto.test", logging.WARNING, __file__, 1, "%d notes", (3,), None)
    line = json.loads(JsonFormatter().format(record))
    assert line["level"] == "warning"
    assert line["logger"] == "spunto.test"
    assert line["message"] == "3 notes"
    assert line["time"].endswith("Z")
