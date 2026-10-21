"""Owner-written decisions and append-only outcome notes, without AI or network."""

import uuid
from datetime import UTC, date, datetime

from spunto.api.errors import Problem
from spunto.host import ContextError, Ctx, Plugin

plugin = Plugin("decision-journal")
LIMITS = {"title": 80, "decision": 500, "alternatives": 500, "rationale": 700, "expected": 500}
STATUSES = ("open", "reviewed", "closed")


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
    return f"{moment.day} {moment:%B %Y, %H:%M} UTC"


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
    return Problem(409, "decision_conflict", "This decision changed. Reopen it before reviewing.")


async def available(ctx, state):
    """Stored journal content never bypasses current visibility of its ordinary notes."""
    note = await ctx.notes.get(state["note_id"]) if state.get("note_id") else None
    if note is None:
        return None
    source_id = state["original"].get("source_id")
    if source_id and await ctx.notes.get(source_id) is None:
        return None
    return note


async def entry_for_note(ctx, note_id):
    note = await ctx.notes.get(note_id)
    if note is None:
        return None
    entries = await ctx.storage.records.list("decisions", note_id=note_id, limit=1)
    if entries:
        state = entries[0]["data"]
    else:
        outcomes = await ctx.storage.records.list("outcomes", note_id=note_id, limit=1)
        state = (
            await ctx.storage.records.get("decisions", outcomes[0]["data"]["decision_id"])
            if outcomes
            else None
        )
    return state if state and await available(ctx, state) else None


def summary(state, now):
    due = state["review_date"]
    today = datetime.fromtimestamp(now / 1000, UTC).date().isoformat()
    overdue = bool(due and due <= today and state["status"] == "open")
    return (
        f"{state['status'].capitalize()} · revision {state['version']}"
        + (f" · review {due}" if due else "")
        + (" · due" if overdue else "")
    )


async def annotate(ctx, state):
    note = await available(ctx, state)
    if note is None:
        return
    await ctx.notes.annotate(note["id"], "member:journal", {})
    await ctx.notes.annotate(
        note["id"],
        "panel",
        {
            "ui": 1,
            "items": [
                text(summary(state, ctx.clock())),
                {
                    "type": "command",
                    "id": "journal",
                    "title": "Open decision",
                    "args": {"note_id": note["id"]},
                },
                {
                    "type": "command",
                    "id": "review-decision",
                    "title": "Review or reopen",
                    "args": {"note_id": note["id"]},
                },
            ],
        },
        note_version=note["version"],
    )


def original_body(original, due):
    body = (
        f"# {original['title']}\n\n"
        f"Decision recorded {stamp(original['created_ms'])}.\n\n"
        f"## Decision\n{original['decision']}\n\n"
        f"## Alternatives\n{original['alternatives']}\n\n"
        f"## Rationale\n{original['rationale']}\n\n"
        f"## Expected outcome\n{original['expected']}\n\n"
        + (f"Initial review date: {due}.\n\n" if due else "")
        + (f"[Source note](/n/{original['source_id']})\n\n" if original.get("source_id") else "")
        + "Later outcomes are separate linked notes; this original decision is retained."
    )
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
        return text("The source note is unavailable.")
    if title is None:
        properties = {
            "request_id": field("Request ID", 36, str(uuid.uuid7())),
            "title": field("Decision title", LIMITS["title"], minLength=1),
            "decision": field("What did you decide?", LIMITS["decision"], minLength=1),
            "alternatives": field("Alternatives considered", LIMITS["alternatives"], minLength=1),
            "rationale": field("Why this choice?", LIMITS["rationale"], minLength=1),
            "expected": field("Expected outcome", LIMITS["expected"], minLength=1),
            "review_on": field("Review date (YYYY-MM-DD, optional)", 10, ""),
            "private": {
                "type": "boolean",
                "title": "Private",
                "default": bool(source and source["private"]),
            },
        }
        if source:
            properties["note_id"] = field("Source note ID", 36, source["id"])
        return form(properties, ["request_id", *LIMITS, "private"])
    values = dict(
        title=title,
        decision=decision,
        alternatives=alternatives,
        rationale=rationale,
        expected=expected,
    )
    values = {key: clean(value, LIMITS[key]) for key, value in values.items()}
    if type(private) is not bool:
        raise ValueError("Private must be true or false.")
    requested_private = private
    private = private or bool(source and source["private"])
    due = review_date(review_on)
    request_id = identifier(str(uuid.uuid7()) if request_id is None else request_id)
    original = {
        **values,
        "source_id": source["id"] if source else None,
        "requested_private": requested_private,
        "private": private,
    }
    state = await ctx.storage.records.get("decisions", request_id)
    if state is None:
        state = {
            "id": request_id,
            "original": {**original, "created_ms": ctx.clock()},
            "initial_review_date": due,
            "note_id": None,
            "version": 1,
            "status": "open",
            "review_date": due,
            "pending": None,
            "latest": None,
        }
        if not await ctx.storage.records.compare_and_set("decisions", request_id, None, state):
            state = await ctx.storage.records.get("decisions", request_id)
    # Inherited source privacy can change while an interrupted creation is retried.
    # Compare the owner's input, then preserve any classification already reserved.
    if {
        key: value
        for key, value in state["original"].items()
        if key not in {"created_ms", "private"}
    } != {key: value for key, value in original.items() if key != "private"} or state[
        "initial_review_date"
    ] != due:
        raise conflict()
    if state["note_id"] is None:
        if private and not state["original"]["private"]:
            classified = {**state, "original": {**state["original"], "private": True}}
            if await ctx.storage.records.compare_and_set(
                "decisions", request_id, state, classified
            ):
                state = classified
            else:
                state = await ctx.storage.records.get("decisions", request_id)
    if state["note_id"] is None:
        created = await ctx.notes.create(
            original_body(state["original"], due),
            private=private or state["original"]["private"],
            source_versions={source["id"]: source["version"]} if source else None,
            request_id="decision:" + request_id,
        )
        completed = {**state, "note_id": created["id"]}
        if await ctx.storage.records.compare_and_set(
            "decisions", request_id, state, completed, note_id=created["id"]
        ):
            state = completed
        else:
            state = await ctx.storage.records.get("decisions", request_id)
    if await available(ctx, state) is None:
        return text("This decision is unavailable. Its original creation will not be repeated.")
    await annotate(ctx, state)
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
    state = await entry_for_note(ctx, note_id)
    if state is None:
        return text("Choose a decision or outcome note from the Decisions view.")
    if outcome is None:
        if state["pending"]:
            pending = state["pending"]
            return {
                "ui": 1,
                "items": [
                    text(
                        "A review was interrupted. Resume the same review before starting another."
                    ),
                    {
                        "type": "command",
                        "id": "review-decision",
                        "title": "Resume review",
                        "args": {
                            "note_id": state["note_id"],
                            **{
                                key: pending[key]
                                for key in (
                                    "outcome",
                                    "reflection",
                                    "status",
                                    "review_on",
                                    "base_version",
                                    "request_id",
                                )
                            },
                        },
                    },
                ],
            }
        return form(
            {
                "note_id": field("Decision note ID", 36, state["note_id"]),
                "request_id": field("Request ID", 36, str(uuid.uuid7())),
                "base_version": {
                    "type": "integer",
                    "title": "Decision revision",
                    "minimum": 1,
                    "default": state["version"],
                },
                "outcome": field("What happened, or why reopen?", 1600, minLength=1),
                "reflection": field("What did you learn? (optional)", 600, ""),
                "status": {
                    "type": "string",
                    "title": "Status (open reopens)",
                    "enum": list(STATUSES),
                    "default": "reviewed",
                },
                "review_on": field(
                    "Next review date (YYYY-MM-DD, optional)", 10, state["review_date"]
                ),
            },
            ["note_id", "request_id", "base_version", "outcome", "status"],
        )
    outcome, reflection = clean(outcome, 1600), clean(reflection, 600, optional=True)
    due = review_date(review_on)
    if status not in STATUSES or type(base_version) is not int or base_version < 1:
        raise ValueError("Choose a status and the current decision revision.")
    request_id = identifier(str(uuid.uuid7()) if request_id is None else request_id)
    event_id = state["id"] + ":" + request_id
    intent = {
        "request_id": request_id,
        "base_version": base_version,
        "outcome": outcome,
        "reflection": reflection,
        "status": status,
        "review_on": due,
    }
    prior = await ctx.storage.records.get("outcomes", event_id)
    if prior:
        if prior["decision_id"] != state["id"] or prior["intent"] != intent:
            raise conflict()
        if state["pending"] is None:
            if await ctx.notes.get(prior["note_id"]) is None:
                return text("That outcome note is unavailable. It will not be recreated.")
            await annotate(ctx, state)
            return {"type": "notes", "ids": [prior["note_id"], state["note_id"]]}
    if state["pending"] is None:
        if state["version"] != base_version:
            raise conflict()
        pending = {**intent, "created_ms": ctx.clock()}
        reserved = {**state, "pending": pending}
        if not await ctx.storage.records.compare_and_set(
            "decisions", state["id"], state, reserved, note_id=state["note_id"]
        ):
            raise conflict()
        state = reserved
    if {key: value for key, value in state["pending"].items() if key != "created_ms"} != intent:
        raise conflict()
    root = await available(ctx, state)
    if root is None:
        return text("The decision is unavailable.")
    source_id = state["original"].get("source_id")
    source = await ctx.notes.get(source_id) if source_id else None
    if source_id and source is None:
        return text("The decision source is unavailable.")
    source_versions = {root["id"]: root["version"]}
    if source:
        source_versions[source["id"]] = source["version"]
    pending = state["pending"]
    body = (
        f"# Decision review\n\n[Original decision](/n/{root['id']})\n\n"
        f"Recorded {stamp(pending['created_ms'])}. Status: {status}.\n\n"
        f"## Actual outcome / reason to reopen\n{outcome}\n\n"
        + (f"## Reflection\n{reflection}\n\n" if reflection else "")
        + (f"Next review date: {due}.\n\n" if due else "")
        + "The original decision, alternatives and rationale remain unchanged."
    )
    created = await ctx.notes.create(
        body,
        private=root["private"] or bool(source and source["private"]),
        source_versions=source_versions,
        request_id="outcome:" + event_id,
    )
    event = {
        "decision_id": state["id"],
        "note_id": created["id"],
        "intent": intent,
        "created_ms": pending["created_ms"],
        "version": base_version + 1,
    }
    if not await ctx.storage.records.compare_and_set(
        "outcomes", event_id, None, event, note_id=created["id"], key=state["id"]
    ):
        if await ctx.storage.records.get("outcomes", event_id) != event:
            raise conflict()
    completed = {
        **state,
        "pending": None,
        "version": base_version + 1,
        "status": status,
        "review_date": due,
        "latest": {"id": request_id, "note_id": created["id"]},
    }
    if not await ctx.storage.records.compare_and_set(
        "decisions", state["id"], state, completed, note_id=root["id"]
    ):
        latest = await ctx.storage.records.get("decisions", state["id"])
        if latest["latest"] != completed["latest"]:
            raise conflict()
        completed = latest
    await annotate(ctx, completed)
    if await ctx.notes.get(created["id"]) is None:
        return text("The review was recorded, but its note is now unavailable.")
    return {"type": "notes", "ids": [created["id"], root["id"]]}


@plugin.command("journal")
async def journal(ctx: Ctx, note_id=None, cursor=None, outcome_cursor=None):
    owner(ctx)
    if note_id is not None:
        state = await entry_for_note(ctx, note_id)
        if state is None:
            return text("This decision is unavailable.")
        items = [text(summary(state, ctx.clock())), {"type": "notes", "ids": [state["note_id"]]}]
        events = await ctx.storage.records.list(
            "outcomes", key=state["id"], after=outcome_cursor, limit=9
        )
        refs = []
        for event in events[:8]:
            event = event["data"]
            if await ctx.notes.get(event["note_id"]):
                refs.append(
                    {
                        "id": event["note_id"],
                        "reason": (
                            f"Review {event['version'] - 1}: "
                            f"{event['intent']['status']} · {stamp(event['created_ms'])}"
                        ),
                    }
                )
        if refs:
            items.append({"type": "notes", "items": refs})
        items.append(
            {
                "type": "command",
                "id": "review-decision",
                "title": "Review or reopen",
                "args": {"note_id": state["note_id"]},
            }
        )
        if len(events) > 8:
            items.append(
                {
                    "type": "command",
                    "id": "journal",
                    "title": "More outcomes",
                    "args": {"note_id": state["note_id"], "outcome_cursor": events[7]["id"]},
                }
            )
        return {"ui": 1, "items": items}
    page = await ctx.notes.query(
        view="journal", source="plugin:decision-journal", limit=10, cursor=cursor
    )
    refs = []
    for note in page["notes"]:
        state = await entry_for_note(ctx, note["id"])
        if state:
            refs.append({"id": note["id"], "reason": summary(state, ctx.clock())})
    items = (
        [{"type": "notes", "items": refs}]
        if refs
        else [text("No visible decisions on this page. Use Record decision to start one.")]
    )
    if page["next_cursor"]:
        items.append(
            {
                "type": "command",
                "id": "journal",
                "title": "More decisions",
                "args": {"cursor": page["next_cursor"]},
            }
        )
    return {"ui": 1, "items": items}
