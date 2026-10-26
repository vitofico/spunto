import asyncio
import json

import httpx
import pytest

from spunto.ai.policy import HTTPPolicy
from spunto.ai.quota import ACTIONS
from spunto.config import load_settings
from spunto.errors import OperatorError


@pytest.mark.anyio
async def test_policy_caches_by_account_and_expires_to_operator_defaults():
    now = [1_790_000_000]
    calls = []
    state = {"status": 200}
    fallback = dict.fromkeys(ACTIONS, 1)

    def upstream(request):
        calls.append(request)
        assert request.headers["Authorization"] == "Bearer synthetic-shared-token"
        account_id = request.url.path.rsplit("/", 1)[1]
        return httpx.Response(
            state["status"],
            json={
                "version": 1,
                "account_id": account_id,
                "expires_at": now[0] + 120,
                "limits": dict.fromkeys(ACTIONS, 3 if account_id == "alice" else 1),
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(upstream)) as client:
        policy = HTTPPolicy(
            "https://policy.example.test/v1/accounts",
            "synthetic-shared-token",
            fallback,
            client=client,
            clock=lambda: now[0],
        )
        assert (await policy.limits("alice"))["ask"] == 3
        assert (await policy.limits("bob"))["ask"] == 1
        assert len(calls) == 2
        state["status"] = 503
        assert (await policy.limits("alice"))["ask"] == 3
        now[0] += 121
        assert (await policy.limits("alice"))["ask"] == 1
        assert len(calls) == 3
        assert (await policy.limits("alice"))["ask"] == 1
        assert len(calls) == 3
        await policy.close()


@pytest.mark.parametrize("malformed", ["account", "expiry", "bool", "missing", "large", "redirect"])
@pytest.mark.anyio
async def test_policy_malformed_snapshots_fall_back_without_following_redirects(malformed):
    now = 1_790_000_000
    fallback = dict.fromkeys(ACTIONS, 1)
    data = {
        "version": 1,
        "account_id": "alice",
        "expires_at": now + 120,
        "limits": dict.fromkeys(ACTIONS, 3),
    }
    if malformed == "account":
        data["account_id"] = "bob"
    elif malformed == "expiry":
        data["expires_at"] = now + 301
    elif malformed == "bool":
        data["limits"]["ask"] = True
    elif malformed == "missing":
        del data["limits"]["ask"]
    raw = b"x" * 16385 if malformed == "large" else json.dumps(data).encode()
    calls = []

    def upstream(request):
        calls.append(request)
        return httpx.Response(
            302 if malformed == "redirect" else 200,
            content=raw,
            headers={"Location": "https://attacker.test"},
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(upstream)) as client:
        policy = HTTPPolicy(
            "http://policy:8090/v1/accounts",
            "synthetic",
            fallback,
            client=client,
            clock=lambda: now,
        )
        assert await policy.limits("alice") == fallback
        assert len(calls) == 1


def test_policy_configuration_is_optional_hosted_and_requires_paired_secret(env):
    assert load_settings().ai_policy_url is None
    env(SPUNTO_AI_POLICY_URL="http://policy:8090/v1/accounts")
    with pytest.raises(OperatorError, match="Set both SPUNTO_AI_POLICY"):
        load_settings()
    env(SPUNTO_AI_POLICY_TOKEN="synthetic-long-policy-token-00000000")
    with pytest.raises(OperatorError, match="only to hosted"):
        load_settings()
    env(
        SPUNTO_DATABASE_URL="postgresql://localhost/spunto",
        SPUNTO_IDENTITY_DATABASE_URL="postgresql://localhost/spunto_identity",
    )
    assert load_settings().ai_policy_url == "http://policy:8090/v1/accounts"
    env(SPUNTO_AI_POLICY_URL="https://secret@policy.test")
    with pytest.raises(OperatorError, match="fixed trusted"):
        load_settings()


@pytest.mark.anyio
async def test_concurrent_space_requests_share_policy_fetch():
    calls = []

    async def upstream(request):
        calls.append(request)
        await asyncio.sleep(0)
        return httpx.Response(
            200,
            json={
                "version": 1,
                "account_id": "alice",
                "expires_at": 1_790_000_120,
                "limits": dict.fromkeys(ACTIONS, 3),
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(upstream)) as client:
        policy = HTTPPolicy(
            "http://policy:8090/v1/accounts",
            "synthetic",
            dict.fromkeys(ACTIONS, 1),
            client=client,
            clock=lambda: 1_790_000_000,
        )
        results = await asyncio.gather(*(policy.limits("alice") for _ in range(8)))
        assert len(calls) == 1
        assert all(result["ask"] == 3 for result in results)


@pytest.mark.anyio
async def test_hosted_transcription_measures_wav_and_refuses_unverified_formats():
    import io
    import wave
    from types import SimpleNamespace

    from spunto.ai.errors import AIError
    from spunto.ai.gateway import Gateway

    output = io.BytesIO()
    with wave.open(output, "wb") as recording:
        recording.setnchannels(1)
        recording.setsampwidth(2)
        recording.setframerate(8000)
        recording.writeframes(b"\0\0" * (8000 * 3))
    gateway = object.__new__(Gateway)
    gateway.settings = SimpleNamespace(max_upload_bytes=1000000)
    gateway.quota = SimpleNamespace(policy=object())

    async def capture(*args, **kwargs):
        return kwargs["quota_seconds"]

    gateway._call = capture
    assert await gateway.transcribe(output.getvalue(), "audio/wav", duration_s=0.001) == 3
    with pytest.raises(AIError, match="ai_audio_duration_unverified"):
        await gateway.transcribe(b"not verified audio", "audio/mpeg", duration_s=0.001)
