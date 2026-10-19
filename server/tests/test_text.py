import json
import sqlite3
import subprocess
import sys
from pathlib import Path

import pytest
from hypothesis import given
from hypothesis import strategies as st

from spunto.core.text import (
    fold,
    label_key,
    labels,
    rewrite_labels,
    search_query,
    set_labels,
    title_of,
)

FIXTURES = Path(__file__).resolve().parents[2] / "spec" / "fixtures"


def fixture(name: str) -> list[dict]:
    return json.loads((FIXTURES / (name + ".json")).read_text())


@pytest.mark.parametrize("case", fixture("labels"))
def test_labels_fixture(case):
    found = labels(case["body"])
    assert [token.path for token in found] == case["paths"]
    assert [token.key for token in found] == case["keys"]
    for token in found:
        assert label_key(case["body"][token.start + 1 : token.end]) == token.key
        assert case["body"][token.start] == "#"


@pytest.mark.parametrize("case", fixture("titles"))
def test_titles_fixture(case):
    assert title_of(case["body"], case.get("limit", 120)) == case["title"]


@pytest.mark.parametrize(
    ("marker", "repetitions", "expected"),
    [
        ("[", 100_000, "[" * 120),
        ("[](", 33_333, "[](" * 40),
        ("<", 100_000, "<" * 120),
        ("`", 100_000, ""),
    ],
)
def test_adversarial_titles_finish_in_a_bounded_process(marker, repetitions, expected):
    # A subprocess bounds a regression in backtracking without hanging the test worker.
    subprocess.run(  # noqa: S603 - fixed Python code and synthetic arguments, without a shell.
        [
            sys.executable,
            "-c",
            "from spunto.core.text import title_of; import sys; "
            "assert title_of(sys.argv[1] * int(sys.argv[2])) == sys.argv[3]",
            marker,
            str(repetitions),
            expected,
        ],
        check=True,
        capture_output=True,
        text=True,
        timeout=10,
    )


def test_title_code_closer_preserves_many_shorter_runs():
    marker = "`" * 300
    content = "head " + ("`" * 299 + " x ") * 250 + "tail"
    assert title_of(marker + content + marker, 100_000) == content


@pytest.mark.parametrize("case", fixture("fold"))
def test_fold_fixture(case):
    assert fold(case["text"]) == case["folded"]


@pytest.mark.parametrize("case", fixture("rewrites"))
def test_rewrite_fixture(case):
    assert rewrite_labels(case["body"], case["old"], case["new"]) == case["expected"]


@pytest.mark.parametrize(
    "path", ["", "#tag", "12/x", "a//b", "a/", "a b", "x" * 129, "a/b/c/d/e/f"]
)
def test_invalid_label_path(path):
    with pytest.raises(ValueError):
        label_key(path)


def test_label_limits_are_all_or_nothing():
    assert len(labels("#" + "x" * 128)) == 1
    assert not labels("#" + "x" * 129)
    with pytest.raises(ValueError):
        rewrite_labels("#a/b/c/d/e", "a", "x/y")


@pytest.mark.parametrize(
    ("body", "add", "remove", "expected"),
    [
        ("Text", ["idea", "work"], [], "Text\n#idea #work"),
        ("Text #Idea", ["idea"], [], "Text #Idea"),
        ("Text #idea\n", ["work"], [], "Text #idea #work\n"),
        ("Text\n", ["idea"], [], "Text\n#idea"),
        ("", ["idea"], [], "#idea"),
        ("#work #work/x #workout", [], ["work"], "  #workout"),
        ("#idea", ["Idea"], ["idea"], "#Idea"),
    ],
)
def test_set_labels(body, add, remove, expected):
    assert set_labels(body, add, remove) == expected


@pytest.mark.parametrize("fence", ["```", "~~~", "````python"])
def test_set_labels_refuses_invisible_additions_in_unclosed_code(fence):
    body = f"A thought #existing\n{fence}\n#not_a_label"
    with pytest.raises(ValueError, match="Close the unfinished code block before adding labels"):
        set_labels(body, ["new"], [])
    assert set_labels(body, ["existing"], []) == body


@pytest.mark.parametrize(
    ("text", "expected", "paths"),
    [
        ("riunione lunedì #project/spunto", '"riunione" "lunedì"*', ["project/spunto"]),
        ('"lista della spesa" #idea', '"lista della spesa"', ["idea"]),
        ('"pull request" dep', '"pull request" "dep"*', []),
        ('" OR NEAR(x) title:hello* -world', '"OR" "NEAR" "x" "title" "hello" "world"*', []),
        ("l’idea", '"l" "idea"*', []),
        ("#work #idea", "", ["work", "idea"]),
        ("***", "", []),
    ],
)
def test_search_query(text, expected, paths):
    assert search_query(text) == (expected, paths)


WORD = st.text(alphabet="abcdefghijklmnopqrstuvwxyz", min_size=1, max_size=20)


@given(WORD, WORD)
def test_rename_changes_only_matching_spans(old, replacement):
    body = f"Text #{old} #{old}/child #{old}other `#{old}` https://x/#{old}"
    expected = f"Text #{replacement} #{replacement}/child #{old}other `#{old}` https://x/#{old}"
    assert rewrite_labels(body, old, replacement) == expected
    assert rewrite_labels(body, old, None) == f"Text   #{old}other `#{old}` https://x/#{old}"


@given(st.lists(WORD, max_size=15))
def test_label_add_is_idempotent_and_complete(paths):
    result = set_labels("A thought.", paths, [])
    assert {token.key for token in labels(result)} == set(paths)
    assert set_labels(result, paths, []) == result
    assert result.startswith("A thought.")


@given(st.text(max_size=200))
def test_search_input_cannot_inject_fts_syntax(text):
    expression, _ = search_query(text)
    if expression:
        with sqlite3.connect(":memory:") as conn:
            conn.execute("CREATE VIRTUAL TABLE search USING fts5(body)")
            conn.execute("INSERT INTO search VALUES ('sample text')")
            conn.execute("SELECT rowid FROM search WHERE search MATCH ?", (expression,)).fetchall()
