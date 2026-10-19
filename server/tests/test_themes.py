"""Colour-only theme safety, shared catalog and durable owner/device selection."""

import copy
import json
from pathlib import Path
from urllib.parse import quote

import pytest
from conftest import PUBLIC_URL

from spunto.api.plugins import Preferences
from spunto.host.themes import contrast, theme_catalog, theme_errors, theme_ids, theme_manifest


def test_all_five_themes_match_source_and_pass_unrounded_contrast():
    source = json.loads((Path(__file__).parents[2] / "web/tokens.json").read_text())
    assert theme_ids() == ("carta", "ardesia", "salvia", "corallo", "elettrica")
    assert theme_catalog() == source["catalog"]
    for theme in theme_ids():
        assert theme_errors(source["themes"][theme]) == []
        assert theme_manifest(theme) == {"id": theme, "tokens": 1, **source["themes"][theme]}
    assert contrast("#000000", "#FFFFFF") == 21
    assert contrast("#512D94", "#512D94") == 1
    theme_schema = Preferences.model_json_schema()["properties"]["theme"]
    assert "enum" not in theme_schema
    assert theme_schema["anyOf"] == [
        {"maxLength": 24, "minLength": 2, "type": "string"},
        {"type": "null"},
    ]


def test_invalid_contrast_and_executable_values_are_refused_with_pair_names():
    broken = copy.deepcopy(theme_manifest("corallo"))
    broken["dark"]["ink-3"] = broken["dark"]["sunken"]
    assert any("ink-3 on sunken is 1.00:1 in dark" in error for error in theme_errors(broken))
    broken["light"]["accent"] = "url(https://example.invalid/font)"
    assert "Missing or invalid colour roles in light" in theme_errors(broken)
    assert len(theme_errors({})) == 2
    secondary = copy.deepcopy(theme_manifest("elettrica"))
    secondary["light"]["ink-2"] = secondary["light"]["paper"]
    assert any("ink-2 on paper is 1.00:1 in light" in error for error in theme_errors(secondary))


@pytest.mark.anyio
async def test_vivid_instance_selection_and_cookie_override_survive_fresh_html(server, tmp_path):
    web = tmp_path / "web"
    web.mkdir()
    (web / "index.html").write_text(
        "<!doctype html><html><body>Synthetic theme fixture</body></html>"
    )
    server.services.settings.web_dir = web
    assert (
        await server.client.post(
            "/api/v1/setup",
            json={"code": server.services.setup_code, "password": "correct horse battery"},
        )
    ).status_code == 204
    for theme in ("corallo", "elettrica"):
        changed = await server.client.put(
            "/api/v1/settings", json={"theme": theme}, headers={"Origin": PUBLIC_URL}
        )
        assert changed.status_code == 200 and changed.json()["theme"] == theme
        assert (await server.client.get("/api/v1/settings")).json()["theme"] == theme
        assert f'data-theme="{theme}"' in (await server.client.get("/")).text
    cookie = quote(json.dumps({"theme": "corallo", "mode": "dark"}))
    assert (
        'data-theme="corallo"'
        in (await server.client.get("/", headers={"Cookie": "theme=" + cookie})).text
    )
    assert (await server.client.get("/api/v1/settings")).json()["theme"] == "elettrica"
    assert 'data-theme="elettrica"' in (await server.client.get("/")).text
    assert (
        await server.client.put(
            "/api/v1/settings", json={"theme": "untrusted-theme"}, headers={"Origin": PUBLIC_URL}
        )
    ).status_code == 422
