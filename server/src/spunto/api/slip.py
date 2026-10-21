"""One visible daily slip, independent of timeline pagination."""

import json
from datetime import datetime, time, timedelta

from fastapi import APIRouter
from pydantic import BaseModel, ConfigDict, Field

from spunto.api.deps import PrincipalDep, ServicesDep
from spunto.api.errors import Problem
from spunto.core.notes import Notes, iso, sequence
from spunto.host.vocabulary import render_output
from spunto.store.db import now_ms

router = APIRouter(prefix="/api/v1", tags=["Daily slip"])


def _day(settings):
    day = datetime.fromtimestamp(now_ms() / 1000, settings.zone).date()
    start = datetime.combine(day, time.min, settings.zone)
    end = datetime.combine(day + timedelta(days=1), time.min, settings.zone)
    return day.isoformat(), int(start.timestamp() * 1000), int(end.timestamp() * 1000)


async def _current(conn, services, principal):
    day, start, end = _day(services.settings)
    hidden = await conn.execute_fetchall(
        "SELECT value FROM setting WHERE key='host:slip_dismissed'"
    )
    if hidden and json.loads(hidden[0][0]) == day:
        return None
    # An archived or trashed note has left the timeline, so its slip leaves with it.
    rows = await conn.execute_fetchall(
        "SELECT a.* FROM annotation a JOIN plugin p ON p.id=a.plugin_id "
        "JOIN note n ON n.id=a.note_id "
        "WHERE a.kind='slip' AND a.updated_at>=? AND a.updated_at<? "
        "AND n.archived_at IS NULL AND n.trashed_at IS NULL "
        "AND p.state='enabled' AND p.switch='on' ORDER BY p.position,a.plugin_id LIMIT 1",
        (start, end),
    )
    if not rows:
        return None
    row = rows[0]
    notes = Notes(services.store, principal)
    try:
        note = await notes._row(conn, row["note_id"])
    except Problem as error:
        if error.status != 404:
            raise
        return None
    payload = await notes._visible_components(conn, json.loads(row["payload"]))
    if payload is None:
        return None
    return {
        "plugin_id": row["plugin_id"],
        "note_id": row["note_id"],
        "payload": render_output(payload),
        "updated_at": iso(row["updated_at"]),
        "note": await notes._serialize(conn, note, render=True),
    }


@router.get("/slip")
async def get_slip(principal: PrincipalDep, services: ServicesDep):
    async with services.store.read() as conn:
        return {"slip": await _current(conn, services, principal)}


class Dismiss(BaseModel):
    model_config = ConfigDict(extra="forbid")
    plugin_id: str = Field(min_length=1, max_length=128)
    note_id: str = Field(min_length=1, max_length=128)


@router.post("/slip/dismiss")
async def dismiss_slip(body: Dismiss, principal: PrincipalDep, services: ServicesDep):
    if not principal.owner:
        raise Problem(403, "owner_required", "Use an owner session or device.")
    async with services.store.write() as conn:
        current = await _current(conn, services, principal)
        if current is None or (current["plugin_id"], current["note_id"]) != (
            body.plugin_id,
            body.note_id,
        ):
            raise Problem(404, "not_found", "That daily slip is unavailable.")
        await conn.execute(
            "DELETE FROM annotation WHERE plugin_id=? AND note_id=? AND kind='slip'",
            (body.plugin_id, body.note_id),
        )
        await conn.execute(
            "INSERT OR REPLACE INTO tombstone VALUES ('annotation',?,?,?)",
            (f"{body.plugin_id}/{body.note_id}/slip", await sequence(conn), now_ms()),
        )
        await conn.execute(
            "INSERT INTO setting VALUES ('host:slip_dismissed',?) ON CONFLICT(key) "
            "DO UPDATE SET value=excluded.value",
            (json.dumps(_day(services.settings)[0]),),
        )
    return {"dismissed": True}
