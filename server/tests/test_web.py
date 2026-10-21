"""M3 integration contracts against real SQLite and the production SPA host."""

import json
import re
from urllib.parse import quote

import pytest
from conftest import PUBLIC_URL

from spunto.auth import tokens
from spunto.core.render import render_markdown
from spunto.store.db import now_ms

pytestmark = pytest.mark.anyio
ORIGIN = {"Origin": PUBLIC_URL}


async def setup(server):
    result = await server.client.post(
        "/api/v1/setup",
        json={"code": server.services.setup_code, "password": "correct horse battery"},
    )
    assert result.status_code == 204


async def note(server, body="A note", **values):
    result = await server.client.post(
        "/api/v1/notes", json={"body": body, **values}, headers=ORIGIN
    )
    assert result.status_code == 201, result.text
    return result.json()


async def test_spa_theme_assets_and_api_boundaries(server, tmp_path):
    root = tmp_path / "web"
    root.mkdir()
    (root / "index.html").write_text('<!doctype html><html lang="en"><body>Spunto</body></html>')
    (root / "assets").mkdir()
    (root / "assets" / "app-12345678.js").write_text("export{}")
    (root / ".env").write_text("never serve this")
    (root / "escape.js").symlink_to(tmp_path / "outside.js")
    (tmp_path / "outside.js").write_text("private")
    server.services.settings.web_dir = root
    for path in ("/", "/n/any-id", "/settings/plugins", "/index.html"):
        result = await server.client.get(path)
        assert result.status_code == 200
        assert 'data-theme="carta"' in result.text
        assert 'data-mode="system"' in result.text
        assert result.headers["cache-control"] == "no-store"
        assert result.headers["vary"] == "Cookie"
        assert "script-src 'self'" in result.headers["content-security-policy"]
    asset = await server.client.get("/assets/app-12345678.js")
    assert asset.status_code == 200 and "immutable" in asset.headers["cache-control"]
    for path in ("/.env", "/escape.js", "/assets/missing.js", "/api/v1/missing", "/a/missing"):
        assert (await server.client.get(path)).status_code in (401, 404)
    assert (await server.client.post("/mcp", json={})).status_code in (401, 404)
    assert (await server.client.post("/unknown", json={})).status_code == 404
    await setup(server)
    changed = await server.client.put("/api/v1/settings", json={"theme": "salvia"}, headers=ORIGIN)
    assert changed.status_code == 200 and changed.json()["theme"] == "salvia"
    assert 'data-theme="salvia"' in (await server.client.get("/")).text
    cookie = quote(json.dumps({"theme": "ardesia", "mode": "dark", "reading": "sans", "size": "l"}))
    result = await server.client.get("/", headers={"Cookie": "theme=" + cookie})
    for key, value in dict(theme="ardesia", mode="dark", reading="sans", size="l").items():
        assert f'data-{key}="{value}"' in result.text
    evil = quote(json.dumps({"theme": '"><script>alert(1)</script>', "mode": ["dark"]}))
    result = await server.client.get("/", headers={"Cookie": "theme=" + evil})
    assert 'data-theme="salvia"' in result.text and "<script>" not in result.text
    assert (
        await server.client.put("/api/v1/settings", json={"theme": "invented"}, headers=ORIGIN)
    ).status_code == 422


async def test_session_identity_survives_rotation_and_is_owner_only(server):
    assert (await server.client.get("/api/v1/auth/session")).status_code == 401
    await setup(server)
    retry_headers = ORIGIN | {"Idempotency-Key": "00000000-0000-4000-8000-000000000099"}
    first = await server.client.post(
        "/api/v1/notes", json={"body": "uncertain capture"}, headers=retry_headers
    )
    assert first.status_code == 201
    before = (await server.client.get("/api/v1/auth/session")).json()
    assert before["password_managed"] is False and len(before["owner_id"]) == 32
    assert (await server.client.post("/api/v1/auth/logout", headers=ORIGIN)).status_code == 204
    assert (
        await server.client.post(
            "/api/v1/auth/login", json={"password": "correct horse battery"}, headers=ORIGIN
        )
    ).status_code == 204
    after = (await server.client.get("/api/v1/auth/session")).json()
    assert after["owner_id"] == before["owner_id"]
    assert after["credential_id"] != before["credential_id"]
    retry = await server.client.post(
        "/api/v1/notes", json={"body": "uncertain capture"}, headers=retry_headers
    )
    assert retry.status_code == 201 and retry.json()["id"] == first.json()["id"]
    assert len((await server.client.get("/api/v1/notes")).json()["notes"]) == 1
    _, pat = await tokens.create(server.services.store, name="Read", client="script", scope="read")
    assert (
        await server.client.get("/api/v1/auth/session", headers={"Authorization": "Bearer " + pat})
    ).status_code == 403


async def test_multipart_upload_dedup_scope_download_and_limits(server):
    data = b"\x89PNG\r\n\x1a\n" + b"fixture"
    assert (
        await server.client.post("/api/v1/attachments", files={"file": ("photo.png", data)})
    ).status_code == 401
    await setup(server)
    assert (
        await server.client.post("/api/v1/attachments", files={"file": ("photo.png", data)})
    ).status_code == 403
    uploaded = await server.client.post(
        "/api/v1/attachments", files={"file": ("photo.png", data, "image/png")}, headers=ORIGIN
    )
    assert uploaded.status_code == 200, uploaded.text
    item = uploaded.json()
    assert item["size"] == len(data) and item["mime"] == "image/png"
    assert item["snippet"] == f"![photo.png]({item['url']})"
    again = await server.client.post(
        "/api/v1/attachments", files={"file": ("other.png", data)}, headers=ORIGIN
    )
    assert again.json()["id"] == item["id"]
    downloaded = await server.client.get(item["url"])
    assert downloaded.content == data and "inline" in downloaded.headers["content-disposition"]
    assert downloaded.headers["cache-control"] == "private, no-store"
    svg = await server.client.post(
        "/api/v1/attachments",
        files={"file": ("evil.svg", b"<svg><script/></svg>", "image/svg+xml")},
        headers=ORIGIN,
    )
    downloaded = await server.client.get(svg.json()["url"])
    assert downloaded.headers["content-type"] == "application/octet-stream"
    assert "attachment;" in downloaded.headers["content-disposition"]
    _, pat = await tokens.create(server.services.store, name="Read", client="script", scope="read")
    bearer = {"Authorization": "Bearer " + pat}
    assert (await server.client.get(item["url"], headers=bearer)).status_code == 404
    assert (
        await server.client.post("/api/v1/attachments", files={"file": ("x", data)}, headers=bearer)
    ).status_code == 403
    await note(server, item["snippet"])
    assert (await server.client.get(item["url"], headers=bearer)).content == data
    for files in (
        {"file": ("empty", b"")},
        {"wrong": ("x", data)},
        {"file": ("x", data), "extra": ("y", data)},
    ):
        response = await server.client.post("/api/v1/attachments", files=files, headers=ORIGIN)
        assert response.status_code in (400, 422), response.text
    oversized = await server.client.post(
        "/api/v1/attachments",
        files={"file": ("x", b"x" * (server.services.settings.max_upload_bytes + 1))},
        headers=ORIGIN,
    )
    assert oversized.status_code == 413


async def test_label_impact_counts_distinct_notes_in_archive_trash_and_search_html(server):
    await setup(server)
    first = await note(server, "#Work #work/deep #work/deep\n- [ ] Capture")
    second = await note(server, "#work/sub")
    third = await note(server, "#work/sub/last")
    await note(server, "#workspace `#work`\n```\n#work\n```")
    await server.client.post(
        f"/api/v1/notes/{second['id']}/flags", json={"archived": True}, headers=ORIGIN
    )
    await server.client.post(
        f"/api/v1/notes/{third['id']}/flags", json={"trashed": True}, headers=ORIGIN
    )
    impact = await server.client.post("/api/v1/labels/impact", json={"old": "work"}, headers=ORIGIN)
    assert impact.json() == {"notes": 3}
    too_deep = await server.client.post(
        "/api/v1/labels/impact", json={"old": "work", "new": "a/b/c/d"}, headers=ORIGIN
    )
    assert too_deep.status_code == 422
    assert (await server.client.get("/api/v1/search", params={"q": "Capture"})).json()["hits"][0][
        "id"
    ] == first["id"]
    result = await server.client.get("/api/v1/search", params={"q": "Capture", "render": "html"})
    assert "data-checkbox-offset=" in result.json()["hits"][0]["html"]


async def test_slip_old_note_visibility_dismissal_and_catalog_counts(server):
    await setup(server)
    visible = await note(server, "Old recall", created_at="2020-01-01T12:00:00Z")
    private = await note(server, "Private recall", private=True)
    await server.services.host.enable("resurface", ["notes.read", "annotate", "schedule"])
    payload = {"type": "notes", "items": [{"id": visible["id"]}, {"id": private["id"]}]}
    async with server.services.store.write() as conn:
        await conn.execute(
            "INSERT INTO annotation VALUES (?,?,?,?,?,?)",
            ("resurface", visible["id"], "slip", json.dumps(payload), 1, now_ms()),
        )
    owner = (await server.client.get("/api/v1/slip")).json()["slip"]
    assert owner["note"]["id"] == visible["id"] and owner["note"]["html"]
    _, pat = await tokens.create(server.services.store, name="Read", client="script", scope="read")
    bearer = {"Authorization": "Bearer " + pat}
    public = (await server.client.get("/api/v1/slip", headers=bearer)).json()["slip"]
    assert [item["id"] for item in public["payload"]["items"]] == [visible["id"]]
    catalog = (await server.client.get("/api/v1/plugins")).json()["plugins"]
    resurface = next(p for p in catalog if p["id"] == "resurface")
    assert resurface["data_count"] >= 1 and resurface["data_counts"]["annotation"] == 1
    body = {"plugin_id": "resurface", "note_id": visible["id"]}
    assert (
        await server.client.post("/api/v1/slip/dismiss", json=body, headers=bearer)
    ).status_code == 403
    before = (await server.client.get("/api/v1/changes/wait", params={"timeout": 0})).json()[
        "cursor"
    ]
    assert (await server.client.post("/api/v1/slip/dismiss", json=body, headers=ORIGIN)).json() == {
        "dismissed": True
    }
    assert (await server.client.get("/api/v1/slip")).json() == {"slip": None}
    after = (await server.client.get("/api/v1/changes/wait", params={"timeout": 0})).json()[
        "cursor"
    ]
    assert after != before
    assert (await server.client.get(f"/api/v1/notes/{visible['id']}")).json()[
        "body"
    ] == "Old recall"


async def test_slip_leaves_with_an_archived_or_trashed_note(server):
    await setup(server)
    old = await note(server, "Old recall", created_at="2020-01-01T12:00:00Z")
    await server.services.host.enable("resurface", ["notes.read", "annotate", "schedule"])
    payload = {"type": "notes", "items": [{"id": old["id"]}]}
    async with server.services.store.write() as conn:
        await conn.execute(
            "INSERT INTO annotation VALUES (?,?,?,?,?,?)",
            ("resurface", old["id"], "slip", json.dumps(payload), 1, now_ms()),
        )

    async def slip():
        return (await server.client.get("/api/v1/slip")).json()["slip"]

    async def flag(**flags):
        await server.client.post(f"/api/v1/notes/{old['id']}/flags", json=flags, headers=ORIGIN)

    assert (await slip())["note_id"] == old["id"]
    await flag(archived=True)
    assert await slip() is None
    body = {"plugin_id": "resurface", "note_id": old["id"]}
    dismissed = await server.client.post("/api/v1/slip/dismiss", json=body, headers=ORIGIN)
    assert dismissed.status_code == 404
    await flag(archived=False)
    assert (await slip())["note_id"] == old["id"]
    await flag(trashed=True)
    assert await slip() is None


@pytest.mark.parametrize("prefix", ["", "🎈\n\n", "a\r\n\r\n", "# Heading\n\n"])
async def test_checkbox_offsets_are_utf16_and_code_is_protected(prefix):
    body = (
        prefix + "- [ ] First\n- [X] Done\n  - [x] Nested\n\n```\n- [ ] code\n```\n\n"
        "`[ ] literal`\n\\[ ] escaped"
    )
    html = render_markdown(body)
    positions = re.findall(r'data-checkbox-offset="(\d+)"', html)
    assert len(positions) == 3, html
    encoded = body.encode("utf-16-le")
    for position in positions:
        at = int(position) * 2
        assert encoded[at - 2 : at].decode("utf-16-le") == "["
        assert encoded[at : at + 2].decode("utf-16-le") in " xX"
        assert encoded[at + 2 : at + 4].decode("utf-16-le") == "]"
    assert html.count('checked=""') == 2 and "- [ ] code" in html
    raw = render_markdown('<input type="checkbox" data-checkbox-offset="0" onclick="steal()">')
    assert "<input" not in raw


async def test_labels_link_inline_without_touching_code_escapes_images_or_existing_links():
    body = (
        "A #Work/deep and #caffè. #123 \\#escaped `#code`\n\n"
        "```\n#fenced\n```\n\n"
        "[existing #link](https://example.org) ![#image](/a/fixture)\n\n"
        "- [ ] #task"
    )
    html = render_markdown(body)
    assert '<a class="label" href="/l/Work/deep"' in html
    assert 'href="/l/caff%C3%A8"' in html
    assert 'href="/l/task"' in html
    for path in ("123", "escaped", "code", "fenced", "image", "link"):
        assert f'href="/l/{path}"' not in html
    assert 'alt="#image"' in html
    assert 'href="https://example.org"' in html
    assert "\ue000" not in html and "\ue001" not in html
    assert html.count("<input ") == 1


@pytest.mark.parametrize(
    "body",
    [
        "[jump](#ideas)",
        '[jump](#ideas "Go #there")',
        "[jump][#ref]\n\n[#ref]: https://example.org/#target",
    ],
)
async def test_label_renderer_preserves_fragment_links_titles_and_references(body):
    html = render_markdown(body)
    assert ">jump</a>" in html
    assert "\ue000" not in html and "%EE%80" not in html
    assert 'href="#ideas"' in html or 'href="https://example.org/#target"' in html
