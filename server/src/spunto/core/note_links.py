"""Parse note links and append them using the current notes' visibility and titles."""

import re
import uuid

from markdown_it import MarkdownIt

from spunto.api.errors import Problem
from spunto.core.notes import Notes, _body, _id
from spunto.host.plugin import ContextError
from spunto.store.db import now_ms

_MARKDOWN = MarkdownIt("commonmark", {"html": False})
_LINK = re.compile(r"(?:/n/|note://)([0-9a-fA-F-]{36})")
_PROPOSAL = re.compile(r"\[(?:\\[^\r\n]|[^\\\[\]\r\n])+\]\(([^()\r\n]+)\)")


def _target(href):
    match = _LINK.fullmatch(href or "")
    if match:
        try:
            parsed = uuid.UUID(match[1])
            if parsed.version == 7:
                return str(parsed)
        except ValueError:
            pass
    return None


def targets(body):
    result = {}
    for token in _MARKDOWN.parse(body):
        for child in token.children or []:
            if child.type == "link_open" and (note_id := _target(child.attrGet("href"))):
                result[note_id] = None
    return list(result)


def proposal_target(payload):
    # Existing rows encode the target in a single Markdown link. Discard the stored caption.
    text = payload.get("text") if isinstance(payload, dict) else None
    match = _PROPOSAL.fullmatch(text) if isinstance(text, str) else None
    return _target(match[1]) if match else None


def link_text(target_id, caption="Linked note"):
    escaped = re.sub(r"([\\`*_{}\[\]()#+.!<>~&-])", r"\\\1", caption)
    return f"[{escaped}](/n/{target_id})"


def visible_link(target):
    title = ""
    if not target["private"]:
        title = re.sub(r"\s+", " ", target["title"] or "").strip()[:100]
    return link_text(target["id"], title or "Linked note")


def append_link(source, target):
    body = source["body"] + "\n\n" + visible_link(target)
    _body(body)
    if target["id"] not in targets(body):
        raise Problem(
            422, "invalid_markdown", "Close this note's Markdown before inserting a link."
        )
    return body


async def insert_link(ctx, note_id, target_id, *, base_version, target_version):
    # A bundled command with an explicit owner action can make this one narrow edit.
    if (
        ctx.plugin_id != "links"
        or ctx.operation != "command:link"
        or ctx.principal is None
        or not ctx.principal.owner
    ):
        raise ContextError("owner_required")
    ctx.check_write()
    store = ctx._host.store
    domain = Notes(store, ctx.principal)
    now = now_ms()
    source_id, destination_id = _id(note_id, now), _id(target_id, now)
    if source_id == destination_id:
        raise Problem(422, "validation_failed", "Choose another note.")
    async with store.write() as conn:
        ctx.check("notes.read")
        ctx.check("suggest")
        source = await domain._row(conn, source_id)
        target = await domain._row(conn, destination_id)
        if destination_id in targets(source["body"]):
            return False
        if source["version"] != base_version:
            raise Problem(409, "version_conflict", "This note changed. Reopen Link to note.")
        if target["version"] != target_version:
            raise Problem(
                409, "version_conflict", "That note changed. Search again before linking it."
            )
        await domain._update(
            conn, source, append_link(source, target), "suggestion", coalesce=False
        )
        ctx.check("suggest")
    return True
