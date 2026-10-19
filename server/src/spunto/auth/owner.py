"""The one owner, whose password comes from the environment or from setup (02 §9.2)."""

import base64
import hmac
import secrets

from spunto.auth import passwords
from spunto.config import Settings
from spunto.store.db import Store, now_ms


async def password_hash(store: Store) -> str | None:
    """The owner's password hash, or None while setup is still pending."""
    async with store.read() as conn:
        rows = await conn.execute_fetchall("SELECT password_hash FROM owner")
    return rows[0]["password_hash"] if rows else None


async def apply_environment(store: Store, settings: Settings) -> None:
    """Make the password in the environment the owner's; the environment wins at every start."""
    if settings.owner_password_hash is not None:
        await set_password(store, settings.owner_password_hash, "env")
    elif settings.owner_password is not None:
        # Hashing costs a moment, so a password that already matches is left as it is.
        current = await password_hash(store)
        if current is None or not await passwords.verify_password(current, settings.owner_password):
            await set_password(store, await passwords.hash_password(settings.owner_password), "env")


async def set_password(store: Store, new_hash: str, source: str) -> None:
    """Replace the owner's password; when it changes, every web session signs out."""
    now = now_ms()
    async with store.write() as conn:
        rows = await conn.execute_fetchall("SELECT password_hash FROM owner")
        if rows and rows[0]["password_hash"] == new_hash:
            return
        await conn.execute(
            "INSERT INTO owner (id, password_hash, password_source, created_at)"
            " VALUES (1, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET"
            " password_hash = excluded.password_hash, password_source = excluded.password_source",
            (new_hash, source, now),
        )
        await conn.execute(
            "UPDATE credential SET revoked_at = ? WHERE kind = 'session' AND revoked_at IS NULL",
            (now,),
        )


async def create(store: Store, new_hash: str) -> bool:
    """Create the owner from setup; False when one already exists."""
    async with store.write() as conn:
        cursor = await conn.execute(
            "INSERT INTO owner (id, password_hash, password_source, created_at)"
            " VALUES (1, ?, 'setup', ?) ON CONFLICT (id) DO NOTHING",
            (new_hash, now_ms()),
        )
        return cursor.rowcount == 1


def new_setup_code() -> str:
    """160 random bits as 32 letters and digits in groups of four, easy to copy from a log."""
    code = base64.b32encode(secrets.token_bytes(20)).decode()
    return "-".join(code[i : i + 4] for i in range(0, len(code), 4))


def same_code(given: str, expected: str) -> bool:
    def normal(code: str) -> bytes:
        return code.replace("-", "").replace(" ", "").upper().encode()

    return hmac.compare_digest(normal(given), normal(expected))
