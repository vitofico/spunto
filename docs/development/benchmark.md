# M1 server benchmark

Run from the repository root:

```sh
uv run --project server python scripts/benchmark-server.py
```

The script creates an isolated temporary instance with no AI provider, seeds 50,000 deterministic
notes of 500 characters each, and deletes the database afterward. Batch SQL is used only for setup;
timed queries call the production `Notes.search` service and the authenticated `/mcp` endpoint through
HTTPX's ASGI transport. No user data or configuration is modified. Normal MCP rate limits stay active,
with one read token per query group.

Each query has three warm-up calls and twenty timed calls. The script checks note/revision/FTS counts,
derived label membership, 51 rare-word matches and 16,667 `project/spunto` matches before timing. It
exits nonzero if any keyword p95 exceeds 100 ms, MCP p95 exceeds 300 ms, or default answer text exceeds
7,000 characters (the spec's conservative 2,000-token estimate). UTF-8 answer bytes and complete HTTP
response bytes are reported separately. Raw timings, corpus facts and host details go into a secure
`spunto-perf-*` directory in the operating system's temporary folder. The script prints the full
`results.json` path; retain that file when comparing runs.

## Local result, 2026-10-13

Native macOS arm64, Python 3.14.8, SQLite 3.54.0; 50,000 notes, 508 UTF-8 bytes per body on average.
The run used the production filters, weighted FTS5 ranking, note serialization and bearer auth.

| Query | Keyword p95, ms | MCP over ASGI p95, ms |
|---|---:|---:|
| Common word | 30.3 | 29.1 |
| Rare word | 2.9 | 2.1 |
| Prefix | 38.0 | 37.7 |
| Accent | 24.8 | 24.4 |
| Quoted phrase | 27.3 | 26.9 |
| Nested label filter | 29.8 | 29.7 |
| Empty query, recent notes | 19.6 | 18.7 |

The largest default answer was 6,981 characters / 7,061 UTF-8 bytes; its complete HTTP response was
7,521 bytes. All configured targets passed. The original query, which sorted full note bodies with
`bm25()` in the projection, took 135–153 ms at p95 on broad matches. Selecting candidate IDs through
FTS5's rank ordering before fetching the returned notes removed that cost while retaining filters
and stable cursor candidates.

These are local service/ASGI measurements: no socket, reverse proxy, TLS or network latency is
included. They do not measure the deployment node or Android reference hardware. Re-run on the
deployment runtime before making claims about those environments.
