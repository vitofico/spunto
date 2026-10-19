"""Personal access tokens and the principal shared by REST and MCP."""

import hashlib
import hmac
import secrets
from dataclasses import dataclass
from typing import Literal

from spunto.store.db import Store, now_ms


@dataclass(frozen=True)
class Principal:
    id: str
    kind: str
    name: str
    scope: str
    client: str | None = None
    private_ok: bool = False
    external_ok: bool = True
    user_id: str | None = None
    workspace_id: str | None = None
    workspace_role: str = "owner"

    @property
    def owner(self) -> bool:
        return self.kind in {"session", "device"}

    @property
    def writable(self) -> bool:
        return self.workspace_role != "viewer" and (self.owner or self.scope == "write")


async def create(
    store: Store,
    *,
    name: str,
    client: str,
    scope: Literal["read", "write", "status"],
    private_ok: bool = False,
    external_ok: bool = True,
    expires_at: int | None = None,
) -> tuple[str, str]:
    credential_id, secret = secrets.token_hex(8), secrets.token_urlsafe(32)
    async with store.write() as conn:
        await conn.execute(
            "INSERT INTO credential"
            " (id,kind,name,client,scope,private_ok,external_ok,secret_hash,created_at,expires_at)"
            " VALUES (?,'pat',?,?,?,?,?,?,?,?)",
            (
                credential_id,
                name,
                client,
                scope,
                int(private_ok),
                int(external_ok),
                hashlib.sha256(secret.encode()).hexdigest(),
                now_ms(),
                expires_at,
            ),
        )
    return credential_id, f"spunto_pat_{credential_id}_{secret}"


async def authenticate(store: Store, token: str, now: int | None = None) -> Principal | None:
    parts = token.split("_", 3)
    if len(parts) != 4 or parts[0] != "spunto" or parts[1] not in {"pat", "device"}:
        return None
    now = now_ms() if now is None else now
    kind, credential_id, secret = parts[1:]
    async with store.read() as conn:
        rows = await conn.execute_fetchall(
            "SELECT * FROM credential WHERE id=? AND kind=? AND revoked_at IS NULL",
            (credential_id, kind),
        )
    if not rows:
        return None
    row = rows[0]
    if (
        not row["secret_hash"]
        or not hmac.compare_digest(row["secret_hash"], hashlib.sha256(secret.encode()).hexdigest())
        or (row["expires_at"] is not None and now >= row["expires_at"])
    ):
        return None
    if row["last_used_at"] is None or now - row["last_used_at"] >= 60_000:
        async with store.write() as conn:
            await conn.execute(
                "UPDATE credential SET last_used_at=? WHERE id=?", (now, credential_id)
            )
    return Principal(
        id=credential_id,
        kind=kind,
        name=row["name"],
        scope=row["scope"],
        client=row["client"],
        private_ok=kind == "device" or bool(row["private_ok"]),
        external_ok=bool(row["external_ok"]),
    )
