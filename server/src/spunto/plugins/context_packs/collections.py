"""Owner-only Collections product API over the original Context Packs records."""

import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from spunto.api.errors import Problem
from spunto.host.language import tr

from . import (
    COLLECTION,
    conflict,
    edit,
    for_collection,
    identifier,
    markdown,
    page_records,
    provenance,
    record,
    sharing,
    source,
)


class Reference(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    id: str
    version: int | None = Field(default=None, ge=1)
    pinned: bool = False


class CollectionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    action: Literal["list", "read", "save", "add", "delete"] = "list"
    id: str | None = None
    revision: int | None = Field(default=None, ge=1)
    cursor: str | None = Field(default=None, max_length=56)
    title: str | None = Field(default=None, min_length=1, max_length=80)
    query: str = Field(default="", max_length=80)
    intro: str | None = Field(default=None, max_length=600)
    goal: str | None = Field(default=None, max_length=500)
    sources: list[Reference] | None = Field(default=None, max_length=10)
    intro_citations: dict[str, str] | None = Field(default=None, max_length=8)
    private: bool | None = None
    external: bool | None = None


async def live_detail(ctx, key, row):
    states = await provenance(ctx, row, owner_labels=True)
    pins = row.get("pinned_source_ids", [])
    return {
        **overview(key, row),
        "intro": row.get("intro", ""),
        "intro_citations": row.get("intro_citations", {}),
        "goal": row["goals"],
        "private": row["private"],
        "external": row.get("external", False),
        "sources": [
            {
                "id": item["id"],
                "title": item.get("display_title", ""),
                "version": item.get("current_version"),
                "status": item["status"],
                "private": item.get("current_private", item["private"]),
                "external": item.get("current_external", item["external"]),
                "pinned": item["id"] in pins,
            }
            for item in states
        ],
        "markdown": markdown(row, states),
        "agent_access": sharing(row, states),
        "ask_ready": not row.get("draft") and any(item.get("ai_eligible") for item in states),
    }


def overview(key, row):
    return {
        "id": key,
        "title": row["title"],
        "draft": bool(row.get("draft")),
        "revision": row["revision"],
        "count": len(row["sources"]),
        "updated_at": row["updated_at"],
    }


async def collection_request(ctx, data):
    ctx = for_collection(ctx)
    if data.action == "list":
        rows, cursor = await page_records(
            ctx, data.cursor, limit=16, query=data.query.casefold().strip()
        )
        return {
            "collections": [overview(row["id"], row["data"]) for row in rows],
            "next_cursor": cursor,
        }
    key = identifier(data.id)
    if data.action == "read":
        return await live_detail(ctx, key, await record(ctx, key))
    if data.action == "delete":
        if data.revision is None:
            raise ValueError("collection revision required")
        await edit(ctx, key, data.revision, action="delete")
        return {"deleted": True}

    old = await ctx.storage.records.get(COLLECTION, key)
    if old:
        if old.get("deleted"):
            conflict()
        # Replays of the same creation return the originally committed record.
        if data.revision is None and data.action == "save":
            if old.get("creation_digest") == request_digest(data):
                return await live_detail(ctx, key, old)
            conflict()
        if old["revision"] != data.revision:
            conflict()
    elif data.revision is not None or data.action == "add":
        conflict()

    old = old or {}
    baselines = {item["id"]: item for item in old.get("sources", [])}
    pins = set(old.get("pinned_source_ids", []))
    private = old.get("private", True) if data.private is None else data.private
    external = old.get("external", False) if data.external is None else data.external
    preview = await provenance(ctx, old) if old else []
    live = {item["id"]: item for item in preview}
    desired = data.sources
    if data.action == "add":
        old_sources = [Reference(id=item, pinned=item in pins) for item in baselines]
        desired = old_sources + [item for item in data.sources or [] if item.id not in baselines]
    versions = {}
    selected = list(baselines.values())
    if desired is not None:
        keys = [identifier(item.id) for item in desired]
        if len(keys) > 10 or len(keys) != len(set(keys)):
            raise Problem(422, "validation_failed", tr("Choose up to ten different source notes."))
        selected, pins = [], set()
        for ref in desired:
            baseline = baselines.get(ref.id)
            if ref.version is None:
                if baseline is None:
                    raise ValueError("new sources need a version")
            else:
                note = await ctx.notes.get(ref.id)
                if note is None or note["version"] != ref.version:
                    raise Problem(409, "version_conflict", tr("A source changed. Review it again."))
                baseline = source(note)
                versions[ref.id] = ref.version
            selected.append(baseline)
            if ref.pinned:
                pins.add(ref.id)
        for removed in set(baselines) - set(keys):
            # Keep the original classification if detached source material may
            # still be present in the owner's prose.
            state = live[removed]
            if state["current_version"] is not None:
                versions[removed] = state["current_version"]
            private |= state["private"] or state.get(
                "current_private", state["status"] == "unavailable"
            )
            external |= state["external"] or state.get(
                "current_external", state["status"] == "unavailable"
            )

    private |= any(
        item["private"] or live.get(item["id"], {}).get("current_private", False)
        for item in selected
    )
    external |= any(
        item["external"] or live.get(item["id"], {}).get("current_external", False)
        for item in selected
    )
    title = old.get("title", "") if data.title is None else data.title.strip()
    if not title:
        raise ValueError("a collection title is required")
    intro = old.get("intro", "") if data.intro is None else data.intro.strip()
    previous_proof = old.get("intro_citations", {})
    proof = previous_proof if data.intro_citations is None else data.intro_citations
    if not intro:
        proof = {}
    elif previous_proof and not proof:
        raise Problem(
            422,
            "validation_failed",
            tr("Clear the sourced introduction before removing one of its sources."),
        )
    source_ids = {item["id"] for item in selected}
    if not proof.keys() <= source_ids:
        raise Problem(
            422,
            "validation_failed",
            tr("Clear the sourced introduction before removing one of its sources."),
        )
    if any(not re.fullmatch(r"[a-f0-9]{64}", fingerprint) for fingerprint in proof.values()):
        raise ValueError("invalid introduction source fingerprint")
    verifying = proof if proof != previous_proof or intro != old.get("intro") else {}
    for note_id in verifying:
        note = await ctx.notes.evidence(note_id)
        baseline = next(item for item in selected if item["id"] == note_id)
        if (
            note is None
            or note["private"]
            or note["machine"]
            or note["archived"]
            or note["version"] != baseline["version"]
            or note["evidence_fingerprint"] != verifying[note_id]
        ):
            raise Problem(409, "version_conflict", tr("A source changed. Review it again."))
        versions[note_id] = note["version"]
    row = {
        **old,
        "title": title,
        "intro": intro,
        "intro_citations": proof,
        "project": old.get("project", ""),
        "goals": old.get("goals", "") if data.goal is None else data.goal.strip(),
        "constraints": old.get("constraints", ""),
        "decisions": old.get("decisions", ""),
        "questions": old.get("questions", ""),
        "private": private,
        "external": external,
        "sources": selected,
        "pinned_source_ids": [item["id"] for item in selected if item["id"] in pins],
        "revision": old.get("revision", 0) + 1,
        "created_at": old.get("created_at", ctx.clock()),
        "updated_at": ctx.clock(),
    }
    if not old:
        # Never persist another copy of authored prose as a request receipt.
        # The digest matches canonical request values and carries no raw text.
        row["creation_digest"] = request_digest(data)
    if data.action == "save" and row.get("draft"):
        states = await provenance(ctx, row)
        if any(item["status"] != "current" for item in states):
            raise Problem(
                409,
                "version_conflict",
                tr(
                    "Review changed sources and remove unavailable sources "
                    "before saving this draft."
                ),
            )
        versions.update({item["id"]: item["version"] for item in selected})
        del row["draft"]
    detail = await live_detail(ctx, key, row)
    if not await ctx.storage.records.compare_and_set(
        COLLECTION,
        key,
        old or None,
        row,
        source_versions=versions or None,
        source_fingerprints=verifying or None,
    ):
        if not old:
            prior = await ctx.storage.records.get(COLLECTION, key)
            if (
                prior
                and not prior.get("deleted")
                and prior.get("creation_digest") == request_digest(data)
            ):
                return await live_detail(ctx, key, prior)
        conflict()
    return detail


def request_digest(data):
    import hashlib

    return hashlib.sha256(data.model_dump_json(exclude_none=True).encode()).hexdigest()
