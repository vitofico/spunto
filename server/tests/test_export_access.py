"""Owner-facing export, monitoring, CLI credentials and offline reference contracts."""

import asyncio
import hashlib
import io
import json
import uuid
import zipfile
from contextlib import closing
from datetime import UTC, datetime, timedelta
from html.parser import HTMLParser

import pytest
from conftest import PUBLIC_URL

from spunto import cli
from spunto.jobs.backup import backup_now
from spunto.store.db import DB_NAME, connect
from spunto.store.migrations import migrate

pytestmark = pytest.mark.anyio
ORIGIN = {"Origin": PUBLIC_URL}


@pytest.fixture
async def owner(server):
    response = await server.client.post(
        "/api/v1/setup",
        json={
            "code": server.services.setup_code,
            "password": "export owner password",
        },
    )
    assert response.status_code == 204
    return server


async def save(owner, body, **options):
    response = await owner.client.post(
        "/api/v1/notes", json={"body": body, **options}, headers=ORIGIN
    )
    assert response.status_code == 201, response.text
    return response.json()


async def flags(owner, note, **values):
    response = await owner.client.post(
        f"/api/v1/notes/{note['id']}/flags", json=values, headers=ORIGIN
    )
    assert response.status_code == 200, response.text


async def token(owner, **options):
    response = await owner.client.post(
        "/api/v1/tokens", json={"name": "Export client", **options}, headers=ORIGIN
    )
    assert response.status_code == 201, response.text
    return response.json()


def markdown_records(content):
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        records = {}
        for name in archive.namelist():
            if not name.startswith("notes/"):
                continue
            document = archive.read(name).decode()
            assert document.startswith("---\n")
            front, body = document[4:].split("\n---\n\n", 1)
            metadata = {
                key: json.loads(value)
                for key, value in (line.split(": ", 1) for line in front.splitlines())
            }
            records[metadata["id"]] = (name, metadata, body)
        return records


async def test_export_is_a_portable_archive_with_unchanged_markdown_and_unique_names(owner):
    body = '# Shared title\n\nQuotes " and é.\n\n#Ideas #project/spunto\n```\n#not-a-label\n```\n'
    first = await save(owner, body)
    second = await save(owner, body)
    archived = await save(owner, "Archived words #past")
    trashed = await save(owner, "Trashed words #past")
    private = await save(owner, "Private words", private=True)
    await flags(owner, first, pinned=True)
    await flags(owner, archived, archived=True)
    await flags(owner, trashed, trashed=True)
    response = await owner.client.get("/api/v1/export")
    assert response.status_code == 200
    assert response.headers["content-type"] == "application/zip"
    assert response.headers["content-disposition"] == 'attachment; filename="spunto-notes.zip"'
    records = markdown_records(response.content)
    assert set(records) == {first["id"], second["id"], archived["id"], trashed["id"], private["id"]}
    assert len({record[0] for record in records.values()}) == 5
    name, metadata, exported_body = records[first["id"]]
    assert name.startswith("notes/" + first["created_at"][:4] + "/")
    assert first["id"] in name and exported_body == body
    assert metadata == {
        "id": first["id"],
        "created": first["created_at"],
        "updated": metadata["updated"],
        "labels": ["Ideas", "project/spunto"],
        "pinned": True,
        "archived": False,
        "trashed": False,
        "private": False,
        "source": first["source"],
        "origin": "owner",
        "machine": False,
        "conflict_of": None,
    }
    assert metadata["updated"].endswith("Z")
    assert records[archived["id"]][1]["archived"] is True
    assert records[trashed["id"]][1]["trashed"] is True
    assert records[private["id"]][1]["private"] is True
    # Exporting never rewrites the database's Markdown.
    assert (await owner.client.get(f"/api/v1/notes/{first['id']}")).json()["body"] == body


async def test_export_token_visibility_defaults_and_opt_ins_include_archived_and_trash(owner):
    public = await save(owner, "Public")
    private = await save(owner, "Private", private=True)
    outside = await save(owner, "Outside")
    archived = await save(owner, "Archived")
    trashed = await save(owner, "Trashed")
    await flags(owner, archived, archived=True)
    await flags(owner, trashed, trashed=True)
    async with owner.services.store.write() as connection:
        await connection.execute("UPDATE note SET origin='external' WHERE id=?", (outside["id"],))
    defaults = await token(owner)
    restricted = await token(owner, external_ok=False)
    private_allowed = await token(owner, private_ok=True, external_ok=False)
    everyone = await token(owner, private_ok=True)
    cases = [
        (defaults, {public["id"], outside["id"], archived["id"], trashed["id"]}),
        (restricted, {public["id"], archived["id"], trashed["id"]}),
        (private_allowed, {public["id"], private["id"], archived["id"], trashed["id"]}),
        (everyone, {public["id"], private["id"], outside["id"], archived["id"], trashed["id"]}),
    ]
    for credential, expected in cases:
        response = await owner.client.get(
            "/api/v1/export", headers={"Authorization": "Bearer " + credential["token"]}
        )
        assert response.status_code == 200
        assert set(markdown_records(response.content)) == expected


async def test_attachment_export_uses_sharded_files_and_only_rewrites_markdown_destinations(owner):
    attachment_id = str(uuid.uuid7())
    attachment_bytes = b"a portable attachment"
    digest = hashlib.sha256(attachment_bytes).hexdigest()
    original = owner.services.settings.attachments_dir / digest[:2] / digest[2:4] / digest
    original.parent.mkdir(parents=True)
    original.write_bytes(attachment_bytes)
    url = f"/a/{attachment_id}"
    body = (
        f'Attachment examples\n\n![image]({url})\n[download]({url} "caption")\n'
        f"[reference][asset]\n\n[asset]: {url}\n\n"
        f"Literal {url} should remain unchanged.\n`{url}`\n\n"
        f"```markdown\n[not a link]({url})\n```\n\n"
        f"[different]({url}-suffix)\n[remote](https://example.test{url})\n"
    )
    first = await save(owner, body)
    second = await save(owner, f"Another reference\n\n[download]({url})")
    async with owner.services.store.write() as connection:
        await connection.execute(
            "INSERT INTO attachment(id,sha256,mime,size,filename,created_at) VALUES(?,?,?,?,?,0)",
            (attachment_id, digest, "text/plain", len(attachment_bytes), "readme.txt"),
        )
        for note in (first, second):
            await connection.execute(
                "INSERT INTO note_attachment VALUES(?,?)", (note["id"], attachment_id)
            )
    response = await owner.client.get("/api/v1/export")
    assert response.status_code == 200
    records = markdown_records(response.content)
    relative = f"../../attachments/{digest}.txt"
    expected = body.replace(f"![image]({url})", f"![image]({relative})")
    expected = expected.replace(f'[download]({url} "caption")', f'[download]({relative} "caption")')
    expected = expected.replace(f"[asset]: {url}", f"[asset]: {relative}")
    assert records[first["id"]][2] == expected
    assert records[second["id"]][2] == f"Another reference\n\n[download]({relative})"
    with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
        assert archive.read(f"attachments/{digest}.txt") == attachment_bytes
        assert [name for name in archive.namelist() if name.startswith("attachments/")] == [
            f"attachments/{digest}.txt"
        ]
    assert (await owner.client.get(f"/api/v1/notes/{first['id']}")).json()["body"] == body


async def test_rest_token_defaults_expiry_and_credential_metadata_show_the_secret_once(owner):
    minted = await token(owner)
    secret = minted["token"].split("_", 3)[3]
    async with owner.services.store.read() as connection:
        row = dict(
            (
                await connection.execute_fetchall(
                    "SELECT * FROM credential WHERE id=?", (minted["id"],)
                )
            )[0]
        )
    assert (
        row["scope"],
        row["client"],
        row["private_ok"],
        row["external_ok"],
        row["expires_at"],
    ) == ("read", "script", 0, 1, None)
    assert row["secret_hash"] == hashlib.sha256(secret.encode()).hexdigest()
    assert secret not in repr(row) and minted["token"] not in repr(row)
    listing = (await owner.client.get("/api/v1/credentials")).json()
    metadata = next(item for item in listing if item["id"] == minted["id"])
    assert metadata["kind"] == "pat" and metadata["name"] == "Export client"
    assert metadata["created_at"].endswith("Z") and metadata["last_used_at"] is None
    assert minted["token"] not in json.dumps(listing) and "secret_hash" not in json.dumps(listing)
    bearer = {"Authorization": "Bearer " + minted["token"]}
    assert (await owner.client.get("/api/v1/notes", headers=bearer)).status_code == 200
    listing = (await owner.client.get("/api/v1/credentials")).json()
    assert next(item for item in listing if item["id"] == minted["id"])["last_used_at"].endswith(
        "Z"
    )
    expiry = datetime.now(UTC) + timedelta(days=1)
    expiring = await token(
        owner,
        client="codex",
        scope="write",
        private_ok=True,
        external_ok=False,
        expires_at=expiry.isoformat(),
    )
    listing = (await owner.client.get("/api/v1/credentials")).json()
    assert next(item for item in listing if item["id"] == expiring["id"])[
        "expires_at"
    ] == expiry.isoformat(timespec="milliseconds").replace("+00:00", "Z")
    async with owner.services.store.write() as connection:
        await connection.execute("UPDATE credential SET expires_at=1 WHERE id=?", (expiring["id"],))
    assert (
        await owner.client.get(
            "/api/v1/notes", headers={"Authorization": "Bearer " + expiring["token"]}
        )
    ).status_code == 401
    assert expiring["id"] not in {
        item["id"] for item in (await owner.client.get("/api/v1/credentials")).json()
    }
    assert (
        await owner.client.delete("/api/v1/credentials/" + minted["id"], headers=ORIGIN)
    ).status_code == 204
    assert (await owner.client.get("/api/v1/notes", headers=bearer)).status_code == 401


@pytest.mark.parametrize("expiry", ["2000-01-01T00:00:00Z", "2030-01-01T00:00:00"])
async def test_rest_token_expiry_rejects_past_and_unzoned_values(owner, expiry):
    response = await owner.client.post(
        "/api/v1/tokens", json={"name": "Invalid expiry", "expires_at": expiry}, headers=ORIGIN
    )
    assert response.status_code == 422
    async with owner.services.store.read() as connection:
        assert not await connection.execute_fetchall("SELECT id FROM credential WHERE kind='pat'")


async def test_status_token_monitors_counts_and_backup_without_exposing_note_content(owner):
    note = await save(owner, "private-body-monitor-sentinel", private=True)
    await flags(owner, note, trashed=True)
    status = await token(owner, client="monitor", scope="status")
    snapshot = await asyncio.to_thread(backup_now, owner.services.settings)
    response = await owner.client.get(
        "/api/v1/status", headers={"Authorization": "Bearer " + status["token"]}
    )
    assert response.status_code == 200
    report = response.json()
    assert (
        report["database"]["notes"]
        == report["database"]["private_notes"]
        == report["database"]["trashed_notes"]
        == 1
    )
    assert report["database"]["size_bytes"] > 0 and report["uptime_seconds"] >= 0
    assert report["backup"]["size_bytes"] == snapshot.stat().st_size
    assert report["backup"]["last_at"].endswith("Z") and report["backup"]["age_hours"] >= 0
    assert (
        "private-body-monitor-sentinel" not in response.text and "secret_hash" not in response.text
    )
    for path in ("/api/v1/export", "/api/v1/notes/" + note["id"], "/api/v1/labels"):
        assert (
            await owner.client.get(path, headers={"Authorization": "Bearer " + status["token"]})
        ).status_code == 403
    assert (await owner.client.get("/api/v1/status")).status_code == 200


@pytest.fixture
def cli_logging(monkeypatch):
    monkeypatch.setattr(cli.logs, "configure", lambda level, fmt: None)


def test_cli_token_requires_a_database_and_finished_setup(env, settings, capsys, cli_logging):
    assert cli.main(["token", "create", "--name", "Before setup"]) == 1
    assert "finish owner setup" in capsys.readouterr().err
    settings.data_dir.mkdir(parents=True)
    migrate(settings.data_dir / DB_NAME, settings.backup_dir / "pre-migration")
    assert cli.main(["token", "create", "--name", "Before setup"]) == 1
    assert "Finish owner setup" in capsys.readouterr().err
    with closing(connect(settings.data_dir / DB_NAME)) as connection:
        assert connection.execute("SELECT count(*) FROM credential").fetchone()[0] == 0


async def test_cli_token_flags_and_expiry_match_the_http_credential_contract(
    owner, capsys, cli_logging
):
    expiry = datetime.now(UTC) + timedelta(days=2)
    result = await asyncio.to_thread(
        cli.main,
        [
            "token",
            "create",
            "--name",
            "Terminal agent",
            "--client",
            "codex",
            "--scope",
            "write",
            "--private",
            "--no-external",
            "--expires-at",
            expiry.isoformat(),
        ],
    )
    assert result == 0
    minted = capsys.readouterr().out.strip()
    assert minted.startswith("spunto_pat_") and "\n" not in minted
    credential_id, secret = minted.split("_", 3)[2:]
    async with owner.services.store.read() as connection:
        row = dict(
            (
                await connection.execute_fetchall(
                    "SELECT * FROM credential WHERE id=?", (credential_id,)
                )
            )[0]
        )
    assert (row["name"], row["client"], row["scope"], row["private_ok"], row["external_ok"]) == (
        "Terminal agent",
        "codex",
        "write",
        1,
        0,
    )
    assert row["expires_at"] == int(expiry.timestamp() * 1000)
    assert row["secret_hash"] == hashlib.sha256(secret.encode()).hexdigest()
    assert (
        await owner.client.get("/api/v1/notes", headers={"Authorization": "Bearer " + minted})
    ).status_code == 200
    assert await asyncio.to_thread(cli.main, ["token", "create", "--name", "Default script"]) == 0
    default = capsys.readouterr().out.strip()
    assert default != minted and default.startswith("spunto_pat_")
    async with owner.services.store.read() as connection:
        row = (
            await connection.execute_fetchall(
                "SELECT scope,client,private_ok,external_ok,expires_at FROM credential WHERE id=?",
                (default.split("_", 3)[2],),
            )
        )[0]
    assert tuple(row) == ("read", "script", 0, 1, None)


@pytest.mark.parametrize("expiry", ["not-a-date", "2000-01-01T00:00:00Z", "2030-01-01T00:00:00"])
async def test_cli_rejects_unsafe_expiry_without_creating_tokens(
    owner, capsys, cli_logging, expiry
):
    result = await asyncio.to_thread(
        cli.main, ["token", "create", "--name", "Invalid expiry", "--expires-at", expiry]
    )
    assert result == 1 and "--expires-at must be a future" in capsys.readouterr().err
    async with owner.services.store.read() as connection:
        assert not await connection.execute_fetchall("SELECT id FROM credential WHERE kind='pat'")


class Assets(HTMLParser):
    def __init__(self):
        super().__init__()
        self.sources = []
        self.scripts = []

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag == "script":
            self.scripts.append(values.get("src"))
        if tag in {"script", "link", "img", "iframe"}:
            self.sources.extend(values[key] for key in ("src", "href") if key in values)


async def test_reference_is_static_offline_and_openapi_describes_note_responses(server):
    response = await server.client.get("/api/v1/docs")
    assert response.status_code == 200 and response.headers["content-type"].startswith("text/html")
    assets = Assets()
    assets.feed(response.text)
    assert not assets.scripts and not assets.sources
    assert "Not encrypted" in response.text and "/api/v1/openapi.json" in response.text
    assert response.headers["referrer-policy"] == "same-origin"
    assert response.headers["x-content-type-options"] == "nosniff"
    assert "script-src 'self'" in response.headers["content-security-policy"]
    assert "frame-ancestors 'none'" in response.headers["content-security-policy"]
    document = (await server.client.get("/api/v1/openapi.json")).json()
    note = document["components"]["schemas"]["Note"]
    assert {"id", "body", "version", "labels", "source", "origin", "private", "created_at"} <= set(
        note["properties"]
    )
    for path, method, status in (
        ("/api/v1/notes", "post", "201"),
        ("/api/v1/notes/{id}", "get", "200"),
    ):
        schema = document["paths"][path][method]["responses"][status]["content"][
            "application/json"
        ]["schema"]
        assert schema["$ref"] == "#/components/schemas/Note"
    assert (
        document["paths"]["/api/v1/search"]["get"]["responses"]["200"]["content"][
            "application/json"
        ]["schema"]["$ref"]
        == "#/components/schemas/SearchPage"
    )
