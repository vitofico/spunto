"""Tasks stay ordinary notes; this plugin only writes a progress token."""

from spunto.core.text import task_counts
from spunto.host import Ctx, Plugin
from spunto.plugins._notes import iter_notes

plugin = Plugin("tasks")


def task_total(body: str) -> int:
    return task_counts(body)[1]


@plugin.on("note.saved")
async def update(ctx: Ctx, note_id: str, note_version: int | None = None) -> None:
    note = await ctx.notes.get(note_id)
    if note is None:
        return
    total = task_total(note["body"])
    payload = (
        {"type": "text", "text": f"{total - note['open_tasks']} of {total} done"} if total else None
    )
    await ctx.notes.annotate(note_id, "meta", payload)


@plugin.on("plugin.enabled")
async def backfill(ctx: Ctx) -> None:
    async for note in iter_notes(ctx, machine=True):
        await update(ctx, note["id"])
