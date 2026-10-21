"""The M1 core REST API. All writes call the shared note service."""

import asyncio
import re
import uuid
from datetime import date, datetime
from pathlib import Path
from typing import Annotated, Literal

from fastapi import APIRouter, Header, Query, Response
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, model_validator

from spunto.api.deps import PrincipalDep, ServicesDep, SessionDep
from spunto.api.errors import Problem, problem_response
from spunto.core.export import build_zip
from spunto.core.notes import Notes

router = APIRouter(prefix="/api/v1", tags=["Notes"])


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Source(Input):
    kind: Literal["web", "android", "share", "selection", "import", "agent", "api", "plugin"] = (
        "api"
    )
    ref: str | None = Field(None, max_length=2000)
    url: str | None = Field(None, max_length=2000)


class Note(BaseModel):
    id: str
    body: str
    title: str
    labels: list[str]
    version: int
    change_seq: int
    created_at: str
    updated_at: str
    pinned: bool
    archived: bool
    trashed: bool
    private: bool
    flag_times: dict[str, int] = Field(default_factory=dict)
    open_tasks: int
    source: Source
    origin: Literal["owner", "agent", "plugin", "external"]
    machine: bool
    updated_via_agent: bool = False
    conflict_of: str | None
    suggestions: list[dict] = Field(default_factory=list)
    annotations: list[dict] = Field(default_factory=list)
    html: str | None = None
    merged: bool | None = None
    duplicate: bool | None = None


class Page(BaseModel):
    notes: list[Note]
    next_cursor: str | None


class SearchHit(Note):
    match: Literal["keyword", "meaning", "both"]


class SearchPage(BaseModel):
    hits: list[SearchHit]
    next_cursor: str | None
    semantic: Literal["off", "on", "unavailable"]


class Create(Input):
    id: str | None = None
    body: str = Field(min_length=1, max_length=100_000)
    created_at: datetime | None = None
    source: Source | None = None
    private: bool = False

    @model_validator(mode="after")
    def _timezone(self):
        if self.created_at and self.created_at.tzinfo is None:
            raise ValueError("created_at needs a time zone")
        return self


class Edit(Input):
    body: str = Field(max_length=100_000)


class Append(Input):
    text: str = Field(min_length=1, max_length=100_000)
    expected_version: int | None = Field(None, ge=1)
    separator: Literal["paragraph", "line"] = "paragraph"


class Flags(Input):
    pinned: bool | None = None
    archived: bool | None = None
    trashed: bool | None = None
    private: bool | None = None
    pinned_at: datetime | None = None
    archived_at: datetime | None = None
    trashed_at: datetime | None = None
    private_at: datetime | None = None

    @model_validator(mode="after")
    def _clock(self):
        if not any(
            getattr(self, name) is not None for name in ("pinned", "archived", "trashed", "private")
        ):
            raise ValueError("provide at least one flag")
        for name in ("pinned", "archived", "trashed", "private"):
            value = getattr(self, f"{name}_at")
            if value is not None and (value.tzinfo is None or getattr(self, name) is None):
                raise ValueError("flag times need a time zone and the corresponding flag")
        return self


@router.get("/notes", response_model=Page, response_model_exclude_unset=True)
async def list_notes(
    principal: PrincipalDep,
    services: ServicesDep,
    view: str = "timeline",
    label: str | None = None,
    from_date: Annotated[date | None, Query(alias="from")] = None,
    to_date: Annotated[date | None, Query(alias="to")] = None,
    limit: Annotated[int, Query(ge=1, le=500)] = 50,
    cursor: str | None = None,
    render: Literal["html"] | None = None,
):
    return await Notes(services.store, principal).list(
        view=view,
        labels=[label] if label else [],
        from_date=from_date,
        to_date=to_date,
        limit=limit,
        cursor=cursor,
        render=render == "html",
    )


@router.post("/notes", status_code=201, response_model=Note, response_model_exclude_unset=True)
async def create_note(
    body: Create,
    principal: PrincipalDep,
    services: ServicesDep,
    idempotency_key: Annotated[str | None, Header()] = None,
):
    if idempotency_key:
        try:
            uuid.UUID(idempotency_key)
        except ValueError:
            raise Problem(422, "validation_failed", "Idempotency-Key must be a UUID.") from None
    return await Notes(services.store, principal).create(
        body.body,
        id=body.id,
        created_at=int(body.created_at.timestamp() * 1000) if body.created_at else None,
        source=body.source.model_dump() if body.source else None,
        private=body.private,
        op_id=idempotency_key,
    )


@router.get("/notes/{id}", response_model=Note, response_model_exclude_unset=True)
async def get_note(
    id: str, principal: PrincipalDep, services: ServicesDep, render: Literal["html"] | None = None
):
    return await Notes(services.store, principal).get(
        id, include_trashed=True, render=render == "html"
    )


@router.patch(
    "/notes/{id}",
    response_model=Note,
    response_model_exclude_unset=True,
    responses={
        409: {"description": "Overlapping edit; conflict_id identifies the preserved incoming copy"}
    },
)
async def edit_note(
    id: str,
    body: Edit,
    principal: PrincipalDep,
    services: ServicesDep,
    if_match: Annotated[str | None, Header()] = None,
):
    if if_match is None or not re.fullmatch(r'"[1-9][0-9]*"', if_match):
        raise Problem(
            428, "version_required", 'Send If-Match with the quoted version, such as "3".'
        )
    result = await Notes(services.store, principal).edit(id, body.body, int(if_match[1:-1]))
    if result.get("conflict"):
        response = problem_response(
            409,
            "version_conflict",
            f"Overlapping edits; your text is preserved in note {result['conflict_id']}.",
        )
        import json

        data = json.loads(response.body)
        data.update(conflict_id=result["conflict_id"], version=result["version"])
        return JSONResponse(data, status_code=409, media_type="application/problem+json")
    return result


@router.post("/notes/{id}/append", response_model=Note, response_model_exclude_unset=True)
async def append_note(id: str, body: Append, principal: PrincipalDep, services: ServicesDep):
    return await Notes(services.store, principal).append(
        id, body.text, body.expected_version, body.separator
    )


@router.post("/notes/{id}/flags", response_model=Note, response_model_exclude_unset=True)
async def flag_note(id: str, body: Flags, principal: PrincipalDep, services: ServicesDep):
    flags = {
        name: getattr(body, name)
        for name in ("pinned", "archived", "trashed", "private")
        if getattr(body, name) is not None
    }
    clocks = {
        name: int(value.timestamp() * 1000)
        for name in flags
        if (value := getattr(body, f"{name}_at")) is not None
    }
    return await Notes(services.store, principal).flags(id, flags, clocks)


@router.delete("/notes/{id}", status_code=204)
async def delete_note(id: str, principal: PrincipalDep, session: SessionDep, services: ServicesDep):
    await Notes(services.store, principal).delete(id)
    return Response(status_code=204)


@router.post("/trash/empty")
async def empty_trash(principal: PrincipalDep, session: SessionDep, services: ServicesDep):
    return {"deleted": await Notes(services.store, principal).empty_trash()}


@router.get("/notes/{id}/revisions")
async def revisions(id: str, principal: PrincipalDep, services: ServicesDep):
    return await Notes(services.store, principal).revisions(id)


@router.post(
    "/notes/{id}/revisions/{version}/restore",
    response_model=Note,
    response_model_exclude_unset=True,
)
async def restore_revision(
    id: str, version: int, principal: PrincipalDep, session: SessionDep, services: ServicesDep
):
    return await Notes(services.store, principal).restore_revision(id, version)


@router.get("/search", response_model=SearchPage, response_model_exclude_unset=True)
async def search(
    principal: PrincipalDep,
    services: ServicesDep,
    q: Annotated[str, Query(max_length=500)] = "",
    labels: Annotated[list[str] | None, Query()] = None,
    from_date: Annotated[date | None, Query(alias="from")] = None,
    to_date: Annotated[date | None, Query(alias="to")] = None,
    mode: Literal["hybrid", "keyword"] = "hybrid",
    include_archived: bool = False,
    sort: Literal["relevance", "recent"] = "relevance",
    limit: Annotated[int, Query(ge=1, le=50)] = 50,
    cursor: str | None = None,
    render: Literal["html"] | None = None,
):
    return await Notes(services.store, principal).search(
        q,
        labels,
        from_date,
        to_date,
        include_archived,
        sort,
        limit,
        cursor,
        render=render == "html",
        mode=mode,
    )


@router.get("/labels")
async def list_labels(
    principal: PrincipalDep,
    services: ServicesDep,
    prefix: str | None = None,
    limit: Annotated[int, Query(ge=1, le=500)] = 500,
):
    return await Notes(services.store, principal).labels(prefix, limit, include_archived=False)


class Rename(Input):
    old: str = Field(alias="from", max_length=128)
    new: str = Field(alias="to", max_length=128)


class Remove(Input):
    path: str = Field(max_length=128)


class Impact(Input):
    old: str | None = Field(None, max_length=128)
    new: str | None = Field(None, max_length=128)
    note_ids: list[str] | None = Field(None, min_length=1, max_length=10)


@router.post("/labels/impact")
async def label_impact(
    body: Impact, principal: PrincipalDep, session: SessionDep, services: ServicesDep
):
    from spunto.core.text import label_key, rewrite_labels

    try:
        if body.note_ids is not None:
            from spunto.core.suggestions import validate_payload
            from spunto.core.text import labels

            if body.old is not None or body.new is None:
                raise ValueError("A cluster needs new and note_ids, without old.")
            payload = validate_payload("label_change", {"new": body.new, "note_ids": body.note_ids})
            domain = Notes(services.store, principal)
            async with services.store.read() as conn:
                rows = [await domain._row(conn, i) for i in payload["note_ids"]]
                if any(r["private"] or r["machine"] or labels(r["body"]) for r in rows):
                    raise ValueError("A cluster needs eligible unlabeled notes.")
            return {"notes": len(rows)}
        if body.old is None:
            raise ValueError("A rename needs old.")
        label_key(body.old)
        if body.new is not None:
            label_key(body.new)
        async with services.store.read() as conn:
            rows = await conn.execute_fetchall("SELECT body FROM note")
            count = sum(rewrite_labels(row[0], body.old, body.new) != row[0] for row in rows)
    except ValueError as error:
        raise Problem(422, "validation_failed", str(error)) from None
    return {"notes": count}


@router.post("/labels/rename")
async def rename_label(
    body: Rename, principal: PrincipalDep, session: SessionDep, services: ServicesDep
):
    return {"rewritten": await Notes(services.store, principal).rewrite_label(body.old, body.new)}


@router.post("/labels/remove")
async def remove_label(
    body: Remove, principal: PrincipalDep, session: SessionDep, services: ServicesDep
):
    return {"rewritten": await Notes(services.store, principal).rewrite_label(body.path, None)}


async def _chunks(path: Path):
    try:
        with path.open("rb") as source:
            while chunk := await asyncio.to_thread(source.read, 64 * 1024):
                yield chunk
    finally:
        await asyncio.to_thread(path.unlink, missing_ok=True)


@router.get("/export", response_class=StreamingResponse)
async def export(principal: PrincipalDep, services: ServicesDep):
    if principal.kind not in {"session", "pat"}:
        raise Problem(
            403, "session_required", "Export from an owner session or personal access token."
        )
    if getattr(services.store, "backend", None) == "postgres":
        from spunto.core.postgres_export import build_postgres_zip

        path = await build_postgres_zip(services, principal)
    else:
        path = await asyncio.to_thread(build_zip, services.settings, principal)
    return StreamingResponse(
        _chunks(path),
        media_type="application/zip",
        headers={"Content-Disposition": 'attachment; filename="spunto-notes.zip"'},
    )
