"""Read bounded pages through the host, with no plugin SQL."""


async def iter_notes(ctx, **filters):
    cursor = None
    while True:
        page = await ctx.notes.query(**filters, cursor=cursor)
        for note in page["notes"]:
            yield note
        cursor = page["next_cursor"]
        if cursor is None:
            return
