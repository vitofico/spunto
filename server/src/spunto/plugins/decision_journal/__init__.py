"""Owner-written decisions and append-only outcome notes, without AI or network."""

import hashlib
import html
import json
import re
import uuid
from datetime import UTC, date, datetime

from spunto.api.errors import Problem
from spunto.core.text import labels, title_of
from spunto.host import ContextError, Ctx, Plugin
from spunto.host.language import language, tr, use_language

plugin = Plugin("decision-journal")
LIMITS = {"title": 80, "decision": 500, "alternatives": 500, "rationale": 700, "expected": 500}
STATUSES = ("open", "reviewed", "closed")
REVIEW_FIELDS = ("request_id", "base_version", "outcome", "reflection", "status", "review_on")
DECISION_UNAVAILABLE = "This decision is unavailable. Its original creation will not be repeated."
OUTCOME_UNAVAILABLE = "That outcome note is unavailable. It will not be recreated."
MONTHS = (
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
)


def text(value):
    return {"type": "text", "text": value}


def owner(ctx):
    if ctx.principal is None or not ctx.principal.owner or ctx.unattended:
        raise ContextError("owner_required")


def clean(value, limit, *, optional=False):
    if not isinstance(value, str) or len(value) > limit or "\x00" in value:
        raise ValueError("Invalid decision field.")
    value = value.strip()
    if not value and not optional:
        raise ValueError("Complete the decision fields.")
    return value


def identifier(value):
    if not isinstance(value, str):
        raise ValueError("Invalid decision request ID.")
    try:
        parsed = uuid.UUID(value)
    except ValueError:
        raise ValueError("Invalid decision request ID.") from None
    if str(parsed) != value or parsed.version not in {4, 7}:
        raise ValueError("Invalid decision request ID.")
    return value


def review_date(value):
    value = clean(value, 10, optional=True)
    if value:
        try:
            parsed = date.fromisoformat(value)
        except ValueError:
            raise ValueError("Use a review date in YYYY-MM-DD format.") from None
        if parsed.isoformat() != value:
            raise ValueError("Use a review date in YYYY-MM-DD format.")
    return value


def stamp(milliseconds):
    """A date people read in a note: "21 October 2026, 14:32 UTC"."""
    moment = datetime.fromtimestamp(milliseconds / 1000, UTC)
    return tr(
        "{day} {month} {year}, {time} UTC",
        day=moment.day,
        month=tr(MONTHS[moment.month - 1]),
        year=moment.year,
        time=f"{moment:%H:%M}",
    )


def display_date(iso):
    if language() == "en":
        return iso
    day = date.fromisoformat(iso)
    return tr(
        "{day} {month} {year}",
        day=day.day,
        month=tr(MONTHS[day.month - 1]),
        year=day.year,
    )


def field(title, limit, default=None, **extra):
    result = {"type": "string", "title": title, "maxLength": limit, **extra}
    if default is not None:
        result["default"] = default
    return result


def form(properties, required):
    return {
        "type": "form",
        "schema": {
            "type": "object",
            "additionalProperties": False,
            "properties": properties,
            "required": required,
        },
    }


def conflict():
    return Problem(
        409, "decision_conflict", tr("This decision changed. Reopen it before reviewing.")
    )


async def available(ctx, state, root=None):
    """Stored journal content never bypasses current visibility of its ordinary notes."""
    if state is None or not state.get("note_id"):
        return None
    if root is None:
        root = await ctx.notes.get(state["note_id"])
    if root is None:
        return None
    source_id = state["original"].get("source_id")
    source = await ctx.notes.get(source_id) if source_id else None
    if source_id and source is None:
        return None
    return root, source


async def entry_for_note(ctx, note_id, note=None):
    if not isinstance(note_id, str):
        return None
    entries = await ctx.storage.records.list("decisions", note_id=note_id, limit=1)
    if entries:
        state = entries[0]["data"]
    else:
        outcomes = await ctx.storage.records.list("outcomes", note_id=note_id, limit=1)
        if not outcomes or (note is None and await ctx.notes.get(note_id) is None):
            return None
        state = await ctx.storage.records.get("decisions", outcomes[0]["data"]["decision_id"])
    if state:
        visible = await available(ctx, state, note if note_id == state["note_id"] else None)
        if visible:
            return state, *visible
    return None


def summary(state, now):
    due = state["review_date"]
    status = state["status"]
    if due and status != "closed":
        today = datetime.fromtimestamp(now / 1000, UTC).date().isoformat()
        date = tr("Review due {date}" if due <= today else "Review on {date}", date=due)
        return f"{tr(status.capitalize())} · {date}"
    return tr(status.capitalize())


def action(id, title, **args):
    return {"type": "command", "id": id, "title": title, "args": args}


async def annotate(ctx, note, *, decision_entry=False):
    annotations = {
        item["kind"]: item["payload"]
        for item in note["annotations"]
        if item["plugin_id"] == "decision-journal"
    }
    panel = {
        "ui": 1,
        # Persist a stable action for offline clients; clients localize by command ID.
        "items": [action("journal", "Open decision journal", note_id=note["id"])],
    }
    try:
        if decision_entry and "member:journal" not in annotations:
            await ctx.notes.annotate(note["id"], "member:journal", {})
        if annotations.get("panel") != panel:
            await ctx.notes.annotate(note["id"], "panel", panel)
    except Problem as exc:
        if exc.status != 404:
            raise


def heading(value):
    value = " ".join(value.split()) or tr("Decision")
    return re.sub(r"([\\`*_\[\]()#!~])", r"\\\1", html.escape(value, quote=False))


def recording_fingerprint(original, due):
    intent = {key: value for key, value in original.items() if key not in {"created_ms", "private"}}
    return hashlib.sha256(json.dumps([intent, due], sort_keys=True).encode()).hexdigest()


def original_body(original, due, *, untitled=False):
    decision = original["decision"]
    choice_is_title = (
        original["title"] == " ".join(decision.split()) == decision
        and title_of(decision, LIMITS["title"]) == decision
        and not labels(decision)
    )
    body = (
        f"# {heading('' if untitled else original['title'])}\n\n"
        + tr("Decision recorded {time}.", time=stamp(original["created_ms"]))
        + "\n\n"
        + ("" if choice_is_title else f"## {tr('Decision')}\n{decision}\n\n")
        + "".join(
            f"## {tr(label)}\n{original[key]}\n\n"
            for key, label in (
                ("alternatives", "Alternatives"),
                ("rationale", "Rationale"),
                ("expected", "Expected outcome"),
            )
            if original[key]
        )
        + (tr("Initial review date: {date}.", date=display_date(due)) + "\n\n" if due else "")
        + (
            f"[{tr('Source note')}](/n/{original['source_id']})\n\n"
            if original.get("source_id")
            else ""
        )
    ).rstrip()
    if len(body) > 4000:
        raise ValueError("Decision exceeds the display budget.")
    return body


@plugin.command("record-decision")
async def record(
    ctx: Ctx,
    title=None,
    decision=None,
    alternatives=None,
    rationale=None,
    expected=None,
    review_on="",
    private=False,
    note_id=None,
    request_id=None,
):
    owner(ctx)
    source = await ctx.notes.get(note_id) if note_id is not None else None
    if note_id is not None and source is None:
        return text(tr("The source note is unavailable."))
    if (
        all(
            value is None
            for value in (title, decision, alternatives, rationale, expected, request_id)
        )
        and review_on == ""
        and private is False
    ):
        properties = {
            "request_id": field(tr("Request ID"), 36, str(uuid.uuid7())),
            "decision": field(tr("What did you decide?"), LIMITS["decision"], minLength=1),
            "title": field(tr("Decision title"), LIMITS["title"]),
            "alternatives": field(tr("Alternatives considered"), LIMITS["alternatives"]),
            "rationale": field(tr("Why this choice?"), LIMITS["rationale"]),
            "expected": field(tr("Expected outcome"), LIMITS["expected"]),
            "review_on": field(tr("Review date (YYYY-MM-DD, optional)"), 10, ""),
            "private": {
                "type": "boolean",
                "title": tr("Private"),
                "default": bool(source and source["private"]),
            },
        }
        if source:
            properties["note_id"] = field(tr("Source note ID"), 36, source["id"])
        return form(properties, ["request_id", "decision"])
    values = dict(
        title=title,
        decision=decision,
        alternatives=alternatives,
        rationale=rationale,
        expected=expected,
    )
    values = {
        key: clean("" if value is None else value, LIMITS[key], optional=key != "decision")
        for key, value in values.items()
    }
    untitled = False
    if not values["title"]:
        derived = title_of(values["decision"], LIMITS["title"])
        untitled = not derived
        values["title"] = derived or "Decision"
    if type(private) is not bool:
        raise ValueError("Private must be true or false.")
    requested_private = private
    private = private or bool(source and source["private"])
    due = review_date(review_on)
    request_id = identifier(str(uuid.uuid7()) if request_id is None else request_id)
    creation_id = "decision:" + request_id
    source_versions = {source["id"]: source["version"]} if source else None
    original = {
        **values,
        "source_id": source["id"] if source else None,
        "requested_private": requested_private,
        "private": private,
    }
    fingerprint = recording_fingerprint(original, due)
    records = ctx.storage.records
    state = await records.get("decisions", request_id)
    if state is None:
        if await ctx.notes.creation_acknowledged(creation_id):
            # Creation is acknowledged even if the note and its linked state were purged.
            state = await records.get("decisions", request_id)
            if state is None:
                return text(tr(DECISION_UNAVAILABLE))
    if state is None:
        state = {
            "id": request_id,
            # An interrupted reservation can outlive a purged note. Keep no prose here.
            "original": {
                "source_id": original["source_id"],
                "private": private,
                "created_ms": ctx.clock(),
            },
            "draft_hash": fingerprint,
            "language": language(),
            "initial_review_date": due,
            "note_id": None,
            "version": 1,
            "status": "open",
            "review_date": due,
            "pending": None,
            "latest": None,
        }
        if not await records.compare_and_set(
            "decisions", request_id, None, state, source_versions=source_versions
        ):
            state = await records.get("decisions", request_id)
            if state is None:
                return text(tr(DECISION_UNAVAILABLE))
    # Inherited source privacy can change while an interrupted creation is retried.
    # Compare the owner's input, then preserve any classification already reserved.
    saved_fingerprint = state.get("draft_hash") or recording_fingerprint(
        state["original"], state["initial_review_date"]
    )
    if saved_fingerprint != fingerprint:
        raise conflict()
    if state["note_id"] is None and private and not state["original"]["private"]:
        classified = {**state, "original": {**state["original"], "private": True}}
        if not await records.compare_and_set(
            "decisions", request_id, state, classified, source_versions=source_versions
        ):
            raise conflict()
        state = classified
    if state["note_id"] is None:
        original.update(
            created_ms=state["original"]["created_ms"],
            private=state["original"]["private"],
        )
        with use_language(state.get("language", "en")):
            body = original_body(original, due, untitled=untitled)
        created = await ctx.notes.create(
            body,
            private=original["private"],
            source_versions=source_versions,
            request_id=creation_id,
        )
        completed = {**state, "note_id": created["id"], "original": original}
        completed.pop("draft_hash", None)
        if await records.compare_and_set(
            "decisions", request_id, state, completed, note_id=created["id"]
        ):
            state = completed
        else:
            if await records.compare_and_delete("decisions", request_id, state):
                return text(tr(DECISION_UNAVAILABLE))
            state = await records.get("decisions", request_id)
            if state is not None and state["note_id"] is None:
                raise conflict()
    visible = await available(ctx, state)
    if visible is None:
        return text(tr(DECISION_UNAVAILABLE))
    await annotate(ctx, visible[0], decision_entry=True)
    return {"type": "notes", "ids": [state["note_id"]]}


@plugin.command("review-decision")
async def review(
    ctx: Ctx,
    note_id: str,
    outcome=None,
    reflection="",
    status=None,
    review_on="",
    base_version=None,
    request_id=None,
):
    owner(ctx)
    entry = await entry_for_note(ctx, note_id)
    if entry is None:
        return text(tr("Choose a decision or outcome note from the Decisions view."))
    state, root, source = entry
    if outcome is None:
        if state["pending"]:
            pending = state["pending"]
            return {
                "ui": 1,
                "items": [
                    text(
                        tr(
                            "A review was interrupted. "
                            "Resume the same review before starting another."
                        )
                    ),
                    action(
                        "review-decision",
                        tr("Resume review"),
                        note_id=root["id"],
                        **{key: pending[key] for key in REVIEW_FIELDS},
                    ),
                ],
            }
        return form(
            {
                "note_id": field(tr("Decision note ID"), 36, state["note_id"]),
                "request_id": field(tr("Request ID"), 36, str(uuid.uuid7())),
                "base_version": {
                    "type": "integer",
                    "title": tr("Decision revision"),
                    "minimum": 1,
                    "default": state["version"],
                },
                "outcome": field(tr("What happened, or why reopen?"), 1600, minLength=1),
                "reflection": field(tr("What did you learn? (optional)"), 600, ""),
                "status": {
                    "type": "string",
                    "title": tr("After this review"),
                    "enum": list(STATUSES),
                    "default": "reviewed",
                },
                "review_on": field(tr("Next review date (YYYY-MM-DD, optional)"), 10, ""),
            },
            ["note_id", "request_id", "base_version", "outcome", "status"],
        )
    outcome, reflection = clean(outcome, 1600), clean(reflection, 600, optional=True)
    due = review_date(review_on)
    if status not in STATUSES or type(base_version) is not int or base_version < 1:
        raise ValueError("Choose a status and the current decision revision.")
    request_id = identifier(str(uuid.uuid7()) if request_id is None else request_id)
    event_id = state["id"] + ":" + request_id
    creation_id = "outcome:" + event_id
    intent = {
        "request_id": request_id,
        "base_version": base_version,
        "outcome": outcome,
        "reflection": reflection,
        "status": status,
        "review_on": due,
    }
    records = ctx.storage.records
    prior = await records.get("outcomes", event_id)
    if prior:
        if prior["decision_id"] != state["id"] or prior["intent"] != intent:
            raise conflict()
        if state["version"] >= prior["version"]:
            return await review_result(ctx, root, prior["note_id"])

    pending = state["pending"]
    source_versions = {root["id"]: root["version"]}
    if source:
        source_versions[source["id"]] = source["version"]
    private = root["private"] or bool(source and source["private"])
    if pending is None or {key: pending[key] for key in REVIEW_FIELDS} != intent:
        if prior is None and await ctx.notes.creation_acknowledged(creation_id):
            return text(tr(OUTCOME_UNAVAILABLE))
        if pending is not None or state["version"] != base_version:
            raise conflict()
        pending = {**intent, "created_ms": ctx.clock(), "private": private, "language": language()}
    else:
        # Earlier reservations did not save their classification. Resume
        # those privately because their sources could have been declassified.
        pending = {**pending, "private": pending.get("private", True) or private}
    if pending != state["pending"]:
        reserved = {**state, "pending": pending}
        if not await records.compare_and_set(
            "decisions",
            state["id"],
            state,
            reserved,
            note_id=root["id"],
            source_versions=source_versions,
        ):
            raise conflict()
        state = reserved
    followup = due if status != "closed" else ""
    # A linked legacy outcome can survive longer than its old creation receipt.
    if prior:
        outcome_id = prior["note_id"]
    else:
        with use_language(pending.get("language", "en")):
            reopened = status == "open"
            original_title = root["title"][: LIMITS["title"]]
            title = (
                tr("Reopened · {title}" if reopened else "Outcome · {title}", title=original_title)
                if original_title
                else tr("Decision reopened" if reopened else "Decision outcome")
            )
            section = tr("Reason to reopen" if reopened else "Outcome")
            recorded = tr("Recorded {time}.", time=stamp(pending["created_ms"]))
            body = (
                f"# {heading(title)}\n\n"
                f"[{tr('Original decision')}](/n/{root['id']})\n\n"
                f"{recorded}\n\n"
                f"## {section}\n{outcome}\n\n"
                + (f"## {tr('Reflection')}\n{reflection}\n\n" if reflection else "")
                + (tr("Next review date: {date}.", date=display_date(followup)) if followup else "")
            ).rstrip()
        created = await ctx.notes.create(
            body,
            private=pending["private"],
            source_versions=source_versions,
            request_id=creation_id,
        )
        outcome_id = created["id"]
    event = {
        "decision_id": state["id"],
        "note_id": outcome_id,
        "intent": intent,
        "created_ms": pending["created_ms"],
        "version": base_version + 1,
    }
    if not await records.compare_and_set(
        "outcomes", event_id, None, event, note_id=outcome_id, key=state["id"]
    ):
        # The acknowledged outcome might have been purged before it was linked.
        if (stored := await records.get("outcomes", event_id)) is not None and stored != event:
            raise conflict()
    completed = {
        **state,
        "pending": None,
        "version": base_version + 1,
        "status": status,
        "review_date": followup,
        "latest": {"id": request_id, "note_id": outcome_id},
    }
    if not await records.compare_and_set(
        "decisions", state["id"], state, completed, note_id=root["id"]
    ):
        latest = await records.get("decisions", state["id"])
        if latest is None:
            return text(tr("The decision is unavailable."))
        if latest["version"] < completed["version"] or (
            latest["latest"] != completed["latest"]
            and await records.get("outcomes", event_id) != event
        ):
            raise conflict()
    return await review_result(ctx, root, outcome_id)


async def review_result(ctx, root, outcome_id):
    outcome_note = await ctx.notes.get(outcome_id)
    if outcome_note is None:
        return text(tr(OUTCOME_UNAVAILABLE))
    await annotate(ctx, root, decision_entry=True)
    await annotate(ctx, outcome_note)
    return {"type": "notes", "ids": [outcome_id, root["id"]]}


@plugin.command("journal")
async def journal(ctx: Ctx, note_id=None, cursor=None, outcome_cursor=None):
    owner(ctx)
    if note_id is not None:
        entry = await entry_for_note(ctx, note_id)
        if entry is None:
            return text(tr("This decision is unavailable."))
        state, root, _ = entry
        items = [text(summary(state, ctx.clock())), {"type": "notes", "ids": [state["note_id"]]}]
        events = await ctx.storage.records.list(
            "outcomes", key=state["id"], after=outcome_cursor, limit=9
        )
        refs = []
        for event in events[:8]:
            event = event["data"]
            if event["version"] <= state["version"] and await ctx.notes.get(event["note_id"]):
                refs.append(
                    {
                        "id": event["note_id"],
                        "reason": (
                            tr(
                                "Reopened"
                                if event["intent"]["status"] == "open"
                                else event["intent"]["status"].capitalize()
                            )
                            + f" · {stamp(event['created_ms'])}"
                        ),
                    }
                )
        if refs:
            items.append({"type": "notes", "items": refs})
        items.append(
            action(
                "review-decision",
                tr("Record outcome") if state["status"] == "open" else tr("Review or reopen"),
                note_id=root["id"],
            )
        )
        if len(events) > 8:
            items.append(
                action(
                    "journal",
                    tr("More outcomes"),
                    note_id=root["id"],
                    outcome_cursor=events[7]["id"],
                )
            )
        return {"ui": 1, "items": items}
    page = await ctx.notes.query(
        view="journal", source="plugin:decision-journal", limit=10, cursor=cursor
    )
    refs = []
    for note in page["notes"]:
        entry = await entry_for_note(ctx, note["id"], note)
        if entry:
            refs.append({"id": note["id"], "reason": summary(entry[0], ctx.clock())})
    items = (
        [{"type": "notes", "items": refs}]
        if refs
        else [
            text(
                tr("No decisions on this page.")
                if cursor or page["notes"]
                else tr("No decisions yet.")
            )
        ]
    )
    if page["next_cursor"]:
        items.append(action("journal", tr("More decisions"), cursor=page["next_cursor"]))
    items.append(action("record-decision", tr("Record decision")))
    return {"ui": 1, "items": items}
