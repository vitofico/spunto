"""The inbox is a core source filter minus synced review membership."""

from spunto.host import Ctx, Plugin

plugin = Plugin("agent-inbox")


@plugin.command("mark-reviewed")
async def mark_reviewed(ctx: Ctx, note_id: str) -> dict:
    note = await ctx.notes.get(note_id)
    if note is None:
        return {"type": "text", "text": "Note unavailable"}
    await ctx.notes.annotate(note_id, "member:reviewed", {})
    await ctx.storage.records.put(
        "reviewed", note_id, {"version": note["version"]}, note_id=note_id
    )
    return {"type": "text", "text": "Marked reviewed"}


@plugin.on("note.saved")
async def changed(ctx: Ctx, note_id: str, note_version: int | None = None) -> None:
    reviewed = await ctx.storage.records.get("reviewed", note_id)
    if reviewed is None:
        return
    note = await ctx.notes.get(note_id)
    # A subsequent agent change needs reviewing again. Owner changes do not undo review.
    if note and note["updated_via_agent"] and note["version"] > reviewed["version"]:
        await ctx.notes.annotate(note_id, "member:reviewed", None)
        await ctx.storage.records.delete("reviewed", note_id)
