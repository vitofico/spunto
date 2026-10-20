"""Stored backlinks including explicit machine follow-ups, without AI or network."""

import re
import uuid

from markdown_it import MarkdownIt

from spunto.api.errors import Problem
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
async def link(
    ctx: Ctx,
    note_id: str,
    target_id: str | None = None,
    query: str = "",
    target: str = "Choose a note",
    source_choices: list[str] | None = None,
    base_version: int | None = None,
):
    source = await ctx.notes.get(note_id)
    if source is None:
        return text("The note is unavailable.")
    if base_version is not None and (
        type(base_version) is not int or source["version"] != base_version
    ):
        raise Problem(409, "version_conflict", "This note changed. Reopen Link to note.")
    target_version = None
    if target != "Choose a note":
        match = re.fullmatch(r"([1-8])\. .{1,60}", target) if isinstance(target, str) else None
        if (
            match is None
            or not isinstance(source_choices, list)
            or not 1 <= len(source_choices) <= 8
            or int(match[1]) > len(source_choices)
        ):
            raise ValueError("invalid target selection")
        choice = source_choices[int(match[1]) - 1]
        selected = re.fullmatch(r"([0-9a-f-]{36})@([1-9][0-9]{0,18})", choice)
        if selected is None:
            raise ValueError("invalid target selection")
        target_id, target_version = selected[1], int(selected[2])
    if target_id is None:
        if not isinstance(query, str) or len(query) > 200:
            raise ValueError("Search supports up to 200 characters.")
        query = query.strip()
        hits = (
            (await ctx.notes.search(query, limit=9))["hits"]
            if query
            else (await ctx.notes.query(limit=9, machine=True))["notes"]
        )
        candidates = [note for note in hits if note["id"] != note_id][:8]
        captions = [
            f"{index}. "
            + (
                re.sub(r"\s+", " ", note.get("title") or note["body"]).strip()[:60]
                or "Untitled note"
            )
            for index, note in enumerate(candidates, 1)
        ]
        return {
            "ui": 1,
            "items": [
                text(
                    "Search or choose a recent note, then review the proposed link."
                    if candidates
                    else "No matching notes. Change the search and run it again."
                ),
                {
                    "type": "form",
                    "schema": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["note_id", "target", "source_choices", "base_version"],
                        "properties": {
                            "note_id": {"type": "string", "default": note_id},
                            "base_version": {"type": "integer", "default": source["version"]},
                            "query": {
                                "type": "string",
                                "title": "Search notes (blank shows recent)",
                                "maxLength": 200,
                                "default": query,
                            },
                            "target": {
                                "type": "string",
                                "title": "Link to",
                                "enum": ["Choose a note", *captions],
                                "default": "Choose a note",
                            },
                            "source_choices": {
                                "type": "array",
                                "items": {"type": "string", "maxLength": 60},
                                "default": [
                                    f"{note['id']}@{note['version']}" for note in candidates
                                ],
                            },
                        },
                    },
                },
            ],
        }
    destination = await ctx.notes.get(target_id)
    if not destination:
        return text("The note is unavailable.")
    if target_version is not None and destination["version"] != target_version:
        raise Problem(409, "version_conflict", "That note changed. Search again before linking it.")
    if note_id == target_id:
        raise ValueError("Choose another note.")
    if target_id in targets(source["body"]):
        return text("This note already links to the selected note.")
    title = "Linked note"
    if not destination["private"] or source["private"]:
        title = re.sub(r"\s+", " ", destination.get("title") or "").strip()[:100] or title
    title = re.sub(r"([\\`*_{}\[\]()#+.!<>~-])", r"\\\1", title)
    proposal = await ctx.notes.suggest(
        note_id,
        "append_text",
        {"text": f"[{title}](/n/{destination['id']})"},
        note_version=source["version"],
        source_versions={destination["id"]: destination["version"]},
    )
    return {
        "type": "pill",
        "label": "Link to note",
        "action": "accept",
        "suggestion_id": proposal["id"],
    }
