#!/usr/bin/env python3
"""Benchmark the M1 server locally, without sockets, providers or persistent user data.

Run from the repository root: uv run --project server python scripts/benchmark-server.py
Raw timings go in a secure temporary report directory; the generated database is deleted.
"""

import argparse
import asyncio
import hashlib
import json
import logging
import math
import os
import platform
import secrets
import sqlite3
import statistics
import tempfile
import time
import uuid
from collections import Counter
from pathlib import Path

import httpx

from spunto.auth import tokens
from spunto.auth.tokens import Principal
from spunto.config import Settings
from spunto.core.notes import Notes
from spunto.core.text import fold, labels, title_of
from spunto.main import create_app, startup

PUBLIC_URL = "http://benchmark.local"
KEYWORD_TARGET_MS = 100
MCP_TARGET_MS = 300
ANSWER_TARGET_CHARS = 7000
QUERIES = {
    "common": "idea",
    "rare": "ornitorinco",
    "prefix": "pro",
    "accent": "perché",
    "phrase": '"offline search"',
    "label": "idea #project/spunto",
    "empty_recent": "",
}


def corpus_row(index: int) -> str:
    project = ("spunto", "home", "work")[index % 3]
    first = f"Idea {index}: progettare una piccola app #idea #project/{project}"
    rare = " Un ornitorinco raro." if index % 997 == 0 else ""
    paragraph = (
        "Perché non provare offline search prima del deploy? CITTÀ, Straße e ﬁnestra: "
        "parole incollate dal web. Ricordare l’idea e parlarne lunedì con Marco. "
    )
    body = first + "\n" + paragraph + rare
    return body + "\n" + ("Review the project and write another idea. " * 12)[: 500 - len(body) - 1]


async def seed(store, count: int) -> dict:
    """Batch seeding is benchmark setup; measured calls use the actual production services."""
    known_labels: dict[str, int] = {}
    label_counts: Counter[str] = Counter()
    body_bytes = 0
    epoch = 1_760_000_000_000
    for start in range(0, count, 500):
        notes, revisions, memberships, indexed = [], [], [], []
        for index in range(start, min(start + 500, count)):
            rowid = index + 1
            stamp = epoch + index * 60_000
            note_id = str(uuid.UUID(int=(stamp << 80) | (7 << 76) | (2 << 62) | rowid))
            body = corpus_row(index)
            title = title_of(body)
            body_bytes += len(body.encode())
            notes.append(
                (
                    rowid,
                    note_id,
                    body,
                    title,
                    hashlib.sha256(body.encode()).hexdigest(),
                    rowid,
                    stamp,
                    stamp,
                )
            )
            revisions.append((note_id, body, stamp))
            indexed.append((rowid, fold(title), fold(body)))
            for token in labels(body):
                label_id = known_labels.setdefault(token.key, len(known_labels) + 1)
                memberships.append((note_id, label_id))
                label_counts[token.key] += 1
        async with store.write() as connection:
            await connection.executemany(
                "INSERT OR IGNORE INTO label(id,key,path,created_at) VALUES (?,?,?,?)",
                [(identifier, key, key, epoch) for key, identifier in known_labels.items()],
            )
            await connection.executemany(
                "INSERT INTO note(rowid,id,body,title,body_hash,version,change_seq,created_at,"
                "updated_at,source_kind,origin,created_by,updated_by)"
                " VALUES (?,?,?,?,?,1,?,?,?,'api','owner','benchmark','benchmark')",
                notes,
            )
            await connection.executemany(
                "INSERT INTO note_revision VALUES (?,1,?,'create','benchmark',?)",
                revisions,
            )
            await connection.executemany("INSERT INTO note_label VALUES (?,?)", memberships)
            await connection.executemany(
                "INSERT INTO note_fts(rowid,title,body,extra) VALUES (?,?,?,'')",
                indexed,
            )
            await connection.execute(
                "UPDATE meta SET value=? WHERE key='change_seq'",
                (str(start + len(notes)),),
            )
    async with store.read() as connection:
        counts = (
            await connection.execute_fetchall(
                "SELECT (SELECT count(*) FROM note), (SELECT count(*) FROM note_revision),"
                " (SELECT count(*) FROM note_fts), (SELECT count(*) FROM note_label),"
                " (SELECT value FROM meta WHERE key='change_seq')"
            )
        )[0]
        if list(counts) != [count, count, count, count * 2, str(count)]:
            raise RuntimeError("The generated note, revision, label and FTS counts disagree.")
        rare_count = (
            await connection.execute_fetchall(
                "SELECT count(*) FROM note_fts WHERE note_fts MATCH ?",
                ('"ornitorinco"',),
            )
        )[0][0]
        if rare_count != math.ceil(count / 997):
            raise RuntimeError("The rare-word fixture count does not match the generator.")
        project_count = (
            await connection.execute_fetchall(
                "SELECT count(*) FROM note_label nl JOIN label l ON l.id=nl.label_id"
                " WHERE l.key='project/spunto'"
            )
        )[0][0]
        if project_count != math.ceil(count / 3):
            raise RuntimeError("The nested-label fixture count does not match the generator.")
    return {
        "notes": count,
        "average_body_utf8_bytes": round(body_bytes / count, 1),
        "characters_per_body": 500,
        "rare_matches": rare_count,
        "label_matches": project_count,
        "label_counts": dict(label_counts),
    }


def timings(values: list[float]) -> dict:
    ordered = sorted(values)
    return {
        "p50_ms": round(statistics.median(ordered), 3),
        "p95_ms": round(ordered[math.ceil(len(ordered) * 0.95) - 1], 3),
        "max_ms": round(max(ordered), 3),
        "raw_ms": [round(value, 3) for value in values],
    }


async def run(count: int, samples: int) -> dict:
    result = {
        "host": {
            "system": platform.system(),
            "release": platform.release(),
            "machine": platform.machine(),
            "python": platform.python_version(),
            "sqlite": sqlite3.sqlite_version,
            "load_average_start": os.getloadavg(),
        },
        "measurement": "Native host; real SQLite; in-process HTTP ASGI, no socket/TLS latency",
        "ai_configured": False,
        "samples_per_query": samples,
        "warmup_calls_per_query": 3,
        "mcp_protocol": "2025-06-18",
        "mcp_credentials": "One read PAT per query group; normal production limits remain active",
        "queries": {},
    }
    with tempfile.TemporaryDirectory(prefix="spunto-benchmark-") as temporary:
        root = Path(temporary)
        settings = Settings(
            public_url=PUBLIC_URL,
            data_dir=root,
            backup_dir=root / "backups",
            attachments_dir=root / "attachments",
            owner_password=secrets.token_urlsafe(24),
            owner_password_file=None,
            owner_password_hash=None,
            owner_password_hash_file=None,
            mcp_enabled=True,
        )
        async with startup(settings) as services:
            started = time.perf_counter()
            result["corpus"] = await seed(services.store, count)
            result["seed_seconds"] = round(time.perf_counter() - started, 3)
            print(f"Seeded {count} notes in {result['seed_seconds']} s", flush=True)
            app = create_app(services)
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app), base_url=PUBLIC_URL
            ) as client:
                for name, query in QUERIES.items():
                    credential_id, token = await tokens.create(
                        services.store,
                        name=f"Benchmark {name}",
                        client="script",
                        scope="read",
                    )
                    notes = Notes(
                        services.store,
                        Principal(credential_id, "pat", "Benchmark", "read"),
                    )
                    keyword, mcp = [], []
                    answer_chars, answer_sizes, http_sizes = [], [], []
                    headers = {
                        "Authorization": "Bearer " + token,
                        "Accept": "application/json, text/event-stream",
                        "MCP-Protocol-Version": "2025-06-18",
                    }
                    for iteration in range(samples + 3):
                        started = time.perf_counter()
                        found = await notes.search(query, limit=50)
                        keyword_elapsed = (time.perf_counter() - started) * 1000
                        if not found["hits"] or found["semantic"] != "off":
                            raise RuntimeError(f"The {name} query did not return keyword hits.")
                        started = time.perf_counter()
                        response = await client.post(
                            "/mcp",
                            headers=headers,
                            json={
                                "jsonrpc": "2.0",
                                "id": iteration + 1,
                                "method": "tools/call",
                                "params": {
                                    "name": "search_notes",
                                    "arguments": {"query": query, "mode": "keyword"},
                                },
                            },
                        )
                        mcp_elapsed = (time.perf_counter() - started) * 1000
                        if response.status_code != 200:
                            raise RuntimeError(
                                f"MCP {name} query returned HTTP {response.status_code}."
                            )
                        answer = response.json().get("result", {})
                        if answer.get("isError") or not answer.get("content"):
                            raise RuntimeError(f"MCP {name} query returned an error.")
                        payload = answer["content"][0]["text"]
                        if not json.loads(payload).get("hits"):
                            raise RuntimeError(f"MCP {name} query did not return hits.")
                        if iteration >= 3:
                            keyword.append(keyword_elapsed)
                            mcp.append(mcp_elapsed)
                            answer_chars.append(len(payload))
                            answer_sizes.append(len(payload.encode()))
                            http_sizes.append(len(response.content))
                    result["queries"][name] = {
                        "query": query,
                        "keyword": timings(keyword),
                        "mcp_http": timings(mcp),
                        "max_answer_chars": max(answer_chars),
                        "max_answer_utf8_bytes": max(answer_sizes),
                        "max_http_response_bytes": max(http_sizes),
                    }
                    print(
                        f"{name}: keyword p95 {timings(keyword)['p95_ms']:.1f} ms; "
                        f"MCP ASGI p95 {timings(mcp)['p95_ms']:.1f} ms; "
                        f"answer {max(answer_chars)} chars / {max(answer_sizes)} UTF-8 bytes",
                        flush=True,
                    )
    result["host"]["load_average_end"] = os.getloadavg()
    result["targets"] = {
        "keyword_p95_ms": KEYWORD_TARGET_MS,
        "mcp_p95_ms": MCP_TARGET_MS,
        "default_answer_characters": ANSWER_TARGET_CHARS,
    }
    result["passed"] = all(
        query["keyword"]["p95_ms"] <= KEYWORD_TARGET_MS
        and query["mcp_http"]["p95_ms"] <= MCP_TARGET_MS
        and query["max_answer_chars"] <= ANSWER_TARGET_CHARS
        for query in result["queries"].values()
    )
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--notes", type=int, default=50_000)
    parser.add_argument("--samples", type=int, default=20)
    args = parser.parse_args()
    if args.notes < 50_000 or not 10 <= args.samples <= 50:
        parser.error("Use at least 50000 notes and 10 to 50 samples per query.")
    logging.getLogger("spunto").setLevel(logging.ERROR)
    logging.getLogger("mcp").setLevel(logging.ERROR)
    output = Path(tempfile.mkdtemp(prefix="spunto-perf-"))
    result = asyncio.run(run(args.notes, args.samples))
    (output / "results.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f"{'PASS' if result['passed'] else 'FAIL'}; full results: {output / 'results.json'}")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
