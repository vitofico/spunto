"""Fresh, bounded source passages and exact retrieval-evidence fingerprints."""

import hashlib
import json
import re
from collections import Counter

from spunto.core.text import fold

_WORDS = re.compile(r"[^\W_]+", re.UNICODE)
_STOP = set(
    "a an and are as at be by do does for from how i in is it of on or that the this to "
    "was what when where which who why with you "
    "a al alla che chi come con da dal dalla dei del della di e gli i il in la le lo "
    "nel nella o per quale quando quanto se sono su un una uno".split()
)
_GAP = "\n[…]\n"


async def evidence(conn, note):
    """Read enabled extracted text in the caller's source transaction."""
    rows = await conn.execute_fetchall(
        "SELECT t.plugin_id,t.text FROM note_text t JOIN plugin p ON p.id=t.plugin_id "
        "WHERE t.note_id=? AND p.state='enabled' AND p.switch='on' ORDER BY p.position,p.id",
        (note["id"],),
    )
    parts = [note["body"], *(row["text"] for row in rows)]
    payload = [note["id"], note["body"], [(r["plugin_id"], r["text"]) for r in rows]]
    fingerprint = hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
    ).hexdigest()
    return parts, fingerprint


def passages(parts, query, limit):
    """Rank overlapping windows, retaining original text and deterministic order."""
    text = "\n\n".join(part for part in parts if part)
    if len(text) <= limit:
        return text
    terms = set(_WORDS.findall(fold(query))) - _STOP
    # Candidate cost depends on source length, not on an arbitrarily tiny budget.
    width = 800
    # Overlap protects facts and phrases at a window boundary.
    step = width * 3 // 4
    ranked = []
    for start in range(0, len(text), step):
        end = min(len(text), start + width)
        words = Counter(_WORDS.findall(fold(text[start:end])))
        hits = terms.intersection(words)
        score = len(hits) * 4 + sum(min(words[term], 3) for term in hits)
        ranked.append((-score, start, end))
        if end == len(text):
            break
    selected = []
    remaining = limit
    for _, start, end in sorted(ranked):
        if any(start < prior_end and end > prior_start for prior_start, prior_end in selected):
            continue
        available = remaining - (len(_GAP) if selected else 0)
        if available <= 0:
            break
        if end - start > available:
            # Use a smaller fresh window around the matching word, rather than
            # cutting the high-scoring evidence off the end of a window.
            matches = [m.start() for m in _WORDS.finditer(text[start:end]) if fold(m[0]) in terms]
            focus = matches[0] if matches else 0
            start += max(0, min(focus - available // 3, end - start - available))
            end = start + available
        selected.append((start, end))
        remaining -= end - start + (len(_GAP) if len(selected) > 1 else 0)
    return _GAP.join(text[start:end] for start, end in sorted(selected))


def source_passages(parts, query, limit, hints=()):
    """Resolve semantic hints against freshly reconstructed chunks only."""
    from spunto.ai.vectors import chunks

    if not hints:
        return passages(parts, query, limit)
    fresh = chunks(parts[0], "\n\n".join(parts[1:]))
    preferred = []
    seen = set()
    for hint in hints:
        ordinal = hint["ordinal"]
        if (
            ordinal < len(fresh)
            and ordinal not in seen
            and hashlib.sha256(fresh[ordinal].encode()).hexdigest() == hint["text_hash"]
        ):
            preferred.append(fresh[ordinal])
            seen.add(ordinal)
    if not preferred:
        return passages(parts, query, limit)
    # Semantic neighbours may tie on routine body chunks. Reserve room for a
    # lexical match anywhere in the full evidence, including extracted text.
    # Keep the strongest semantic chunk whole whenever both can fit.
    lexical_limit = min(
        limit // 2,
        max(min(256, limit // 3), limit - len(preferred[0]) - len(_GAP)),
    )
    lexical = passages(parts, query, lexical_limit) if lexical_limit else ""
    terms = set(_WORDS.findall(fold(query))) - _STOP
    if not terms.intersection(_WORDS.findall(fold(lexical))):
        lexical = ""
    selected = []
    remaining = limit - len(lexical) - (len(_GAP) if lexical else 0)
    for text in preferred:
        available = remaining - (len(_GAP) if selected else 0)
        if available <= 0:
            break
        part = passages([text], query, available)
        selected.append(part)
        remaining -= len(part) + (len(_GAP) if len(selected) > 1 else 0)
    if lexical:
        selected.append(lexical)
    return _GAP.join(selected)
