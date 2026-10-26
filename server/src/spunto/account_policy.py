"""Generic per-account policy and counters; limits come only from the operator."""

import json
from datetime import UTC, datetime

from psycopg.errors import RaiseException

from spunto.api.errors import Problem
from spunto.store.db import now_ms


class AccountPolicy:
    def __init__(self, accounts, source):
        self.accounts, self.source = accounts, source

    async def prepare(self, user_id):
        limits = await self.source.resources(user_id)
        await self.accounts.one(
            "INSERT INTO public.spunto_account_policy VALUES (%s,%s::jsonb,%s) "
            "ON CONFLICT(user_id) DO UPDATE SET "
            "limits=excluded.limits,updated_at=excluded.updated_at RETURNING user_id",
            (user_id, json.dumps(limits), now_ms()),
        )
        return limits

    async def workspace(self, workspace_id):
        row = await self.accounts.one(
            "SELECT created_by FROM public.spunto_workspace WHERE id=%s", (workspace_id,)
        )
        if row:
            return await self.prepare(row["created_by"])
        return {}

    async def take(self, user_id, action, amount=1, *, monthly=False):
        limits = await self.prepare(user_id)
        limit = limits.get(action)
        now = datetime.now(UTC)
        period = now.strftime("%Y-%m" if monthly else "%Y-%m-%d")
        async with self.accounts.transaction() as conn:
            row = await (
                await conn.execute(
                    "SELECT closed_at FROM public.spunto_account WHERE id=%s FOR UPDATE", (user_id,)
                )
            ).fetchone()
            if row is None or row["closed_at"] is not None:
                raise Problem(403, "account_closed", "This account is closed.")
            row = await (
                await conn.execute(
                    "INSERT INTO public.spunto_account_counter(user_id,period,action,used) "
                    "SELECT %s,%s,%s,%s WHERE %s::bigint IS NULL OR %s<=%s "
                    "ON CONFLICT(user_id,period,action) DO UPDATE SET "
                    "used=spunto_account_counter.used+excluded.used "
                    "WHERE %s::bigint IS NULL OR "
                    "spunto_account_counter.used+excluded.used<=%s RETURNING used",
                    (user_id, period, action, amount, limit, amount, limit, limit, limit),
                )
            ).fetchone()
            if row is None:
                raise Problem(
                    429,
                    "account_quota_exhausted",
                    "This operation's account allowance is exhausted; reads and export remain"
                    " available.",
                )
        return period, action, amount

    async def release(self, user_id, receipt):
        period, action, amount = receipt
        await self.accounts.one(
            "UPDATE public.spunto_account_counter SET used=greatest(0,used-%s) WHERE "
            "user_id=%s AND period=%s AND action=%s RETURNING used",
            (amount, user_id, period, action),
        )


def quota_problem(exc):
    if isinstance(exc, RaiseException) and str(exc).splitlines()[0] in {
        "account_storage_limit",
        "account_file_limit",
        "account_space_limit",
        "account_member_limit",
        "account_closed",
    }:
        return Problem(
            429,
            "account_quota_exhausted",
            "This operation exceeds the account limit; reads and export remain available.",
        )
    return None
