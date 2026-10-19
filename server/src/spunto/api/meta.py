from fastapi import APIRouter

from spunto import __version__
from spunto.api.deps import ServicesDep
from spunto.auth import owner

router = APIRouter(prefix="/api/v1")


@router.get("/meta")
async def meta(services: ServicesDep) -> dict[str, object]:
    """What a client needs before signing in; nothing secret (02 §5.3).

    The M0 subset: AI capabilities, plugins_rev and the Android floor arrive
    with the features they describe.
    """
    settings = services.settings
    async with services.store.read() as conn:
        rows = await conn.execute_fetchall("SELECT value FROM meta WHERE key = 'sync_epoch'")
    return {
        "version": __version__,
        "api_version": 1,
        "public_url": settings.public_url,
        "unencrypted": not settings.secure,
        "max_upload_bytes": settings.max_upload_bytes,
        "setup_pending": await owner.password_hash(services.store) is None,
        "sync_epoch": rows[0]["value"],
        "mcp_url": f"{settings.public_url}/mcp" if settings.mcp_enabled else None,
        "ai_capabilities": services.ai.capabilities if services.ai else [],
        "plugins_rev": services.host.plugins_rev if services.host else 0,
        "min_android_build": 0,
    }
