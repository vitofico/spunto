"""Vector-only duplicate comparison; no automatic merge or chat request."""

from spunto.host import Ctx, Plugin
from spunto.plugins._wave2 import backfill, eligible, fingerprint, unchanged, versions

plugin = Plugin("duplicates")


@plugin.on("note.saved")
@plugin.job("compare")
async def compare(ctx: Ctx, note_id: str, note_version: int | None = None):
    note = await eligible(ctx, note_id)
    if not note or (note_version is not None and note_version != note["version"]):
        return
    neighbors = await ctx.vectors.nearest(note_id=note_id, k=5)
    for hit in neighbors:
        if hit["id"] == note_id or hit["score"] < ctx.settings.get("threshold", 0.97):
            continue
        other = await eligible(ctx, hit["id"])
        if not other:
            continue
        target, source = sorted([note, other], key=lambda n: n["id"])
        key = fingerprint([target["id"], target["version"], source["id"], source["version"]])
        if await ctx.storage.records.get("pairs", key):
            continue
        await unchanged(ctx, [target, source])
        await ctx.notes.suggest(
            target["id"],
            "merge_notes",
            {"source_id": source["id"], "target_id": target["id"]},
            note_version=target["version"],
            source_versions=versions([target, source]),
            confidence=hit["score"],
            reason="These notes are very similar. Compare both before merging.",
        )
        await ctx.storage.records.put("pairs", key, {"offered": True})
        break


@plugin.on("plugin.enabled")
@plugin.job("backfill")
async def enabled(ctx: Ctx, cursor: str | None = None):
    await backfill(ctx, "compare", cursor=cursor)
