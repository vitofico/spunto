import json
from pathlib import Path

import pytest
from hypothesis import given
from hypothesis import strategies as st

from spunto.core.merge import merge

FIXTURES = Path(__file__).resolve().parents[2] / "spec" / "fixtures" / "merge.json"


@pytest.mark.parametrize("case", json.loads(FIXTURES.read_text()))
def test_merge_fixture(case):
    assert merge(case["base"], case["current"], case["incoming"]) == case["merged"]
    assert merge(case["base"], case["incoming"], case["current"]) == case["merged"]


@given(st.text(), st.text())
def test_one_sided_edits_and_identical_edits_are_exact(base, changed):
    assert merge(base, base, changed) == changed
    assert merge(base, changed, base) == changed
    assert merge(base, changed, changed) == changed


@given(st.integers(min_value=2, max_value=30), st.data())
def test_disjoint_generated_replacements_preserve_both_edits(size, data):
    positions = data.draw(st.lists(st.integers(0, size - 1), min_size=2, max_size=2, unique=True))
    lines = [f"original {i}\n" for i in range(size)]
    current, incoming, expected = lines.copy(), lines.copy(), lines.copy()
    current[positions[0]] = expected[positions[0]] = "CURRENT ADDED\n"
    incoming[positions[1]] = expected[positions[1]] = "INCOMING ADDED\n"
    result = merge("".join(lines), "".join(current), "".join(incoming))
    assert result == "".join(expected)
    assert merge("".join(lines), "".join(incoming), "".join(current)) == result


@given(st.integers(min_value=1, max_value=20), st.data())
def test_generated_edits_never_drop_either_added_text(size, data):
    """Generate replacements, deletions and insertions on both sides, including overlap."""
    base_lines = [f"base {i}\n" for i in range(size)]
    bodies = []
    additions = []
    edits = []
    for side in ("CURRENT", "INCOMING"):
        start = data.draw(st.integers(0, size))
        end = data.draw(st.integers(start, size))
        added = data.draw(st.lists(st.integers(), min_size=0, max_size=3))
        new_lines = [f"{side} added {index}: {value}\n" for index, value in enumerate(added)]
        bodies.append("".join(base_lines[:start] + new_lines + base_lines[end:]))
        additions.extend(new_lines)
        edits.append((start, end, new_lines))
    base = "".join(base_lines)
    result = merge(base, bodies[0], bodies[1])
    assert result == merge(base, bodies[1], bodies[0])
    a, b, left_lines = edits[0]
    c, d, right_lines = edits[1]
    if edits[0] == edits[1] or (a == b and not left_lines) or (c == d and not right_lines):
        overlaps = False
    elif a == b and c == d:
        overlaps = a == c
    elif a == b:
        overlaps = c < a < d
    elif c == d:
        overlaps = a < c < b
    else:
        overlaps = max(a, c) < min(b, d)
    assert (result is None) == overlaps
    if result is not None:
        for line in additions:
            assert line in result


@given(st.integers(min_value=0, max_value=20))
def test_same_position_different_insertions_conflict(position):
    lines = [f"line {i}\n" for i in range(20)]
    ours = lines[:position] + ["ours\n"] + lines[position:]
    theirs = lines[:position] + ["theirs\n"] + lines[position:]
    assert merge("".join(lines), "".join(ours), "".join(theirs)) is None
