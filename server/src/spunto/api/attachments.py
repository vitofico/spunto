"""Authenticated downloads for the content-addressed /a/ links in note bodies."""

import asyncio
import re

from fastapi import APIRouter, Request
from fastapi.responses import FileResponse
from starlette.datastructures import UploadFile
from starlette.exceptions import HTTPException
from starlette.formparsers import MultiPartException

from spunto.api.deps import PrincipalDep, ServicesDep
from spunto.api.errors import Problem
from spunto.core.attachments import path_for, write_attachment
from spunto.core.notes import Notes

router = APIRouter(tags=["Attachments"])


@router.get("/a/{id}")
async def download(id: str, principal: PrincipalDep, services: ServicesDep):
    visibility, _ = Notes(services.store, principal)._visibility()
    async with services.store.read() as conn:
        rows = await conn.execute_fetchall(
            "SELECT a.* FROM attachment a WHERE a.id=? AND (? OR EXISTS ("  # noqa: S608
            "SELECT 1 FROM note_attachment na JOIN note n ON n.id=na.note_id "
            "WHERE na.attachment_id=a.id AND n.trashed_at IS NULL AND " + visibility + "))",
            (id, int(principal.owner)),
        )
    if not rows or not re.fullmatch(r"[a-f0-9]{64}", rows[0]["sha256"]):
        raise Problem(404, "not_found", "That attachment is unavailable.")
    row = dict(rows[0])
    path = path_for(services.settings.attachments_dir, row["sha256"])
    if not await asyncio.to_thread(path.is_file):
        raise Problem(404, "not_found", "That attachment is unavailable.")
    inline = row["mime"] in {"image/png", "image/jpeg", "image/gif", "image/webp"}
    return FileResponse(
        path,
        media_type=row["mime"] if inline else "application/octet-stream",
        filename=row["filename"] or "attachment",
        content_disposition_type="inline" if inline else "attachment",
        headers={"Cache-Control": "private, no-store"},
    )


@router.post(
    "/api/v1/attachments",
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {
                "multipart/form-data": {
                    "schema": {
                        "type": "object",
                        "required": ["file"],
                        "properties": {"file": {"type": "string", "format": "binary"}},
                    }
                }
            },
        }
    },
)
async def upload(request: Request, principal: PrincipalDep, services: ServicesDep):
    if not principal.writable:
        raise Problem(403, "read_only_token", "This token is read-only.")
    file_maximum = services.settings.max_upload_bytes
    if services.hosted and services.hosted.account_policy:
        policy = await services.hosted.account_policy.workspace(services.store._context[0])
        if policy.get("file_bytes") is not None:
            file_maximum = min(file_maximum, policy["file_bytes"])
    maximum = file_maximum + 65536
    detail = f"Choose a file of at most {file_maximum / 1_000_000:g} MB."
    length = request.headers.get("content-length")
    if length:
        digits = length.lstrip("0") or "0"
        if (
            not length.isascii()
            or not length.isdecimal()
            or len(digits) > len(str(maximum))
            or int(digits) > maximum
        ):
            raise Problem(413, "attachment_too_large", detail)
    received = 0
    oversized = False

    async def receive():
        nonlocal received, oversized
        message = await request.receive()
        received += len(message.get("body", b""))
        if received > maximum:
            oversized = True
            # Multipart's own error path closes every partially spooled file.
            raise MultiPartException("Attachment upload exceeds its configured limit.")
        return message

    bounded = Request(request.scope, receive=receive)
    if not request.headers.get("content-type", "").startswith("multipart/form-data"):
        raise Problem(422, "validation_failed", "Upload one file as multipart/form-data.")
    try:
        async with bounded.form(max_files=1, max_fields=0, max_part_size=file_maximum) as form:
            file = form.get("file")
            if not isinstance(file, UploadFile) or len(form.multi_items()) != 1:
                raise Problem(422, "validation_failed", "Upload exactly one file.")
            data = await file.read(file_maximum + 1)
            if not data:
                raise Problem(422, "validation_failed", "Choose a non-empty file.")
            if len(data) > file_maximum:
                raise Problem(413, "attachment_too_large", detail)
            return await write_attachment(
                services.store,
                services.settings,
                data,
                filename=file.filename,
                mime=file.content_type,
            )
    except HTTPException:
        if oversized:
            raise Problem(413, "attachment_too_large", detail) from None
        raise
    except MultiPartException, ValueError:
        if oversized:
            raise Problem(413, "attachment_too_large", detail) from None
        raise Problem(422, "validation_failed", "The attachment upload is invalid.") from None
