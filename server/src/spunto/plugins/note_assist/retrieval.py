"""Bounded retrieval shared by explicitly approved note workflows."""

from spunto.ai.errors import AIError
from spunto.ai.passages import source_passages
from spunto.ai.vectors import VectorUnavailable
from spunto.host import ContextError


async def eligible_goal_sources(ctx, query, *, limit=8, include=(), exclude=(), seed_note_id=None):
    """Combine early keyword and semantic hits. Return live evidence-backed notes."""
    semantic = []
    try:
        semantic = await ctx.vectors.nearest(
            **({"note_id": seed_note_id} if seed_note_id is not None else {"text": query}),
            k=40,
            include_chunks=True,
        )
    except VectorUnavailable:
        pass
    except ContextError as error:
        if str(error) not in {"permission_denied", "ai_unavailable"}:
            raise
    except AIError as error:
        if error.category != "provider" and error.code not in {
            "ai_profile_paused",
            "ai_unfenced_note",
        }:
            raise
    text = (await ctx.notes.search(query, limit=40, match_any=True))["hits"]
    scores = {}
    for ranking in (text, semantic):
        for rank, hit in enumerate(ranking, 1):
            note_id = hit["id"]
            scores[note_id] = scores.get(note_id, 0) + 1 / (60 + rank)
    hints = {hit["id"]: hit["chunks"] for hit in semantic if hit.get("chunks")}
    candidates = dict.fromkeys([*include, *sorted(scores, key=scores.get, reverse=True)])
    excluded = set(exclude)
    result = []
    for note_id in candidates:
        if note_id in excluded:
            continue
        note = await ctx.notes.evidence(note_id, with_parts=True)
        if note is None or note["machine"] or note["private"] or note["archived"]:
            continue
        note["chunk_hints"] = hints.get(note_id, [])
        result.append(note)
        if len(result) == limit:
            break
    return result


def supporting_passage(note, query, *, maximum=340):
    """Return actual prose from a matched note, prioritizing matching sentences."""
    if note.get("evidence_parts"):
        passage = source_passages(
            note["evidence_parts"], query, maximum, note.get("chunk_hints", ())
        )
        return passage.strip()
    return note["body"].strip()[:maximum]
