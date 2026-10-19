# Blinko teardown

Research date: 2026-10-13. Version studied: Blinko 1.8.8 (released 2026-06-20, the current `latest` image), source at `main` as of 2026-08-27. Sources: the repo (`github.com/blinkospace/blinko`, which `blinko-space/blinko` redirects to), docs.blinko.space, the issue tracker, and the theficos manifests in `theficos-cluster/applications/blinko` (local `main` plus the 4 commits on `origin/main` that moved Postgres to 17.11 today).

## TL;DR

- Blinko got the **capture loop** right: one input box on top of a card feed, inline `#tags` with `/` for nesting, Markdown as the note format, and a share target on Android. That loop is worth copying almost unchanged.
- Everything around that loop has grown sideways: three note types users can't tell apart, a "decentralized hub", plugins, a music player, analytics, comments, 13 OAuth providers, three AI frameworks. The result is a 430 MB image, a 93 MB APK, and a web UI that burns 20 to 60 % of a CPU core while idle.
- The AI layer is broad but fragile. Vectors live in a separate SQLite file that regularly goes missing (`no such table: blinko`, the most-commented open issue), auto-tagging parses free text with `split(',')` and appends whatever comes back straight into the note, and provider-specific bugs (Ollama `/embed`, Anthropic `/v1`, Jina) stay open for months.
- The MCP server exists (streamable HTTP plus legacy SSE, bearer token) but it reuses the in-app agent's tools: internal parameters leak into the schema, tools run as `superadmin`, and several are broken in open issues.
- Android is a Tauri WebView wrapper with no offline mode. Offline mode is the most-upvoted open request.
- The project is effectively one maintainer: since 2026-04-01, 56 issues opened and 8 closed, and the last code merge was 2026-08-03.

## 1. What Blinko is

"AI-powered card note-taking" for fleeting thoughts (11.1k stars, GPL-3.0, created 2024-10). Stack:

| Layer | Choice |
|---|---|
| Frontend | React 18, Vite, Tailwind, HeroUI, MobX, React Router 7, Vditor (Markdown editor), i18next |
| Backend | Node 20 + Express, tRPC (also exposed as REST through `trpc-to-openapi`), Prisma |
| Storage | PostgreSQL for everything except vectors, which go in a libSQL (SQLite) file |
| AI | Mastra agents + Vercel AI SDK providers + LangChain document loaders (`llamaindex` also installed in the image) |
| Jobs | `pg-boss` (a job queue stored in Postgres) plus cron-style jobs |
| Clients | Web/PWA, Tauri 2 for desktop (macOS, Windows, Linux) and Android |
| Build | Bun + Turbo monorepo (`app`, `server`, `shared`, `prisma`) |

Project health: the `blinko-space` account authored 1,317 commits; the next human contributor has 44. 904 issues total, 401 open. Triage is mostly a bot (Dosu). The fix for the idle-CPU bug (PR #1255) has been open since 2026-10-02.

## 2. Core concepts

### Note types

One `notes` table with an integer `type`:

| Type | Name | Intended use | Behaviour |
|---|---|---|---|
| 0 | Blinko ("flash thought") | short, throwaway captures | a scheduled job can auto-archive them after a period |
| 1 | Note | long-form, permanent | same editor, same card |
| 2 | Todo | tasks | same editor; checkboxes are just Markdown `- [ ]` |

Each note also carries flags: `isTop` (pinned), `isArchived`, `isRecycle` (trash, a soft delete), `isShare` (public link with optional password, expiry and view cap), `isReviewed` (for daily review).

The split confuses people. In #692 a user writes they have "NO IDEA what the difference between a Blinko and a Note is"; the maintainer's answer was a setting to pick the default landing page, because the app was "originally designed to include both short-term and long-term notes". The docs need a whole page ("Blinko vs Note") to explain it. Functionally the types differ only in which sidebar list they appear in and whether auto-archive touches them.

### Tags: `#hashtag` and nesting

- Tags are written **inline in the note text**: `#idea`, `#code/javascript`. The `/` builds a hierarchy, so `#code/javascript` creates `code` with child `javascript`.
- On every save the server runs a regex (`#[^\s#]*`) over the content, builds the tree, and upserts rows in `tag` (name, parent id, icon, sort order) and `tagsToNote` (the join). So **the note text is the source of truth** and the tag table is an index derived from it.
- Good: zero friction (you type the tag where the thought is), the Markdown stays portable, and hierarchy is free.
- Bad, because text is the source of truth:
  - Renaming a tag rewrites every note's text with `content.replace(new RegExp('#' + oldName, 'g'), ...)` (`server/routerTrpc/tag.ts`). The old name is not escaped and there is no word boundary, so renaming `#work` also turns `#workout` in those notes into `#<new>out`.
  - Clients cache the tag tree: after a rename the sidebar keeps the old tag even after clearing cache (#1102).
  - AI auto-tagging works by appending `#tags` to the end of the note text, so AI output becomes permanent content.

### References, comments, history

- **References**: `noteReference` (from, to) links notes; the UI shows outgoing links and backlinks.
- **Comments**: threaded, can come from guests on shared notes (stores guest name, IP, user agent). AI output is also posted as comments authored by "Blinko AI".
- **History**: every content change snapshots the previous version into `noteHistory` with a version number. Cheap and useful.

### Resources

Attachments live under `.blinko/files` (or S3) and have their own "Resources" page with folders. Folder rename and delete are buggy (#1128: shows "Success" but fails; #1243: ghost folders).

## 3. Capture flow

| Surface | Flow |
|---|---|
| Web/PWA | Home is an editor box above a masonry feed of cards. Type Markdown, `#` autocompletes tags, attach files or images, record voice, pick the type (Blinko, Note, Todo), send. `Ctrl+K` opens search. |
| Desktop (Tauri) | Global-hotkey Quick Note window, Quick AI window, a text-selection toolbar ("translate, ask AI, bookmark"), autostart. |
| Android | Share target (text, links with title fetch, images, files), launcher shortcuts (Quick Note, Voice), a home-screen voice widget. Voice is transcribed if an audio model is configured. |
| API | `POST /api/v1/note/upsert` with a bearer token; a desktop companion ("Blinko Snap"), a WeChat bot. |
| Resurfacing | Daily Review and random notes bring old captures back. |

Pain points on capture are mostly mobile: the "saved" toast appears but the edit is lost on Android Chrome and in the APK (#948), the input box opens with a noticeable delay (#672), the text field becomes unreachable (#1219), iOS editing stutters (#613), the camera button is missing (#1049). And nothing works without a connection to the server (#898).

## 4. AI features

Everything is configured **in the web UI and stored in Postgres** (`aiProviders`, `aiModels` with a `capabilities` JSON, plus global config that picks the main, embedding, voice, image and rerank models). Nothing is set through environment variables.

| Feature | How it works | Problems |
|---|---|---|
| **Providers** | Chat: OpenAI, Anthropic, Gemini, Ollama, DeepSeek, OpenRouter, xAI, Azure, MiniMax, LiteLLM, any OpenAI-compatible URL. Embeddings: OpenAI, Azure, Voyage, Ollama, OpenAI-compatible. Audio: OpenAI voice. | Ollama goes through the unmaintained `ollama-ai-provider`, which calls `/embed` instead of `/api/embed` (#1126, open). Anthropic template is missing `/v1`, so every request 404s (#1248). Jina via OpenAI-compatible fails although "Test connection" passes (#1210). "AI not working" threads: #258, #509, #611, #629, #644, #799, #885. |
| **Embedding search (RAG)** | Markdown is chunked (Mastra `MDocument`), each chunk embedded with the string `Create At: ... Update At: ...` appended, stored in one libSQL index named `blinko` under `.blinko/vector`, cosine similarity, top-K default 3, minimum score 0.4, optional LLM rerank. Embedding dimension is guessed from the model name or entered by hand. Attachments: PDF, DOCX, CSV, TXT via LangChain loaders; images are described and OCR'd by a vision model, then embedded. A tag can exclude notes from embedding. | The vector table goes missing: `SQLITE_ERROR: no such table: blinko` (#646, 28 comments, open since 2025-04; also #579, #852). Reading the code, the index is only created when the vector-store singleton first starts, and it skips creation if no embedding model is configured yet, so configuring embeddings later likely leaves no table until a force rebuild or restart. Force rebuild segfaults around 400 to 800 notes into an 8,000-note library (#1232) and a restart mid-rebuild leaves progress stuck (#663). One shared index for all users, with top-K taken before filtering by account. Timestamps in the embedded text dilute the vectors. Users report RAG simply not triggering (#1094, #988). |
| **Auto-tagging** | "AI post-processing" runs after a note is created or updated, in mode `comment`, `tags`, `both`, `smartEdit` or `custom`. The tag agent gets the full existing tag list and a long prompt asking for comma-separated `#tags`, preferring existing ones and nesting new ones under existing parents. Also manual: right-click a note, pick from suggested tags. | Output is parsed with `result.text.split(',')`, capped at 5, and **appended to the note text** with no schema and no validation. A model that ignores the format (the Ollama-cloud behaviour we already hit in quire) writes its prose straight into your note. The tag list sent to the model is not scoped to the user. |
| **Chat with notes** | `/ai` page; conversations and messages stored in Postgres; streaming Mastra agent; retrieved notes injected into the system prompt and returned to the UI as sources. The agent has tools: search, create, update, delete notes, comment, web search (Tavily), web page extract, scheduled tasks, plus any external MCP servers the user registers. Conversations can be shared. | An agent with delete rights over your notes, driven by whatever model you configured. Large hardcoded prompts. |
| **Writing assist** | In-editor Expand, Polish, Custom prompt, with Accept and Reject. Also: AI comment on a note, emoji suggestions, "related notes" (keyword extraction then search), conversation-title summaries. | Early reports of polish inserting odd symbols and HTML (#217, #260). |
| **Scheduled AI tasks** | A cron expression plus a prompt; the agent runs it periodically. | The MCP versions of these tools fail on every call (#1178, #1052). |
| **Voice** | Audio attachments are transcribed with the configured audio model. | Transcription finishes but never shows in the UI (#989). |

## 5. Data model and storage

Postgres via Prisma, 19 models:

- Core: `notes`, `tag`, `tagsToNote`, `noteReference`, `noteHistory`, `attachments`, `comments`.
- AI: `aiProviders` (API keys stored in plain text), `aiModels`, `conversation`, `message`, `aiScheduledTask`, `mcpServers` (external MCP servers, including `stdio` commands the server will spawn).
- Social and multi-user: `accounts`, `follows`, `notifications`, `noteInternalShare`, `session`.
- Misc: `config` (key to JSON, including global settings), `cache`, `plugin`, `fonts` (font files stored as `bytea`).

Notes:

- IDs are auto-increment integers. That is fine for a server-only app but awkward for offline clients that must create notes before syncing.
- `notes.metadata` is a JSON grab-bag (`isIndexed`, `isAttachmentsIndexed`, and more).
- `accountId` is nullable on most tables, and multi-user leaks through in places (the tag list for AI and the vector index are global).
- Outside Postgres, `/app/.blinko` holds uploaded files (`files/`), scheduled `pg_dump` backups (`pgdump/`) and the libSQL vector DB (`vector/`). So a backup needs Postgres **and** that directory, and the two can drift.
- Background jobs: auto-archive Blinkos, DB backup, rebuild embeddings, Memos import, Markdown import, "recommend" (for Hub), AI scheduled tasks.
- Import and export: Memos DB import, Markdown import, a `.bko` backup format. Users report backups that won't import (#927) and data loss after `compose down/up` when the volume wasn't mounted (#1077).

In short: the note body is portable Markdown, which is good, but tags, history, vectors and files are spread across three stores that must be kept consistent.

## 6. API and MCP

### API

- The app talks tRPC; most procedures are mirrored as REST under `/api/v1/*` with Swagger at `/api-doc` and a hosted reference at api.blinko.space.
- Auth is a JWT. Each user can generate an API token in settings. The server accepts it as `Authorization: Bearer` **or as a `?token=` query parameter**, which ends up in proxy and access logs.
- Endpoints are wide: `note/list` takes about 16 filters (type, tag, archived, trash, shared, has file, has link, has todo, date range, AI query, and more). Plain search is `ILIKE`-style `contains` on content; semantic search is a separate flag.
- Extras: webhooks on note and comment events, RSS for public notes.

### MCP server

Present since at least September 2025 (`server/routerExpress/mcp.ts`):

- Transports: streamable HTTP at `/mcp`, legacy SSE at `/sse` + `/messages`. Bearer-token auth on every request; the session must stay with the same user.
- Sessions live in an in-memory map, so they vanish on restart and the server can't run more than one replica.
- 10 tools, reused from the in-app chat agent: `searchBlinko`, `upsertBlinko`, `updateBlinko`, `deleteBlinko`, `createComment`, `webSearch`, `webExtra`, `createScheduledTask`, `listScheduledTasks`, `deleteScheduledTask`.

Why it falls short for Claude Code or Codex:

- The tools were written for the in-app agent, not for external agents. Every schema exposes a `token` field described as "internal use, do not pass!".
- Inside the tools, the call runs as `role: 'superadmin'` whatever the caller's real role.
- `searchBlinko` returns 30 full notes as JSON by default, which floods an agent's context; there is no "get one note" or "list tags" tool, and no MCP resources or prompts.
- Open bugs: scheduled-task tools fail with `accountId: NaN` (#1178, #1052); `deleteBlinko` succeeds but returns a validation error, and `updateBlinko` can't restore from trash (#1179, #1070; fix PR #1250 open).
- `upsertBlinko` logs the full note content to stdout on every call.
- There is no docs page for connecting an MCP client.

Blinko is also an **MCP client**: users can register external MCP servers (stdio, SSE, HTTP) whose tools get added to the chat agent.

## 7. Self-hosting footprint

### Upstream

- Image `blinkospace/blinko`: about 430 MB compressed per architecture (amd64, arm64), `node:20-alpine` runtime. The Dockerfile also `npm install`s `llamaindex`, `@langchain/community`, `sqlite3` and a global Prisma at build time. One user measured the image growing from 500 MB to 1.5 GB and RAM from 200 to 450 MB between two minor versions (#569).
- Every container start runs `prisma migrate deploy` and the seed script before the server starts.
- Requires PostgreSQL (the docs use 14). Port 1111. One data volume, `/app/.blinko`.
- Env vars: `DATABASE_URL`, `NEXTAUTH_SECRET`, `NEXTAUTH_URL`, `NEXT_PUBLIC_BASE_URL`, plus `TRUST_PROXY` and `DISABLE_SECURE_COOKIE`. The `NEXT*` names are left over from Blinko's Next.js days; it now runs Vite + Express. S3, SSO and AI are configured in the UI.
- Install paths: `curl | bash` script, `docker run`, Compose, a Helm chart, PikaPods. Upgrade pain: #819 (won't start after upgrade), #852 (vector migration missing), #1075 (sort order scrambled and backup task gone), #971 (`latest` tag briefly missing).

### theficos (from the manifests)

| Component | Image | Requests | Limits | Storage |
|---|---|---|---|---|
| `blinko` | `blinkospace/blinko:latest`, `imagePullPolicy: Always`, `Recreate`, 1 replica | 50m CPU, 256Mi | 1 CPU, 1Gi | `/app/.blinko` on a 5Gi NFS PV (RWX, `soft,timeo=30,retrans=3`, `/mnt/cluster-data/blinko`) |
| init `wait-for-db` | `postgres:17.11-alpine` (`origin/main`; local `main` still 14) | n/a | n/a | n/a |
| `blinko-postgres` | `postgres:17.11` (since #46 today; was 14) | 50m CPU, 256Mi | 500m CPU, 512Mi | 5Gi hostpath PVC `blinko-postgresql-data-17`; the PG14 PVC is kept as a rollback |
| `ollama-proxy` | `nginxinc/nginx-unprivileged:1-alpine` | 5m CPU, 16Mi | 50m CPU, 32Mi | none |
| (external) shared Ollama | `ollama` namespace, CPU-only | n/a | n/a | n/a |

- **Totals for Blinko itself**: 3 pods, about 105m CPU and 528Mi requested, about 1.55 CPU and 1.5Gi at the limits, plus two persistent locations (Postgres PVC and the NFS directory).
- **Env**: `NODE_ENV=production`, `TZ=Europe/Rome`, `NEXTAUTH_URL=https://blinko.theficos.dedyn.io`, `NEXTAUTH_SECRET`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, and `DATABASE_URL` assembled from them. The SOPS secret holds `NEXTAUTH_SECRET`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`.
- **AI wiring** (from the April design spec): chat on `qwen3.5:cloud` through the local Ollama's cloud proxy, embeddings on local `nomic-embed-text`. Entered by hand in the Blinko UI, so it isn't in Git.
- **The nginx proxy exists only for #1126** (rewriting `/embed` to `/api/embed`). The upstream workaround is to set the Ollama base URL to `.../api`, but then model-list sync breaks, so the proxy is still the cleaner fix.
- **Ingress**: Traefik + cert-manager on `blinko.theficos.dedyn.io`, default-deny NetworkPolicies. Authentik forward auth was removed on 2026-04-13 (Traefik can't reference a middleware in another namespace), so Blinko's own login is the only gate on a public hostname. The open-registration report (#803) is still open; the current code does check the "allow register" flag, so treat it as unconfirmed, but note that the first account to register on an empty DB becomes `superadmin`.
- **Risks in this setup**: `:latest` plus `Always` means a pod restart can pull a new version and run migrations unattended. The libSQL vector file sits on a soft-mounted NFS share, and SQLite on NFS is a known source of lock and corruption trouble.
- Live usage was not measured: this machine's kubectl context points at a different cluster, so all numbers come from the manifests.

## 8. Mobile and Android

- The Android app is Tauri 2: the same React UI running in Android System WebView, plus a small Kotlin plugin for share intents, launcher shortcuts and the voice widget. One universal APK of 93 MB, distributed through GitHub releases.
- It is a thin client: it needs the server reachable to read or write anything. "Offline mode for mobile client" (#898) is the most-reacted open issue; one commenter says they use Joplin instead for exactly this reason.
- WebView-specific bugs dominate: edits lost after the "saved" toast (#948), unreachable text field (#1219), stale tag cache across devices (#1102), "app does not create new notes" (#926), "android app not working" (#690).
- Since 1.5.4, some Android vendors' scanners flag the APK as a trojan (#1193). Same class of problem as quire's Play Protect block.
- iOS is possible through Tauri but not shipped; iOS users run the PWA.

## 9. Weaknesses

**Too many concepts on screen.** The sidebar shows Blinko, Notes, Todo, Analytics, Resources, Archived, Trash, Plugin and Settings, with AI, Review and Hub elsewhere. Around the notes sit comments, notifications, follows, RSS, a "decentralized" Blinko Hub, a plugin marketplace, a global music player (with Spotify login), custom fonts and a 3D shader background. Most of these aren't about capturing or finding ideas.

**Bloat.**
- Server: 87 dependencies, including 13 `passport-*` login strategies and three overlapping AI stacks (Mastra, LangChain, AI SDK, with `llamaindex` added in the image).
- Frontend: 87 dependencies, including `three`, `@react-three/fiber`, `@shadergradient/react`, `echarts`, `mermaid`, `markmap`, `katex`, `swiper`, `canvas-confetti` and `vanilla-tilt`.
- The server also ships Vditor's renderers (graphviz, MathJax, PlantUML, ABC music notation, chemical structures).
- Outcome: a 430 MB image, a 93 MB APK, a 100 MB AppImage.

**Performance.**
- An idle tab uses 20 to 60 % of a CPU core (#1222). The cause: a hidden loading-dots SVG with three infinite `<animate>` elements stays mounted and forces a layout every frame. The one-line fix (#1255) is unmerged.
- Older complaints about stutter and too many animations (#207, #612).
- Embedding rebuilds crash on large libraries (#1232).

**Reliability.**
- The vector table goes missing (#646, #579, #852).
- Upgrades break startup or scramble data (#819, #1075).
- Saves report success and silently fail (#948).
- Resource folders misbehave (#1128, #1243).
- Backups won't import (#927).

**Fragile AI.**
- Every provider has its own open bug (#1126, #1248, #1210, #799, #885, #611).
- Model output is parsed as free text and written into notes.
- "Test connection" checks a chat call, not the embedding call that actually fails.
- AI results arrive as comments, which pile up under notes.

**Security and multi-user leaks.**
- Tokens are accepted in the query string.
- Agent and MCP tools run as `superadmin`.
- Provider API keys sit in plain text in the DB.
- Tag lists and the vector index aren't scoped per user.
- Note content is logged.
- The first registrant becomes admin.
- An external MCP server configured as `stdio` makes the server spawn whatever command was entered in the UI.

**Maintenance.**
- Bus factor of one.
- Since April: 56 issues opened, 8 closed.
- Fixes from contributors wait weeks (#1250, #1253, #1255).

## 10. KEEP, DROP, FIX

### KEEP (copy this)

1. **Capture-first home screen**: one input box on top of a reverse-chronological feed; type, tag, send with one keystroke, card appears immediately.
2. **Inline `#tags` with `/` nesting** (`#project/notes-app`), with autocomplete. Typing a tag where the thought is beats a separate tag picker.
3. **Markdown as the note body**, so notes stay readable and exportable outside the app.
4. **Android share target, launcher shortcuts and a quick-capture widget** (text, link, image, voice).
5. **AI tag suggestions that prefer your existing tags** and file new ones under existing parents, so the tag tree doesn't sprawl. Both automatic and on demand.
6. **Chat with your notes that cites its sources**: retrieved notes come back as clickable references next to the answer.
7. **Writing assist with explicit Accept or Reject**: the AI never rewrites a note silently.
8. **Bring your own model, by role**: separate chat, embedding, vision and audio models; Ollama and any OpenAI-compatible endpoint work as first-class options.
9. **Search that reaches into attachments**: PDF text and image descriptions/OCR are indexed like note text.
10. **Pin, archive, trash (soft delete) and per-note version history.**
11. **Note-to-note links with backlinks.**
12. **Resurfacing old notes** (Daily Review or a random note). Cheap to build and it's what turns a capture pile into ideas.
13. **An MCP server over streamable HTTP with a bearer token, plus a documented REST API.**

### DROP (leave out to stay minimal)

1. **Three note types.** One kind of note. Todos are just Markdown checkboxes, filterable by "has open tasks". No type-specific auto-archive.
2. **Social and federation features**: Blinko Hub, follows, RSS, guest comments, notifications, public comment threads.
3. **Plugin system and marketplace.**
4. **Decoration**: music player and Spotify, analytics dashboard, 3D or shader backgrounds, confetti, tilt effects, the emoji agent, custom font uploads.
5. **Multi-user, roles, internal sharing and 13 OAuth providers.** Single user, with an optional OIDC login later (Authentik already exists on theficos).
6. **The agent that can create, update and delete notes on its own**, scheduled AI tasks, Tavily web search, and the built-in MCP *client* (spawning stdio commands configured from the UI).
7. **AI output posted as comments.** Suggestions should be accepted into the note or dismissed.
8. **Several overlapping AI frameworks.** One thin client covering OpenAI-compatible and Ollama.
9. **A heavy editor with diagram engines** (Mermaid, PlantUML, Graphviz, MathJax and so on). Plain Markdown with good typography.
10. **Desktop Tauri builds, the WeChat bot and the Snap companion.** Web/PWA plus native Android covers the brief.
11. **S3 storage and a second database file.** One volume.

### FIX (pain points to do better)

1. **Offline-first Android** (#898): a local database on the phone, capture and read with no network, sync when back online. Client-generated IDs (UUIDs or similar) so offline notes don't collide.
2. **One store, one transaction**: vectors in the same database as notes (for example `sqlite-vec` or `pgvector`), written with the note, with a per-note "indexed with model X" marker. Nothing like `no such table: blinko` can happen when there is no second store.
3. **Validated structured AI output** (the Ollama-cloud lesson from quire): request JSON against a schema, validate it, retry or fall back, and never write unvalidated model text into a note. Tag suggestions are pending until accepted, or applied only after validation against the tag grammar.
4. **Tags as real entities with safe rename**: escaped, boundary-aware rewrite inside one transaction, and clients refresh their tag list from a sync cursor instead of a stale cache (#1102).
5. **Zero idle cost**: no perpetual animations, a JS bundle budget, an APK budget (#1222).
6. **Small, GitOps-friendly self-hosting**: one container, SQLite by default (Postgres optional), one volume, every setting including AI providers and keys set by env or secret, a pinned image tag, and a backup before migrations run. Aim for one pod on theficos with no sidecar proxies.
7. **A sturdy embedding pipeline**: a resumable background queue with batching and progress that survives restarts and large libraries (#1232, #663). Embed clean chunk text (no timestamps). Detect the dimension by probing the model instead of guessing from its name.
8. **Honest provider checks**: "test connection" runs the real chat **and** embedding calls the app will make (#1210). Native Ollama paths tested in CI against a real Ollama (#1126).
9. **MCP designed for coding agents**: a few well-described tools (`search_notes` returning snippets and IDs, `get_note`, `create_note`, `append_to_note`, `list_tags`, `add_tags`), no internal parameters, read-only and read-write token scopes, stateless HTTP so restarts don't drop sessions, and copy-paste setup docs for Claude Code and Codex.
10. **Secure by default**: no open registration, tokens only in headers, secrets from env rather than plain text in the DB, never log note content.
11. **A trustworthy mobile editor**: show "saved" only after the local DB (and later the server) confirms the write (#948). Test with Gboard and other IMEs.
12. **One search box**: hybrid full-text plus semantic ranking instead of `ILIKE` and a separate "AI search" toggle.
13. **Exit and entry**: export to a folder of plain `.md` files at any time, and batch-import Markdown, including from Blinko itself (#236, #245, #927).

## Sources

- Repo: https://github.com/blinkospace/blinko (files read: `README.md`, `CLAUDE.md`, `dockerfile`, `docker-compose.prod.yml`, `.env.tmpl`, `prisma/schema.prisma`, `server/aiServer/index.ts`, `server/aiServer/aiModelFactory.ts`, `server/aiServer/providers/*`, `server/aiServer/tools/{searchBlinko,createBlinko}.ts`, `server/routerExpress/mcp.ts`, `server/routerTrpc/{note,tag,user}.ts`, `server/lib/helper.ts`, `shared/lib/{types,helper,pathConstant}.ts`, `app/package.json`, `server/package.json`, `app/src/store/baseStore.ts`)
- Docs: https://docs.blinko.space/llms.txt and the pages for Blinko vs Note, Tags, AI Setting, AI Tag, AI Search, AI Post Processing, AI Writing, Daily Review, Android Share, Android Shortcuts, Blinko Hub, Access Token, Install
- Issues: #646, #579, #852, #1126, #1232, #663, #1094, #988, #1210, #1248, #799, #885, #611, #629, #644, #258, #509, #217, #260, #1222 (PR #1255), #207, #612, #569, #898, #948, #672, #1219, #613, #1049, #926, #690, #1193, #1102, #1128, #1243, #692, #803, #819, #1075, #1077, #927, #971, #1178, #1052, #1179, #1070 (PR #1250), #989, #236, #245
- Docker Hub tags API for image sizes; GitHub release 1.8.8 assets for APK and AppImage sizes
- theficos: `theficos-cluster/applications/blinko/*` on `main` and `origin/main` (commits a11ab8f, f7ffecf, f7ee449, 744478a) and the Blinko + Ollama design spec from commit 56f2549
