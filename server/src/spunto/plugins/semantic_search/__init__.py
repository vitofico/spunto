"""Bounded ranking IDs; the core owns visibility, filters and search blending."""

from spunto.host import Ctx, Plugin

plugin = Plugin("semantic-search")
FLOORS = {"embeddinggemma": 0.35, "bge-m3": 0.4, "nomic-embed-text": 0.35}


@plugin.search_source("search")
async def search(ctx: Ctx, query: str) -> list[dict]:
    if not isinstance(query, str) or not 1 <= len(query) <= 500:
        raise ValueError("query must contain 1 to 500 characters")
    floor = ctx.settings.get(
        "similarity_floor", FLOORS[ctx.settings.get("model_family", "embeddinggemma")]
    )
    neighbors = await ctx.vectors.nearest(text=query, k=50)
    seen, ranked = set(), []
    for item in neighbors:
        if item["id"] not in seen and item["score"] >= floor:
            ranked.append({"id": item["id"], "score": item["score"]})
            seen.add(item["id"])
    return ranked[:50]
