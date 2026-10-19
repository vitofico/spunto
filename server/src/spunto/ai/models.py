"""Bounded model metadata from operator-configured origins; never download models."""

import asyncio
import json
import re

import httpx

from spunto.ai.adapters import quiet_provider_logs
from spunto.ai.errors import AIError

MODEL_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,199}\Z")
MAX_MODELS = 128
MAX_BYTES = 1_048_576


async def inventory(profile, *, transport=None):
    quiet_provider_logs()
    key = profile.api_key.get_secret_value()
    headers = (
        {"x-api-key": key, "anthropic-version": "2023-06-01"}
        if profile.provider == "anthropic"
        else {"Authorization": "Bearer " + key}
        if key
        else {}
    )
    async with httpx.AsyncClient(
        transport=transport,
        timeout=min(profile.timeout_s, 10),
        follow_redirects=False,
        headers=headers,
    ) as client:

        async def request(method, url, **kwargs):
            async with client.stream(method, url, **kwargs) as response:
                if not response.is_success:
                    raise AIError(
                        "provider", "ai_inventory_unavailable", status=response.status_code
                    )
                chunks, size = [], 0
                async for chunk in response.aiter_bytes():
                    size += len(chunk)
                    if size > MAX_BYTES:
                        raise AIError("provider", "ai_inventory_unavailable")
                    chunks.append(chunk)
                return json.loads(b"".join(chunks))

        async def native():
            root = profile.base_url.removesuffix("/v1")
            value = await request("GET", root + "/api/tags")
            rows = value.get("models")
            if not isinstance(rows, list) or len(rows) > MAX_MODELS:
                raise AIError("provider", "ai_inventory_unavailable")
            semaphore = asyncio.Semaphore(4)

            async def model(row):
                name = row.get("name") if isinstance(row, dict) else None
                if not isinstance(name, str) or not MODEL_ID.fullmatch(name):
                    raise AIError("provider", "ai_inventory_unavailable")
                capabilities = None
                try:
                    async with semaphore:
                        details = await request("POST", root + "/api/show", json={"model": name})
                    caps = details.get("capabilities")
                    if isinstance(caps, list) and all(isinstance(cap, str) for cap in caps):
                        capabilities = []
                        if "completion" in caps:
                            capabilities.append("chat")
                            if "vision" in caps:
                                capabilities.append("vision")
                        if "embedding" in caps:
                            capabilities.append("embed")
                        # Audio/vision embedding is not generative vision or transcription.
                        if "transcription" in caps:
                            capabilities.append("transcribe")
                except (
                    AIError,
                    httpx.HTTPError,
                    TimeoutError,
                    ValueError,
                    TypeError,
                    AttributeError,
                ):
                    pass  # Listed model, but compatibility metadata is unavailable.
                return {"id": name, "capabilities": capabilities}

            return await asyncio.gather(*(model(row) for row in rows))

        try:
            async with asyncio.timeout(20):
                if profile.provider == "openai" and profile.base_url.endswith("/v1"):
                    try:
                        models = await native()
                    except (
                        AIError,
                        httpx.HTTPError,
                        TimeoutError,
                        ValueError,
                        TypeError,
                        AttributeError,
                    ):
                        # A generic OpenAI proxy may reject or repurpose /api/tags.
                        # The configured standard endpoint still defines its inventory.
                        models = None
                else:
                    models = None
                if models is None:
                    path = "/v1/models?limit=128" if profile.provider == "anthropic" else "/models"
                    value = await request("GET", profile.base_url + path)
                    rows = value.get("data")
                    if (
                        not isinstance(rows, list)
                        or len(rows) > MAX_MODELS
                        or value.get("has_more")
                    ):
                        raise AIError("provider", "ai_inventory_unavailable")
                    models = []
                    for row in rows:
                        name = row.get("id") if isinstance(row, dict) else None
                        if not isinstance(name, str) or not MODEL_ID.fullmatch(name):
                            raise AIError("provider", "ai_inventory_unavailable")
                        caps = None
                        if profile.provider == "anthropic":
                            caps = ["chat"]
                            info = row.get("capabilities")
                            if isinstance(info, dict):
                                if info.get("image_input", {}).get("supported"):
                                    caps.append("vision")
                            else:
                                caps = None
                        models.append({"id": name, "capabilities": caps})
                return sorted(
                    [
                        {
                            **row,
                            "leaves_network": profile.with_model("chat", row["id"]).leaves_network(
                                "chat"
                            ),
                        }
                        for row in models
                    ],
                    key=lambda row: row["id"],
                )
        except httpx.HTTPError, TimeoutError, ValueError, TypeError, AttributeError:
            raise AIError("provider", "ai_inventory_unavailable") from None
