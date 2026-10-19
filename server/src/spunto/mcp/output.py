"""Compact JSON answers, with outside text fenced and a shared size ceiling."""

import html
import json
from copy import deepcopy

MAX_CHARS = 28000  # about 8,000 tokens at the spec's conservative 3.5 chars/token


def compact(payload: object) -> str:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def fence(text: str, note: dict) -> str:
    if note.get("origin") != "external":
        return text
    source = note.get("source") or {}
    origin = html.escape(
        str(source.get("ref") or source.get("kind") or "external")[:240], quote=True
    )
    return f'<external_data from="{origin}">\n{html.escape(text, quote=False)}\n</external_data>'


def shorten(text: str, length: int) -> str:
    if text.startswith("<external_data ") and text.endswith("\n</external_data>"):
        opening, _, body = text.partition('">\n')
        opening += '">'
        ending = "\n</external_data>"
        return opening + "\n" + body[: max(0, length - len(opening) - len(ending) - 1)] + ending
    return text[:length]


def bounded(payload: dict) -> str:
    """Keep a valid answer and an explicit truncation marker, including JSON escaping overhead."""
    result = deepcopy(payload)
    while len(encoded := compact(result)) > MAX_CHARS:
        result["truncated"] = True
        # Prefer reducing full note bodies over throwing away requested notes.
        notes = result.get("notes", [])
        bodies = [
            note
            for note in notes
            if len(note.get("body", "")) > 200
            and len(shorten(note["body"], max(100, len(note["body"]) // 2))) < len(note["body"])
        ]
        if bodies:
            note = max(bodies, key=lambda note: len(note["body"]))
            note["body"] = shorten(note["body"], max(100, len(note["body"]) // 2))
            note["truncated"] = True
            continue
        lists = [value for value in result.values() if isinstance(value, list) and value]
        if lists:
            max(lists, key=lambda value: len(compact(value))).pop()
            continue
        # Single-note write results may contain a large label vocabulary.
        return compact(
            {"error": "The answer is too large; request fewer notes or labels.", "truncated": True}
        )
    return encoded


def bounded_search(payload: dict, *, budget: int) -> str:
    """Trim hit details rather than IDs or cursors, so pagination never skips a hit."""
    result = deepcopy(payload)
    while len(encoded := compact(result)) > budget:
        result["truncated"] = True
        candidates = []
        for hit in result["hits"]:
            labels = hit.get("labels", [])
            if labels:
                candidates.append((len(compact(labels)), hit, "labels", labels[: len(labels) // 2]))
            for key in ("title", "snippet", "via"):
                text = hit.get(key, "")
                if not text:
                    continue
                smaller = shorten(text, len(text) // 2) if key != "via" else text[: len(text) // 2]
                if len(smaller) >= len(text) and text.startswith("<external_data "):
                    # Attribution may itself exceed the remaining budget. Keep
                    # an intact generic fence and the separate origin marker.
                    _, _, body = text.partition('">\n')
                    body = body.removesuffix("\n</external_data>")
                    smaller = '<external_data from="external">\n' + body + "\n</external_data>"
                if len(smaller) < len(text):
                    candidates.append((len(compact(text)), hit, key, smaller))
        if not candidates:
            # IDs plus the domain's bounded cursor always fit the budget for
            # the requested limit. A domain contract failure stays a safe error.
            raise ValueError("Search metadata exceeds its response budget")
        _, hit, key, smaller = max(candidates, key=lambda candidate: candidate[0])
        hit[key] = smaller
        hit["truncated"] = True
    return encoded
