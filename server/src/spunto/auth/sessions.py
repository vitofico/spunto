"""Web sessions: a random token in a cookie, stored only as a hash (02 §9.1, §9.4).

Tokens look like spunto_<kind>_<id>_<secret>, so a leaked one is recognisable
by secret scanners and identifiable by its public id without its secret.
"""

import hashlib
import hmac
import secrets

from spunto.config import Settings
from spunto.store.db import Store

DAY_MS = 24 * 60 * 60 * 1000
IDLE_MS = 30 * DAY_MS
LIFETIME_MS = 90 * DAY_MS
TOUCH_EVERY_MS = 60 * 1000


def cookie_name(settings: Settings) -> str:
    # Browsers accept a __Host- cookie only over HTTPS, so on a plain-HTTP LAN
    # the session rides in a plain cookie instead of failing silently (D33).
    return "__Host-session" if settings.secure else "session"


async def create(
    store: Store, name: str, now: int, *, expected_password_hash: str | None = None
) -> str | None:
    """Issue a session only if the password used to authenticate still belongs to the owner.

    Recovery can run beside the server, so checking and issuing must share the
    writer transaction with respect to a password change and its revocations.
    """
    credential_id = secrets.token_hex(8)
    secret = secrets.token_urlsafe(32)
    async with store.write() as conn:
        if expected_password_hash is not None:
            rows = await conn.execute_fetchall("SELECT password_hash FROM owner")
            if not rows or rows[0]["password_hash"] != expected_password_hash:
                return None
        await conn.execute(
            "INSERT INTO credential (id, kind, name, scope, secret_hash, created_at, expires_at)"
            " VALUES (?, 'session', ?, 'full', ?, ?, ?)",
            (credential_id, name, _digest(secret), now, now + LIFETIME_MS),
        )
    return f"spunto_session_{credential_id}_{secret}"


async def resolve(store: Store, token: str, now: int) -> str | None:
    """The session's credential id, or None for a token that is unknown, revoked or expired."""
    parts = token.split("_", 3)
    if len(parts) != 4 or parts[:2] != ["spunto", "session"]:
        return None
    credential_id, secret = parts[2], parts[3]
    async with store.read() as conn:
        rows = await conn.execute_fetchall(
            "SELECT secret_hash, created_at, expires_at, last_used_at FROM credential"
            " WHERE id = ? AND kind = 'session' AND revoked_at IS NULL",
            (credential_id,),
        )
    if not rows or not hmac.compare_digest(rows[0]["secret_hash"], _digest(secret)):
        return None
    last_active = rows[0]["last_used_at"] or rows[0]["created_at"]
    if now >= rows[0]["expires_at"] or now - last_active >= IDLE_MS:
        return None
    if now - last_active >= TOUCH_EVERY_MS:
        async with store.write() as conn:
            await conn.execute(
                "UPDATE credential SET last_used_at = ? WHERE id = ?", (now, credential_id)
            )
    return credential_id


async def revoke(store: Store, credential_id: str, now: int) -> None:
    async with store.write() as conn:
        await conn.execute(
            "UPDATE credential SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
            (now, credential_id),
        )


def _digest(secret: str) -> str:
    # A fast hash is enough for 256 random bits, and a slow one would tax every request.
    return hashlib.sha256(secret.encode()).hexdigest()
