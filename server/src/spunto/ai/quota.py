"""Monthly AI allowances per hosted account, and what each call sent to providers.

A hosted space charges every AI call to its owner's account before the call joins
a provider queue, so creating another space never adds allowance. SQLite servers
keep no allowance; the provider's own spending limit applies there.
"""

import contextvars
from datetime import UTC, datetime

from spunto.ai.errors import AIError

# Proposed launch tiers; None is unlimited. Embeddings run locally and power search.
PLANS = {
    "free": {"labels": 100, "ask": 20, "chat": 50, "vision": 10, "transcribe": 10, "embed": None},
    "paid": {
        "labels": 2000,
        "ask": 300,
        "chat": 1000,
        "vision": 200,
        "transcribe": 100,
        "embed": None,
    },
    "unlimited": dict.fromkeys(("labels", "ask", "chat", "vision", "transcribe", "embed")),
}
ACTIONS = tuple(PLANS["free"])
TITLES = {
    "labels": "Auto-labelling",
    "ask": "Ask",
    "chat": "Other AI actions",
    "vision": "Image reading",
    "transcribe": "Transcription",
    "embed": "Search indexing",
}
_PLUGIN_ACTIONS = {"ask": "ask", "label-suggestions": "labels"}
_current = contextvars.ContextVar("spunto_ai_usage", default=None)


def action(capability, plugin_id):
    """The allowance one call draws from; core connection checks draw from none."""
    if capability == "embed":
        return "embed"
    if plugin_id == "core":
        return None
    return _PLUGIN_ACTIONS.get(plugin_id, "chat") if capability == "chat" else capability


class Usage:
    """Whether one action reached a provider, and the tokens providers reported for it."""

    def __init__(self):
        self.sent, self.input_tokens, self.output_tokens = False, 0, 0

    def __enter__(self):
        self._token = _current.set(self)
        return self

    def __exit__(self, *exc):
        _current.reset(self._token)


def sending():
    if usage := _current.get():
        usage.sent = True


def used(input_tokens, output_tokens):
    # Provider reports are untrusted; a malformed count records nothing.
    if usage := _current.get():
        for name, value in (("input_tokens", input_tokens), ("output_tokens", output_tokens)):
            if type(value) is int and 0 <= value < 2**31:
                setattr(usage, name, getattr(usage, name) + value)


def _month(now):
    start = now.astimezone(UTC).replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    following = start.replace(year=start.year + (start.month == 12), month=start.month % 12 + 1)
    return start.strftime("%Y-%m"), following


class QuotaExceeded(AIError):
    def __init__(self, action, limit, resets_at, now):
        # Paused jobs look again hourly, so a raised limit resumes them without a restart.
        wait = (resets_at - now).total_seconds()
        super().__init__("unavailable", "ai_quota_exhausted", retry_after=min(3600, wait))
        self.action, self.limit, self.resets_at = action, limit, resets_at

    @property
    def detail(self):
        return (
            f"{TITLES[self.action]} has used this month's allowance ({self.limit}) and resumes "
            f"on {self.resets_at.day} {self.resets_at:%B}. Notes and search keep working."
        )


class AccountQuota:
    """Atomic monthly counters in the identity database for one space owner's account."""

    def __init__(self, accounts, user_id, limits, *, clock=lambda: datetime.now(UTC)):
        self.accounts, self.user_id, self.limits, self.clock = accounts, user_id, limits, clock

    async def reserve(self, action):
        """Count one action before it queues, or refuse it once the month's allowance is used."""
        now = self.clock()
        period, resets_at = _month(now)
        limit = self.limits[action]
        row = (
            await self.accounts.one(
                "INSERT INTO public.spunto_ai_usage AS u(user_id,period,action,calls) "
                "VALUES (%s,%s,%s,1) ON CONFLICT (user_id,period,action) "
                "DO UPDATE SET calls=u.calls+1 WHERE %s::bigint IS NULL OR u.calls<%s "
                "RETURNING calls",
                (self.user_id, period, action, limit, limit),
            )
            if limit != 0
            else None
        )
        if row is None:
            raise QuotaExceeded(action, limit, resets_at, now)
        return action, period

    async def release(self, action, period):
        """Return an action that never reached a provider."""
        async with self.accounts.transaction() as conn:
            await conn.execute(
                "UPDATE public.spunto_ai_usage SET calls=calls-1 "
                "WHERE user_id=%s AND period=%s AND action=%s AND calls>0",
                (self.user_id, period, action),
            )

    async def status(self):
        period, resets_at = _month(self.clock())
        rows = await self.accounts.all(
            "SELECT action,calls FROM public.spunto_ai_usage WHERE user_id=%s AND period=%s",
            (self.user_id, period),
        )
        calls = {row["action"]: row["calls"] for row in rows}
        return {
            "period": period,
            "resets_at": resets_at.isoformat().replace("+00:00", "Z"),
            "actions": [
                {"action": name, "used": calls.get(name, 0), "limit": self.limits[name]}
                for name in ACTIONS
            ],
        }
