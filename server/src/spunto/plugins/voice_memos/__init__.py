"""Immutable recordings remain attached; only an owner-accepted diff edits text."""

import re

from spunto.host import Ctx, Plugin
from spunto.host.manifest import ManifestError
from spunto.host.storage import encoded
from spunto.plugins._capture import (
    audio_duration,
    bounded,
    clean,
    current,
    identity,
    link,
    markdown,
    markdown_output,
    text,
)

plugin = Plugin("voice-memos")
MIMES = {"audio/ogg", "audio/opus", "audio/wav", "audio/x-wav"}


@plugin.on("attachment.added")
@plugin.on("note.saved")
@plugin.job("transcribe")
async def process(
    ctx: Ctx,
    note_id: str | None = None,
    note_version: int | None = None,
    attachment_id: str | None = None,
):
    if note_id is None:
        return None
    note = await ctx.capture.note(note_id, note_version=note_version)
    if note is None:
        return None
    attachments = [
        attachment
        for attachment in await ctx.attachments.list(note_id)
        if attachment["mime"] in MIMES
    ]
    pending, values = [], []
    for attachment in attachments:
        key = identity(note_id, attachment["sha256"])
        receipt = await ctx.storage.records.get("recordings", key)
        if receipt and receipt.get("complete"):
            if not receipt.get("words"):
                continue
            proposal = await ctx.capture.proposal_state(note_id, receipt.get("suggestion_id"))
            if proposal and proposal["status"] in {"accepted", "dismissed"}:
                continue
            if (
                proposal
                and proposal["status"] == "pending"
                and proposal["note_version"] == note["version"]
            ):
                values.append(receipt)
                continue
            # Reuse words after the owner changes a comment. A superseded proposal
            # is not an owner decision, and cannot remain the only usable diff.
            receipt = {**receipt, "complete": False}
        if receipt is None:
            if attachment_id and attachment_id != attachment["id"]:
                continue
            audio = await ctx.attachments.read(
                attachment["id"], note_id=note_id, note_version=note["version"]
            )
            duration = await ctx.to_thread(audio_duration, audio, attachment["mime"])
            if duration is None or not 0 < duration <= 3600:
                continue
            language = ctx.settings.get("language", "auto")
            transcript = await ctx.ai.transcribe(
                audio,
                attachment["mime"],
                duration_s=duration,
                language=None if language == "auto" else language,
                notes=[note],
            )
            if not await current(ctx, note, attachment):
                return text("The recording source changed.")
            words = clean(transcript.text, 100000).strip()
            if not words:
                continue
            receipt = {
                "attachment_id": attachment["id"],
                "seconds": round(duration, 2),
                "preview": bounded(words, 1800),
                "words": words,
                "complete": False,
            }
            try:
                encoded(receipt)
            except ManifestError:
                # A durable metadata-only failure prevents repeated charges on every save.
                receipt.update(
                    words="",
                    complete=True,
                    preview="Transcript exceeds the result budget; the recording is kept.",
                )
            await ctx.capture.store_result(
                "recordings", key, note_id, note["version"], receipt, attachment_id=attachment["id"]
            )
        if receipt.get("words"):
            values.append(receipt)
        if not receipt.get("complete"):
            pending.append((key, receipt, attachment))
    if not pending or not await current(ctx, note):
        return None
    body = note["body"]
    if not ctx.settings.get("keep_link", True):
        for attachment in attachments:
            body = re.sub(
                r"\[[^\]\n]*\]\(/a/" + re.escape(attachment["id"]) + r"\)", "", body
            ).strip()
    words = list(dict.fromkeys(value["words"] for value in values if value["words"] not in body))
    proposed = "\n\n".join([body, *words]).strip()
    if len(proposed) > 100000:
        return text("The transcripts are too long to append; recordings are kept.")
    suggestion = await ctx.notes.suggest(
        note_id, "replace_body", {"body": proposed}, note_version=note["version"]
    )
    for key, receipt, attachment in pending:
        receipt.update(complete=True, suggestion_id=suggestion["id"])
        await ctx.capture.store_result(
            "recordings", key, note_id, note["version"], receipt, attachment_id=attachment["id"]
        )
    return text("Transcript proposals are ready; recordings are kept.")


@plugin.command("transcribe")
async def transcribe(ctx: Ctx, note_id: str):
    return await process(ctx, note_id)


@plugin.panel()
async def panel(ctx: Ctx, id: str):
    links = {attachment["id"] for attachment in await ctx.attachments.list(id)}
    receipts = await ctx.storage.records.list("recordings", note_id=id)
    values = [row["data"] for row in receipts if row["data"]["attachment_id"] in links]
    if not values:
        return None
    return markdown_output(
        "\n\n".join(
            link(
                f"Recording {int(value['seconds']) // 60}:{int(value['seconds']) % 60:02d}",
                "/a/" + value["attachment_id"],
            )
            + "\n\n"
            + markdown(value["preview"])
            for value in values
        )
    )
