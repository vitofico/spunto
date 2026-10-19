"""Operator discovery and sample contracts, using the actual SQLite host."""

import shutil
from pathlib import Path
from types import SimpleNamespace

import pytest

from spunto.cli import main
from spunto.host.loader import check_folder, discover
from spunto.host.manifest import ManifestError
from spunto.host.testing import harness

SAMPLE = Path(__file__).resolve().parents[2] / "examples/plugins/word-count"


def install(tmp_path, *, name="sample", changes=None, code=None):
    folder = tmp_path / "plugins" / name
    shutil.copytree(SAMPLE, folder)
    if changes:
        path = folder / "plugin.toml"
        body = path.read_text()
        for before, after in changes.items():
            body = body.replace(before, after)
        path.write_text(body)
    if code is not None:
        (folder / "plugin.py").write_text(code)
    return folder


def test_check_does_not_execute_or_require_configuration(tmp_path, monkeypatch, capsys):
    marker = tmp_path / "must-not-exist"
    folder = install(tmp_path, code=f"raise RuntimeError({str(marker)!r})\n")
    monkeypatch.delenv("SPUNTO_PUBLIC_URL", raising=False)
    assert check_folder(folder)["api"] == "1.0"
    assert main(["plugin", "check", str(folder)]) == 0
    assert "valid API 1.0" in capsys.readouterr().out
    assert not marker.exists()


@pytest.mark.parametrize("variable", ["SPUNTO_PLUGINS_DIR", "SPUNTO_THEMES_DIR"])
def test_operator_directory_configuration_names_invalid_file(env, tmp_path, variable):
    from spunto.config import load_settings
    from spunto.errors import OperatorError

    path = tmp_path / "not-a-directory"
    path.write_text("synthetic")
    env(**{variable: str(path)})
    with pytest.raises(OperatorError, match=variable):
        load_settings()


@pytest.mark.parametrize(
    "changes,code",
    [
        ({'api = "1.0"': 'api = "9.0"'}, None),
        ({'entry = "plugin:plugin"': 'entry = "../outside:plugin"'}, None),
        ({'entry = "plugin:plugin"': 'entry = "missing:plugin"'}, None),
        (None, "def broken(\n"),
    ],
)
def test_static_rejects_invalid_contracts(tmp_path, changes, code):
    folder = install(tmp_path, changes=changes, code=code)
    with pytest.raises(ManifestError):
        check_folder(folder)


def test_discovery_is_lazy_isolates_failures_and_cannot_shadow_builtin(tmp_path, settings):
    install(tmp_path, name="good", code="raise RuntimeError('must not import during discovery')")
    install(tmp_path, name="collision", changes={'id = "word-count"': 'id = "tasks"'})
    install(tmp_path, name="invalid", changes={'api = "1.0"': 'api = "9.0"'})
    install(tmp_path, name="missing-id", changes={'id = "word-count"': ""})
    manifests, bindings, failures = discover(settings.model_copy(update={"data_dir": tmp_path}))
    assert len({m["id"] for m in manifests}) == len(manifests)
    assert next(m for m in manifests if m["id"] == "tasks")["entry"].startswith("spunto.plugins.")
    assert "word-count" in bindings
    assert len(failures) == 3
    assert any("9.0" in reason and "1.0" in reason for reason in failures.values())
    for item in manifests:
        if item["id"] in failures:
            assert item["permissions"] == []
            assert "entry" not in item and "settings" not in item and "contributes" not in item


@pytest.mark.anyio
async def test_installed_entrypoint_sidecar_is_lazy_and_enabled_through_normal_host(
    tmp_path, settings, monkeypatch
):
    import importlib.metadata

    package = tmp_path / "installed_sample"
    package.mkdir()
    marker = tmp_path / "imported"
    (package / "__init__.py").write_text(
        "from pathlib import Path\n"
        "from spunto.host import Plugin\n"
        f"Path({str(marker)!r}).touch()\n"
        "plugin = Plugin('installed-sample')\n"
    )
    (package / "plugin.toml").write_text(
        'id="installed-sample"\nname="Installed sample"\nversion="1"\napi="1.0"\n'
        'kind="server"\ndescription="Synthetic installed package"\n'
        'permissions=[]\nentry="installed_sample:plugin"\n'
    )
    distribution = SimpleNamespace(
        name="synthetic-distribution", locate_file=lambda p: tmp_path / p
    )
    entry = SimpleNamespace(
        name="installed-sample", value="installed_sample:plugin", dist=distribution
    )
    monkeypatch.setattr(importlib.metadata, "entry_points", lambda **kwargs: [entry])
    monkeypatch.syspath_prepend(str(tmp_path))
    async with harness(tmp_path, settings.model_copy(update={"data_dir": tmp_path})) as host:
        assert not marker.exists()
        await host.enable("installed-sample", [])
        assert marker.exists()
        assert host.states["installed-sample"]["state"] == "enabled"


@pytest.mark.anyio
async def test_operator_load_error_does_not_break_startup(tmp_path, settings):
    install(tmp_path, code="raise RuntimeError('synthetic import failure')")
    async with harness(tmp_path, settings.model_copy(update={"data_dir": tmp_path})) as host:
        assert host.states["tasks"]["state"] in {"available", "enabled"}
        await host.enable("word-count", permissions=["notes.read", "annotate"])
        assert host.states["word-count"]["state"] == "failed"
        assert host.states["word-count"]["last_error"] == "plugin_load_failed"


@pytest.mark.anyio
async def test_sample_relative_import_storage_disable_and_reset(tmp_path, settings):
    from spunto.core.notes import Notes
    from spunto.host import Ctx
    from spunto.host.vocabulary import validate_output

    folder = install(tmp_path)
    (folder / "helper.py").write_text("MARKER = 'relative helper'\n")
    path = folder / "plugin.py"
    path.write_text("from .helper import MARKER\n" + path.read_text())
    async with harness(tmp_path, settings.model_copy(update={"data_dir": tmp_path})) as host:
        # The generic harness supplies its core note factory explicitly here.
        from spunto.core.plugin_notes import PluginNotes

        host.notes_factory = lambda ctx: PluginNotes(ctx)
        await host.enable("word-count", permissions=["notes.read", "annotate"])
        assert host.states["word-count"]["state"] == "enabled"
        from spunto.auth.tokens import Principal

        notes = Notes(host.store, Principal("owner", "session", "Owner", "full", private_ok=True))
        note = await notes.create("Three synthetic words")
        ctx = Ctx(host, "word-count")
        handler = host.plugins["word-count"].handlers["hook"]["note.saved"]
        await handler(ctx, note["id"], note["version"])
        async with host.store.read() as conn:
            rows = await conn.execute_fetchall(
                "SELECT payload FROM annotation WHERE plugin_id=?", ("word-count",)
            )
        import json

        payload = json.loads(rows[0][0])
        validate_output(payload)
        assert payload == [{"type": "text", "text": "3 words"}]
        await host.disable("word-count")
        with pytest.raises(PermissionError):
            await handler(ctx, note["id"], note["version"])
        async with host.store.read() as conn:
            assert await conn.execute_fetchall(
                "SELECT 1 FROM annotation WHERE plugin_id='word-count'"
            )
        await host.delete_data("word-count")
        async with host.store.read() as conn:
            assert not await conn.execute_fetchall(
                "SELECT 1 FROM annotation WHERE plugin_id='word-count'"
            )
