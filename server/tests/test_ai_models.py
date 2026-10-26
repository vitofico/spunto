"""Real scoped SQLite settings, bounded synthetic model inventories and hot swaps."""

import asyncio
import json

import httpx
import pytest
from conftest import PUBLIC_URL
from pydantic import SecretStr
from test_api_plugins import setup

from spunto.ai.errors import AIError
from spunto.ai.gateway import Gateway, Probe
from spunto.ai.models import inventory
from spunto.ai.profiles import Profile, embedding_prefixes
from spunto.auth import tokens

pytestmark = pytest.mark.anyio
ORIGIN = {"Origin": PUBLIC_URL}
CAPS = {
    "chat:cloud": ["completion"],
    "chat-new": ["completion"],
    "embeddinggemma:latest": ["embedding"],
    "embeddinggemma-2:270m": ["embedding"],
    "nomic-embed-text:latest": ["embedding"],
    "image-generator": ["completion", "vision"],
    "multimodal-embed": ["embedding", "vision", "audio"],
    "unknown": None,
}


class Provider:
    def __init__(self):
        self.requests = []
        self.fail = None
        self.pause = None
        self.reject_model = None

    async def __call__(self, request):
        body = json.loads(request.content) if request.content else {}
        self.requests.append((request.url, body, dict(request.headers)))
        if self.fail == "inventory" and request.url.path == "/api/tags":
            return httpx.Response(503, text="private provider diagnostic")
        if request.url.path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": name} for name in CAPS]})
        if request.url.path == "/api/show":
            caps = CAPS[body["model"]]
            return httpx.Response(200, json={"capabilities": caps, "template": "private prompt"})
        if request.url.path == "/v1/embeddings":
            if self.fail == "embed" or body["model"] == self.reject_model:
                return httpx.Response(404)
            model = body["model"]
            dimension = 4 if model == "embeddinggemma-2:270m" else 3
            return httpx.Response(
                200,
                json={
                    "data": [
                        {"index": i, "embedding": [0 if self.fail == "zero" else 0.5] * dimension}
                        for i, _ in enumerate(body["input"])
                    ]
                },
            )
        if request.url.path == "/v1/chat/completions":
            if self.pause:
                self.pause[0].set()
                await self.pause[1].wait()
            return httpx.Response(
                200,
                json={
                    "choices": [{"finish_reason": "stop", "message": {"content": '{"ok":true}'}}]
                },
            )
        return httpx.Response(404)


@pytest.fixture
async def model_server(server):
    # Startup owns a separate health/host refresh task. Settle this server's task
    # before replacing its gateway, otherwise it can race the model-save fences.
    for task in asyncio.all_tasks():
        if task.get_name() != "ai-startup-check":
            continue
        frame = getattr(task.get_coro(), "cr_frame", None)
        if frame is not None and frame.f_locals.get("services") is server.services:
            await asyncio.wait_for(asyncio.shield(task), 10)
    provider = Provider()
    profile = Profile(
        name="local",
        base_url="http://provider.synthetic.test/v1",
        api_key=SecretStr("fixture-sentinel-key"),
        chat_model="chat:cloud",
        embed_model="embeddinggemma:latest",
        rate_per_min=100000,
        embed_query_prefix=embedding_prefixes("embeddinggemma")[0],
        embed_document_prefix=embedding_prefixes("embeddinggemma")[1],
    )
    settings = server.services.settings.model_copy(
        update={
            "ai_profiles": {"local": profile},
            "ai_chat_profile": "local",
            "ai_embed_profile": "local",
        }
    )
    gateway = Gateway(server.services.store, settings, transport=httpx.MockTransport(provider))
    server.services.ai = gateway
    server.services.host.gateway = gateway
    gateway.selection.on_change = server.services.host._model_selection_changed
    server.services.vectors.gateway = gateway
    await setup(server)
    return server, provider, settings


async def choose(server, selections, revision=None):
    if revision is None:
        revision = (await server.client.get("/api/v1/ai")).json()["model_selection"]["revision"]
    return await server.client.put(
        "/api/v1/ai/models", headers=ORIGIN, json={"revision": revision, "selections": selections}
    )


async def test_inventory_capabilities_locality_and_no_provider_details(model_server):
    server, provider, _ = model_server
    response = await server.client.get("/api/v1/ai/models")
    assert response.status_code == 200, response.text
    rows = {row["id"]: row for row in response.json()["profiles"][0]["models"]}
    assert rows["embeddinggemma-2:270m"]["capabilities"] == ["embed"]
    assert rows["multimodal-embed"]["capabilities"] == ["embed"]
    assert rows["image-generator"]["capabilities"] == ["chat", "vision"]
    assert rows["unknown"]["capabilities"] is None
    assert rows["chat:cloud"]["leaves_network"]
    assert all(
        str(url).startswith("http://provider.synthetic.test/") for url, _, _ in provider.requests
    )
    assert "private prompt" not in response.text and "fixture-sentinel-key" not in response.text
    assert (await server.client.get("/api/v1/ai/models?profile=absent")).status_code == 422


async def test_hot_selection_persists_restart_binds_new_cap_and_resets_offline(model_server):
    server, provider, settings = model_server
    choice = {
        "embed": {"profile": "local", "model": "embeddinggemma-2:270m"},
        "vision": {"profile": "local", "model": "image-generator"},
    }
    response = await choose(server, choice)
    assert response.status_code == 200, response.text
    value = response.json()
    assert value["can_manage"] and value["bindings"]["vision"] == "local"
    assert value["model_selection"]["selections"] == choice and "indexing" in value
    assert value["profiles"][0]["embed_model"] == "embeddinggemma-2:270m"
    assert settings.ai_profiles["local"].embed_model == "embeddinggemma:latest"
    assert settings.ai_vision_profile is None
    restarted = Gateway(server.services.store, settings, transport=httpx.MockTransport(provider))
    await restarted.selection.load()
    assert restarted.profile_for("embed").embed_model == "embeddinggemma-2:270m"
    assert restarted.profile_for("vision").vision_model == "image-generator"
    await restarted.close()
    provider.fail = "inventory"
    reset = await choose(server, {})
    assert reset.status_code == 200, reset.text
    assert reset.json()["model_selection"]["selections"] == {}
    assert "vision" not in reset.json()["bindings"]
    assert reset.json()["profiles"][0]["embed_model"] == "embeddinggemma:latest"


async def test_failed_or_incompatible_selection_preserves_previous_configuration(model_server):
    server, provider, _ = model_server
    before = (await server.client.get("/api/v1/ai")).json()["model_selection"]
    for model, capability in (("not-installed", "embed"), ("multimodal-embed", "vision")):
        result = await choose(server, {capability: {"profile": "local", "model": model}})
        assert result.status_code == 422, result.text
    provider.fail = "inventory"
    failed = await choose(server, {"chat": {"profile": "local", "model": "chat-new"}})
    assert failed.status_code == 503 and failed.json()["code"] == "ai_inventory_unavailable"
    provider.fail = "embed"
    failed = await choose(server, {"embed": {"profile": "local", "model": "embeddinggemma-2:270m"}})
    assert failed.status_code == 503
    provider.fail = "zero"
    failed = await choose(server, {"embed": {"profile": "local", "model": "embeddinggemma-2:270m"}})
    assert failed.status_code == 503
    assert (await server.client.get("/api/v1/ai")).json()["model_selection"] == before


async def test_cas_between_independent_gateways_and_late_health(model_server):
    server, provider, settings = model_server
    gateway = server.services.ai
    other = Gateway(gateway.store, settings, transport=httpx.MockTransport(provider))
    initial = gateway.selection.revision
    results = await asyncio.gather(
        gateway.selection.save(initial, {"chat": {"profile": "local", "model": "chat-new"}}),
        other.selection.save(initial, {"chat": {"profile": "local", "model": "unknown"}}),
        return_exceptions=True,
    )
    assert (
        sum(
            isinstance(value, AIError) and value.code == "ai_selection_changed" for value in results
        )
        == 1
    )
    await gateway.selection.load()
    await other.selection.load()
    assert gateway.selection.status() == other.selection.status()
    provider.pause = asyncio.Event(), asyncio.Event()
    health = asyncio.create_task(gateway.health_check())
    await asyncio.wait_for(provider.pause[0].wait(), 1)
    await gateway.selection.save(gateway.selection.revision, {})
    provider.pause[1].set()
    assert await health == []
    assert gateway.health == []
    await other.close()


async def test_old_request_is_fenced_after_model_save(model_server):
    server, provider, _ = model_server
    gateway = server.services.ai
    provider.pause = asyncio.Event(), asyncio.Event()
    task = asyncio.create_task(gateway.chat(Probe, "fixed synthetic probe"))
    await asyncio.wait_for(provider.pause[0].wait(), 1)
    await gateway.selection.save(
        gateway.selection.revision, {"chat": {"profile": "local", "model": "chat-new"}}
    )
    provider.pause[1].set()
    with pytest.raises(AIError, match="ai_configuration_changed"):
        await task


async def test_old_result_waiting_for_accounting_is_fenced_before_publication(model_server):
    server, _, _ = model_server
    gateway = server.services.ai
    started, resume = asyncio.Event(), asyncio.Event()
    original_count = gateway._count

    async def blocked_count(*args):
        started.set()
        await resume.wait()
        await original_count(*args)

    gateway._count = blocked_count
    pending = asyncio.create_task(gateway.chat(Probe, "fixed synthetic probe"))
    await asyncio.wait_for(started.wait(), 1)
    await gateway.selection.save(
        gateway.selection.revision,
        {
            "chat": {"profile": "local", "model": "chat-new"},
        },
    )
    resume.set()
    with pytest.raises(AIError, match="ai_configuration_changed"):
        await pending
    async with gateway.store.read() as conn:
        calls = await conn.execute_fetchall("SELECT n FROM ai_stat WHERE capability='chat'")
    assert sum(row[0] for row in calls) == 1


async def test_selection_commit_fences_old_activation_before_async_host_refresh(model_server):
    from spunto.ai.vectors import VectorUnavailable
    from spunto.auth.tokens import Principal
    from spunto.core.notes import Notes

    server, _, _ = model_server
    gateway, host, index = server.services.ai, server.services.host, server.services.vectors
    await host.close()
    server.services.store.event_sink = None
    notes = Notes(gateway.store, Principal("fixture", "session", "Fixture", "full"))
    first = await notes.create("Synthetic first note")
    second = await notes.create("Synthetic second note")
    await index.sync_routes({"semantic-search": "local"})
    await index.backfill()
    old = await index.active_key()
    await gateway.selection.save(
        gateway.selection.revision,
        {
            "embed": {"profile": "local", "model": "nomic-embed-text:latest"},
        },
    )
    await index.prepare_routes()
    await index.index_note(first["id"])
    entered, resume = asyncio.Event(), asyncio.Event()
    original_activate = index._activate

    async def blocked_activation(**kwargs):
        entered.set()
        await resume.wait()
        await original_activate(**kwargs)

    index._activate = blocked_activation
    pending = asyncio.create_task(index.index_note(second["id"]))
    await asyncio.wait_for(entered.wait(), 1)
    await gateway.selection.save(
        gateway.selection.revision,
        {
            "embed": {"profile": "local", "model": "embeddinggemma-2:270m"},
        },
    )
    # Host.refresh_models has not run; synchronous commit/apply fences already apply.
    resume.set()
    with pytest.raises(VectorUnavailable):
        await pending
    assert await index.active_key() == old


async def test_cancelled_model_save_retains_worker_refresh_until_target_is_queued(model_server):
    server, _, _ = model_server
    gateway, host, store = server.services.ai, server.services.host, server.services.store
    await host.close()
    async with store.write() as conn:
        await conn.execute("DELETE FROM job WHERE plugin_id='core' AND kind='ai-routes'")
    saved = asyncio.Event()

    async def interrupted_request():
        await gateway.selection.save(
            gateway.selection.revision,
            {
                "embed": {"profile": "local", "model": "embeddinggemma-2:270m"},
            },
        )
        saved.set()
        await asyncio.Event().wait()  # Request disappears before Host.refresh_models.

    request = asyncio.create_task(interrupted_request())
    await asyncio.wait_for(saved.wait(), 1)
    request.cancel()
    with pytest.raises(asyncio.CancelledError):
        await request
    assert await gateway.selection.load() is False
    assert host._model_selection_epoch > host._model_selection_refreshed
    async with store.read() as conn:
        assert not await conn.execute_fetchall(
            "SELECT id FROM job WHERE plugin_id='core' AND kind='ai-routes'"
        )
    # The worker uses this same path: unchanged saved JSON still has pending work.
    await host.refresh_models()
    assert host._model_selection_epoch == host._model_selection_refreshed
    async with store.read() as conn:
        assert await conn.execute_fetchall(
            "SELECT id FROM job WHERE plugin_id='core' AND kind='ai-routes' AND status='queued'"
        )


@pytest.mark.parametrize("interruption", ["cancel", "failure", "newer"])
async def test_model_refresh_retries_without_acknowledging_newer_changes(
    model_server, interruption
):
    server, _, _ = model_server
    gateway, host = server.services.ai, server.services.host
    await host.close()
    await gateway.selection.save(
        gateway.selection.revision,
        {
            "chat": {"profile": "local", "model": "chat-new"},
        },
    )
    entered, resume = asyncio.Event(), asyncio.Event()
    refresh_ai = host.refresh_ai

    async def blocked_refresh():
        entered.set()
        await resume.wait()
        if interruption == "failure":
            raise AIError("provider", "synthetic_refresh_failure")
        await refresh_ai()

    host.refresh_ai = blocked_refresh
    pending = asyncio.create_task(host.refresh_models())
    await asyncio.wait_for(entered.wait(), 1)
    old_epoch = host._model_selection_epoch
    await gateway.selection.save(
        gateway.selection.revision,
        {
            "chat": {"profile": "local", "model": "unknown"},
        },
    )
    assert host._model_selection_epoch > old_epoch
    if interruption == "cancel":
        pending.cancel()
        with pytest.raises(asyncio.CancelledError):
            await pending
    else:
        resume.set()
        if interruption == "failure":
            with pytest.raises(AIError, match="synthetic_refresh_failure"):
                await pending
        else:
            await pending
    assert host._model_selection_refreshed < host._model_selection_epoch
    host.refresh_ai = refresh_ai
    await host.refresh_models()
    assert host._model_selection_refreshed == host._model_selection_epoch
    assert gateway.profile_for("chat").chat_model == "unknown"


async def test_model_refresh_serializes_with_direct_health_refresh(model_server):
    server, _, _ = model_server
    host = server.services.host
    await host.close()
    entered, resume = asyncio.Event(), asyncio.Event()
    original, calls, active, peak = host._refresh_ai, 0, 0, 0

    async def blocked_refresh():
        nonlocal calls, active, peak
        calls += 1
        active += 1
        peak = max(peak, active)
        if calls == 1:
            entered.set()
            await resume.wait()
        try:
            await original()
        finally:
            active -= 1

    host._refresh_ai = blocked_refresh
    health = asyncio.create_task(host.refresh_ai())
    await asyncio.wait_for(entered.wait(), 1)
    models = asyncio.create_task(host.refresh_models(changed=True))
    await asyncio.sleep(0)
    assert calls == 1 and not models.done()
    resume.set()
    await asyncio.gather(health, models)
    assert calls == 2 and peak == 1


@pytest.mark.parametrize("code", ["plugin_disabled", "permission_denied"])
async def test_pending_refresh_retries_only_superseded_owner_generation(model_server, code):
    from spunto.host.plugin import ContextError

    server, _, _ = model_server
    gateway, host = server.services.ai, server.services.host
    await host.close()
    await host.disable("semantic-search")
    await gateway.selection.save(
        gateway.selection.revision,
        {
            "chat": {"profile": "local", "model": "chat-new"},
        },
    )
    original = host.refresh_ai

    async def superseded():
        raise ContextError(code)

    host.refresh_ai = superseded
    if code == "plugin_disabled":
        await host.refresh_models()
    else:
        with pytest.raises(ContextError, match=code):
            await host.refresh_models()
    assert host._model_selection_refreshed < host._model_selection_epoch
    host.refresh_ai = original
    await host.refresh_models()
    assert host._model_selection_refreshed == host._model_selection_epoch
    assert host.states["semantic-search"]["switch"] == "off"
    assert host.states["semantic-search"]["state"] == "disabled"


async def test_model_routes_require_session_origin_and_reject_pat(model_server):
    server, _, _ = model_server
    body = {"revision": server.services.ai.selection.revision, "selections": {}}
    assert (await server.client.put("/api/v1/ai/models", json=body)).status_code == 403
    _, token = await tokens.create(
        server.services.store, name="Fixture", client="test", scope="write"
    )
    headers = {**ORIGIN, "Authorization": "Bearer " + token}
    assert (await server.client.get("/api/v1/ai/models", headers=headers)).status_code == 403
    assert (
        await server.client.put("/api/v1/ai/models", headers=headers, json=body)
    ).status_code == 403


async def test_stale_profile_removal_is_visible_and_fails_only_affected_binding(model_server):
    server, provider, settings = model_server
    gateway = server.services.ai
    await gateway.selection.save(
        gateway.selection.revision, {"chat": {"profile": "local", "model": "chat-new"}}
    )
    alternative = Profile(
        name="alternative", base_url="http://alternative.synthetic/v1", chat_model="other"
    )
    changed = settings.model_copy(
        update={
            "ai_profiles": {"alternative": alternative},
            "ai_chat_profile": "alternative",
            "ai_embed_profile": None,
        }
    )
    restarted = Gateway(gateway.store, changed, transport=httpx.MockTransport(provider))
    await restarted.selection.load()
    assert restarted.selection.status()["selections"]["chat"]["profile"] == "local"
    assert "chat" not in restarted.settings.ai_bindings
    with pytest.raises(AIError, match="ai_capability_unbound"):
        restarted.profile_for("chat")
    await restarted.selection.save(restarted.selection.revision, {})
    assert restarted.profile_for("chat").chat_model == "other"
    await restarted.close()


async def test_environment_change_invalidates_saved_revision_and_old_model_circuit(model_server):
    server, provider, settings = model_server
    gateway = server.services.ai
    await gateway.selection.save(
        gateway.selection.revision,
        {
            "embed": {"profile": "local", "model": "embeddinggemma-2:270m"},
        },
    )
    provider.reject_model = "embeddinggemma:latest"
    with pytest.raises(AIError):
        await gateway.embed(["fixed historical query"], profile=settings.ai_profiles["local"])
    assert gateway.queues["local"].paused_until == 0
    assert len((await gateway.embed(["fixed current query"]))[0]) == 4
    old_revision = gateway.selection.revision
    replacement = settings.ai_profiles["local"].model_copy(
        update={"base_url": "http://replacement.synthetic/v1"}
    )
    restarted = Gateway(
        gateway.store,
        settings.model_copy(update={"ai_profiles": {"local": replacement}}),
        transport=httpx.MockTransport(provider),
    )
    await restarted.selection.load()
    assert restarted.selection.revision != old_revision
    with pytest.raises(AIError, match="ai_selection_changed"):
        await restarted.selection.save(old_revision, {})
    await restarted.close()


async def test_discovery_redirects_bodies_and_unknown_compatibility_are_bounded():
    profile = Profile(
        name="local",
        base_url="https://provider.synthetic/v1",
        api_key=SecretStr("fixture-sentinel-key"),
    )
    seen = []

    def redirect(request):
        seen.append(str(request.url))
        return httpx.Response(302, headers={"Location": "https://unapproved.synthetic/secret"})

    with pytest.raises(AIError):
        await inventory(profile, transport=httpx.MockTransport(redirect))
    assert seen == ["https://provider.synthetic/api/tags", "https://provider.synthetic/v1/models"]

    def generic(request):
        if request.url.path == "/api/tags":
            return httpx.Response(404)
        return httpx.Response(200, json={"data": [{"id": "unknown-model"}]})

    assert (await inventory(profile, transport=httpx.MockTransport(generic)))[0][
        "capabilities"
    ] is None
    for native in (httpx.Response(403), httpx.Response(200, json={"unrelated": True})):

        def proxy(request, native=native):
            return (
                native
                if request.url.path == "/api/tags"
                else httpx.Response(200, json={"data": [{"id": "proxy-model"}]})
            )

        assert (await inventory(profile, transport=httpx.MockTransport(proxy)))[0][
            "id"
        ] == "proxy-model"
    with pytest.raises(AIError):
        await inventory(
            profile,
            transport=httpx.MockTransport(
                lambda request: httpx.Response(200, content=b"x" * 1_048_577)
            ),
        )


async def test_embedding_prefix_switch_preserves_explicit_empty_and_custom(env):
    from spunto.config import load_settings

    env(
        SPUNTO_AI_PROFILES="local",
        SPUNTO_AI_LOCAL_BASE_URL="http://localhost/v1",
        SPUNTO_AI_LOCAL_EMBED_MODEL="embeddinggemma:latest",
        SPUNTO_AI_EMBED_PROFILE="local",
    )
    original = load_settings().ai_profiles["local"]
    assert (
        original.with_model("embed", "embeddinggemma-2:270m").embed_query_prefix
        == "task: search result | query: "
    )
    assert (
        original.with_model("embed", "nomic-embed-text:latest").embed_query_prefix
        == "search_query: "
    )
    assert original.with_model("embed", "unknown").embed_query_prefix == ""
    env(SPUNTO_AI_LOCAL_EMBED_QUERY_PREFIX="", SPUNTO_AI_LOCAL_EMBED_DOCUMENT_PREFIX="custom: ")
    explicit = load_settings().ai_profiles["local"].with_model("embed", "nomic-embed-text:latest")
    assert explicit.embed_query_prefix == "" and explicit.embed_document_prefix == "custom: "
