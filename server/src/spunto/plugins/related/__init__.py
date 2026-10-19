"""Live related-note references, without retained annotations or model prose."""

from spunto.host import Ctx, Plugin

plugin = Plugin("related")


async def related_notes(ctx: Ctx, note_id: str) -> list[dict]:
    neighbors = await ctx.vectors.nearest(note_id=note_id, k=5)
    # The generation-bound vector service authorizes the seed and filters
    # visibility, machine/private notes and the seed's conflict family.
    seen, result = {note_id}, []
    for item in neighbors:
        if item["id"] not in seen:
            seen.add(item["id"])
            result.append({"id": item["id"]})
        if len(result) == 5:
            break
    return result


@plugin.panel("panel")
async def panel(ctx: Ctx, id: str) -> dict:
    return {"type": "notes", "items": await related_notes(ctx, id)}


@plugin.mcp_tool("notes")
async def notes(ctx: Ctx, id: str) -> dict:
    return {"notes": await related_notes(ctx, id)}
