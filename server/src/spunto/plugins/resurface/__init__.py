"""One synced daily slip, spaced by plugin-owned last-shown records."""

import random
from datetime import datetime

from spunto.host import Ctx, Plugin
from spunto.plugins._notes import iter_notes

plugin = Plugin("resurface")
DAY = 86_400_000


def time_ms(value: int | str) -> int:
    if isinstance(value, int):
        return value
    return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)


@plugin.schedule("daily")
async def daily(ctx: Ctx) -> None:
    now = ctx.clock()
    today = datetime.fromtimestamp(now / 1000, ctx.zone)
    day = today.date().isoformat()
    previous = await ctx.storage.kv.get("daily")
    if previous and previous["day"] == day:
        return
    notes = [
        note
        async for note in iter_notes(ctx)
        if not note["machine"]
        and not note["archived"]
        and time_ms(note["created_at"]) <= now - ctx.settings.get("min_age_days", 30) * DAY
    ]
    anniversary = [
        note
        for note in notes
        if (created := datetime.fromtimestamp(time_ms(note["created_at"]) / 1000, ctx.zone)).year
        < today.year
        and (created.month, created.day) == (today.month, today.day)
    ]
    candidates = anniversary or notes
    if previous and previous.get("note_id"):
        await ctx.notes.annotate(previous["note_id"], "slip", None)
    if not candidates:
        await ctx.storage.kv.set("daily", {"day": day, "note_id": None})
        return
    weights = []
    for note in candidates:
        last = await ctx.storage.records.get("shown", note["id"])
        weights.append(max(1, (now - last["last_shown"]) // DAY) if last else 365)
    picked = random.choices(candidates, weights=weights, k=1)[0]  # noqa: S311
    await ctx.notes.annotate(
        picked["id"],
        "slip",
        {
            "type": "notes",
            "items": [
                {"id": picked["id"], "reason": "On this day" if anniversary else "From your notes"}
            ],
        },
    )
    await ctx.storage.records.put("shown", picked["id"], {"last_shown": now}, note_id=picked["id"])
    await ctx.storage.kv.set("daily", {"day": day, "note_id": picked["id"]})
