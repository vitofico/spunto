"""Content-addressed attachment storage shared by the host and authenticated API."""

import asyncio
import hashlib
import os
import re
import tempfile
import uuid
from pathlib import Path

from spunto.api.errors import Problem
from spunto.host.plugin import ContextError
from spunto.store.db import now_ms


def sniff(data: bytes, supplied: str | None) -> str:
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return "image/webp"
    # Untrusted HTML/SVG and claimed image types are always downloaded.
    if (
        supplied
        and re.fullmatch(r"[a-z0-9.+-]+/[a-z0-9.+-]+", supplied)
        and not supplied.startswith("image/")
    ):
        return supplied
    return "application/octet-stream"


def path_for(directory: Path, digest: str) -> Path:
    return directory / digest[:2] / digest[2:4] / digest


def _save(directory, digest, data):
    target = path_for(directory, digest)
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
            raise OSError("attachment_digest_mismatch")
        return
    fd, name = tempfile.mkstemp(prefix=".upload-", dir=target.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, target)
        directory_fd = os.open(target.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if os.path.exists(name):
            os.unlink(name)


async def write_attachment(
    store,
    settings,
    data,
    *,
    filename=None,
    mime=None,
    check=lambda: None,
    clock=now_ms,
    to_thread=asyncio.to_thread,
):
    check()
    if not isinstance(data, bytes) or len(data) > settings.max_upload_bytes:
        raise ValueError(f"attachment must be bytes of at most {settings.max_upload_mb} MB")
    if filename is not None and (not isinstance(filename, str) or len(filename) > 255):
        raise ValueError("invalid filename")
    filename = Path(filename).name if filename else "attachment"
    filename = re.sub(r"[\x00-\x1f\x7f]", "", filename)
    digest = hashlib.sha256(data).hexdigest()
    media = sniff(data, mime)
    await to_thread(_save, settings.attachments_dir, digest, data)
    check()
    async with store.write() as conn:
        check()
        rows = await conn.execute_fetchall(
            "SELECT * FROM attachment WHERE sha256=? LIMIT 1", (digest,)
        )
        if rows:
            result = dict(rows[0])
        else:
            result = {
                "id": str(uuid.uuid7()),
                "sha256": digest,
                "mime": media,
                "size": len(data),
                "filename": filename,
                "width": None,
                "height": None,
                "created_at": clock(),
            }
            await conn.execute(
                "INSERT INTO attachment VALUES (?,?,?,?,?,?,?,?)", tuple(result.values())
            )
            store.emit("attachment.added")
        check()
    check()
    url = "/a/" + result["id"]
    label = re.sub(r"[\[\]\\()<>]", "", filename)
    result.update(
        url=url, snippet=("!" if result["mime"].startswith("image/") else "") + f"[{label}]({url})"
    )
    return result


class Attachments:
    def __init__(self, ctx):
        self.ctx, self.store = ctx, ctx._host.store
        self.directory = ctx._host.settings.attachments_dir

    async def write(self, data, *, filename=None, mime=None):
        if self.ctx.principal is not None and not self.ctx.principal.writable:
            raise ContextError("read_only_token")
        return await write_attachment(
            self.store,
            self.ctx._host.settings,
            data,
            filename=filename,
            mime=mime,
            check=lambda: self.ctx.check("attachments.write"),
            clock=self.ctx.clock,
            to_thread=self.ctx.to_thread,
        )

    async def list(self, note_id, *, limit=50):
        self.ctx.check("attachments.read")
        if type(limit) is not int or not 1 <= limit <= 100:
            raise ValueError("attachment limit must be 1 to 100")
        async with self.store.read() as conn:
            note = await self._eligible_note(conn, note_id)
            if note is None:
                return []
            rows = await conn.execute_fetchall(
                "SELECT a.* FROM attachment a JOIN note_attachment na ON na.attachment_id=a.id "
                "WHERE na.note_id=? ORDER BY a.created_at,a.id LIMIT ?",
                (note_id, limit),
            )
        self.ctx.check("attachments.read")
        return [{**dict(row), "note_id": note_id, "note_version": note["version"]} for row in rows]

    async def _eligible_note(self, conn, note_id, note_version=None):
        from spunto.core.plugin_notes import PluginNotes

        core = PluginNotes(self.ctx).core
        try:
            note = await core._row(conn, note_id)
        except Problem as exc:
            if exc.status == 404:
                return None
            raise
        bound = getattr(self.ctx, "capture_note_id", None)
        if bound is not None and note_id != bound:
            return None
        if (
            note["private"]
            or note["machine"]
            or note_version is not None
            and note["version"] != note_version
        ):
            return None
        operation = getattr(self.ctx, "operation", None) or ""
        if operation.startswith(("hook:", "job:")) and self.ctx.unattended:
            actor = await conn.execute_fetchall(
                "SELECT kind FROM credential WHERE id=?", (note["updated_by"],)
            )
            if note["origin"] != "owner" or actor and actor[0][0] == "pat":
                return None
        return note

    async def info(self, attachment_id, *, note_id=None, note_version=None):
        self.ctx.check("attachments.read")
        note_id = note_id or getattr(self.ctx, "capture_note_id", None)
        async with self.store.read() as conn:
            links = await conn.execute_fetchall(
                "SELECT note_id FROM note_attachment WHERE attachment_id=? "
                "AND (? IS NULL OR note_id=?) ORDER BY note_id LIMIT 100",
                (attachment_id, note_id, note_id),
            )
            for link in links:
                note = await self._eligible_note(conn, link[0], note_version)
                if note is not None:
                    rows = await conn.execute_fetchall(
                        "SELECT * FROM attachment WHERE id=?", (attachment_id,)
                    )
                    if rows and re.fullmatch(r"[a-f0-9]{64}", rows[0]["sha256"]):
                        self.ctx.check("attachments.read")
                        return {
                            **dict(rows[0]),
                            "note_id": note["id"],
                            "note_version": note["version"],
                        }
        self.ctx.check("attachments.read")
        return None

    async def read(self, attachment_id, *, note_id=None, note_version=None):
        self.ctx.check("attachments.read")
        metadata = await self.info(attachment_id, note_id=note_id, note_version=note_version)
        if metadata is None:
            raise Problem(404, "not_found", "That attachment is unavailable.")
        try:
            data = await self.ctx.to_thread(path_for(self.directory, metadata["sha256"]).read_bytes)
        except OSError:
            raise ContextError("attachment_unavailable") from None
        self.ctx.check("attachments.read")
        current = await self.info(
            attachment_id, note_id=metadata["note_id"], note_version=metadata["note_version"]
        )
        if (
            current is None
            or current["sha256"] != metadata["sha256"]
            or len(data) != metadata["size"]
            or hashlib.sha256(data).hexdigest() != metadata["sha256"]
        ):
            raise ContextError("attachment_source_changed")
        return data
