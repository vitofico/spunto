"""Stored backlinks including explicit machine follow-ups, without AI or network."""

import re

from spunto.api.errors import Problem
from spunto.core.note_links import insert_link, link_text, targets
from spunto.host import Ctx, Plugin
from spunto.plugins._wave2 import text

plugin = Plugin("links")
_DUPLICATE = "This note already links to the selected note."


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
    new = targets(note["body"])[:50] if note else []
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
    target_version: int | None = None,
    insert: bool = False,
):
    if type(insert) is not bool:
        raise ValueError("Insert must be a boolean.")
    if target_version is not None and (type(target_version) is not int or target_version < 1):
        raise ValueError("Target version must be a positive integer.")
    if insert:
        if (
            not isinstance(note_id, str)
            or not isinstance(target_id, str)
            or target != "Choose a note"
        ):
            raise ValueError("Choose a source and target note to insert a link.")
        if type(base_version) is not int or base_version < 1 or target_version is None:
            raise ValueError("Source and target versions must be positive integers.")
        inserted = await insert_link(
            ctx, note_id, target_id, base_version=base_version, target_version=target_version
        )
        return text("Link inserted." if inserted else _DUPLICATE)
    source = await ctx.notes.get(note_id)
    if source is None:
        return text("The note is unavailable.")
    if base_version is not None and (
        type(base_version) is not int or source["version"] != base_version
    ):
        raise Problem(409, "version_conflict", "This note changed. Reopen Link to note.")
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
            (await ctx.notes.search(query, limit=9, mode="keyword"))["hits"]
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
        return text(_DUPLICATE)
    proposal = await ctx.notes.suggest(
        note_id,
        "append_text",
        {"text": link_text(destination["id"])},
        note_version=source["version"],
        source_versions={destination["id"]: destination["version"]},
    )
    return {
        "type": "pill",
        "label": "Link to note",
        "action": "accept",
        "suggestion_id": proposal["id"],
    }
