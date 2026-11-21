"""A hosted account's collaboration inbox and its per-membership subscriptions."""

from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends, Request, Response
from pydantic import BaseModel, ConfigDict

from spunto.api.errors import Problem


def inbox_scope(request: Request) -> str | None:
    principal = request.state.hosted_principal
    if principal.kind == "session":
        return None
    if principal.kind == "device":
        return principal.workspace_id
    raise Problem(403, "inbox_unavailable", "Sign in or use a paired device.")


router = APIRouter(prefix="/api/v1/notifications", tags=["Collaboration"])


class Follow(BaseModel):
    model_config = ConfigDict(extra="forbid")
    following: bool


class Notification(BaseModel):
    id: UUID
    kind: Literal["space_joined", "note_shared", "note_created"]
    workspace_id: str
    workspace_name: str
    note_id: str | None
    created_at: int
    read: bool


class Inbox(Follow):
    followable: bool
    notifications: list[Notification]


@router.get("", response_model=Inbox)
async def inbox(request: Request, response: Response, space: str | None = Depends(inbox_scope)):
    response.headers["Cache-Control"] = "private, no-store"
    accounts = request.state.services.hosted.accounts
    user = request.state.account["id"]
    workspace = request.state.workspace
    rows = await accounts.all(
        "SELECT n.id,n.kind,n.workspace_id,n.note_id,n.created_at,n.read,"
        "CASE WHEN m.user_id IS NOT NULL THEN w.name ELSE '' END AS workspace_name "
        "FROM public.spunto_notification n JOIN public.spunto_workspace w ON w.id=n.workspace_id "
        "LEFT JOIN public.spunto_membership m ON m.workspace_id=n.workspace_id AND m.user_id=%s "
        "WHERE n.recipient_id=%s AND (%s::text IS NULL OR "
        "(n.workspace_id=%s AND m.user_id IS NOT NULL)) "
        "ORDER BY n.created_at DESC,n.id DESC LIMIT 50",
        (user, user, space, space),
    )
    membership = await accounts.one(
        "SELECT following FROM public.spunto_membership WHERE user_id=%s AND workspace_id=%s",
        (user, workspace["id"]),
    )
    return {
        "notifications": rows,
        "followable": not workspace["personal"] and membership is not None,
        "following": bool(membership and membership["following"]),
    }


@router.put("/follow", response_model=Follow, dependencies=[Depends(inbox_scope)])
async def follow(body: Follow, request: Request):
    workspace = request.state.workspace
    if workspace["personal"]:
        raise Problem(409, "personal_space", "Choose a shared space to follow.")
    user = request.state.account["id"]
    async with request.state.services.hosted.accounts.transaction() as conn:
        updated = await conn.execute(
            "UPDATE public.spunto_membership SET following=%s WHERE workspace_id=%s AND user_id=%s",
            (body.following, workspace["id"], user),
        )
        if not updated.rowcount:
            raise Problem(404, "workspace_unavailable", "That space is unavailable.")
        if not body.following:
            await conn.execute(
                "DELETE FROM public.spunto_notification WHERE workspace_id=%s AND member_id=%s "
                "AND kind='note_created'",
                (workspace["id"], user),
            )
    return body


@router.post("/{event_id}/read", status_code=204)
async def mark_read(event_id: UUID, request: Request, space: str | None = Depends(inbox_scope)):
    async with request.state.services.hosted.accounts.transaction() as conn:
        updated = await conn.execute(
            "UPDATE public.spunto_notification n SET read=TRUE WHERE id=%s AND recipient_id=%s "
            "AND (%s::text IS NULL OR (workspace_id=%s AND EXISTS "
            "(SELECT 1 FROM public.spunto_membership m WHERE m.workspace_id=n.workspace_id "
            "AND m.user_id=n.recipient_id)))",
            (event_id, request.state.account["id"], space, space),
        )
        if not updated.rowcount:
            raise Problem(404, "not_found", "That notification is unavailable.")
    return Response(status_code=204)
