"""On-demand metadata counts, with no tracking, stored history or model calls."""

from spunto.host import Ctx, Plugin

plugin = Plugin("library-stats")


@plugin.command("summary")
async def summary(ctx: Ctx):
    counts = await ctx.notes.stats(machine=True)
    lines = [
        f"Saved notes: {counts['saved']} · Active: {counts['active']} · "
        f"Archived: {counts['archived']}",
        f"Created in the last 30 days: {counts['created_30_days']}",
        f"Active notes without labels: {counts['unlabeled']}",
        f"Active pinned notes: {counts['pinned']}",
        f"Open tasks in active notes: {counts['open_tasks']}",
        "Counts cover notes you can access in this space, including private and "
        "machine-written notes. Trash is excluded. The last 30 days ends now.",
    ]
    return {"ui": 1, "items": [{"type": "text", "text": line} for line in lines]}
