"""Include the bundled journal's membership in an owner note's create transaction."""

import json
from datetime import date

from pydantic import BaseModel, ConfigDict, Field, field_validator

from spunto.api.errors import Problem
from spunto.core.notes import sequence
from spunto.host.manifest import ManifestError
from spunto.host.vocabulary import validate_annotation

JOURNAL = "decision-journal"


class DecisionJournalCapture(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    rationale: str = Field(default_factory=str, max_length=700)
    review_on: str = Field(default_factory=str, max_length=10)

    @field_validator("review_on")
    @classmethod
    def valid_date(cls, value):
        if value and date.fromisoformat(value).isoformat() != value:
            raise ValueError("Use a review date in YYYY-MM-DD format.")
        return value

    @field_validator("rationale")
    @classmethod
    def valid_rationale(cls, value):
        if any(ord(char) < 32 and char not in "\n\t\r" for char in value):
            raise ValueError("Unexpected control character in the rationale.")
        return value


def journal_panel(note_id, rationale=""):
    # Commands are localized by ID even when offline. Text components allow 280 characters.
    items = [
        {
            "type": "command",
            "id": "journal",
            "title": "Open decision journal",
            "args": {"note_id": note_id},
        }
    ]
    items.extend(
        {"type": "text", "text": rationale[index : index + 280]}
        for index in range(0, len(rationale), 280)
    )
    return {"ui": 1, "items": items}


async def register_capture(conn, row, fields, domain):
    if not domain.principal.owner or domain.principal.workspace_role != "owner":
        raise Problem(403, "owner_required", "Only the space owner can record a decision.")
    host = getattr(domain.store, "plugin_host", None)
    if host is None or host.catalog_metadata.get(JOURNAL, {}).get("origin") != "bundled":
        raise Problem(409, "plugin_disabled", "Enable Decision journal to record a decision.")
    active = await conn.execute_fetchall(
        "SELECT granted FROM plugin WHERE id=? AND state='enabled' AND switch='on'", (JOURNAL,)
    )
    if not active or not {"notes.read", "notes.create", "annotate"} <= set(
        json.loads(active[0]["granted"])
    ):
        raise Problem(409, "plugin_disabled", "Enable Decision journal to record a decision.")
    note_id = row["id"]
    state = {
        "id": f"capture:{note_id}",
        "original": {
            "source_id": None,
            "private": bool(row["private"]),
            "created_ms": row["created_at"],
        },
        "initial_review_date": fields.review_on,
        "note_id": note_id,
        "version": 1,
        "status": "open",
        "review_date": fields.review_on,
        "pending": None,
        "latest": None,
        "capture_rationale": fields.rationale,
    }
    await conn.execute(
        "INSERT INTO plugin_record(plugin_id,collection,id,note_id,data,updated_at) "
        "VALUES(?,'decisions',?,?,?,?)",
        (JOURNAL, state["id"], note_id, json.dumps(state), row["updated_at"]),
    )
    for kind, payload in (
        ("member:journal", {}),
        ("panel", journal_panel(note_id, fields.rationale)),
    ):
        try:
            validate_annotation(kind, payload)
        except ManifestError as exc:
            raise Problem(422, "validation_failed", str(exc)) from exc
        await conn.execute(
            "INSERT INTO annotation VALUES (?,?,?,?,?,?)",
            (JOURNAL, note_id, kind, json.dumps(payload), await sequence(conn), row["updated_at"]),
        )
