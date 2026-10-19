"""Per-profile token bucket, priority, fair shares and bounded concurrency."""

import asyncio
import time
from collections import Counter, deque
from contextlib import asynccontextmanager

from spunto.ai.errors import AIError


class ProfileQueue:
    def __init__(self, profile, *, window_s=60, clock=time.monotonic):
        self.profile, self.window_s, self.clock = profile, window_s, clock
        self.condition = asyncio.Condition()
        self.pending = []
        self.history = deque()
        self.active = 0
        self.tokens = float(profile.rate_per_min)
        self.last_refill = clock()
        self.sequence = 0
        self.failures = 0
        self.paused_until = 0

    def pause(self):
        self.failures += 1
        self.paused_until = self.clock() + min(1800, 30 * 2 ** min(self.failures - 1, 6))

    def success(self):
        self.failures = 0
        self.paused_until = 0

    def _chosen(self):
        now = self.clock()
        self.tokens = min(
            self.profile.rate_per_min,
            self.tokens + (now - self.last_refill) * self.profile.rate_per_min / self.window_s,
        )
        self.last_refill = now
        while self.history and self.history[0][0] <= now - self.window_s:
            self.history.popleft()
        if self.active >= self.profile.concurrency or self.tokens < 1:
            return None
        counts = Counter(plugin for _, plugin in self.history)
        contenders = {item[2] for item in self.pending}
        eligible = [
            item
            for item in self.pending
            if len(contenders) == 1 or counts[item[2]] < max(1, self.profile.rate_per_min / 2)
        ]
        return (
            min(eligible, key=lambda item: (item[0], counts[item[2]], item[1]))
            if eligible
            else None
        )

    @asynccontextmanager
    async def slot(self, plugin, priority):
        acquired = False
        async with self.condition:
            self.sequence += 1
            item = (priority, self.sequence, plugin)
            self.pending.append(item)
            self.condition.notify_all()
            try:
                while True:
                    if self.paused_until > self.clock():
                        raise AIError(
                            "unavailable",
                            "ai_profile_paused",
                            retry_after=self.paused_until - self.clock(),
                        )
                    if self._chosen() == item:
                        self.pending.remove(item)
                        self.active += 1
                        self.tokens -= 1
                        self.history.append((self.clock(), plugin))
                        acquired = True
                        break
                    wait = (
                        max(0.001, self.history[0][0] + self.window_s - self.clock())
                        if self.history
                        else None
                    )
                    if self.tokens < 1:
                        wait = max(
                            0.001, (1 - self.tokens) * self.window_s / self.profile.rate_per_min
                        )
                    try:
                        async with asyncio.timeout(wait):
                            await self.condition.wait()
                    except TimeoutError:
                        pass
            finally:
                if not acquired:
                    self.pending.remove(item)
                    self.condition.notify_all()
        try:
            yield
        finally:
            async with self.condition:
                self.active -= 1
                self.condition.notify_all()
