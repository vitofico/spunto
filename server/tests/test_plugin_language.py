"""App language is per request. Saved notes and machine-readable fields keep their identity."""

import asyncio
import uuid
from contextlib import suppress
from string import Formatter

import pytest

from spunto.ai.errors import AIError
from spunto.api.errors import Problem
from spunto.api.plugins import _call
from spunto.host.language import LANGUAGES, _catalog, answer_language, negotiate, use_language
from spunto.plugins.decision_journal import MONTHS, display_date, stamp, summary

ORIGIN = {"Origin": "http://spunto.test:8080"}
PREFIX = "/api/v1/plugins/"


def test_catalog_keys_and_dynamically_formatted_journal_dates():
    required = {
        *MONTHS,
        "Open",
        "Reviewed",
        "Closed",
        "Reopened",
        "Review due {date}",
        "Review on {date}",
        "{day} {month} {year}",
        "{day} {month} {year}, {time} UTC",
        "Outcome · {title}",
        "Reopened · {title}",
    }
    italian = _catalog("it")
    assert required <= italian.keys()
    for code in set(LANGUAGES) - {"en"}:
        catalog = _catalog(code)
        assert catalog.keys() == italian.keys()
        for key, value in catalog.items():
            assert value
            assert sorted(field for _, field, _, _ in Formatter().parse(key) if field) == sorted(
                field for _, field, _, _ in Formatter().parse(value) if field
            ), (code, key)
        with use_language(code):
            assert stamp(1_792_600_320_000) != "21 October 2026, 16:32 UTC"
            assert display_date("2026-10-21") != "2026-10-21"
            for status in ("open", "closed", "reviewed"):
                text = summary({"status": status, "review_date": "2026-10-21"}, 1_792_600_320_000)
                assert text.startswith(catalog[status.capitalize()])


def test_language_preferences_are_bounded_and_explicit_english_guides_answers():
    assert negotiate("nl, fr-CA;q=0.8, es;q=0.9, de;q=0") == "es"
    assert negotiate("fr; q=NaN, en; q=1.1, it;q=0") is None
    assert negotiate("DE-de") == "de"
    assert not answer_language()
    with suppress(RuntimeError), use_language("en"):
        assert "Use English" in answer_language()
        assert "unless the user explicitly requests another language" in answer_language()
        raise RuntimeError
    assert not answer_language()


@pytest.mark.anyio
async def test_parallel_bundled_commands_and_saved_journal_language(server):
    client, host = server.client, server.services.host
    assert (
        await client.post(
            "/api/v1/setup",
            json={"code": server.services.setup_code, "password": "correct horse battery"},
        )
    ).status_code == 204
    for plugin in ("decision-journal", "library-stats"):
        response = await client.post(
            f"{PREFIX}{plugin}/enable",
            json={"permissions": host.manifests[plugin]["permissions"]},
            headers=ORIGIN,
        )
        assert response.status_code == 200, response.text

    async def command(plugin, name, code, args=None):
        response = await client.post(
            f"{PREFIX}{plugin}/commands/{name}",
            headers={**ORIGIN, "Accept-Language": code},
            json=args or {},
        )
        assert response.status_code == 200, response.text
        assert response.headers.get("Vary") == "Accept-Language"
        return response.headers.get("Content-Language"), response.json()

    async def prompt(locale):
        code, result = await command("decision-journal", "record-decision", locale)
        return code, result["schema"]["properties"]["decision"]["title"]

    assert await asyncio.gather(*(prompt(code) for code in ["fr-CA", "it", "de", "es", "nl"])) == [
        ("fr", "Qu’avez-vous décidé ?"),
        ("it", "Che cosa hai deciso?"),
        ("de", "Was hast du entschieden?"),
        ("es", "¿Qué decidiste?"),
        ("en", "What did you decide?"),
    ]
    capture = {
        "title": "Private",
        "decision": "What did you decide?",
        "rationale": "User-authored reasoning.",
        "review_on": "2030-10-21",
        "request_id": str(uuid.uuid7()),
    }
    _, saved = await command("decision-journal", "record-decision", "it-IT", capture)
    assert (await command("decision-journal", "record-decision", "de", capture))[1] == saved
    [root] = saved["ids"]
    body = (await client.get(f"/api/v1/notes/{root}")).json()["body"]
    assert body.startswith("# Private")
    assert "## Decisione\nWhat did you decide?" in body
    assert "## Motivazione\nUser-authored reasoning." in body
    assert "Prima revisione: 21 ottobre 2030." in body

    _, form = await command("decision-journal", "review-decision", "es", {"note_id": root})
    assert form["schema"]["properties"]["status"]["enum"] == ["open", "reviewed", "closed"]
    assert form["schema"]["properties"]["status"]["title"] == "Después de esta revisión"
    review = {
        "note_id": root,
        "base_version": 1,
        "status": "reviewed",
        "outcome": "User-authored outcome.",
        "request_id": str(uuid.uuid7()),
    }
    _, saved_review = await command("decision-journal", "review-decision", "fr", review)
    outcome_id = saved_review["ids"][0]
    outcome = (await client.get(f"/api/v1/notes/{outcome_id}")).json()["body"]
    assert outcome.startswith("# Résultat · Private\n")
    assert "## Résultat\nUser-authored outcome." in outcome
    _, german = await command("decision-journal", "journal", "de", {"note_id": root})
    assert german["items"][0]["text"] == "Überprüft"
    _, stats = await command("library-stats", "summary", "es")
    assert stats["items"][0]["text"].startswith("Notas guardadas: 2 · Activas: 2")


@pytest.mark.anyio
async def test_localized_timeout_and_disabled_plugin(server):
    async def timeout():
        raise AIError("unavailable", "ai_provider_timeout")

    with use_language("fr"), pytest.raises(Problem) as error:
        await _call(timeout())
    assert error.value.code == "ai_provider_timeout"
    assert "Réglages → IA" in error.value.detail
    client = server.client
    assert (
        await client.post(
            "/api/v1/setup",
            json={"code": server.services.setup_code, "password": "correct horse battery"},
        )
    ).status_code == 204
    response = await client.post(
        f"{PREFIX}decision-journal/commands/journal",
        headers={**ORIGIN, "Accept-Language": "it"},
        json={},
    )
    assert response.status_code == 404
    assert response.headers["Content-Language"] == "it"
    assert response.json()["detail"] == "Il plugin non è disponibile."
