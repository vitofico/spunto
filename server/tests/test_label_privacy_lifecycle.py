"""Normal note eligibility changes do not consume the label plugin's failure budget."""

import asyncio
import json

import pytest
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from test_plugins_ai import OWNER, seed
from test_plugins_ai import ai_host as ai_host

from spunto.core.suggestions import decide
from spunto.host import Ctx
from spunto.plugins import label_suggestions

pytestmark = pytest.mark.anyio


async def dispatched(host):
    while host.store._dispatch_tasks:
        await asyncio.gather(*host.store._dispatch_tasks)


async def fresh_queue(host, monkeypatch):
    host.settings.ai_embed_profile = None
    monkeypatch.setattr(host.store, "event_sink", host.emit)
    await dispatched(host)
    async with host.store.write() as conn:
        await conn.execute("DELETE FROM job")


async def queued_hook(host, note, event="note.saved"):
    await host._queue(
        "label-suggestions",
        "hook:" + event,
        {"note_id": note["id"], "note_version": note["version"]},
        note_id=note["id"],
    )
    row = await host._claim()
    assert row["plugin_id"] == "label-suggestions" and row["kind"] == "hook:" + event
    return row


async def test_owner_private_then_dismiss_completes_hook_without_plugin_failure(
    ai_host, monkeypatch
):
    host, provider = ai_host
    notes, _, target = await seed(host)
    await fresh_queue(host, monkeypatch)
    await label_suggestions.saved(Ctx(host, "label-suggestions"), target["id"], target["version"])
    async with host.store.read() as conn:
        suggestion_id = (await conn.execute_fetchall("SELECT id FROM suggestion"))[0][0]
    private = await notes.flags(target["id"], {"private": True})
    await dispatched(host)
    async with host.store.write() as conn:
        await conn.execute("DELETE FROM job")
    result = await decide(host.store, OWNER, suggestion_id, "dismiss")
    assert result["suggestion"]["status"] == "dismissed"
    await dispatched(host)
    row = await host._claim()
    assert row["kind"] == "hook:suggestion.decided"
    assert row["note_id"] == private["id"]
    provider.requests.clear()
    await host._run(row)
    async with host.store.read() as conn:
        assert not await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (row["id"],))
    assert host.states["label-suggestions"]["state"] == "enabled"
    assert host.failures.get("label-suggestions", 0) == 0
    assert not provider.requests
    assert (
        await Ctx(host, "label-suggestions").storage.records.get("dismissed", target["id"]) is None
    )


@pytest.mark.parametrize("eligibility", ["private", "trashed", "missing", "machine"])
async def test_decided_skips_ineligible_seed(ai_host, eligibility):
    host, provider = ai_host
    notes, _, target = await seed(host)
    if eligibility in {"private", "trashed"}:
        await notes.flags(target["id"], {eligibility: True})
    elif eligibility == "missing":
        await notes.flags(target["id"], {"trashed": True})
        await notes.delete(target["id"])
    else:
        async with host.store.write() as conn:
            await conn.execute("UPDATE note SET machine=1 WHERE id=?", (target["id"],))
    ctx = Ctx(host, "label-suggestions")
    provider.requests.clear()
    await label_suggestions.decided(ctx, target["id"])
    assert await ctx.storage.records.get("dismissed", target["id"]) is None
    assert not provider.requests


@pytest.mark.parametrize("flag", ["private", "trashed"])
async def test_suggestion_decisions_eligibility_change_after_seed_read_is_benign(ai_host, flag):
    host, _ = ai_host
    notes, _, target = await seed(host)
    ctx = Ctx(host, "label-suggestions")
    assert await ctx.notes.get(target["id"])
    await notes.flags(target["id"], {flag: True})
    assert await ctx.notes.suggestion_decisions(target["id"]) == []


async def test_private_while_gateway_capacity_queued_retries_fresh_hook_without_attempts(
    ai_host, monkeypatch
):
    host, provider = ai_host
    notes, _, target = await seed(host)
    await fresh_queue(host, monkeypatch)
    row = await queued_hook(host, target)
    queue = host.gateway.queues["fixture"]
    queue.active = queue.profile.concurrency
    provider.requests.clear()
    running = asyncio.create_task(host._run(row))
    try:
        async with asyncio.timeout(3):
            async with queue.condition:
                await queue.condition.wait_for(lambda: bool(queue.pending))
        await notes.flags(target["id"], {"private": True})
        await dispatched(host)
        # Keep the running payload: no newer saved job hides its attempt accounting.
        async with host.store.write() as conn:
            await conn.execute("DELETE FROM job WHERE id!=?", (row["id"],))
        async with queue.condition:
            queue.active = 0
            queue.condition.notify_all()
        await running
        assert not provider.requests
        assert host.failures.get("label-suggestions", 0) == 0
        assert host.states["label-suggestions"]["state"] == "enabled"
        async with host.store.write() as conn:
            jobs = await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (row["id"],))
            assert len(jobs) == 1
            assert (jobs[0]["status"], jobs[0]["attempts"], jobs[0]["lease_until"]) == (
                "queued",
                0,
                None,
            )
            assert jobs[0]["run_after"] > host.clock()
            await conn.execute("UPDATE job SET run_after=0 WHERE id=?", (row["id"],))
        # Re-read eligibility inside the hook, even with no version guard in old payloads.
        async with host.store.write() as conn:
            await conn.execute(
                "UPDATE job SET args=? WHERE id=?",
                (json.dumps({"note_id": target["id"]}), row["id"]),
            )
        await host._run(await host._claim())
        async with host.store.read() as conn:
            assert not await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (row["id"],))
            assert not await conn.execute_fetchall("SELECT * FROM suggestion")
        assert not provider.requests
        assert host.failures["label-suggestions"] == 0
    finally:
        async with queue.condition:
            queue.active = 0
            queue.condition.notify_all()
        if not running.done():
            running.cancel()
        await running


@pytest.mark.parametrize("mode", ["wrong-types", "provider-refusal"])
async def test_malformed_output_and_provider_refusal_still_open_failure_circuit(
    ai_host, monkeypatch, mode
):
    host, provider = ai_host
    notes, _, _ = await seed(host)
    await fresh_queue(host, monkeypatch)
    provider.mode = mode
    provider.requests.clear()

    if mode == "provider-refusal":
        refusal_app = FastAPI()

        @refusal_app.post("/v1/chat/completions")
        async def refusal(request: Request):
            provider.requests.append(await request.json())
            return JSONResponse(
                {"choices": [{"finish_reason": "stop", "message": {"refusal": "fixture"}}]}
            )

        monkeypatch.setattr(host.gateway.transport, "app", refusal_app)

    for attempt in range(1, 6):
        target = await notes.create("Eligible label inference fixture " + str(attempt))
        await dispatched(host)
        async with host.store.write() as conn:
            await conn.execute("DELETE FROM job WHERE status='queued'")
        row = await queued_hook(host, target)
        await host._run(row)
        async with host.store.read() as conn:
            job = (await conn.execute_fetchall("SELECT * FROM job WHERE id=?", (row["id"],)))[0]
            assert (job["status"], job["attempts"], job["lease_until"]) == ("dead", 1, None)
        assert host.failures["label-suggestions"] == attempt
    assert host.states["label-suggestions"]["state"] == "failed"
    assert len(provider.requests) == (10 if mode == "wrong-types" else 5)
