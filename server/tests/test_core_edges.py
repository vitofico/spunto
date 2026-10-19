"""Regression coverage for review findings at concurrency and size boundaries."""

import asyncio
import sqlite3
import threading
import uuid

import pytest

from spunto.auth.tokens import Principal
from spunto.core import notes as domain
from spunto.core.export import _rewrite_links
from spunto.core.merge import merge
from spunto.core.notes import Notes

pytestmark = pytest.mark.anyio


def service(server, name="edge-owner"):
    return Notes(server.services.store, Principal(name, "session", name, "full", private_ok=True))


async def test_read_snapshot_keeps_body_version_and_derived_labels_consistent(server, monkeypatch):
    reader = service(server)
    writer = service(server, "other-owner")
    note = await reader.create("Original #before")
    observed, release = asyncio.Event(), asyncio.Event()
    original = reader._serialize

    async def gated(conn, row, **options):
        observed.set()
        await release.wait()
        return await original(conn, row, **options)

    monkeypatch.setattr(reader, "_serialize", gated)
    reading = asyncio.create_task(reader.get(note["id"]))
    await observed.wait()
    try:
        await writer.edit(note["id"], "Changed #after", 1)
    finally:
        release.set()
    result = await reading
    assert (result["body"], result["labels"], result["version"]) == (
        "Original #before",
        ["before"],
        1,
    )
    assert (await writer.get(note["id"]))["labels"] == ["after"]


async def test_merge_cpu_work_does_not_hold_writer_or_block_other_requests(server, monkeypatch):
    editor = service(server)
    writer = service(server, "other-owner")
    note = await editor.create("One\nTwo\n")
    await writer.edit(note["id"], "Changed\nTwo\n", 1)
    started, release = threading.Event(), threading.Event()
    original = domain.merge

    def held_merge(*values):
        started.set()
        assert release.wait(5)
        return original(*values)

    monkeypatch.setattr(domain, "merge", held_merge)
    pending = asyncio.create_task(editor.edit(note["id"], "One\nOther\n", 1))
    assert await asyncio.to_thread(started.wait, 5)
    try:
        unrelated = await asyncio.wait_for(writer.create("Unrelated write"), 1)
        assert unrelated["body"] == "Unrelated write"
        health = await asyncio.wait_for(server.client.get("/healthz"), 1)
        assert health.status_code == 200
    finally:
        release.set()
    assert (await pending)["body"] == "Changed\nOther\n"


async def test_oversized_clean_merge_preserves_both_valid_bodies_in_separate_notes(server):
    editor = service(server)
    writer = service(server, "other-owner")
    base = "a" * 49998 + "\n" + "b" * 49998 + "\n"
    current, incoming = "xx" + base, base + "yy"
    assert len(current) == len(incoming) == 100000
    note = await editor.create(base)
    await writer.edit(note["id"], current, 1)
    result = await editor.edit(note["id"], incoming, 1)
    assert result["conflict"]
    assert (await editor.get(note["id"]))["body"] == current
    assert (await editor.get(result["conflict_id"]))["body"] == incoming


async def test_empty_stale_edit_is_preserved_as_a_conflicted_copy(server):
    editor = service(server)
    writer = service(server, "other-owner")
    note = await editor.create("Original")
    await writer.edit(note["id"], "Changed", 1)
    result = await editor.edit(note["id"], "", 1)
    assert result["conflict"]
    assert (await editor.get(result["conflict_id"]))["body"] == ""
    assert (await editor.get(note["id"]))["body"] == "Changed"


async def test_deleting_a_conflict_parent_sequences_the_surviving_copy_change(server):
    editor = service(server)
    writer = service(server, "other-owner")
    note = await editor.create("Original")
    await writer.edit(note["id"], "Changed", 1)
    result = await editor.edit(note["id"], "Incoming", 1)
    before = result["note"]
    await editor.flags(note["id"], {"trashed": True})
    await editor.delete(note["id"])
    after = await editor.get(before["id"])
    assert after["conflict_of"] is None and after["body"] == "Incoming"
    assert after["change_seq"] > before["change_seq"]
    assert after["version"] == before["version"] + 1


async def test_failed_commit_rolls_back_and_keeps_writer_usable(server, monkeypatch):
    store = server.services.store
    original = store._writer.execute
    failed = False
    note_inserted = False

    async def fail_once(sql, *args, **kwargs):
        nonlocal failed, note_inserted
        if sql.startswith("INSERT INTO note("):
            note_inserted = True
        if sql == "COMMIT" and note_inserted and not failed:
            failed = True
            raise sqlite3.OperationalError("injected full disk")
        return await original(sql, *args, **kwargs)

    monkeypatch.setattr(store._writer, "execute", fail_once)
    with pytest.raises(sqlite3.OperationalError, match="injected"):
        await service(server).create("Uncommitted")
    assert (await service(server).list())["notes"] == []
    assert (await service(server).create("Next write"))["body"] == "Next write"


async def test_repeated_lines_merge_without_losing_distant_edits():
    base = "x\n" * 25000
    assert (
        merge(base, "first\n" + base[2:], base[:-2] + "last\n")
        == "first\n" + "x\n" * 24998 + "last\n"
    )


async def test_export_preserves_indented_code_and_rewrites_multiline_and_nested_labels():
    url = f"/a/{uuid.uuid7()}"
    destination = "../../attachments/file.png"
    body = f"    [Example]({url})\n\n![alt\ntext]({url})\n\n[Outer [nested]]({url})\n"
    assert (
        _rewrite_links(body, {url: destination})
        == f"    [Example]({url})\n\n![alt\ntext]({destination})\n\n"
        f"[Outer [nested]]({destination})\n"
    )
