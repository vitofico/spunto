"""Optional operator-trusted HTTP allowance policy, independent of payment providers."""

import asyncio
import json
import time
from urllib.parse import quote

import httpx

from spunto.ai.quota import ACTIONS

RESOURCES = {
    "storage_bytes",
    "file_bytes",
    "owned_shared_spaces",
    "members_per_shared_space",
    "mcp_writes_daily",
    "ask_daily",
    "transcription_seconds_monthly",
}


class HTTPPolicy:
    def __init__(self, url, token, fallback, *, resources=None, client=None, clock=time.time):
        self.url, self.token, self.fallback, self.clock = url, token, fallback, clock
        self.client = client or httpx.AsyncClient(timeout=3, follow_redirects=False)
        self._owns_client = client is None
        self._cache, self._locks = {}, {}
        self.fallback_resources = resources or {}

    async def limits(self, account_id):
        return (await self.snapshot(account_id))["limits"]

    async def resources(self, account_id):
        return (await self.snapshot(account_id))["resources"]

    async def snapshot(self, account_id):
        # One resolver is shared by all spaces; failures never extend a cached allowance.
        lock = self._locks.setdefault(account_id, asyncio.Lock())
        async with lock:
            cached = self._cache.get(account_id)
            if cached and cached[0] > self.clock():
                return cached[1]

            def fallback():
                # Short backoff bounds retries without extending an expired allowance.
                result = {"limits": self.fallback, "resources": self.fallback_resources}
                self._cache[account_id] = (self.clock() + 15, result)
                return result

            try:
                async with (
                    asyncio.timeout(3),
                    self.client.stream(
                        "GET",
                        self.url + "/" + quote(account_id, safe=""),
                        headers={"Authorization": "Bearer " + self.token},
                        timeout=3,
                        follow_redirects=False,
                    ) as response,
                ):
                    if response.status_code != 200:
                        return fallback()
                    raw = bytearray()
                    async for chunk in response.aiter_bytes():
                        raw.extend(chunk)
                        if len(raw) > 16_384:
                            return fallback()
                data = json.loads(raw)
                now = self.clock()
                limits, expires = data["limits"], data["expires_at"]
                resources = data.get("resources", {})
                if (
                    type(data["version"]) is not int
                    or data["version"] != 1
                    or data["account_id"] != account_id
                    or type(expires) is not int
                    or not now < expires <= now + 300
                    or not isinstance(limits, dict)
                    or set(limits) != set(ACTIONS)
                    or any(
                        limit is not None and (type(limit) is not int or not 0 <= limit < 2**63)
                        for limit in limits.values()
                    )
                    or not isinstance(resources, dict)
                    or not set(resources) <= RESOURCES
                    or any(
                        v is not None and (type(v) is not int or not 0 <= v < 2**63)
                        for v in resources.values()
                    )
                ):
                    return fallback()
                result = {"limits": limits, "resources": resources}
                self._cache[account_id] = (min(expires, now + 120), result)
                return result
            except httpx.HTTPError, ValueError, KeyError, TypeError, TimeoutError:
                return fallback()

    async def close(self):
        if self._owns_client:
            await self.client.aclose()
