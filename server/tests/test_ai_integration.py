"""Bounded cross-service checks beyond individual first-party plugin handlers."""

import asyncio

import pytest
from test_plugins_ai import OWNER, seed, state
from test_plugins_ai import ai_host as ai_host

from spunto.ai.errors import AIError
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.host import Ctx
from spunto.plugins import label_suggestions

pytestmark = pytest.mark.anyio


@pytest.fixture
async def integration_host(ai_host):
    host, provider = ai_host
    original = host.store.plugin_host
    handlers = {
        plugin_id: {kind: dict(values) for kind, values in plugin.handlers.items()}
        for plugin_id, plugin in host.plugins.items()
    }
    host.store.plugin_host = host
    try:
        yield host, provider
    finally:
        host.store.plugin_host = original
        for plugin_id, values in handlers.items():
            host.plugins[plugin_id].handlers = values


def ranker(host, candidates):
    async def search(ctx, query):
        return [{"id": note_id, "score": 0.9} for note_id in candidates]

    host.plugins["semantic-search"].handlers["search_source"]["search"] = search


async def test_hybrid_rrf_filters_visibility_labels_archive_dates_and_cursor(integration_host):
    host, _ = integration_host
    notes = Notes(host.store, OWNER)
    both = await notes.create("Alpha keyword #wanted")
    meaning = await notes.create("Concept in different words #wanted")
    keyword = await notes.create("Alpha secondary #wanted")
    wrong_label = await notes.create("Unrelated category #other")
    archived = await notes.create("Archive concept #wanted")
    private = await notes.create("Secret concept #wanted")
    outside = await notes.create("Outside concept #wanted")
    machine = await notes.create("Machine concept #wanted")
    old = await notes.create("Old concept #wanted", created_at=1000)
    await notes.flags(archived["id"], {"archived": True})
    await notes.flags(private["id"], {"private": True})
    async with host.store.write() as conn:
        await conn.execute("UPDATE note SET origin='external' WHERE id=?", (outside["id"],))
        await conn.execute("UPDATE note SET machine=1 WHERE id=?", (machine["id"],))
    ranker(
        host,
        [n["id"] for n in (meaning, both, wrong_label, archived, private, outside, machine, old)],
    )
    reader = Notes(host.store, Principal("fixture", "pat", "Fixture", "read", external_ok=False))
    page = await reader.search("Alpha", labels=["wanted"], from_date="2026-01-01", limit=1)
    assert page["semantic"] == "on"
    assert page["hits"][0]["id"] == both["id"] and page["hits"][0]["match"] == "both"
    seen = [both["id"]]
    while page["next_cursor"]:
        page = await reader.search(
            "Alpha", labels=["wanted"], from_date="2026-01-01", limit=1, cursor=page["next_cursor"]
        )
        seen.extend(hit["id"] for hit in page["hits"])
    assert set(seen) == {both["id"], meaning["id"], keyword["id"]} and len(seen) == 3


@pytest.mark.parametrize("mode", ["malicious", "timeout", "failure"])
async def test_bad_semantic_sources_preserve_keyword_results_and_owner_data(integration_host, mode):
    host, _ = integration_host
    notes, _, target = await seed(host)
    before = await state(host)

    async def bad_source(ctx, query):
        if mode == "timeout":
            await asyncio.sleep(2.2)
        if mode == "failure":
            raise AIError("provider", "fixture_failure")
        return [{"id": "' OR 1=1 --", "score": 0.99}]

    host.plugins["semantic-search"].handlers["search_source"]["search"] = bad_source
    result = await notes.search("Spunto SQLite")
    assert result["semantic"] == "unavailable"
    assert target["id"] in {hit["id"] for hit in result["hits"]}
    assert await state(host) == before


async def test_hybrid_cursor_rechecks_visibility_after_first_page(integration_host):
    host, _ = integration_host
    notes = Notes(host.store, OWNER)
    first = await notes.create("Alpha keyword")
    later = await notes.create("Different words concept")
    ranker(host, [first["id"], later["id"]])
    reader = Notes(host.store, Principal("fixture", "pat", "Fixture", "read", external_ok=False))
    page = await reader.search("Alpha", limit=1)
    assert page["next_cursor"]
    await notes.flags(later["id"], {"private": True})
    following = await reader.search("Alpha", limit=1, cursor=page["next_cursor"])
    assert following["hits"] == []


async def test_label_vocabulary_excludes_private_machine_and_caller_hidden_external(
    integration_host,
):
    host, provider = integration_host
    notes, _, target = await seed(host)
    private = await notes.create("Secret notes #secret")
    machine = await notes.create("Generated notes #machine-only")
    outside = await notes.create("External words #outside-only")
    await notes.flags(private["id"], {"private": True})
    async with host.store.write() as conn:
        await conn.execute("UPDATE note SET machine=1 WHERE id=?", (machine["id"],))
        await conn.execute("UPDATE note SET origin='external' WHERE id=?", (outside["id"],))
    reader = Principal("fixture", "pat", "Fixture", "read", external_ok=False)
    vocabulary = await Ctx(host, "label-suggestions", principal=reader).notes.labels()
    assert {item["path"] for item in vocabulary["labels"]} == {"project/Spunto"}
    host.settings.ai_embed_profile = None
    provider.mode = "prose"
    before = await state(host)
    with pytest.raises(AIError):
        await label_suggestions.saved(
            Ctx(host, "label-suggestions"), target["id"], target["version"]
        )
    assert await state(host) == before


@pytest.mark.parametrize("mode,status,attempts", [("500", "queued", 0), ("prose", "dead", 1)])
async def test_worker_provider_outage_preserves_attempts_validation_dies(
    integration_host, mode, status, attempts
):
    host, provider = integration_host
    _, _, target = await seed(host)
    host.settings.ai_embed_profile = None
    job_id = await host._queue(
        "label-suggestions",
        "hook:note.saved",
        {"note_id": target["id"], "note_version": target["version"]},
        note_id=target["id"],
    )
    async with host.store.write() as conn:
        await conn.execute(
            "UPDATE job SET status='running',lease_until=? WHERE id=?",
            (host.clock() + 300000, job_id),
        )
        job = dict((await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (job_id,)))[0])
    provider.mode = mode
    before = await state(host)
    await host._run(job)
    async with host.store.read() as conn:
        result = dict((await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (job_id,)))[0])
    assert result["status"] == status and result["attempts"] == attempts
    if status == "queued":
        assert result["run_after"] > host.clock()
    assert await state(host) == before
