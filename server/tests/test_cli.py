import io
import sys
from contextlib import closing

import pytest
from argon2 import PasswordHasher

from spunto import cli
from spunto.jobs.backup import PRE_MIGRATION
from spunto.store.db import DB_NAME, connect
from spunto.store.lock import DataLock
from spunto.store.migrations import migrate


@pytest.fixture(autouse=True)
def leave_logging_alone(monkeypatch):
    # Pytest owns the root logger during tests.
    monkeypatch.setattr(cli.logs, "configure", lambda level, fmt: None)


@pytest.fixture
def stdin(monkeypatch):
    def feed(text: str) -> None:
        monkeypatch.setattr(sys, "stdin", io.StringIO(text))

    return feed


@pytest.fixture
def database(settings):
    settings.data_dir.mkdir(parents=True)
    migrate(settings.data_dir / DB_NAME, settings.backup_dir / PRE_MIGRATION)
    return settings.data_dir / DB_NAME


def test_bad_settings_stop_with_one_sentence(env, capsys):
    env(SPUNTO_PUBLIC_URL="spunto.example.org")
    assert cli.main(["serve"]) == 1
    assert capsys.readouterr().err == (
        "spunto: SPUNTO_PUBLIC_URL must start with https:// or http://, "
        "e.g. https://spunto.example.org.\n"
    )


def test_serve_refuses_a_data_directory_in_use(env, data_dir, capsys):
    with DataLock(data_dir):
        assert cli.main(["serve"]) == 1
    assert "Another Spunto process is using" in capsys.readouterr().err


def test_backup_and_restore(env, database, capsys):
    assert cli.main(["backup"]) == 0
    snapshot = capsys.readouterr().out.strip()
    assert snapshot.endswith(".db")
    assert cli.main(["restore", snapshot]) == 0
    assert "New sync epoch" in capsys.readouterr().out


def test_restore_of_a_missing_file(env, database, capsys):
    assert cli.main(["restore", "/nowhere/spunto.db"]) == 1
    assert "There is no snapshot at /nowhere/spunto.db." in capsys.readouterr().err


def test_set_password_signs_out_the_web(env, database, stdin, capsys):
    with closing(connect(database)) as conn:
        conn.execute(
            "INSERT INTO credential (id, kind, name, scope, created_at) VALUES"
            " ('s1', 'session', 'Firefox', 'full', 0)"
        )
    stdin("correct horse battery\n")
    assert cli.main(["owner", "set-password"]) == 0
    assert "signed out" in capsys.readouterr().out
    with closing(connect(database)) as conn:
        [(stored, source)] = conn.execute("SELECT password_hash, password_source FROM owner")
        [(revoked,)] = conn.execute("SELECT revoked_at FROM credential")
    assert PasswordHasher().verify(stored, "correct horse battery")
    assert source == "ui"
    assert revoked is not None


def test_set_password_defers_to_the_environment(env, database, stdin, capsys):
    env(SPUNTO_OWNER_PASSWORD="correct horse battery")
    stdin("another password\n")
    assert cli.main(["owner", "set-password"]) == 1
    assert "comes from the environment" in capsys.readouterr().err


def test_set_password_rejects_a_short_one(env, database, stdin, capsys):
    stdin("short\n")
    assert cli.main(["owner", "set-password"]) == 1
    assert "at least 8 characters" in capsys.readouterr().err


def test_hash_password_needs_no_settings(monkeypatch, stdin, capsys):
    monkeypatch.delenv("SPUNTO_PUBLIC_URL", raising=False)
    stdin("correct horse battery\n")
    assert cli.main(["owner", "hash-password"]) == 0
    printed = capsys.readouterr().out.strip()
    assert printed.startswith("$argon2id$")
    assert PasswordHasher().verify(printed, "correct horse battery")


def test_hosted_plugin_import_requires_explicit_owner_and_workspace(env, capsys):
    env(
        SPUNTO_DATABASE_URL="postgresql://localhost/spunto",
        SPUNTO_IDENTITY_DATABASE_URL="postgresql://localhost/spunto_identity",
    )
    assert cli.main(["plugins", "run", "markdown-import", "--path", "/synthetic"]) == 1
    assert "require both --user-id and --workspace-id" in capsys.readouterr().err


def test_sqlite_plugin_import_refuses_hosted_identity_flags(env, capsys):
    assert (
        cli.main(
            [
                "plugins",
                "run",
                "markdown-import",
                "--path",
                "/synthetic",
                "--user-id",
                "fixture-owner",
                "--workspace-id",
                "fixture-workspace",
            ]
        )
        == 1
    )
    assert "apply only to hosted PostgreSQL" in capsys.readouterr().err


@pytest.mark.parametrize(
    "arguments",
    [
        ["blinko-import"],
        ["markdown-import", "--url", "https://source.synthetic.invalid"],
        ["markdown-import", "--label-types"],
        ["markdown-import", "--include-trash"],
    ],
)
def test_removed_importer_and_options_are_not_cli_commands(arguments):
    with pytest.raises(SystemExit) as error:
        cli.main(["plugins", "run", *arguments])
    assert error.value.code == 2


def test_markdown_cli_dry_run_apply_and_stopped_service_lock(env, data_dir, tmp_path, capsys):
    source = tmp_path / "markdown"
    source.mkdir()
    (source / "note.md").write_text("Synthetic CLI import #retained")
    arguments = ["plugins", "run", "markdown-import", "--path", str(source)]
    with DataLock(data_dir):
        assert cli.main(arguments) == 1
    assert "Another Spunto process is using" in capsys.readouterr().err
    assert cli.main([*arguments, "--dry-run"]) == 0
    assert '"created": 0' in capsys.readouterr().out
    with closing(connect(data_dir / DB_NAME)) as conn:
        assert conn.execute("SELECT count(*) FROM note").fetchone()[0] == 0
    assert cli.main(arguments) == 0
    assert '"created": 1' in capsys.readouterr().out
    assert cli.main(arguments) == 0
    assert '"unchanged": 1' in capsys.readouterr().out
