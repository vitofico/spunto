# M0 server spikes

Run on 2026-10-13 against the approved design (02 §16, M0 "Skeleton and spikes"). Three questions had to be answered before M1 starts: does `sqlite-vec` load in the real base image on both architectures, can the official Python MCP SDK serve a per-token, per-plugin tool list over stateless HTTP to the two agents the owner uses, and can a plugin be switched on and off without a restart. The spike code lives in `spikes/server/` (gitignored); this file keeps the commands, the trimmed outputs and the verdicts.

| Spike | Verdict |
|---|---|
| 1. `sqlite-vec` in the image, amd64 and arm64 | **Works** |
| 2. MCP over stateless streamable HTTP in FastAPI, bearer auth, per-token and per-plugin tool lists | **Works with caveats**: Codex proven end to end; Claude Code proven at the protocol level only, because the standalone `claude` CLI on this Mac is not logged in |
| 3. Toy plugin enabled and disabled at runtime | **Works with caveats**: worker threads cannot be cancelled, and resumed jobs overlap the re-enable backfill |

The short version: the stack in 02 holds. The surprise is that the MCP SDK is now 2.x and speaks two protocol generations at once, and Claude Code already uses the newer one; nothing in the design breaks, but §1.4 and §8 need a few lines updated (listed at the end).

## Environment

- Host: macOS on Apple silicon (arm64). Docker 29.8.2 (Docker Desktop, containerd image store), buildx 0.37.2. amd64 runs under Docker Desktop's emulation.
- Python 3.14.8 (uv 0.13.0). `mcp` 2.3.0 with `mcp-types` 2.3.0, `fastapi` 0.143.0, `starlette` 1.8.0, `uvicorn` 0.54.0, `pydantic` 2.14.1.
- `sqlite-vec` 0.1.9 (the latest stable; 0.1.10 is in alpha), `aiosqlite` 0.22.1.
- Clients: Claude Code 2.1.285, codex-cli 0.162.1 (logged in with ChatGPT).

---

## 1. `sqlite-vec` in the image on amd64 and arm64

**Setup.** A throwaway image on the base 02 §1.4 and §10.1 name, installing from a lockfile with uv and running as UID 10001:

```dockerfile
FROM python:3.14-slim
COPY --from=ghcr.io/astral-sh/uv:0.13.0 /uv /usr/local/bin/uv
WORKDIR /spike
COPY pyproject.toml uv.lock ./        # aiosqlite==0.22.1, sqlite-vec==0.1.9
RUN uv sync --frozen --no-dev --no-install-project
COPY probe.py ./
USER 10001
CMD ["/spike/.venv/bin/python", "probe.py"]
```

`probe.py` opens a WAL database through `aiosqlite` (the access path 02 §1.4 picks), loads the extension with `enable_load_extension` and `sqlite_vec.loadable_path()`, then: creates a `vec0` table of 768-dimension vectors (embeddinggemma's size) with `distance_metric=cosine`, inserts 10,000 vectors, runs a k-nearest query joined to a `note` table, runs the same query as a full scan with the scalar `vec_distance_cosine` over a plain BLOB table (the fallback 02 §18 names), writes a note and its vector in one transaction and rolls it back, and deletes a vector. It also reads the ELF header of `vec0.so`, since a wheel carrying a binary for the wrong architecture is the usual way a loadable extension fails on arm64.

**Commands.**

```sh
cd spikes/server/vec
uv lock --python 3.14
docker buildx build --platform linux/amd64,linux/arm64 --load -t spunto-vec-spike:m0 .
docker run --rm --platform linux/arm64 --read-only --tmpfs /tmp --cap-drop ALL spunto-vec-spike:m0
docker run --rm --platform linux/amd64 --read-only --tmpfs /tmp --cap-drop ALL spunto-vec-spike:m0
```

**Output** (trimmed; the multi-platform build took 11 s):

```text
== linux/arm64
python 3.14.8 on aarch64, sqlite 3.46.1, sqlite-vec wheel 0.1.9, vec0.so is 64-bit aarch64
loaded: vec_version() = v0.1.9
inserted 10000 x 768-dim vectors (vec0 + plain table) in 0.63 s
vec0 KNN k=5 in 10.0 ms: [(43, 0.0025, 'note 43'), (8918, 0.8497, 'note 8918'), ...]
plain-table scan k=5 in 17.3 ms: [(43, 0.0025), (8918, 0.8497), ...]
after rollback: 10000 notes, 10000 vectors
after deleting row 43 the nearest is 8918
OK
== linux/amd64 (emulated)
python 3.14.8 on x86_64, sqlite 3.46.1, sqlite-vec wheel 0.1.9, vec0.so is 64-bit x86-64
loaded: vec_version() = v0.1.9
vec0 KNN k=5 in 12.8 ms ...  plain-table scan k=5 in 23.0 ms ...
OK
```

The base resolves to Debian 13 (trixie) with SQLite 3.46.1, as 02 §1.4 says. Both runs used a read-only root filesystem, UID 10001 and no capabilities (02 §10.1). The wheel adds 164 KB.

**Verdict: works.** The extension loads through `aiosqlite` on both architectures, `vec0` takes inserts and answers nearest-neighbour queries, and a vector written in the same transaction as its note rolls back with it, which is the property 02 §1.4 chose `sqlite-vec` for.

**Implications for 02.**

- Pin `sqlite-vec==0.1.9` at M0; Renovate should not jump to the 0.1.10 alphas.
- The fallback in §18 is real and cheap at this scale: a plain table plus `vec_distance_cosine` answered the same top five in 17 to 23 ms over 10,000 vectors. Timings under emulation are indicative only.
- The real image copies uv only into the build stage; this throwaway image kept it in the runtime layer for brevity.

---

## 2. MCP over stateless streamable HTTP, inside FastAPI, with bearer auth

**What changed under us.** The official SDK is now `mcp` 2.x, and protocol revision **2026-07-28** removed the `initialize` handshake: each request carries its own envelope, and a client starts with `server/discover`. The SDK serves both generations from one low-level `Server`: requests whose `MCP-Protocol-Version` header names 2026-07-28 take the new path (stateless by design), and older ones take the handshake path, where `stateless=True` still matters. Claude Code 2.1.285 speaks 2026-07-28; Codex 0.162.1 speaks 2025-06-18 with a handshake. The spike confirmed both against the same endpoint.

**Setup.** `spikes/server/mcp/spunto/app.py` mounts the SDK inside FastAPI. The shape that worked, and that M1 can lift:

```python
server = Server("spunto", version=..., on_list_tools=list_tools, on_call_tool=call_tool)  # 2.x: constructor handlers
session_manager = StreamableHTTPSessionManager(
    app=server, stateless=True, json_response=True,
    security_settings=TransportSecuritySettings(allowed_hosts=[public.netloc], allowed_origins=[PUBLIC_URL]),
)
app = FastAPI(lifespan=...)  # the lifespan enters session_manager.run()
app.router.add_route("/mcp", BearerAuth(StreamableHTTPASGIApp(session_manager)), methods=["POST"])

# BearerAuth (a plain ASGI wrapper) checks the token before the SDK sees the request and puts the
# principal in scope["state"]; handlers read it back from the HTTP request the SDK attaches:
async def list_tools(ctx, params):
    tools = visible_tools(ctx.request.state.principal)          # core + enabled plugins, filtered by scope
    return types.ListToolsResult(tools=tools, ttl_ms=0, cache_scope="private")

async def call_tool(ctx, params):
    entry = visible_tools(ctx.request.state.principal).get(params.name)   # authorize again at call time
    ...
```

Tokens follow 02 §9.1 (`spunto_pat_<id>_<secret>`, SHA-256 of the secret kept, looked up by id and compared with `hmac.compare_digest`). There are three throwaway credentials: a read token, a write token and an "owner" credential standing in for the web session, which is the only one the plugin switch accepts (`PUT /api/v1/plugins/{id}`). The core tools are two stand-ins, `search_notes` (read) and `create_note` (write, which fires `note.saved` after the in-memory "commit"). The toy plugin `tally` (spike 3) adds `tally_stats` (read-only) and `tally_reset` (write).

**Commands.** `serve.sh` writes three random tokens to `tokens.env` (mode 600) and starts uvicorn on 127.0.0.1:8765 with `tally` enabled. `curl_checks.sh` sends raw JSON-RPC. `clients.sh` runs the real agents with per-invocation configuration only:

```sh
# Claude Code: a temporary MCP config file whose header uses ${SPUNTO_TOKEN}, expanded by Claude Code at load
cat > "$work/mcp.json" <<'JSON'
{"mcpServers": {"spunto": {"type": "http", "url": "http://127.0.0.1:8765/mcp",
                           "headers": {"Authorization": "Bearer ${SPUNTO_TOKEN}"}}}}
JSON
env -i HOME="$HOME" USER="$USER" PATH="$PATH" TMPDIR="$TMPDIR" SPUNTO_TOKEN="$token" \
  claude -p "$prompt" --model haiku --strict-mcp-config --mcp-config "$work/mcp.json" \
  --allowedTools mcp__spunto --no-session-persistence --max-budget-usd 0.5 < /dev/null

# Codex: -c overrides with the same keys as 02 §8.6; --ignore-user-config skips ~/.codex/config.toml entirely
SPUNTO_TOKEN="$token" codex exec --ephemeral --skip-git-repo-check --ignore-user-config -s read-only -C "$work" \
  -c 'mcp_servers.spunto.url="http://127.0.0.1:8765/mcp"' \
  -c 'mcp_servers.spunto.bearer_token_env_var="SPUNTO_TOKEN"' \
  -c 'model_reasoning_effort="low"' "$prompt" < /dev/null
```

`env -i` is there because this run happened inside a Claude desktop session, whose environment puts a nested `claude` into bare mode; a clean environment makes the nested run behave like the owner's terminal.

### Raw HTTP

```text
# no token                       {"error":"A valid read or write access token is required."} [HTTP 401]
# wrong secret                   {"error":"A valid read or write access token is required."} [HTTP 401]
# GET /mcp                       [HTTP 405]
# foreign Origin                 Invalid Origin header [HTTP 403]
# read token, tally on           ['search_notes', 'tally_stats']
# write token, tally on          ['search_notes', 'create_note', 'tally_stats', 'tally_reset']
# read token calls create_note   {"content":[{"text":"This token is read-only.",...}],"isError":true}
# a PAT tries the plugin switch  {"detail":"Plugin switches need the owner's session, never an access token."} [HTTP 403]
# owner switches tally off       {"id":"tally","enabled":false,"plugins_rev":2}
# read token, tally off          ['search_notes']
# write token, tally off         ['search_notes', 'create_note']
# write token calls tally_stats while off   {"content":[{"text":"The tally plugin is off on this server.",...}],"isError":true}
# owner switches tally on        {"id":"tally","enabled":true,"plugins_rev":3}
# write token, tally on again    ['search_notes', 'create_note', 'tally_stats', 'tally_reset']
```

Also: a `Host` header other than the public one (`spunto.spunto.svc.cluster.local:8080`) gets 421, the public URL as `Origin` gets 200, and a 5 MB body gets 413.

### Codex: end to end

Read token, `tally` on. The agent listed two tools and called one:

```text
TOOL: search_notes
TOOL: tally_stats
mcp: spunto/search_notes (completed)
RESULT: {"hits":[{"id":"01a1363d-…","snippet":"First spike note about #sqlite and agents"}]}

server: http POST /mcp principal=reader mcp-protocol-version=None user-agent='codex-mcp-client/0.162.1'
        rpc initialize protocol=2025-03-26 · rpc notifications/initialized · rpc tools/list -> ['search_notes', 'tally_stats']
        rpc tools/call search_notes protocol=2025-06-18 principal=reader
```

Write token, `tally` on. Four tools; the new note was counted by the plugin a second after the save returned:

```text
TOOL: mcp__spunto__create_note   TOOL: mcp__spunto__search_notes   TOOL: mcp__spunto__tally_reset   TOOL: mcp__spunto__tally_stats
CREATED: …"{\"id\":\"01a1363f-7636-…\",\"version\":1}"…
TALLY:   …"{\"notes_counted\":2,\"words\":11}"…

server: rpc tools/call create_note principal=writer
        spunto.plugin.tally worker thread finished counting 01a1363f-7636-…   (1 s later, after the call returned)
        rpc tools/call tally_stats principal=writer
```

After the owner switched `tally` off, a new session saw the shorter list on its first request, with no restart:

```text
write token:  TOOL: mcp__spunto__create_note  TOOL: mcp__spunto__search_notes   TALLY: not available
read token:   TOOL: mcp__spunto__search_notes                                   TALLY: not available
```

Switched off in the middle of a session. Codex called `tally_stats`, slept 25 s while the owner switched the plugin off, and called it again. It keeps the list it fetched at session start, and the server refuses the stale call cleanly:

```text
FIRST:  {"content":[{"type":"text","text":"{\"notes_counted\":2,\"words\":11}"}],"isError":false}
SECOND: {"content":[{"type":"text","text":"The tally plugin is off on this server."}],"isError":true}
TOOL: mcp__spunto__create_note  TOOL: mcp__spunto__search_notes  TOOL: mcp__spunto__tally_reset  TOOL: mcp__spunto__tally_stats
```

### Claude Code: protocol level

The standalone CLI on this Mac has no login of its own (`claude auth status` reports `"loggedIn": false`; the owner uses the desktop app, which hands its sessions their credentials). Each `claude -p` run therefore connected to the MCP server, which Claude Code does before the first model call, and then stopped with `Not logged in · Please run /login`. The server side of those four runs is still the real client's behaviour, and it shows the per-token, per-plugin list arriving:

```text
user-agent='claude-code/2.1.285 (sdk-cli)'  mcp-protocol-version=2026-07-28  origin=None
write token, tally off:  rpc server/discover · rpc tools/list -> ['search_notes', 'create_note']
read token,  tally off:  rpc server/discover · rpc tools/list -> ['search_notes']
write token, tally on:   rpc server/discover · rpc tools/list -> ['search_notes', 'create_note', 'tally_stats', 'tally_reset']
read token,  tally on:   rpc server/discover · rpc tools/list -> ['search_notes', 'tally_stats']
```

It also shows that the `${SPUNTO_TOKEN}` form in an MCP config file is expanded by Claude Code (the server saw `principal=reader` and `principal=writer`), which is the form 02 §8.6 gives for `.mcp.json`.

To cover `tools/call` on the 2026-07-28 path that Claude Code uses, `sdk_client.py` drives the SDK's own client in both generations with both tokens:

```text
    legacy  read: tools=['search_notes', 'tally_stats']        create_note -> is_error=True This token is read-only.
    legacy write: tools=['search_notes', 'create_note', 'tally_stats', 'tally_reset']   create_note -> is_error=False {"id":…,"version":1}
2026-07-28  read: tools=['search_notes', 'tally_stats']        create_note -> is_error=True This token is read-only.
2026-07-28 write: tools=['search_notes', 'create_note', 'tally_stats', 'tally_reset']   create_note -> is_error=False {"id":…,"version":1}
```

**To finish the Claude Code half**, the owner runs, from a terminal where `claude` is logged in and with the spike server up (`spikes/server/mcp/serve.sh`):

```sh
spikes/server/mcp/clients.sh claude read  claude-read  'Use only the spunto MCP server. Print the exact names of every tool you have from it, one per line, each prefixed with "TOOL: ". Then call search_notes with query "sqlite" and print the raw result prefixed with "RESULT: ".'
spikes/server/mcp/clients.sh claude write claude-write 'Use only the spunto MCP server. Print every tool name prefixed with "TOOL: ", then call create_note with body "Claude was here #spike" and print the raw result prefixed with "CREATED: ".'
```

### A revoked token

Both clients were given a token the server does not know. Neither tried OAuth discovery: there was no request to any `/.well-known/` path, which is what 02 §8.2 expects from a server that advertises no OAuth metadata. Claude Code tried the 2026-07-28 path, then fell back to a handshake `POST`, and stopped on the 401. Codex logged the server's sentence and carried on without the server:

```text
ERROR rmcp::transport::worker: worker quit with fatal: Transport channel closed, when UnexpectedServerResponse("HTTP 401: {\"error\":\"A valid read or write access token is required.\"}")
```

### What the clients send

Neither client sent an `Origin` header (02 §8.1 assumed so), neither opened a `GET /mcp` stream, and neither sent a session id. Codex presents the tools to its model as `mcp__spunto__<tool>`, the same prefix Claude Code uses, so the 40-character limit in 02 §2.6 covers both. In `codex exec` with approval `never`, Codex ran `create_note`, a write tool, without asking.

**Verdict: works with caveats.** Stateless streamable HTTP from the SDK's low-level server, mounted in FastAPI behind bearer auth, served a tool list that differed per token and per enabled plugin and changed on the next request after a switch, with no restart. Codex is proven end to end. Claude Code is proven at the protocol level for all four token and plugin combinations; its model-driven tool call waits on a logged-in CLI, and the same `tools/call` path passed with the SDK client.

**Implications for 02** are folded into the list at the end.

---

## 3. A toy plugin enabled and disabled at runtime

**Setup.** `spikes/server/mcp/plugins/tally/` is a separate installable package, so discovery goes through the entry-point group 02 §2.14 names for third-party plugins:

```toml
[project.entry-points."spunto.plugins"]
tally = "spunto_tally:plugin"
```

Its `plugin.toml` sits in the package and declares the two MCP tools. In code it registers a `note.saved` hook (counts words; the counting is blocking work run through `ctx.to_thread`, with a 1 s sleep standing in for something like parsing an attachment), a `plugin.enabled` backfill and the two tools.

The host (`spikes/server/mcp/spunto/host.py`, about 200 lines) does what 02 §2.12 describes:

- **Discover** reads each manifest without importing the plugin: `importlib.util.find_spec()` locates the top-level package without executing it, and the manifest is read from its folder. It refuses a manifest whose `id` and `entry` differ from the entry point's name and value.
- **Enable** imports the module through the entry point, checks that the tools the manifest declares are exactly the ones the code binds, registers the hooks, bumps `plugins_rev`, resumes paused jobs and runs the `plugin.enabled` backfill.
- **Disable** unregisters the hooks, cancels the plugin's running jobs and keeps them paused, and bumps a per-plugin **generation**. Every `Ctx` (the handle a plugin uses to reach the host) is bound to the generation it was issued under, so after a disable every call through an old handle raises `PluginDisabled`, even after a later re-enable. This is how "the host refuses its calls from that moment" (02 §7.8) is enforced.

**Command.**

```sh
cd spikes/server/mcp && .venv/bin/python check_lifecycle.py
```

**Output** (lightly reformatted from `spikes/server/mcp/out/lifecycle.txt`; values unchanged):

```text
   67 ms  spunto.host   discovered tally (spunto_tally:plugin); module imported: False
   69 ms  spunto.host   enabled tally (generation 1, imported now)
   70 ms  check         enable took 2.2 ms; hooks: {'note.saved': [('tally', count)], 'plugin.enabled': [('tally', backfill)]}
 1078 ms  tally         worker thread finished counting a
 1078 ms  spunto.host   job ('plugin.enabled', 'backfill', None) done
 1370 ms  check         kv after backfill: {'a': 3}
 1370 ms  check         --- save note b, then switch tally off while its count runs in a worker thread
 1672 ms  check         running before disable: [('note.saved', 'count', 'b')]
 1672 ms  spunto.host   job ('note.saved', 'count', 'b') cancelled; kept paused until tally is enabled again
 1672 ms  spunto.host   disabled tally: cancelled 1 running job(s), 1 paused
 1672 ms  check         disable took 0.7 ms; running: []; paused: [('note.saved', 'count', 'b')]
 1672 ms  check         --- save note c while tally is off
 1672 ms  check         running: [] (no hook is registered, so nothing was queued)
 2371 ms  tally         worker thread finished counting b
 2677 ms  check         kv while off (read directly): {'a': 3}
 2677 ms  check         a Ctx issued before the switch is refused: tally was switched off; call refused
 2677 ms  check         --- switch tally back on
 2677 ms  spunto.host   enabled tally (generation 3, module reused from sys.modules)
 2677 ms  spunto.host   resuming paused job ('note.saved', 'count', 'b')
 3686 ms  tally         worker thread finished counting b
 3687 ms  tally         worker thread finished counting b
 3687 ms  spunto.host   job ('note.saved', 'count', 'b') done
 4694 ms  tally         worker thread finished counting c
 4695 ms  spunto.host   job ('plugin.enabled', 'backfill', None) done
 5179 ms  check         kv after re-enable: {'a': 3, 'b': 2, 'c': 1}
 5179 ms  check         the old Ctx is still refused after re-enable: tally was switched off; call refused
 5179 ms  check         OK
```

The same switch also ran inside the live server in spike 2: the owner's `PUT` changed the MCP tool list on the next request, and a call to a tool of the disabled plugin came back as a one-sentence error.

**What happened to the work in flight**, in plain terms:

- **The coroutine stops at once.** Disabling cancelled the hook job while it was waiting on its thread. It wrote nothing, went back into the queue as paused, and ran again after the re-enable. Disable took under a millisecond.
- **The thread does not stop.** Python cannot kill a thread, so the count for note b kept running for another 0.7 s after the plugin was off, and its result was thrown away (the line at 2371 ms). Had it tried to reach the host, the generation check would have refused it. The cost is a worker thread held for as long as the blocking code runs, which for a stuck plugin means until restart.
- **Saves while off leave nothing behind.** With the hooks unregistered, note c produced no job; the `plugin.enabled` backfill counted it after the re-enable.
- **The module stays loaded.** Re-enable reused the module object from `sys.modules`; code changed on disk needs a restart, which fits 02 §2.14 (installs need a restart anyway).
- **Overlap after re-enable.** The resumed hook job for b and the backfill both counted b at the same moment (the two lines at 3686 and 3687 ms). The count is idempotent here, so the result is right, but a plugin that appends would have done it twice.

**Verdict: works with caveats.** Discovery without import, enable and disable without restart, hooks registered and unregistered, cancellation and pause of running jobs, and refusal of stale handles all behave as 02 §2.12 describes. The caveats are the uncancellable thread and the resumed-job overlap, both answered below.

---

## Proposed changes to 02

None of these changes a decision; they make the spec match the SDK that exists today and pin down behaviour the spikes exposed.

1. **§1.4, MCP row.** Name the version: `mcp` 2.x (2.3.0 tested). The 2.x low-level `Server` takes its handlers as constructor arguments (`on_list_tools`, `on_call_tool`), not decorators, and a handler reaches the HTTP request (and so the token's principal) through `ctx.request`. Add one sentence: the SDK serves the 2026-07-28 protocol (no handshake, which Claude Code 2.1.285 uses) and the 2025 handshake protocol (Codex 0.162.1) from the same endpoint, and `stateless=True` governs only the second.
2. **§8.1, tool list.** Return `tools/list` with `ttl_ms=0` and `cache_scope="private"`. The 2026-07-28 revision lets clients cache list results; ours depend on the token and the plugin switches, so they must never be shared across credentials or reused.
3. **§8.1, authorize at call time.** Say explicitly that `call_tool` re-derives the visible list for the calling token and refuses anything outside it ("This token is read-only.", "The X plugin is off on this server."). Agents keep the list from session start (Codex did), so a stale list reaching a disabled plugin is normal, not an attack. The "next session" sentence stays true. (2026-07-28 also offers `subscriptions/listen` for change notices; not needed now.)
4. **§8.1, Origin check.** Use the SDK's `TransportSecuritySettings(allowed_origins=[SPUNTO_PUBLIC_URL], allowed_hosts=[<public host>])`: it lets requests without `Origin` through (both agents send none) and refuses a foreign one with 403. It also enforces the `Host` header (421 otherwise), so an in-cluster caller that uses the service name must be listed too, or the Origin check is done in our own middleware instead.
5. **§8.1, limits.** The SDK's default body limit is 4 MiB; set `max_request_body_size=1_000_000` to match the 1 MB in the spec.
6. **§8.3, errors.** An exception escaping a handler reaches the client as a JSON-RPC error whose message is the exception text (seen during the spike: code 0, Python's `AttributeError` message). Wrap tool dispatch so an unexpected error becomes the usual one-sentence `isError` result and a logged code, so note text inside an exception can never reach an agent.
7. **§8.1, a small saving (optional).** On the 2026-07-28 path the SDK runs the `tools/list` handler again on every `tools/call` to validate parameter headers. Passing `Server(get_tool_input_schema=…)`, a name-to-schema lookup over all known tools, avoids it. That lookup is not scoped to the caller, which is fine because authorization stays in `call_tool`.
8. **§8.5 and §8.6, Codex approvals.** `codex exec` with approval `never` ran a write tool without asking. The setup page's advice to auto-approve read tools only needs the Codex-side setting spelled out (per-server and per-tool approval keys appear in codex 0.162; confirm the exact names when M1 writes the snippets).
9. **§2.6 and §2.12, the host.**
   - Read manifests through `importlib.util.find_spec()` and the package folder; `importlib.resources.files()` would import the plugin. Refuse a manifest whose `id` or `entry` does not match its entry point.
   - Bind every `Ctx` to a per-plugin generation that disable bumps; that is the whole mechanism behind "refuses its calls from that moment".
   - The host API is async only. Blocking plugin work goes through `ctx.to_thread` on a small executor of the plugin's own (two threads, say), because a thread cannot be cancelled: a disable discards its result, but the thread runs on, and a stuck plugin should not be able to drain the default executor the core also uses.
   - A job cancelled by a disable returns to `queued` and does not count as an attempt, so it never moves toward `dead` or the circuit breaker (§7.8).
   - Hook handlers must be idempotent, and the per-plugin concurrency of 1 (§7.8) applies to hook jobs and the `plugin.enabled` backfill alike, so a resumed job and the backfill never touch the same note at once.
   - Saves while a plugin is disabled queue nothing for it; the `plugin.enabled` backfill is how it catches up. One sentence in §2.12 makes this explicit.
10. **§1.4, `sqlite-vec`.** Pin 0.1.9, and note in §18 that the plain-table fallback measured 17 to 23 ms for 10,000 vectors of 768 dimensions, so it is a real fallback, not a theoretical one.

## Side effects and cleanup

- No global configuration was changed: the SHA-256 of `~/.claude/settings.json` and `~/.codex/config.toml` matched before and after. `~/.claude.json` changed, but this session's own host writes it continuously, so the change cannot be attributed to the spike.
- Codex ran with `--ephemeral --ignore-user-config` and left no session file. Claude Code, despite `--no-session-persistence`, created an empty `~/.claude/projects/<cwd>/memory/` folder per run; the six empty folders were removed.
- The spike server was stopped, `tokens.env` deleted, and the `spunto-vec-spike:m0` image removed. `serve.sh` makes fresh tokens on the next run.
- Idle memory of the spike server (FastAPI, the MCP SDK and the toy plugin, on macOS) was 81 MiB resident. That is not the image measurement 02 §18 asks for; it only says the SDK is not heavy.

Takeaway: the stack in 02 works on both architectures and with both agents, and what the spikes add is a short list of exact settings (cache hints, Origin and body limits, call-time authorization, generations and paused jobs in the host) for M1 and M2 to build in from the start.
