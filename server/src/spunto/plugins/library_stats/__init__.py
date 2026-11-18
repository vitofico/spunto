"""On-demand metadata counts, with no tracking, stored history or model calls."""

from spunto.host import Ctx, Plugin
from spunto.host.language import tr

plugin = Plugin("library-stats")


@plugin.command("summary")
async def summary(ctx: Ctx):
    counts = await ctx.notes.stats(machine=True)
    lines = [
        tr("Saved notes: {saved} · Active: {active} · Archived: {archived}", **counts),
        tr("Created in the last 30 days: {created_30_days}", **counts),
        tr("Active notes without labels: {unlabeled}", **counts),
        tr("Active pinned notes: {pinned}", **counts),
        tr("Open tasks in active notes: {open_tasks}", **counts),
        tr(
            "Counts cover notes you can access in this space, including private and "
            "machine-written notes. Trash is excluded. The last 30 days ends now."
        ),
    ]
    return {"ui": 1, "items": [{"type": "text", "text": line} for line in lines]}
