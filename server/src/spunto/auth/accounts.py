"""Hosted accounts and access metadata; note content stays behind the scoped store."""

import hashlib
import re
import secrets
import uuid
from contextlib import asynccontextmanager

from psycopg.errors import UniqueViolation
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from spunto.api.errors import Problem
from spunto.auth import passwords
from spunto.auth.backoff import FIRST_WAIT, FORGET_AFTER, FREE_FAILURES, MAX_WAIT
from spunto.store.db import now_ms

SESSION_MS = 30 * 86_400_000
REAUTH_MS = 5 * 60_000


def local_username(value):
    if not value.isascii() or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{2,63}", value.lower()):
        raise Problem(
            422,
            "validation_failed",
            "Use 3–64 letters, digits, dots, underscores or hyphens for the username.",
        )
    return value.lower()


def new_password(value):
    if reason := passwords.problem(value):
        raise Problem(422, "validation_failed", reason)


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


class Accounts:
    def __init__(self, pool):
        self.pool = pool

    @classmethod
    async def open(cls, url):
        pool = AsyncConnectionPool(url, open=False, kwargs={"row_factory": dict_row})
        try:
            await pool.open(wait=True)
            async with pool.connection() as conn:
                role = await (
                    await conn.execute(
                        "SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user"
                    )
                ).fetchone()
                if role["rolsuper"] or role["rolbypassrls"]:
                    raise ValueError("The identity database role must not bypass row security.")
        except BaseException:
            await pool.close()
            raise
        return cls(pool)

    async def close(self):
        await self.pool.close()

    @asynccontextmanager
    async def transaction(self):
        async with self.pool.connection() as conn, conn.transaction():
            yield conn

    async def one(self, sql, args=()):
        async with self.transaction() as conn:
            return await (await conn.execute(sql, args)).fetchone()

    async def all(self, sql, args=()):
        async with self.transaction() as conn:
            return await (await conn.execute(sql, args)).fetchall()

    async def sign_in(self, identity):
        # Email is contact metadata, never an identity/account linking key.
        email = identity.email if identity.email_verified else None
        name = (identity.display_name or "Spunto user").strip()[:80]
        async with self.transaction() as conn:
            account = await (
                await conn.execute(
                    "INSERT INTO public.spunto_account(id,issuer,subject,email,display_name) "
                    "VALUES (%s,%s,%s,%s,%s) ON CONFLICT (issuer,subject) DO UPDATE "
                    "SET email=excluded.email,display_name=excluded.display_name RETURNING *",
                    (str(uuid.uuid4()), identity.issuer, identity.subject, email, name),
                )
            ).fetchone()
            workspace = await (
                await conn.execute(
                    "SELECT * FROM public.spunto_workspace WHERE created_by=%s AND personal",
                    (account["id"],),
                )
            ).fetchone()
            if workspace is None:
                workspace = await (
                    await conn.execute(
                        "INSERT INTO public.spunto_workspace "
                        "(id,name,created_by,created_at,personal) "
                        "VALUES (%s,'Personal',%s,%s,TRUE) RETURNING *",
                        (str(uuid.uuid4()), account["id"], now_ms()),
                    )
                ).fetchone()
                await conn.execute(
                    "INSERT INTO public.spunto_membership(workspace_id,user_id,role) "
                    "VALUES (%s,%s,'owner')",
                    (workspace["id"], account["id"]),
                )
            return account, workspace

    async def _new_session(self, conn, user_id, auth_method):
        token = "spunto_account_" + secrets.token_urlsafe(32)
        await conn.execute(
            "INSERT INTO public.spunto_login_session(token_hash,user_id,expires_at,auth_method) "
            "VALUES (%s,%s,%s,%s)",
            (digest(token), user_id, now_ms() + SESSION_MS, auth_method),
        )
        return token

    async def issue_session(self, user_id, auth_method="oidc"):
        async with self.transaction() as conn:
            return await self._new_session(conn, user_id, auth_method)

    async def resolve_session(self, token):
        if not token or len(token) > 256 or not token.startswith("spunto_account_"):
            return None
        return await self.one(
            "SELECT a.*,s.auth_method FROM public.spunto_login_session s JOIN "
            "public.spunto_account a "
            "ON a.id=s.user_id WHERE s.token_hash=%s AND s.revoked_at IS NULL "
            "AND s.expires_at>%s",
            (digest(token), now_ms()),
        )

    async def register_local(self, username, password, display_name=None, *, create_session=True):
        username = local_username(username)
        new_password(password)
        password_hash = await passwords.hash_password(password)
        try:
            async with self.transaction() as conn:
                account = await (
                    await conn.execute(
                        "INSERT INTO public.spunto_account(id,display_name) VALUES (%s,%s) "
                        "RETURNING *",
                        (str(uuid.uuid4()), (display_name or username).strip()[:80] or username),
                    )
                ).fetchone()
                await conn.execute(
                    "INSERT INTO public.spunto_local_credential VALUES (%s,%s,%s,%s)",
                    (account["id"], username, password_hash, now_ms()),
                )
                workspace = await (
                    await conn.execute(
                        "INSERT INTO public.spunto_workspace(id,name,created_by,created_at,"
                        "personal) "
                        "VALUES (%s,'Personal',%s,%s,TRUE) RETURNING *",
                        (str(uuid.uuid4()), account["id"], now_ms()),
                    )
                ).fetchone()
                await conn.execute(
                    "INSERT INTO public.spunto_membership VALUES (%s,%s,'owner')",
                    (workspace["id"], account["id"]),
                )
                token = (
                    await self._new_session(conn, account["id"], "password")
                    if create_session
                    else None
                )
        except UniqueViolation:
            raise Problem(
                409, "registration_unavailable", "Those account details cannot be registered."
            ) from None
        return account, workspace, token

    async def password_sign_in(self, username, password, dummy_hash):
        # Unknown names take the same Argon2 path; email is never used to find an account.
        username = username.lower() if username.isascii() else ""
        async with self.transaction() as conn:
            account = await (
                await conn.execute(
                    "SELECT a.* FROM public.spunto_account a JOIN public.spunto_local_credential c "
                    "ON c.user_id=a.id WHERE c.username=%s FOR UPDATE OF a",
                    (username,),
                )
            ).fetchone()
            credential = None
            if account:
                credential = await (
                    await conn.execute(
                        "SELECT password_hash FROM public.spunto_local_credential "
                        "WHERE user_id=%s AND username=%s",
                        (account["id"], username),
                    )
                ).fetchone()
            verified = await passwords.verify_password(
                credential["password_hash"] if credential else dummy_hash, password
            )
            if not credential or not verified:
                raise Problem(401, "invalid_credentials", "The username or password is not right.")
            token = await self._new_session(conn, account["id"], "password")
        return account, token

    async def security(self, user_id, session_token):
        row = await self.one(
            "SELECT a.issuer,c.username,EXISTS(SELECT 1 FROM "
            "public.spunto_account_reauthentication r "
            "WHERE r.session_hash=%s AND r.user_id=a.id AND r.purpose='local-password' "
            "AND r.expires_at>%s) AS reauthenticated FROM public.spunto_account a "
            "LEFT JOIN public.spunto_local_credential c ON c.user_id=a.id WHERE a.id=%s",
            (digest(session_token), now_ms(), user_id),
        )
        return {
            "local_username": row["username"],
            "local_password_set": row["username"] is not None,
            "oidc_linked": row["issuer"] is not None,
            "reauthenticated": row["reauthenticated"],
        }

    async def grant_reauthentication(self, user_id, session_token, purpose):
        async with self.transaction() as conn:
            await conn.execute(
                "DELETE FROM public.spunto_account_reauthentication WHERE expires_at<=%s",
                (now_ms(),),
            )
            await conn.execute(
                "INSERT INTO public.spunto_account_reauthentication VALUES (%s,%s,%s,%s) "
                "ON CONFLICT(session_hash) DO UPDATE SET purpose=excluded.purpose,"
                "expires_at=excluded.expires_at",
                (digest(session_token), user_id, purpose, now_ms() + REAUTH_MS),
            )

    async def verify_local_password(self, user_id, password, dummy_hash):
        row = await self.one(
            "SELECT password_hash FROM public.spunto_local_credential WHERE user_id=%s", (user_id,)
        )
        verified = await passwords.verify_password(
            row["password_hash"] if row else dummy_hash, password
        )
        if not row or not verified:
            raise Problem(401, "wrong_password", "The current password is not right.")

    async def set_local_password(
        self, user_id, session_token, username, password, current_password, *, operator=False
    ):
        new_password(password)
        if username is not None:
            username = local_username(username)
        password_hash = await passwords.hash_password(password)
        try:
            async with self.transaction() as conn:
                account = await (
                    await conn.execute(
                        "SELECT id FROM public.spunto_account WHERE id=%s FOR UPDATE", (user_id,)
                    )
                ).fetchone()
                if account is None:
                    raise Problem(404, "account_unavailable", "That account is unavailable.")
                row = await (
                    await conn.execute(
                        "SELECT * FROM public.spunto_local_credential WHERE user_id=%s", (user_id,)
                    )
                ).fetchone()
                if not operator:
                    if row:
                        verified = await passwords.verify_password(
                            row["password_hash"], current_password or ""
                        )
                        if not verified:
                            raise Problem(
                                401, "wrong_password", "The current password is not right."
                            )
                    else:
                        proof = await (
                            await conn.execute(
                                "DELETE FROM public.spunto_account_reauthentication WHERE "
                                "session_hash=%s "
                                "AND user_id=%s AND purpose='local-password' AND expires_at>%s "
                                "RETURNING user_id",
                                (digest(session_token), user_id, now_ms()),
                            )
                        ).fetchone()
                        if not proof:
                            raise Problem(
                                403,
                                "reauthentication_required",
                                "Verify your sign-in before setting a local password.",
                            )
                username = username or (row["username"] if row else None)
                if not username:
                    raise Problem(422, "validation_failed", "Choose a username.")
                await conn.execute(
                    "INSERT INTO public.spunto_local_credential VALUES (%s,%s,%s,%s) "
                    "ON CONFLICT(user_id) DO UPDATE SET username=excluded.username,"
                    "password_hash=excluded.password_hash,updated_at=excluded.updated_at",
                    (user_id, username, password_hash, now_ms()),
                )
                await conn.execute(
                    "UPDATE public.spunto_login_session SET revoked_at=%s WHERE user_id=%s AND "
                    "revoked_at IS NULL",
                    (now_ms(), user_id),
                )
                await conn.execute(
                    "DELETE FROM public.spunto_account_reauthentication WHERE user_id=%s",
                    (user_id,),
                )
                return None if operator else await self._new_session(conn, user_id, "password")
        except UniqueViolation:
            raise Problem(
                409, "registration_unavailable", "Those account details cannot be registered."
            ) from None

    async def link_oidc(self, identity, user_id, session_token):
        try:
            async with self.transaction() as conn:
                account = await (
                    await conn.execute(
                        "SELECT * FROM public.spunto_account WHERE id=%s FOR UPDATE", (user_id,)
                    )
                ).fetchone()
                proof = await (
                    await conn.execute(
                        "DELETE FROM public.spunto_account_reauthentication WHERE session_hash=%s "
                        "AND user_id=%s AND purpose='oidc-link' AND expires_at>%s RETURNING "
                        "user_id",
                        (digest(session_token), user_id, now_ms()),
                    )
                ).fetchone()
                if not account or not proof or account["issuer"] is not None:
                    raise Problem(403, "link_unavailable", "That sign-in cannot be linked.")
                await conn.execute(
                    "UPDATE public.spunto_account SET issuer=%s,subject=%s WHERE id=%s",
                    (identity.issuer, identity.subject, user_id),
                )
        except UniqueViolation:
            raise Problem(409, "link_unavailable", "That sign-in cannot be linked.") from None

    async def guess_wait(self, keys):
        now = now_ms()
        async with self.transaction() as conn:
            await conn.execute(
                "DELETE FROM public.spunto_auth_throttle WHERE last_failure<%s",
                (now - int(FORGET_AFTER * 1000),),
            )
            row = await (
                await conn.execute(
                    "SELECT coalesce(max(blocked_until),0) AS blocked FROM "
                    "public.spunto_auth_throttle WHERE key=ANY(%s)",
                    (keys,),
                )
            ).fetchone()
            count = await (
                await conn.execute("SELECT count(*) AS n FROM public.spunto_auth_throttle")
            ).fetchone()
            if count["n"] >= 10_000:
                return 60
            return max(0, (row["blocked"] - now) / 1000)

    async def guess_failed(self, keys):
        now = now_ms()
        async with self.transaction() as conn:
            for key in keys:
                row = await (
                    await conn.execute(
                        "INSERT INTO public.spunto_auth_throttle VALUES (%s,1,%s,0) "
                        "ON CONFLICT(key) DO UPDATE SET failures=spunto_auth_throttle.failures+1,"
                        "last_failure=excluded.last_failure RETURNING failures",
                        (key, now),
                    )
                ).fetchone()
                wait = (
                    min(
                        FIRST_WAIT * 2 ** min(max(0, row["failures"] - FREE_FAILURES), 20), MAX_WAIT
                    )
                    if row["failures"] >= FREE_FAILURES
                    else 0
                )
                await conn.execute(
                    "UPDATE public.spunto_auth_throttle SET blocked_until=%s WHERE key=%s",
                    (now + int(wait * 1000), key),
                )

    async def guess_succeeded(self, keys):
        async with self.transaction() as conn:
            await conn.execute("DELETE FROM public.spunto_auth_throttle WHERE key=ANY(%s)", (keys,))

    async def revoke_session(self, token):
        async with self.transaction() as conn:
            await conn.execute(
                "UPDATE public.spunto_login_session SET revoked_at=%s WHERE token_hash=%s",
                (now_ms(), digest(token)),
            )

    async def workspace(self, user_id, workspace_id=None):
        row = await self.one(
            "SELECT w.*,m.role FROM public.spunto_workspace w "
            "JOIN public.spunto_membership m ON m.workspace_id=w.id "
            "WHERE m.user_id=%s AND ((%s::text IS NULL AND w.personal) OR w.id=%s) "
            "ORDER BY w.created_at LIMIT 1",
            (user_id, workspace_id, workspace_id),
        )
        if row is None:
            raise Problem(404, "workspace_unavailable", "That space is unavailable.")
        return row

    async def workspaces(self, user_id):
        return await self.all(
            "SELECT w.id,w.name,w.personal,m.role FROM public.spunto_workspace w "
            "JOIN public.spunto_membership m ON m.workspace_id=w.id "
            "WHERE m.user_id=%s ORDER BY w.personal DESC,w.created_at",
            (user_id,),
        )

    async def create_workspace(self, user_id, name):
        workspace = {"id": str(uuid.uuid4()), "name": name, "personal": False, "role": "owner"}
        async with self.transaction() as conn:
            await conn.execute(
                "INSERT INTO public.spunto_workspace(id,name,created_by,created_at,personal) "
                "VALUES (%s,%s,%s,%s,FALSE)",
                (workspace["id"], name, user_id, now_ms()),
            )
            await conn.execute(
                "INSERT INTO public.spunto_membership(workspace_id,user_id,role) "
                "VALUES (%s,%s,'owner')",
                (workspace["id"], user_id),
            )
        return workspace

    async def recipient(self, *, email=None, user_id=None, username=None):
        if sum(value is not None for value in (email, user_id, username)) != 1:
            raise Problem(
                422,
                "validation_failed",
                "Provide one existing username, verified email or account ID.",
            )
        if username is not None:
            username = local_username(username)
        rows = await self.all(
            "SELECT a.id,a.email,a.display_name,c.username FROM public.spunto_account a "
            "LEFT JOIN public.spunto_local_credential c ON c.user_id=a.id "
            "WHERE (%s::text IS NOT NULL AND lower(a.email)=lower(%s)) "
            "OR (%s::text IS NOT NULL AND a.id=%s) OR c.username=%s",
            (email, email.strip() if email else None, user_id, user_id, username),
        )
        if len(rows) != 1:
            raise Problem(404, "account_unavailable", "That account is unavailable.")
        return rows[0]

    async def require_owner(self, user_id, workspace_id):
        workspace = await self.workspace(user_id, workspace_id)
        if workspace["role"] != "owner":
            raise Problem(403, "owner_required", "Only the space owner can manage access.")
        return workspace

    async def credential_account(self, credential_id):
        return await self.one(
            "SELECT a.*,l.workspace_id FROM public.spunto_credential_locator l "
            "JOIN public.spunto_account a ON a.id=l.user_id WHERE l.id=%s",
            (credential_id,),
        )


class OIDCTransactions:
    def __init__(self, accounts, capacity=10_000):
        self.accounts = accounts
        self.capacity = capacity

    async def put(self, state_hash, transaction):
        from spunto.auth.oidc import OIDCError

        async with self.accounts.transaction() as conn:
            # Bound unauthenticated state even across concurrent requests/processes.
            await conn.execute("SELECT pg_advisory_xact_lock(831701847)")
            await conn.execute(
                "DELETE FROM public.spunto_oidc_transaction WHERE expires_at<=%s",
                (now_ms(),),
            )
            count = await (
                await conn.execute("SELECT count(*) FROM public.spunto_oidc_transaction")
            ).fetchone()
            if count["count"] >= self.capacity:
                raise OIDCError("Too many pending sign-in attempts.")
            await conn.execute(
                "INSERT INTO public.spunto_oidc_transaction "
                "(state_hash,verifier,nonce,browser_hash,expires_at,purpose,user_id,session_hash,"
                "requested_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                (
                    state_hash,
                    transaction.verifier,
                    transaction.nonce,
                    transaction.browser_hash,
                    int(transaction.expires_at * 1000),
                    transaction.purpose,
                    transaction.user_id,
                    transaction.session_hash,
                    transaction.requested_at,
                ),
            )

    async def take(self, state_hash):
        from spunto.auth.oidc import OIDCTransaction

        async with self.accounts.transaction() as conn:
            row = await (
                await conn.execute(
                    "DELETE FROM public.spunto_oidc_transaction WHERE state_hash=%s RETURNING *",
                    (state_hash,),
                )
            ).fetchone()
        return (
            OIDCTransaction(
                row["verifier"],
                row["nonce"],
                row["browser_hash"],
                row["expires_at"] / 1000,
                row["purpose"],
                row["user_id"],
                row["session_hash"],
                row["requested_at"],
            )
            if row
            else None
        )
