"""Owner-only AI destination inspection and fixed-content provider checks."""

from typing import Literal

from fastapi import APIRouter, Query
from pydantic import BaseModel, ConfigDict, Field

from spunto.ai.errors import AIError
from spunto.api.deps import PrincipalDep, ServicesDep, SessionDep
from spunto.api.errors import Problem

router = APIRouter(prefix="/api/v1/ai")


class ReindexBody(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    drop: bool = False


class ModelChoice(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    profile: str = Field(pattern=r"^[a-z][a-z0-9_]{0,23}$")
    model: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,199}$")


class ModelSelectionBody(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    revision: str = Field(min_length=1, max_length=64)
    selections: dict[Literal["chat", "embed", "transcribe", "vision"], ModelChoice]


def can_manage(principal):
    return principal.owner and principal.workspace_role == "owner"


def require_manage(principal):
    if not can_manage(principal):
        raise Problem(403, "owner_required", "Only the space owner can manage AI models.")


async def refresh(services, *, changed=False):
    await services.host.refresh_models(changed=changed)


async def status(services, principal):
    ai = await services.ai.status()
    if not can_manage(principal):
        # The allowance includes the owner's activity in their other, private spaces.
        ai["quota"] = None
    return {
        **ai,
        "indexing": await services.vectors.status(),
        "can_manage": can_manage(principal),
    }


def model_error(exc):
    code = exc.code
    if code == "ai_selection_changed":
        return Problem(409, code, "AI model choices changed. Reload before saving again.")
    if code == "ai_model_invalid":
        return Problem(422, code, "Choose an available model compatible with this capability.")
    return Problem(
        503, "ai_inventory_unavailable", "The provider could not verify the selected model."
    )


@router.get("")
async def configuration(services: ServicesDep, principal: PrincipalDep):
    if not principal.owner:
        raise Problem(403, "owner_required", "Use an owner session or device.")
    await refresh(services)
    return await status(services, principal)


@router.get("/models")
async def models(
    services: ServicesDep, principal: PrincipalDep, profile: str | None = Query(None, max_length=24)
):
    require_manage(principal)
    try:
        return await services.ai.selection.models(profile)
    except AIError as exc:
        raise model_error(exc) from None


@router.put("/models")
async def select_models(
    body: ModelSelectionBody, services: ServicesDep, session: SessionDep, principal: PrincipalDep
):
    require_manage(principal)
    try:
        changed = await services.ai.selection.save(
            body.revision, {cap: choice.model_dump() for cap, choice in body.selections.items()}
        )
        await refresh(services, changed=changed)
    except AIError as exc:
        raise model_error(exc) from None
    return await status(services, principal)


@router.post("/check")
async def check(services: ServicesDep, session: SessionDep, principal: PrincipalDep):
    require_manage(principal)
    await refresh(services)
    health = await services.ai.health_check()
    try:
        await services.host.refresh_ai()
    except AIError as exc:
        return {"health": health, "refresh_error": exc.code}
    return {"health": health}


@router.post("/reindex")
async def reindex(
    body: ReindexBody, services: ServicesDep, session: SessionDep, principal: PrincipalDep
):
    require_manage(principal)
    await refresh(services)
    try:
        await services.vectors.reindex(drop=body.drop)
    except AIError as exc:
        raise Problem(503, exc.code, "The configured AI service is unavailable.") from None
    return {"indexing": await services.vectors.status()}
