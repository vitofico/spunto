"""Portable Markdown, shared by ZIP export and note:// resources."""

import json
import os
import re
import sqlite3
import tempfile
import zipfile
from pathlib import Path

from markdown_it import MarkdownIt

from spunto.auth.tokens import Principal
from spunto.config import Settings
from spunto.core.notes import iso
from spunto.core.text import _code_mask, fold
from spunto.store.db import DB_NAME


def _rewrite_links(body: str, replacements: dict[str, str]) -> str:
    if not replacements:
        return body
    urls = "|".join(re.escape(url) for url in sorted(replacements, key=len, reverse=True))
    protected = _code_mask(body)
    offsets = [0]
    for line in body.splitlines(keepends=True):
        offsets.append(offsets[-1] + len(line))
    destinations = set()
    for token in MarkdownIt().parse(body):
        if token.type in {"code_block", "fence"} and token.map:
            start, end = offsets[token.map[0]], offsets[token.map[1]]
            protected[start:end] = b"\1" * (end - start)
        for child in token.children or []:
            if child.type == "link_open":
                destinations.add(child.attrGet("href"))
            if child.type == "image":
                destinations.add(child.attrGet("src"))
    edits = []
    brackets = []
    inline = re.compile(r"\([ \t\n]*<?(?P<url>" + urls + r")>?(?=[\s)])")
    for at, char in enumerate(body):
        if protected[at]:
            continue
        back = at
        while back and body[back - 1] == "\\":
            back -= 1
        if (at - back) % 2:
            continue
        if char == "[":
            brackets.append(at)
        elif char == "]" and brackets:
            brackets.pop()
            match = inline.match(body, at + 1)
            if match and match["url"] in destinations and match["url"] in replacements:
                start, end = match.span("url")
                edits.append((start, end, replacements[match["url"]]))
        elif char == "\n" and at and body[at - 1] == "\n":
            brackets.clear()
    reference = re.compile(r"(?m)^ {0,3}\[[^\n\]]+\]:[ \t]*<?(?P<url>" + urls + r")(?=[\s>]|$)")
    for match in reference.finditer(body):
        if match["url"] in destinations and match["url"] in replacements:
            start, end = match.span("url")
            if not protected[start]:
                edits.append((start, end, replacements[match["url"]]))
    for match in re.finditer(r"<(?P<url>" + urls + r")>", body):
        start, end = match.span("url")
        if any(left <= start and right >= end for left, right, _ in edits):
            continue
        if not protected[start] and match["url"] in destinations:
            back = match.start()
            while back and body[back - 1] == "\\":
                back -= 1
            if (match.start() - back) % 2 == 0:
                label = re.sub(r"([\\\[\]*_`])", r"\\\1", match["url"])
                edits.append(
                    (match.start(), match.end(), f"[{label}]({replacements[match['url']]})")
                )
    for start, end, value in sorted(set(edits), reverse=True):
        body = body[:start] + value + body[end:]
    return body


def notes_markdown(note: dict) -> str:
    front = {
        "id": note["id"],
        "created": note["created_at"],
        "updated": note["updated_at"],
        "labels": note["labels"],
        "pinned": note["pinned"],
        "archived": note["archived"],
        "trashed": note.get("trashed", False),
        "private": note.get("private", False),
        "source": note["source"],
        "origin": note["origin"],
        "machine": note.get("machine", False),
        "conflict_of": note.get("conflict_of"),
    }
    # JSON values are YAML 1.2 scalars/collections and safely quote arbitrary owner text.
    return (
        "---\n"
        + "\n".join(
            f"{key}: {json.dumps(value, ensure_ascii=False)}" for key, value in front.items()
        )
        + "\n---\n\n"
        + note["body"]
    )


def build_zip(settings: Settings, principal: Principal) -> Path:
    handle = tempfile.NamedTemporaryFile(prefix="spunto-export-", suffix=".zip", delete=False)
    output = Path(handle.name)
    handle.close()
    conn = sqlite3.connect(f"file:{settings.data_dir / DB_NAME}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    try:
        conn.execute("BEGIN")
        conditions = []
        if not principal.private_ok:
            conditions.append("private=0")
        if not principal.external_ok:
            conditions.append("origin!='external'")
        where = " WHERE " + " AND ".join(conditions) if conditions else ""
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
            copied = set()
            # Visibility fragments are fixed above, never supplied by a caller.
            for row in conn.execute("SELECT * FROM note" + where + " ORDER BY created_at,id"):  # noqa: S608
                created = iso(row["created_at"])
                slug = re.sub(r"[^a-z0-9]+", "-", fold(row["title"])).strip("-")[:72] or "note"
                filename = f"notes/{created[:4]}/{created[:10]}-{slug}-{row['id']}.md"
                body = row["body"]
                replacements = {}
                for attachment in conn.execute(
                    (
                        "SELECT a.* FROM attachment a JOIN note_attachment na ON "
                        "na.attachment_id=a.id WHERE na.note_id=?"
                    ),
                    (row["id"],),
                ):
                    digest = attachment["sha256"]
                    if not re.fullmatch(r"[0-9a-f]{64}", digest):
                        continue
                    original = settings.attachments_dir / digest[:2] / digest[2:4] / digest
                    if not original.is_file():
                        continue
                    suffix = Path(attachment["filename"] or "").suffix.lower()
                    suffix = suffix if re.fullmatch(r"\.[a-z0-9]{1,10}", suffix) else ""
                    member = f"attachments/{digest}{suffix}"
                    if member not in copied:
                        archive.write(original, member)
                        copied.add(member)
                    replacements[f"/a/{attachment['id']}"] = os.path.relpath(
                        member, Path(filename).parent
                    )
                body = _rewrite_links(body, replacements)
                source = {"kind": row["source_kind"], "ref": row["source_ref"]}
                if row["source_url"]:
                    source["url"] = row["source_url"]
                note = {
                    "id": row["id"],
                    "body": body,
                    "created_at": created,
                    "updated_at": iso(row["updated_at"]),
                    "labels": [
                        label[0]
                        for label in conn.execute(
                            (
                                "SELECT l.path FROM label l JOIN note_label nl ON nl.label_id=l.id "
                                "WHERE nl.note_id=? ORDER BY l.id"
                            ),
                            (row["id"],),
                        )
                    ],
                    "pinned": bool(row["pinned"]),
                    "archived": row["archived_at"] is not None,
                    "trashed": row["trashed_at"] is not None,
                    "private": bool(row["private"]),
                    "source": source,
                    "origin": row["origin"],
                    "machine": bool(row["machine"]),
                    "conflict_of": row["conflict_of"],
                }
                archive.writestr(filename, notes_markdown(note))
        return output
    except BaseException:
        output.unlink(missing_ok=True)
        raise
    finally:
        conn.close()
