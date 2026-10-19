"""Bounded contexts and guarded, persistent owner results for the second plugin wave."""

import hashlib
import json
import re
from datetime import datetime

from spunto.api.errors import Problem

MAX_SINGLE = 20_000
MAX_CONTEXT = 50_000


def text(message):
    return {"type": "text", "text": message}


def versions(notes):
    return {n["id"]: n["version"] for n in notes}


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


async def eligible(ctx, note_id, *, maximum=MAX_SINGLE):
    note = await ctx.notes.get(note_id)
    if not note or note["machine"] or note["private"]:
        return None
    if len(note["body"]) > maximum:
        raise ValueError(f"This command supports notes up to {maximum} characters.")
    return note


async def unchanged(ctx, notes):
    for old in notes:
        current = await eligible(ctx, old["id"], maximum=1_000_000)
        if not current or current["version"] != old["version"]:
            raise Problem(409, "version_conflict", "A source changed; run the command again.")


def excerpts(notes, maximum=MAX_CONTEXT):
    result = []
    for note in notes:
        if maximum <= 0:
            break
        body = note["body"][:maximum]
        result.append({**note, "body": body})
        maximum -= len(body)
    return result


def cited_body(body, citations):
    """Models cannot smuggle undeclared internal references into grounded output."""
    references = set(re.findall(r"(?:/n/|note://)([0-9a-fA-F-]{36})", body))
    if not references <= set(citations):
        raise ValueError("undeclared_citation")
    return (
        body
        + "\n\nSources: "
        + ", ".join(f"[Note {index}](/n/{note_id})" for index, note_id in enumerate(citations, 1))
    )


def markdown_links(body):
    from markdown_it import MarkdownIt

    return [
        child.attrGet("href") or child.attrGet("src")
        for token in MarkdownIt("commonmark", {"html": False}).parse(body)
        for child in token.children or []
        if child.type in {"link_open", "image"}
    ]


async def backfill(ctx, job, *, cursor=None):
    page = await ctx.notes.query(limit=25, cursor=cursor)
    for note in page["notes"]:
        await ctx.enqueue(
            job,
            {"note_id": note["id"], "note_version": note["version"]},
            note_id=note["id"],
            priority=5,
        )
    if page["next_cursor"]:
        await ctx.enqueue("backfill", {"cursor": page["next_cursor"]}, priority=5)


async def history(ctx):
    result, cursor = [], 0
    while True:
        page = await ctx.notes.suggestion_history(cursor=cursor, limit=500)
        result.extend(page["suggestions"])
        cursor = page["next_cursor"]
        if cursor is None:
            return result


async def save_receipt(ctx, key, receipt):
    if not isinstance(key, str) or not re.fullmatch(r"[0-9a-f]{64}", key):
        raise ValueError("invalid result id")
    if receipt is None or receipt["expires"] <= ctx.clock():
        return text("That result expired. Run the command again.")
    if receipt.get("saved"):
        note = await ctx.notes.get(receipt["saved"])
        return (
            {"type": "notes", "ids": [note["id"]]}
            if note
            else text("The saved note is unavailable.")
        )
    note = await ctx.notes.create(
        receipt["body"],
        cites=receipt["citations"],
        source_versions=receipt["versions"],
        request_id=key,
    )
    receipt["saved"] = note["id"]
    await ctx.storage.records.put("results", key, receipt)
    return {"type": "notes", "ids": [note["id"]]}


def week_key(ctx):
    date = datetime.fromtimestamp(ctx.clock() / 1000, ctx.zone).date()
    year, week, _ = date.isocalendar()
    return f"{year}-W{week:02}"


async def write_machine(ctx, body, citations, sources, request_id):
    if (
        not citations
        or len(set(citations)) != len(citations)
        or not set(citations) <= {source["id"] for source in sources}
    ):
        raise ValueError("citations_outside_supplied_context")
    await unchanged(ctx, sources)
    return await ctx.notes.create(
        cited_body(body, citations),
        cites=citations,
        source_versions=versions(sources),
        request_id=request_id,
    )
