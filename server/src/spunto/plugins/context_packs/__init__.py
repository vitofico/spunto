"""Manual project briefs, with explicit revision baselines and live visibility guards."""

import asyncio
import hashlib
import json
import re
import uuid

from spunto.api.errors import Problem
from spunto.host import ContextError, Ctx, Plugin

plugin = Plugin("context-packs")
COLLECTION = "packs"
SECTIONS = {
    "goals": "Goals",
    "constraints": "Constraints",
    "decisions": "Decisions",
    "questions": "Open questions",
}
NONE = "None"


def text(value):
    return {"type": "text", "text": value}


def command(name, title, **args):
    return {"type": "command", "id": name, "title": title, "args": args}


def output(*items):
    return {"ui": 1, "items": list(items)}


def owner(ctx):
    ctx.check_write()
    if ctx.principal is not None and not ctx.principal.owner:
        raise ContextError("owner_required")


def string(value, maximum, *, required=False):
    if not isinstance(value, str) or len(value) > maximum or (required and not value.strip()):
        raise ValueError("invalid context pack text")
    return value.strip()


def identifier(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError("invalid context pack identifier")
    return value


def revision(value):
    if type(value) is not int or value < 1:
        raise ValueError("a positive pack revision is required")
    return value


def conflict():
    raise Problem(409, "version_conflict", "This pack changed. Reopen it before saving.")


async def record(ctx, pack_id, expected=None):
    pack = await ctx.storage.records.get(COLLECTION, identifier(pack_id))
    if pack is None or pack.get("deleted"):
        raise Problem(404, "not_found", "That context pack is unavailable.")
    if expected is not None and revision(expected) != pack["revision"]:
        conflict()
    return pack


def source(note):
    # No source body, title or excerpt is cached in the pack.
    return {
        "id": note["id"],
        "version": note["version"],
        "private": bool(note["private"]),
        "external": note["origin"] == "external",
    }


async def provenance(ctx, pack):
    principal = ctx.principal
    restricted = principal is not None and not principal.owner
    if restricted and pack["private"] and not principal.private_ok:
        return None
    if restricted and pack.get("external", False) and not principal.external_ok:
        return None
    result = []
    for baseline in pack["sources"]:
        if restricted and (
            (baseline["private"] and not principal.private_ok)
            or (baseline["external"] and not principal.external_ok)
        ):
            return None
        note = await ctx.notes.get(baseline["id"])
        if note is None:
            if restricted:
                return None
            result.append({**baseline, "current_version": None, "status": "unavailable"})
        else:
            changed = source(note) != baseline
            result.append(
                {
                    **baseline,
                    "current_version": note["version"],
                    "status": "changed" if changed else "current",
                    "current_private": bool(note["private"]),
                    "current_external": note["origin"] == "external",
                }
            )
    return result


def markdown(pack, states):
    lines = [
        f"# {pack['title']}",
        f"Project: {pack['project'] or 'Unassigned'}",
        f"Pack revision: {pack['revision']}",
    ]
    if pack["private"] or pack.get("external", False):
        labels = [
            label
            for flag, label in (("private", "private"), ("external", "contains external material"))
            if pack.get(flag, False)
        ]
        lines.append("Classification: " + ", ".join(labels))
    if any(item["status"] != "current" for item in states):
        lines.append(
            "Source revisions need review; this brief has not been refreshed automatically."
        )
    for key, title in SECTIONS.items():
        lines.extend([f"\n## {title}", pack[key] or "Not recorded."])
    lines.append("\n## Sources")
    for item in states:
        current = (
            str(item["current_version"]) if item["current_version"] is not None else "unavailable"
        )
        lines.append(
            f"- [Note](/n/{item['id']}) · baseline {item['version']}"
            f" · current {current} · {item['status']}"
            + (" · private" if item.get("current_private", item["private"]) else "")
            + (" · external" if item.get("current_external", item["external"]) else "")
        )
    if not states:
        lines.append("No source notes selected.")
    result = "\n\n".join(lines)
    if len(result) > 4000:
        raise ValueError("context pack exceeds the 4000-character brief budget")
    return result


async def render(ctx, pack_id):
    pack = await record(ctx, pack_id)
    states = await provenance(ctx, pack)
    if states is None:
        return text("That context pack is unavailable.")
    args = {"pack_id": pack_id, "expected_revision": pack["revision"]}
    return output(
        {"type": "markdown", "text": markdown(pack, states)},
        command("edit", "Edit brief", **args),
        command("sources", "Review sources", **args),
        command("edit", "Keep private note copy", **args, action="save-note"),
        command("packs", "All context packs"),
    )


def form(properties, required=()):
    return {
        "type": "form",
        "schema": {
            "type": "object",
            "additionalProperties": False,
            "properties": properties,
            "required": list(required),
        },
    }


def field(title, maximum, default=""):
    return {"type": "string", "title": title, "maxLength": maximum, "default": default}


def edit_form(pack=None, note_id=None):
    pack = pack or {}
    props = {
        "title": {**field("Brief title", 80, pack.get("title", "")), "minLength": 1},
        "project": field("Project", 80, pack.get("project", "")),
        **{key: field(title, 500, pack.get(key, "")) for key, title in SECTIONS.items()},
        "private": {
            "type": "boolean",
            "title": "Private pack (only private-enabled agents can read)",
            "default": pack.get("private", False),
        },
        "external": {
            "type": "boolean",
            "title": "Contains external material (hide from agents excluding external notes)",
            "default": pack.get("external", False),
        },
        "action": {
            "type": "string",
            "title": "Action",
            "enum": ["save", "delete"] if pack else ["save"],
            "default": "save",
        },
    }
    if not pack:
        props["title"].pop("default")
        props["request_id"] = field("Creation request ID", 36, str(uuid.uuid4()))
    if note_id:
        props["note_id"] = {**field("Initial source note", 36, note_id), "enum": [note_id]}
    return output(
        text(
            "Curate your own brief. Source bodies are never copied automatically. "
            "Commands need a connection; private note copies work offline. "
            "Review content classifications explicitly before clearing them."
        ),
        form(props, ["title", *(["request_id"] if not pack else [])]),
    )


@plugin.command("packs")
async def packs(
    ctx: Ctx, pack_id: str | None = None, cursor: str | None = None, note_id: str | None = None
):
    owner(ctx)
    if note_id is not None:
        note = await ctx.notes.get(identifier(note_id))
        if note is None:
            return text("That source note is unavailable.")
        return output(
            text("Start a project brief with this note as its first tracked source."),
            command("edit", "New context pack", note_id=note_id),
            command("packs", "All context packs"),
        )
    if pack_id is not None:
        return await render(ctx, pack_id)
    page = await ctx.storage.records.list(COLLECTION, limit=7, after=cursor)
    items = [text("Saved context packs"), command("edit", "New context pack")]
    for row in page[:6]:
        if row["data"].get("deleted"):
            continue
        label = row["data"]["title"]
        items.append(
            command("packs", label if len(label) <= 24 else label[:23] + "…", pack_id=row["id"])
        )
    if len(page) > 6:
        items.append(command("packs", "Next packs", cursor=page[5]["id"]))
    return output(*items)


@plugin.command("edit")
async def edit(
    ctx: Ctx,
    pack_id: str | None = None,
    expected_revision: int | None = None,
    title: str | None = None,
    project: str = "",
    goals: str = "",
    constraints: str = "",
    decisions: str = "",
    questions: str = "",
    private: bool = False,
    external: bool = False,
    action: str = "save",
    note_id: str | None = None,
    request_id: str | None = None,
):
    owner(ctx)
    if action not in {"save", "delete", "save-note"}:
        raise ValueError("unknown context pack action")
    old = await record(ctx, pack_id, expected_revision) if pack_id else None
    if old is not None and expected_revision is None:
        raise ValueError("pack revision is required")
    if action == "delete":
        # Keep only a digest receipt so a lost creation response cannot recreate
        # a subsequently deleted brief. Curated prose is erased from this record.
        tombstone = {"deleted": True, "creation_digest": old["creation_digest"]} if old else None
        if old is None or not await ctx.storage.records.compare_and_set(
            COLLECTION, pack_id, old, tombstone
        ):
            conflict()
        return text("Context pack deleted. Any note copies remain ordinary private notes.")
    if action == "save-note":
        if old is None:
            raise ValueError("a saved pack is required")
        states = await provenance(ctx, old)
        if states is None or any(item["status"] != "current" for item in states):
            raise Problem(
                409, "version_conflict", "Review source changes before keeping a note copy."
            )
        note = await ctx.notes.create(
            markdown(old, states),
            private=True,
            source_versions={item["id"]: item["version"] for item in states} or None,
            request_id=f"pack:{pack_id}:{old['revision']}",
        )
        if await ctx.notes.get(note["id"]) is None:
            return text("That private note copy is unavailable. It will not be recreated.")
        return output(
            text(
                "A private note copy was kept. It is a snapshot; "
                "future pack changes do not update it."
            ),
            {"type": "notes", "ids": [note["id"]]},
        )
    if title is None:
        return edit_form(old, note_id)
    if type(private) is not bool or type(external) is not bool:
        raise ValueError("content classifications must be boolean")
    if old is None and expected_revision is not None:
        raise ValueError("new packs do not have a revision")
    intent = {
        "title": string(title, 80, required=True),
        "project": string(project, 80),
        **{
            key: string(value, 500)
            for key, value in {
                "goals": goals,
                "constraints": constraints,
                "decisions": decisions,
                "questions": questions,
            }.items()
        },
        "private": private,
        "external": external,
        "note_id": identifier(note_id) if note_id is not None else None,
    }
    digest = hashlib.sha256(
        json.dumps(intent, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()
    ).hexdigest()
    if old is None:
        pack_id = identifier(request_id) if request_id is not None else str(uuid.uuid4())
        prior = await ctx.storage.records.get(COLLECTION, pack_id)
        if prior is not None:
            if prior["creation_digest"] != digest:
                conflict()
            if prior.get("deleted"):
                return text("That context pack was deleted. Its creation will not be repeated.")
            return await render(ctx, pack_id)
    elif request_id is not None:
        raise ValueError("creation request IDs are only for new packs")
    sources = old["sources"] if old else []
    if note_id is not None:
        if old:
            raise ValueError("add sources through Review sources")
        note = await ctx.notes.get(identifier(note_id))
        if note is None:
            raise ValueError("source unavailable")
        sources = [source(note)]
    data = {
        **{
            key: value
            for key, value in intent.items()
            if key not in {"note_id", "private", "external"}
        },
        "private": private or any(item["private"] for item in sources),
        "external": external or any(item["external"] for item in sources),
        "sources": sources,
        "revision": old["revision"] + 1 if old else 1,
        "created_at": old["created_at"] if old else ctx.clock(),
        "updated_at": ctx.clock(),
        "creation_digest": old["creation_digest"] if old else digest,
    }
    states = await provenance(ctx, data)
    data["private"] |= any(item.get("current_private", False) for item in states)
    data["external"] |= any(item.get("current_external", False) for item in states)
    markdown(data, states)
    if not await ctx.storage.records.compare_and_set(COLLECTION, pack_id, old, data):
        if old is None:
            prior = await ctx.storage.records.get(COLLECTION, pack_id)
            if prior is not None and prior["creation_digest"] == digest:
                if prior.get("deleted"):
                    return text("That context pack was deleted. Its creation will not be repeated.")
                return await render(ctx, pack_id)
        conflict()
    return await render(ctx, pack_id)


def choice(note):
    # A short chooser caption is not stored or used as source content.
    caption = re.sub(r"\s+", " ", note.get("title") or note["body"]).strip()
    return f"{note['id']}@{note['version']} — {caption[:60]}"


def caption(note, index):
    title = re.sub(r"\s+", " ", note.get("title") or note["body"]).strip()
    return f"{index}. {title[:60] or 'Untitled note'}"


def mappings(values, maximum):
    if values is None:
        return []
    if not isinstance(values, list) or len(values) > maximum:
        raise ValueError("invalid source choices")
    result = []
    for value in values:
        if not isinstance(value, str) or len(value) > 60:
            raise ValueError("invalid source choice")
        match = re.fullmatch(r"([0-9a-f-]{36})@([1-9][0-9]{0,18})", value)
        if match is None:
            raise ValueError("invalid source choice")
        result.append((identifier(match[1]), int(match[2])))
    return result


def selected_choice(value, choices):
    match = re.fullmatch(r"([1-9][0-9]?)\. .{1,60}", value)
    if match is None or int(match[1]) > len(choices):
        raise ValueError("invalid source selection")
    return choices[int(match[1]) - 1]


@plugin.command("sources")
async def sources(
    ctx: Ctx,
    pack_id: str,
    expected_revision: int,
    query: str = "",
    add: str = NONE,
    remove: str = NONE,
    refresh: bool = False,
    source_choices: list[str] | None = None,
    removal_choices: list[str] | None = None,
):
    owner(ctx)
    old = await record(ctx, pack_id, expected_revision)
    query = string(query, 200)
    if (
        not isinstance(add, str)
        or len(add) > 160
        or not isinstance(remove, str)
        or len(remove) > 128
        or type(refresh) is not bool
    ):
        raise ValueError("invalid source selection")
    if int(add != NONE) + int(remove != NONE) + int(refresh) > 1:
        raise ValueError("choose one source action at a time")
    additions = mappings(source_choices, 5)
    removals = mappings(removal_choices, 10)
    selected = list(old["sources"])
    private, external = old["private"], old.get("external", False)
    if add != NONE:
        match = re.fullmatch(r"([0-9a-f-]{36})@([1-9][0-9]*) — .{0,60}", add)
        source_id, source_version = (
            (identifier(match[1]), int(match[2]))
            if match is not None
            else selected_choice(add, additions)
        )
        note = await ctx.notes.get(source_id)
        if note is None or note["version"] != source_version:
            raise Problem(
                409, "version_conflict", "That source changed. Search again before adding it."
            )
        if any(item["id"] == note["id"] for item in selected):
            raise ValueError("source already selected")
        if len(selected) == 10:
            raise ValueError("context packs support at most ten source notes")
        selected.append(source(note))
        private |= bool(note["private"])
        external |= note["origin"] == "external"
    elif remove != NONE:
        if removals:
            remove, baseline_version = selected_choice(remove, removals)
        else:
            remove, baseline_version = identifier(remove), None
        if remove not in {item["id"] for item in selected}:
            raise ValueError("unknown pack source")
        removed = next(item for item in selected if item["id"] == remove)
        if baseline_version is not None and removed["version"] != baseline_version:
            raise ValueError("invalid source removal choice")
        current = await ctx.notes.get(remove)
        # Detaching a source never implicitly publishes its old curated summary.
        private |= removed["private"] or current is None or bool(current["private"])
        external |= removed["external"] or current is None or current["origin"] == "external"
        selected = [item for item in selected if item["id"] != remove]
    elif refresh:
        selected = []
        for baseline in old["sources"]:
            note = await ctx.notes.get(baseline["id"])
            if note is None:
                raise Problem(
                    409,
                    "version_conflict",
                    "Remove unavailable sources before refreshing baselines.",
                )
            selected.append(source(note))
            private |= baseline["private"] or bool(note["private"])
            external |= baseline["external"] or note["origin"] == "external"
    if add != NONE or remove != NONE or refresh:
        new = {
            **old,
            "sources": selected,
            "private": private,
            "external": external,
            "revision": old["revision"] + 1,
            "updated_at": ctx.clock(),
        }
        states = await provenance(ctx, new)
        markdown(new, states)
        if not await ctx.storage.records.compare_and_set(COLLECTION, pack_id, old, new):
            conflict()
        return await render(ctx, pack_id)
    hits = (
        (await ctx.notes.search(query, limit=5))["hits"]
        if query
        else (await ctx.notes.query(limit=5, machine=True))["notes"]
    )
    candidates = [note for note in hits if note["id"] not in {item["id"] for item in selected}]
    states = await provenance(ctx, old)
    removal_captions = []
    for index, baseline in enumerate(selected, 1):
        note = await ctx.notes.get(baseline["id"])
        removal_captions.append(caption(note, index) if note else f"{index}. Unavailable source")
    statuses = (
        "; ".join(f"{index}: {item['status']}" for index, item in enumerate(states, 1))
        or "No source notes selected."
    )
    return output(
        text(
            "Search or browse notes, then choose one source action. "
            "Up to five matches are shown; refine the search for more. "
            "Refresh acknowledges revisions without rewriting your brief "
            "or clearing classifications."
        ),
        text(statuses),
        {
            "type": "notes",
            "ids": [item["id"] for item in states if item["status"] != "unavailable"],
        },
        {"type": "notes", "ids": [note["id"] for note in candidates]},
        form(
            {
                "query": field("Search source notes (blank browses recent)", 200, query),
                "add": {
                    "type": "string",
                    "title": "Add source",
                    "enum": [
                        NONE,
                        *[caption(note, index) for index, note in enumerate(candidates, 1)],
                    ],
                    "default": NONE,
                },
                "remove": {
                    "type": "string",
                    "title": "Remove source",
                    "enum": [NONE, *removal_captions],
                    "default": NONE,
                },
                "refresh": {
                    "type": "boolean",
                    "title": "Acknowledge all current source revisions",
                    "default": False,
                },
                "source_choices": {
                    "type": "array",
                    "items": {"type": "string", "maxLength": 60},
                    "default": [f"{note['id']}@{note['version']}" for note in candidates],
                },
                "removal_choices": {
                    "type": "array",
                    "items": {"type": "string", "maxLength": 60},
                    "default": [f"{item['id']}@{item['version']}" for item in selected],
                },
            }
        ),
        command("packs", "Back to brief", pack_id=pack_id),
    )


@plugin.mcp_tool("list")
async def list_packs(ctx: Ctx, cursor: str | None = None) -> dict:
    result = []
    after = cursor
    # Hidden IDs never become continuation tokens. Bound both memory and work;
    # an oversized library fails explicitly instead of returning a partial page.
    async with asyncio.timeout(5):
        while len(result) < 11:
            page = await ctx.storage.records.list(COLLECTION, limit=50, after=after)
            for row in page:
                if row["data"].get("deleted"):
                    continue
                states = await provenance(ctx, row["data"])
                if states is not None:
                    result.append(
                        {
                            "id": row["id"],
                            "title": row["data"]["title"],
                            "project": row["data"]["project"],
                            "revision": row["data"]["revision"],
                            "needs_review": any(item["status"] != "current" for item in states),
                        }
                    )
                    if len(result) == 11:
                        break
            if len(page) < 50:
                break
            after = page[-1]["id"]
    return {"packs": result[:10], "next_cursor": result[9]["id"] if len(result) > 10 else None}


@plugin.mcp_tool("get")
async def get_pack(ctx: Ctx, pack_id: str) -> dict:
    pack = await ctx.storage.records.get(COLLECTION, identifier(pack_id))
    if pack is None or pack.get("deleted"):
        return {"found": False}
    states = await provenance(ctx, pack)
    if states is None:
        return {"found": False}
    return {
        "found": True,
        "id": pack_id,
        "revision": pack["revision"],
        "markdown": markdown(pack, states),
        "sources": states,
    }
