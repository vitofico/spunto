# AGENTS.md: working on Spunto

## Approved public distribution revision (2026-10-19)

The owner authorizes making `vitofico/spunto` public, publishing a stable signed
Android release for Obtainium, and submitting it to F-Droid after verification.
This supersedes the private-only publication restriction below for this
repository. Keep `spunto-cloud` private. Preserve existing release tags and the
owner-controlled signing identity; signing keys and private test evidence stay
outside the repository. Complete the public exposure audit and independent
release review before changing visibility. An F-Droid submission is not an
accepted listing until its maintainers publish it.

## Approved hosted revision (2026-10-18)

The owner authorizes shared multi-user PostgreSQL hosting, generic OIDC/Authentik
SSO, shared spaces and individual-note collaboration, with a separate local
`spunto-cloud` repository. This supersedes D8 and the SQLite-only invariant below
for hosted deployments; SQLite self-hosting remains supported. Follow
`docs/decisions/2026-10-18-multi-user.md`. The owner explicitly approves publishing
both repositories privately and deploying after verification. Preserve the
existing production library and verify recovery and migration before cutover.
Use synthetic users for acceptance. Live cursors/character-level editing are
deferred; use versioned autosave, merge and preserved conflicts. Do not change
the historical approved design documents 01–05.

Spunto is a self-hosted, AI-first notes app: one Python process that owns one SQLite file, a native Android app, a React web app and an MCP endpoint for coding agents, with every feature beyond the core built as a plugin. The design is settled and lives in `docs/`; read `docs/README.md` first, then `docs/02-spec.md` for anything on the server. The owner now authorizes **finishing and delivering the full product**, including M6 through M8 (02 §16), fixes found during the requirements audit, and two additional bolder colour themes. Coordinate bounded work with subagents and test on the open Android emulator and local Ollama server. The owner authorizes consolidating the current work into one initial commit and pushing the source to the configured GitHub repository. Preserve a local recovery copy and complete required checks and independent review before pushing. GitHub releases, APK publication and registry publication still require separate owner instructions. Deployment on the existing theficos MicroK8s cluster at `spunto.theficos.dedyn.io`, including HTTPS, authentication and direct local image import, is authorized. Production starts with an empty database; fixtures remain local. M0 through M5 have local evidence in `docs/development/`; reuse checks only while their inputs stay unchanged. Import, retrieval and new acceptance checks use synthetic fixtures only by the owner's choice. Third-party loading/API freeze remains M8. Physical-phone, external-client and owner-adoption checks retain their stated limitations and must not be reported as passed without evidence.

If a task is ambiguous, conflicts with an invariant below or with the spec, stop and ask. Do not edit `docs/01-requirements.md` to `docs/05-redesign-ux.md` to make code fit; propose the change instead.

## Layout

| Path | What |
|---|---|
| `server/` | The Python package (uv project). `src/spunto/`: `main.py` (startup sequence, app factory), `config.py` (settings), `cli.py` (the `spunto` command), `store/` (SQLite, data lock, migrations in `store/sql/`, snapshots), `auth/` (passwords, owner, sessions, back-off), `jobs/` (backup/retention), `host/` (lifecycle, manifests, contexts, storage, leased worker), `plugins/` (bundled plugins), `api/` (routers), `core/` (notes, labels, merge, search, rendering and export), `mcp/` (authenticated agent transport/tools). Tests in `server/tests/`. |
| `deploy/k8s/` | Kustomize base for the authorized theficos MicroK8s deployment at `spunto.theficos.dedyn.io`; image import directly to the node without a registry push |
| `deploy/compose/minimal/` | The minimal Docker Compose setup (02 §10.4) |
| `scripts/` | `smoke-selfhost.sh`: starts a built image hardened, then setup, login, backup and restore |
| `web/` | React 19/Vite/strict TypeScript, TanStack Query, wouter, typed API, core screens and generated plugin UI. Vitest in `tests/`; three-engine Playwright in `e2e/`. |
| `.github/workflows/` | `server.yml` (lint, tests, multi-arch image and smoke test), `security.yml` (gitleaks, pip-audit), `release.yml` (main pushes: checked CalVer APK/image, atomic source/tag promotion), Android/server recovery workflows (repair immutable releases) |
| `docs/` | Requirements, spec, design, decision records; `docs/spikes/` keeps spike results |
| `spikes/` | Throwaway spike code, gitignored; only results go into `docs/spikes/` |

The `android/` modules and shared sync fixtures follow 02 §13. M8 adds operator-installed plugin and colour-theme packs; the web app has no service worker or offline replica. The original three themes stay intact when adding the owner's two bolder themes.

## Commands

```sh
cd server
uv sync                                    # dev environment, from the committed uv.lock
uv run pytest                              # real SQLite files in temporary directories, no containers
uv run ruff check . && uv run ruff format --check .
SPUNTO_PUBLIC_URL=http://localhost:8080 SPUNTO_DATA_DIR=/tmp/spunto SPUNTO_LOG_FORMAT=text uv run spunto serve
uv run spunto backup | restore <snapshot> | owner set-password | owner hash-password
uv run spunto token create --help
uv run spunto plugins run markdown-import --help

docker buildx build --platform linux/amd64,linux/arm64 -f server/Dockerfile .
docker buildx build --load -t spunto:dev -f server/Dockerfile . && scripts/smoke-selfhost.sh spunto:dev
pre-commit run --all-files                 # from the repository root

cd web                                    # Node 26, pnpm 12.11.2
pnpm install --frozen-lockfile
pnpm generate:api && pnpm generate:tokens
pnpm typecheck && pnpm test && pnpm format:check && pnpm build
pnpm exec playwright install chromium firefox webkit
pnpm test:e2e                             # fixtures against Vite
# Real API tests need a disposable image with the documented test owner:
SPUNTO_E2E_URL=http://127.0.0.1:18081 pnpm test:e2e
```

## Invariants

These come from the spec. Breaking one needs the owner's decision and a spec change first.

1. **One process, one SQLite file.** The data-directory lock (`store/lock.py`) keeps a second process off the volume. No replicas, no Postgres, never on NFS (D1, 02 §2.2).
2. **One writer.** Writes are short transactions through `Store.write()`; nothing holds the writer across a network call (02 §3.2).
3. **Migrations are numbered SQL files** in `store/sql/`, applied at start after a pre-migration snapshot. Never edit a migration that has shipped; add the next number. A database newer than the code refuses to start (HOST-5).
4. **Operator configuration is environment only:** `SPUNTO_` variables, with `_FILE` variants for secrets. The owner's [approved model-selection revision](docs/decisions/2026-10-18-ai-model-selection.md) permits per-space model/profile choices in Settings → AI; provider endpoints, credentials and limits remain environment-only. A bad environment value stops startup with one sentence naming the variable; problems the operator must fix raise `OperatorError` (HOST-2).
5. **Secrets never leak.** Logs never contain note text, search queries, passwords, token values or provider keys. Tokens are random, stored as SHA-256, compared in constant time, and accepted only in a cookie or the `Authorization` header, never in a URL (02 §9).
6. **Cookie-authenticated writes carry a matching `Origin`**; plain HTTP is allowed only loudly (D33).
7. **The image runs as UID 10001** with a read-only root filesystem and writes only to `/data` and `/tmp` (02 §10.1).
8. **No telemetry, no side doors.** The server never calls home and never spawns a process from configuration or from user, API or MCP input.

## Conventions

- Python 3.14, FastAPI, hand-written SQL (no ORM), pydantic-settings. Lean code: no dead code, no speculative abstraction, comments only where they explain why.
- Web cards consume server-sanitized HTML. New capture persists locally until Save; existing note edits autosave. Draft acknowledgement must preserve typing done while a request is in flight. JS initial gzip budget is 150,000 bytes; all bundled WOFF2 fonts together are at most 320,000 bytes.
- `uv.lock` is committed and CI installs with `--locked`.
- ruff is pinned to one exact version in `server/pyproject.toml` and in `.pre-commit-config.yaml`; bump both in the same change.
- Database times are integer milliseconds since the epoch, UTC; API times are ISO 8601 with `Z`. API errors are RFC 9457 problem documents with a stable `code`.
- Tests use a real SQLite file in a temporary directory; do not mock the store.
- gitleaks allowlists test sentinels by their exact value in `.gitleaks.toml`, never by path, so a real secret in a test file still fails the scan.
- A release workflow builds and pushes the image before it creates the tag, so a tag never points at an image that does not exist.

## Commits

Gitmoji plus conventional commits: the emoji, then the type and scope, then a short imperative summary.

```text
:sparkles: feat(server): add login back-off
:bug: fix(server): keep the data lock until the database closes
:memo: docs: record the M0 spike results
```

Scopes follow the top-level folders: `server`, `web`, `android`, `deploy`, `docs`, `ci`.

Write commits, pull requests and docs as the human author. No AI attribution anywhere: no `Co-Authored-By` trailers for an assistant, no "generated with" footers, no AI-branded branch names.
