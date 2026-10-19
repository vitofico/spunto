"""Line-based three-way merge; a conflict leaves both bodies to the caller (§6.4)."""

from dataclasses import dataclass
from difflib import SequenceMatcher


@dataclass(frozen=True)
class _Edit:
    start: int
    end: int
    lines: tuple[str, ...]


def _edits(base: list[str], changed: list[str]) -> list[_Edit]:
    # Trim the common edges before diffing. A one-line edit in a long repeated
    # list should compare that line, not thousands of indistinguishable lines.
    start = 0
    while start < min(len(base), len(changed)) and base[start] == changed[start]:
        start += 1
    end = 0
    while end < min(len(base), len(changed)) - start and base[-end - 1] == changed[-end - 1]:
        end += 1
    original = base[start : len(base) - end if end else len(base)]
    updated = changed[start : len(changed) - end if end else len(changed)]
    return [
        _Edit(start + i, start + j, tuple(updated[a:b]))
        for kind, i, j, a, b in SequenceMatcher(None, original, updated).get_opcodes()
        if kind != "equal"
    ]


def _overlaps(left: _Edit, right: _Edit) -> bool:
    if left.start == left.end and right.start == right.end:
        return left.start == right.start
    if left.start == left.end:
        return right.start < left.start < right.end
    if right.start == right.end:
        return left.start < right.start < left.end
    return max(left.start, right.start) < min(left.end, right.end)


def merge(base: str, current: str, incoming: str) -> str | None:
    """Return a clean merge, or None for overlapping unequal edits."""
    if current == incoming:
        return current
    if current == base:
        return incoming
    if incoming == base:
        return current
    # An appended line needs a separator after an unterminated final line. That
    # separator is a boundary, not an overlapping edit of the preceding words.
    if any(text and not text.endswith(("\n", "\r")) for text in (base, current, incoming)):
        ending = "\r\n" if "\r\n" in base else "\n"
        normalized = [
            text + ending if text and not text.endswith(("\n", "\r")) else text
            for text in (base, current, incoming)
        ]
        result = merge(*normalized)
        if (
            result is not None
            and not current.endswith(("\n", "\r"))
            and not incoming.endswith(("\n", "\r"))
        ):
            return result.removesuffix(ending)
        return result
    original = base.splitlines(keepends=True)
    ours = _edits(original, current.splitlines(keepends=True))
    theirs = _edits(original, incoming.splitlines(keepends=True))
    for left in ours:
        for right in theirs:
            if left != right and _overlaps(left, right):
                return None
    edits = sorted(set(ours + theirs), key=lambda edit: (edit.start, edit.end))
    output: list[str] = []
    cursor = 0
    for edit in edits:
        output.extend(original[cursor : edit.start])
        output.extend(edit.lines)
        cursor = edit.end
    output.extend(original[cursor:])
    return "".join(output)
