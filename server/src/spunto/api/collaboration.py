"""Space membership and explicit, revocable, signed-in individual-note grants."""

import re
from typing import Literal

from fastapi import APIRouter, Depends, Request, Response
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator

from spunto.api.deps import current_session
from spunto.api.errors import Problem, problem_response
from spunto.auth.tokens import Principal
from spunto.core.attachments import path_for
from spunto.core.notes import Notes
from spunto.store.db import now_ms
from spunto.store.sharing import SharedEditStore

router = APIRouter(
    prefix="/api/v1", tags=["Collaboration"], dependencies=[Depends(current_session)]
)


class CreateSpace(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=80)

    @field_validator("name")
    @classmethod
    def clean(cls, value):
        value = value.strip()
        if not value or any(ord(c) < 32 or ord(c) == 127 for c in value):
            raise ValueError("Use a visible space name without control characters.")
        return value


class Grant(BaseModel):
    model_config = ConfigDict(extra="forbid")
    email: str | None = Field(None, min_length=3, max_length=320)
    user_id: str | None = Field(None, min_length=1, max_length=80)
    username: str | None = Field(None, min_length=3, max_length=64)
    role: Literal["editor", "viewer"] = "viewer"


class SharedEdit(BaseModel):
    model_config = ConfigDict(extra="forbid")
    body: str = Field(max_length=100_000)
    version: int = Field(ge=1, strict=True)


def context(request):
    return request.state.services.hosted, request.state.account, request.state.workspace


@router.get("/workspaces")
async def workspaces(request: Request):
    manager, account, workspace = context(request)
    return {
        "workspaces": await manager.accounts.workspaces(account["id"]),
        "active_workspace_id": workspace["id"],
    }


@router.post("/workspaces", status_code=201)
async def create_workspace(body: CreateSpace, request: Request):
    manager, account, _ = context(request)
    if manager.account_policy:
        await manager.account_policy.prepare(account["id"])
    workspace = await manager.accounts.create_workspace(account["id"], body.name)
    await manager.services(workspace, account["id"])
    return workspace


@router.get("/workspaces/{workspace_id}/members")
async def members(workspace_id: str, request: Request):
    manager, account, _ = context(request)
    await manager.accounts.workspace(account["id"], workspace_id)
    rows = await manager.accounts.all(
        "SELECT a.id AS user_id,a.email,a.display_name,c.username,m.role "
        "FROM public.spunto_membership m JOIN public.spunto_account a ON a.id=m.user_id "
        "LEFT JOIN public.spunto_local_credential c ON c.user_id=a.id "
        "WHERE m.workspace_id=%s ORDER BY m.role,a.display_name",
        (workspace_id,),
    )
    return {"members": rows}


@router.post("/workspaces/{workspace_id}/members")
async def add_member(workspace_id: str, body: Grant, request: Request):
    manager, account, _ = context(request)
    workspace = await manager.accounts.require_owner(account["id"], workspace_id)
    if workspace["personal"]:
        raise Problem(409, "personal_space", "Create a shared space or share an individual note.")
    recipient = await manager.accounts.recipient(
        email=body.email, user_id=body.user_id, username=body.username
    )
    if manager.account_policy:
        await manager.account_policy.prepare(account["id"])
    if recipient["id"] == account["id"]:
        raise Problem(409, "owner_membership", "The owner's membership cannot be changed here.")
    async with manager.accounts.transaction() as conn:
        await conn.execute(
            "INSERT INTO public.spunto_membership(workspace_id,user_id,role) VALUES (%s,%s,%s) "
            "ON CONFLICT (workspace_id,user_id) DO UPDATE SET role=excluded.role "
            "WHERE spunto_membership.role<>'owner'",
            (workspace_id, recipient["id"], body.role),
        )
    return {
        "user_id": recipient["id"],
        "email": recipient["email"],
        "display_name": recipient["display_name"],
        "username": recipient["username"],
        "role": body.role,
    }


@router.delete("/workspaces/{workspace_id}/members/{user_id}", status_code=204)
async def remove_member(workspace_id: str, user_id: str, request: Request):
    manager, account, _ = context(request)
    await manager.accounts.require_owner(account["id"], workspace_id)
    if user_id == account["id"]:
        raise Problem(409, "owner_membership", "The owner's membership cannot be removed.")
    async with manager.accounts.transaction() as conn:
        await conn.execute(
            "DELETE FROM public.spunto_membership WHERE workspace_id=%s AND user_id=%s "
            "AND role<>'owner'",
            (workspace_id, user_id),
        )
    return Response(status_code=204)


async def owned_note(request, note_id):
    manager, account, workspace = context(request)
    await manager.accounts.require_owner(account["id"], workspace["id"])
    note = await Notes(request.state.services.store, request.state.hosted_principal).get(note_id)
    return manager, account, workspace, note


@router.get("/notes/{note_id}/shares")
async def shares(note_id: str, request: Request):
    manager, _, workspace, _ = await owned_note(request, note_id)
    return {
        "shares": await manager.accounts.all(
            "SELECT a.id AS user_id,a.email,a.display_name,c.username,s.role "
            "FROM public.spunto_note_share s "
            "JOIN public.spunto_account a ON a.id=s.grantee_id "
            "LEFT JOIN public.spunto_local_credential c ON c.user_id=a.id "
            "WHERE s.workspace_id=%s AND s.note_id=%s ORDER BY a.display_name",
            (workspace["id"], note_id),
        )
    }


@router.post("/notes/{note_id}/shares")
async def share(note_id: str, body: Grant, request: Request):
    manager, account, workspace, _ = await owned_note(request, note_id)
    recipient = await manager.accounts.recipient(
        email=body.email, user_id=body.user_id, username=body.username
    )
    if recipient["id"] == account["id"]:
        raise Problem(409, "own_note", "This note already belongs to you.")
    async with manager.accounts.transaction() as conn:
        await conn.execute(
            "INSERT INTO public.spunto_note_share "
            "(workspace_id,note_id,grantee_id,role,created_by,created_at) "
            "VALUES (%s,%s,%s,%s,%s,%s) "
            "ON CONFLICT(workspace_id,note_id,grantee_id) DO UPDATE SET role=excluded.role",
            (workspace["id"], note_id, recipient["id"], body.role, account["id"], now_ms()),
        )
    return {
        "user_id": recipient["id"],
        "email": recipient["email"],
        "display_name": recipient["display_name"],
        "username": recipient["username"],
        "role": body.role,
    }


@router.delete("/notes/{note_id}/shares/{user_id}", status_code=204)
async def unshare(note_id: str, user_id: str, request: Request):
    manager, _, workspace, _ = await owned_note(request, note_id)
    async with manager.accounts.transaction() as conn:
        await conn.execute(
            "DELETE FROM public.spunto_note_share WHERE workspace_id=%s AND note_id=%s "
            "AND grantee_id=%s",
            (workspace["id"], note_id, user_id),
        )
    return Response(status_code=204)


async def shared_services(request, grant):
    manager, account, _ = context(request)
    workspace = await manager.accounts.one(
        "SELECT * FROM public.spunto_workspace WHERE id=%s",
        (grant["workspace_id"],),
    )
    if workspace is None:
        raise Problem(404, "not_found", "That note is unavailable.")
    scoped = await manager.services({**workspace, "role": grant["role"]}, account["id"])
    principal = Principal(
        "share:" + account["id"],
        "session",
        account["display_name"],
        "full",
        private_ok=True,
        user_id=account["id"],
        workspace_id=workspace["id"],
        workspace_role=grant["role"],
    )
    return scoped, principal


async def shared_note(request, grant):
    services, principal = await shared_services(request, grant)
    domain = Notes(services.store, principal)
    async with services.store.read() as conn:
        note = await domain._serialize(
            conn, await domain._row(conn, grant["note_id"]), include_plugins=False
        )
    from spunto.core.render import render_markdown

    note["html"] = render_markdown(note["body"])
    note["html"] = re.sub(
        r'(["\'])/a/([a-fA-F0-9-]{36})',
        lambda m: (
            m[1]
            + "/api/v1/shared-notes/"
            + grant["note_id"]
            + "/attachments/"
            + m[2]
            + "?workspace_id="
            + grant["workspace_id"]
        ),
        note["html"],
    )
    # A recipient gets this note, never the owner's label browser/settings/history.
    note.update(
        share_role=grant["role"],
        workspace_id=grant["workspace_id"],
        shared_by=grant.get("shared_by", "Spunto user"),
    )
    return note


async def grant_for(request, note_id):
    manager, account, _ = context(request)
    workspace_id = request.query_params.get("workspace_id")
    rows = await manager.accounts.all(
        "SELECT s.*,a.display_name AS shared_by FROM public.spunto_note_share s "
        "JOIN public.spunto_account a ON a.id=s.created_by "
        "WHERE s.grantee_id=%s AND s.note_id=%s "
        "AND (%s::text IS NULL OR s.workspace_id=%s)",
        (account["id"], note_id, workspace_id, workspace_id),
    )
    if len(rows) != 1:
        raise Problem(404, "not_found", "That note is unavailable.")
    return rows[0]


@router.get("/shared-notes")
async def shared_notes(request: Request):
    manager, account, _ = context(request)
    grants = await manager.accounts.all(
        "SELECT s.*,a.display_name AS shared_by FROM public.spunto_note_share s "
        "JOIN public.spunto_account a ON a.id=s.created_by "
        "WHERE s.grantee_id=%s ORDER BY s.created_at DESC LIMIT 500",
        (account["id"],),
    )
    result = []
    for grant in grants:
        try:
            result.append(await shared_note(request, grant))
        except Problem as exc:
            if exc.status != 404:
                raise
    return {"notes": result}


@router.get("/shared-notes/{note_id}")
async def read_shared(note_id: str, request: Request):
    return await shared_note(request, await grant_for(request, note_id))


@router.patch("/shared-notes/{note_id}")
async def edit_shared(note_id: str, body: SharedEdit, request: Request):
    grant = await grant_for(request, note_id)
    services, principal = await shared_services(request, grant)
    # Grant lock, note edit, conflict copy and its grant share one database commit.
    # A downgrade/revocation takes effect before the next edit can acquire this lock.
    scoped = services.hosted.store.for_workspace(grant["workspace_id"], principal.user_id, "editor")
    scoped.event_sink, scoped.change_event = services.store.event_sink, services.store.change_event
    scoped._dispatch_tasks = services.store._dispatch_tasks
    async with scoped.write() as conn:
        access = await conn.execute_fetchall("SELECT spunto.lock_shared_note(?)", (note_id,))
        if access[0][0] != "editor":
            raise Problem(403, "read_only_note", "This shared note is read-only.")
        principal = Principal(**{**principal.__dict__, "workspace_role": "editor"})
        result = await Notes(SharedEditStore(scoped, conn, note_id), principal).edit(
            note_id,
            body.body,
            body.version,
        )
        if result.get("conflict"):
            await conn.execute(
                "SELECT spunto.grant_conflict_copy(?,?)",
                (note_id, result["conflict_id"]),
            )
            response = problem_response(
                409, "version_conflict", "Your conflicting edit was preserved."
            )
            import json

            data = json.loads(response.body)
            data.update(conflict_id=result["conflict_id"], version=result["version"])
            return JSONResponse(data, status_code=409, media_type="application/problem+json")
    return await shared_note(request, grant)


@router.get("/shared-notes/{note_id}/attachments/{attachment_id}")
async def shared_attachment(note_id: str, attachment_id: str, request: Request):
    grant = await grant_for(request, note_id)
    services, principal = await shared_services(request, grant)
    await Notes(services.store, principal).get(note_id)
    async with services.store.read() as conn:
        rows = await conn.execute_fetchall(
            "SELECT a.* FROM attachment a JOIN note_attachment n ON n.attachment_id=a.id "
            "WHERE n.note_id=? AND a.id=?",
            (note_id, attachment_id),
        )
    if not rows or not re.fullmatch(r"[a-f0-9]{64}", rows[0]["sha256"]):
        raise Problem(404, "not_found", "That attachment is unavailable.")
    path = path_for(services.settings.attachments_dir, rows[0]["sha256"])
    if not path.is_file():
        raise Problem(404, "not_found", "That attachment is unavailable.")
    inline = rows[0]["mime"] in {"image/png", "image/jpeg", "image/gif", "image/webp"}
    return FileResponse(
        path,
        media_type=rows[0]["mime"] if inline else "application/octet-stream",
        filename=None if inline else "attachment",
        content_disposition_type="inline" if inline else "attachment",
        headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"},
    )
