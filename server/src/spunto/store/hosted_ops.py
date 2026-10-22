"""Operator-only PostgreSQL provisioning. Never imported by request handlers."""

import os
from pathlib import Path

import psycopg
from psycopg import sql

from spunto.errors import OperatorError
from spunto.store.postgres import migrate_postgres

IDENTITY_TABLES = (
    "spunto_account",
    "spunto_workspace",
    "spunto_membership",
    "spunto_login_session",
    "spunto_note_share",
    "spunto_oidc_transaction",
    "spunto_pairing",
    "spunto_local_credential",
    "spunto_account_reauthentication",
    "spunto_auth_throttle",
    "spunto_ai_usage",
)


def operator_secret(name):
    value, filename = os.environ.get(name), os.environ.get(name + "_FILE")
    if bool(value) == bool(filename):
        raise OperatorError(f"Set exactly one of {name} and {name}_FILE.")
    if filename:
        try:
            value = Path(filename).read_text().rstrip("\r\n")
        except OSError:
            raise OperatorError(f"{name}_FILE cannot be read.") from None
    if not value:
        raise OperatorError(f"{name} cannot be empty.")
    return value


async def provision_postgres(admin_url, runtime_role, identity_role):
    if not runtime_role or not identity_role or runtime_role == identity_role:
        raise OperatorError("Use distinct runtime and identity database roles.")
    await migrate_postgres(admin_url, runtime_role)
    async with await psycopg.AsyncConnection.connect(admin_url) as conn:
        roles = await (
            await conn.execute(
                "SELECT rolname,rolsuper,rolbypassrls,rolcreaterole FROM pg_roles "
                "WHERE rolname IN (%s,%s)",
                (runtime_role, identity_role),
            )
        ).fetchall()
        if len(roles) != 2 or any(any(row[1:]) for row in roles):
            raise OperatorError("Both database roles must exist without administrative privileges.")
        await conn.execute(
            sql.SQL("GRANT USAGE ON SCHEMA public TO {} ").format(
                sql.Identifier(identity_role),
            )
        )
        for table in IDENTITY_TABLES:
            await conn.execute(
                sql.SQL("GRANT SELECT,INSERT,UPDATE,DELETE ON public.{} TO {}").format(
                    sql.Identifier(table),
                    sql.Identifier(identity_role),
                )
            )
        for function in ("spunto.lock_shared_note(TEXT)", "spunto.grant_conflict_copy(TEXT,TEXT)"):
            await conn.execute(
                sql.SQL("GRANT EXECUTE ON FUNCTION {} TO {}").format(
                    sql.SQL(function),
                    sql.Identifier(runtime_role),
                )
            )
        await conn.execute(
            sql.SQL("GRANT SELECT ON public.spunto_credential_locator TO {}").format(
                sql.Identifier(identity_role),
            )
        )
        await conn.execute(
            sql.SQL("REVOKE ALL ON SCHEMA spunto FROM {}").format(
                sql.Identifier(identity_role),
            )
        )
        await conn.execute(
            sql.SQL("REVOKE ALL ON ALL TABLES IN SCHEMA spunto FROM {}").format(
                sql.Identifier(identity_role),
            )
        )


async def migrate_from_environment():
    await provision_postgres(
        operator_secret("SPUNTO_DATABASE_ADMIN_URL"),
        os.environ.get("SPUNTO_DATABASE_ROLE", "spunto_runtime"),
        os.environ.get("SPUNTO_IDENTITY_DATABASE_ROLE", "spunto_identity"),
    )
