"""Synthetic inbound transports and real-SQLite durable receipts/privacy budgets."""

import asyncio
import json
from contextlib import asynccontextmanager
from email.message import EmailMessage
from pathlib import Path

import httpx
import pytest

from spunto.core.plugin_notes import PluginNotes
from spunto.host import Ctx
from spunto.host.plugin import ContextError
from spunto.host.testing import FakeClock, harness
from spunto.net import FetchResponse
from spunto.net.imap import MailboxError
from spunto.plugins import email_in, telegram

pytestmark = pytest.mark.anyio
TOKEN = "123456:synthetic-token-not-live"


def mail(
    sender="allowed@example.invalid", body="Synthetic incoming mail", subject="Synthetic subject"
):
    message = EmailMessage()
    message["From"], message["To"], message["Subject"] = sender, "owner@example.invalid", subject
    message.set_content(body)
    return message.as_bytes()


def update(uid, *, sender=42, text="Synthetic bot message", kind="private", bot=False):
    return {
        "update_id": uid,
        "message": {
            "from": {"id": sender, "is_bot": bot},
            "chat": {"id": sender, "type": kind},
            "text": text,
        },
    }


@pytest.fixture
async def inbound(tmp_path, settings, monkeypatch):
    monkeypatch.setenv("SPUNTO_PLUGIN_EMAIL_IN_PASSWORD", "synthetic mailbox password")
    monkeypatch.setenv("SPUNTO_PLUGIN_TELEGRAM_BOT_TOKEN", TOKEN)
    clock = FakeClock()
    async with harness(
        tmp_path,
        settings.model_copy(update={"data_dir": tmp_path}),
        notes_factory=PluginNotes,
        catalog=[Path(email_in.__file__).parent, Path(telegram.__file__).parent],
        clock=clock,
    ) as host:
        for plugin_id in ("email-in", "telegram"):
            await host.enable(plugin_id, host.manifests[plugin_id]["permissions"])
            assert host.states[plugin_id]["state"] == "waiting"
            assert host.states[plugin_id]["last_error"] == "Needs a list of allowed senders"
        await host.set_settings(
            "email-in",
            {
                "url": "imaps://mail.example.invalid:993",
                "username": "synthetic-owner",
                "allow_senders": ["allowed@example.invalid"],
            },
        )
        await host.set_settings("telegram", {"allow_accounts": ["42"]})
        assert all(row["state"] == "enabled" for row in host.states.values())
        yield host, clock


async def rows(host):
    async with host.store.read() as conn:
        return [
            dict(row) for row in await conn.execute_fetchall("SELECT * FROM note ORDER BY rowid")
        ]


async def test_secrets_are_environment_only_waiting_is_generic_and_status_bounded(inbound):
    host, _ = inbound
    assert "synthetic mailbox password" not in json.dumps(host.catalog())
    assert TOKEN not in json.dumps(host.manifest())
    assert "net.out:<configured>" in host.manifests["email-in"]["permissions"]
    assert host.catalog()[0]["network_destinations"] == ["mail.example.invalid"]
    ctx = Ctx(host, "telegram", unattended=True)
    with pytest.raises(ValueError):
        await ctx.report_status("https://credential.invalid/body")
    await ctx.report_status("telegram_poll_failed")
    assert host.states["telegram"]["last_error"] == "telegram_poll_failed"
    await ctx.report_status()
    assert host.states["telegram"]["last_error"] is None


async def test_email_allowlist_external_origin_uidvalidity_and_exactly_once(inbound, monkeypatch):
    import spunto.net.imap

    host, _ = inbound
    calls = []

    async def read(url, **kwargs):
        calls.append(kwargs)
        return {
            "uidvalidity": 77,
            "messages": [
                {"uid": 1, "raw": mail("denied@example.invalid")},
                {"uid": 2, "raw": mail()},
            ]
            if kwargs["after_uid"] == 0
            else [],
        }

    monkeypatch.setattr(spunto.net.imap, "guarded_read", read)
    ctx = Ctx(host, "email-in", unattended=True)
    await email_in.poll(ctx)
    await email_in.poll(ctx)
    notes = await rows(host)
    assert len(notes) == 1 and notes[0]["origin"] == "external" and notes[0]["machine"] == 0
    assert "Synthetic incoming mail" in notes[0]["body"]
    assert calls[1]["after_uid"] == 2 and calls[1]["uidvalidity"] == 77
    assert (await ctx.storage.kv.get("checkpoint"))["uid"] == 2
    assert (
        "password"
        not in next(item for item in host.catalog() if item["id"] == "email-in")["settings"]
    )


async def test_email_receipt_survives_checkpoint_failure_and_daily_limit(inbound, monkeypatch):
    import spunto.net.imap
    from spunto.host.storage import KV

    host, clock = inbound

    async def read(url, **kwargs):
        after = kwargs["after_uid"]
        return {
            "uidvalidity": 77,
            "messages": [
                {"uid": uid, "raw": mail(body=f"Synthetic message {uid}")}
                for uid in range(after + 1, min(7, after + 6))
            ],
        }

    monkeypatch.setattr(spunto.net.imap, "guarded_read", read)
    ctx = Ctx(host, "email-in", unattended=True)
    original = KV.set

    async def fail_checkpoint(self, key, value):
        if key == "checkpoint":
            raise OSError("synthetic checkpoint crash")
        return await original(self, key, value)

    with monkeypatch.context() as patch:
        patch.setattr(KV, "set", fail_checkpoint)
        with pytest.raises(OSError):
            await email_in.poll(ctx)
    assert len(await rows(host)) == 1
    await email_in.poll(ctx)
    assert len(await rows(host)) == 5
    await email_in.poll(ctx)
    assert (
        len(await rows(host)) == 5
        and host.states["email-in"]["last_error"] == "creation_daily_limit"
    )
    assert (await ctx.storage.kv.get("checkpoint"))["uid"] == 5
    clock.advance(86_400_000)
    await email_in.poll(ctx)
    assert len(await rows(host)) == 6
    assert (await ctx.storage.kv.get("checkpoint"))["uid"] == 6


async def test_email_transport_and_oversize_fail_closed_without_raw_status(inbound, monkeypatch):
    import spunto.net.imap

    host, _ = inbound
    ctx = Ctx(host, "email-in", unattended=True)

    async def read(url, **kwargs):
        raise MailboxError("imap_tls_failed")

    monkeypatch.setattr(spunto.net.imap, "guarded_read", read)
    await email_in.poll(ctx)
    assert not await rows(host)
    assert host.states["email-in"]["last_error"] == "imap_tls_failed"

    async def oversized(url, **kwargs):
        return {"uidvalidity": 1, "messages": [{"uid": 1, "error": "email_message_too_large"}]}

    monkeypatch.setattr(spunto.net.imap, "guarded_read", oversized)
    await email_in.poll(ctx)
    assert not await rows(host) and await ctx.storage.kv.get("checkpoint") is None
    assert host.states["email-in"]["last_error"] == "email_message_too_large"
    with pytest.raises(ContextError):
        await ctx.imap_read("imaps://arbitrary.invalid", username="x", password="y")
    with pytest.raises(ContextError, match="imap_option_denied"):
        await ctx.imap_read(ctx.settings["url"], resolver=lambda: None)


async def test_telegram_private_allowlist_checkpoint_and_no_outgoing_messages(inbound):
    host, _ = inbound
    requests = []

    async def fetch(ctx, url, **kwargs):
        request = json.loads(kwargs["body"])
        requests.append((url, request))
        result = [update(1, sender=99), update(2, kind="group"), update(3, bot=True), update(4)]
        return FetchResponse(
            200,
            httpx.Headers(),
            json.dumps(
                {
                    "ok": True,
                    "result": [item for item in result if item["update_id"] >= request["offset"]],
                }
            ).encode(),
        )

    host.fetcher = fetch
    ctx = Ctx(host, "telegram", unattended=True)
    await telegram.poll(ctx)
    await telegram.poll(ctx)
    notes = await rows(host)
    assert len(notes) == 1 and notes[0]["body"] == "Synthetic bot message"
    assert notes[0]["origin"] == "external" and notes[0]["machine"] == 0
    assert all(
        url.endswith("/getUpdates") and body["allowed_updates"] == ["message"]
        for url, body in requests
    )
    assert requests[1][1]["offset"] == 5


async def test_disable_or_settings_change_during_receive_cancels_no_resurrection(inbound):
    host, _ = inbound

    async def fetch(ctx, url, **kwargs):
        await host.set_settings("telegram", {"allow_accounts": ["99"]})
        return FetchResponse(
            200, httpx.Headers(), json.dumps({"ok": True, "result": [update(1)]}).encode()
        )

    host.fetcher = fetch
    with pytest.raises(ContextError, match="plugin_disabled"):
        await telegram.poll(Ctx(host, "telegram", unattended=True))
    assert not await rows(host)
    await host.disable("telegram")
    async with host.store.read() as conn:
        assert not await conn.execute_fetchall(
            "SELECT 1 FROM plugin_kv WHERE plugin_id='telegram' AND key='checkpoint'"
        )


@pytest.mark.parametrize(
    "result",
    [
        {"ok": False, "description": "https://credential.invalid/raw-error"},
        {"ok": True, "result": [update(1), update(1)]},
        {"ok": True, "result": [update(1), {"update_id": True}]},
    ],
)
async def test_telegram_invalid_whole_response_has_no_partial_writes(inbound, result, caplog):
    host, _ = inbound

    async def fetch(ctx, url, **kwargs):
        return FetchResponse(200, httpx.Headers(), json.dumps(result).encode())

    host.fetcher = fetch
    ctx = Ctx(host, "telegram", unattended=True)
    await telegram.poll(ctx)
    assert not await rows(host) and await ctx.storage.kv.get("checkpoint") is None
    assert host.states["telegram"]["last_error"] == "telegram_poll_failed"
    assert TOKEN not in caplog.text and "credential.invalid" not in caplog.text


async def test_email_malformed_batch_and_html_fencing(inbound, monkeypatch):
    import spunto.net.imap

    host, _ = inbound

    async def malformed(url, **kwargs):
        return {
            "uidvalidity": 1,
            "messages": [{"uid": 1, "raw": mail()}, {"uid": 1, "raw": mail()}],
        }

    monkeypatch.setattr(spunto.net.imap, "guarded_read", malformed)
    ctx = Ctx(host, "email-in", unattended=True)
    await email_in.poll(ctx)
    assert not await rows(host) and host.states["email-in"]["last_error"] == "imap_invalid_response"
    raw = mail(
        body="<html><head><style>hidden</style></head><body><p>Visible synthetic words</p>"
        "<script>hidden</script></body></html>"
    )
    raw = raw.replace(b"text/plain", b"text/html")
    rendered = email_in._message(raw, {"allowed@example.invalid"})
    assert "Visible synthetic words" in rendered and "hidden" not in rendered


@pytest.mark.parametrize(
    "plugin_id, values, code",
    [
        ("telegram", {"allow_accounts": ["not-an-account-id"]}, "telegram_allowlist_invalid"),
        (
            "email-in",
            {
                "url": "imaps://mail.example.invalid",
                "username": "synthetic",
                "allow_senders": ["not-an-address"],
            },
            "email_allowlist_invalid",
        ),
    ],
)
async def test_invalid_allowlists_do_not_consume_remote_messages(inbound, plugin_id, values, code):
    host, _ = inbound
    await host.set_settings(plugin_id, values)
    ctx = Ctx(host, plugin_id, unattended=True)
    await (email_in.poll(ctx) if plugin_id == "email-in" else telegram.poll(ctx))
    assert not await rows(host) and await ctx.storage.kv.get("checkpoint") is None
    assert host.states[plugin_id]["last_error"] == code


async def test_settings_cannot_bypass_a_failed_plugin_circuit(inbound):
    host, _ = inbound
    await host._state("telegram", "failed", "plugin_circuit_open")
    await host.set_settings("telegram", {"allow_accounts": ["99"]})
    assert host.states["telegram"]["state"] == "failed"
    assert host.states["telegram"]["last_error"] == "plugin_circuit_open"


async def test_large_multipart_message_does_not_block_actual_host_watchdog(inbound, monkeypatch):
    import spunto.net.imap

    host, _ = inbound
    raw = (
        b"From: allowed@example.invalid\r\nSubject: Synthetic parser budget\r\n"
        b"MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=b\r\n\r\n"
        + b"--b\r\nContent-Type: text/plain\r\n\r\nx\r\n" * 6000
        + b"--b--\r\n"
    )
    assert len(raw) < 262_144

    async def receive(url, **kwargs):
        return {"uidvalidity": 1, "messages": [{"uid": 1, "raw": raw}]}

    monkeypatch.setattr(spunto.net.imap, "guarded_read", receive)
    ctx = Ctx(host, "email-in", unattended=True)
    await host._call_handler(email_in.poll, ctx, {})
    assert not host.loop_violations
    assert len(await rows(host)) == 1
    assert (await ctx.storage.kv.get("checkpoint"))["uid"] == 1


async def test_bad_destination_rejected_before_write_and_legacy_restart_is_safe(
    tmp_path, settings, monkeypatch
):
    from spunto.host.manifest import ManifestError

    monkeypatch.setenv("SPUNTO_PLUGIN_EMAIL_IN_PASSWORD", "synthetic mailbox password")
    directory = tmp_path / "legacy-destination"
    config = settings.model_copy(update={"data_dir": directory})
    catalog = [Path(email_in.__file__).parent]
    values = {
        "url": "imaps://mail.example.invalid",
        "username": "synthetic",
        "allow_senders": ["allowed@example.invalid"],
    }
    async with harness(directory, config, catalog=catalog, notes_factory=PluginNotes) as host:
        await host.enable("email-in", host.manifests["email-in"]["permissions"])
        await host.set_settings("email-in", values)
        with pytest.raises(ManifestError, match="destination is invalid"):
            await host.set_settings("email-in", {**values, "url": "imaps://["})
        assert host.states["email-in"]["settings"]["url"] == values["url"]
        legacy = {**values, "url": "imaps://["}
        async with host.store.write() as conn:
            await conn.execute(
                "UPDATE plugin SET settings=? WHERE id='email-in'", (json.dumps(legacy),)
            )
        host.states["email-in"]["settings"] = legacy
        assert host.catalog()[0]["network_destinations"] == []
    async with harness(directory, config, catalog=catalog, notes_factory=PluginNotes) as host:
        assert host.states["email-in"]["state"] == "waiting"
        assert host.states["email-in"]["last_error"] == "Needs an imaps:// mailbox server"
        assert host.catalog()[0]["network_destinations"] == []


@pytest.mark.parametrize("owner_action", ["trashed", "private", "owner-edited", "purged"])
async def test_creation_receipt_after_checkpoint_crash_never_reads_owner_or_resurrects(
    inbound, monkeypatch, owner_action
):
    import spunto.net.imap
    from spunto.auth.tokens import Principal
    from spunto.core.notes import Notes
    from spunto.host.storage import KV

    host, _ = inbound

    async def receive(url, **kwargs):
        return {
            "uidvalidity": 77,
            "messages": [{"uid": 1, "raw": mail()}] if kwargs["after_uid"] == 0 else [],
        }

    monkeypatch.setattr(spunto.net.imap, "guarded_read", receive)
    original = KV.set

    async def crash(self, key, value):
        if key == "checkpoint":
            raise OSError("synthetic checkpoint crash")
        return await original(self, key, value)

    with monkeypatch.context() as patch:
        patch.setattr(KV, "set", crash)
        with pytest.raises(OSError):
            await email_in.poll(Ctx(host, "email-in", unattended=True))
    initial = (await rows(host))[0]
    owner = Notes(host.store, Principal("owner", "session", "Owner", "full", private_ok=True))
    sensitive = "Synthetic owner-adopted words unavailable to inbound capture"
    await owner.edit(initial["id"], sensitive, initial["version"])
    if owner_action in {"trashed", "private", "purged"}:
        await owner.flags(
            initial["id"], {"private" if owner_action == "private" else "trashed": True}
        )
    if owner_action == "purged":
        await owner.delete(initial["id"])
    async with host.store.read() as conn:
        saved = await conn.execute_fetchall(
            "SELECT op_id FROM applied_op WHERE credential_id='plugin:email-in'"
        )
    ctx = Ctx(host, "email-in", unattended=True)
    assert "notes.read" not in host.states["email-in"]["granted"]
    ack = await ctx.notes.create("Original retry input", request_id=saved[0]["op_id"])
    assert ack["id"] == initial["id"] and ack["version"] == 1 and ack["replayed"] is True
    assert "body" not in ack and sensitive not in json.dumps(ack)
    assert not ctx._borrowed_notes
    await email_in.poll(ctx)
    assert (await ctx.storage.kv.get("checkpoint"))["uid"] == 1
    retained = await rows(host)
    assert len(retained) == (0 if owner_action == "purged" else 1)
    if retained:
        assert retained[0]["body"] == sensitive
        assert bool(retained[0]["private"]) == (owner_action == "private")
        assert bool(retained[0]["trashed_at"]) == (owner_action == "trashed")


async def test_legacy_id_only_creation_receipt_does_not_read_current_note(inbound):
    host, _ = inbound
    ctx = Ctx(host, "email-in", unattended=True)
    created = await ctx.notes.create("Synthetic original", request_id="legacy-receipt")
    async with host.store.write() as conn:
        await conn.execute(
            "UPDATE applied_op SET result=? WHERE credential_id=? AND op_id=?",
            (json.dumps({"id": created["id"]}), "plugin:email-in", "legacy-receipt"),
        )
    ack = await Ctx(host, "email-in", unattended=True).notes.create(
        "Retry", request_id="legacy-receipt"
    )
    assert ack == {"id": created["id"], "replayed": True}


async def test_replay_paused_in_sql_cannot_return_to_retired_context(inbound, monkeypatch):
    import aiosqlite

    host, _ = inbound
    ctx = Ctx(host, "email-in", unattended=True)
    created = await ctx.notes.create("Synthetic original", request_id="retirement-receipt")
    observed, release, retired = asyncio.Event(), asyncio.Event(), asyncio.Event()
    original = aiosqlite.Connection.execute_fetchall
    original_write = host.store.write

    @asynccontextmanager
    async def tracked_write():
        if host.generations["email-in"] != ctx.generation:
            retired.set()
        async with original_write() as connection:
            yield connection

    async def paused(connection, sql, parameters=None):
        result = await original(connection, sql, parameters)
        if sql.startswith("SELECT result FROM applied_op"):
            observed.set()
            await release.wait()
        return result

    monkeypatch.setattr(aiosqlite.Connection, "execute_fetchall", paused)
    monkeypatch.setattr(host.store, "write", tracked_write)
    replay = asyncio.create_task(ctx.notes.create("Retry", request_id="retirement-receipt"))
    await asyncio.wait_for(observed.wait(), 2)
    disabling = asyncio.create_task(host.disable("email-in"))
    await asyncio.wait_for(retired.wait(), 2)
    release.set()
    with pytest.raises(ContextError, match="plugin_disabled"):
        await replay
    await disabling
    assert host.states["email-in"]["state"] == "disabled"
    assert [note["id"] for note in await rows(host)] == [created["id"]]
