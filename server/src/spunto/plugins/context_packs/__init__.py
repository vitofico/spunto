"""Manual project briefs, with explicit revision baselines and live visibility guards."""

import asyncio
import hashlib
import json
import re
import uuid

from spunto.api.errors import Problem
from spunto.host import ContextError, Ctx, Plugin
from spunto.host.language import tr

plugin = Plugin("context-packs")
COLLECTION = "packs"
PAGE_SIZE = 3
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
    raise Problem(409, "version_conflict", tr("This pack changed. Reopen it before saving."))


async def record(ctx, pack_id, expected=None):
    pack = await ctx.storage.records.get(COLLECTION, identifier(pack_id))
    if pack is None or pack.get("deleted"):
        raise Problem(404, "not_found", tr("That context pack is unavailable."))
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


async def provenance(ctx, pack, *, owner_labels=False):
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
        proof = pack.get("intro_citations", {}).get(baseline["id"])
        note = (
            await ctx.notes.evidence(baseline["id"])
            if proof
            else await ctx.notes.get(baseline["id"])
        )
        if note is None:
            if restricted:
                return None
            result.append({**baseline, "current_version": None, "status": "unavailable"})
        else:
            changed = source(note) != baseline or (
                proof is not None and note["evidence_fingerprint"] != proof
            )
            result.append(
                {
                    **baseline,
                    "current_version": note["version"],
                    "status": "changed" if changed else "current",
                    "current_private": bool(note["private"]),
                    "current_external": note["origin"] == "external",
                }
            )
            if owner_labels and not restricted:
                # Reviewed in place only. The stored brief and exported Markdown
                # never include live source titles or excerpts.
                title = re.sub(r"[\[\]\\`*_<>&]", "", re.sub(r"\s+", " ", note.get("title") or ""))
                result[-1]["display_title"] = title.strip()[:60]
                result[-1]["ai_eligible"] = (
                    not note["private"]
                    and note["origin"] != "external"
                    and not note["machine"]
                    and not note["archived"]
                )
    return result


def markdown(pack, states, *, show_titles=False):
    lines = [
        f"# {pack['title']}",
        tr("Project: {project}", project=pack["project"] or tr("Unassigned")),
        tr("Pack revision: {revision}", revision=pack["revision"]),
    ]
    if pack["private"] or pack.get("external", False):
        labels = [
            tr(label)
            for flag, label in (("private", "private"), ("external", "contains external material"))
            if pack.get(flag, False)
        ]
        lines.append(tr("Classification: {labels}", labels=", ".join(labels)))
    if any(item["status"] != "current" for item in states):
        lines.append(
            tr("Source revisions need review; this brief has not been refreshed automatically.")
        )
    if pack.get("intro"):
        lines.append(pack["intro"])
    for key, title in SECTIONS.items():
        if pack[key]:
            lines.extend([f"\n## {tr(title)}", pack[key]])
    lines.append(f"\n## {tr('Sources')}")
    first_source = len(lines)
    for item in states:
        current = (
            str(item["current_version"])
            if item["current_version"] is not None
            else tr("unavailable")
        )
        lines.append(
            f"- [{tr('Note')}](/n/{item['id']}) · "
            + tr(
                "baseline {baseline} · current {current}", baseline=item["version"], current=current
            )
            + f" · {tr(item['status'])}"
            + (f" · {tr('private')}" if item.get("current_private", item["private"]) else "")
            + (f" · {tr('external')}" if item.get("current_external", item["external"]) else "")
        )
    if not states:
        lines.append(tr("No source notes selected."))
    result = "\n\n".join(lines)
    if len(result) > 4000:
        raise ValueError("context pack exceeds the 4000-character brief budget")
    if show_titles:
        length = len(result)
        for i, item in enumerate(states, first_source):
            title = item.get("display_title")
            if title:
                generic = f"- [{tr('Note')}]"
                named = f"- [{title}]"
                candidate_length = length + len(named) - len(generic)
                if candidate_length <= 4000:
                    lines[i] = lines[i].replace(generic, named, 1)
                    length = candidate_length
        return "\n\n".join(lines)
    return result


def editor_args(pack_id, pack):
    return {"pack_id": pack_id, "expected_revision": pack["revision"]}


def sharing(pack, states):
    if any(item["status"] == "unavailable" for item in states):
        return tr("Agent access is blocked until unavailable sources are removed.")
    private = pack["private"] or any(item.get("current_private") for item in states)
    external = pack.get("external", False) or any(item.get("current_external") for item in states)
    if private and external:
        requirement = tr("Agents need permission to read both private and external content.")
    elif private:
        requirement = tr("Agents need permission to read private content.")
    elif external:
        requirement = tr("Agents need permission to read external content.")
    else:
        requirement = tr("Agents need access to this space and all sources.")
    return requirement + " " + tr("The Context packs agent tools must also be exposed.")


async def render(ctx, pack_id, *, export=False):
    pack = await record(ctx, pack_id)
    args = editor_args(pack_id, pack)
    if pack.get("draft"):
        return output(
            {"type": "markdown", "text": f"# {pack['title']}"},
            text(tr("Draft · Finish your brief to make it available to agents.")),
            command("sources", tr("Choose sources"), **args),
            command("edit", tr("Write brief"), **args, action="finish"),
            command("edit", tr("Delete pack"), **args, action="confirm-delete"),
            command("packs", tr("All context packs")),
        )
    states = await provenance(ctx, pack, owner_labels=not export)
    if states is None:
        return text(tr("That context pack is unavailable."))
    brief = {"type": "markdown", "text": markdown(pack, states, show_titles=not export)}
    ready = all(item["status"] == "current" for item in states)
    if export:
        items = [
            brief,
            text(
                tr("Copy sends the Markdown above to your clipboard, including its source links.")
            ),
            text(sharing(pack, states)),
        ]
        if ready:
            items.append(command("edit", tr("Keep private note copy"), **args, action="save-note"))
        else:
            items.append(text(tr("Review source changes before keeping a private note copy.")))
        return output(*items, command("packs", tr("Back to brief"), pack_id=pack_id))
    review = command("sources", tr("Review sources"), **args)
    use = command("packs", tr("Use brief"), pack_id=pack_id, export=True)
    return output(
        brief,
        command("edit", tr("Edit brief"), **args, action="revise"),
        *([use, review] if ready else [review, use]),
        text(sharing(pack, states)),
        command("edit", tr("Delete pack"), **args, action="confirm-delete"),
        command("packs", tr("All context packs")),
    )


def form(properties, required=(), *, title=None):
    schema = {
        "type": "object",
        "additionalProperties": False,
        "properties": properties,
        "required": list(required),
    }
    if title:
        schema["title"] = title
    return {"type": "form", "schema": schema}


def field(title, maximum, default=""):
    return {"type": "string", "title": title, "maxLength": maximum, "default": default}


def start_form(note_id):
    props = {
        "title": {"type": "string", "title": tr("Brief title"), "minLength": 1, "maxLength": 80},
        "goals": field(tr("Goals"), 500),
        "request_id": field(tr("Creation request ID"), 36, str(uuid.uuid4())),
    }
    if note_id:
        props["note_id"] = {**field(tr("Initial source note"), 36, note_id), "enum": [note_id]}
    return output(
        text(
            tr(
                "Name the work and describe what you want to achieve. Next you can link the "
                "notes that provide context."
            )
        ),
        form(props, ["title", "request_id"], title=tr("Create draft")),
        command("packs", tr("All context packs")),
    )


def edit_form(pack=None, note_id=None, *, action="save"):
    pack = pack or {}
    props = {
        "title": {**field(tr("Brief title"), 80, pack.get("title", "")), "minLength": 1},
        "project": field(tr("Project"), 80, pack.get("project", "")),
        **{key: field(tr(title), 500, pack.get(key, "")) for key, title in SECTIONS.items()},
        "private": {
            "type": "boolean",
            "title": tr("Private pack (only private-enabled agents can read)"),
            "default": pack.get("private", False),
        },
        "external": {
            "type": "boolean",
            "title": tr("Contains external material (hide from agents excluding external notes)"),
            "default": pack.get("external", False),
        },
        "action": {
            "type": "string",
            "title": tr("Action"),
            "enum": ["save", "delete"] if pack else ["save"],
            "default": "save",
        },
    }
    if action in {"finish", "revise"}:
        del props["action"]
    if not pack:
        props["title"].pop("default")
        props["request_id"] = field(tr("Creation request ID"), 36, str(uuid.uuid4()))
    if note_id:
        props["note_id"] = {**field(tr("Initial source note"), 36, note_id), "enum": [note_id]}
    if action in {"finish", "revise"}:
        intro = (
            tr(
                "Pull together the details someone needs to continue this work. Review the "
                "access options before saving. Source notes stay linked and their text stays in "
                "the notes."
            )
            if action == "finish"
            else tr(
                "Update what you want people and agents to know. Review access options if the "
                "content has changed."
            )
        )
        return output(text(intro), form(props, ["title"], title=tr("Save brief")))
    return output(
        text(
            tr(
                "Curate your own brief. Source bodies are never copied automatically. "
                "Commands need a connection; private note copies work offline. "
                "Review content classifications explicitly before clearing them."
            )
        ),
        form(props, ["title", *(["request_id"] if not pack else [])]),
    )


@plugin.command("packs")
async def packs(
    ctx: Ctx,
    pack_id: str | None = None,
    cursor: str | None = None,
    note_id: str | None = None,
    export: bool = False,
):
    owner(ctx)
    if note_id is not None:
        note = await ctx.notes.get(identifier(note_id))
        if note is None:
            return text(tr("That source note is unavailable."))
        return output(
            text(tr("Start a project brief with this note as its first tracked source.")),
            command("edit", tr("New context pack"), action="start", note_id=note_id),
            command("packs", tr("All context packs")),
        )
    if pack_id is not None:
        return await render(ctx, pack_id, export=export)
    page, next_cursor = await page_records(ctx, cursor, limit=PAGE_SIZE)
    items = [
        text(
            tr(
                "Build a reusable handoff for a project, a task, or an agent. "
                "Write a brief, link the notes behind it, and revisit it when the work changes."
            )
        ),
        command("edit", tr("New context pack"), action="start"),
    ]
    if page:
        items.append(text(tr("Saved context packs")))
    for row in page:
        pack = row["data"]
        label = tr("Draft · {title}", title=pack["title"]) if pack.get("draft") else pack["title"]
        items.extend([text(label), command("packs", tr("Open pack"), pack_id=row["id"])])
    if next_cursor:
        items.append(command("packs", tr("Next packs"), cursor=next_cursor))
    return output(*items)


async def page_records(ctx, cursor=None, *, limit, query=""):
    # Continue older in-flight UUID pages in their original order.
    after, before = None, None
    if cursor is not None:
        if not isinstance(cursor, str):
            raise ValueError("invalid pack cursor")
        if ":" in cursor:
            if not re.fullmatch(r"[0-9]{1,19}:[0-9a-f-]{36}", cursor):
                raise ValueError("invalid pack cursor")
            updated_at, last_id = cursor.split(":")
            before = int(updated_at), identifier(last_id)
        else:
            after = identifier(cursor)
    recent = after is None
    page = []
    async with asyncio.timeout(5):
        while len(page) <= limit:
            rows = await ctx.storage.records.list(
                COLLECTION, limit=25, after=after, recent=recent, before=before
            )
            page.extend(
                row
                for row in rows
                if not row["data"].get("deleted") and query in row["data"]["title"].casefold()
            )
            if len(rows) < 25:
                break
            if recent:
                before = rows[-1]["updated_at"], rows[-1]["id"]
            else:
                after = rows[-1]["id"]
    next_cursor = None
    if len(page) > limit:
        last = page[limit - 1]
        next_cursor = f"{last['updated_at']}:{last['id']}" if recent else last["id"]
    return page[:limit], next_cursor


def for_collection(ctx):
    """Open the current first-party collection store in this caller's workspace."""
    from spunto.host import ContextError

    host = ctx._host
    origin = host.catalog_metadata.get("context-packs", {}).get("origin")
    if origin != "bundled" or host.states.get("context-packs", {}).get("state") != "enabled":
        raise ContextError("plugin_disabled")
    context = Ctx(host, "context-packs", principal=ctx.principal, operation="command:collections")
    owner(context)
    return context


async def ask_context(ctx, collection_id):
    context = for_collection(ctx)
    pack = await record(context, collection_id)
    states = await provenance(context, pack)
    if pack.get("draft"):
        raise Problem(409, "version_conflict", tr("Save the collection before using Ask."))
    versions = {}
    for item in states:
        if item["status"] == "unavailable":
            continue
        note = await ctx.notes.evidence(item["id"])
        if (
            note is not None
            and not note["private"]
            and note["origin"] != "external"
            and not note["machine"]
            and not note["archived"]
        ):
            versions[item["id"]] = note["version"]
    if not versions:
        raise Problem(409, "version_conflict", tr("Add a source eligible for AI before using Ask."))
    return {
        "id": collection_id,
        "revision": pack["revision"],
        "source_ids": list(versions),
        "source_versions": versions,
        # The scoped source list is enough for Ask. Collection prose may have
        # been written from material that the Ask provider is not allowed to see.
        "brief": "",
    }


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
    if action not in {"save", "delete", "save-note", "start", "finish", "revise", "confirm-delete"}:
        raise ValueError("unknown context pack action")
    old = await record(ctx, pack_id, expected_revision) if pack_id else None
    if old is not None and expected_revision is None:
        raise ValueError("pack revision is required")
    if action == "start" and old is not None:
        raise ValueError("start requires a new pack")
    if action == "finish" and (old is None or not old.get("draft")):
        raise ValueError("finish requires a draft pack")
    if action == "finish":
        states = await provenance(ctx, old)
        if any(item["status"] != "current" for item in states):
            message = tr(
                "Review changed sources and remove unavailable sources before saving this draft."
            )
            if title is not None:
                raise Problem(409, "version_conflict", message)
            return output(
                text(message),
                command("sources", tr("Review sources"), **editor_args(pack_id, old)),
                command("packs", tr("Back to brief"), pack_id=pack_id),
            )
    if action == "revise" and (old is None or old.get("draft")):
        raise ValueError("revise requires a finished pack")
    if action == "confirm-delete":
        if old is None:
            raise ValueError("a saved pack is required")
        args = editor_args(pack_id, old)
        return output(
            text(
                tr(
                    "Delete this pack? The source notes and any private note copies will stay "
                    "in your library."
                )
            ),
            command("edit", tr("Delete pack"), **args, action="delete"),
            command("packs", tr("Back to brief"), pack_id=pack_id),
        )
    if action == "delete":
        # Keep only a digest receipt so a lost creation response cannot recreate
        # a subsequently deleted brief. Curated prose is erased from this record.
        tombstone = {"deleted": True, "creation_digest": old["creation_digest"]} if old else None
        if old is None or not await ctx.storage.records.compare_and_set(
            COLLECTION, pack_id, old, tombstone
        ):
            conflict()
        return output(
            text(tr("Context pack deleted. Any note copies remain ordinary private notes.")),
            command("packs", tr("All context packs")),
        )
    if action == "save-note":
        if old is None or old.get("draft"):
            raise ValueError("a finished pack is required")
        states = await provenance(ctx, old)
        if states is None or any(item["status"] != "current" for item in states):
            raise Problem(
                409, "version_conflict", tr("Review source changes before keeping a note copy.")
            )
        note = await ctx.notes.create(
            markdown(old, states),
            private=True,
            source_versions={item["id"]: item["version"] for item in states} or None,
            source_fingerprints=old.get("intro_citations") or None,
            request_id=f"pack:{pack_id}:{old['revision']}",
        )
        if await ctx.notes.get(note["id"]) is None:
            return text(tr("That private note copy is unavailable. It will not be recreated."))
        return output(
            text(
                tr(
                    "A private note copy was kept. It is a snapshot; "
                    "future pack changes do not update it."
                )
            ),
            {"type": "notes", "ids": [note["id"]]},
            command("packs", tr("Back to brief"), pack_id=pack_id),
        )
    if title is None:
        if action == "start":
            if note_id is not None:
                identifier(note_id)
            return start_form(note_id)
        return edit_form(old, note_id, action=action)
    if action == "start":
        private = True
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
    if action == "start":
        intent["draft"] = True
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
                return text(tr("That context pack was deleted. Its creation will not be repeated."))
            return await render(ctx, pack_id)
    elif request_id is not None:
        raise ValueError("creation request IDs are only for new packs")
    sources = old["sources"] if old else []
    source_versions = (
        {item["id"]: item["version"] for item in sources} or None if action == "finish" else None
    )
    if note_id is not None:
        if old:
            raise ValueError("add sources through Review sources")
        note = await ctx.notes.get(identifier(note_id))
        if note is None:
            raise ValueError("source unavailable")
        sources = [source(note)]
        if action == "start":
            source_versions = {note["id"]: note["version"]}
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
    if old:
        data.update(
            {
                key: old[key]
                for key in ("intro", "pinned_source_ids", "intro_citations")
                if key in old
            }
        )
    if old and old.get("draft") and action != "finish":
        data["draft"] = True
    states = await provenance(ctx, data)
    data["private"] |= any(item.get("current_private", False) for item in states)
    data["external"] |= any(item.get("current_external", False) for item in states)
    markdown(data, states)
    if not await ctx.storage.records.compare_and_set(
        COLLECTION, pack_id, old, data, source_versions=source_versions
    ):
        if old is None:
            prior = await ctx.storage.records.get(COLLECTION, pack_id)
            if prior is not None and prior["creation_digest"] == digest:
                if prior.get("deleted"):
                    return text(
                        tr("That context pack was deleted. Its creation will not be repeated.")
                    )
                return await render(ctx, pack_id)
        conflict()
    return await render(ctx, pack_id)


def choice(note):
    # A short chooser caption is not stored or used as source content.
    caption = re.sub(r"\s+", " ", note.get("title") or note["body"]).strip()
    return f"{note['id']}@{note['version']} — {caption[:60]}"


def caption(note, index):
    title = re.sub(r"\s+", " ", note.get("title") or note["body"]).strip()
    return f"{index}. {title[:60] or tr('Untitled note')}"


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


def picker_selection(picker, add_id, add_version):
    if type(picker) is not bool:
        raise ValueError("picker must be a boolean")
    if add_id is None and add_version is None:
        return None
    if not picker:
        raise ValueError("open the source picker before adding a note")
    return identifier(add_id), revision(add_version)


def picker_fields(data, maximum):
    return {
        "expected_revision": {"type": "integer", "default": data["revision"]},
        "max_sources": {"type": "integer", "default": maximum},
        "selected_ids": {
            "type": "array",
            "items": {"type": "string", "maxLength": 36},
            "default": [item["id"] for item in data["sources"]],
        },
    }


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
    picker: bool = False,
    add_id: str | None = None,
    add_version: int | None = None,
):
    owner(ctx)
    direct = picker_selection(picker, add_id, add_version)
    if picker:
        revision(expected_revision)
        if remove is None:
            remove = NONE
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
    if sum([direct is not None, add != NONE, remove != NONE, refresh]) > 1:
        raise ValueError("choose one source action at a time")
    additions = mappings(source_choices, 5)
    removals = mappings(removal_choices, 10)
    selected = list(old["sources"])
    private, external = old["private"], old.get("external", False)
    source_versions = None
    if direct is not None or add != NONE:
        match = re.fullmatch(r"([0-9a-f-]{36})@([1-9][0-9]*) — .{0,60}", add)
        source_id, source_version = direct or (
            (identifier(match[1]), int(match[2]))
            if match is not None
            else selected_choice(add, additions)
        )
        note = await ctx.notes.get(source_id)
        if note is None or note["version"] != source_version:
            raise Problem(
                409, "version_conflict", tr("That source changed. Search again before adding it.")
            )
        if any(item["id"] == note["id"] for item in selected):
            raise ValueError("source already selected")
        if len(selected) == 10:
            raise ValueError("context packs support at most ten source notes")
        selected.append(source(note))
        source_versions = {note["id"]: note["version"]}
        private |= bool(note["private"])
        external |= note["origin"] == "external"
    elif remove != NONE:
        if removals:
            remove, baseline_version = selected_choice(remove, removals)
        elif picker and re.match(r"[1-9][0-9]?\. ", remove):
            remove, baseline_version = selected_choice(
                remove, [(item["id"], item["version"]) for item in selected]
            )
        else:
            remove, baseline_version = identifier(remove), None
        if remove in old.get("intro_citations", {}):
            raise Problem(
                422,
                "validation_failed",
                tr("Clear the sourced introduction before removing one of its sources."),
            )
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
                    tr("Remove unavailable sources before refreshing baselines."),
                )
            selected.append(source(note))
            private |= baseline["private"] or bool(note["private"])
            external |= baseline["external"] or note["origin"] == "external"
        if selected:
            source_versions = {item["id"]: item["version"] for item in selected}
    if direct is not None or add != NONE or remove != NONE or refresh:
        new = {
            **old,
            "sources": selected,
            "private": private,
            "external": external,
            "revision": old["revision"] + 1,
            "updated_at": ctx.clock(),
        }
        if "pinned_source_ids" in old:
            ids = {item["id"] for item in selected}
            new["pinned_source_ids"] = [item for item in old["pinned_source_ids"] if item in ids]
        states = await provenance(ctx, new)
        markdown(new, states)
        if not await ctx.storage.records.compare_and_set(
            COLLECTION, pack_id, old, new, source_versions=source_versions
        ):
            conflict()
        if picker:
            return await render_sources(ctx, pack_id, new, picker=True)
        return await render(ctx, pack_id)
    return await render_sources(ctx, pack_id, old, query=query, picker=picker)


async def render_sources(ctx, pack_id, data, *, query="", picker=False):
    selected = data["sources"]
    states = await provenance(ctx, data)
    removal_captions = []
    for index, baseline in enumerate(selected, 1):
        note = await ctx.notes.get(baseline["id"])
        removal_captions.append(
            caption(note, index) if note else f"{index}. {tr('Unavailable source')}"
        )
    statuses = "; ".join(
        f"{index} · {tr(item['status'])}" for index, item in enumerate(states, 1)
    ) or tr("No source notes selected.")
    actions = {
        "remove": {
            "type": "string",
            "title": tr("Remove source"),
            "enum": [NONE, *removal_captions],
            "default": NONE,
        },
        "refresh": {
            "type": "boolean",
            "title": tr("Acknowledge all current source revisions"),
            "default": False,
        },
    }
    notes = {
        "type": "notes",
        "items": [
            {"id": item["id"], "reason": tr(item["status"])}
            for item in states
            if item["status"] != "unavailable"
        ],
    }
    back = command("packs", tr("Back to brief"), pack_id=pack_id)
    continue_action = (
        [command("edit", tr("Write brief"), **editor_args(pack_id, data), action="finish")]
        if data.get("draft")
        else []
    )
    if picker:
        intro = (
            tr(
                "Link the notes that explain the work. You can add up to ten, or continue "
                "without sources."
            )
            if data.get("draft")
            else tr(
                "Open changed notes to check what is different. Acknowledge their revisions "
                "after updating the brief as needed."
            )
        )
        return output(
            text(intro),
            text(statuses),
            notes,
            form({**actions, **picker_fields(data, 10)}),
            *continue_action,
            back,
        )
    hits = (
        (await ctx.notes.search(query, limit=5, mode="keyword"))["hits"]
        if query
        else (await ctx.notes.query(limit=5, machine=True))["notes"]
    )
    candidates = [note for note in hits if note["id"] not in {item["id"] for item in selected}]
    return output(
        text(
            tr(
                "Search or browse notes, then choose one source action. "
                "Up to five matches are shown; refine the search for more. "
                "Refresh acknowledges revisions without rewriting your brief "
                "or clearing classifications."
            )
        ),
        text(statuses),
        notes,
        {"type": "notes", "ids": [note["id"] for note in candidates]},
        form(
            {
                "query": field(tr("Search source notes (blank browses recent)"), 200, query),
                "add": {
                    "type": "string",
                    "title": tr("Add source"),
                    "enum": [
                        NONE,
                        *[caption(note, index) for index, note in enumerate(candidates, 1)],
                    ],
                    "default": NONE,
                },
                **actions,
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
        *continue_action,
        back,
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
                if row["data"].get("deleted") or row["data"].get("draft"):
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
    if pack is None or pack.get("deleted") or pack.get("draft"):
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
