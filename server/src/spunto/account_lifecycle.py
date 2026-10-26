"""Generic account export/closure/purge contracts for an authenticated operator client."""

import asyncio
import hmac
import json
import secrets
import shutil
from pathlib import Path

from fastapi import Request
from fastapi.responses import StreamingResponse

from spunto.api.errors import Problem
from spunto.auth.accounts import digest
from spunto.store.db import now_ms


async def blockers(conn, user_id):
    shared = await (
        await conn.execute(
            "SELECT count(*) AS n FROM public.spunto_workspace WHERE created_by=%s AND NOT "
            "personal",
            (user_id,),
        )
    ).fetchone()
    grants = await (
        await conn.execute(
            "SELECT count(*) AS n FROM public.spunto_note_share WHERE created_by=%s", (user_id,)
        )
    ).fetchone()
    return shared["n"], grants["n"]


def install(app, manager):
    @app.post("/api/v1/auth/account/closure")
    async def prepare(request: Request, body: dict):
        if request.state.hosted_principal.kind != "session":
            raise Problem(403, "session_required", "Use a browser session to close this account.")
        user_id = request.state.account["id"]
        from spunto.auth import sessions
        from spunto.hosted import _local_guess

        token = request.cookies.get(sessions.cookie_name(manager.settings), "")
        local = await manager.accounts.one(
            "SELECT user_id FROM public.spunto_local_credential WHERE user_id=%s", (user_id,)
        )
        if local:
            if (
                not isinstance(body.get("current_password", ""), str)
                or len(body.get("current_password", "")) > 1024
            ):
                raise Problem(
                    422,
                    "validation_failed",
                    "Provide a current password of at most 1024 characters.",
                )
            async with _local_guess(request, request.state.services, user_id):
                await manager.accounts.verify_local_password(
                    user_id, body.get("current_password", ""), manager.dummy_password_hash
                )
        choice = body.get("delete_owned_shared", False)
        if type(choice) is not bool or body.get("confirm") != "DELETE MY ACCOUNT":
            raise Problem(
                422, "confirmation_required", "Confirm account deletion and the shared-data choice."
            )
        async with manager.accounts.transaction() as conn:
            await conn.execute(
                "SELECT id FROM public.spunto_account WHERE id=%s FOR UPDATE", (user_id,)
            )
            shared, grants = await blockers(conn, user_id)
            if (shared or grants) and not choice:
                raise Problem(
                    409,
                    "shared_data_requires_choice",
                    "Transfer owned shared spaces or explicitly choose to delete your owned "
                    "shared data.",
                )
            if not local:
                proof = await (
                    await conn.execute(
                        "DELETE FROM public.spunto_account_reauthentication WHERE session_hash=%s "
                        "AND user_id=%s AND purpose='account-delete' AND expires_at>%s "
                        "RETURNING user_id",
                        (digest(token), user_id, now_ms()),
                    )
                ).fetchone()
                if not proof:
                    raise Problem(
                        403,
                        "reauthentication_required",
                        "Verify a fresh provider sign-in before account deletion.",
                    )
            row = await (
                await conn.execute(
                    "INSERT INTO "
                    "public.spunto_account_closure(id,user_id,shared_choice,created_at) "
                    "VALUES (%s,%s,%s,%s) ON CONFLICT(user_id) DO UPDATE SET "
                    "shared_choice=excluded.shared_choice RETURNING id",
                    (secrets.token_urlsafe(32), user_id, choice, now_ms()),
                )
            ).fetchone()
        return {
            "closure_id": row["id"],
            "account_id": user_id,
            "shared_spaces": shared,
            "shared_notes": grants,
            "identity": {
                "issuer": request.state.account["issuer"],
                "subject": request.state.account["subject"],
            },
        }

    def authorize(request):
        configured = manager.settings.account_service_token
        if not configured or not hmac.compare_digest(
            request.headers.get("authorization", "").encode(), ("Bearer " + configured).encode()
        ):
            raise Problem(
                401, "service_unauthorized", "A scoped account-service credential is required."
            )

    @app.get("/internal/v1/accounts/{user_id}/export")
    async def export(user_id: str, request: Request):
        authorize(request)
        # Service export needs a browser-created closure receipt, not an arbitrary account ID.
        closure = await manager.accounts.one(
            "SELECT id FROM public.spunto_account_closure WHERE user_id=%s AND id=%s",
            (user_id, request.headers.get("x-spunto-closure")),
        )
        if not closure:
            raise Problem(403, "closure_required", "A prepared account operation is required.")
        return {"account_id": user_id, "export_url": "/api/v1/account/export"}

    @app.get("/api/v1/account/export")
    async def account_export(request: Request):
        import tempfile
        import zipfile

        from spunto.api.notes import _chunks
        from spunto.auth.tokens import Principal
        from spunto.core.postgres_export import build_postgres_zip

        user_id = request.state.account["id"]
        if request.state.hosted_principal.kind != "session":
            raise Problem(403, "session_required", "Export your account from a browser session.")
        handle, filename = tempfile.mkstemp(suffix=".zip", prefix="spunto-account-")
        import os

        os.close(handle)
        paths = []
        try:
            owned = await manager.accounts.all(
                "SELECT * FROM public.spunto_workspace WHERE created_by=%s", (user_id,)
            )
            for workspace in owned:
                services = await manager.services({**workspace, "role": "owner"}, user_id)
                principal = Principal(
                    "account-export",
                    "session",
                    "Account export",
                    "full",
                    private_ok=True,
                    user_id=user_id,
                    workspace_id=workspace["id"],
                    workspace_role="owner",
                )
                path = await build_postgres_zip(services, principal)
                paths.append((path, workspace["id"] + ".zip"))

            def combine():
                with zipfile.ZipFile(filename, "w", zipfile.ZIP_DEFLATED) as archive:
                    for path, name in paths:
                        archive.write(path, name)

            await asyncio.to_thread(combine)
            return StreamingResponse(
                _chunks(Path(filename)),
                media_type="application/zip",
                headers={"Content-Disposition": "attachment; filename=spunto-account.zip"},
            )
        except BaseException:
            await asyncio.to_thread(Path(filename).unlink, missing_ok=True)
            raise
        finally:
            for path, _ in paths:
                await asyncio.to_thread(path.unlink, missing_ok=True)

    @app.post("/internal/v1/accounts/{user_id}/close")
    async def close(user_id: str, body: dict, request: Request):
        authorize(request)
        async with manager.accounts.transaction() as conn:
            account = await (
                await conn.execute(
                    "SELECT * FROM public.spunto_account WHERE id=%s FOR UPDATE", (user_id,)
                )
            ).fetchone()
            closure = await (
                await conn.execute(
                    "SELECT * FROM public.spunto_account_closure WHERE user_id=%s AND id=%s "
                    "FOR UPDATE",
                    (user_id, body.get("closure_id")),
                )
            ).fetchone()
            if not closure:
                raise Problem(
                    403,
                    "closure_required",
                    "A prepared operation for this exact account is required.",
                )
            if closure["state"] != "prepared":
                return {"account_id": user_id, "state": closure["state"]}
            shared, grants = await blockers(conn, user_id)
            if (shared or grants) and not closure["shared_choice"]:
                raise Problem(
                    409,
                    "shared_data_requires_choice",
                    "Shared ownership changed; confirm the shared-data choice again.",
                )
            owned = await (
                await conn.execute(
                    "SELECT id FROM public.spunto_workspace WHERE created_by=%s ORDER BY id",
                    (user_id,),
                )
            ).fetchall()
            await conn.execute(
                "UPDATE public.spunto_account SET closed_at=%s WHERE id=%s", (now_ms(), user_id)
            )
            await conn.execute(
                "UPDATE public.spunto_login_session SET revoked_at=%s WHERE user_id=%s",
                (now_ms(), user_id),
            )
            await conn.execute("DELETE FROM public.spunto_pairing WHERE user_id=%s", (user_id,))
            identity = (
                digest(account["issuer"] + "\0" + account["subject"]) if account["issuer"] else None
            )
            await conn.execute(
                "UPDATE public.spunto_account_closure SET "
                "state='closed',identity_digest=%s,workspaces=%s::jsonb WHERE id=%s",
                (identity, json.dumps([w["id"] for w in owned]), closure["id"]),
            )
        return {"account_id": user_id, "state": "closed"}

    @app.post("/internal/v1/accounts/{user_id}/purge")
    async def purge(user_id: str, body: dict, request: Request):
        authorize(request)
        row = await manager.accounts.one(
            "SELECT * FROM public.spunto_account_closure WHERE user_id=%s AND id=%s",
            (user_id, body.get("closure_id")),
        )
        if not row or row["state"] == "prepared":
            raise Problem(409, "account_not_closed", "Close this exact account before purging.")
        if row["state"] == "purged":
            return {"account_id": user_id, "state": "purged"}
        # Close only this account's owned runtimes, never a space it merely joined.
        for workspace_id in row["workspaces"]:
            async with manager._lock:
                runtime = manager._runtimes.get(workspace_id)
            if runtime:
                await runtime.host.close()
                await runtime.ai.close()
                manager._runtimes.pop(workspace_id, None)
            for directory in (
                manager.settings.data_dir / "workspaces" / workspace_id,
                manager.settings.attachments_dir / workspace_id,
                manager.settings.backup_dir / workspace_id,
            ):
                if directory.exists():
                    await asyncio.to_thread(shutil.rmtree, directory)
        async with manager.accounts.transaction() as conn:
            await conn.execute(
                "SELECT id FROM public.spunto_account WHERE id=%s FOR UPDATE", (user_id,)
            )
            await conn.execute(
                "DELETE FROM public.spunto_note_share WHERE grantee_id=%s OR created_by=%s",
                (user_id, user_id),
            )
            await conn.execute(
                "DELETE FROM public.spunto_workspace WHERE created_by=%s", (user_id,)
            )
            await conn.execute("DELETE FROM public.spunto_account WHERE id=%s", (user_id,))
            await conn.execute(
                "UPDATE public.spunto_account_closure SET state='purged' WHERE id=%s", (row["id"],)
            )
        return {"account_id": user_id, "state": "purged"}
