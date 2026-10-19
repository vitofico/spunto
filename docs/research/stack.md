# Stack research: what the notes app can reuse

Surveyed 2026-10-13, read-only, from `quire` (main at `eebaf0f`), `galley` (main at
`7a74760`) and `theficos-cluster` (main at `9a43573`, plus the uncommitted
`applications/galley/` and `applications/galley-cloud/` folders on disk).

## Why this document exists

The notes app needs four surfaces: an Android app, a web app, a self-hostable
server, and an MCP server (a small program that exposes tools such as
`search_notes` or `create_note` to Claude Code or Codex). None of these is new
territory. Quire already ships a native Android app that syncs offline edits to a
Python server and calls an LLM through Ollama. Galley already ships a TypeScript
web app, a Hono server, OIDC login against Authentik and a `galley-mcp` command.
The cluster already runs Blinko, Ollama and Authentik side by side. The question
is which of those solved problems the new app can lift, and which stack lets it
lift the most without making one person maintain too many toolchains.

## 1. Quire (Android app + Python server)

### Android

| Area | What Quire uses |
| --- | --- |
| Language / build | Kotlin 2.0.20, AGP 8.6.1, KSP, Gradle version catalog (`gradle/libs.versions.toml`), JDK 21 |
| SDK levels | `minSdk 26`, `targetSdk 34`, `compileSdk 35` |
| UI | Jetpack Compose (BOM 2025.07.00), Material 3, Navigation Compose 2.8.1, Coil 2.7 |
| Persistence | Room 2.6.1 (schema version 9), `androidx.security:security-crypto` for Keystore-backed credentials |
| Background work | WorkManager 2.9.1 |
| Networking | OkHttp 4.12 + kotlinx.serialization 1.7.2, hand-written DTOs |
| DI | Hand-rolled `AppContainer` (`app/.../di/AppContainer.kt`); no Hilt or Koin |
| Tests | JUnit 4, Robolectric 4.13, Turbine, Truth, MockWebServer |

Module layout (`settings.gradle.kts`): `:app` (Compose UI, navigation, DI wiring),
`:core:model`, `:core:identity`, `:core:metadata`, `:data:local` (Room + outbox),
`:data:sync` (REST client + WorkManager job), `:data:ai`, `:data:library`,
`:data:opds`, `:reader`, `:auth`. The split keeps pure-Kotlin rules in `:core:*`
so they can be tested against the same fixtures the server uses.

**Sync model, the most reusable idea.** Every local write lands in Room and in a
`pending_sync_ops` outbox table. A WorkManager job drains the outbox to the
server, then pulls deltas since the last cursor. Conflicts resolve by record-level
last-writer-wins on a server-assigned `updated_at`, with set union plus tombstones
for collections (`docs/architecture.md`). In practice: you edit a note on the
train with no signal, the edit is safe locally, and it reaches the server the next
time WorkManager gets a network. The server never calls back into the device
("push model").

**Hard-won build lessons worth copying.** The root `build.gradle.kts` gives every
`Test` task a 10-minute timeout and logs each test's `started` event, because a
wedged unit test once held CI for six hours and shipped a tag with no APK.
`scripts/dgradle` runs Gradle inside a pinned Docker image (Temurin 21 + Android
SDK 35, linux/amd64), so local and CI builds match.

### Server

| Area | What Quire uses |
| --- | --- |
| Runtime | Python >= 3.12, managed with `uv` (`uv.lock`) |
| Framework | FastAPI >= 0.115 + uvicorn |
| Data | SQLAlchemy 2 async + asyncpg, Alembic with per-feature migration branches, Postgres 16 |
| Config | pydantic-settings with a `QUIRE_SERVER_` env prefix; misconfiguration fails at startup, never a silent downgrade |
| Logging | python-json-logger, structured |
| Auth | `AuthBackend` protocol (`core/auth_backend.py`) with two implementations: `CalibreWebBasicAuth` (probes calibre-web with the incoming Basic header, TTL-cached) and `NativeAuth` (email + password hashed with argon2id, opaque session tokens stored hashed, user ids namespaced `native:<id>`) |
| Lint / test | ruff pinned to the same version in `pyproject.toml` and pre-commit, pytest + pytest-asyncio + testcontainers Postgres |
| Probes | `/health`, `/readyz`, a small admin status page at `/quire-admin` |

**The LLM client already handles the Ollama gotcha.**
`server/quire_server/core/ai/client.py` speaks the OpenAI chat-completions shape
(works with Ollama, OpenAI, vLLM, OpenRouter). It sends
`response_format: json_schema`, validates the answer with Pydantic, and retries
once with the validation error appended. If the answer is missing required keys,
it concludes the provider accepted `json_schema` and ignored it (exactly what
Ollama's cloud models do), and from then on also spells the schema out in the
prompt. If the provider rejects `json_schema` outright, it falls back once to
`json_object`. Around it sit `provider_errors.py` (human-readable hints such as
`ollama pull <model>`), a process-wide token bucket (`AI_RATE_PER_MIN`), and
per-user daily budgets. This is the single most valuable file for an AI-first
notes app: auto-labelling and summarising notes both need structured output that
can be trusted.

### Packaging, release and distribution

- **Server image** (`server/Dockerfile`): `python:3.12-slim`, `uv venv`, OCI labels,
  version passed as a build arg and exposed as an env var, `CMD` runs
  `scripts/migrate.py` (Alembic upgrade) and then uvicorn. One container, migrations
  on start.
- **Compose** (`server/docker-compose.yml`): `postgres:16-alpine` with a healthcheck
  plus the server; `env_file: .env`; required variables use `${VAR:?message}` so
  `docker compose up` fails with a readable sentence instead of booting a broken
  server; `extra_hosts: host.docker.internal:host-gateway` so a self-hoster's local
  Ollama is reachable on Linux too. A Caddy reference compose adds TLS.
- **CI** (`.github/workflows/`, all actions pinned by SHA):
  - `android-ci.yaml` is path-filtered. It bumps a CalVer `YYYY.MM.DD.<run>` and
    a monotonic `VERSION_CODE` in `gradle.properties`, then pushes commit and tag
    in one atomic push after build, tests and lint pass. A release job signs the
    APK (keystore from a base64 secret, debug-signed fallback) and attaches it to a
    GitHub Release. If another merge landed meanwhile, the run dispatches a fresh
    run instead of retrying.
  - `server-ci.yaml` runs ruff and pytest, then pushes
    `ghcr.io/vitofico/quire-server:{sha,latest}` with SBOM and provenance, then
    Trivy.
  - `codeql.yaml` weekly; `release-apk.yaml` rebuilds a given tag by hand.
- **Distribution:** F-Droid (reproducible builds, checked locally before each
  submission), Obtainium and GitHub Releases. Store text and screenshots live in
  `fastlane/metadata/android/en-US/`.
- **Hygiene:** Renovate (weekly, digests pinned, androidx grouped), pre-commit
  with `pre-commit-hooks`, ruff and `detect-secrets` (baseline committed).
- **Docs and licence:** `docs/{architecture,configuration,development,release,sync-api}.md`;
  working specs and plans go in the gitignored `docs/superpowers/`. Apache-2.0 + CLA.

## 2. Galley (TypeScript monorepo with an MCP server)

### Workspace

| Area | What Galley uses |
| --- | --- |
| Tooling | pnpm 9.15.9 workspaces (`apps/*`, `packages/*`), Node >= 20 (image `node:20.20.2-bookworm-slim`), ESM only |
| TypeScript | ^5.5, strict plus `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `verbatimModuleSyntax` (`tsconfig.base.json`) |
| Runtime model | Packages export `./src/index.ts` and run through `tsx`; no separate build step at runtime |
| Server framework | Hono 4 + `@hono/node-server` (web-server, proxy, compile) |
| Web | React 18 + Vite 5, CodeMirror 6, DOMPurify, Yjs + `y-indexeddb` for local drafts; theming with CSS custom properties switched by `data-theme`, plus two skins (ADR-0028) |
| LLM | Vercel AI SDK v6 (`ai`, `@ai-sdk/openai-compatible`, `@ai-sdk/anthropic`) hidden behind a `LanguageModelClient` interface; providers are config, not code (`docs/providers.md`) |
| Auth | `@galley/auth`: OIDC Authorization Code + PKCE with `jose`, IdP-agnostic; `GALLEY_AUTH_MODE=oidc` and any unknown value throws at startup |
| Persistence | `@galley/persistence`: store interfaces with in-memory reference implementations, `pg` and `isomorphic-git` adapters |
| Quality | vitest 3, Playwright 1.60 (chromium + firefox/webkit), prettier, ast-grep rules (`rules/`: no `console.log`, no `ts-ignore`, no focused or skipped tests) |

Package boundaries are enforced and written down in `AGENTS.md`: `shared` holds
types only, cores never import React, and the web app is the only React package.

### MCP server (`apps/mcp`)

- `@modelcontextprotocol/sdk` ^1.12 with `McpServer` + `registerTool` and zod 3
  input schemas.
- **Transport is stdio only.** Networked, authenticated MCP is on Galley's roadmap
  (#16.4) and not built, so there is no HTTP MCP transport to copy.
- `bin/galley-mcp.mjs` registers `tsx` in-process and imports `src/main.ts`, so the
  installed command (`pnpm link --global`) always runs the current checkout and
  stays a single process (stdio is the transport, so no intermediate pipe).
- Conventions worth keeping: stdout carries only protocol, every human notice goes
  to stderr; config parsing fails loudly and names the bad flag; operational
  failures come back as one-line `isError` results, never stacks; reads are capped
  (`READ_LIMITS`) and say honestly when they truncate; secrets and capability ids
  are logged only as fingerprints.
- Write safety: agents never edit directly; `propose_edit` publishes a pending
  proposal that a human accepts in the browser. Auto-accept exists but is opt-in,
  checkpointed and revertable (ADR-0023). A one-time pairing code is exchanged for
  a durable pairing stored in the user's config (ADR-0026).
- Tests run the server against the SDK's in-memory transport pair.

For a notes app the Yjs-room plumbing does not transfer, but the tool-shaping
conventions, the bin wrapper and the review-gate idea do.

### Packaging, release and docs

- **One multi-stage `Dockerfile`** with `unit`, `test` (Playwright) and `runtime`
  targets; the single runtime image runs every service as `USER node`.
- **Compose** with profiles for optional services and an overlay file;
  `scripts/smoke-selfhost.sh` brings the stack up and checks every health endpoint.
  Ports bind to `127.0.0.1` by default.
- **Kubernetes** (`deploy/k8s/`): Kustomize base + components (`auth`, `compile`) +
  overlays, default-deny NetworkPolicy, image pinned once in the base.
- **CI:** `ci.yml`; `release.yml` computes CalVer `YYYY.MM.MICRO`
  (`scripts/calver.mjs`), pushes `ghcr.io/vitofico/galley:{calver,stable}`, then tags
  and creates the GitHub Release (tag after push, so a failed push never leaves an
  orphan tag); `security.yml` runs `pnpm audit` with an ignore list, gitleaks with a
  tuned `.gitleaks.toml`, Trivy and semgrep; `demo.yml` publishes to GitHub Pages.
- **Docs layout:** `AGENTS.md` (invariants, boundaries, conventions, attribution
  rule), `docs/` with `vision.md`, `architecture.md`, `roadmap.md`, topic docs and
  ADRs in `docs/decisions/ADR-NNNN-<slug>.md`; `CHANGELOG.md`; gitignored
  `internal/` for planning. AGPL-3.0 + CLA + `TRADEMARKS.md`.

## 3. theficos-cluster (how apps get deployed)

- **Platform:** single-node MicroK8s; one Kustomize folder per app in
  `applications/<app>/`; deployed on the node with `make deploy APP=<name>`
  (`scripts/deploy/10-deploy.sh`, which supports `--dry-run` and `--diff`).
- **Ingress:** Traefik with `cert-manager.io/cluster-issuer: letsencrypt-prod`,
  `router.entrypoints: websecure`, `router.tls: "true"`; hosts are
  `<app>.theficos.dedyn.io` (DNS at deSEC). Path routing uses Traefik `Middleware`
  `stripPrefix`; the longer prefix wins across Ingress objects.
- **Pod security (required for app pods):** `automountServiceAccountToken: false`,
  `seccompProfile: RuntimeDefault`, drop all capabilities, no privilege escalation,
  `runAsNonRoot` and `readOnlyRootFilesystem` where the image allows.
- **NetworkPolicies (required):** `default-deny-ingress`, `allow-from-traefik` on
  the app port, explicit app-to-database rules.
- **Secrets:** SOPS + age. A per-app `secret.yaml` is encrypted, kept out of
  `kustomization.yaml`, and decrypted and applied by the deploy script.
- **Images:** private `ghcr.io/vitofico/*`; each namespace needs the
  `github-packages-repo` pull secret copied in. Galley uses a moving `stable` tag
  with `imagePullPolicy: Always` and pins a CalVer tag to roll back; Quire uses
  `latest`.
- **Storage:** Postgres runs per app on `microk8s-hostpath` PVCs (Quire:
  `postgres:16-alpine` StatefulSet; Blinko: `postgres:14` Deployment). App files go
  on NFS PersistentVolumes at `/mnt/cluster-data/<app>` (RWX, `Retain`). The
  October upgrade plan (`docs/upgrade-plan-2026-10.md`) records that hostpath PVCs
  are **outside the borg backups**, so a new app should ship with a `pg_dump` hook
  from day one.
- **Authentik** (2026.2.7, `auth.theficos.dedyn.io`): apps integrate through native
  OIDC (Immich, Nextcloud, Galley), not through a forward-auth proxy. Galley's
  provider and application are created by an idempotent API script
  (`scripts/helpers/authentik-galley-setup.sh`) that reads the client secret from
  the in-cluster Secret, so the plaintext never leaves the cluster.
- **LLMs:** one shared Ollama (`ollama/ollama:0.32.14`, 20 Gi hostpath for models)
  at `http://ollama.ollama.svc.cluster.local:11434/v1`. Quire and Hermes use cloud
  models through it (`gpt-oss:120b-cloud`, fallback `gemma4:31b-cloud`), Quire with
  an API key from its Secret. Every consumer namespace needs an `allow-from-<ns>`
  policy in the `ollama` namespace (and an egress rule on its own side if it
  restricts egress). Galley's model proxy uses the same Ollama as a named upstream.
- **Blinko today:** `blinkospace/blinko:latest` on port 1111 at
  `blinko.theficos.dedyn.io`, NextAuth, Postgres 14 (move to 17 planned before
  PG14's 12 November end of life), data on NFS at `/mnt/cluster-data/blinko`. An
  nginx sidecar rewrites `/embed` to Ollama's `/api/embed` to work around an
  upstream Blinko bug, which shows that an embedding model is already pulled on the
  cluster and that the existing notes live in a Postgres the new app could import
  from.

## 4. Reusable pieces at a glance

| Need in the notes app | Best existing source | Language |
| --- | --- | --- |
| Offline-first Android edits that sync later | Quire `:data:local` outbox + `:data:sync` WorkManager job, LWW on `updated_at` | Kotlin |
| Android module layout, version catalog, test timeouts, `dgradle` | Quire | Kotlin |
| Signed APK release, CalVer, F-Droid + Obtainium, fastlane metadata | Quire `android-ci.yaml`, `docs/release.md` | YAML |
| Structured LLM output that survives Ollama ignoring `json_schema` | Quire `core/ai/client.py` + `provider_errors.py` + rate limits | Python |
| Local accounts (email + password, session tokens) | Quire `NativeAuth` | Python |
| Login through Authentik | Galley `@galley/auth` (OIDC + PKCE, `jose`) and the Authentik setup script | TypeScript, bash |
| Provider-agnostic LLM calls with streaming and tools | Galley `@galley/agent` (`LanguageModelClient` over AI SDK v6) | TypeScript |
| Web app scaffolding, theming with CSS tokens, Playwright e2e | Galley `apps/web`, ADR-0028 | TypeScript |
| SPA served by a tiny server with runtime `/config.js` | Galley `apps/web-server` (Hono) | TypeScript |
| MCP tool conventions and an installable bin | Galley `apps/mcp` | TypeScript |
| Server image with migrate-on-start, readable compose failures | Quire `server/Dockerfile`, `docker-compose.yml` | Docker |
| Image release to ghcr with CalVer + `stable` | Galley `release.yml` | YAML |
| Secret scanning, audit, Trivy | Galley `security.yml`, Quire `detect-secrets` | YAML |
| Cluster manifests (ingress, netpol, SOPS, pod security) | `theficos-cluster/applications/galley` as the template | YAML |
| Agent rules and decision records | Galley `AGENTS.md` + `docs/decisions/` | Markdown |

Things neither repo has yet, whichever stack is chosen: vector search (for
"find notes like this one"; `pgvector` on Postgres is the obvious fit), an HTTP
MCP transport with authentication, and Android login through OIDC.

## 5. Candidate stacks

All three assume the same deployment shape, because it is what makes self-hosting
easy: **one server image that serves the API, the built web app and the MCP
endpoint, plus one Postgres**, shipped as a two-service `docker compose` file and as
a Kustomize folder for theficos. They differ in which language the server is
written in and how the Android app is built.

### A. Quire-shaped: native Kotlin app + Python server + TypeScript web

- **Android:** Kotlin + Compose, copying Quire's module split, Room outbox,
  WorkManager sync, `AppContainer` DI and release pipeline.
- **Server:** FastAPI + SQLAlchemy async + Alembic + Postgres (with `pgvector`),
  forked from Quire's server skeleton, `NativeAuth`, AI client and rate limits.
- **MCP:** the official Python MCP SDK mounted inside the FastAPI app as a
  Streamable HTTP endpoint, reusing the server's auth (personal access tokens), plus
  Galley's tool conventions (bounded reads, honest one-line errors, stderr-only
  notices for any stdio shim).
- **Web:** React + Vite SPA in a small pnpm workspace, styled with Galley's CSS-token
  approach, built into the server image; TypeScript types generated from FastAPI's
  OpenAPI schema.
- **Reuses:** almost all of Quire (Android, server, AI, auth, Docker, CI, F-Droid);
  Galley's web patterns and MCP conventions; theficos manifests.
- **Main risk:** three languages and three toolchains for one person, and API
  contracts written three times (Pydantic, hand-written Kotlin DTOs, generated TS).
  Contract drift is the failure mode; generating clients from OpenAPI reduces it
  for the web but Quire's Kotlin DTOs are hand-written today.

### B. Galley-shaped: TypeScript everywhere, Android as a wrapped web app

- **Server:** Hono + Postgres (`pg`, with `pgvector`) in a pnpm monorepo laid out
  like Galley (`apps/server`, `apps/web`, `apps/mcp`, `packages/shared`,
  `packages/ai`, `packages/auth`), zod schemas shared by every surface.
- **Web:** React + Vite, installable as a PWA (an offline-capable web app the
  browser can add to the home screen).
- **Android:** the same web build wrapped with Capacitor (a native shell around a
  WebView, with plugins for the share sheet and storage).
- **MCP:** `apps/mcp` on `@modelcontextprotocol/sdk`, both as a `notes-mcp` stdio
  bin modelled on `galley-mcp` and as a Streamable HTTP route on the server.
- **Reuses:** Galley's monorepo, AGENTS.md and ADR habits, Hono servers, OIDC
  package, `LanguageModelClient` provider layer, Dockerfile, CalVer image release,
  security workflow and Kustomize layout.
- **Main risk:** the Android app. A WebView shell struggles to feel native (quick
  capture from the share sheet, home-screen widgets, smooth offline editing), which
  cuts against "beautifully designed" on the platform used most for capturing
  ideas. F-Droid builds of Capacitor apps are possible but heavier than Quire's
  Gradle-only build. Quire's Ollama-proof structured-output logic must also be
  ported, since AI SDK object generation does not by itself recover from a provider
  that silently ignores the schema.

### C. Hybrid: native Kotlin app + TypeScript server, web and MCP

- **Android:** identical to A (Quire's Kotlin + Compose stack and pipeline).
- **Server, web and MCP:** identical to B (Galley-shaped pnpm monorepo, Hono,
  React + Vite, MCP SDK with stdio bin and HTTP route), with zod as the single
  source of the API contract and an OpenAPI document generated from it for the
  Kotlin client.
- **Reuses:** Quire for everything Android; Galley for everything server, web and
  MCP; theficos manifests. Two languages instead of three, and the web, server and
  MCP share types for free.
- **Main risk:** the server-side pieces Quire has already debugged in Python (the
  `json_schema` fallback and retry, `NativeAuth` with argon2id, migrate-on-start,
  rate limits) have to be re-implemented in TypeScript, and the Kotlin client still
  crosses a language boundary, so contract drift moves rather than disappears.

### Not shortlisted

Kotlin or Compose Multiplatform (one Kotlin codebase for Android and web) and
Flutter: neither repo uses them, so nothing transfers, and Compose's web target is
the least proven part of either toolchain.

## 6. How to choose

The real decision axis is **how native the Android capture experience must feel,
weighed against how many languages one person maintains.** If quick capture on
Android (share sheet, widget, offline) is the heart of the product, B is out and the
choice is between A (maximum reuse of already-debugged code, three languages) and C
(two languages, one shared contract for web, server and MCP, at the cost of porting
Quire's AI and auth code). If the web app is where most notes get written, B's
single language becomes attractive. Doing nothing yet costs little: this is a
research input for the requirements and design steps, and no repo changes until a
stack is chosen.

Lean, for the design step to confirm or overturn: **A**, because the two pieces
that are hardest to get right in an AI-first, offline-capable notes app (trustworthy
structured LLM output and offline sync) already exist and are tested in Quire's
Python server and Kotlin app, and the web layer is the thinnest of the four
surfaces.

**Takeaway:** whichever stack wins, the Android sync model and the LLM client come
from Quire, the MCP conventions and web scaffolding come from Galley, and the
deployment is a copy of `applications/galley` adapted to one image plus Postgres.
