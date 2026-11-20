"""Session-only upload, dry-run gate and persistent import execution."""

import asyncio
import io
import zipfile

import pytest
from conftest import PUBLIC_URL

pytestmark = pytest.mark.anyio
ORIGIN = {"Origin": PUBLIC_URL}


def archive():
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w", zipfile.ZIP_DEFLATED) as output:
        output.writestr("first.md", "Synthetic first #import")
        output.writestr("second.md", "Synthetic second [first](first.md)")
    return data.getvalue()


@pytest.fixture
async def owner(server):
    response = await server.client.post(
        "/api/v1/setup",
        json={"code": server.services.setup_code, "password": "synthetic import owner"},
    )
    assert response.status_code == 204
    await server.services.host.enable(
        "markdown-import", server.services.host.manifests["markdown-import"]["permissions"]
    )
    return server


async def upload(owner, content=None, headers=None):
    return await owner.client.post(
        "/api/v1/plugins/markdown-import/upload",
        files={
            "file": (
                "synthetic.zip",
                content if content is not None else archive(),
                "application/zip",
            )
        },
        headers=headers if headers is not None else ORIGIN,
    )


async def test_owner_upload_preview_queued_import_and_note_links(owner):
    response = await upload(owner)
    assert response.status_code == 200, response.text
    preview = response.json()
    assert preview["report"]["notes"] == 2 and preview["report"]["created"] == 0
    assert (await owner.client.get("/api/v1/notes")).json()["notes"] == []
    response = await owner.client.post(
        "/api/v1/plugins/markdown-import/import",
        json={"archive_id": preview["archive_id"]},
        headers=ORIGIN,
    )
    assert response.status_code == 200, response.text
    assert "queued" in response.json()[0]["text"]
    for _ in range(100):
        notes = (await owner.client.get("/api/v1/notes")).json()["notes"]
        if len(notes) == 2:
            break
        await asyncio.sleep(0.02)
    assert len(notes) == 2
    first = next(note for note in notes if "first #" in note["body"])
    second = next(note for note in notes if "second" in note["body"])
    assert f"[first](/n/{first['id']})" in second["body"]
    assert all(note["source"]["kind"] == "import" for note in notes)


async def test_upload_origin_bearer_and_disabled_boundaries(owner):
    assert (await upload(owner, headers={})).status_code == 403
    token = await owner.client.post(
        "/api/v1/tokens", json={"name": "Synthetic agent", "scope": "write"}, headers=ORIGIN
    )
    assert token.status_code == 201
    bearer = {"Authorization": "Bearer " + token.json()["token"]}
    assert (await upload(owner, headers=bearer)).status_code == 403
    await owner.services.host.disable("markdown-import")
    assert (await upload(owner)).status_code == 404


async def test_invalid_zip_size_and_archive_id_fail_without_note_writes(owner):
    assert (await upload(owner, b"Not a ZIP")).status_code == 422
    response = await owner.client.post(
        "/api/v1/plugins/markdown-import/upload",
        content=b"x",
        headers={
            **ORIGIN,
            "Content-Length": "20065537",
            "Content-Type": "multipart/form-data; boundary=none",
        },
    )
    assert response.status_code == 413
    response = await owner.client.post(
        "/api/v1/plugins/markdown-import/import",
        json={"archive_id": "../../secret"},
        headers=ORIGIN,
    )
    assert response.status_code == 422
    assert (await owner.client.get("/api/v1/notes")).json()["notes"] == []


async def test_new_upload_invalidates_old_preview(owner):
    first = (await upload(owner)).json()
    second = (await upload(owner)).json()
    assert first["archive_id"] != second["archive_id"]
    response = await owner.client.post(
        "/api/v1/plugins/markdown-import/import",
        json={"archive_id": first["archive_id"]},
        headers=ORIGIN,
    )
    assert response.status_code == 422
    assert len(list((owner.services.settings.data_dir / "imports").glob("*.zip"))) == 1


async def test_concurrent_uploads_preserve_one_staged_archive(owner):
    first, second = await asyncio.gather(upload(owner), upload(owner))
    assert first.status_code == second.status_code == 200
    archive_ids = {first.json()["archive_id"], second.json()["archive_id"]}
    files = list((owner.services.settings.data_dir / "imports").glob("*.zip"))
    assert len(files) == 1 and files[0].stem in archive_ids
    async with owner.services.store.read() as conn:
        rows = await conn.execute_fetchall(
            "SELECT id FROM plugin_record WHERE plugin_id='markdown-import' "
            "AND collection='uploads'"
        )
    assert [row["id"] for row in rows] == [files[0].stem]


async def test_queued_import_archive_survives_replacement_upload(owner):
    from spunto.auth.tokens import Principal
    from spunto.host import Ctx

    preview = (await upload(owner)).json()
    context = Ctx(
        owner.services.host,
        "markdown-import",
        principal=Principal("fixture-owner", "session", "Owner", "full", private_ok=True),
    )
    # Keep the worker's persisted job queued beyond this test's replacement attempt.
    await context.enqueue("import", {"archive_id": preview["archive_id"]}, delay_ms=60_000)
    response = await upload(owner)
    assert response.status_code == 409 and response.json()["code"] == "import_in_progress"
    assert (
        owner.services.settings.data_dir / "imports" / (preview["archive_id"] + ".zip")
    ).is_file()


async def test_evernote_preview_confirmation_resources_and_retry(owner):
    import base64
    import hashlib

    image = b"\x89PNG\r\n\x1a\nsynthetic-enex-image"
    resource_hash = hashlib.md5(image, usedforsecurity=False).hexdigest()
    encoded = base64.b64encode(image).decode()
    source = f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE en-export SYSTEM "https://xml.evernote.com/pub/evernote-export4.dtd">
<en-export><note><title>Plan &amp; review</title><content><![CDATA[
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE en-note SYSTEM "http://xml.evernote.com/pub/enml2.dtd">
<en-note><div>Hello <strong>synthetic world</strong>.</div>
<en-media hash="{resource_hash}" type="image/png"/>
<en-crypt>synthetic-encrypted-payload</en-crypt></en-note>]]></content>
<created>20201013T100000Z</created><updated>20201014T100000Z</updated>
<tag>Idea notes</tag><tag>Projects/Synthetic</tag>
<resource><data encoding="base64">{encoded}</data><mime>image/png</mime>
<resource-attributes><file-name>Sample image.png</file-name></resource-attributes>
</resource></note></en-export>"""

    async def upload_enex():
        return await owner.client.post(
            "/api/v1/plugins/markdown-import/upload?source_format=evernote",
            files={"file": ("fixture.enex", source.encode(), "application/xml")},
            headers=ORIGIN,
        )

    response = await upload_enex()
    assert response.status_code == 200, response.text
    preview = response.json()
    assert preview["report"]["notes"] == preview["report"]["attachments"] == 1
    assert preview["report"]["planned"]["created"] == preview["report"]["unsupported_items"] == 1
    assert (await owner.client.get("/api/v1/notes")).json()["notes"] == []
    queued = await owner.client.post(
        "/api/v1/plugins/markdown-import/import",
        json={"archive_id": preview["archive_id"]},
        headers=ORIGIN,
    )
    assert queued.status_code == 200
    for _ in range(100):
        result = await upload_enex()
        if result.status_code != 409:
            break
        await asyncio.sleep(0.02)
    assert result.status_code == 200, result.text
    assert result.json()["report"]["planned"]["unchanged"] == 1
    notes = (await owner.client.get("/api/v1/notes")).json()["notes"]
    (note,) = notes
    assert note["title"] == "Plan & review"
    assert note["labels"] == ["Idea-notes", "Projects/Synthetic"]
    assert "**synthetic world**" in note["body"] and "![Sample image.png](/a/" in note["body"]
    assert "synthetic-encrypted-payload" not in note["body"]
    assert note["created_at"].startswith("2020-10-13T10:00:00")
    assert note["updated_at"].startswith("2020-10-14T10:00:00")


async def test_notion_outer_zip_validation_is_actionable_and_localized(owner):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as zip_file:
        zip_file.writestr("Synthetic export.zip", archive())
    response = await owner.client.post(
        "/api/v1/plugins/markdown-import/upload?source_format=notion",
        files={"file": ("Synthetic outer.zip", data.getvalue(), "application/zip")},
        headers={**ORIGIN, "Accept-Language": "it"},
    )
    assert response.status_code == 422
    assert response.headers["Content-Language"] == "it"
    problem = response.json()
    assert problem["code"] == "invalid_import_file"
    assert "ZIP esterno" in problem["detail"]
    assert "Synthetic" not in problem["detail"]
