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
