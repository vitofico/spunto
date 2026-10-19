"""Bounded owner imports; archives are read in memory, never extracted to disk."""

import asyncio
import hashlib
import io
import json
import mimetypes
import posixpath
import re
import stat
import uuid
import weakref
import zipfile
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlsplit

from markdown_it import MarkdownIt

from spunto.api.notes import Source
from spunto.core.export import _rewrite_links
from spunto.core.text import label_key, labels
from spunto.host import Ctx, Plugin
from spunto.host.plugin import ContextError

plugin = Plugin("markdown-import")
STAGING_LOCKS = weakref.WeakKeyDictionary()


def staging_lock(host):
    return STAGING_LOCKS.setdefault(host, asyncio.Lock())


MAX_ARCHIVE_BYTES = 20_000_000
MAX_EXPANDED_BYTES = 64_000_000
MAX_MEMBERS = 20_000
MAX_NOTES = 10_000
MAX_ATTACHMENT_BYTES = 5_000_000
EXPORT_FIELDS = {
    "id",
    "created",
    "updated",
    "labels",
    "pinned",
    "archived",
    "trashed",
    "private",
    "source",
    "origin",
    "machine",
    "conflict_of",
}


@dataclass
class Bundle:
    files: dict[str, bytes]
    digest: str


def member_name(name: str) -> str:
    """A portable relative path; reject aliases and platform-specific escapes."""
    parts = PurePosixPath(name).parts
    if (
        not name
        or "\\" in name
        or "\x00" in name
        or ":" in name
        or name.startswith("/")
        or any(part in {".", ".."} for part in parts)
        or str(PurePosixPath(name)) != name.rstrip("/")
    ):
        raise ValueError("The archive contains an unsafe path")
    return name.rstrip("/")


def bundle(files: dict[str, bytes]) -> Bundle:
    if len(files) > MAX_MEMBERS or sum(map(len, files.values())) > MAX_EXPANDED_BYTES:
        raise ValueError("The import exceeds 20,000 files or 64 MB expanded")
    notes = [name for name in files if name.lower().endswith(".md")]
    if not notes or len(notes) > MAX_NOTES:
        raise ValueError("Choose an import with 1 to 10,000 Markdown notes")
    digest = hashlib.sha256()
    for name, data in sorted(files.items()):
        digest.update(name.encode())
        digest.update(b"\0")
        digest.update(hashlib.sha256(data).digest())
    return Bundle(files, digest.hexdigest())


def read_zip(data: bytes) -> Bundle:
    if not isinstance(data, bytes) or not data or len(data) > MAX_ARCHIVE_BYTES:
        raise ValueError("Choose a ZIP of at most 20 MB")
    files = {}
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            infos = archive.infolist()
            if len(infos) > MAX_MEMBERS:
                raise ValueError("The archive contains more than 20,000 entries")
            expanded = 0
            for info in infos:
                name = member_name(info.filename)
                mode = info.external_attr >> 16
                if info.flag_bits & 1 or stat.S_ISLNK(mode):
                    raise ValueError("Encrypted files and symbolic links are not supported")
                if info.is_dir():
                    continue
                if stat.S_IFMT(mode) not in {0, stat.S_IFREG}:
                    raise ValueError("The archive contains a special file")
                if name in files:
                    raise ValueError("The archive contains duplicate paths")
                expanded += info.file_size
                if expanded > MAX_EXPANDED_BYTES:
                    raise ValueError("The archive exceeds 64 MB expanded")
                maximum = 400_000 if name.lower().endswith(".md") else MAX_ATTACHMENT_BYTES
                if info.file_size > maximum:
                    raise ValueError("A note or attachment exceeds its import limit")
                with archive.open(info) as stream:
                    content = stream.read(maximum + 1)
                if len(content) > maximum or len(content) != info.file_size:
                    raise ValueError("The archive has an invalid file size")
                files[name] = content
    except zipfile.BadZipFile, RuntimeError, NotImplementedError:
        raise ValueError("Choose a valid, unencrypted ZIP archive") from None
    return bundle(files)


def read_path(path: Path) -> Bundle:
    """Only the operator CLI supplies a path. No API accepts a filesystem path."""
    if path.is_symlink():
        raise ValueError("Choose a regular ZIP or folder, not a symbolic link")
    if path.is_file():
        if path.stat().st_size > MAX_ARCHIVE_BYTES:
            raise ValueError("Choose a ZIP of at most 20 MB")
        return read_zip(path.read_bytes())
    if not path.is_dir():
        raise ValueError("Choose an existing ZIP or Markdown folder")
    files = {}
    expanded = 0
    for entry in path.rglob("*"):
        relative = entry.relative_to(path)
        if any(part.startswith(".") for part in relative.parts):
            continue
        if entry.is_symlink():
            raise ValueError("The folder contains a symbolic link")
        if entry.is_dir():
            continue
        if not entry.is_file():
            raise ValueError("The folder contains a special file")
        name = member_name(relative.as_posix())
        maximum = 400_000 if name.lower().endswith(".md") else MAX_ATTACHMENT_BYTES
        size = entry.stat().st_size
        expanded += size
        if size > maximum or expanded > MAX_EXPANDED_BYTES or len(files) >= MAX_MEMBERS:
            raise ValueError("The folder exceeds its file or expanded-size limit")
        with entry.open("rb") as stream:
            content = stream.read(maximum + 1)
        if len(content) > maximum:
            raise ValueError("A file changed while reading the import")
        files[name] = content
    return bundle(files)


def scalar(value: str):
    """JSON export values plus the safe scalar/list subset used by common vaults."""
    value = value.strip()
    try:
        return json.loads(value)
    except ValueError:
        if value.startswith("[") and value.endswith("]"):
            return [scalar(part) for part in value[1:-1].split(",") if part.strip()]
        if value.startswith("'") and value.endswith("'"):
            return value[1:-1].replace("''", "'")
        return value


def frontmatter(document: str) -> tuple[dict, str]:
    if not document.startswith("---\n"):
        return {}, document
    end = document.find("\n---\n", 4)
    if end < 0 or end > 16_000:
        raise ValueError("A note has invalid or oversized front matter")
    metadata = {}
    last = None
    for line in document[4:end].splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line.startswith(("  - ", "- ")) and last:
            values = metadata[last]
            if values == "":
                values = metadata[last] = []
            if isinstance(values, list):
                values.append(scalar(line.strip()[2:]))
            continue
        if ":" not in line or line.startswith((" ", "\t")):
            continue  # Unsupported YAML remains a best-effort metadata import.
        key, value = line.split(":", 1)
        if key in metadata:
            raise ValueError("A note has duplicate front-matter fields")
        metadata[key] = scalar(value)
        last = key
    body = document[end + 5 :]
    # The exporter emits one blank separator before the unchanged body.
    if EXPORT_FIELDS <= metadata.keys() and body.startswith("\n"):
        body = body[1:]
    return metadata, body


def milliseconds(value, fallback: int) -> int:
    if value is None:
        return fallback
    if type(value) is int:
        return value * 1000 if value < 100_000_000_000 else value
    if not isinstance(value, str):
        raise ValueError("A note has an invalid timestamp")
    try:
        moment = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if moment.tzinfo is None:
            moment = moment.replace(tzinfo=UTC)
        return int(moment.timestamp() * 1000)
    except ValueError, OverflowError:
        raise ValueError("A note has an invalid timestamp") from None


def destinations(body: str) -> set[str]:
    found = set()
    for token in MarkdownIt().parse(body):
        for child in token.children or []:
            if child.type == "image":
                found.add(child.attrGet("src"))
            elif child.type == "link_open":
                found.add(child.attrGet("href"))
    return found


def local_target(note_path: str, url: str) -> str | None:
    parsed = urlsplit(url)
    if parsed.scheme or parsed.netloc or not parsed.path or parsed.path.startswith("/"):
        return None
    path = unquote(parsed.path)
    if "\\" in path or "\x00" in path:
        raise ValueError("A note has an unsafe attachment link")
    target = posixpath.normpath(posixpath.join(posixpath.dirname(note_path), path))
    if target.startswith("../") or target in {"..", "."} or target.startswith("/"):
        raise ValueError("A note link escapes the import folder")
    return target


def prepare(name: str, data: bytes, now: int) -> dict:
    try:
        document = data.decode("utf-8-sig")
    except UnicodeDecodeError:
        raise ValueError("Markdown notes must use UTF-8") from None
    if document.startswith("---\r\n"):
        document = document.replace("\r\n", "\n")
    metadata, body = frontmatter(document)
    own = EXPORT_FIELDS <= metadata.keys()
    created = milliseconds(
        metadata.get("created", metadata.get("created_at", metadata.get("date"))), now
    )
    updated = milliseconds(metadata.get("updated", metadata.get("updated_at")), created)
    if any(not 0 <= timestamp <= now + 86_400_000 for timestamp in (created, updated)):
        raise ValueError("A note has an invalid or future timestamp")
    if own:
        if set(metadata) != EXPORT_FIELDS:
            raise ValueError("The Spunto export has unknown metadata fields")
        try:
            identifier = uuid.UUID(metadata["id"])
            if identifier.version != 7:
                raise ValueError
        except ValueError, TypeError, AttributeError:
            raise ValueError("The Spunto export has an invalid note ID") from None
        if any(
            type(metadata[key]) is not bool
            for key in ("pinned", "archived", "trashed", "private", "machine")
        ):
            raise ValueError("The Spunto export has invalid flags")
        expected = {item.key for item in labels(body)}
        if (
            not isinstance(metadata["labels"], list)
            or any(not isinstance(item, str) for item in metadata["labels"])
            or {label_key(item) for item in metadata["labels"]} != expected
        ):
            raise ValueError("The Spunto export labels do not match its body")
        Source.model_validate(metadata["source"])
        if metadata["origin"] not in {"owner", "agent", "external", "plugin"}:
            raise ValueError("The Spunto export has invalid provenance")
        if metadata["conflict_of"] is not None:
            try:
                if uuid.UUID(metadata["conflict_of"]).version != 7:
                    raise ValueError
            except ValueError, TypeError, AttributeError:
                raise ValueError("The Spunto export has an invalid conflict reference") from None
        key = "spunto:" + str(identifier)
        restored = {key: metadata[key] for key in ("source", "origin", "machine", "conflict_of")}
        restored["labels"] = metadata["labels"]
    else:
        identifier = None
        key = "file:" + name
        restored = None
        tags = metadata.get("tags", metadata.get("labels", []))
        if isinstance(tags, str):
            tags = re.split(r"[,\s]+", tags)
        if not isinstance(tags, list) or len(tags) > 500:
            raise ValueError("A note has invalid front-matter tags")
        existing = {item.key for item in labels(body)}
        appended = []
        for tag in tags:
            if not isinstance(tag, str):
                continue
            tag = tag.removeprefix("#")
            recognized = labels("#" + tag)
            if (
                len(recognized) == 1
                and recognized[0].path == tag
                and recognized[0].key not in existing
            ):
                appended.append("#" + tag)
                existing.add(recognized[0].key)
        if appended:
            body += "\n\n" + " ".join(appended)
    if len(body) > 100_000:
        raise ValueError("A note exceeds 100,000 characters")
    return {
        "name": name,
        "body": body,
        "created_at": created,
        "updated_at": updated,
        "id": str(identifier) if identifier else None,
        "import_key": key,
        "pinned": metadata.get("pinned") is True,
        "archived": metadata.get("archived") is True,
        "trashed": metadata.get("trashed") is True,
        "private": metadata.get("private") is True,
        "import_metadata": restored,
    }


async def run_import(ctx: Ctx, source: Bundle, *, dry_run: bool = True) -> dict:
    if ctx.principal is not None and not ctx.principal.owner or ctx.unattended:
        raise ContextError("owner_import_required")
    now = ctx.clock()
    prepared = await ctx.to_thread(
        lambda: [
            prepare(name, data, now)
            for name, data in sorted(source.files.items())
            if name.lower().endswith(".md")
        ]
    )
    keys = [note["import_key"] for note in prepared]
    if len(keys) != len(set(keys)):
        raise ValueError("The import contains duplicate note IDs")
    report = {
        "notes": len(prepared),
        "attachments": 0,
        "attachment_bytes": 0,
        "created": 0,
        "updated": 0,
        "unchanged": 0,
        "skipped_owner_edits": 0,
        "missing_links": 0,
        "failed_notes": 0,
        "digest": source.digest,
    }
    plans = []
    note_ids = {}
    for note in prepared:
        old = await ctx.notes.lookup_import(note["import_key"])
        state = await ctx.storage.records.get("imports", note["import_key"])
        digest = hashlib.sha256(source.files[note["name"]]).hexdigest()
        if not note["id"]:
            created = note["created_at"]
            if not 0 <= created < 2**48:
                raise ValueError("A note has an invalid timestamp")
            note["id"] = (
                old["id"]
                if old
                else str(uuid.UUID(int=(created << 80) | (uuid.uuid7().int & ((1 << 80) - 1))))
            )
        note_ids[note["name"]] = note["id"]
        targets = {}
        for url in await ctx.to_thread(destinations, note["body"]):
            target = local_target(note["name"], url)
            if target is None:
                continue
            if target not in source.files:
                report["missing_links"] += 1
                continue
            targets[url] = target
        fingerprint = hashlib.sha256()
        fingerprint.update(source.files[note["name"]])
        for target in sorted(set(targets.values())):
            fingerprint.update(target.encode())
            fingerprint.update(hashlib.sha256(source.files[target]).digest())
        digest = fingerprint.hexdigest()
        plans.append((note, old, state, digest, targets))
    attachment_names = {
        target for *_, targets in plans for target in targets.values() if target not in note_ids
    }
    if any(len(PurePosixPath(name).name) > 255 for name in attachment_names):
        raise ValueError("An attachment filename exceeds 255 characters")
    maximum = min(MAX_ATTACHMENT_BYTES, ctx._host.settings.max_upload_bytes)
    if any(len(source.files[name]) > maximum for name in attachment_names):
        raise ValueError("An attachment exceeds the configured upload limit")
    report["attachments"] = len(attachment_names)
    report["attachment_bytes"] = sum(len(source.files[name]) for name in attachment_names)
    for note, old, state, digest, targets in plans:
        if dry_run:
            continue
        if state and (old is None or old["version"] != state["version"]):
            report["skipped_owner_edits"] += 1
            continue
        if old and state and state["digest"] == digest:
            report["unchanged"] += 1
            continue
        # Imported notes updated by the owner are never replaced on a later import.
        replacements = {}
        for url, target in targets.items():
            if target in note_ids:
                replacements[url] = "/n/" + note_ids[target]
            else:
                attached = await ctx.attachments.write(
                    source.files[target],
                    filename=PurePosixPath(target).name,
                    mime=mimetypes.guess_type(target)[0],
                )
                replacements[url] = attached["url"]
        body = _rewrite_links(note["body"], replacements)
        if old:
            saved = await ctx.notes.update_own(
                old["id"],
                body,
                updated_at=note["updated_at"],
                pinned=note["pinned"],
                archived=note["archived"],
                trashed=note["trashed"],
                private=note["private"] if note["import_metadata"] is not None else None,
                import_metadata=note["import_metadata"],
            )
            if saved is None:
                report["skipped_owner_edits"] += 1
                continue
            report["updated"] += 1
        else:
            saved = await ctx.notes.create(
                **{key: value for key, value in note.items() if key != "name"} | {"body": body}
            )
            report["created"] += 1
        await ctx.storage.records.put(
            "imports",
            note["import_key"],
            {"digest": digest, "version": saved["version"]},
            note_id=saved["id"],
            key=note["import_key"],
        )
    await ctx.storage.kv.set("last_report", report)
    return report


def report_output(report: dict) -> list[dict]:
    return [
        {
            "type": "text",
            "text": (
                f"{report['notes']} notes, {report['attachments']} attachments "
                f"({report['attachment_bytes']} bytes). "
                f"Created {report['created']}, updated {report['updated']}, "
                f"unchanged {report['unchanged']}; "
                f"skipped {report['skipped_owner_edits']} owner-edited notes. "
                f"{report['missing_links']} missing local links."
            ),
        }
    ]


@plugin.route("report")
async def report(ctx: Ctx):
    stored = await ctx.storage.kv.get("last_report")
    return (
        report_output(stored)
        if stored
        else [{"type": "text", "text": "Upload a ZIP to preview the import first."}]
    )


async def staged(ctx: Ctx, archive_id: str) -> tuple[Bundle, dict, Path]:
    async with staging_lock(ctx._host):
        return await _staged(ctx, archive_id)


async def _staged(ctx: Ctx, archive_id: str) -> tuple[Bundle, dict, Path]:
    try:
        if str(uuid.UUID(archive_id)) != archive_id:
            raise ValueError
    except ValueError, TypeError, AttributeError:
        raise ValueError("Upload a ZIP to preview the import first") from None
    metadata = await ctx.storage.records.get("uploads", archive_id)
    # The durable job is the consent receipt even if a process failed between
    # enqueue's commit and the optional preview-record update.
    ctx.check()
    async with ctx._host.store.read() as conn:
        jobs = await conn.execute_fetchall(
            "SELECT id FROM job WHERE plugin_id=? AND kind='job:import' "
            "AND status IN ('queued','running') AND json_extract(args,'$.archive_id')=?",
            (ctx.plugin_id, archive_id),
        )
    ctx.check()
    if metadata and jobs:
        metadata = {**metadata, "queued": True, "job_id": jobs[0]["id"]}
    if not metadata or (
        not metadata.get("queued") and ctx.clock() - metadata["created"] > 86_400_000
    ):
        raise ValueError("The import preview expired; upload the ZIP again")
    path = ctx._host.settings.data_dir / "imports" / (archive_id + ".zip")
    try:
        source = await ctx.to_thread(lambda: read_zip(path.read_bytes()))
    except OSError:
        raise ValueError("Upload the ZIP again; its staged file is unavailable") from None
    if source.digest != metadata["digest"]:
        raise ValueError("The staged import changed; upload the ZIP again")
    return source, metadata, path


@plugin.job("import")
async def import_job(ctx: Ctx, archive_id: str) -> None:
    source, metadata, path = await staged(ctx, archive_id)
    if not metadata.get("previewed"):
        raise ValueError("Preview the ZIP before importing")
    await run_import(ctx, source, dry_run=False)
    await ctx.to_thread(path.unlink, missing_ok=True)
    await ctx.storage.records.delete("uploads", archive_id)


@plugin.route("import", method="POST", write=True)
async def import_upload(ctx: Ctx, archive_id: str) -> list[dict]:
    async with staging_lock(ctx._host):
        _, metadata, _ = await _staged(ctx, archive_id)
        if not metadata.get("previewed"):
            raise ValueError("Preview the ZIP before importing")
        if metadata.get("queued"):
            return [{"type": "text", "text": "This import is already queued."}]
        job_id = await ctx.enqueue("import", {"archive_id": archive_id})
        await ctx.storage.records.put(
            "uploads", archive_id, {**metadata, "queued": True, "job_id": job_id}
        )
    await ctx.storage.kv.set("last_job", job_id)
    return [{"type": "text", "text": "Import queued. The report updates when it finishes."}]
