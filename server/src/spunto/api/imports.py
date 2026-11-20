"""Owner-session export upload for the enabled note importer."""

import uuid

from fastapi import APIRouter, Request, Response
from starlette.datastructures import UploadFile
from starlette.formparsers import MultiPartException

from spunto.api.deps import ServicesDep, SessionDep
from spunto.api.errors import Problem
from spunto.api.plugins import _call, _host, _localized
from spunto.auth.tokens import Principal
from spunto.host import Ctx
from spunto.host.language import tr
from spunto.plugins.markdown_import import (
    MAX_ARCHIVE_BYTES,
    ImportFormat,
    read_upload,
    run_import,
    staging_lock,
)

router = APIRouter(tags=["Imports"])


@router.post("/api/v1/plugins/markdown-import/upload")
async def upload(
    request: Request,
    response: Response,
    session: SessionDep,
    services: ServicesDep,
    source_format: ImportFormat = "markdown",
):
    host = _host(services, "markdown-import", enabled=True)
    with _localized(request, response, host, "markdown-import"):
        async with staging_lock(host):
            async with services.store.read() as conn:
                busy = await conn.execute_fetchall(
                    "SELECT 1 FROM job WHERE plugin_id='markdown-import' AND kind='job:import' "
                    "AND status IN ('queued','running') LIMIT 1"
                )
            if busy:
                raise Problem(
                    409, "import_in_progress", tr("Wait for the current import to finish.")
                )
            return await _upload(request, session, services, source_format)


async def _upload(request: Request, session: str, services, source_format: ImportFormat):
    host = _host(services, "markdown-import", enabled=True)
    ctx = Ctx(
        host,
        "markdown-import",
        principal=Principal(session, "session", "Owner", "full", private_ok=True),
    )
    ctx.check("routes")
    maximum = MAX_ARCHIVE_BYTES + 65536
    size_error = tr("Choose an import file of at most 20 MB.")
    length = request.headers.get("content-length")
    if length and (not length.isdecimal() or len(length) > 8 or int(length) > maximum):
        raise Problem(413, "import_too_large", size_error)
    if not request.headers.get("content-type", "").startswith("multipart/form-data"):
        raise Problem(422, "validation_failed", tr("Upload exactly one export file."))
    received = 0

    async def receive():
        nonlocal received
        message = await request.receive()
        received += len(message.get("body", b""))
        if received > maximum:
            raise Problem(413, "import_too_large", size_error)
        return message

    bounded = Request(request.scope, receive=receive)
    try:
        async with bounded.form(max_files=1, max_fields=0, max_part_size=MAX_ARCHIVE_BYTES) as form:
            file = form.get("file")
            if not isinstance(file, UploadFile) or len(form.multi_items()) != 1:
                raise Problem(422, "validation_failed", tr("Upload exactly one export file."))
            if source_format == "markdown" and (file.filename or "").lower().endswith(".enex"):
                source_format = "evernote"
            data = await file.read(MAX_ARCHIVE_BYTES + 1)
            if len(data) > MAX_ARCHIVE_BYTES:
                raise Problem(413, "import_too_large", size_error)
    except MultiPartException, ValueError:
        raise Problem(422, "validation_failed", tr("The export upload is invalid.")) from None
    source = await _call(_read_source(ctx, data, source_format))
    report = await _call(run_import(ctx, source, dry_run=True))
    archive_id = str(uuid.uuid7())
    directory = services.settings.data_dir / "imports"
    await ctx.to_thread(directory.mkdir, parents=True, exist_ok=True)
    suffix = ".enex" if source_format == "evernote" else ".zip"
    path = directory / (archive_id + suffix)
    # An active import already holds an immutable snapshot before another upload.
    # Bound staging to one archive; replacement invalidates the old preview.
    for prior in await ctx.to_thread(
        lambda: [p for p in directory.iterdir() if p.suffix in {".zip", ".enex"}]
    ):
        await ctx.to_thread(prior.unlink, missing_ok=True)
        await ctx.storage.records.delete("uploads", prior.stem)
    await ctx.to_thread(path.write_bytes, data)
    await ctx.storage.records.put(
        "uploads",
        archive_id,
        {
            "created": ctx.clock(),
            "digest": source.digest,
            "format": source_format,
            "previewed": True,
        },
    )
    return {"archive_id": archive_id, "report": report}


async def _read_source(ctx, data, source_format):
    try:
        return await ctx.to_thread(read_upload, data, source_format)
    except ValueError:
        if source_format == "evernote":
            detail = tr("Choose a valid Evernote ENEX file under 20 MB.")
        elif source_format == "notion":
            detail = tr(
                "Choose a Notion ZIP containing Markdown pages. Unzip any outer archive first."
            )
        else:
            detail = tr(
                "Choose a valid ZIP containing Markdown files and their linked attachments."
            )
        raise Problem(422, "invalid_import_file", detail) from None
