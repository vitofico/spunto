"""CLI removal of retained vectors with AI off uses a real stopped database."""

import json
from contextlib import closing

from spunto import cli
from spunto.ai.profiles import Profile
from spunto.ai.vectors import fingerprint, table_name
from spunto.store.db import DB_NAME, connect
from spunto.store.migrations import migrate


def test_cli_ai_reindex_drop_removes_retained_index_with_no_provider(
    env, settings, monkeypatch, capsys
):
    monkeypatch.setattr(cli.logs, "configure", lambda *_: None)
    settings.data_dir.mkdir(parents=True)
    path = settings.data_dir / DB_NAME
    migrate(path, settings.backup_dir)
    profile = Profile(name="retained", base_url="http://127.0.0.1/v1", embed_model="fixture")
    key, descriptor = fingerprint(profile, 3)
    with closing(connect(path)) as conn:
        conn.execute(
            "INSERT INTO embedding_model VALUES (?,?,?,?,0)", (key, profile.name, descriptor, 3)
        )
        conn.execute("INSERT INTO embedding_route VALUES (?,?,?)", (profile.name, key, key))
        conn.execute(f"CREATE VIRTUAL TABLE {table_name(key)} USING vec0(embedding float[3])")  # noqa: S608
        conn.execute("INSERT INTO meta(key,value) VALUES('active_embedding_index',?)", (key,))
    assert cli.main(["ai", "reindex", "--drop"]) == 0
    output = json.loads(capsys.readouterr().out)
    assert not output["enabled"] and output["routes"] == []
    with closing(connect(path)) as conn:
        assert not conn.execute("SELECT 1 FROM embedding_model").fetchall()
        assert not conn.execute("SELECT 1 FROM embedding_route").fetchall()
        assert not conn.execute("SELECT 1 FROM meta WHERE key='active_embedding_index'").fetchall()
        assert not conn.execute(
            "SELECT 1 FROM sqlite_master WHERE name=?", (table_name(key),)
        ).fetchall()
