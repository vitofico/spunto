"""Owner-session ZIP upload for the enabled Markdown importer."""

import uuid

from fastapi import APIRouter, Request
from starlette.datastructures import UploadFile
from starlette.formparsers import MultiPartException

from spunto.api.deps import ServicesDep, SessionDep
from spunto.api.errors import Problem
from spunto.api.plugins import _call, _host
from spunto.auth.tokens import Principal
from spunto.host import Ctx
from spunto.plugins.markdown_import import MAX_ARCHIVE_BYTES, read_zip, run_import, staging_lock

router = APIRouter(tags=["Imports"])


@router.post("/api/v1/plugins/markdown-import/upload")
async def upload(request: Request, session: SessionDep, services: ServicesDep):
    host = _host(services, "markdown-import", enabled=True)
    async with staging_lock(host):
        async with services.store.read() as conn:
            busy = await conn.execute_fetchall(
                "SELECT 1 FROM job WHERE plugin_id='markdown-import' AND kind='job:import' "
                "AND status IN ('queued','running') LIMIT 1"
            )
        if busy:
            raise Problem(409, "import_in_progress", "Wait for the current import to finish.")
        return await _upload(request, session, services)


async def _upload(request: Request, session: str, services):
    host = _host(services, "markdown-import", enabled=True)
    ctx = Ctx(
        host,
        "markdown-import",
        principal=Principal(session, "session", "Owner", "full", private_ok=True),
    )
    ctx.check("routes")
    maximum = MAX_ARCHIVE_BYTES + 65536
    length = request.headers.get("content-length")
    if length and (not length.isdecimal() or int(length) > maximum):
        raise Problem(413, "import_too_large", "Choose a ZIP of at most 20 MB.")
    if not request.headers.get("content-type", "").startswith("multipart/form-data"):
        raise Problem(422, "validation_failed", "Upload one ZIP as multipart/form-data.")
    received = 0

    async def receive():
        nonlocal received
        message = await request.receive()
        received += len(message.get("body", b""))
        if received > maximum:
            raise Problem(413, "import_too_large", "Choose a ZIP of at most 20 MB.")
        return message

    bounded = Request(request.scope, receive=receive)
    try:
        async with bounded.form(max_files=1, max_fields=0, max_part_size=MAX_ARCHIVE_BYTES) as form:
            file = form.get("file")
            if not isinstance(file, UploadFile) or len(form.multi_items()) != 1:
                raise Problem(422, "validation_failed", "Upload exactly one ZIP.")
            data = await file.read(MAX_ARCHIVE_BYTES + 1)
            if len(data) > MAX_ARCHIVE_BYTES:
                raise Problem(413, "import_too_large", "Choose a ZIP of at most 20 MB.")
    except MultiPartException, ValueError:
        raise Problem(422, "validation_failed", "The ZIP upload is invalid.") from None
    source = await _call(ctx.to_thread(read_zip, data))
    report = await _call(run_import(ctx, source, dry_run=True))
    archive_id = str(uuid.uuid7())
    directory = services.settings.data_dir / "imports"
    await ctx.to_thread(directory.mkdir, parents=True, exist_ok=True)
    path = directory / (archive_id + ".zip")
    # An active import already holds an immutable snapshot before another upload.
    # Bound staging to one archive; replacement invalidates the old preview.
    for prior in await ctx.to_thread(lambda: list(directory.glob("*.zip"))):
        await ctx.to_thread(prior.unlink, missing_ok=True)
        await ctx.storage.records.delete("uploads", prior.stem)
    await ctx.to_thread(path.write_bytes, data)
    await ctx.storage.records.put(
        "uploads", archive_id, {"created": ctx.clock(), "digest": source.digest, "previewed": True}
    )
    return {"archive_id": archive_id, "report": report}
