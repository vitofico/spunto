"""M1 contracts through real SQLite and the REST boundary."""

import hashlib
import uuid

import pytest
from conftest import PUBLIC_URL

from spunto.api.errors import Problem
from spunto.auth import tokens
from spunto.auth.tokens import Principal
from spunto.core.notes import Notes
from spunto.store.db import now_ms

pytestmark = pytest.mark.anyio
ORIGIN = {"Origin": PUBLIC_URL}


@pytest.fixture
def domain(server):
    return Notes(
        server.services.store,
        Principal("owner-one", "session", "Owner", "full", private_ok=True),
    )


@pytest.fixture
async def signed_in(server):
    response = await server.client.post(
        "/api/v1/setup",
        json={"code": server.services.setup_code, "password": "correct horse battery"},
    )
    assert response.status_code == 204
    return server


async def bearer(server, scope="read", **options):
    _, token = await tokens.create(
        server.services.store, name="Test client", client="script", scope=scope, **options
    )
    return {"Authorization": "Bearer " + token}


async def post_note(server, body="An idea", **options):
    response = await server.client.post(
        "/api/v1/notes", json={"body": body, **options}, headers=ORIGIN
    )
    assert response.status_code == 201, response.text
    return response.json()


async def test_domain_save_derives_labels_search_title_hash_and_real_tasks_atomically(domain):
    body = (
        "# Plan\n- [ ] Call Marco\n- [x] Buy milk\n```\n"
        "- [ ] Not a task #hidden\n```\n#Idea #project/spunto"
    )
    note = await domain.create(body)
    assert uuid.UUID(note["id"]).version == 7
    assert note["title"] == "Plan"
    assert note["labels"] == ["Idea", "project/spunto"]
    assert note["open_tasks"] == 1
    assert note["source"]["kind"] == "api"
    assert note["origin"] == "owner"
    assert (await domain.search("Marco #project"))["hits"][0]["id"] == note["id"]
    async with domain.store.read() as connection:
        row = (
            await connection.execute_fetchall(
                "SELECT body_hash FROM note WHERE id=?", (note["id"],)
            )
        )[0]
    assert row["body_hash"] == hashlib.sha256(body.encode()).hexdigest()
    changed = await domain.edit(note["id"], "Replacement #new", note["version"])
    assert changed["labels"] == ["new"]
    assert not (await domain.search("Marco"))["hits"]
    assert (await domain.search("Replacement #new"))["hits"][0]["id"] == note["id"]
    assert [label["path"] for label in (await domain.labels())["labels"]] == ["new"]
    with pytest.raises(Problem):
        await domain.append(note["id"], "x" * 100_001)
    unchanged = await domain.get(note["id"])
    assert unchanged["body"] == changed["body"] and unchanged["version"] == changed["version"]


async def test_domain_coalescing_keeps_baseline_and_other_credentials_break_session(domain):
    note = await domain.create("Baseline")
    second = await domain.edit(note["id"], "First edit", 1)
    third = await domain.edit(note["id"], "Second edit", second["version"])
    assert [(r["version"], r["body"]) for r in await domain.revisions(note["id"])] == [
        (third["version"], "Second edit"),
        (1, "Baseline"),
    ]
    agent = Notes(domain.store, Principal("agent", "pat", "Agent", "write"))
    appended = await agent.append(note["id"], "Agent addition")
    after_agent = await domain.edit(note["id"], "Owner after agent", appended["version"])
    history = await domain.revisions(note["id"])
    assert {r["version"] for r in history} == {
        1,
        third["version"],
        appended["version"],
        after_agent["version"],
    }
    restored = await domain.restore_revision(note["id"], 1)
    assert restored["body"] == "Baseline"
    assert restored["version"] > after_agent["version"]
    assert (await domain.revisions(note["id"]))[0]["change_kind"] == "restore"


async def test_domain_stale_edit_merges_adjacent_lines_and_conflict_preserves_bodies(domain):
    note = await domain.create(
        "a\nb\n", source={"kind": "share", "ref": "browser", "url": "https://example.org"}
    )
    agent = Notes(domain.store, Principal("agent", "pat", "Agent", "write"))
    await agent.append(note["id"], "Added by agent", separator="line")
    clean = await domain.edit(note["id"], "A\nb\n", 1)
    assert clean["merged"] is True
    assert "A\n" in clean["body"] and "Added by agent" in clean["body"]
    separate = await domain.create("Original line")
    current = await domain.edit(separate["id"], "Owner's full text", 1)
    conflict = await agent.edit(separate["id"], "Agent's full text", 1)
    assert conflict["conflict"] is True
    assert (await domain.get(separate["id"]))["body"] == current["body"]
    copy = await domain.get(conflict["conflict_id"])
    assert copy["body"] == "Agent's full text"
    assert copy["conflict_of"] == separate["id"]
    assert copy["source"] == separate["source"]
    assert copy["created_at"] == separate["created_at"]
    with pytest.raises(Problem) as failure:
        await domain.edit(separate["id"], "Future", current["version"] + 1)
    assert failure.value.code == "resync_required"


async def test_domain_flag_clock_clamps_future_and_old_arrivals_cannot_override(
    domain, monkeypatch
):
    note = await domain.create("Flag test")
    tick = now_ms()
    monkeypatch.setattr("spunto.core.notes.now_ms", lambda: tick)
    await domain.flags(note["id"], {"archived": True}, {"archived": tick + 86_400_000})
    async with domain.store.read() as connection:
        row = (
            await connection.execute_fetchall(
                "SELECT archived_clock FROM note WHERE id=?", (note["id"],)
            )
        )[0]
    assert row["archived_clock"] == tick
    monkeypatch.setattr("spunto.core.notes.now_ms", lambda: tick + 1)
    latest = await domain.flags(note["id"], {"archived": False}, {"archived": tick + 1})
    stale = await domain.flags(note["id"], {"archived": True}, {"archived": tick - 1})
    assert latest["archived"] is False
    assert stale["archived"] is False and stale["version"] == latest["version"]
    tied = await domain.flags(note["id"], {"archived": True}, {"archived": tick + 1})
    assert tied["archived"] is True


async def test_domain_namespace_rename_merge_remove_and_rollback(domain):
    note = await domain.create("#work #work/x #workout `#work`")
    target = await domain.create("#job")
    assert await domain.rewrite_label("work", "job") == 1
    rewritten = await domain.get(note["id"])
    assert rewritten["body"] == "#job #job/x #workout `#work`"
    vocabulary = {label["path"]: label for label in (await domain.labels())["labels"]}
    assert vocabulary["job"]["count"] == 2 and vocabulary["job"]["own_count"] == 2
    assert vocabulary["job/x"]["count"] == 1
    assert await domain.rewrite_label("job", None) == 2
    assert (await domain.get(note["id"]))["body"] == "  #workout `#work`"
    assert (await domain.get(target["id"]))["body"] == ""
    shallow = await domain.create("#a/one")
    deep = await domain.create("#a/b/c/d/e")
    with pytest.raises((ValueError, Problem)):
        await domain.rewrite_label("a", "x/y")
    assert (await domain.get(shallow["id"]))["body"] == shallow["body"]
    assert (await domain.get(deep["id"]))["body"] == deep["body"]


async def test_domain_private_external_visibility_applies_to_every_read_and_replay(domain):
    public = await domain.create("Visible unique #public")
    private = await domain.create("Private unique #private", private=True)
    external = await domain.create("External unique #external")
    # Seed the origin an external ingestion plugin will assign, without implementing that plugin.
    async with domain.store.write() as connection:
        await connection.execute("UPDATE note SET origin='external' WHERE id=?", (external["id"],))
    reader = Notes(domain.store, Principal("reader", "pat", "Reader", "read", external_ok=False))
    assert [n["id"] for n in (await reader.list())["notes"]] == [public["id"]]
    assert [n["id"] for n in (await reader.search("unique"))["hits"]] == [public["id"]]
    assert [label["path"] for label in (await reader.labels())["labels"]] == ["public"]
    for hidden in (private, external):
        for read in (reader.get, reader.revisions):
            with pytest.raises(Problem) as failure:
                await read(hidden["id"])
            assert failure.value.status == 404
    writer = Notes(domain.store, Principal("writer", "pat", "Writer", "write"))
    created = await writer.create("My captured text", op_id="request-id")
    await domain.flags(created["id"], {"private": True})
    with pytest.raises(Problem) as failure:
        await writer.create("My captured text", op_id="request-id")
    assert failure.value.status == 404


async def test_domain_search_unicode_literal_input_and_namespace_filters(domain):
    match = await domain.create("Perché Straße ﬁnestra l’idea #project/spunto")
    await domain.create("Ordinary OR near title words #other")
    for query in ("perche", "Straße", "strasse", "ﬁnestra", "finestra", "l’idea"):
        assert [n["id"] for n in (await domain.search(query + " #project"))["hits"]] == [
            match["id"]
        ]
    assert not (await domain.search('" OR NEAR(foo) title:x* -y'))["hits"]
    assert (await domain.search("#project", labels=["project/spunto"]))["hits"][0]["id"] == match[
        "id"
    ]


async def test_domain_search_with_only_punctuation_matches_nothing(domain):
    labelled = await domain.create("Garden plan #home")
    await domain.create("Another note")
    for query in ("%_*?", "***", "#", " - "):
        assert (await domain.search(query))["hits"] == []
    assert len((await domain.search(""))["hits"]) == 2
    assert [n["id"] for n in (await domain.search("#home ***"))["hits"]] == [labelled["id"]]


async def test_domain_relevance_puts_the_newer_note_first_when_scores_tie(domain):
    older = await domain.create("Duplicate thought about gardens")
    newer = await domain.create("Duplicate thought about gardens")
    async with domain.store.write() as connection:
        await connection.execute(
            "UPDATE note SET updated_at=updated_at-60000 WHERE id=?", (older["id"],)
        )
    hits = (await domain.search("gardens"))["hits"]
    assert [n["id"] for n in hits] == [newer["id"], older["id"]]


async def test_domain_relevance_paging_keeps_original_matches_when_bm25_corpus_changes(domain):
    original = [
        await domain.create(body)
        for body in ["query query", "query " + "long " * 100, "query " + "words " * 100]
    ]
    first = await domain.search("query", limit=1)
    seen = [n["id"] for n in first["hits"]]
    cursor = first["next_cursor"]
    for _ in range(10):
        await domain.create("x")
    for _ in range(5):
        if not cursor:
            break
        page = await domain.search("query", limit=1, cursor=cursor)
        seen.extend(n["id"] for n in page["hits"])
        cursor = page["next_cursor"]
    assert cursor is None
    assert len(seen) == len(set(seen)) == 3
    assert set(seen) == {n["id"] for n in original}


async def test_domain_retention_purges_trash_and_keeps_latest_twenty_revisions(domain, server):
    from spunto.jobs.maintenance import prune

    discarded = await domain.create("Discarded searchterm")
    await domain.flags(discarded["id"], {"trashed": True})
    retained = await domain.create("Baseline for old device", op_id="retention-retry")
    other = Notes(
        domain.store, Principal("owner-two", "session", "Other device", "full", private_ok=True)
    )
    version = retained["version"]
    for number in range(25):
        editor = other if number % 2 == 0 else domain
        version = (await editor.edit(retained["id"], f"Retained text {number}", version))["version"]
    counts = await prune(domain.store, server.services.settings, now=now_ms() + 181 * 86_400_000)
    assert counts["notes"] == 1 and counts["operations"] == 1 and counts["revisions"] == 6
    history = await domain.revisions(retained["id"])
    assert len(history) == 20 and history[0]["version"] == version
    assert all(revision["version"] != 1 for revision in history)
    with pytest.raises(Problem) as failure:
        await domain.get(discarded["id"], include_trashed=True)
    assert failure.value.status == 404
    async with domain.store.read() as connection:
        rows = await connection.execute_fetchall(
            "SELECT change_seq FROM tombstone WHERE kind='note' AND id=?", (discarded["id"],)
        )
    assert len(rows) == 1
    assert not (await domain.search("Discarded", include_archived=True))["hits"]
    conflict = await domain.edit(retained["id"], "Old device's complete text", 1)
    assert conflict["conflict"] is True
    assert (await domain.get(retained["id"]))["body"] == "Retained text 24"
    assert (await domain.get(conflict["conflict_id"]))["body"] == "Old device's complete text"


async def test_rest_creation_and_input_validation(signed_in):
    server = signed_in
    note = await post_note(server, "First line #Idea", created_at="2026-01-01T12:34:56Z")
    assert uuid.UUID(note["id"]).version == 7
    assert note["created_at"] == "2026-01-01T12:34:56.000Z"
    future = uuid.UUID(int=((now_ms() + 2 * 86_400_000) << 80) | (7 << 76) | (2 << 62) | 123)
    for changes in (
        {"id": str(uuid.uuid4())},
        {"id": str(future)},
        {"body": ""},
        {"created_at": "2026-01-01T12:00:00"},
        {"unknown": True},
    ):
        response = await server.client.post(
            "/api/v1/notes", json={"body": "Invalid", **changes}, headers=ORIGIN
        )
        assert response.status_code == 422, response.text
        assert response.headers["content-type"].startswith("application/problem+json")
    duplicate_id = await server.client.post(
        "/api/v1/notes", json={"id": note["id"], "body": "Duplicate"}, headers=ORIGIN
    )
    assert duplicate_id.status_code == 409
    op_id = str(uuid.uuid4())
    headers = {**ORIGIN, "Idempotency-Key": op_id}
    first = await server.client.post("/api/v1/notes", json={"body": "Retry safe"}, headers=headers)
    again = await server.client.post("/api/v1/notes", json={"body": "Retry safe"}, headers=headers)
    assert first.status_code == again.status_code == 201
    assert first.json()["id"] == again.json()["id"]


async def test_rest_versions_conflict_and_owner_revision_restore(signed_in):
    server = signed_in
    original = await post_note(server, "Original")
    url = "/api/v1/notes/" + original["id"]
    absent = await server.client.patch(url, json={"body": "Missing version"}, headers=ORIGIN)
    assert absent.status_code == 428
    edited = await server.client.patch(
        url, json={"body": "Owner text"}, headers={**ORIGIN, "If-Match": '"1"'}
    )
    assert edited.status_code == 200
    stale = await server.client.patch(
        url, json={"body": "Other full text"}, headers={**ORIGIN, "If-Match": '"1"'}
    )
    assert stale.status_code == 409 and stale.json()["code"] == "version_conflict"
    copy = (await server.client.get("/api/v1/notes/" + stale.json()["conflict_id"])).json()
    assert copy["body"] == "Other full text" and copy["conflict_of"] == original["id"]
    assert (await server.client.get(url)).json()["body"] == "Owner text"
    restored = await server.client.post(url + "/revisions/1/restore", headers=ORIGIN)
    assert restored.status_code == 200 and restored.json()["body"] == "Original"
    assert restored.json()["version"] > edited.json()["version"]


async def test_rest_cookie_csrf_and_read_status_token_boundaries(signed_in):
    server = signed_in
    note = await post_note(server)
    url = "/api/v1/notes/" + note["id"]
    for headers in ({}, {"Origin": "https://foreign.test"}):
        response = await server.client.post(
            url + "/append", json={"text": "Blocked"}, headers=headers
        )
        assert response.status_code == 403 and response.json()["code"] == "origin_mismatch"
    read = await bearer(server)
    assert (await server.client.get(url, headers=read)).status_code == 200
    rejected = await server.client.post(url + "/append", json={"text": "Blocked"}, headers=read)
    assert rejected.status_code == 403 and rejected.json()["code"] == "read_only_token"
    write = await bearer(server, "write")
    appended = await server.client.post(
        url + "/append", json={"text": "Bearer append"}, headers=write
    )
    assert appended.status_code == 200 and appended.json()["body"].endswith("Bearer append")
    status = await bearer(server, "status")
    status_response = await server.client.get("/api/v1/status", headers=status)
    assert status_response.status_code == 200
    assert status_response.json()["database"]["notes"] == 1
    assert (await server.client.get("/api/v1/status", headers=read)).status_code == 403
    for path in (url, "/api/v1/notes", "/api/v1/search", "/api/v1/labels", url + "/revisions"):
        response = await server.client.get(path, headers=status)
        assert response.status_code == 403 and response.json()["code"] == "status_only_token"
    server.client.cookies.clear()
    query_token = await server.client.get(
        url, params={"token": read["Authorization"].split(" ", 1)[1]}
    )
    assert query_token.status_code == 401


async def test_rest_token_cannot_delete_restore_or_rewrite_vocabulary(signed_in):
    server = signed_in
    note = await post_note(server, "#work Text")
    url = "/api/v1/notes/" + note["id"]
    premature = await server.client.delete(url, headers=ORIGIN)
    assert premature.status_code == 409 and premature.json()["code"] == "not_trashed"
    await server.client.post(url + "/flags", json={"trashed": True}, headers=ORIGIN)
    write = await bearer(server, "write")
    restricted = (
        ("DELETE", url, None),
        ("POST", "/api/v1/trash/empty", None),
        ("POST", url + "/revisions/1/restore", None),
        ("POST", "/api/v1/labels/rename", {"from": "work", "to": "job"}),
        ("POST", "/api/v1/labels/remove", {"path": "work"}),
    )
    session_cookies = dict(server.client.cookies)
    for with_cookie in (True, False):
        if not with_cookie:
            server.client.cookies.clear()
        for method, path, payload in restricted:
            response = await server.client.request(
                method, path, json=payload, headers={**write, **ORIGIN}
            )
            assert response.status_code == 403 and response.json()["code"] == "session_required"
    server.client.cookies.update(session_cookies)
    assert (await server.client.get(url)).json()["body"] == note["body"]
    assert (await server.client.delete(url, headers=ORIGIN)).status_code == 204
    assert (await server.client.get(url)).status_code == 404
    async with server.services.store.read() as connection:
        tombstone = await connection.execute_fetchall(
            "SELECT * FROM tombstone WHERE kind='note' AND id=?", (note["id"],)
        )
    assert len(tombstone) == 1
    recreate = await server.client.post(
        "/api/v1/notes", json={"id": note["id"], "body": "Return"}, headers=ORIGIN
    )
    assert recreate.status_code == 409


async def test_rest_private_external_notes_and_label_counts_are_hidden_until_token_opt_in(
    signed_in,
):
    server = signed_in
    public = await post_note(server, "Visible query #public")
    private = await post_note(server, "Private query #secret", private=True)
    external = await post_note(server, "External query #outside")
    async with server.services.store.write() as connection:
        await connection.execute("UPDATE note SET origin='external' WHERE id=?", (external["id"],))
    limited = await bearer(server, external_ok=False)
    for path, array in (("/api/v1/notes", "notes"), ("/api/v1/search?q=query", "hits")):
        response = await server.client.get(path, headers=limited)
        assert response.status_code == 200
        assert [n["id"] for n in response.json()[array]] == [public["id"]]
    vocabulary = (await server.client.get("/api/v1/labels", headers=limited)).json()
    assert vocabulary["labels"] == [{"path": "public", "count": 1, "own_count": 1}]
    for note in (private, external):
        for suffix in ("", "/revisions"):
            assert (
                await server.client.get("/api/v1/notes/" + note["id"] + suffix, headers=limited)
            ).status_code == 404
    permitted = await bearer(server, private_ok=True, external_ok=True)
    all_notes = await server.client.get("/api/v1/notes", headers=permitted)
    assert {n["id"] for n in all_notes.json()["notes"]} == {
        public["id"],
        private["id"],
        external["id"],
    }


async def test_rest_label_counts_leave_out_archived_notes_like_label_views(signed_in, domain):
    server = signed_in
    await post_note(server, "Live #keep/child")
    archived = await post_note(server, "Old #keep")
    only_archived = await post_note(server, "Old too #gone")
    for note in (archived, only_archived):
        flagged = await server.client.post(
            "/api/v1/notes/" + note["id"] + "/flags", json={"archived": True}, headers=ORIGIN
        )
        assert flagged.status_code == 200
    rail = (await server.client.get("/api/v1/labels")).json()
    assert rail["labels"] == [
        {"path": "keep", "count": 1, "own_count": 0},
        {"path": "keep/child", "count": 1, "own_count": 1},
    ]
    view = (await server.client.get("/api/v1/notes", params={"label": "keep"})).json()
    assert len(view["notes"]) == rail["labels"][0]["count"]
    # Plugins and agents still see the whole vocabulary, archived notes included.
    vocabulary = {label["path"]: label["count"] for label in (await domain.labels())["labels"]}
    assert vocabulary == {"gone": 1, "keep": 2, "keep/child": 1}


async def test_rest_tokens_are_shown_once_owner_managed_and_revocable(signed_in):
    server = signed_in
    minted = await server.client.post(
        "/api/v1/tokens", json={"name": "Script", "scope": "read"}, headers=ORIGIN
    )
    assert minted.status_code == 201
    data = minted.json()
    header = {"Authorization": "Bearer " + data["token"]}
    listing = (await server.client.get("/api/v1/credentials")).json()
    assert data["id"] in {item["id"] for item in listing}
    assert all("token" not in item and "secret_hash" not in item for item in listing)
    async with server.services.store.read() as connection:
        row = (
            await connection.execute_fetchall(
                "SELECT secret_hash FROM credential WHERE id=?", (data["id"],)
            )
        )[0]
    assert len(row["secret_hash"]) == 64 and row["secret_hash"] not in data["token"]
    note = await post_note(server)
    assert (
        await server.client.get("/api/v1/notes/" + note["id"], headers=header)
    ).status_code == 200
    write = await bearer(server, "write")
    session_cookies = dict(server.client.cookies)
    for with_cookie in (True, False):
        if not with_cookie:
            server.client.cookies.clear()
        for method, path, body in (
            ("POST", "/api/v1/tokens", {"name": "Forbidden"}),
            ("DELETE", "/api/v1/credentials/" + data["id"], None),
        ):
            blocked = await server.client.request(
                method, path, json=body, headers={**ORIGIN, **write}
            )
            assert blocked.status_code == 403 and blocked.json()["code"] == "session_required"
    server.client.cookies.update(session_cookies)
    revoked = await server.client.delete("/api/v1/credentials/" + data["id"], headers=ORIGIN)
    assert revoked.status_code == 204
    assert (
        await server.client.get("/api/v1/notes/" + note["id"], headers=header)
    ).status_code == 401
    expired = await bearer(server, expires_at=now_ms() - 1)
    assert (
        await server.client.get("/api/v1/notes/" + note["id"], headers=expired)
    ).status_code == 401


async def test_rest_dates_pinned_keyset_and_arrivals(signed_in):
    server = signed_in
    originals = [
        await post_note(
            server, f"Note {day} #project/spunto", created_at=f"2026-01-0{day}T12:00:00Z"
        )
        for day in range(1, 5)
    ]
    await server.client.post(
        "/api/v1/notes/" + originals[0]["id"] + "/flags", json={"pinned": True}, headers=ORIGIN
    )
    first = (await server.client.get("/api/v1/notes", params={"limit": 1})).json()
    assert first["notes"][0]["id"] == originals[0]["id"]
    await post_note(server, "New arrival")
    seen, cursor = [first["notes"][0]["id"]], first["next_cursor"]
    for _ in range(10):
        if not cursor:
            break
        page = (
            await server.client.get("/api/v1/notes", params={"limit": 1, "cursor": cursor})
        ).json()
        seen.extend(n["id"] for n in page["notes"])
        cursor = page.get("next_cursor")
    assert cursor is None and len(seen) == len(set(seen))
    assert {n["id"] for n in originals}.issubset(seen)
    filtered = await server.client.get(
        "/api/v1/search", params={"q": "#project", "from": "2026-01-02", "to": "2026-01-03"}
    )
    assert {n["id"] for n in filtered.json()["hits"]} == {originals[1]["id"], originals[2]["id"]}
    wrong_query = await server.client.get(
        "/api/v1/notes", params={"view": "archive", "cursor": first["next_cursor"]}
    )
    assert wrong_query.status_code == 422


async def test_rest_render_is_sanitized_and_remote_images_are_links(signed_in):
    server = signed_in
    body = '<script>alert("bad")</script>\n\n[click](javascript:alert(1))\n\n![remote](https://external.test/image.png)\n\n![local](/a/0192f3c4-7a1e-7c3b-9e55-1d2f0a9b8c7d)'
    note = await post_note(server, body)
    response = await server.client.get("/api/v1/notes/" + note["id"], params={"render": "html"})
    assert response.status_code == 200
    rendered = response.json()["html"]
    assert "<script" not in rendered and 'href="javascript:' not in rendered
    assert '<a href="https://external.test/image.png"' in rendered
    assert '<img src="https://external.test' not in rendered
    assert '<img src="/a/' in rendered


async def test_rest_unfinished_fence_label_error_keeps_note_unchanged(signed_in):
    server = signed_in
    note = await post_note(server, "```\nExample")
    writer = Notes(server.services.store, Principal("agent", "pat", "Agent", "write"))
    with pytest.raises(Problem) as failure:
        await writer.set_labels(note["id"], ["work"], [])
    assert failure.value.status == 422
    stored = (await server.client.get("/api/v1/notes/" + note["id"])).json()
    assert stored["body"] == note["body"] and stored["version"] == note["version"]


async def test_rest_invalid_descendant_rename_rolls_back_the_entire_vocabulary_change(signed_in):
    server = signed_in
    shallow = await post_note(server, "#a/one")
    deep = await post_note(server, "#a/b/c/d/e")
    rejected = await server.client.post(
        "/api/v1/labels/rename", json={"from": "a", "to": "x/y"}, headers=ORIGIN
    )
    assert rejected.status_code == 422 and rejected.json()["code"] == "validation_failed"
    for original in (shallow, deep):
        unchanged = (await server.client.get("/api/v1/notes/" + original["id"])).json()
        assert unchanged["body"] == original["body"] and unchanged["version"] == original["version"]
    vocabulary = (await server.client.get("/api/v1/labels")).json()["labels"]
    assert "x" not in {entry["path"] for entry in vocabulary}
