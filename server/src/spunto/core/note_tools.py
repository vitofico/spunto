"""Local input checks for bundled commands on an authorized note detail read."""

from spunto.core.attachments import linked_attachments
from spunto.core.plugin_capture import capture_source_eligible
from spunto.plugins._capture import source_url
from spunto.plugins._wave2 import MAX_SINGLE
from spunto.plugins.image_text import MIMES as IMAGE_MIMES
from spunto.plugins.label_suggestions import MIN_LABEL_WORDS
from spunto.plugins.title_summary import MIN_TITLE_WORDS
from spunto.plugins.voice_memos import MIMES as AUDIO_MIMES


async def inputs(conn, row, note):
    eligible = capture_source_eligible(row)
    mimes = (
        {item["mime"] for item in await linked_attachments(conn, row["id"], mime_only=True)}
        if eligible
        else set()
    )
    words = len(note["body"].split(maxsplit=MIN_TITLE_WORDS - 1)) if eligible else 0
    return {
        "image": bool(mimes & IMAGE_MIMES),
        "audio": bool(mimes & AUDIO_MIMES),
        "link": eligible and source_url(note) is not None,
        "title_summary": words >= MIN_TITLE_WORDS,
        "labels": words >= MIN_LABEL_WORDS,
        "short_text": words > 0 and len(note["body"]) <= MAX_SINGLE,
    }
