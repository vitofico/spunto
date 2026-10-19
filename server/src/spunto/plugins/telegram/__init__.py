"""Private allowlisted bot messages; no outgoing messages or token logging."""

import hashlib
import json
import re

from spunto.host import Ctx, Plugin
from spunto.host.plugin import ContextError
from spunto.net import FetchError

plugin = Plugin("telegram")


@plugin.on("plugin.enabled")
async def start(ctx: Ctx):
    await ctx.enqueue("poll", priority=5)


@plugin.job("poll")
async def poll(ctx: Ctx):
    if any(
        not re.fullmatch(r"[1-9][0-9]{0,19}", value) for value in ctx.settings["allow_accounts"]
    ):
        await ctx.report_status("telegram_allowlist_invalid")
        await ctx.enqueue("poll", delay_ms=3_600_000, priority=5)
        return
    token = ctx.settings["bot_token"]
    if not re.fullmatch(r"[0-9]{1,20}:[A-Za-z0-9_-]{15,100}", token):
        await ctx.report_status("telegram_token_invalid")
        await ctx.enqueue("poll", delay_ms=3_600_000, priority=5)
        return
    source = hashlib.sha256(token.encode()).hexdigest()[:24]
    checkpoint = await ctx.storage.kv.get("checkpoint") or {}
    offset = checkpoint.get("offset", 0) if checkpoint.get("source") == source else 0
    error, delay = None, 60_000
    try:
        response = await ctx.fetch(
            "https://api.telegram.org/bot" + token + "/getUpdates",
            method="POST",
            headers={"Content-Type": "application/json"},
            body=json.dumps(
                {"offset": offset, "limit": 5, "timeout": 0, "allowed_updates": ["message"]}
            ).encode(),
            max_bytes=262_144,
            timeout=10,
        )
        payload = response.json()
        if (
            response.status_code != 200
            or not isinstance(payload, dict)
            or payload.get("ok") is not True
            or not isinstance(payload.get("result"), list)
            or len(payload["result"]) > 5
        ):
            raise FetchError("telegram_invalid_response")
        ids = [
            item.get("update_id") if isinstance(item, dict) else None for item in payload["result"]
        ]
        if any(type(uid) is not int or uid < offset for uid in ids) or ids != sorted(set(ids)):
            raise FetchError("telegram_invalid_response")
        for update in payload["result"]:
            ctx.check()
            uid = update.get("update_id") if isinstance(update, dict) else None
            if type(uid) is not int or uid < offset:
                raise FetchError("telegram_invalid_response")
            message = update.get("message", {})
            sender = message.get("from", {}) if isinstance(message, dict) else {}
            chat = message.get("chat", {}) if isinstance(message, dict) else {}
            sender_id = sender.get("id") if isinstance(sender, dict) else None
            allowed = {
                value
                for value in ctx.settings["allow_accounts"]
                if re.fullmatch(r"[1-9][0-9]{0,19}", value)
            }
            accepted = (
                type(sender_id) is int
                and str(sender_id) in allowed
                and sender.get("is_bot") is False
                and isinstance(chat, dict)
                and chat.get("type") == "private"
                and type(chat.get("id")) is int
                and chat.get("id") == sender_id
            )
            if accepted:
                body = message.get("text", message.get("caption"))
                if not isinstance(body, str) or not body.strip() or len(body) > 100_000:
                    error, delay = "telegram_message_unsupported", 3_600_000
                    break
                await ctx.notes.create(
                    body.replace("\x00", "\ufffd"), request_id=f"telegram:{source}:{uid}"
                )
            offset = uid + 1
            await ctx.storage.kv.set("checkpoint", {"source": source, "offset": offset})
    except FetchError:
        error = "telegram_poll_failed"
    except ContextError as failure:
        if str(failure) != "creation_daily_limit":
            raise
        error, delay = "creation_daily_limit", 3_600_000
    await ctx.report_status(error)
    await ctx.enqueue("poll", delay_ms=delay, priority=5)
