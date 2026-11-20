"""Explicit, manual thinking histories and collision workspaces; no AI or note rewrites."""

import re
import uuid

from spunto.api.errors import Problem
from spunto.core.text import title_of
from spunto.host import Ctx, Plugin
from spunto.host.language import tr
from spunto.plugins.context_packs import (
    NONE,
    caption,
    command,
    field,
    form,
    identifier,
    output,
    owner,
    picker_fields,
    picker_selection,
    revision,
    selected_choice,
    source,
    string,
    text,
)

plugin = Plugin("thinking-desk")
COLLECTION = "desks"
PAGE_SIZE = 4
TOMBSTONE = {"deleted": True}
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
            tr(
                "This desk or source changed. Reopen the desk, review sources, and retry; "
                "your original notes remain intact."
            )
        ),
    )


async def load(ctx, desk_id, expected_revision=None):
    data = await ctx.storage.records.get(COLLECTION, identifier(desk_id))
    if data is None or data.get("deleted"):
        raise Problem(404, "not_found", tr("That thinking desk is unavailable."))
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


async def save(ctx, desk_id, old, new, *, picker=False, source_versions=None, picker_feedback=None):
    new = {**new, "revision": old["revision"] + 1 if old else 1}
    states = await current(ctx, new)
    summary(new, states)
    if not await ctx.storage.records.compare_and_set(
        COLLECTION, desk_id, old, new, source_versions=source_versions
    ):
        conflict()
    if picker:
        return await render_sources(
            ctx, desk_id, new, await current(ctx, new), picker=True, feedback=picker_feedback
        )
    return await render(ctx, desk_id)


def source_status(baseline, note):
    if note is None:
        return "unavailable"
    if source(note) != {key: baseline[key] for key in ("id", "version", "private", "external")}:
        return "changed — review sources"
    return "current"


def summary(data, states, *, snapshot=True):
    history = data["mode"] == "history"
    lines = [f"# {data['title']}"] if snapshot else []
    if snapshot:
        lines.append(tr("Mode: {mode}", mode=tr(data["mode"])))
    if data["prompt"]:
        lines.append(data["prompt"])
    if data["thinking"]:
        section = "Your reflection" if history else "Your idea"
        lines.extend(["## " + tr(section), data["thinking"]])
    if states or snapshot:
        lines.append("## " + tr("Notes in creation order" if history else "Notes brought together"))
    for index, (baseline, note) in enumerate(
        sorted(states, key=lambda pair: (pair[0]["created_at"], pair[0]["id"])), 1
    ):
        date = baseline["created_at"][:10]
        status = source_status(baseline, note)
        # Resolve live titles only from a currently readable note.
        label = tr("Note") if snapshot else tr("Unavailable note")
        if note and not snapshot:
            label = re.sub(r"([\\`*_\[\]<>])", r"\\\1", caption(note, index).split(". ", 1)[1])
        reference = f"[{label}](/n/{baseline['id']})" if note else label
        line = f"{index}. {date} · {reference}"
        if snapshot or status != "current":
            line += f" · {tr('revision {version}', version=baseline['version'])} · {tr(status)}"
        lines.append(line)
    if history and (data["relations"] or snapshot):
        lines.append("## " + tr("Your connections"))
        numbers = {
            b["id"]: i
            for i, (b, _) in enumerate(
                sorted(states, key=lambda p: (p[0]["created_at"], p[0]["id"])), 1
            )
        }
        for link in data["relations"]:
            lines.append(
                "- "
                + tr(
                    "Note {first} {relation} note {second}",
                    first=numbers[link["from"]],
                    relation=tr(link["kind"]),
                    second=numbers[link["to"]],
                )
            )
    result = "\n\n".join(lines)
    if len(result) > 4000:
        if not snapshot:
            return summary(data, states)
        raise ValueError("Desk exceeds its display budget; shorten your reflection or connections")
    return result


async def render(ctx, desk_id):
    data = await load(ctx, desk_id)
    states = await current(ctx, data)
    history = data["mode"] == "history"
    minimum = 1 if history else 2
    available = sum(note is not None for _, note in states)
    blocked = any(source_status(baseline, note) != "current" for baseline, note in states)
    args = {"desk_id": desk_id, "expected_revision": data["revision"]}
    choose = command(
        "sources", tr("Choose notes" if available < minimum else "Manage notes"), **args
    )
    write = command(
        "edit",
        tr(
            ("Edit reflection" if data["thinking"] else "Write a reflection")
            if history
            else ("Edit your idea" if data["thinking"] else "Write your idea")
        ),
        **args,
        action="write",
    )
    connect = command("sources", tr("Connect notes"), **args, action="connect")
    snapshot = command("edit", tr("Save as private note"), **args, action="snapshot")
    if blocked:
        next_step = tr("Review changed notes or remove unavailable ones before saving a copy.")
        actions = [command("sources", tr("Review notes"), **args)]
    elif available < minimum:
        next_step = tr(
            "Choose the notes that show how your idea changed."
            if history
            else "Choose at least two notes from different subjects to find a connection."
        )
        actions = [choose]
    elif not history and not data["thinking"]:
        next_step = tr(
            "Look for a connection between these notes, then write the idea in your own words."
        )
        actions = [write, choose]
    elif history and available >= 2 and not data["relations"]:
        next_step = tr(
            "Connect notes to show what changed, or save this timeline as a private note."
        )
        actions = [connect, snapshot, choose]
    else:
        next_step = tr(
            "Keep working here, or save a private note with your work and links to the originals."
        )
        actions = [snapshot, choose]
    if ((history and available >= minimum) or data["thinking"]) and write not in actions:
        actions.append(write)
    if history and available >= 2 and not blocked and connect not in actions:
        actions.append(connect)
    primary, *secondary = actions
    details = summary(data, states, snapshot=False)
    return output(
        {"type": "markdown", "text": f"# {data['title']}"},
        text(next_step),
        primary,
        *([{"type": "markdown", "text": details}] if details else []),
        *secondary,
        command("edit", tr("Desk details"), **args, action="details"),
        command("desk", tr("All thinking desks")),
    )


def choices(note_id=None, *, back=False):
    start_args = {"action": "start"}
    if note_id:
        start_args["note_id"] = note_id

    items = [
        text(
            tr(
                "Start with notes you already have. Build a timeline of an idea or write a "
                "new idea by bringing two topics together."
            )
        ),
        *([text(tr("The note you opened will be added first."))] if note_id else []),
        {
            "type": "markdown",
            "text": "### "
            + tr("Follow one idea")
            + "\n\n"
            + tr("Put an early trip plan beside the notes that made you rethink it."),
        },
        command("edit", tr("Trace an idea"), **start_args, mode="history"),
        {
            "type": "markdown",
            "text": "### "
            + tr("Make a new connection")
            + "\n\n"
            + tr("Use a note about pruning plants to rethink how you cut a project’s scope."),
        },
        command("edit", tr("Combine notes"), **start_args, mode="collision"),
    ]
    if back:
        items.append(command("desk", tr("All thinking desks")))
    return output(*items)


@plugin.command("desk")
async def desk(
    ctx: Ctx,
    desk_id: str | None = None,
    cursor: str | None = None,
    note_id: str | None = None,
    action: str | None = None,
):
    owner(ctx)
    if action not in {None, "new"}:
        raise ValueError("unknown thinking desk navigation")
    if desk_id:
        return await render(ctx, desk_id)
    if note_id:
        if await ctx.notes.get(identifier(note_id)) is None:
            return text(tr("That source note is unavailable."))
        return choices(note_id, back=True)
    if action == "new":
        return choices(back=True)

    # An already open older client may still be holding an ascending ID cursor.
    after, before = None, None
    if cursor is not None:
        if not isinstance(cursor, str):
            raise ValueError("invalid desk cursor")
        if ":" in cursor:
            if not re.fullmatch(r"[0-9]{1,19}:[0-9a-f-]{36}", cursor):
                raise ValueError("invalid desk cursor")
            updated_at, last_id = cursor.split(":")
            before = int(updated_at), identifier(last_id)
        else:
            after = identifier(cursor)
    page = await ctx.storage.records.list(
        COLLECTION,
        limit=PAGE_SIZE + 1,
        recent=after is None,
        before=before,
        after=after,
        exclude_data=TOMBSTONE,
    )
    if not page and cursor is None:
        return choices()
    items = [command("desk", tr("New desk"), action="new")]
    for row in page[:PAGE_SIZE]:
        items.extend(
            [text(row["data"]["title"]), command("desk", tr("Open desk"), desk_id=row["id"])]
        )
    if len(page) > PAGE_SIZE:
        last = page[PAGE_SIZE - 1]
        next_cursor = last["id"] if after else f"{last['updated_at']}:{last['id']}"
        items.append(command("desk", tr("More desks"), cursor=next_cursor))
    return output(*items)


def titled_form(title, properties, required=()):
    component = form(properties, required)
    component["schema"]["title"] = tr(title)
    return component


def title_field(default=None):
    spec = {**field(tr("A name for this desk"), 80), "minLength": 1}
    if default is None:
        del spec["default"]
    else:
        spec["default"] = default
    return spec


def writing_reference(note):
    lines = note["body"].strip().splitlines()
    if lines and title_of(lines[0]) == note.get("title"):
        lines = lines[1:]
    excerpt = " ".join(" ".join(lines).split())
    snippet = excerpt if len(excerpt) <= 96 else excerpt[:95].rsplit(" ", 1)[0] + "…"
    return {"id": note["id"], **({"reason": snippet} if snippet else {})}


@plugin.command("edit")
async def edit(
    ctx: Ctx,
    desk_id: str | None = None,
    expected_revision: int | None = None,
    title: str | None = None,
    mode: str | None = None,
    prompt: str | None = None,
    thinking: str | None = None,
    action: str = "save",
    request_id: str | None = None,
    note_id: str | None = None,
):
    owner(ctx)
    old = await load(ctx, desk_id, expected_revision) if desk_id else None
    if old and expected_revision is None:
        conflict()
    if action == "start":
        if old or mode not in {"history", "collision"}:
            raise ValueError("choose how to start the desk")
        if title is None:
            return output(
                text(
                    tr(
                        "Name your timeline. You can choose notes next."
                        if mode == "history"
                        else "Name your desk. You can choose notes and write an idea next."
                    )
                ),
                titled_form(
                    "Create desk",
                    {
                        "title": title_field(),
                        "request_id": field(tr("Creation request ID"), 36, str(uuid.uuid4())),
                    },
                    ["title"],
                ),
                command(
                    "desk",
                    tr("Back to choices"),
                    **({"note_id": note_id} if note_id else {"action": "new"}),
                ),
            )
        action = "save"
    if action in {"details", "write", "confirm-delete"}:
        if not old:
            raise ValueError("a saved desk is required")
        args = {"desk_id": desk_id, "expected_revision": old["revision"]}
        back = command("desk", tr("Back to desk"), desk_id=desk_id)
        if action == "confirm-delete":
            return output(
                text(
                    tr(
                        "Delete this desk and its connections? Your original notes and private "
                        "copies stay in your library."
                    )
                ),
                command("edit", tr("Delete desk"), **args, action="delete"),
                back,
            )
        if action == "details" and title is None:
            return output(
                titled_form(
                    "Save details",
                    {
                        "title": title_field(old["title"]),
                        "prompt": field(tr("Question to explore (optional)"), 300, old["prompt"]),
                    },
                    ["title"],
                ),
                command("edit", tr("Delete desk…"), **args, action="confirm-delete"),
                back,
            )
        if action == "write" and thinking is None:
            history = old["mode"] == "history"
            references = [writing_reference(note) for _, note in await current(ctx, old) if note]
            field_title = tr("Your reflection" if history else "Your idea")
            prompt_title = tr(
                "What changed in your thinking as you read these notes?" if history else PROMPTS[0]
            )
            return output(
                *([{"type": "notes", "items": references[:2]}] if references else []),
                *([{"type": "markdown", "text": old["prompt"]}] if old["prompt"] else []),
                titled_form(
                    "Save reflection" if history else "Save idea",
                    {
                        "thinking": {
                            **field(field_title, 1500, old["thinking"]),
                            "description": prompt_title,
                        }
                    },
                ),
                *([{"type": "notes", "items": references[2:]}] if len(references) > 2 else []),
                *(
                    []
                    if history
                    else [
                        {
                            "type": "markdown",
                            "text": "### "
                            + tr("Creative prompts")
                            + "\n\n"
                            + "\n".join(f"- {tr(question)}" for question in PROMPTS[1:]),
                        }
                    ]
                ),
                back,
            )
        changes = (
            {
                "title": string(title, 80, required=True),
                "prompt": string(prompt, 300) if prompt is not None else old["prompt"],
            }
            if action == "details"
            else {"thinking": string(thinking, 1500)}
        )
        return await save(ctx, desk_id, old, {**old, **changes})
    if action not in {"save", "delete", "snapshot"}:
        raise ValueError("unknown thinking desk action")
    if action == "delete":
        if not old:
            raise ValueError("a saved desk is required")
        await ctx.storage.records.compare_and_set(COLLECTION, desk_id, old, TOMBSTONE) or conflict()
        return output(
            text(tr("Desk deleted. Original notes and saved copies remain intact.")),
            command("desk", tr("All thinking desks")),
        )
    if action == "snapshot":
        if not old:
            raise ValueError("a saved desk is required")
        if old["mode"] == "collision" and (len(old["sources"]) < 2 or not old["thinking"].strip()):
            raise ValueError(
                tr("Choose at least two notes and write your new idea before saving a collision")
            )
        states = await current(ctx, old)
        if any(source_status(baseline, note) != "current" for baseline, note in states):
            conflict()
        note = await ctx.notes.create(
            summary(old, states),
            private=True,
            source_versions={b["id"]: b["version"] for b, _ in states} or None,
            request_id=f"desk:{desk_id}:{old['revision']}",
        )
        return output(
            text(tr("Private note saved. Open it below or return to your desk to keep working.")),
            {"type": "notes", "ids": [note["id"]]},
            command("desk", tr("Back to desk"), desk_id=desk_id),
            command("desk", tr("All thinking desks")),
        )
    if title is None:
        data = old or {}
        return output(
            text(
                tr(
                    "History follows selected notes in creation order. Collision: choose two to "
                    "five notes from different subjects and develop your own idea. No AI is used"
                    " and original notes never change."
                )
            ),
            form(
                {
                    "title": {
                        **field(
                            tr("Topic or desk title"),
                            80,
                            data.get("title", tr("New thinking desk")),
                        ),
                        "minLength": 1,
                    },
                    "mode": {
                        "type": "string",
                        "title": tr("Desk type"),
                        "enum": [data["mode"]] if old else ["history", "collision"],
                        "default": data.get("mode", "history"),
                    },
                    "prompt": field(
                        tr("Problem or question (optional)"), 300, data.get("prompt", "")
                    ),
                    "thinking": field(
                        tr("Your reflection or new idea"), 1500, data.get("thinking", "")
                    ),
                    "action": {
                        "type": "string",
                        "title": tr("Action"),
                        "enum": ["save", "delete"] if old else ["save"],
                        "default": "save",
                    },
                    **(
                        {"request_id": field(tr("Creation request ID"), 36, str(uuid.uuid4()))}
                        if not old
                        else {}
                    ),
                },
                ["title"],
            ),
        )
    if mode is None:
        mode = old["mode"] if old else "history"
    if mode not in {"history", "collision"} or (old and mode != old["mode"]):
        raise ValueError("invalid desk type")
    data = {
        "title": string(title, 80, required=True),
        "mode": mode,
        "prompt": string(prompt if prompt is not None else old["prompt"] if old else "", 300),
        "thinking": string(
            thinking if thinking is not None else old["thinking"] if old else "", 1500
        ),
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
    picker: bool = False,
    add_id: str | None = None,
    add_version: int | None = None,
):
    direct = picker_selection(picker, add_id, add_version)
    if action == "connect":
        if picker:
            raise ValueError("choose notes before connecting them")
        return await relate(ctx, desk_id, expected_revision, from_note, to_note, kind, disconnect)
    if action != "choose":
        raise ValueError("unknown source action")
    owner(ctx)
    if picker:
        revision(expected_revision)
        if remove is None:
            remove = NONE
    old = await load(ctx, desk_id, expected_revision)
    query = string(query, 200)
    if (
        type(refresh) is not bool
        or sum([direct is not None, add != NONE, remove != NONE, refresh]) > 1
    ):
        raise ValueError("choose one source action")
    selected = list(old["sources"])
    states = await current(ctx, old)
    source_versions = None
    if direct is not None or add != NONE:
        if direct is None:
            if not source_choices or len(source_choices) > 5 or not isinstance(add, str):
                raise ValueError("search again before selecting a source")
            note_id, version = selected_choice(add, source_choices).split("@")
        else:
            note_id, version = direct
        note = await ctx.notes.get(identifier(note_id))
        if note is None or str(note["version"]) != str(version):
            conflict()
        maximum = 5 if old["mode"] == "collision" else 10
        if len(selected) >= maximum or any(b["id"] == note_id for b in selected):
            raise ValueError("source already selected or desk source limit reached")
        selected.append({**source(note), "created_at": note["created_at"]})
        if picker:
            source_versions = {note["id"]: note["version"]}
    elif remove != NONE:
        if picker and isinstance(remove, str) and re.match(r"[1-9][0-9]?\. ", remove):
            remove = selected_choice(remove, [item["id"] for item in selected])
        elif remove in (captions := source_captions(states)):
            remove = selected[captions.index(remove)]["id"]
        identifier(remove)
        if remove not in {b["id"] for b in selected}:
            raise ValueError("unknown source")
        selected = [b for b in selected if b["id"] != remove]
    elif refresh:
        selected = []
        for _baseline, note in states:
            if note is None:
                conflict()
            selected.append({**source(note), "created_at": note["created_at"]})
        if picker and selected:
            source_versions = {item["id"]: item["version"] for item in selected}
    if direct is not None or add != NONE or remove != NONE or refresh:
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
            picker=picker,
            source_versions=source_versions,
            picker_feedback=(
                tr("Note added. {count} selected.", count=len(selected))
                if direct is not None
                else tr("Desk notes updated.")
            ),
        )
    return await render_sources(ctx, desk_id, old, states, query=query, picker=picker)


def source_captions(states):
    return [
        caption(note, i) if note else f"{i}. {tr('Unavailable note')}"
        for i, (_, note) in enumerate(states, 1)
    ]


async def render_sources(ctx, desk_id, data, states, *, query="", picker=False, feedback=None):
    actions = {
        "remove": {
            "type": "string",
            "title": tr("Remove source"),
            "enum": [NONE, *source_captions(states)],
            "default": NONE,
        },
        "refresh": {
            "type": "boolean",
            "title": tr("Use the updated versions"),
            "default": False,
        },
    }
    back = command("desk", tr("Back to desk"), desk_id=desk_id)
    if picker:
        ids = [note["id"] for _, note in states if note]
        ready = len(ids) >= (2 if data["mode"] == "collision" else 1)
        return output(
            text(
                feedback or tr("Search for a note and add it. Each addition is saved to the desk.")
            ),
            *([back] if ready else []),
            *([{"type": "notes", "ids": ids}] if ids else []),
            *(
                [text(tr("Removing a note also removes its connections."))]
                if data["relations"]
                else []
            ),
            titled_form(
                "Update notes",
                {
                    **({"remove": actions["remove"]} if states else {}),
                    **(
                        {"refresh": actions["refresh"]}
                        if any(
                            source_status(baseline, note) == "changed — review sources"
                            for baseline, note in states
                        )
                        else {}
                    ),
                    **picker_fields(data, 5 if data["mode"] == "collision" else 10),
                },
            ),
            *([] if ready else [back]),
        )
    hits = (
        (await ctx.notes.search(query, limit=5, mode="keyword"))["hits"]
        if query
        else (await ctx.notes.query(limit=5, machine=True))["notes"]
    )
    hits = [n for n in hits if n["id"] not in {b["id"] for b in data["sources"]}]
    return output(
        text(
            tr(
                "Search, then add one note. Removing a source also removes its connections. "
                "Acknowledge revisions only after reviewing the current originals. Collision"
                " works best with two to five different subjects."
            )
        ),
        {"type": "notes", "ids": [n["id"] for n in hits]},
        form(
            {
                "query": field(tr("Search notes (blank browses recent)"), 200, query),
                "add": {
                    "type": "string",
                    "title": tr("Add source"),
                    "enum": [NONE, *[caption(n, i) for i, n in enumerate(hits, 1)]],
                    "default": NONE,
                },
                **actions,
                "source_choices": {
                    "type": "array",
                    "items": {"type": "string", "maxLength": 60},
                    "default": [f"{n['id']}@{n['version']}" for n in hits],
                },
            }
        ),
        back,
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
    states = sorted(
        await current(ctx, old), key=lambda pair: (pair[0]["created_at"], pair[0]["id"])
    )
    ids = [baseline["id"] for baseline, _ in states]
    labels = source_captions(states)
    if from_note in labels:
        from_note = ids[labels.index(from_note)]
    if to_note in labels:
        to_note = ids[labels.index(to_note)]
    if from_note is None:
        if len(ids) < 2:
            return output(
                text(tr("Select at least two source notes first.")),
                command(
                    "sources",
                    tr("Choose sources"),
                    desk_id=desk_id,
                    expected_revision=old["revision"],
                ),
            )
        return output(
            text(
                tr(
                    "The first note builds on, challenges, or replaces the second. This records "
                    "your interpretation; neither note is edited. Choose the same connection "
                    "with Remove to undo it."
                )
            ),
            {"type": "notes", "ids": ids},
            titled_form(
                "Save connection",
                {
                    "from_note": {"type": "string", "title": tr("First note"), "enum": labels},
                    "kind": {
                        "type": "string",
                        "title": tr("Connection"),
                        "enum": RELATIONS,
                        "default": "builds-on",
                    },
                    "to_note": {"type": "string", "title": tr("Second note"), "enum": labels},
                    "disconnect": {
                        "type": "boolean",
                        "title": tr("Remove this connection"),
                        "default": False,
                    },
                },
                ["from_note", "to_note"],
            ),
            command("desk", tr("Back to desk"), desk_id=desk_id),
        )
    if (
        from_note not in ids
        or to_note not in ids
        or from_note == to_note
        or kind not in RELATIONS
        or type(remove) is not bool
    ):
        raise ValueError("choose two different selected notes and a supported connection")
    if any(source_status(baseline, note) != "current" for baseline, note in states):
        conflict()
    link = {"from": from_note, "to": to_note, "kind": kind}
    links = [r for r in old["relations"] if r != link]
    if not remove:
        if len(links) >= 20:
            raise ValueError("a history supports at most twenty connections")
        links.append(link)
    return await save(ctx, desk_id, old, {**old, "relations": links})
