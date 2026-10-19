# Real MCP client verification

Run from the repository root with an already authenticated Codex CLI:

```sh
uv run --project server python scripts/mcp-client-smoke.py
```

The script starts the current package on an ephemeral port, uses loopback HTTP for owner login and
token creation, and configures Codex only for this invocation. It runs in an empty temporary workspace
with `--ephemeral`, `--ignore-user-config`, a read-only sandbox and a token supplied through
`SPUNTO_TOKEN`. It never writes global Codex configuration. All notes and credentials are synthetic.

In one real model-driven session, Codex creates one note, searches for its unique marker, reads it
back and confirms its complete body. The script independently verifies SQLite's note count, exact
body, source and token provenance, plus the server's create/search/get call counts. It refuses success
if server logs contain the synthetic note marker or credentials. The CLI has a 180-second timeout;
the server, client process and synthetic database are cleaned up on every outcome.

Redacted full CLI/server logs and a summary live in an owner-private `spunto-client-e2e-*` directory in
the operating system's temporary folder. The script prints its complete path. It needs permission to
bind a local port and for the authenticated CLI to reach its model backend. The packaged server's
normal container listener is used; all test client requests address `127.0.0.1`.

## Local result, 2026-10-13

Codex CLI 0.162.1, authenticated with ChatGPT, passed against the final M1 package over actual HTTP.
One synthetic note was captured and recalled in one session; the database recorded `source_kind`
and `origin` as `agent`, the configured token name as the source, and token client `codex`.

The server recorded one call each to `create_note`, `search_notes` and `get_notes` (2 ms, 1 ms and
0 ms respectively); the session completed in about 17 seconds. The server logs contained no note
content or credentials, and the test processes and database were removed. The original run's retained
evidence is `/tmp/spunto-client-e2e/20261013T203106Z-16415/results.json` with sibling redacted logs.

Claude Code's model-driven capture/recall check remains pending: its standalone CLI is not logged in.
No login was attempted. Protocol negotiation, scoped tool discovery and calls for its newer MCP
protocol are covered by the server tests; that does not substitute for the real logged-in client
acceptance check.

## Current-package local result, 2026-10-15

One fresh run passed against the current local server package using the existing authenticated
Codex CLI. Runtime versions were Spunto 0.1.0, Python 3.14.8, MCP 2.3.0 and Codex CLI 0.162.1
(uv 0.13.1). The exact command was:

```sh
uv run --locked --project server python scripts/mcp-client-smoke.py --timeout 180
```

The single model-driven session completed exactly one `create_note`, one `search_notes` and one
`get_notes` call over real loopback HTTP, with no CLI errors or shell commands. The independent
database check found exactly one complete synthetic note with the original body, `source_kind`
and `origin` both `agent`, the temporary token name as its source and token client `codex`.
Codex confirmed the exact saved body, and the helper exited 0.

The disposable server used a synthetic owner and scoped token. Redacted CLI/server logs are retained;
server logs contained no note content or credentials. The temporary server, client process and
synthetic database were cleaned up. The 144-file server source manifest, helper and dependency
metadata were unchanged across the run. This is local MCP integration evidence; deployment and
physical-phone acceptance remain separate.

The receipt is `/tmp/spunto-local-delivery-7/mcp-current/receipt.json`, with sibling `results.json`,
`codex.jsonl`, `codex.log`, `final.txt`, `server.log` and `runner.log`. The sibling
`provenance-before.json` records the runtime versions and SHA-256 bindings for all server source
files, the helper, dependency metadata and CLI executable.

The Codex helper did not invoke Claude Code or probe its authentication state; its
`claude_model_check` field is fixed text. A separate same-day preflight checked Claude Code
2.1.285 with `claude auth status --json`: it reported not authenticated (exit 1). The sanitized
receipt is `/tmp/spunto-local-delivery-7/claude-preflight.json`; it records no account identifiers
or credentials. No login, account change or Claude model session was attempted, so that client's
model-driven acceptance remains pending.
