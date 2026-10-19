"""Allowlisted mail to external notes; read-only polling and durable UID receipts."""

import hashlib
from email import policy
from email.parser import BytesParser
from email.utils import getaddresses
from html.parser import HTMLParser

from spunto.host import Ctx, Plugin
from spunto.host.plugin import ContextError
from spunto.net.imap import MailboxError

plugin = Plugin("email-in")


class _Text(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts, self.hidden = [], 0

    def handle_starttag(self, tag, attrs):
        if tag in {"script", "style", "head"}:
            self.hidden += 1
        if tag in {"p", "div", "br", "li"} and not self.hidden:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in {"script", "style", "head"}:
            self.hidden = max(0, self.hidden - 1)

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


def _message(raw, allowed):
    message = BytesParser(policy=policy.default).parsebytes(raw)
    senders = getaddresses(message.get_all("From", []))
    if len(senders) != 1 or senders[0][1].casefold() not in allowed:
        return None
    part = message.get_body(preferencelist=("plain", "html"))
    if part is None:
        raise ValueError("email_text_unavailable")
    body = part.get_content()
    if not isinstance(body, str):
        raise ValueError("email_text_unavailable")
    if part.get_content_type() == "text/html":
        text = _Text()
        text.feed(body)
        body = "".join(text.parts)
    subject = " ".join(str(message.get("Subject", "Email")).split())
    result = f"# {subject}\n\nFrom: {senders[0][1]}\n\n{body.strip()}".replace("\x00", "\ufffd")
    if len(result) > 100_000:
        raise MailboxError("email_message_too_large")
    return result


@plugin.on("plugin.enabled")
async def start(ctx: Ctx):
    await ctx.enqueue("poll", priority=5)


@plugin.job("poll")
async def poll(ctx: Ctx):
    configuration = ctx.settings
    allowlist = configuration["allow_senders"]
    if any(
        len(getaddresses([value])) != 1
        or getaddresses([value])[0][1].casefold() != value.casefold()
        or "@" not in value
        or any(c.isspace() for c in value)
        for value in allowlist
    ):
        await ctx.report_status("email_allowlist_invalid")
        await ctx.enqueue("poll", delay_ms=3_600_000, priority=5)
        return
    source = hashlib.sha256(
        (
            configuration["url"] + "\0" + configuration["username"] + "\0" + configuration["folder"]
        ).encode()
    ).hexdigest()[:24]
    previous = await ctx.storage.kv.get("checkpoint") or {}
    if previous.get("source") != source:
        previous = {}
    error, delay = None, 60_000
    try:
        batch = await ctx.imap_read(
            configuration["url"],
            username=configuration["username"],
            password=configuration["password"],
            folder=configuration["folder"],
            after_uid=previous.get("uid", 0),
            uidvalidity=previous.get("uidvalidity"),
            limit=5,
        )
        if (
            not isinstance(batch, dict)
            or type(batch.get("uidvalidity")) is not int
            or not isinstance(batch.get("messages"), list)
            or len(batch["messages"]) > 5
        ):
            raise MailboxError("imap_invalid_response")
        validity = batch["uidvalidity"]
        after = previous.get("uid", 0) if previous.get("uidvalidity") == validity else 0
        ids = [item.get("uid") if isinstance(item, dict) else None for item in batch["messages"]]
        if (
            validity < 1
            or any(type(uid) is not int or uid <= after for uid in ids)
            or ids != sorted(set(ids))
        ):
            raise MailboxError("imap_invalid_response")
        for item in batch["messages"]:
            ctx.check()
            uid = item.get("uid")
            if type(uid) is not int or uid <= after:
                raise MailboxError("imap_invalid_response")
            if item.get("error"):
                error, delay = "email_message_too_large", 3_600_000
                break  # Retain it for operator resolution; never truncate or lose words.
            allowed = {value.casefold() for value in ctx.settings["allow_senders"]}
            try:
                body = await ctx.to_thread(_message, item["raw"], allowed)
            except ValueError, LookupError, UnicodeError, TypeError, KeyError:
                error, delay = "email_message_unsupported", 3_600_000
                break
            if body is not None:
                await ctx.notes.create(body, request_id=f"mail:{source}:{validity}:{uid}")
            after = uid
            await ctx.storage.kv.set(
                "checkpoint", {"source": source, "uidvalidity": validity, "uid": uid}
            )
    except MailboxError as failure:
        error = failure.code
    except ContextError as failure:
        if str(failure) != "creation_daily_limit":
            raise
        error, delay = "creation_daily_limit", 3_600_000
    await ctx.report_status(error)
    await ctx.enqueue("poll", delay_ms=delay, priority=5)
