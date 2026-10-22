"""Shared SQLite/PostgreSQL serialization contracts with synthetic plugin output."""

import json
from dataclasses import replace

from spunto.auth import tokens
from spunto.core.notes import Notes
from spunto.store.db import now_ms


async def assert_batch_serialization(domain):
    first = await domain.create("Synthetic batch first #First #Second")
    second = await domain.create("Synthetic batch second #Second #First")
    plain = await domain.create("Synthetic batch plain")
    private = await domain.create("Synthetic batch private", private=True)
    agent_id, _ = await tokens.create(
        domain.store, name="Synthetic agent", client="script", scope="write"
    )
    agent = Notes(domain.store, replace(domain.principal, id=agent_id, kind="pat", scope="write"))
    await agent.append(second["id"], "Agent addition")
    now = now_ms()
    async with domain.store.write() as conn:
        for position, plugin_id in enumerate(("batch-first", "batch-later", "batch-disabled")):
            await conn.execute(
                "INSERT INTO plugin(id,version,kind,switch,state,position) "
                "VALUES (?,'1.0.0','server',?,'enabled',?)",
                (plugin_id, "off" if plugin_id == "batch-disabled" else "on", position - 100),
            )
        for note in (first, second):
            for index in range(6):
                await conn.execute(
                    "INSERT INTO suggestion(id,plugin_id,note_id,action,payload,confidence,"
                    "status,change_seq,created_at) VALUES (?,'batch-first',?,?,?,?, 'pending',?,?)",
                    (
                        f"{note['id']}-{index}",
                        note["id"],
                        "open" if index == 0 else "append_text",
                        json.dumps(
                            {"note_id": private["id"]} if index == 0 else {"text": str(index)}
                        ),
                        1 - index / 10,
                        1,
                        now - 15 * 86400000 if index == 5 else now,
                    ),
                )
            for plugin_id in ("batch-first", "batch-later", "batch-disabled"):
                await conn.execute(
                    "INSERT INTO annotation(plugin_id,note_id,kind,payload,change_seq,updated_at) "
                    "VALUES (?,?,'meta',?,1,?)",
                    (plugin_id, note["id"], json.dumps({"type": "text", "text": plugin_id}), now),
                )
            await conn.execute(
                "INSERT INTO annotation(plugin_id,note_id,kind,payload,change_seq,updated_at) "
                "VALUES ('batch-first',?,'panel',?,1,?)",
                (
                    note["id"],
                    json.dumps({"type": "notes", "ids": [plain["id"], private["id"]]}),
                    now,
                ),
            )
    reader = Notes(domain.store, replace(domain.principal, private_ok=False))
    for response in (
        await reader.list(render=True),
        await reader.search("Synthetic batch", mode="keyword", render=True),
        await reader.search("", mode="keyword", render=True),
    ):
        values = response.get("notes", response.get("hits"))
        assert {note["id"] for note in values} == {first["id"], second["id"], plain["id"]}
        by_id = {note["id"]: note for note in values}
        assert by_id[first["id"]]["labels"] == by_id[second["id"]]["labels"] == ["First", "Second"]
        assert not by_id[first["id"]]["updated_via_agent"]
        assert by_id[second["id"]]["updated_via_agent"]
        assert by_id[plain["id"]]["labels"] == []
        assert by_id[plain["id"]]["suggestions"] == by_id[plain["id"]]["annotations"] == []
        for note_id in (first["id"], second["id"]):
            value = by_id[note_id]
            assert [item["payload"] for item in value["suggestions"]] == [
                {"text": str(index)} for index in (1, 2, 3)
            ]
            assert [item["payload"] for item in value["annotations"]] == [
                {"type": "text", "text": "batch-first"},
                {"type": "notes", "ids": [plain["id"]]},
            ]
            assert "<p>Synthetic batch" in value["html"]
