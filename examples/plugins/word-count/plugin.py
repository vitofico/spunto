"""Local-only sample using the same scoped API as bundled plugins."""

from spunto.host import Ctx, Plugin

plugin = Plugin("word-count")


@plugin.on("note.saved")
async def count(ctx: Ctx, note_id: str, note_version: int):
    note = await ctx.notes.get(note_id)
    if note is None:
        return
    words = len(note["body"].split())
    await ctx.notes.annotate(
        note_id,
        "panel",
        [{"type": "text", "text": f"{words} words"}],
        note_version=note_version,
    )
