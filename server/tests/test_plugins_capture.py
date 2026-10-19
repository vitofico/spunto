"""M7 contracts: actual SQLite/context gates, synthetic AI and captured HTTP only."""

import io
import json
import struct
import wave
from types import SimpleNamespace

import httpx
import pytest
from ai_fake import FakeProvider

from spunto.ai.gateway import Gateway
from spunto.ai.profiles import Profile
from spunto.auth.tokens import Principal
from spunto.core.attachments import Attachments, write_attachment
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.core.suggestions import decide
from spunto.host import Ctx, Host, validate_output
from spunto.host.plugin import ContextError
from spunto.net.fetch import FetchError, FetchResponse
from spunto.plugins import image_text, link_enrichment, voice_memos, webhooks
from spunto.plugins._capture import audio_duration, bounded, markdown_output, page_details

pytestmark = pytest.mark.anyio
OWNER = Principal("capture-owner", "session", "Owner", "write", private_ok=True)
IDS = ["link-enrichment", "image-text", "voice-memos", "webhooks"]


class AI:
    health = []

    def __init__(self):
        self.calls = []
        self.callback = None

    def profile_for(self, *args):
        return Profile(
            name="synthetic",
            base_url="http://provider.test/v1",
            chat_model="fake",
            vision_model="fake",
            transcribe_model="fake",
            embed_model="fake",
        )

    def for_context(self, ctx):
        ctx.check()
        return self

    def available(self, capability):
        return True

    async def vision(self, output, image, mime, **options):
        self.calls.append(("vision", image, options))
        if self.callback:
            await self.callback()
        return output(text="SYNTHETIC OCR lighthouse", description="A test sign.")

    async def chat(self, output, **options):
        self.calls.append(("chat", options))
        return output(summary="A synthetic source summary.", labels=[])

    async def transcribe(self, audio, mime, **options):
        self.calls.append(("transcribe", audio, options))
        if self.callback:
            await self.callback()
        return SimpleNamespace(text="Synthetic voice transcript.")


class HTTP:
    def __init__(self):
        self.calls = []
        self.responses = []
        self.callback = None

    async def __call__(self, ctx, url, **options):
        ctx.check()
        self.calls.append((url, options))
        if self.callback:
            await self.callback()
        response = (
            self.responses.pop(0)
            if self.responses
            else FetchResponse(
                200,
                httpx.Headers({"content-type": "text/html"}),
                b"<title>Synthetic source</title><script>secret</script><p>A test page.</p>",
            )
        )
        if isinstance(response, Exception):
            raise response
        return response


@pytest.fixture
async def capture(server):
    store = server.services.store
    original = store.event_sink
    store.event_sink = None
    ai, http = AI(), HTTP()
    host = Host(
        store,
        server.services.settings,
        notes_factory=PluginNotes,
        attachments_factory=Attachments,
        gateway=ai,
        fetcher=http,
    )
    await host.discover()
    for id in IDS:
        assert host.manifests[id]["default"] == "off"
        await host.enable(id, host.manifests[id]["permissions"])
        assert host.states[id]["state"] == "enabled"
    try:
        yield host, ai, http, Notes(store, OWNER)
    finally:
        await host.close()
        store.event_sink = original


def ctx(host, plugin, note, *, explicit=False, operation="hook:note.saved"):
    return Ctx(
        host,
        plugin,
        principal=OWNER if explicit else None,
        unattended=not explicit,
        capture_note_id=note["id"],
        operation="command:fetch" if explicit else operation,
    )


async def attached(host, notes, data, mime, label="Synthetic fixture"):
    attachment = await write_attachment(
        host.store, host.settings, data, filename="fixture", mime=mime
    )
    note = await notes.create(label + "\n\n" + attachment["snippet"])
    return note, attachment


def wav(seconds=1):
    value = io.BytesIO()
    with wave.open(value, "wb") as file:
        file.setnchannels(1)
        file.setsampwidth(2)
        file.setframerate(16000)
        file.writeframes(b"\0\0" * int(seconds * 16000))
    return value.getvalue()


def ogg_page(payload, granule, sequence, *, flags=0, serial=42, lacing=None, version=0):
    if lacing is None:
        lacing = bytes([255] * (len(payload) // 255) + [len(payload) % 255])
    assert len(lacing) <= 255 and sum(lacing) == len(payload)
    header = (
        b"OggS"
        + bytes([version, flags])
        + struct.pack("<QII", granule, serial, sequence)
        + bytes(4)
        + bytes([len(lacing)])
        + lacing
    )
    page = bytearray(header + payload)
    checksum = 0
    for value in page:
        checksum ^= value << 24
        for _ in range(8):
            checksum = ((checksum << 1) ^ (0x04C11DB7 if checksum & 0x80000000 else 0)) & 0xFFFFFFFF
    struct.pack_into("<I", page, 22, checksum)
    return bytes(page)


def ogg_cases():
    head = b"OpusHead" + bytes([1, 1]) + struct.pack("<HIhB", 312, 16000, 0, 0)
    tags = b"OpusTags" + bytes(8)
    prefix = ogg_page(head, 0, 0, flags=2) + ogg_page(tags, 0, 1)
    audio = b"\xf8\xff\xfe" * 50

    def last(granule=48312, **options):
        return ogg_page(audio, granule, 2, flags=4, lacing=bytes([3] * 50), **options)

    valid = prefix + last()
    corrupt = bytearray(valid)
    corrupt[-1] ^= 1
    unknown = 0xFFFFFFFFFFFFFFFF
    large = b"\xf8" * 65537
    return {
        "native-style": (valid, 1),
        "continued-audio": (
            prefix
            + ogg_page(b"\xf8" * 255, unknown, 2, lacing=b"\xff")
            + ogg_page(b"\xff\xfe", 48312, 3, flags=5),
            1,
        ),
        "continued-tags": (
            ogg_page(head, 0, 0, flags=2)
            + ogg_page(
                b"OpusTags" + struct.pack("<I", 260) + b"v" * 243,
                unknown,
                1,
                lacing=b"\xff",
            )
            + ogg_page(b"v" * 17 + bytes(4), 0, 2, flags=1)
            + ogg_page(audio, 48312, 3, flags=4, lacing=bytes([3] * 50)),
            1,
        ),
        "maximum-duration": (prefix + last(3600 * 48000 + 312), 3600),
        "headers-only": (
            ogg_page(head, 0, 0, flags=2) + ogg_page(tags, 48312, 1, flags=4),
            None,
        ),
        "missing-tags": (
            ogg_page(head, 0, 0, flags=2) + ogg_page(audio, 48312, 1, flags=4),
            None,
        ),
        "tags-vendor-overrun": (
            ogg_page(head, 0, 0, flags=2)
            + ogg_page(b"OpusTags" + struct.pack("<II", 50, 0), 0, 1)
            + last(),
            None,
        ),
        "tags-comment-overrun": (
            ogg_page(head, 0, 0, flags=2)
            + ogg_page(b"OpusTags" + struct.pack("<III", 0, 1, 5) + b"x", 0, 1)
            + last(),
            None,
        ),
        "illegal-continuation": (prefix + ogg_page(audio, 48312, 2, flags=5), None),
        "unmarked-continuation": (
            prefix
            + ogg_page(b"\xf8" * 255, unknown, 2, lacing=b"\xff")
            + ogg_page(b"\xff\xfe", 48312, 3, flags=4),
            None,
        ),
        "unfinished-eos-packet": (
            prefix + ogg_page(b"\xf8" * 255, 48312, 2, flags=4, lacing=b"\xff"),
            None,
        ),
        "corrupt-crc": (bytes(corrupt), None),
        "missing-bos": (ogg_page(head, 0, 0) + ogg_page(tags, 0, 1) + last(), None),
        "repeated-bos": (prefix + ogg_page(audio, 48312, 2, flags=6), None),
        "missing-eos": (prefix + ogg_page(audio, 48312, 2), None),
        "trailing-eos": (valid + ogg_page(b"\xf8\xff\xfe", 49272, 3, flags=4), None),
        "trailing-byte": (valid + b"x", None),
        "wrong-serial": (prefix + last(serial=43), None),
        "wrong-sequence": (prefix + ogg_page(audio, 48312, 3, flags=4), None),
        "wrong-version": (prefix + last(version=1), None),
        "reserved-flags": (prefix + ogg_page(audio, 48312, 2, flags=12), None),
        "zero-audio-packet": (prefix + ogg_page(b"", 48312, 2, flags=4), None),
        "zero-duration": (prefix + last(312), None),
        "too-long": (prefix + last(3600 * 48000 + 313), None),
        "unknown-eos-granule": (prefix + last(unknown), None),
        "signed-granule-overflow": (prefix + last(1 << 63), None),
        "regressing-granule": (
            prefix
            + ogg_page(b"\xf8\xff\xfe", 48312, 2)
            + ogg_page(b"\xf8\xff\xfe", 48000, 3, flags=4),
            None,
        ),
        "packet-over-65536": (
            prefix
            + ogg_page(large[:65025], unknown, 2, lacing=bytes([255] * 255))
            + ogg_page(large[65025:], 48312, 3, flags=5),
            None,
        ),
        "stereo-head": (
            ogg_page(head[:9] + b"\x02" + head[10:], 0, 0, flags=2) + ogg_page(tags, 0, 1) + last(),
            None,
        ),
        "wrong-head-version": (
            ogg_page(head[:8] + b"\x02" + head[9:], 0, 0, flags=2) + ogg_page(tags, 0, 1) + last(),
            None,
        ),
        "mapped-head": (
            ogg_page(head[:-1] + b"\x01", 0, 0, flags=2) + ogg_page(tags, 0, 1) + last(),
            None,
        ),
        "head-shared-page": (
            ogg_page(head + tags, 0, 0, flags=2, lacing=bytes([19, 16]))
            + ogg_page(audio, 48312, 1, flags=4),
            None,
        ),
        "truncated-page": (valid[:-1], None),
    }


async def test_link_once_cached_outputs_and_no_body_edit(capture):
    host, ai, http, notes = capture
    note = await notes.create("https://public.test/article")
    value = await link_enrichment.enrich(ctx(host, IDS[0], note), note["id"], note["version"])
    validate_output(value)
    await link_enrichment.enrich(ctx(host, IDS[0], note), note["id"], note["version"])
    assert len(http.calls) == 1 and len(ai.calls) == 1
    assert (await notes.get(note["id"]))["body"] == note["body"]
    async with host.store.read() as conn:
        assert (
            len(
                await conn.execute_fetchall(
                    "SELECT * FROM annotation WHERE note_id=?", (note["id"],)
                )
            )
            == 2
        )
        assert (
            len(
                await conn.execute_fetchall(
                    "SELECT * FROM suggestion WHERE note_id=?", (note["id"],)
                )
            )
            == 1
        )
        assert (
            "summary"
            in (
                await conn.execute_fetchall(
                    "SELECT text FROM note_text WHERE note_id=?", (note["id"],)
                )
            )[0][0]
        )


@pytest.mark.parametrize("flag", ["private", "machine", "external", "agent"])
async def test_auto_capture_source_gate_and_owner_explicit(capture, flag):
    host, ai, http, notes = capture
    note = await notes.create("https://public.test/source")
    async with host.store.write() as conn:
        if flag in {"private", "machine"}:
            await conn.execute(f"UPDATE note SET {flag}=1 WHERE id=?", (note["id"],))  # noqa: S608
        else:
            await conn.execute("UPDATE note SET origin=? WHERE id=?", (flag, note["id"]))
    await link_enrichment.enrich(ctx(host, IDS[0], note), note["id"])
    assert not http.calls
    await host.invoke_command(IDS[0], "fetch", {"note_id": note["id"]}, principal=OWNER)
    assert len(http.calls) == (0 if flag in {"private", "machine"} else 1)


async def test_scope_cannot_read_another_note_and_disabling_cancels(capture):
    host, ai, http, notes = capture
    note = await notes.create("Bound")
    other = await notes.create("Other")
    scoped = ctx(host, IDS[0], note)
    with pytest.raises(ContextError, match="capture_scope_required"):
        await scoped.capture.note(other["id"])
    await host.disable(IDS[0])
    with pytest.raises(ContextError, match="plugin_disabled"):
        await scoped.capture.note(note["id"])


async def test_link_fetch_race_publishes_nothing(capture):
    host, ai, http, notes = capture
    note = await notes.create("https://public.test/race")

    async def changed():
        await notes.flags(note["id"], {"private": True})

    http.callback = changed
    await link_enrichment.enrich(ctx(host, IDS[0], note), note["id"])
    assert not ai.calls
    async with host.store.read() as conn:
        assert not await conn.execute_fetchall(
            "SELECT * FROM annotation WHERE note_id=?", (note["id"],)
        )
        assert not await conn.execute_fetchall(
            "SELECT * FROM plugin_record WHERE note_id=?", (note["id"],)
        )


async def test_image_search_cached_panel_no_provider_on_panel_and_private_hide(capture):
    host, ai, http, notes = capture
    note, attachment = await attached(host, notes, b"\x89PNG\r\n\x1a\nsynthetic", "image/png")
    await image_text.process(ctx(host, IDS[1], note), note["id"], note["version"], attachment["id"])
    await image_text.process(ctx(host, IDS[1], note), note["id"])
    assert len(ai.calls) == 1
    panel = await host.invoke_panel(IDS[1], note["id"], principal=OWNER)
    assert "lighthouse" in panel["text"]
    assert len(ai.calls) == 1
    async with host.store.read() as conn:
        assert (
            "lighthouse"
            in (
                await conn.execute_fetchall(
                    "SELECT text FROM note_text WHERE note_id=?", (note["id"],)
                )
            )[0][0]
        )
    assert (await notes.get(note["id"]))["body"] == note["body"]
    await notes.flags(note["id"], {"private": True})
    assert await host.invoke_panel(IDS[1], note["id"], principal=OWNER) is None


async def test_image_unlink_during_model_has_no_result(capture):
    host, ai, http, notes = capture
    note, attachment = await attached(host, notes, b"\x89PNG\r\n\x1a\nfixture", "image/png")

    async def changed():
        await notes.edit(note["id"], "Image removed", note["version"])

    ai.callback = changed
    await image_text.process(ctx(host, IDS[1], note), note["id"])
    async with host.store.read() as conn:
        assert not await conn.execute_fetchall(
            "SELECT * FROM note_text WHERE note_id=?", (note["id"],)
        )
        assert not await conn.execute_fetchall(
            "SELECT * FROM plugin_record WHERE note_id=?", (note["id"],)
        )


async def test_voice_proposal_once_original_recording_and_body_retained(capture):
    host, ai, http, notes = capture
    note, attachment = await attached(host, notes, wav(), "audio/wav")
    await voice_memos.process(ctx(host, IDS[2], note), note["id"])
    await voice_memos.process(ctx(host, IDS[2], note), note["id"])
    assert len(ai.calls) == 1
    assert (await notes.get(note["id"]))["body"] == note["body"]
    async with host.store.read() as conn:
        proposal = (
            await conn.execute_fetchall("SELECT * FROM suggestion WHERE note_id=?", (note["id"],))
        )[0]
        assert proposal["action"] == "replace_body"
        body = json.loads(proposal["payload"])["body"]
        assert "Synthetic voice transcript." in body and attachment["id"] in body
    panel = await host.invoke_panel(IDS[2], note["id"], principal=OWNER)
    assert "0:01" in panel["text"] and len(ai.calls) == 1
    await notes.flags(note["id"], {"private": True})
    assert await host.invoke_panel(IDS[2], note["id"], principal=OWNER) is None


async def test_voice_no_model_without_valid_duration_and_race(capture):
    host, ai, http, notes = capture
    invalid, _ = await attached(host, notes, b"not an audio container", "audio/ogg")
    await voice_memos.process(ctx(host, IDS[2], invalid), invalid["id"])
    assert not ai.calls
    note, _ = await attached(host, notes, wav(), "audio/wav")

    async def changed():
        await notes.flags(note["id"], {"private": True})

    ai.callback = changed
    await voice_memos.process(ctx(host, IDS[2], note), note["id"])
    async with host.store.read() as conn:
        assert not await conn.execute_fetchall(
            "SELECT * FROM suggestion WHERE note_id=?", (note["id"],)
        )


async def test_webhook_delivery_idempotency_and_configured_only(capture):
    host, ai, http, notes = capture
    await host.set_settings(IDS[3], {"urls": ["https://receiver.test/hook"]})
    note = await notes.create("Synthetic event #fixture")
    await webhooks.saved(ctx(host, IDS[3], note), note["id"], note["version"])
    await webhooks.saved(ctx(host, IDS[3], note), note["id"], note["version"])
    assert len(http.calls) == 1
    url, options = http.calls[0]
    payload = json.loads(options["body"])
    assert url == "https://receiver.test/hook" and payload["body"] == note["body"]
    assert options["headers"]["Idempotency-Key"] == payload["event_id"]
    async with host.store.read() as conn:
        records = await conn.execute_fetchall(
            "SELECT data FROM plugin_record WHERE plugin_id=?", (IDS[3],)
        )
        assert all(note["body"] not in row[0] for row in records)


async def test_webhook_retry_all_destinations_and_private_cancellation(capture):
    host, ai, http, notes = capture
    await host.set_settings(IDS[3], {"urls": ["https://one.test/hook", "https://two.test/hook"]})
    note = await notes.create("Synthetic transient event")
    http.responses = [FetchError("fetch_timeout"), FetchResponse(503, httpx.Headers(), b"")]
    await webhooks.saved(ctx(host, IDS[3], note), note["id"], note["version"])
    async with host.store.read() as conn:
        row = (
            await conn.execute_fetchall(
                "SELECT * FROM job WHERE plugin_id=? AND kind='job:deliver'", (IDS[3],)
            )
        )[0]
    args = json.loads(row["args"])
    args.pop("_host_unattended")
    assert len(args["targets"]) == 2 and args["attempt"] == 1
    await notes.flags(note["id"], {"private": True})
    await webhooks.deliver(ctx(host, IDS[3], note, operation="job:deliver"), **args)
    assert len(http.calls) == 2


async def test_webhook_tombstones_fail_closed_and_no_private_ids(capture):
    host, ai, http, notes = capture
    await host.set_settings(IDS[3], {"urls": ["https://receiver.test/hook"]})
    note = await notes.create("Synthetic public then private")
    trashed = await notes.flags(note["id"], {"trashed": True, "private": True})
    await webhooks.trashed(
        ctx(host, IDS[3], note),
        note["id"],
        trashed["version"],
        private=True,
        machine=False,
        origin="owner",
        change_seq=trashed["change_seq"],
    )
    await notes.delete(note["id"])
    await webhooks.purged(
        ctx(host, IDS[3], note),
        note["id"],
        trashed["version"],
        change_seq=trashed["change_seq"] + 1,
    )
    await webhooks.purged(
        ctx(host, IDS[3], note),
        note["id"],
        trashed["version"],
        private=True,
        machine=False,
        origin="owner",
        change_seq=trashed["change_seq"] + 1,
    )
    assert not http.calls


async def test_webhook_public_purge_metadata_and_stable_retry(capture):
    host, ai, http, notes = capture
    await host.set_settings(IDS[3], {"urls": ["https://receiver.test/hook"]})
    note = await notes.create("Synthetic purge")
    events = []

    async def sink(event, **args):
        events.append((event, args))

    host.store.event_sink = sink
    await notes.flags(note["id"], {"trashed": True})
    await notes.delete(note["id"])
    await host.store.drain_events()
    args = next(args for event, args in events if event == "note.purged")
    assert args["private"] is False and "body" not in args
    await webhooks.purged(ctx(host, IDS[3], note), **args)
    sent = json.loads(http.calls[0][1]["body"])
    assert "body" not in sent and sent["id"] == note["id"]


def test_duration_ogg_wav_and_bounded_untrusted_page():
    assert audio_duration(wav(2), "audio/wav") == 2
    data, _ = ogg_cases()["native-style"]
    assert audio_duration(data, "audio/ogg") == 1
    assert audio_duration(data[:-1], "audio/ogg") is None
    assert audio_duration(b"garbage", "audio/ogg") is None
    title, page, words = page_details(
        b"<title>Safe</title><script>ignore me</script><p>visible text</p>"
    )
    assert title == "Safe" and page == "visible text" and words == 2
    assert len(bounded("😀" * 20000).encode()) == 12000


@pytest.mark.parametrize(
    ("data", "seconds"),
    [pytest.param(data, seconds, id=name) for name, (data, seconds) in ogg_cases().items()],
)
def test_ogg_duration_requires_complete_single_stream_mono_opus(data, seconds):
    assert audio_duration(data, "audio/ogg") == seconds


async def test_voice_ogg_container_gate_keeps_originals_and_never_sends_invalid_audio(capture):
    host, ai, http, notes = capture
    fake = FakeProvider()
    profile = Profile(
        name="ogg-fixture",
        base_url="http://provider.test/v1",
        chat_model="fake",
        transcribe_model="fake",
        rate_per_min=100000,
    )
    settings = host.settings.model_copy(
        update={
            "ai_profiles": {profile.name: profile},
            "ai_chat_profile": profile.name,
            "ai_transcribe_profile": profile.name,
        }
    )
    gateway = Gateway(host.store, settings, transport=httpx.ASGITransport(fake.app))
    host.gateway = gateway
    try:
        for name, (data, seconds) in ogg_cases().items():
            if name == "maximum-duration":
                continue
            note, attachment = await attached(host, notes, data, "audio/ogg", name)
            scoped = ctx(host, IDS[2], note)
            previous = len(fake.requests)
            await voice_memos.process(scoped, note["id"])
            assert len(fake.requests) == previous + (seconds is not None), name
            if seconds is not None:
                assert fake.requests[-1]["path"] == "/v1/audio/transcriptions", name
            assert (await notes.get(note["id"]))["body"] == note["body"], name
            assert await scoped.attachments.read(attachment["id"]) == data, name
            async with host.store.read() as conn:
                suggestions = await conn.execute_fetchall(
                    "SELECT * FROM suggestion WHERE note_id=?", (note["id"],)
                )
            assert len(suggestions) == (seconds is not None), name
    finally:
        await gateway.close()


async def test_attachment_read_rechecks_link_after_io(capture, monkeypatch):
    host, ai, http, notes = capture
    note, attachment = await attached(host, notes, b"\x89PNG\r\n\x1a\nread race", "image/png")
    scoped = ctx(host, IDS[1], note)
    original = scoped.to_thread

    async def read_then_detach(fn, *args, **kwargs):
        data = await original(fn, *args, **kwargs)
        await notes.edit(note["id"], "Detached during IO", note["version"])
        return data

    monkeypatch.setattr(scoped, "to_thread", read_then_detach)
    with pytest.raises(ContextError, match="attachment_source_changed"):
        await scoped.attachments.read(attachment["id"], note_id=note["id"])
    assert not ai.calls


async def test_link_publication_resume_uses_receipt_not_network(capture, monkeypatch):
    host, ai, http, notes = capture
    note = await notes.create("https://public.test/interrupted")
    original = PluginNotes.annotate
    calls = 0

    async def fail_once(self, *args, **kwargs):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise RuntimeError("synthetic interrupted publication")
        return await original(self, *args, **kwargs)

    monkeypatch.setattr(PluginNotes, "annotate", fail_once)
    with pytest.raises(RuntimeError, match="interrupted"):
        await link_enrichment.enrich(ctx(host, IDS[0], note), note["id"])
    await link_enrichment.enrich(ctx(host, IDS[0], note), note["id"])
    assert len(http.calls) == 1 and len(ai.calls) == 1
    async with host.store.read() as conn:
        assert (
            len(
                await conn.execute_fetchall(
                    "SELECT * FROM annotation WHERE note_id=?", (note["id"],)
                )
            )
            == 2
        )


async def test_webhook_retry_reuses_key_and_stops_on_config_change(capture):
    host, ai, http, notes = capture
    await host.set_settings(IDS[3], {"urls": ["https://receiver.test/hook"]})
    note = await notes.create("Synthetic lost response")
    http.responses = [FetchError("fetch_timeout")]
    await webhooks.saved(ctx(host, IDS[3], note), note["id"])
    first_key = http.calls[0][1]["headers"]["Idempotency-Key"]
    await webhooks.dispatch(
        ctx(host, IDS[3], note, operation="job:deliver"),
        note["id"],
        "note.saved",
        note["version"],
        note["change_seq"],
        targets=["https://receiver.test/hook"],
        attempt=1,
    )
    assert len(http.calls) == 2 and http.calls[1][1]["headers"]["Idempotency-Key"] == first_key
    other = await notes.create("Synthetic destination removed")
    await host.set_settings(IDS[3], {"urls": []})
    await webhooks.dispatch(
        ctx(host, IDS[3], other, operation="job:deliver"),
        other["id"],
        "note.saved",
        other["version"],
        other["change_seq"],
        targets=["https://receiver.test/hook"],
        attempt=1,
    )
    assert len(http.calls) == 2


async def test_missing_vision_transcription_binding_waits_without_calls(capture):
    host, ai, http, notes = capture
    host.gateway = None
    await host.discover()
    assert host.states[IDS[1]]["state"] == "waiting"
    assert host.states[IDS[2]]["state"] == "waiting"
    assert IDS[1] not in {item["id"] for item in host.manifest()["plugins"]}


async def test_image_keyword_index_removes_unlinked_results(capture):
    host, ai, http, notes = capture
    note, attachment = await attached(host, notes, b"\x89PNG\r\n\x1a\nkeyword", "image/png")
    await image_text.process(ctx(host, IDS[1], note), note["id"])
    async with host.store.read() as conn:
        assert await conn.execute_fetchall(
            "SELECT rowid FROM note_fts WHERE note_fts MATCH 'lighthouse'"
        )
    changed = await notes.edit(note["id"], "Image detached", note["version"])
    await image_text.process(ctx(host, IDS[1], changed), note["id"])
    assert await host.invoke_panel(IDS[1], note["id"], principal=OWNER) is None
    async with host.store.read() as conn:
        assert not await conn.execute_fetchall(
            "SELECT rowid FROM note_fts WHERE note_fts MATCH 'lighthouse'"
        )
    assert len(ai.calls) == 1


async def test_voice_multiple_recordings_make_one_combined_diff(capture):
    host, ai, http, notes = capture
    first = await write_attachment(
        host.store, host.settings, wav(1), filename="one.wav", mime="audio/wav"
    )
    second = await write_attachment(
        host.store, host.settings, wav(2), filename="two.wav", mime="audio/wav"
    )
    note = await notes.create(first["snippet"] + "\n" + second["snippet"])
    original = ai.transcribe

    async def transcript(audio, mime, **options):
        await original(audio, mime, **options)
        return SimpleNamespace(text="Clip one." if options["duration_s"] == 1 else "Clip two.")

    ai.transcribe = transcript
    await voice_memos.process(ctx(host, IDS[2], note), note["id"])
    async with host.store.read() as conn:
        rows = await conn.execute_fetchall(
            "SELECT * FROM suggestion WHERE note_id=? AND status='pending'", (note["id"],)
        )
    assert len(rows) == 1
    proposed = json.loads(rows[0]["payload"])["body"]
    assert "Clip one." in proposed and "Clip two." in proposed
    assert first["id"] in proposed and second["id"] in proposed
    assert len(ai.calls) == 2
    await voice_memos.process(ctx(host, IDS[2], note), note["id"])
    assert len(ai.calls) == 2


async def test_ai_off_keeps_link_title_reading_time_and_search(capture):
    host, ai, http, notes = capture
    ai.available = lambda cap: False
    note = await notes.create("https://public.test/no-ai")
    await host.invoke_command(IDS[0], "fetch", {"note_id": note["id"]}, principal=OWNER)
    assert len(http.calls) == 1 and not ai.calls
    async with host.store.read() as conn:
        assert await conn.execute_fetchall(
            "SELECT rowid FROM note_fts WHERE note_fts MATCH 'Synthetic'"
        )
        assert (
            len(
                await conn.execute_fetchall(
                    "SELECT * FROM suggestion WHERE note_id=?", (note["id"],)
                )
            )
            == 1
        )


async def test_no_agent_command_can_forge_owner_capture(capture):
    host, ai, http, notes = capture
    note = await notes.create("https://public.test/agent-input")
    agent = Principal("synthetic-agent", "pat", "Agent", "write", private_ok=True)
    with pytest.raises(ContextError, match="owner_required"):
        await host.invoke_command(IDS[0], "fetch", {"note_id": note["id"]}, principal=agent)
    assert not http.calls


async def test_transcript_accept_diff_preserves_comment_and_blob(capture):
    host, ai, http, notes = capture
    note, attachment = await attached(host, notes, wav(), "audio/wav", "Owner comment")
    await voice_memos.process(ctx(host, IDS[2], note), note["id"])
    async with host.store.read() as conn:
        suggestion = (
            await conn.execute_fetchall(
                "SELECT id FROM suggestion WHERE note_id=? AND status='pending'", (note["id"],)
            )
        )[0][0]
    await decide(host.store, OWNER, suggestion, "accept")
    saved = await notes.get(note["id"])
    assert (
        saved["body"].startswith("Owner comment") and "Synthetic voice transcript." in saved["body"]
    )
    assert attachment["id"] in saved["body"]
    # Acceptance must not invoke transcription again, or append a duplicate transcript.
    await voice_memos.process(ctx(host, IDS[2], saved), note["id"])
    assert len(ai.calls) == 1


async def test_large_transcript_records_failure_once_and_keeps_audio(capture):
    host, ai, http, notes = capture
    note, attachment = await attached(host, notes, wav(), "audio/wav")
    calls = 0

    async def large(audio, mime, **kwargs):
        nonlocal calls
        calls += 1
        return SimpleNamespace(text='\\"' * 40000)

    ai.transcribe = large
    await voice_memos.process(ctx(host, IDS[2], note), note["id"])
    await voice_memos.process(ctx(host, IDS[2], note), note["id"])
    assert calls == 1
    assert (await notes.get(note["id"]))["body"] == note["body"]
    value = await host.invoke_panel(IDS[2], note["id"], principal=OWNER)
    assert "budget" in value["text"]
    async with host.store.read() as conn:
        assert not await conn.execute_fetchall(
            "SELECT * FROM suggestion WHERE note_id=?", (note["id"],)
        )


def test_markdown_annotation_budget_includes_json_escapes():
    value = markdown_output('\\"😀' * 4000)
    assert len(json.dumps(value, ensure_ascii=False).encode()) <= 3800
    validate_output(value)


async def test_real_gateway_vision_and_transcription_adapter_paths(capture):
    host, ai, http, notes = capture
    fake = FakeProvider(output={"text": "SYNTHETIC adapter OCR", "description": "A fixture."})
    profile = Profile(
        name="capture-fixture",
        base_url="http://provider.test/v1",
        chat_model="fake",
        vision_model="fake",
        transcribe_model="fake",
        rate_per_min=100000,
    )
    settings = host.settings.model_copy(
        update={
            "ai_profiles": {profile.name: profile},
            "ai_chat_profile": profile.name,
            "ai_vision_profile": profile.name,
            "ai_transcribe_profile": profile.name,
        }
    )
    gateway = Gateway(host.store, settings, transport=httpx.ASGITransport(fake.app))
    host.gateway = gateway
    try:
        note, attachment = await attached(
            host, notes, b"\x89PNG\r\n\x1a\nsynthetic adapter", "image/png"
        )
        await image_text.process(ctx(host, IDS[1], note), note["id"])
        assert len(fake.requests) == 1
        request = fake.requests[0]["body"]
        content = request["messages"][1]["content"]
        assert any(part.get("type") == "image_url" for part in content)
        prompt = next(part["text"] for part in content if part["type"] == "text")
        assert '<note id="' + note["id"] in prompt
        voice, blob = await attached(host, notes, wav(), "audio/wav")
        await voice_memos.process(ctx(host, IDS[2], voice), voice["id"])
        assert len(fake.requests) == 2 and fake.requests[-1]["path"] == "/v1/audio/transcriptions"
        assert (await notes.get(voice["id"]))["body"] == voice["body"]
    finally:
        await gateway.close()


@pytest.mark.parametrize("mutation", ["private", "edit", "machine", "disable", "destination"])
async def test_real_webhook_guard_rechecks_source_after_receiver_dns(
    capture, monkeypatch, mutation
):
    from spunto import net
    from spunto.net import fetch as network

    host, ai, http, notes = capture
    await host.set_settings(IDS[3], {"urls": ["https://receiver.test/hook"]})
    note = await notes.create("Synthetic source before delayed DNS")
    sent = []

    async def send(request):
        sent.append(request)
        return httpx.Response(200, text="synthetic")

    async def local():
        return set()

    async def resolve(hostname, port):
        if hostname == "receiver.test":
            if mutation == "private":
                await notes.flags(note["id"], {"private": True})
            elif mutation == "edit":
                await notes.edit(note["id"], "Changed before delivery", note["version"])
            elif mutation == "machine":
                async with host.store.write() as conn:
                    await conn.execute("UPDATE note SET machine=1 WHERE id=?", (note["id"],))
            elif mutation == "disable":
                await host.disable(IDS[3])
            else:
                await host.set_settings(IDS[3], {"urls": []})
            return ["93.184.216.34"]
        return ["1.1.1.1"]

    original = network.guarded_fetch

    async def guarded(url, **kwargs):
        return await original(url, resolver=resolve, transport=httpx.MockTransport(send), **kwargs)

    monkeypatch.setattr(network, "_local_addresses", local)
    monkeypatch.setattr(net, "guarded_fetch", guarded)
    host.fetcher = host._fetch
    if mutation == "disable":
        with pytest.raises(ContextError, match="plugin_disabled"):
            await webhooks.saved(ctx(host, IDS[3], note), note["id"])
    else:
        await webhooks.saved(ctx(host, IDS[3], note), note["id"])
    assert not sent
    async with host.store.read() as conn:
        assert not await conn.execute_fetchall(
            "SELECT 1 FROM job WHERE plugin_id=? AND kind='job:deliver'", (IDS[3],)
        )


async def test_link_source_change_during_dns_never_sends_get(capture, monkeypatch):
    from spunto import net
    from spunto.net import fetch as network

    host, ai, http, notes = capture
    note = await notes.create("https://public.test/late-private")
    sent = []

    async def local():
        return set()

    async def resolve(hostname, port):
        if hostname == "public.test":
            await notes.flags(note["id"], {"private": True})
            return ["93.184.216.34"]
        return ["1.1.1.1"]

    async def send(request):
        sent.append(request)
        return httpx.Response(200)

    original = network.guarded_fetch

    async def guarded(url, **kwargs):
        return await original(url, resolver=resolve, transport=httpx.MockTransport(send), **kwargs)

    monkeypatch.setattr(network, "_local_addresses", local)
    monkeypatch.setattr(net, "guarded_fetch", guarded)
    host.fetcher = host._fetch
    result = await host.invoke_command(IDS[0], "fetch", {"note_id": note["id"]}, principal=OWNER)
    assert "changed" in result["text"] and not sent and not ai.calls


async def test_cached_voice_transcript_survives_comment_edit_as_current_diff(capture):
    host, ai, http, notes = capture
    note, attachment = await attached(host, notes, wav(), "audio/wav", "Initial owner comment")
    await host.invoke_command(IDS[2], "transcribe", {"note_id": note["id"]}, principal=OWNER)
    changed = await notes.edit(
        note["id"], "Edited owner comment\n\n" + attachment["snippet"], note["version"]
    )
    value = await host.invoke_command(
        IDS[2], "transcribe", {"note_id": note["id"]}, principal=OWNER
    )
    assert value is not None and len(ai.calls) == 1
    async with host.store.read() as conn:
        rows = await conn.execute_fetchall(
            "SELECT * FROM suggestion WHERE note_id=? AND status='pending'", (note["id"],)
        )
    assert len(rows) == 1 and rows[0]["note_version"] == changed["version"]
    proposed = json.loads(rows[0]["payload"])["body"]
    assert proposed.startswith("Edited owner comment")
    assert "Synthetic voice transcript." in proposed and attachment["id"] in proposed
    assert (await notes.get(note["id"]))["body"] == changed["body"]


@pytest.mark.parametrize("decision", ["accept", "dismiss"])
async def test_cached_voice_preserves_owner_decision_after_edit(capture, decision):
    host, ai, http, notes = capture
    note, attachment = await attached(host, notes, wav(), "audio/wav")
    await voice_memos.process(ctx(host, IDS[2], note), note["id"])
    async with host.store.read() as conn:
        suggestion = (
            await conn.execute_fetchall(
                "SELECT id FROM suggestion WHERE note_id=? AND status='pending'", (note["id"],)
            )
        )[0][0]
    await decide(host.store, OWNER, suggestion, decision)
    current_note = await notes.get(note["id"])
    changed = await notes.edit(
        note["id"], "Owner intentionally revised\n" + attachment["snippet"], current_note["version"]
    )
    await host.invoke_command(IDS[2], "transcribe", {"note_id": note["id"]}, principal=OWNER)
    assert len(ai.calls) == 1
    async with host.store.read() as conn:
        assert not await conn.execute_fetchall(
            "SELECT * FROM suggestion WHERE note_id=? AND status='pending'", (note["id"],)
        )
    assert (await notes.get(note["id"]))["body"] == changed["body"]


async def test_maximum_html_parser_runs_off_event_loop(capture):
    host, ai, http, notes = capture
    ai.available = lambda capability: False
    note = await notes.create("https://public.test/large")
    body = b"<title>Synthetic large page</title>" + b"<p>synthetic text</p>" * 230000
    assert 4_000_000 < len(body) < 5_000_000
    http.responses = [FetchResponse(200, httpx.Headers({"content-type": "text/html"}), body)]
    await host.invoke_command(IDS[0], "fetch", {"note_id": note["id"]}, principal=OWNER)
    assert not host.loop_violations and len(http.calls) == 1


async def test_real_trashed_event_guard_rechecks_private_state_after_dns(capture, monkeypatch):
    from spunto import net
    from spunto.net import fetch as network

    host, ai, http, notes = capture
    await host.set_settings(IDS[3], {"urls": ["https://receiver.test/hook"]})
    note = await notes.create("Synthetic public trash")
    trashed = await notes.flags(note["id"], {"trashed": True})
    sent = []

    async def local():
        return set()

    async def resolve(hostname, port):
        if hostname == "receiver.test":
            await notes.flags(note["id"], {"private": True})
            return ["93.184.216.34"]
        return ["1.1.1.1"]

    async def send(request):
        sent.append(request)
        return httpx.Response(200)

    original = network.guarded_fetch

    async def guarded(url, **kwargs):
        return await original(url, resolver=resolve, transport=httpx.MockTransport(send), **kwargs)

    monkeypatch.setattr(network, "_local_addresses", local)
    monkeypatch.setattr(net, "guarded_fetch", guarded)
    host.fetcher = host._fetch
    await webhooks.trashed(
        ctx(host, IDS[3], note),
        note["id"],
        trashed["version"],
        private=False,
        machine=False,
        origin="owner",
        change_seq=trashed["change_seq"],
    )
    assert not sent
