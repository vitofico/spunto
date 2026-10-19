"""Back-off for password and setup-code guesses (02 §9.3).

Each client address gets five free failures; after that it must wait 1 second,
then 2, 4 and so on up to 15 minutes before its next try. Above 100 failures an
hour from all addresses together, every try is slowed down as well, which is
what holds back a guesser that spreads across many addresses. Counters live in
memory, so a restart resets them; for one process that is acceptable.
"""

import asyncio
import time
from collections.abc import Callable
from dataclasses import dataclass

FREE_FAILURES = 5
FIRST_WAIT = 1.0
MAX_WAIT = 15 * 60.0
# An address with no failures for this long starts again from zero.
FORGET_AFTER = 60 * 60.0
SLOWDOWN_ABOVE = 100  # failures in the last hour, all addresses together
SLOWDOWN = 1.0


@dataclass
class _Address:
    failures: int = 0
    last_failure: float = 0.0
    blocked_until: float = 0.0


class Backoff:
    def __init__(self, clock: Callable[[], float] = time.monotonic):
        self._clock = clock
        self._addresses: dict[str, _Address] = {}
        self._per_minute: dict[int, int] = {}
        # Guesses take turns, so a burst sent in parallel cannot all pass
        # retry_after before the first of them is counted as a failure.
        self.turn = asyncio.Lock()

    def retry_after(self, address: str) -> float:
        """Seconds this address must still wait before its next try; 0 when it may try now."""
        now = self._clock()
        entry = self._addresses.get(address)
        if entry is None or now - entry.last_failure > FORGET_AFTER:
            return 0.0
        return max(0.0, entry.blocked_until - now)

    def failed(self, address: str) -> None:
        now = self._clock()
        entry = self._addresses.get(address)
        if entry is None or now - entry.last_failure > FORGET_AFTER:
            entry = self._addresses[address] = _Address()
        entry.failures += 1
        entry.last_failure = now
        if entry.failures >= FREE_FAILURES:
            wait = FIRST_WAIT * 2 ** (entry.failures - FREE_FAILURES)
            entry.blocked_until = now + min(wait, MAX_WAIT)
        minute = int(now // 60)
        self._per_minute[minute] = self._per_minute.get(minute, 0) + 1
        self._forget(now)

    def succeeded(self, address: str) -> None:
        self._addresses.pop(address, None)

    def slowdown(self) -> float:
        """Seconds to delay every try while failures are high; 0 normally."""
        now = self._clock()
        self._forget(now)
        return SLOWDOWN if sum(self._per_minute.values()) > SLOWDOWN_ABOVE else 0.0

    def _forget(self, now: float) -> None:
        oldest_minute = int(now // 60) - 59
        for minute in [m for m in self._per_minute if m < oldest_minute]:
            del self._per_minute[minute]
        if len(self._addresses) > 1024:
            for address, entry in list(self._addresses.items()):
                if now - entry.last_failure > FORGET_AFTER:
                    del self._addresses[address]
