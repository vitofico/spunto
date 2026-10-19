"""The enabled-plugin Review queue and owner suggestion decisions."""

from typing import Literal

from fastapi import APIRouter, Query

from spunto.api.deps import PrincipalDep, ServicesDep
from spunto.core.suggestions import decide, pending

router = APIRouter(prefix="/api/v1/suggestions", tags=["Suggestions"])


@router.get("")
async def list_suggestions(
    principal: PrincipalDep,
    services: ServicesDep,
    note_id: str | None = None,
    scope: Literal["note", "review"] = "note",
    limit: int = Query(100, ge=1, le=500),
):
    return await pending(services.store, principal, note_id=note_id, scope=scope, limit=limit)


@router.post("/{id}/accept")
async def accept(id: str, principal: PrincipalDep, services: ServicesDep):
    return await decide(services.store, principal, id, "accept")


@router.post("/{id}/dismiss")
async def dismiss(id: str, principal: PrincipalDep, services: ServicesDep):
    return await decide(services.store, principal, id, "dismiss")
