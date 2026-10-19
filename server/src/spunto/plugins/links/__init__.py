"""Stored backlinks including explicit machine follow-ups, without AI or network."""

import re
import uuid

from markdown_it import MarkdownIt

from spunto.host import Ctx, Plugin
from spunto.plugins._wave2 import text

plugin = Plugin("links")


def targets(body):
    result = []
    parser = MarkdownIt("commonmark", {"html": False})
    for token in parser.parse(body):
        for child in token.children or []:
            if child.type != "link_open":
                continue
            match = re.fullmatch(r"(?:/n/|note://)([0-9a-fA-F-]{36})", child.attrGet("href") or "")
            if not match:
                continue
            try:
                note_id = str(uuid.UUID(match[1]))
                if uuid.UUID(note_id).version == 7 and note_id not in result:
                    result.append(note_id)
            except ValueError:
                continue
    return result[:50]


async def refresh(ctx, target_id):
    target = await ctx.notes.get(target_id)
    if target is None:
        return
    records = await ctx.storage.records.list("edges", key=target_id, limit=1000)
    refs = []
    for record in records:
        source = await ctx.notes.get(record["note_id"])
        if source and target_id in targets(source["body"]):
            # Public panels cannot disclose that a private source exists.
            if source["private"] and not target["private"]:
                continue
            refs.append(source["id"])
            if len(refs) == 10:
                break
    await ctx.notes.annotate(
        target_id, "panel", {"type": "notes", "ids": refs}, note_version=target["version"]
    )


@plugin.on("note.saved")
@plugin.on("note.trashed")
@plugin.on("note.purged")
@plugin.job("index")
async def index(ctx: Ctx, note_id: str, note_version: int | None = None):
    old = await ctx.storage.records.get("indexed", note_id, [])
    note = await ctx.notes.get(note_id)
    new = targets(note["body"]) if note else []
    if note and note_version is not None and note["version"] != note_version:
        return
    for target_id in old:
        await ctx.storage.records.delete("edges", note_id + ":" + target_id)
    for target_id in new:
        await ctx.storage.records.put(
            "edges", note_id + ":" + target_id, {}, note_id=note_id, key=target_id
        )
    await ctx.storage.records.put("indexed", note_id, new)
    for target_id in set(old + new + [note_id]):
        await refresh(ctx, target_id)


@plugin.on("plugin.enabled")
@plugin.job("backfill")
async def backfill(ctx: Ctx, cursor: str | None = None):
    page = await ctx.notes.query(limit=25, cursor=cursor, machine=True)
    for note in page["notes"]:
        await ctx.enqueue(
            "index",
            {"note_id": note["id"], "note_version": note["version"]},
            note_id=note["id"],
            priority=5,
        )
    if page["next_cursor"]:
        await ctx.enqueue("backfill", {"cursor": page["next_cursor"]}, priority=5)


@plugin.command("link")
async def link(ctx: Ctx, note_id: str, target_id: str | None = None):
    if target_id is None:
        return {
            "type": "form",
            "schema": {
                "type": "object",
                "additionalProperties": False,
                "required": ["note_id", "target_id"],
                "properties": {
                    "note_id": {"type": "string", "default": note_id},
                    "target_id": {"type": "string", "title": "Target note ID"},
                },
            },
        }
    source, target = await ctx.notes.get(note_id), await ctx.notes.get(target_id)
    if not source or not target:
        return text("The note is unavailable.")
    if note_id == target_id:
        raise ValueError("Choose another note.")
    proposal = await ctx.notes.suggest(
        note_id,
        "append_text",
        {"text": f"[Linked note](/n/{target['id']})"},
        note_version=source["version"],
        source_versions={target["id"]: target["version"]},
    )
    return {
        "type": "pill",
        "label": "Link to note",
        "action": "accept",
        "suggestion_id": proposal["id"],
    }
