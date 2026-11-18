"""The inbox is a core source filter minus synced review membership."""

from spunto.core import agent_review
from spunto.host import Ctx, Plugin
from spunto.host.language import tr

plugin = Plugin("agent-inbox")


@plugin.command("mark-reviewed")
async def mark_reviewed(ctx: Ctx, note_id: str, base_version: int | None = None) -> dict:
    reviewed = await agent_review.accept(ctx, note_id, base_version)
    if not reviewed:
        return {"type": "text", "text": tr("Note unavailable")}
    message = "Marked reviewed" if base_version is None else "Note accepted"
    return {"type": "text", "text": tr(message)}


@plugin.on("note.saved")
async def changed(ctx: Ctx, note_id: str, note_version: int | None = None) -> None:
    await agent_review.reconcile_legacy(ctx, note_id)
