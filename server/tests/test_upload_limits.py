"""Configured decimal-byte attachment boundaries and bounded multipart streams."""

import pytest
from conftest import PUBLIC_URL
from pydantic import ValidationError

from spunto.ai import AIError, Output
from spunto.config import Settings, load_settings
from spunto.core.attachments import path_for, write_attachment
from spunto.errors import OperatorError

ORIGIN = {"Origin": PUBLIC_URL}
PNG = b"\x89PNG\r\n\x1a\n"


@pytest.mark.parametrize("given", ["0", "-1", "1.5", "1001", "NaN", "True", "9" * 5000])
def test_upload_limit_environment_is_positive_bounded_integer(env, given):
    env(SPUNTO_MAX_UPLOAD_MB=given)
    with pytest.raises(OperatorError, match="SPUNTO_MAX_UPLOAD_MB"):
        load_settings()


@pytest.mark.parametrize("given", [True, False, 1.5, 2.0])
def test_upload_limit_rejects_programmatic_bool_and_float(given):
    with pytest.raises(ValidationError):
        Settings(public_url=PUBLIC_URL, max_upload_mb=given)


def test_upload_limit_default_and_extremes_are_exact_decimal_bytes(env):
    assert load_settings().max_upload_bytes == 20_000_000
    env(SPUNTO_MAX_UPLOAD_MB="1")
    assert load_settings().max_upload_bytes == 1_000_000
    env(SPUNTO_MAX_UPLOAD_MB="1000")
    assert load_settings().max_upload_bytes == 1_000_000_000


@pytest.fixture
async def owner(server):
    response = await server.client.post(
        "/api/v1/setup",
        json={"code": server.services.setup_code, "password": "synthetic upload owner"},
    )
    assert response.status_code == 204
    return server


@pytest.mark.anyio
async def test_default_twenty_mb_boundary_and_sniffing_are_shared_by_core_and_api(owner):
    settings, store = owner.services.settings, owner.services.store
    exact = PNG + b"x" * (settings.max_upload_bytes - len(PNG))
    response = await owner.client.post(
        "/api/v1/attachments",
        files={"file": ("synthetic.png", exact, "image/jpeg")},
        headers=ORIGIN,
    )
    assert response.status_code == 200, response.text
    metadata = response.json()
    assert metadata["size"] == 20_000_000 and metadata["mime"] == "image/png"
    assert path_for(settings.attachments_dir, metadata["sha256"]).stat().st_size == len(exact)
    with pytest.raises(ValueError, match="at most 20 MB"):
        await write_attachment(store, settings, exact + b"x", filename="oversized.png")
    response = await owner.client.post(
        "/api/v1/attachments",
        files={"file": ("oversized.png", exact + b"x", "image/png")},
        headers=ORIGIN,
    )
    assert response.status_code == 413 and response.json()["code"] == "attachment_too_large"
    async with store.read() as conn:
        assert (await conn.execute_fetchall("SELECT count(*) FROM attachment"))[0][0] == 1


@pytest.mark.anyio
async def test_configured_boundary_and_active_content_policy(owner):
    settings = owner.services.settings
    settings.max_upload_mb = 1
    exact = b"x" * 1_000_000
    response = await owner.client.post(
        "/api/v1/attachments",
        files={"file": ("synthetic.bin", exact, "application/octet-stream")},
        headers=ORIGIN,
    )
    assert response.status_code == 200 and response.json()["size"] == 1_000_000
    response = await owner.client.post(
        "/api/v1/attachments",
        files={"file": ("oversized.bin", exact + b"x", "application/octet-stream")},
        headers=ORIGIN,
    )
    assert response.status_code == 413 and "1 MB" in response.json()["detail"]
    response = await owner.client.post(
        "/api/v1/attachments",
        files={"file": ("synthetic.svg", b"<svg onload='bad()'></svg>", "image/svg+xml")},
        headers=ORIGIN,
    )
    assert response.status_code == 200 and response.json()["mime"] == "application/octet-stream"
    downloaded = await owner.client.get(response.json()["url"])
    assert downloaded.headers["content-disposition"].startswith("attachment;")
    assert downloaded.headers["x-content-type-options"] == "nosniff"


@pytest.mark.anyio
async def test_stream_overflow_stops_consumption_and_closes_partial_spool(owner, monkeypatch):
    import starlette.formparsers

    owner.services.settings.max_upload_mb = 2
    spools, sent = [], []
    original = starlette.formparsers.SpooledTemporaryFile

    def spool(*args, **kwargs):
        result = original(*args, **kwargs)
        spools.append(result)
        return result

    monkeypatch.setattr(starlette.formparsers, "SpooledTemporaryFile", spool)

    async def chunks():
        sent.append("header")
        yield (
            b'--bounded\r\nContent-Disposition: form-data; name="file"; '
            b'filename="synthetic.bin"\r\n'
            b"Content-Type: application/octet-stream\r\n\r\n"
        )
        sent.append("first")
        yield b"x" * 1_500_000
        sent.append("overflow")
        yield b"x" * 1_000_000
        sent.append("tail")
        yield b"\r\n--bounded--\r\n"

    response = await owner.client.post(
        "/api/v1/attachments",
        content=chunks(),
        headers={**ORIGIN, "Content-Type": "multipart/form-data; boundary=bounded"},
    )
    assert response.status_code == 413 and response.json()["code"] == "attachment_too_large"
    assert sent == ["header", "first", "overflow"]
    assert spools and all(stream.closed for stream in spools)
    async with owner.services.store.read() as conn:
        assert not await conn.execute_fetchall("SELECT 1 FROM attachment")
    assert not list(owner.services.settings.attachments_dir.glob("*/*/*"))


@pytest.mark.anyio
async def test_chunked_exact_file_and_malformed_length(owner):
    owner.services.settings.max_upload_mb = 1

    async def chunks():
        yield (
            b'--bounded\r\nContent-Disposition: form-data; name="file"; '
            b'filename="synthetic.bin"\r\n\r\n'
        )
        for _ in range(10):
            yield b"x" * 100_000
        yield b"\r\n--bounded--\r\n"

    response = await owner.client.post(
        "/api/v1/attachments",
        content=chunks(),
        headers={**ORIGIN, "Content-Type": "multipart/form-data; boundary=bounded"},
    )
    assert response.status_code == 200 and response.json()["size"] == 1_000_000
    for length in ("10065537", "invalid", "9" * 5000):
        response = await owner.client.post(
            "/api/v1/attachments",
            content=b"ignored",
            headers={
                **ORIGIN,
                "Content-Type": "multipart/form-data; boundary=bounded",
                "Content-Length": length,
            },
        )
        assert response.status_code == 413 and response.json()["code"] == "attachment_too_large"


@pytest.mark.anyio
async def test_ai_attachment_comparisons_share_bytes_and_preserve_mime_duration(owner, monkeypatch):
    gateway = owner.services.ai
    gateway.settings.max_upload_mb = 1
    captured = []

    async def structured(capability, output, prompt, **kwargs):
        captured.append((capability, len(kwargs["image"])))
        return {"synthetic": True}

    monkeypatch.setattr(gateway, "_structured", structured)
    assert await gateway.vision(Output, b"x" * 1_000_000, "image/png") == {"synthetic": True}
    assert captured == [("vision", 1_000_000)]
    with pytest.raises(AIError, match="ai_image_refused"):
        await gateway.vision(Output, b"x" * 1_000_001, "image/png")
    with pytest.raises(AIError, match="ai_image_refused"):
        await gateway.vision(Output, b"x", "image/svg+xml")
    with pytest.raises(AIError, match="ai_audio_refused"):
        await gateway.transcribe(b"x" * 1_000_001, "audio/wav", duration_s=1)
    with pytest.raises(AIError, match="ai_audio_refused"):
        await gateway.transcribe(b"x", "audio/wav", duration_s=3601)


@pytest.mark.anyio
async def test_public_meta_reports_configured_exact_upload_fact_without_auth(server):
    response = await server.client.get("/api/v1/meta")
    assert response.status_code == 200
    assert type(response.json()["max_upload_bytes"]) is int
    assert response.json()["max_upload_bytes"] == 20_000_000
    server.services.settings.max_upload_mb = 1
    assert (await server.client.get("/api/v1/meta")).json()["max_upload_bytes"] == 1_000_000


@pytest.mark.anyio
async def test_markdown_smaller_instance_limit_preflights_before_any_note_write(server, tmp_path):
    import io
    import zipfile
    from pathlib import Path

    from spunto.auth.tokens import Principal
    from spunto.core.attachments import Attachments
    from spunto.core.plugin_notes import PluginNotes
    from spunto.host import Ctx
    from spunto.host.testing import harness
    from spunto.plugins import markdown_import

    directory = tmp_path / "import-limits"
    settings = server.services.settings.model_copy(
        update={
            "data_dir": directory,
            "attachments_dir": directory / "attachments",
            "max_upload_mb": 1,
        }
    )
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as archive:
        archive.writestr("first.md", "Synthetic first note")
        archive.writestr("second.md", "Synthetic later image ![diagram](diagram.png)")
        archive.writestr("diagram.png", PNG + b"x" * 1_000_000)
    bundle = markdown_import.read_zip(data.getvalue())
    async with harness(
        directory,
        settings,
        catalog=[Path(markdown_import.__file__).parent],
        notes_factory=PluginNotes,
        attachments_factory=Attachments,
    ) as host:
        await host.enable("markdown-import", host.manifests["markdown-import"]["permissions"])
        context = Ctx(
            host,
            "markdown-import",
            principal=Principal("owner", "session", "Owner", "full", private_ok=True),
        )
        for dry_run in (True, False):
            with pytest.raises(ValueError, match="configured upload limit"):
                await markdown_import.run_import(context, bundle, dry_run=dry_run)
        async with host.store.read() as conn:
            assert not await conn.execute_fetchall("SELECT 1 FROM note")
            assert not await conn.execute_fetchall("SELECT 1 FROM attachment")
    assert markdown_import.MAX_ATTACHMENT_BYTES == 5_000_000
