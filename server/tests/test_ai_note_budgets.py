"""Per-note limits cap fresh gateway reads without weakening the total or privacy gates."""

import html
import re

import httpx
import pytest
from test_plugins_ai import FakeProvider

from spunto.ai.errors import AIError
from spunto.ai.gateway import Gateway, Probe
from spunto.ai.profiles import Profile
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes

pytestmark = pytest.mark.anyio
OWNER = Principal("owner", "session", "Owner", "write", private_ok=True)


@pytest.fixture
async def bounded(server):
    fake = FakeProvider()
    profile = Profile(
        name="budget",
        base_url="http://provider.test/v1",
        chat_model="fixture",
        rate_per_min=100000,
        timeout_s=1,
    )
    settings = server.services.settings.model_copy(
        update={"ai_profiles": {"budget": profile}, "ai_chat_profile": "budget"}
    )
    gateway = Gateway(server.services.store, settings, transport=httpx.ASGITransport(fake.app))
    try:
        yield gateway, fake, Notes(server.services.store, OWNER)
    finally:
        await gateway.close()


def bodies(fake):
    content = fake.requests[-1]["messages"][1]["content"]
    return {
        match[1]: html.unescape(match[2])
        for match in re.finditer(r'<note id="([^"]+)"[^>]*>(.*?)</note>', content, re.DOTALL)
    }


async def test_limits_apply_to_fresh_database_bodies_and_keep_total_budget(bounded):
    gateway, fake, notes = bounded
    first = await notes.create("OLD database target")
    second = await notes.create("EXAMPLE-START " + "evidence " * 1000 + "EXAMPLE-TAIL")
    await notes.edit(first["id"], "FRESH-TARGET " + "target " * 1000, first["version"])
    assert (
        await gateway.chat(
            Probe,
            "check",
            notes=[{**first, "body": "FORGED-EXCERPT"}, second],
            max_note_chars=30,
            note_char_limits={first["id"]: 12, second["id"]: 25},
        )
    ).ok
    outbound = bodies(fake)
    assert outbound[first["id"]] == "FRESH-TARGET"
    assert outbound[second["id"]].startswith("EXAMPLE-START")
    assert len(outbound[second["id"]]) == 18
    assert sum(map(len, outbound.values())) == 30
    assert "FORGED-EXCERPT" not in str(fake.requests)
    assert "EXAMPLE-TAIL" not in str(fake.requests)


async def test_omitting_optional_limits_keeps_original_shared_budget_behavior(bounded):
    gateway, fake, notes = bounded
    first = await notes.create("0123456789")
    second = await notes.create("another source")
    assert (await gateway.chat(Probe, "check", notes=[first, second], max_note_chars=5)).ok
    assert bodies(fake) == {first["id"]: "01234"}


@pytest.mark.parametrize("limit", [0, -1, True, 1.5, "2", 20001])
async def test_invalid_caps_fail_before_any_provider_request(bounded, limit):
    gateway, fake, notes = bounded
    note = await notes.create("Synthetic budget note")
    with pytest.raises(AIError, match="ai_note_budget_invalid"):
        await gateway.chat(Probe, "check", notes=[note], note_char_limits={note["id"]: limit})
    assert not fake.requests


async def test_unknown_note_budget_key_fails_before_provider(bounded):
    gateway, fake, notes = bounded
    note = await notes.create("Synthetic budget note")
    with pytest.raises(AIError, match="ai_note_budget_invalid"):
        await gateway.chat(Probe, "check", notes=[note], note_char_limits={"unknown": 5})
    assert not fake.requests


async def test_per_note_caps_keep_live_privacy_checks_on_validation_retry(bounded):
    gateway, fake, notes = bounded
    note = await notes.create("Synthetic source becomes private during provider validation")
    fake.mode = "wrong-types"

    async def hide():
        await notes.flags(note["id"], {"private": True})

    fake.on_chat = hide
    with pytest.raises(AIError, match="ai_note_refused"):
        await gateway.chat(Probe, "check", notes=[note], note_char_limits={note["id"]: 8})
    assert len(fake.requests) == 1
    assert len(bodies(fake)[note["id"]]) == 8
