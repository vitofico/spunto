"""Explicit, manual thinking histories and collision workspaces; no AI or note rewrites."""

import re
import uuid

from spunto.api.errors import Problem
from spunto.host import Ctx, Plugin
from spunto.plugins.context_packs import (
    NONE,
    caption,
    command,
    field,
    form,
    identifier,
    output,
    owner,
    source,
    string,
    text,
)

plugin = Plugin("thinking-desk")
COLLECTION = "desks"
RELATIONS = ["builds-on", "challenges", "replaces"]
PROMPTS = [
    "What principle from one subject could solve a problem in the other?",
    "Where do these notes disagree, and what experiment would distinguish them?",
    "What surprising analogy connects these ideas? Where does it break?",
]


def conflict():
    raise Problem(
        409,
        "version_conflict",
        (
            "This desk or source changed. Reopen the desk, review sources, and retry; "
            "your original notes remain intact."
        ),
    )


async def load(ctx, desk_id, expected_revision=None):
    data = await ctx.storage.records.get(COLLECTION, identifier(desk_id))
    if data is None or data.get("deleted"):
        raise Problem(404, "not_found", "That thinking desk is unavailable.")
    if expected_revision is not None and (
        type(expected_revision) is not int or data["revision"] != expected_revision
    ):
        conflict()
    return data


async def current(ctx, data):
    result = []
    for baseline in data["sources"]:
        note = await ctx.notes.get(baseline["id"])
        result.append((baseline, note))
    return result


async def save(ctx, desk_id, old, new):
    new = {**new, "revision": old["revision"] + 1 if old else 1}
    summary(new, await current(ctx, new))
    if not await ctx.storage.records.compare_and_set(COLLECTION, desk_id, old, new):
        conflict()
    return await render(ctx, desk_id)


def summary(data, states):
    lines = [f"# {data['title']}", "Mode: " + data["mode"], data["prompt"]]
    lines.append(
        "## Notes in creation order" if data["mode"] == "history" else "## Selected subjects"
    )
    for index, (baseline, note) in enumerate(
        sorted(states, key=lambda pair: (pair[0]["created_at"], pair[0]["id"])), 1
    ):
        date = baseline["created_at"][:10]
        status = (
            "unavailable"
            if note is None
            else "changed — review sources"
            if source(note) != {k: baseline[k] for k in ("id", "version", "private", "external")}
            else "current"
        )
        # Deliberately retain no cached titles or bodies when sources become unavailable.
        lines.append(
            f"{index}. {date}: [Note](/n/{baseline['id']}) "
            f"· revision {baseline['version']} · {status}"
        )
    if data["mode"] == "history":
        lines.append("## Your connections")
        numbers = {
            b["id"]: i
            for i, (b, _) in enumerate(
                sorted(states, key=lambda p: (p[0]["created_at"], p[0]["id"])), 1
            )
        }
        for link in data["relations"]:
            lines.append(
                f"- Note {numbers[link['from']]} {link['kind']} note {numbers[link['to']]}"
            )

    else:
        lines.extend(["## Creative prompts", *[f"- {prompt}" for prompt in PROMPTS]])
    lines.extend(["## Your thinking", data["thinking"] or "No reflection recorded yet."])
    result = "\n\n".join(lines)
    if len(result) > 4000:
        raise ValueError("Desk exceeds its display budget; shorten your reflection or connections")
    return result


async def render(ctx, desk_id):
    data = await load(ctx, desk_id)
    states = await current(ctx, data)
    args = {"desk_id": desk_id, "expected_revision": data["revision"]}
    return output(
        {"type": "markdown", "text": summary(data, states)},
        {
            "type": "notes",
            "ids": [
                n["id"]
                for _, n in sorted(states, key=lambda p: (p[0]["created_at"], p[0]["id"]))
                if n
            ],
        },
        command("edit", "Write reflection", **args),
        command("sources", "Review sources", **args),
        *(
            [command("sources", "Connect notes", **args, action="connect")]
            if data["mode"] == "history"
            else []
        ),
        command("edit", "Keep private note copy", **args, action="snapshot"),
        command("desk", "All thinking desks"),
    )


@plugin.command("desk")
async def desk(
    ctx: Ctx, desk_id: str | None = None, cursor: str | None = None, note_id: str | None = None
):
    owner(ctx)
    if desk_id:
        return await render(ctx, desk_id)
    page = await ctx.storage.records.list(COLLECTION, limit=6, after=cursor)
    items = [
        text(
            "Choose notes deliberately. Histories preserve originals; collision desks "
            "combine subjects using your own words. Commands require a connection. Saved"
            " private note copies remain available offline."
        ),
        command("edit", "New thinking desk", **({"note_id": note_id} if note_id else {})),
    ]
    for row in page[:5]:
        if not row["data"].get("deleted"):
            items.append(command("desk", row["data"]["title"][:24], desk_id=row["id"]))
    if len(page) > 5:
        items.append(command("desk", "Next desks", cursor=page[4]["id"]))
    return output(*items)


@plugin.command("edit")
async def edit(
    ctx: Ctx,
    desk_id: str | None = None,
    expected_revision: int | None = None,
    title: str | None = None,
    mode: str = "history",
    prompt: str = "",
    thinking: str = "",
    action: str = "save",
    request_id: str | None = None,
    note_id: str | None = None,
):
    owner(ctx)
    old = await load(ctx, desk_id, expected_revision) if desk_id else None
    if old and expected_revision is None:
        conflict()
    if action not in {"save", "delete", "snapshot"}:
        raise ValueError("unknown thinking desk action")
    if action == "delete":
        if not old:
            raise ValueError("a saved desk is required")
        await ctx.storage.records.compare_and_set(
            COLLECTION, desk_id, old, {"deleted": True}
        ) or conflict()
        return text("Desk deleted. Original notes and saved copies remain intact.")
    if action == "snapshot":
        if not old:
            raise ValueError("a saved desk is required")
        if old["mode"] == "collision" and (len(old["sources"]) < 2 or not old["thinking"].strip()):
            raise ValueError(
                "Choose at least two notes and write your new idea before saving a collision"
            )
        states = await current(ctx, old)
        if any(note is None or note["version"] != baseline["version"] for baseline, note in states):
            conflict()
        note = await ctx.notes.create(
            summary(old, states),
            private=True,
            source_versions={b["id"]: b["version"] for b, _ in states} or None,
            request_id=f"desk:{desk_id}:{old['revision']}",
        )
        return output(
            text("Private snapshot saved. It does not update automatically."),
            {"type": "notes", "ids": [note["id"]]},
        )
    if title is None:
        data = old or {}
        return output(
            text(
                "History follows selected notes in creation order. Collision: choose two to "
                "five notes from different subjects and develop your own idea. No AI is used"
                " and original notes never change."
            ),
            form(
                {
                    "title": {
                        **field("Topic or desk title", 80, data.get("title", "New thinking desk")),
                        "minLength": 1,
                    },
                    "mode": {
                        "type": "string",
                        "title": "Desk type",
                        "enum": [data["mode"]] if old else ["history", "collision"],
                        "default": data.get("mode", "history"),
                    },
                    "prompt": field("Problem or question (optional)", 300, data.get("prompt", "")),
                    "thinking": field(
                        "Your reflection or new idea", 1500, data.get("thinking", "")
                    ),
                    "action": {
                        "type": "string",
                        "title": "Action",
                        "enum": ["save", "delete"] if old else ["save"],
                        "default": "save",
                    },
                    **(
                        {"request_id": field("Creation request ID", 36, str(uuid.uuid4()))}
                        if not old
                        else {}
                    ),
                },
                ["title"],
            ),
        )
    if mode not in {"history", "collision"} or (old and mode != old["mode"]):
        raise ValueError("invalid desk type")
    data = {
        "title": string(title, 80, required=True),
        "mode": mode,
        "prompt": string(prompt, 300),
        "thinking": string(thinking, 1500),
        "sources": old["sources"] if old else [],
        "relations": old["relations"] if old else [],
    }
    if not old:
        desk_id = identifier(request_id) if request_id else str(uuid.uuid4())
        if await ctx.storage.records.get(COLLECTION, desk_id) is not None:
            conflict()
        if note_id:
            note = await ctx.notes.get(identifier(note_id))
            if note is None:
                raise ValueError("source unavailable")
            data["sources"] = [{**source(note), "created_at": note["created_at"]}]
    return await save(ctx, desk_id, old, data)


@plugin.command("sources")
async def sources(
    ctx: Ctx,
    desk_id: str,
    expected_revision: int,
    query: str = "",
    add: str = NONE,
    remove: str = NONE,
    refresh: bool = False,
    source_choices: list[str] | None = None,
    action: str = "choose",
    from_note: str | None = None,
    to_note: str | None = None,
    kind: str = "builds-on",
    disconnect: bool = False,
):
    if action == "connect":
        return await relate(ctx, desk_id, expected_revision, from_note, to_note, kind, disconnect)
    if action != "choose":
        raise ValueError("unknown source action")
    owner(ctx)
    old = await load(ctx, desk_id, expected_revision)
    query = string(query, 200)
    if type(refresh) is not bool or sum([add != NONE, remove != NONE, refresh]) > 1:
        raise ValueError("choose one source action")
    selected = list(old["sources"])
    states = await current(ctx, old)
    captions = [
        caption(n, i) if n else f"{i}. Unavailable source" for i, (_, n) in enumerate(states, 1)
    ]
    if add != NONE:
        if not source_choices or len(source_choices) > 5 or not isinstance(add, str):
            raise ValueError("search again before selecting a source")
        match = re.fullmatch(r"([1-5])\. .{1,60}", add)
        if not match or int(match[1]) > len(source_choices):
            raise ValueError("invalid source selection")
        value = source_choices[int(match[1]) - 1]
        note_id, version = value.split("@")
        note = await ctx.notes.get(identifier(note_id))
        if note is None or str(note["version"]) != version:
            conflict()
        maximum = 5 if old["mode"] == "collision" else 10
        if len(selected) >= maximum or any(b["id"] == note_id for b in selected):
            raise ValueError("source already selected or desk source limit reached")
        selected.append({**source(note), "created_at": note["created_at"]})
    elif remove != NONE:
        if remove in captions:
            remove = selected[captions.index(remove)]["id"]
        identifier(remove)
        if remove not in {b["id"] for b in selected}:
            raise ValueError("unknown source")
        selected = [b for b in selected if b["id"] != remove]
    elif refresh:
        selected = []
        for _baseline, note in await current(ctx, old):
            if note is None:
                conflict()
            selected.append({**source(note), "created_at": note["created_at"]})
    if add != NONE or remove != NONE or refresh:
        ids = {b["id"] for b in selected}
        return await save(
            ctx,
            desk_id,
            old,
            {
                **old,
                "sources": selected,
                "relations": [r for r in old["relations"] if r["from"] in ids and r["to"] in ids],
            },
        )
    hits = (
        (await ctx.notes.search(query, limit=5))["hits"]
        if query
        else (await ctx.notes.query(limit=5, machine=True))["notes"]
    )
    hits = [n for n in hits if n["id"] not in {b["id"] for b in selected}]
    return output(
        text(
            "Search, then add one note. Removing a source also removes its connections. "
            "Acknowledge revisions only after reviewing the current originals. Collision"
            " works best with two to five different subjects."
        ),
        {"type": "notes", "ids": [n["id"] for n in hits]},
        form(
            {
                "query": field("Search notes (blank browses recent)", 200, query),
                "add": {
                    "type": "string",
                    "title": "Add source",
                    "enum": [NONE, *[caption(n, i) for i, n in enumerate(hits, 1)]],
                    "default": NONE,
                },
                "remove": {
                    "type": "string",
                    "title": "Remove source",
                    "enum": [NONE, *captions],
                    "default": NONE,
                },
                "refresh": {
                    "type": "boolean",
                    "title": "Acknowledge current source revisions",
                    "default": False,
                },
                "source_choices": {
                    "type": "array",
                    "items": {"type": "string", "maxLength": 60},
                    "default": [f"{n['id']}@{n['version']}" for n in hits],
                },
            }
        ),
        command("desk", "Back to desk", desk_id=desk_id),
    )


async def relate(
    ctx: Ctx,
    desk_id: str,
    expected_revision: int,
    from_note: str | None = None,
    to_note: str | None = None,
    kind: str = "builds-on",
    remove: bool = False,
):
    owner(ctx)
    old = await load(ctx, desk_id, expected_revision)
    if old["mode"] != "history":
        raise ValueError("connections belong to histories")
    ids = [b["id"] for b in old["sources"]]
    states = await current(ctx, old)
    labels = [
        caption(n, i) if n else f"{i}. Unavailable source" for i, (_, n) in enumerate(states, 1)
    ]
    if from_note in labels:
        from_note = ids[labels.index(from_note)]
    if to_note in labels:
        to_note = ids[labels.index(to_note)]
    if from_note is None:
        if len(ids) < 2:
            return output(
                text("Select at least two source notes first."),
                command(
                    "sources", "Choose sources", desk_id=desk_id, expected_revision=old["revision"]
                ),
            )
        return output(
            text(
                "The first note builds on, challenges, or replaces the second. This records "
                "your interpretation; neither note is edited. Choose the same connection "
                "with Remove to undo it."
            ),
            {"type": "notes", "ids": ids},
            form(
                {
                    "from_note": {"type": "string", "title": "First note", "enum": labels},
                    "kind": {
                        "type": "string",
                        "title": "Connection",
                        "enum": RELATIONS,
                        "default": "builds-on",
                    },
                    "to_note": {"type": "string", "title": "Second note", "enum": labels},
                    "disconnect": {
                        "type": "boolean",
                        "title": "Remove this connection",
                        "default": False,
                    },
                },
                ["from_note", "to_note"],
            ),
        )
    if (
        from_note not in ids
        or to_note not in ids
        or from_note == to_note
        or kind not in RELATIONS
        or type(remove) is not bool
    ):
        raise ValueError("choose two different selected notes and a supported connection")
    states = await current(ctx, old)
    if any(n is None or n["version"] != b["version"] for b, n in states):
        conflict()
    link = {"from": from_note, "to": to_note, "kind": kind}
    links = [r for r in old["relations"] if r != link]
    if not remove:
        if len(links) >= 20:
            raise ValueError("a history supports at most twenty connections")
        links.append(link)
    return await save(ctx, desk_id, old, {**old, "relations": links})
