"""Probes for orchestrators and monitors (02 §15)."""

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from spunto.api.deps import ServicesDep
from spunto.store.migrations import LATEST

router = APIRouter()


@router.get("/healthz")
async def healthz() -> dict[str, str]:
    """The process is alive."""
    return {"status": "ok"}


@router.get("/readyz")
async def readyz(services: ServicesDep) -> JSONResponse:
    """The database answers and holds the schema this release expects."""
    try:
        async with services.store.read() as conn:
            rows = await conn.execute_fetchall("PRAGMA user_version")
        ready = rows[0][0] == LATEST
    except Exception:
        ready = False
    return JSONResponse({"status": "ready" if ready else "not ready"}, 200 if ready else 503)
