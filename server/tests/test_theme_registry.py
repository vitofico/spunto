"""Colour-only packs, failure isolation and actual public/authenticated API."""

import copy
import json
from urllib.parse import quote

import pytest
from conftest import PUBLIC_URL

from spunto.host.testing import harness
from spunto.host.themes import ACCENT_ROLES, COLOR_ROLES, ThemeRegistry, theme_manifest

ORIGIN = {"Origin": PUBLIC_URL}


def pack(tmp_path, *, key="operator-paper", colors=None, accent=False):
    path = tmp_path / "themes" / key
    path.mkdir(parents=True)
    (path / "plugin.toml").write_text(
        f'id="{key}"\nname="Operator paper"\nversion="1"\nkind="theme"\ntokens=1\n'
        'entry="evil:code"\nfonts=["https://must-not-load.invalid/font"]\n'
    )
    modes = colors if colors is not None else theme_manifest("carta")
    lines = []
    for mode in ("light", "dark"):
        values = modes[mode]
        if not isinstance(values, dict):
            lines.append(f"{mode}={json.dumps(values)}")
            continue
        lines.append(f"[{mode}]")
        lines.extend(f"{role}={json.dumps(values[role])}" for role in COLOR_ROLES)
        lines.append('font="url(https://must-not-load.invalid/font)"')
    if accent:
        lines.extend(["[accents.extra]", 'name="Extra accent"'])
        for mode in ("light", "dark"):
            lines.append(f"[accents.extra.{mode}]")
            lines.extend(f"{role}={json.dumps(modes[mode][role])}" for role in ACCENT_ROLES)
    (path / "theme.toml").write_text("\n".join(lines))
    return path


def test_colour_pack_preserves_builtins_and_ignores_executable_extra_keys(tmp_path, settings):
    pack(tmp_path, accent=True)
    registry = ThemeRegistry(settings.model_copy(update={"data_dir": tmp_path}))
    assert registry.builtin_ids == {"carta", "ardesia", "salvia", "corallo", "elettrica"}
    for key in registry.builtin_ids:
        assert registry.selected(key) == theme_manifest(key)
        assert registry.cards[key]["accents"] == []
    assert registry.selected("operator-paper", "extra")["accent"] == "extra"
    assert registry.selected("missing")["id"] == "carta"
    css = registry.css("operator-paper", "extra")
    assert 'data-theme="operator-paper"' in css and 'data-accent="extra"' in css
    assert "must-not-load" not in css and "url(" not in css and "font" not in css
    assert "@media(prefers-color-scheme:dark)" in css
    assert registry.selected("operator-paper")["dark"]["sh"] == "0 0 0"
    assert "--sh:0 0 0;" in css.split('[data-mode="dark"]{', 1)[1].split("}", 1)[0]
    assert (
        ThemeRegistry(settings.model_copy(update={"data_dir": tmp_path / "other"})).catalog()
        != registry.catalog()
    )


@pytest.mark.parametrize("mode", ["light", "dark"])
def test_failed_contrast_names_pair_and_does_not_publish_tokens(tmp_path, settings, mode):
    colors = copy.deepcopy(theme_manifest("carta"))
    colors[mode]["ink-3"] = colors[mode]["paper"]
    pack(tmp_path, colors=colors)
    registry = ThemeRegistry(settings.model_copy(update={"data_dir": tmp_path}))
    card = registry.cards["operator-paper"]
    assert card["status"] == "failed"
    assert f"ink-3 on paper is 1.00:1 in {mode}" in card["reason"]
    assert "light" not in card
    with pytest.raises(KeyError):
        registry.css("operator-paper")


def test_failed_accent_and_duplicate_builtin_are_isolated(tmp_path, settings):
    path = pack(tmp_path, accent=True)
    body = (path / "theme.toml").read_text()
    # Corrupt only the explicit accent, keeping the base valid.
    start = body.index("[accents.extra.light]")
    body = body[:start] + body[start:].replace('accent="#9F4C2A"', 'accent="#F8F5F0"')
    (path / "theme.toml").write_text(body)
    pack(tmp_path, key="carta")
    registry = ThemeRegistry(settings.model_copy(update={"data_dir": tmp_path}))
    failed = [card for card in registry.catalog() if card["status"] == "failed"]
    assert len(failed) == 2
    assert any("Accent extra:" in card["reason"] for card in failed)
    assert registry.selected("carta") == theme_manifest("carta")


@pytest.mark.parametrize("bad", ['light="invalid"', '[light]\npaper="red"', "tokens=[])"])
def test_malformed_packs_cannot_stop_startup(tmp_path, settings, bad):
    path = pack(tmp_path)
    (path / "theme.toml").write_text(bad)
    registry = ThemeRegistry(settings.model_copy(update={"data_dir": tmp_path}))
    assert len([c for c in registry.catalog() if c["status"] == "failed"]) == 1
    assert registry.available("carta")


@pytest.mark.anyio
async def test_public_css_owner_preferences_manifest_and_safe_ssr(server, tmp_path):
    pack(tmp_path, accent=True)
    host = server.services.host
    host.themes = ThemeRegistry(server.services.settings.model_copy(update={"data_dir": tmp_path}))
    response = await server.client.get("/api/v1/themes/operator-paper.css?accent=extra")
    assert response.status_code == 200 and response.headers["content-type"].startswith("text/css")
    assert response.headers["cache-control"] == "no-cache"
    assert (
        await server.client.get(
            "/api/v1/themes/operator-paper.css?accent=extra",
            headers={"If-None-Match": response.headers["etag"]},
        )
    ).status_code == 304
    assert (
        await server.client.get("/api/v1/themes/operator-paper.css?accent=unknown")
    ).status_code == 404
    assert (await server.client.get("/api/v1/themes/missing.css")).status_code == 404
    assert (
        await server.client.put(
            "/api/v1/settings", json={"theme": "operator-paper"}, headers=ORIGIN
        )
    ).status_code == 401
    setup = await server.client.post(
        "/api/v1/setup",
        json={"code": server.services.setup_code, "password": "synthetic theme owner"},
    )
    assert setup.status_code == 204
    response = await server.client.put(
        "/api/v1/settings",
        json={"theme": "operator-paper", "theme_accent": "extra"},
        headers=ORIGIN,
    )
    assert response.status_code == 200 and response.json()["theme_accent"] == "extra"
    assert (
        await server.client.put(
            "/api/v1/settings", json={"theme_accent": "unknown"}, headers=ORIGIN
        )
    ).status_code == 422
    assert (
        await server.client.put(
            "/api/v1/settings",
            json={"theme": "operator-paper"},
            headers={"Origin": "https://wrong.invalid"},
        )
    ).status_code == 403
    manifest = await server.client.get("/api/v1/plugins/manifest")
    assert manifest.json()["theme"]["accent"] == "extra"
    assert len(manifest.json()["theme_catalog"]) == 6
    web = tmp_path / "web"
    web.mkdir()
    (web / "index.html").write_text("<html><head></head><body></body></html>")
    server.services.settings.web_dir = web
    html = await server.client.get("/notes")
    assert 'data-theme="operator-paper"' in html.text and 'data-accent="extra"' in html.text
    assert 'href="/api/v1/themes/operator-paper.css?accent=extra"' in html.text
    server.client.cookies.set(
        "theme", quote(json.dumps({"theme": "operator-paper", "accent": '"><script>'}))
    )
    html = await server.client.get("/notes")
    assert "<script>" not in html.text and "data-accent=" not in html.text
    response = await server.client.put("/api/v1/settings", json={"theme": "carta"}, headers=ORIGIN)
    assert response.json()["theme_accent"] is None


@pytest.mark.anyio
async def test_catalog_revision_changes_only_when_restart_discovery_changes(tmp_path, settings):
    config = settings.model_copy(update={"data_dir": tmp_path})
    async with harness(tmp_path, config, catalog=[]) as host:
        original = host.plugins_rev
    async with harness(tmp_path, config, catalog=[]) as host:
        assert host.plugins_rev == original
    pack(tmp_path)
    async with harness(tmp_path, config, catalog=[]) as host:
        installed = host.plugins_rev
        assert installed == original + 1
    async with harness(tmp_path, config, catalog=[]) as host:
        assert host.plugins_rev == installed
    path = tmp_path / "themes/operator-paper/theme.toml"
    path.write_text(path.read_text().replace("#F8F5F0", "#F7F4EF"))
    async with harness(tmp_path, config, catalog=[]) as host:
        assert host.plugins_rev == installed + 1
