"""Synthetic portable archives, owner-edit fencing and real-SQLite round trips."""

import io
import stat
import uuid
import zipfile
from pathlib import Path

import pytest

from spunto.auth.tokens import Principal
from spunto.core.attachments import Attachments, write_attachment
from spunto.core.export import build_zip, notes_markdown
from spunto.core.notes import Notes
from spunto.core.plugin_notes import PluginNotes
from spunto.host import Ctx
from spunto.host.plugin import ContextError
from spunto.host.testing import harness
from spunto.plugins import markdown_import as importer

pytestmark = pytest.mark.anyio
OWNER = Principal("owner-test", "session", "Owner", "full", private_ok=True)


def archive(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as zip_file:
        for name, content in files.items():
            zip_file.writestr(name, content)
    return output.getvalue()


def contents(data):
    with zipfile.ZipFile(io.BytesIO(data)) as zip_file:
        return {name: zip_file.read(name) for name in zip_file.namelist()}


@pytest.fixture
async def target(server, tmp_path):
    directory = tmp_path / "import-target"
    settings = server.services.settings.model_copy(
        update={
            "data_dir": directory,
            "attachments_dir": directory / "attachments",
            "backup_dir": directory / "backups",
        }
    )
    async with harness(
        directory,
        settings,
        catalog=[Path(importer.__file__).parent],
        notes_factory=PluginNotes,
        attachments_factory=Attachments,
    ) as host:
        assert host.manifests["markdown-import"]["default"] == "off"
        await host.enable("markdown-import", host.manifests["markdown-import"]["permissions"])
        yield Ctx(host, "markdown-import", principal=OWNER)


async def test_enqueue_commit_survives_receipt_failure_restart_and_preview_expiry(
    tmp_path, settings, monkeypatch
):
    import uuid

    from spunto.host.storage import Records
    from spunto.host.testing import FakeClock

    directory = tmp_path / "durable-import"
    config = settings.model_copy(update={"data_dir": directory})
    catalog = [Path(importer.__file__).parent]
    clock = FakeClock()
    archive_id = str(uuid.uuid4())
    content = archive({"synthetic.md": "Synthetic durable import"})
    bundle = importer.read_zip(content)
    async with harness(
        directory, config, catalog=catalog, notes_factory=PluginNotes, clock=clock
    ) as host:
        await host.enable("markdown-import", host.manifests["markdown-import"]["permissions"])
        ctx = Ctx(host, "markdown-import", principal=OWNER)
        staging = directory / "imports"
        staging.mkdir()
        (staging / f"{archive_id}.zip").write_bytes(content)
        await ctx.storage.records.put(
            "uploads",
            archive_id,
            {"created": clock(), "digest": bundle.digest, "previewed": True},
        )
        original = Records.put

        async def fail_receipt(self, collection, record_id, data, **kwargs):
            if collection == "uploads" and data.get("queued"):
                raise OSError("synthetic receipt-write crash")
            return await original(self, collection, record_id, data, **kwargs)

        with monkeypatch.context() as patch:
            patch.setattr(Records, "put", fail_receipt)
            with pytest.raises(OSError, match="synthetic receipt-write crash"):
                await importer.import_upload(ctx, archive_id)
        async with host.store.read() as conn:
            rows = await conn.execute_fetchall("SELECT id FROM job WHERE kind='job:import'")
        assert len(rows) == 1
        job_id = rows[0]["id"]
    clock.advance(86_400_001)
    async with harness(
        directory, config, catalog=catalog, notes_factory=PluginNotes, clock=clock
    ) as host:
        ctx = Ctx(host, "markdown-import", principal=OWNER)
        assert "already queued" in (await importer.import_upload(ctx, archive_id))[0]["text"]
        async with host.store.read() as conn:
            rows = await conn.execute_fetchall("SELECT id FROM job WHERE kind='job:import'")
        assert [r["id"] for r in rows] == [job_id]
        await importer.import_job(ctx, archive_id)
        async with host.store.read() as conn:
            notes = await conn.execute_fetchall("SELECT body FROM note")
        assert [n["body"] for n in notes] == ["Synthetic durable import"]
        assert not (directory / "imports" / f"{archive_id}.zip").exists()


@pytest.mark.parametrize(
    "name",
    [
        "../escape.md",
        "/escape.md",
        "C:/escape.md",
        "a/../escape.md",
        "a\\escape.md",
        "./escape.md",
        "a//escape.md",
    ],
)
def test_zip_paths_never_extract_or_escape(name):
    with pytest.raises(ValueError, match="unsafe path"):
        importer.read_zip(archive({name: "Synthetic words"}))


def test_zip_rejects_symlinks_and_expansion_before_note_writes(monkeypatch):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as zip_file:
        entry = zipfile.ZipInfo("linked.md")
        entry.create_system = 3
        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
        zip_file.writestr(entry, "/etc/passwd")
    with pytest.raises(ValueError, match="symbolic links"):
        importer.read_zip(stream.getvalue())
    monkeypatch.setattr(importer, "MAX_EXPANDED_BYTES", 10)
    with pytest.raises(ValueError, match="expanded"):
        importer.read_zip(archive({"note.md": "Synthetic words are too long"}))


def test_frontmatter_vault_tags_dates_and_literal_unsafe_yaml():
    note = importer.prepare(
        "vault/note.md",
        b"---\ncreated: 2026-10-13\ntags:\n  - Ideas\n  - project/spunto\n"
        b"ignored: !!python/object:os.system\n---\n\nOwner words.\n",
        1_800_000_000_000,
    )
    assert note["body"].startswith("\nOwner words.")
    assert note["body"].endswith("#Ideas #project/spunto")
    assert note["import_metadata"] is None
    assert importer.local_target("vault/note.md", "../image.png") == "image.png"
    assert importer.local_target("vault/note.md", "https://example.org/image.png") is None
    with pytest.raises(ValueError, match="escapes"):
        importer.local_target("note.md", "../../secret.txt")


def test_folder_import_rejects_symlink_and_ignores_hidden_configuration(tmp_path):
    (tmp_path / "note.md").write_text("Synthetic owner note", encoding="utf-8")
    (tmp_path / ".obsidian").mkdir()
    (tmp_path / ".obsidian/config.json").write_text("{}")
    assert set(importer.read_path(tmp_path).files) == {"note.md"}
    (tmp_path / "linked.md").symlink_to(tmp_path / "note.md")
    with pytest.raises(ValueError, match="symbolic link"):
        importer.read_path(tmp_path)


async def test_preview_idempotency_owner_edits_and_changed_attachment(target):
    body = "Synthetic #Ideas\n![diagram](../diagram.png)"
    first = importer.read_zip(
        archive({"notes/note.md": body, "diagram.png": b"\x89PNG\r\n\x1a\nsynthetic-one"})
    )
    dry = await importer.run_import(target, first)
    assert dry["notes"] == 1 and dry["attachments"] == 1 and dry["created"] == 0
    assert (await target.notes.lookup_import("file:notes/note.md")) is None
    assert (await importer.run_import(target, first, dry_run=False))["created"] == 1
    old = await target.notes.lookup_import("file:notes/note.md")
    assert "/a/" in old["body"] and "../diagram.png" not in old["body"]
    assert (await importer.run_import(target, first, dry_run=False))["unchanged"] == 1
    second = importer.read_zip(
        archive({"notes/note.md": body, "diagram.png": b"\x89PNG\r\n\x1a\nsynthetic-two"})
    )
    assert (await importer.run_import(target, second, dry_run=False))["updated"] == 1
    current = await target.notes.lookup_import("file:notes/note.md")
    await Notes(target._host.store, OWNER).edit(
        current["id"], "Owner edit survives import", base_version=current["version"]
    )
    assert (await importer.run_import(target, first, dry_run=False))["skipped_owner_edits"] == 1
    assert (await target.notes.lookup_import("file:notes/note.md"))[
        "body"
    ] == "Owner edit survives import"


async def test_own_export_round_trip_body_flags_sources_ids_and_attachments(server, target):
    store, settings = server.services.store, server.services.settings
    core = Notes(store, OWNER)
    attachment = await write_attachment(
        store,
        settings,
        b"\x89PNG\r\n\x1a\nsynthetic-image",
        filename="diagram.png",
        mime="image/png",
    )
    ids = [str(uuid.uuid7()) for _ in range(4)]
    bodies = [
        "#Ideas\r\n\r\n![diagram](" + attachment["url"] + ")\r\n",
        "Later #IDEAS words",
        "A private archived original",
        "Machine external words",
    ]
    async with store.write() as conn:
        for index, (identifier, body) in enumerate(zip(ids, bodies, strict=True)):
            await core._insert(
                conn,
                body,
                identifier,
                1_792_000_000_000 + index,
                {"kind": "android", "ref": "dictation"},
                index == 2,
                origin="external" if index == 3 else "owner",
                machine=index == 3,
                conflict_of=ids[0] if index == 1 else None,
            )
        await conn.execute("UPDATE note SET pinned=1 WHERE id=?", (ids[0],))
        await conn.execute("UPDATE note SET archived_at=updated_at WHERE id=?", (ids[2],))
        await conn.execute("UPDATE note SET trashed_at=updated_at WHERE id=?", (ids[3],))
    original_path = build_zip(settings, OWNER)
    try:
        original = original_path.read_bytes()
    finally:
        original_path.unlink()
    assert (await importer.run_import(target, importer.read_zip(original), dry_run=False))[
        "created"
    ] == 4
    restored_path = build_zip(target._host.settings, OWNER)
    try:
        restored = restored_path.read_bytes()
    finally:
        restored_path.unlink()
    assert contents(restored) == contents(original)


async def test_disable_and_agent_authority_fail_closed(target):
    source = importer.read_zip(archive({"note.md": "Synthetic original"}))
    agent = Ctx(
        target._host, "markdown-import", principal=Principal("agent", "pat", "Script", "write")
    )
    with pytest.raises(ContextError, match="owner_import"):
        await importer.run_import(agent, source, dry_run=False)
    await target._host.disable("markdown-import")
    with pytest.raises(ContextError, match="plugin_disabled"):
        await importer.run_import(target, source, dry_run=False)


async def test_invalid_second_note_validated_before_first_write(target):
    source = importer.read_zip(archive({"first.md": "Synthetic valid", "second.md": b"\xff"}))
    with pytest.raises(ValueError, match="UTF-8"):
        await importer.run_import(target, source, dry_run=False)
    async with target._host.store.read() as conn:
        assert (await conn.execute_fetchall("SELECT count(*) FROM note"))[0][0] == 0


async def test_changed_own_export_updates_privacy_and_provenance_atomically(target):
    identifier = str(uuid.uuid7())
    note = {
        "id": identifier,
        "body": "Synthetic original #Ideas",
        "labels": ["Ideas"],
        "created_at": "2026-10-13T10:00:00Z",
        "updated_at": "2026-10-13T10:00:00Z",
        "pinned": False,
        "archived": False,
        "trashed": False,
        "private": False,
        "source": {"kind": "web", "ref": None},
        "origin": "owner",
        "machine": False,
        "conflict_of": None,
    }
    first = importer.read_zip(archive({"note.md": notes_markdown(note)}))
    assert (await importer.run_import(target, first, dry_run=False))["created"] == 1
    changed = {
        **note,
        "body": "Synthetic changed #IDEAS",
        "private": True,
        "machine": True,
        "origin": "external",
        "source": {
            "kind": "import",
            "ref": "synthetic-source",
            "url": "https://example.invalid/source",
        },
        "updated_at": "2026-10-14T10:00:00Z",
    }
    source = importer.read_zip(archive({"note.md": notes_markdown(changed)}))
    assert (await importer.run_import(target, source, dry_run=False))["updated"] == 1
    restored = await target.notes.lookup_import("spunto:" + identifier)
    assert restored["body"] == changed["body"]
    assert restored["private"] and restored["machine"] and restored["origin"] == "external"
    assert restored["source"] == changed["source"]
    assert restored["labels"] == ["Ideas"]
    assert (await importer.run_import(target, source, dry_run=False))["unchanged"] == 1


async def test_oversized_attachment_filename_rejected_before_any_note_write(target):
    filename = "x" * 256 + ".png"
    source = importer.read_zip(
        archive(
            {
                "first.md": "Synthetic valid first",
                "second.md": f"Synthetic image ![diagram]({filename})",
                filename: b"\x89PNG\r\n\x1a\nsynthetic-image",
            }
        )
    )
    with pytest.raises(ValueError, match="filename exceeds"):
        await importer.run_import(target, source, dry_run=False)
    async with target._host.store.read() as conn:
        assert (await conn.execute_fetchall("SELECT count(*) FROM note"))[0][0] == 0


def test_report_reads_as_counts_and_sizes():
    report = {
        "notes": 1,
        "attachments": 2,
        "attachment_bytes": 2_400_000,
        "created": 1,
        "updated": 0,
        "unchanged": 0,
        "skipped_owner_edits": 1,
        "missing_links": 0,
    }
    assert importer.report_output(report)[0]["text"] == (
        "1 note and 2 attachments (2.4 MB). Created 1, updated 0, unchanged 0. "
        "Kept 1 note you had edited."
    )
    assert [importer.size(value) for value in (1, 999, 1000, 12_000_000)] == [
        "1 byte",
        "999 bytes",
        "1 KB",
        "12 MB",
    ]
