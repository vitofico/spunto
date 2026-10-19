"""Per-token MCP call budgets, held by the one server process."""

import time
from collections import deque
from collections.abc import Callable
from datetime import UTC, date, datetime
from zoneinfo import ZoneInfo

from spunto.api.errors import Problem


class Limits:
    def __init__(self, writes_per_day: int, zone: ZoneInfo, clock: Callable[[], float] = time.time):
        self.writes_per_day = writes_per_day
        self.zone = zone
        self.clock = clock
        self._reads: dict[str, deque[float]] = {}
        self._writes: dict[str, deque[float]] = {}
        self._daily: dict[str, tuple[date, int]] = {}

    def take(self, credential: str, *, write: bool = False) -> None:
        now = self.clock()
        records, maximum = (self._writes, 30) if write else (self._reads, 120)
        times = records.setdefault(credential, deque())
        while times and times[0] <= now - 60:
            times.popleft()
        if len(times) >= maximum:
            raise Problem(
                429, "rate_limited", "This token's minute limit is reached; retry in a minute."
            )
        if write:
            today = datetime.fromtimestamp(now, UTC).astimezone(self.zone).date()
            day, count = self._daily.get(credential, (today, 0))
            count = count if day == today else 0
            if count >= self.writes_per_day:
                raise Problem(
                    429,
                    "rate_limited",
                    "This token's daily write limit is reached; retry tomorrow.",
                )
            self._daily[credential] = today, count + 1
        times.append(now)
        # Drop inactive entries; no background polling is needed.
        if len(records) > 1024:
            for key in [
                key for key, queue in records.items() if not queue or queue[-1] <= now - 60
            ]:
                del records[key]
