"""Labels, titles and keyword queries (02 §4.3–4.4)."""

import html
import re
import unicodedata
from dataclasses import dataclass


@dataclass(frozen=True)
class Label:
    key: str
    path: str
    start: int
    end: int


def _segment_char(char: str) -> bool:
    return char.isalpha() or char.isdigit() or char in "_-"


def label_key(path: str) -> str:
    """Validate a bare label path and return its case-insensitive identity."""
    normalized = unicodedata.normalize("NFC", path)
    segments = normalized.split("/")
    if (
        not normalized
        or len(normalized) > 128
        or len(segments) > 5
        or segments[0].isdigit()
        or any(not segment or not all(_segment_char(c) for c in segment) for segment in segments)
    ):
        raise ValueError("Labels need 1 to 5 letter, digit, underscore or hyphen segments.")
    return normalized.casefold()


def _escaped(text: str, at: int) -> bool:
    start = at
    while start and text[start - 1] == "\\":
        start -= 1
    return (at - start) % 2 == 1


def _code_mask(body: str) -> bytearray:
    """Protect fenced blocks and matched backtick spans, keeping original offsets."""
    mask = bytearray(len(body))
    fence_char, fence_length = "", 0
    offset = 0
    for line in body.splitlines(keepends=True):
        marker = re.match(r" {0,3}(`{3,}|~{3,})([^\r\n]*)", line)
        if fence_char:
            mask[offset : offset + len(line)] = b"\1" * len(line)
            if (
                marker
                and marker[1][0] == fence_char
                and len(marker[1]) >= fence_length
                and not marker[2].strip()
            ):
                fence_char = ""
        elif marker and (marker[1][0] == "~" or "`" not in marker[2]):
            fence_char, fence_length = marker[1][0], len(marker[1])
            mask[offset : offset + len(line)] = b"\1" * len(line)
        offset += len(line)
    runs = list(re.finditer(r"`+", body))
    index = 0
    while index < len(runs):
        opener = runs[index]
        if mask[opener.start()] or _escaped(body, opener.start()):
            index += 1
            continue
        close = index + 1
        while close < len(runs):
            candidate = runs[close]
            # An inline span cannot cross a fenced block.
            if mask[candidate.start()]:
                break
            if len(candidate[0]) == len(opener[0]):
                mask[opener.start() : candidate.end()] = b"\1" * (candidate.end() - opener.start())
                index = close
                break
            close += 1
        index += 1
    return mask


def labels(body: str) -> list[Label]:
    """Return every label occurrence, including duplicates, with exact token spans."""
    protected = _code_mask(body)
    result = []
    at = 0
    while at < len(body):
        if (
            body[at] != "#"
            or protected[at]
            or (at and not (body[at - 1].isspace() or body[at - 1] in "(["))
        ):
            at += 1
            continue
        end = at + 1
        while end < len(body) and not protected[end]:
            char = body[end]
            if not (_segment_char(char) or char == "/" or unicodedata.category(char)[0] == "M"):
                break
            end += 1
        path = body[at + 1 : end]
        try:
            key = label_key(path)
        except ValueError:
            pass
        else:
            result.append(Label(key, unicodedata.normalize("NFC", path), at, end))
        at = end
    return result


def rewrite_labels(body: str, old: str, new: str | None) -> str:
    """Rewrite a label subtree without changing any other text."""
    old_key = label_key(old)
    if new is not None:
        label_key(new)
    depth = len(old.split("/"))
    for token in reversed(labels(body)):
        if token.key != old_key and not token.key.startswith(old_key + "/"):
            continue
        replacement = ""
        if new is not None:
            suffix = token.path.split("/")[depth:]
            path = "/".join([new, *suffix])
            label_key(path)
            replacement = "#" + path
        body = body[: token.start] + replacement + body[token.end :]
    return body


def set_labels(body: str, add: list[str], remove: list[str]) -> str:
    """Remove whole label tokens and append missing labels in the prescribed place."""
    for path in [*add, *remove]:
        label_key(path)
    for path in remove:
        body = rewrite_labels(body, path, None)
    for path in add:
        existing = labels(body)
        if label_key(path) in {token.key for token in existing}:
            continue
        last_content = len(body.rstrip())
        last_line = body.rfind("\n", 0, last_content) + 1
        ends_in_label = any(
            token.end == last_content and token.start >= last_line for token in existing
        )
        if not body:
            body = "#" + path
        elif ends_in_label:
            body = body[:last_content] + " #" + path + body[last_content:]
        else:
            body += ("" if body.endswith("\n") else "\n") + "#" + path
    present = {token.key for token in labels(body)}
    if any(label_key(path) not in present for path in add):
        raise ValueError("Close the unfinished code block before adding labels.")
    return body


def _title_links(line: str) -> str:
    parts = []
    start = at = 0
    while (at := line.find("[", at)) >= 0:
        end = line.find("]", at + 1)
        if end < 0:
            break
        if line[end + 1 : end + 2] != "(":
            # Every opener before this bracket has the same invalid closing bracket.
            at = end + 1
            continue
        close = line.find(")", end + 2)
        if close < 0:
            break
        marker = at - 1 if at > start and line[at - 1] == "!" else at
        parts.extend((line[start:marker], line[at + 1 : end]))
        start = at = close + 1
    parts.append(line[start:])
    return "".join(parts)


def _title_tags(line: str) -> str:
    parts = []
    start = 0
    while (at := line.find("<", start)) >= 0:
        end = line.find(">", at + 1)
        if end < 0:
            break
        parts.append(line[start:at])
        start = end + 1
    parts.append(line[start:])
    return "".join(parts)


def _title_code(line: str) -> str:
    runs = list(re.finditer(r"`+", line))
    longest = [0] * (len(runs) + 1)
    for index in range(len(runs) - 1, -1, -1):
        longest[index] = max(len(runs[index][0]), longest[index + 1])
    parts = []
    start = index = 0
    while index < len(runs):
        opener = max(start, runs[index].start())
        length = runs[index].end() - opener
        # Preserve the greedy opener and first matching closer, without retrying
        # every possible opener length when a long run has no matching closer.
        width = max(min(length, longest[index + 1]), length // 2)
        if not width:
            index += 1
            continue
        close = opener + width
        if width * 2 > length:
            closer = index + 1
            while len(runs[closer][0]) < width:
                closer += 1
            close = runs[closer].start()
        parts.extend((line[start:opener], line[opener + width : close]))
        start = close + width
        while index < len(runs) and runs[index].end() <= start:
            index += 1
    parts.append(line[start:])
    return "".join(parts)


def title_of(body: str, limit: int = 120) -> str:
    """First non-empty Markdown line as plain text, truncated at a word boundary."""
    line = next((line.strip() for line in body.splitlines() if line.strip()), "")
    line = re.sub(r"^ {0,3}(?:#{1,6}\s+|>\s*|[-+*]\s+|\d+[.)]\s+)", "", line)
    line = re.sub(r"^\[[ xX]\]\s*", "", line)
    line = _title_links(line)
    line = _title_tags(line)
    line = _title_code(line)
    line = re.sub(r"(?<!\\)\*{1,3}([^*]+)\*{1,3}", r"\1", line)
    line = re.sub(r"(?<![\w\\])_{1,3}([^_]+)_{1,3}(?!\w)", r"\1", line)
    line = re.sub(r"(?<!\\)~~(.*?)~~", r"\1", line)
    line = re.sub(r"\\([\\`*_{}\[\]()#+.!>~-])", r"\1", line)
    line = html.unescape(line).strip()
    if len(line) <= limit:
        return line
    cut = line[:limit]
    if limit < len(line) and not line[limit].isspace():
        boundary = max((match.start() for match in re.finditer(r"\s+", cut)), default=-1)
        if boundary >= 0:
            cut = cut[:boundary]
    return cut.rstrip()


def fold(text: str) -> str:
    decomposed = unicodedata.normalize("NFKD", text)
    return "".join(char for char in decomposed if not unicodedata.combining(char)).casefold()


def search_query(q: str) -> tuple[str, list[str]]:
    """Quote FTS terms and phrases; pull inline labels out into separate filters."""
    filters = labels(q)
    for token in reversed(filters):
        q = q[: token.start] + " " * (token.end - token.start) + q[token.end :]
    terms: list[tuple[str, bool]] = []
    for match in re.finditer(r'"([^"\n]*)"|([^"\s]+)', q):
        phrase = match[1] is not None
        text = match[1] if phrase else match[2]
        words = re.findall(r"[^\W_]+", text, flags=re.UNICODE)
        if phrase and words:
            terms.append((" ".join(words), True))
        else:
            terms.extend((word, False) for word in words)
    query = " ".join(
        '"' + word + '"' + ("*" if index == len(terms) - 1 and not phrase else "")
        for index, (word, phrase) in enumerate(terms)
    )
    return query, list(dict.fromkeys(token.path for token in filters))


def task_counts(body: str) -> tuple[int, int]:
    """Count checkbox lines consistently, excluding block and inline code."""
    from markdown_it import MarkdownIt

    opened, total = 0, 0
    for token in MarkdownIt().parse(body):
        if token.type != "inline":
            continue
        protected = _code_mask(token.content)
        for match in re.finditer(r"(?m)^\s*\[([ xX])\]\s", token.content):
            if not protected[match.start(1)]:
                total += 1
                opened += match[1] == " "
    return opened, total
