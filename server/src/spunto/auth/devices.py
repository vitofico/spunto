"""Named Android credentials and expiring, one-use pairing grants."""

import hashlib
import secrets

from spunto.api.errors import Problem
from spunto.auth.sessions import IDLE_MS
from spunto.store.db import now_ms

PAIRING_MS = 5 * 60_000


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


async def issue(conn, name):
    credential_id, secret = secrets.token_hex(8), secrets.token_urlsafe(32)
    await conn.execute(
        "INSERT INTO credential (id,kind,name,client,scope,private_ok,secret_hash,created_at) "
        "VALUES (?,'device',?,'android','full',1,?,?)",
        (credential_id, name, digest(secret), now_ms()),
    )
    return {"id": credential_id, "token": f"spunto_device_{credential_id}_{secret}", "name": name}


async def active_session(conn, credential_id, now):
    rows = await conn.execute_fetchall(
        "SELECT id FROM credential WHERE id=? AND kind='session' AND revoked_at IS NULL "
        "AND expires_at>? AND coalesce(last_used_at,created_at)>?",
        (credential_id, now, now - IDLE_MS),
    )
    if not rows:
        raise Problem(401, "unauthenticated", "Sign in again.")
