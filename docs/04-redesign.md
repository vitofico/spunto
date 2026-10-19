# 04 · Redesign: a small core and plugins

Status: approved 2026-10-13 and folded into 01-03; kept as the decision record. §7 lists the edits that were propagated, and where a detail differs, `01-requirements.md`, `02-spec.md` and `03-design.md` are current. Companion: `05-redesign-ux.md`, written in parallel, owns how plugin surfaces and the three themes look. This document owns the mechanism: what is core, how a plugin is built, loaded, shown and switched off, and in what order things ship.

Conventions: decisions continue the register in 02 §17, so new ones are D25 to D31 plus two carried-over ones that never had numbers (D32, D33). Plugin ids are written in `code`. "The host" is the part of the core that loads plugins and gives them services. Nothing is built yet.

---

## 1. What changes and why

The first round of documents drew a tight MVP around one deadline: get off Blinko in about seven weeks. The owner has lifted that. Blinko keeps running on theficos for as long as it takes, and the scope should grow: more AI features, a couple of built-in themes, and room for plugins, with first-party features built as plugins too, so they are easy to add and to switch on and off. The rule that comes with it: basic, minimal, never clutter the space.

The danger is concrete and already documented. Research on Blinko (`research/blinko.md` §9) found a sidebar of Notes, Todo, Analytics, Resources, Archived, Trash, Plugin and Settings, plus a hub, comments, a music player and a shader background; the capture loop got lost among features, and "plugin system and marketplace" went on our drop list for that reason. So the redesign has to add breadth without adding things on screen.

The answer in one sentence: **a small core that is a complete notes app on its own, and every other feature as a plugin that can appear only in a handful of fixed places on screen, each with a size limit the code enforces.**

What that means in practice. Label suggestions are an MVP feature today, woven through the server, sync, the web and Android. Here they become the `label-suggestions` plugin: Python code that hears "a note was saved", asks the AI gateway for labels, and hands the host up to three proposals. The host stores them, syncs them to the phone, and both clients draw them in the one shared suggestion line under the note. Switch the plugin off and the pills vanish on the next sync while the data stays; switch it back on and they return. When Tidy and Link enrichment also have something to propose for the same note, the line still holds one row: extra pills fold into "+2".

| Area | Before (01 to 03) | After |
|---|---|---|
| Scope | A 7-week MVP with a cutover deadline | Core and plugin host first, then plugin waves; cutover when the owner chooses |
| Structure | Each feature built into the server and both clients | A small core plus plugins on one API; first-party plugins use the API a third party would |
| AI features | Labels, related notes, hybrid search; Ask (S); digest and clean-up (C) | Fourteen AI plugins, each with its own switch, routing and status line |
| AI and text | "No generated prose in notes" | AI still never changes the owner's text without an accept; plugins may write *new* notes, visibly marked (D29) |
| Look | One theme, five accents | A theme engine and three built-in theme packs |
| Clients | Every feature hand-built twice | Clients render plugin output through fixed slots in a closed component vocabulary; no plugin code runs on a client |
| MCP | Nine fixed tools | Seven core tools, plus a budgeted set of tools from enabled plugins |
| Non-goals | "Plugins or a theme marketplace" | Plugins and themes are in; a marketplace, installing code from the UI and plugin code on clients stay out |

**What holds unchanged:** Python and FastAPI in one process; one SQLite file with FTS5 and `sqlite-vec`; one container, one volume; native Kotlin and Compose on Android, offline-first with the outbox and merge-first sync; React on the web, online with safe drafts; every model answer validated and failing closed; MCP safety (additive writes, scoped tokens, provenance, no side doors); on-device dictation; export, backups and the design language. Where the plugin model forces a change to any of these, §3.13 and §7 say so.

---

## 2. Core versus plugins

### 2.1 The test

Something is core when a fresh install with every plugin off would be broken or unsafe without it, or when it is a service several plugins need that must be enforced in exactly one place (privacy, validation, sync). Everything else is a plugin. The check that keeps the line honest: **with every plugin off, the app is still a complete, calm notes app**: capture, labels, keyword search, offline sync, agents over MCP, export.

### 2.2 What the core contains

| Area | Core | Why it cannot be a plugin |
|---|---|---|
| Notes | Markdown notes; create, edit, pin, archive, trash; revisions and the history UI; merge and conflicted copies; the private flag; checkboxes ticked in place; source and provenance chips | The data every plugin reads; history is the undo for anything an agent or plugin does |
| Labels | Inline `#labels`, the shared grammar, autocomplete, the tree, rename, merge, remove | Labels are the text (D4); rewrites must be transactional and identical on three platforms |
| Search | Keyword search with filters, and the blending point where search sources plug in | Must work with AI off and offline on Android |
| Capture | Composer, Android share target and selection action, launcher shortcuts, web drafts | Capture is the product (J1) |
| Sync and auth | Android replica, outbox, push and pull, change long-poll, reconcile; owner login, sessions, device tokens, access tokens, QR pairing | Every slot depends on them |
| Operations | Attachments, Markdown export, backups and restore, status page, health checks | "Never lose a thought" and "boring to host" are not optional |
| Agents | Seven MCP tools and the `note://` resource (§9 item 5) | Agents must work on a bare install |
| Surfaces | The slots themselves, the shared Review screen, Settings → Plugins, the theme engine and three themes | Plugins must not draw their own surfaces |
| Plugin host | Loader, manifest validation, lifecycle, permissions, and the services below | |

Shared services, reachable only through the host API:

| Service | What it does | What it enforces |
|---|---|---|
| AI gateway | Provider profiles; structured chat, embeddings, transcription and vision; per-plugin routing and rate shares | Schema and meaning validation with fail-closed (02 §7.2) on every call; no free-text completion method exists; private notes are refused; calls are counted per plugin |
| Vector index | Chunks and `sqlite-vec` tables, as 02 §7.3 | Built and kept current only while an enabled plugin requires embeddings; one index for all plugins; private notes never embedded |
| Jobs and scheduler | The queue from 02 §7.8 with leases, coalescing and backoff, plus schedules | Plugin work never runs in a request; a disabled plugin's jobs pause |
| Plugin storage | Key-value, records, note annotations, searchable text (§3.3) | Namespaced by plugin id; inside the backup; purgeable per plugin |
| Guarded fetch | Outbound HTTP for plugins (D30) | Blocks private and internal addresses, re-checks on redirect, caps size and time, sends no cookies |
| Note access | Read, query, create, update-own, suggest | No call edits the owner's text; plugin writes carry the plugin's own credential, so provenance reuses `created_by` and `updated_by` |

### 2.3 What left the old MVP

Label suggestions (AI-3), semantic ranking (SRC-2), related notes (AI-6) and Ask (AI-8) become the four AI plugins that switch on when AI is configured, so an AI install has exactly what the old MVP had. The Unlabeled view (LAB-5) moves into `label-suggestions`, the Open tasks view (CAP-8) into `tasks`, the "From agents" view (MCP-7) into `agent-inbox` (the "via Claude Code" chip stays core), the Blinko importer (DATA-2) into `blinko-import`, dictation (AND-15) and the widget and tiles (AND-9, AND-10) into Android device plugins. The private flag (AI-9) and revision history (CAP-10) move the other way, from v1.1 into the core, because the AI gateway and the undo story depend on them.

---

## 3. Plugin architecture

### 3.1 Anatomy

A plugin is a folder: `plugin.toml` (the manifest, data only), an optional Python package (server code) and optional tests. There are three kinds:

- **server**: manifest plus optional Python, running in the server process;
- **device**: listed in the server's catalog like any plugin, but its code is compiled into the Android app (or the web bundle) because it needs the microphone, the launcher or the Quick Settings panel (§3.6);
- **theme**: manifest plus a token file, no code (§5).

The host reads the manifest without importing any of the plugin's code, so the owner sees what a plugin will do before a line of it runs. TOML because Python reads it with the standard library and it allows comments.

### 3.2 The manifest

The real manifest of the weekly digest:

```toml
# server/src/spunto/plugins/digest/plugin.toml
id           = "digest"            # [a-z][a-z0-9-]{1,23}; also the route prefix and MCP tool prefix
name         = "Weekly digest"
version      = "1.0.0"
api          = "0.4"               # host API it targets (§3.10)
kind         = "server"            # server | device | theme
category     = "recall"            # capture | organize | recall | develop | agents | data | look
description  = "Each week, a short note on what you captured: themes, open questions and threads worth continuing, each linked to its notes."
entry        = "spunto.plugins.digest:plugin"
requires     = ["ai.chat"]         # unmet: listed as "Needs a chat model" and contributes nothing
default      = "off"               # first-party only: on | on-with-ai | off
data_version = 1                   # bump to run the plugin's own data migration (§3.3)
permissions  = ["notes.read", "notes.create", "ai.chat", "schedule", "mcp"]

[[contributes.commands]]
id    = "write-now"
title = "Write this week's digest"
where = ["palette"]                # palette | slash | note-actions; nothing else exists

[[contributes.views]]
id     = "digests"
title  = "Digests"
filter = { source = "plugin:digest" }   # the closed filter language of §3.5; runs on the phone too

[[contributes.mcp_tools]]
name        = "latest"             # listed to agents as digest_latest
description = "The latest weekly digest as Markdown, with the IDs of the notes it cites."
read_only   = true

[[contributes.schedules]]
id      = "weekly"
weekday = "{day}"                  # placeholders read from the settings below
time    = "{time}"

[settings]                         # a JSON Schema subset; becomes the plugin's settings page
type = "object"

[settings.properties.day]
type    = "string"
title   = "Day"
enum    = ["monday", "friday", "sunday"]
default = "sunday"

[settings.properties.time]
type    = "string"
format  = "time"
title   = "Time"
default = "18:00"

[settings.properties.min_notes]
type    = "integer"
title   = "Skip weeks with fewer notes than"
minimum = 1
default = 5

[settings.properties.leave_out]
type    = "array"
title   = "Leave out"
items   = { type = "string", format = "label" }   # format "label" renders a label picker with autocomplete
default = []
```

On enable, the host turns the permissions into sentences, with the destination filled in from the plugin's AI route: "Reads your notes, never private ones · Writes new notes marked 'via Weekly digest' · Sends a week of notes to the chat model at ollama.com · Runs every Sunday at 18:00 · Adds one tool for agents."

Settings fields marked `"x-secret": true` (a bot token, an IMAP password) are never stored in the database or shown in a form: they come from `SPUNTO_PLUGIN_<ID>_<KEY>` or its `_FILE` variant, as every secret does today (HOST-2), and the page says "Set in the environment". Fields marked `"x-scope": "device"` (dictation language) live on each phone and do not sync.

### 3.3 The host API

The server half of the digest, to show the shape of the API:

```python
# server/src/spunto/plugins/digest/__init__.py
from spunto.host import Plugin, Ctx, Output, field

plugin = Plugin("digest")

class Digest(Output):                       # the gateway validates shape and meaning
    title: str = field.text(max_len=80)
    body: str = field.markdown(max_len=4000)
    cites: list[str] = field.note_ids()     # must be a subset of the notes sent

@plugin.schedule("weekly")
@plugin.command("write-now")
async def write(ctx: Ctx) -> None:
    week = await ctx.notes.query(created_within_days=7)   # private and machine-written notes already excluded
    if len(week) < ctx.settings["min_notes"]:
        return
    d = await ctx.ai.chat(Digest, prompt=PROMPT, notes=week)  # raises on failure: nothing is written
    await ctx.notes.create(f"# {d.title}\n\n{d.body}", cites=d.cites)

@plugin.mcp_tool("latest")
async def latest(ctx: Ctx) -> dict:
    note = await ctx.notes.latest(source="plugin:digest")
    return {"id": note.id, "body": note.body} if note else {"found": False}
```

| Area | Calls | Rules |
|---|---|---|
| Hooks | `note.saved`, `note.trashed`, `note.purged`, `labels.changed`, `suggestion.decided`, `attachment.added`, `plugin.enabled` | Delivered after the commit, as coalesced jobs (ten saves in a row are one call). **There is no hook before a save**, so no plugin can slow a save down or rewrite text on its way in. |
| Notes | `get`, `query`, `latest`, `create`, `update_own`, `suggest`, `annotate`, `set_search_text` | `create` makes a new note under the plugin's credential; `update_own` edits only notes this plugin created (importer re-runs); changes to the owner's notes exist only as suggestions |
| AI | `chat(OutputModel, …)`, `embed`, `transcribe`, `vision` | Structured output only; the gateway wraps note text in data delimiters, routes by the plugin's profile and enforces the private flag |
| Vectors | `nearest(note_id or text, k)` | Only for plugins that require `ai.embed` |
| Jobs | `@job`, `@schedule`, `enqueue` | 02 §7.8 semantics; concurrency 1 per plugin unless declared |
| Storage | `kv`, `records` | Below |
| Network | `fetch` | Guarded; only with `net.fetch` or a declared `net.out:<host>` |
| Attachments | `read`, `write` | Content-addressed as in the core |
| Routes | `@route` | Mounted under `/api/v1/plugins/<id>/` by one dispatcher that answers 404 while the plugin is off; session and device credentials by default, access tokens only if declared |
| MCP | `@mcp_tool` | Below |
| Misc | `settings`, `clock`, `log` | `log` takes a code and counts or IDs, never free text, so note content cannot reach the logs |

**Storage, tables and migrations.** Plugins get no tables of their own and run no SQL (D28). The host keeps their data in six core tables, created by ordinary core migrations:

| Table | Holds | Syncs to Android |
|---|---|---|
| `plugin` | id, version, enabled, settings JSON, `data_version`, state, last error | as the manifest (§3.5) |
| `plugin_kv` | small per-plugin state ("last digest week") | no |
| `plugin_record` | JSON documents keyed by plugin, collection, id, with optional `note_id` and `key` columns indexed | no |
| `suggestion` | `label_suggestion` generalised: plugin, note, note version, action, payload, status, `change_seq` | yes |
| `annotation` | per-note output for the panel, the meta line, the daily slip and view membership, capped at 4 KB each | yes |
| `note_text` | searchable text a plugin attaches to a note (image text, a transcript, a page title) | no; feeds the core keyword index and the embedder |

A plugin's data migration is a function, not DDL: when `data_version` rises, the host takes the usual pre-migration snapshot and runs `@plugin.migrate(old, new)` as a resumable job over that plugin's own rows. Uninstall or "Delete this plugin's data" is one `DELETE … WHERE plugin_id = ?` per table, plus tombstones for synced rows.

**MCP tools.** Plugin tools are listed as `<id>_<tool>` (`digest_latest`), not `<id>.<tool>`: the MCP specification allows dots, but OpenAI's function names allow only letters, digits, `_` and `-`, so a dot depends on every client rewriting names, and an underscore needs no rewriting. Names stay under 40 characters, because Claude Code prefixes them with `mcp__<server>__`. Each plugin may add at most three tools, each with annotations and a description under 300 characters; answers pass through the core serializer and its 8,000-token ceiling. The list is computed per request from the token's scope and the enabled plugins, using the MCP SDK's low-level `list_tools` and `call_tool` handlers instead of FastMCP's static registry; that also delivers 02 §8.2's rule that read tokens never see write tools. Stateless HTTP has no server push, so an agent sees a newly enabled plugin's tools from its next session on. The owner can hide any plugin tool in Settings → Agents.

### 3.4 Slots and the component vocabulary

A **slot** is a fixed place on screen where plugin output may appear, with a budget. Plugins choose slots; they never choose positions, colours, icons or sizes.

| Slot | Web | Android | Budget | Data | Offline on Android |
|---|---|---|---|---|---|
| Suggestion line | Under an entry and in the note | Same | One row for all plugins; overflow folds into "+N"; at most 3 pending per plugin per note; the host ranks across plugins | stored | Shown and decidable |
| Review | One quiet line, "5 suggestions · Review", only from 3 pending; a review screen | Same | One line; one screen for every plugin; at most 3 label-level proposals per plugin per week | stored | Note-level items yes; label-level no |
| Note panel | Right margin (≥ 1280 px) or below the note | "About this note" sheet | One section per plugin; collapsed by default; order set by the owner; at most 10 items | stored or live | Stored sections yes; live ones absent |
| Entry meta token | Meta line | Same | One token per entry across all plugins, picked by the owner's plugin order | stored | Yes |
| Daily slip | Top of the timeline | Top of the timeline and the widget | One item a day across all plugins; dismissing is final for the day | stored | Yes |
| Commands | ⌘K palette, composer `/` menu | Note action sheet, composer `/` menu | At most 3 per plugin; no toolbar, FAB or header slot exists | static | Online-only commands are absent |
| Command result | Panel inside the palette | Bottom sheet | Vocabulary only; "Save as note" is the way to keep it | live | Absent |
| Views | "Views" group in the rail | "Views" group in the labels sheet | One per plugin; the owner can hide it | static filter | Yes, the filter runs locally |
| Settings | Settings → Plugins → name | Same | One generated page per plugin | static | Read-only |
| Search source | "≈ similar meaning" on hits | Same | No surface of its own | live | Absent; keyword search runs locally |
| New notes | Timeline, with a "via Weekly digest" chip | Same | Unattended output (schedules, hooks) capped at 5 notes a day per plugin; owner-started commands uncapped | notes | Yes |
| MCP tools | n/a | n/a | 3 per plugin (§3.3) | n/a | n/a |

Two slots are additions to the brief: the **daily slip** (resurfacing and the widget need a home that is not a note), and **Review**, which already exists in 03 (L3, A8) and becomes the one place for proposals that do not belong to a single note: label merges, emerging topics, duplicate pairs. **Command result** and **search source** make explicit two places the brief implied (Ask's answer, semantic ranking). Background-only plugins (Email-in, Webhooks out) use only Settings.

Everything a plugin shows is built from a closed **component vocabulary**, versioned as `ui = 1`:

| Component | Carries | Web | Android | Older client falls back to |
|---|---|---|---|---|
| `text` | Plain text, at most 280 characters | Sans | Sans | itself |
| `markdown` | At most 4,000 characters | The server's sanitized HTML (02 §9.4) | The core `commonmark` renderer | `text` |
| `notes` | Up to 10 note IDs, each with an optional reason | Note references in the core style | Resolved from Room, so they work offline | `text` |
| `pill` | Suggestion id, a label of at most 40 characters, an action | Dashed pill | Dashed pill | dropped |
| `command` | Command id, arguments, a title of at most 24 characters | Palette row or button | Sheet row | dropped |
| `form` | A JSON Schema subset (string, enum, boolean, integer, number, time, string list) | Generated | Generated | "Open on the web" |
| `diff` | Precomputed hunks and the base version | Side by side or inline | Stacked | `text` |

Suggestions can only do what the core knows how to do, so Android can apply them offline and no plugin invents a new kind of edit:

| Action | Effect when accepted | Offline on Android |
|---|---|---|
| `add_label` | Appends the `#label` (02 §4.3) | Yes |
| `set_title` | Prepends `# Title` | Yes |
| `append_text` | Appends text, for example a link to another note | Yes |
| `replace_body` | Replaces the body, previewed as a `diff`; based on the suggestion's note version, so it merges or makes a conflicted copy like any edit; superseded when the note changes first | Yes |
| `merge_notes` | Opens the core Compare view; accepting appends one note to the other and trashes it | Yes |
| `label_change` | Rename or merge labels through the core impact dialog | No: needs the server |
| `open` | Opens a note or a view | Yes |

### 3.5 How clients render plugins, and Android offline

- **The manifest endpoint.** `GET /api/v1/plugins/manifest` returns the enabled plugins with their static contributions (commands, views, panel sections, settings schemas, themes) and an ETag. `/meta` and every sync pull carry `plugins_rev`, so a client refetches only when something changed; the change long-poll wakes on a plugin toggle like on a note change. Android caches the manifest in DataStore, so slots, views and settings render offline.
- **Stored output** (suggestions, annotations, plugin-written notes) travels in the ordinary pull, ordered by `change_seq`. Clients hide the output of disabled plugins locally, which is why disabling is instant and re-enabling needs no backfill.
- **Live output** (Related notes, Ask answers, search sources) is fetched from the plugin's route when needed. Offline it is absent, never shown as broken (03 principle 9).
- **Views are filters, not code.** A view's filter uses a closed language over core fields: labels, no labels, `source`, open tasks, pinned, archived, a date window, and `member` (a set the plugin maintains as synced annotations). The server turns it into SQL and Android into a Room query, so every view works on a phone in airplane mode.
- **Commands** that call a server route are absent while offline. Accepting a suggestion offline queues `suggestion.decide` in the outbox; the phone shows the expected result at once and the server applies the action against the latest version, which never conflicts for labels and appends and merges for `replace_body`.
- **An older Android build** that meets a component newer than its vocabulary renders the declared fallback; the server keeps serving the build one release behind (02 §5.4).
- **Web** draws the same vocabulary with a handful of small components and computes nothing heavy (diffs arrive precomputed), which keeps the 150 KB budget.

### 3.6 Device plugins

Some features need the phone itself: the microphone (`dictation`, and the recording half of `voice-memos`), the launcher and the Quick Settings panel (`quick-capture`), later the notes role (AND-14). These ship as Gradle modules (`:plugins:dictation`, `:plugins:quick-capture`) compiled into the APK, reviewed like any app code, and listed in the server's catalog with `kind = "device"`. The owner switches them with the same toggle as any plugin; the phone reads the state from the cached manifest, so the mic works offline and before the first sync, when the APK's own default applies. Device plugins may report per-phone availability ("No on-device recognizer on this phone") and keep per-phone settings on the phone.

They are the one exception to "plugins never add buttons": the mic in the composer's action bar, the widget and the tile are native surfaces only compiled-in, reviewed code may use. Nothing is ever loaded at runtime on Android, which keeps the app clear of the dynamic-code-loading pattern that Play Protect flags (Quire's issue 108).

### 3.7 Lifecycle

| State | Meaning | What the owner sees |
|---|---|---|
| Available | Shipped or installed, off | A row in Settings → Plugins with a switch |
| Waiting | On, but a requirement is unmet (no chat model, no embeddings, no attachments) | "Needs a chat model", and no contributions |
| Enabled | On and running | Its slots, views, commands, settings page, tools |
| Disabled | Switched off | Nothing; data, settings and pending jobs are kept |
| Failed | Load error, or a circuit breaker opened after 5 consecutive job failures | One line on the plugin's page and the status page; the rest of the app is untouched |
| Removed | Third-party code taken out of the plugins directory | "Delete this plugin's data?" with a count |

Enabling runs no restart: the host validates the manifest, shows the permissions, imports the code if needed, registers hooks, routes and tools, runs `plugin.enabled` (a backfill, for example) and bumps `plugins_rev`. Disabling unregisters them and pauses the jobs. Python cannot unload an imported module, so a disabled plugin's code stays in memory until the next restart; it simply receives no more calls, and anything it started through the host is cancelled. A plugin that fails to import never stops the server from starting; bad core configuration still does (HOST-2).

The toggles live in the database and can be changed from the web or the owner's phone, never from an access token. For GitOps, `SPUNTO_PLUGINS="+digest,-telegram"` pins listed plugins on or off and locks their switches with "Set in the environment", the same rule the owner password follows (02 §9.2).

### 3.8 Permissions and trust

Permissions are a short closed list: `notes.read`, `notes.create`, `notes.update_own`, `suggest`, `annotate`, `search.text`, `ai.chat`, `ai.embed`, `ai.transcribe`, `ai.vision`, `net.fetch`, `net.out:<host>`, `attachments.read`, `attachments.write`, `schedule`, `routes`, `mcp`, and for device plugins `mic`. Three rules are enforced by the host for every plugin:

1. **A plugin that can send anything off the instance never sees private notes.** Any `ai.*` or `net.*` permission makes the note reader skip them.
2. **"Where note text goes" is computed, not declared.** It comes from the plugin's AI route and its network permissions and is shown on enable and on the plugin's page ("Sends note text to: the chat model at ollama.com").
3. **Plugins propose; only the owner edits.** The API has no call that changes an existing note the owner wrote.

Trust is stated plainly. First-party plugins ship in the image and are reviewed with the core. A third-party plugin is Python running in the same process (D25): installing one means trusting it like Obsidian's community plugins. The permission list is a contract for honest plugins and a review aid, not a sandbox: a malicious plugin could open the database file or make its own HTTP calls. The only hard limits on such code are outside the process, such as a Kubernetes egress policy.

### 3.9 Distribution and install

- **First-party** plugins live in the server package (`spunto/plugins/<id>/`) and in the Android and web codebases for device plugins. They ship with every image; the owner only switches them.
- **Third-party** server plugins are installed by whoever runs the server, never through the UI (D27): a pure-Python folder dropped into `SPUNTO_PLUGINS_DIR` (default `/data/plugins`), or a package installed in a derived image (`FROM ghcr.io/vitofico/spunto:<version>` plus `uv pip install …`), found through the `spunto.plugins` entry-point group. Runtime installs are impossible anyway on theficos, whose pods run with a read-only root filesystem.
- **Themes** install the same way, as folders, and also need no code.
- There is no catalog server and no marketplace. A list in the docs is enough for one-owner instances.

### 3.10 Versioning the plugin API

The host API, the manifest format and the component vocabulary carry versions. The API stays at `0.x` while the first-party waves exercise it (§6): breaking changes are allowed, and because first-party plugins live in the same repository, each one lands in the same pull request as the API change. API `1.0` is frozen before third-party loading opens (M8). After that, a major version is supported alongside the previous one for at least one release, and additions within a major are optional fields and new calls only. The vocabulary grows only by adding components that declare a fallback to an older one, so an Android build one release behind always renders something sensible.

### 3.11 Testing a plugin

The host ships a pytest harness, `spunto.host.testing`: a temporary SQLite file, the fake AI provider from 02 §7.2 with all its failure modes, a fake clock for schedules and a fake fetch. Every plugin, first-party or not, gets the same contract suite for free:

- the manifest is valid and inside every budget;
- with AI off, enabling it leaves it Waiting with no errors and no contributions;
- under each fake-provider failure, no note, suggestion or annotation changes;
- disabling it mid-job leaves nothing new visible, and its data survives;
- "Delete this plugin's data" leaves no row with its id;
- every payload validates against the vocabulary schemas, and every MCP answer stays under the token ceiling.

`spunto plugin check <dir>` runs the static half of this outside the test suite. On the client side, `spec/fixtures/vocabulary/` holds golden payloads that the web (vitest) and Android (Compose screenshot tests) both render, the same pattern as the label-grammar fixtures.

### 3.12 How the clutter contract is enforced in code

Convention erodes; these checks do not.

1. **Closed manifest schema.** Unknown slots, fields or permissions refuse the plugin at load. There is no field for a toolbar button, a FAB, a colour, an icon, a badge or a notification, so none can be requested.
2. **Budgets in the validator.** Three commands, one view, one panel section, one meta-token kind, three MCP tools per plugin; checked at load and in `spunto plugin check`. First-party plugins get no exemption.
3. **Budgets in the write APIs.** `suggest` refuses a fourth pending suggestion per plugin per note; the daily slip accepts one item a day; unattended `notes.create` stops at five a day per plugin; annotations over 4 KB are refused.
4. **Budgets in the serializer.** The note serializer emits at most one meta token per entry and ranks suggestions across plugins; clients render one row with "+N".
5. **Payload schemas.** Every component validates with `additionalProperties: false` and length caps before it is stored or sent.
6. **One renderer per platform.** Clients render only the vocabulary, panel sections always start collapsed (there is no "expanded" field), and the owner's order wins.
7. **No push channel.** The API has no notifications, badges or sounds, which keeps 03's "quiet until spoken to" true for every plugin.
8. **CI.** The contract suite runs for every first-party plugin on every pull request.

### 3.13 Stress test: where the brief does not hold as stated

| The brief says | It does not hold because | This proposal |
|---|---|---|
| Summaries are saved as notes | One summary note per long note would double the timeline | Summaries live in the note panel; only digests, saved answers, expansions and translations become notes |
| Views appear when the plugin is on | A view computed by plugin code cannot run on an offline phone | Views are declarative filters plus synced membership (§3.5) |
| Plugins only add pills, sections, commands, views | Resurfacing and the widget, label merges and duplicate pairs, Ask's answer and semantic ranking have no slot | Daily slip, Review, command result and search source (§3.4) |
| Plugin output is free-form | The phone must apply suggestions offline | A closed action list in the core (§3.4) |
| Plugins never add buttons | Dictation's mic, the widget and the tile are buttons | Device plugins only: compiled in and reviewed (§3.6) |
| MCP tools named `<id>.<tool>` | Dots are outside OpenAI's function-name rule | `<id>_<tool>` |
| MCP core is search, get, create, append | A bare install could not label or archive from an agent | `list_labels`, `set_labels`, `archive_note` stay core (§9 item 5) |
| Enable and disable without restart | Python cannot unload a module | Disabling stops all calls and cancels host-managed work; memory is freed on the next restart |
| Install third-party plugins | Install from the UI would make a stolen session into code execution, and the root filesystem is read-only | Operator-only install (D27) |
| Plugin settings come from a JSON Schema form | Secrets must stay out of the database | `x-secret` fields come from the environment |
| AI needs chat, embed, transcribe | Image text needs a model that reads images | A fourth capability, `vision` |
| "With AI configured, a curated set turns on" | An instance may have embeddings and no chat, or the reverse | Each plugin turns on when its own `requires` are met |
| Link enrichment fetches shared URLs | An agent's `source_url` would make the server fetch anything, a side door MCP-8 forbids | Fetch only for the owner's own captures, unless the owner runs the command (D30) |
| Email-in and Telegram capture | Strangers' text becomes something agents read | Sender allowlist, plus origin marking in MCP answers (D32) |
| Outputs are notes | Machine text would feed later AI calls, a digest of digests | Machine-written notes are excluded from AI inputs until the owner edits them (D29) |
| Embedding service is shared | Today the embedding URL defaults to the chat URL; more plugins make the leak bigger | Explicit profile per capability, nothing inherited (§9 item 3) |

---

## 4. Plugin catalog

"Text goes to" names the recipient beyond the server itself. "Chat", "embed", "transcribe" and "vision" mean the AI profile that capability is routed to for that plugin (D16). Milestones are in §6.

| Plugin | What it does | Slots | Permissions | AI | Text goes to | Default | M |
|---|---|---|---|---|---|---|---|
| **Capture** | | | | | | | |
| `dictation` (device) | Speech to text in the composer, on the phone, Italian and English | Composer mic | `mic` | none | Phone only (online recognizer only with consent, D23) | on | M5 |
| `quick-capture` (device) | Widget and Quick Settings tiles, each with "Dictate" | Widget, tiles | none | none | Nowhere | on (inert until placed) | M5 |
| `voice-memos` | Keeps the recording and transcribes it on the server; transcript offered as a diff | Suggestion line, panel, command | `attachments.*`, `ai.transcribe`, `suggest` | transcribe | Audio to transcribe | off | M7 |
| `link-enrichment` | For a shared link: page title, short summary, label ideas | Suggestion line, panel ("Source"), meta token ("23 min read") | `net.fetch`, `ai.chat`, `suggest`, `annotate`, `search.text` | chat (optional) | The linked site (a GET only); page text and note to chat | off | M7 |
| `image-text` | Reads text in images and describes them, so search finds them | Panel ("In this image") | `attachments.read`, `ai.vision`, `search.text` | vision | Images to vision | off | M7 |
| `tidy` | Cleans up dictated or pasted text without changing meaning | Command, suggestion line (diff) | `notes.read`, `suggest`, `ai.chat` | chat | Note to chat | off | M6 |
| `email-in` | Mail from allowlisted senders to a mailbox becomes a note | Settings only | `net.out:<imap host>`, `notes.create` | none | Nowhere new (the mail server has it already) | off | M8 |
| `telegram` | Messages to the owner's bot become notes | Settings only | `net.out:api.telegram.org`, `notes.create` | none | Nowhere new (Telegram has it already) | off | M8 |
| **Organize** | | | | | | | |
| `label-suggestions` | Up to three labels after a save, from the owner's vocabulary; Unlabeled view; auto-accept for existing labels | Suggestion line, Review, view | `notes.read`, `suggest`, `ai.chat`, `ai.embed` | chat (+ embed for neighbours) | Note to chat | on with AI | M4 |
| `title-summary` | For long notes only (300 words and up): a proposed title and a short summary | Suggestion line, panel ("Summary") | `notes.read`, `suggest`, `annotate`, `ai.chat` | chat | Note to chat | off | M6 |
| `label-gardener` | Proposes merging near-duplicate labels and new labels for clusters of unlabeled notes (merges the brief's Emerging topics); at most three proposals a week (the Review budget, §3.4) | Review | `notes.read`, `suggest`, `ai.chat`, `ai.embed` | chat, embed | Label names and note samples to chat | off | M6 |
| `duplicates` | Finds notes that say the same thing, to compare and merge | Review | `notes.read`, `suggest`, `ai.embed` | embed | Nowhere new (uses the index) | off | M6 |
| `links` (added) | Links between notes and a Backlinks section; "Link to note…" | Panel ("Backlinks"), command | `notes.read`, `annotate`, `suggest` | none | Nowhere | off | M6 |
| `tasks` | Collects notes with open checkboxes into a Tasks view | View, meta token ("2 of 5 done") | `notes.read`, `annotate` | none | Nowhere | off | M2 |
| **Recall** | | | | | | | |
| `semantic-search` | Ranks by meaning in the one search box; MCP search goes hybrid | Search source | `ai.embed` | embed | Every non-private note to embed | on with AI | M4 |
| `related` | Up to five related notes; `related_notes(id)` for agents | Panel ("Related"), MCP tool | `ai.embed`, `mcp` | embed | Nowhere new (uses the index) | on with AI | M4 |
| `ask` | Cited answers from the notes; "Nothing in your notes covers this" when so | Command, command result | `notes.read`, `ai.chat`, `ai.embed`, `notes.create` | chat (+ embed) | Up to 8 notes per question to chat | on with AI | M4 |
| `resurface` | One older note a day, spaced so it is not seen too often; "on this day" when one exists | Daily slip (and the widget) | `notes.read`, `annotate`, `schedule` | none | Nowhere | off (D31) | M2 |
| `digest` | A weekly note of themes, open questions and threads, citing its notes | New notes, view, command, MCP tool | `notes.read`, `notes.create`, `ai.chat`, `schedule`, `mcp` | chat | A week of notes to chat | off | M6 |
| **Develop** | | | | | | | |
| `develop` | Expands a note into a linked follow-up note: questions, next steps, angles | Command, new note | `notes.read`, `notes.create`, `ai.chat` | chat | Note and its related notes to chat | off | M6 |
| `translate` | Italian to English and back, shown as a result, savable as a linked note | Command, command result | `notes.read`, `notes.create`, `ai.chat` | chat | Note to chat | off | M6 |
| **Agents** | | | | | | | |
| `agent-inbox` | "From agents" view and "Mark reviewed" | View, command | `notes.read`, `annotate` | none | Nowhere | off; offered when the first token is made | M2 |
| `webhooks` | Sends note events to URLs the owner configures (n8n, Hermes) | Settings only | `notes.read`, `net.out:<configured>` | none | The configured URLs (never private notes) | off | M7 |
| **Data** | | | | | | | |
| `blinko-import` | Imports from a running Blinko; CLI and a settings page with dry run | Settings, CLI | `net.out:<configured>`, `notes.create`, `notes.update_own`, `attachments.write` | none | Nowhere (it only reads Blinko) | off | M2 |
| `markdown-import` | Imports a zip or folder of Markdown: Obsidian, Bear, Memos, our own export | Settings, CLI | `notes.create`, `notes.update_own`, `attachments.write` | none | Nowhere | off | M7 |

Merged and cut, with reasons: **Emerging topics** merged into `label-gardener`, since both end as label proposals in Review on the same weekly rhythm, and two switches for one idea is settings clutter. **Obsidian export** cut, because core export already opens as an Obsidian vault (DATA-1); import stays as `markdown-import`. **On this day** folded into `resurface`. **Widget and tile** merged into `quick-capture`. **Note links** added because `develop` and `translate` create linked notes, and links that cannot be followed back are half a feature.

---

## 5. Themes

05 decides what the three themes look like and which tokens a theme may set; this section is the mechanism. The current warm-paper theme is one of the three.

**What a theme pack contains.** A folder with `plugin.toml` (`kind = "theme"`, id, name, version, `tokens = 1`) and `theme.toml` with three parts: a `light` and a `dark` set of colour roles (the roles of 03 §11.3: `paper`, `raised`, `sunken`, `ink` to `ink-3`, `rule`, `field`, `accent` family, `highlight`, `danger`, `warn`, `scrim`, state layers); and an optional accent set, each accent with its light and dark values (the picker shows an accent row only for packs with more than one). Themes cannot change typefaces: the serif marks the owner's words (05, U2). Any token outside the themeable set is ignored, so a theme cannot change layout, spacing or motion unless 05 makes those themeable. A theme loads no fonts, because Android and the web's `font-src 'self'` policy would otherwise need runtime font downloads.

**Validation in code.** At load, the host computes the contrast pairs 03 §10 promises (`ink` and `ink-3` on `paper`, `sunken`, `raised`; `accent` on `paper` and on `accent-soft`; `field` on `paper` at 3:1) for both modes and every accent. A theme that misses one is refused, naming the failing pair. A later token contract (`tokens = 2`) adds tokens only with a rule deriving them from existing ones, so older themes keep working.

**On the web.** The three built-in themes are compiled into the bundle as CSS custom properties under `:root[data-theme=…][data-mode=…]`, a few kilobytes each. The server writes the owner's theme, accent and mode as attributes on `<html>` when it serves `index.html`, so there is no flash of the wrong theme. A third-party theme arrives as a stylesheet generated by the server at `/api/v1/themes/<id>.css`, which fits the existing `style-src 'self'` policy; switching swaps one `<link>` and one attribute, with no transition (03 §9.2).

**On Android.** The three built-ins are generated into `:core:design` at build time from the same files (the 03 §11.9 generator, extended to many themes). A third-party theme's tokens arrive in the cached manifest and are mapped at runtime into the Material 3 `ColorScheme` and the app's token object; they are only values, so nothing executes. Pure black stays an Android option derived from any theme's dark set, and dynamic colour still overrides surfaces and accent when the owner turns it on.

**Which theme applies.** Theme and accent are an instance setting that syncs, and each device may override it; light, dark or system stays per device. 05 decides how that reads in Settings.

---

## 6. Milestones

No dates: Blinko keeps running, so each milestone ships when it is good. Each one ends in something the owner uses every day and can keep using if the next one waits.

| | Ships | Daily use from here | Done when |
|---|---|---|---|
| **M0 Skeleton and spikes** | Monorepo, CI, image, config validation, migrations, backup and restore | none yet | The 02 §16 spikes pass, plus: a per-token, per-plugin MCP tool list through the SDK's low-level handlers, seen correctly by Claude Code and Codex; enabling and disabling a test plugin without restart; dictation on the owner's Android 16 phone (D22). The name's availability is checked. |
| **M1 Core server and agents** | Notes, labels, revisions, merge, keyword search, REST API, export, tokens with the `status` scope, the seven MCP tools, theficos deployment | Agents capture and search over MCP | 01 success criterion 5 without semantic search |
| **M2 Plugin host** | Loader, manifest validation, lifecycle, storage tables, jobs and schedules, routes, plugin MCP tools, guarded fetch, Settings → Plugins data; first plugins without AI: `blinko-import`, `agent-inbox`, `tasks`, `resurface` | The owner's Blinko library is in and agents read it | The four plugins pass the contract suite; counts match the Blinko spot check (criterion 1, first half) |
| **M3 Web** | Core screens, the slot renderers and vocabulary, generated settings pages, Review, three themes | Daily capture and recall on the desktop | Playwright suite green; bundle under budget; every plugin off leaves a finished-looking app |
| **M4 AI gateway and wave 1** | Profiles and routing, validation loop, Anthropic adapter, vector index, health check; `label-suggestions`, `semantic-search`, `related`, `ask` | The old MVP's AI, plugin by plugin | Fake-provider suite proves fail-closed for each plugin; the embedding-model check (D17) |
| **M5 Android** | The native app: offline replica, sync, capture entry points, slot renderer, cached manifest; device plugins `dictation` and `quick-capture` | The phone becomes the main capture surface; cutover when the owner chooses | 02 §16's Android criteria, plus offline rendering of stored slots and views |
| **M6 Wave 2: organize, recall, develop** | `title-summary`, `tidy`, `duplicates`, `label-gardener`, `links`, `digest`, `develop`, `translate` | More AI, still one row of pills | Each passes the contract suite; host API reaches `0.9` |
| **M7 Wave 3: capture breadth** | `link-enrichment`, `image-text`, `voice-memos`, `markdown-import`, `webhooks` | Shares, images and voice become searchable | Guarded fetch passes an SSRF test suite; vision and transcription checks in the health page |
| **M8 Wave 4: inbound and third party** | `email-in`, `telegram`; plugin API `1.0` frozen; `SPUNTO_PLUGINS_DIR` and entry-point loading; author docs and `spunto plugin check` | The owner's own small plugins | A sample third-party plugin passes the same suite as first-party ones |

Within a wave, plugins ship one at a time in the order listed. Cutover from Blinko is no longer a milestone with a deadline: it happens after M5, when the owner is happy, and 01's criterion 1 drops its two-week window.

---

## 7. Change list for 01 to 03

Applied only after approval. Each line is a concrete edit.

**01-requirements.md**

- Status, §1 and §5.1: replace the MVP framing with "a minimal core plus plugins"; the vision adds themes and plugins and keeps the capture loop as the heart.
- §2 principles: Minimal becomes "a minimal core; everything else is a plugin that appears only in fixed, budgeted places". AI-first keeps "AI only proposes for the owner's text" and adds "AI may write new notes, always marked". Add a seventh principle: "Extensible without clutter".
- §4: tag every story `core` or `plugin:<id>` in addition to its priority. Move AI-3, AI-6, AI-8, AI-10 to AI-13, SRC-2, SRC-5, SRC-7, LAB-5, LAB-7, CAP-11, CAP-12, DATA-2, DATA-3, AND-9, AND-10, AND-15, AND-16, WEB-8, WEB-9 under their plugins (§4 of this document). Move AI-9 (private) and CAP-10 (history) into the core. Add PLG-1 to PLG-6 (enable and disable, settings page, permissions on enable, delete data, environment pinning, Review), THM-1 to THM-3 (three themes, per-device override, theme packs), and stories for `tidy`, `title-summary`, `develop`, `translate`, `link-enrichment`, `image-text`, `label-gardener`, `email-in`, `telegram`, `webhooks`.
- AI-1: provider profiles and per-plugin routing; no capability inherits another's endpoint (§9 item 3). AI-2: with AI off, AI plugins show as "Waiting" in Settings → Plugins and nowhere else. AI-4: every plugin call goes through the gateway. AI-12 is replaced by `tidy`.
- MCP-2: add the `status` scope. MCP-4 and MCP-5: the seven core tools of §9 item 5 and `request_id` on `create_note`. MCP-9: `get_digest` becomes `digest_latest`. MCP-8: "the server fetches only through the guarded fetch, only for enabled plugins, and never because of agent input" (D30).
- WEB-4: theme picker and Settings → Plugins. A5 and A9 follow 05.
- §5: replace the MoSCoW table, build order and slip order with the core plus waves of §6; remove the cut list (no deadline).
- §6.3: add "enabling or disabling a plugin shows on other clients within 10 seconds" and "hook dispatch adds nothing to save latency". §6.4: outbound traffic is the AI profiles plus the destinations of enabled plugins, all listed in Settings → Plugins. §6.6: re-measure idle memory with the default-on set and set the image budget with every first-party plugin included; plugin storage counts in the 10,000-note disk budget.
- §7 non-goals: remove "Plugins or a theme marketplace" and "scheduled AI tasks"; add "a plugin marketplace, installing code from the UI, plugin code on clients, notifications or badges from plugins, and AI that edits the owner's text without an accept".
- §8: criterion 1 drops the two-week window; criterion 9 becomes per plugin; add "a fresh install with no AI shows only the core", "every first-party plugin passes the contract suite", "disabling any plugin leaves no visible trace".
- §9 and §10: point to 04 §8 for D25 to D33; §10 records the name, Spunto.

**02-spec.md**

- §0 and §2: the one-page summary and the component diagram gain the plugin host, the AI gateway and the plugin modules; §2.3's save trace shows hooks firing after the commit as jobs.
- §3.4: core migrations add the host tables; plugin data migrations are jobs (§3.3).
- §4.2: `label_suggestion` becomes `suggestion` (plugin, action, payload); add `plugin`, `plugin_kv`, `plugin_record`, `annotation`, `note_text` (into the FTS index); `job` gains `plugin_id` and its unique index becomes `(plugin_id, kind, note_id)`; `ai_stat` gains `plugin_id`; `credential.kind` adds `plugin` and `scope` adds `status`; `note.private` and `open_tasks` move from v1.1 into the first schema; `last_resurfaced_at` moves into `resurface` records; revision coalescing (§9 item 1).
- §5.3: add `/api/v1/plugins` (list, manifest, enable, disable, settings, delete data), the `/api/v1/plugins/<id>/*` dispatcher and `/api/v1/themes/<id>.css`; `/related`, `/ask` and `/transcriptions` move under their plugins; suggestion endpoints become plugin-neutral; `/meta` adds `plugins_rev`; `/status` accepts `status` tokens.
- §6: pull carries suggestions from every plugin, annotations and `plugins_rev`; `suggestion.decide` covers every action; §6.10 gets the autosave debounce.
- §7: rewritten as "AI gateway" (profiles, routing, structured-only, vision, rate shares, private enforcement); §7.3 to §7.7 move into per-plugin sections; §7.9 says plugins wait when their capability is missing; D16 becomes per-plugin routing.
- §8: seven core tools, dynamic tool listing through the low-level handlers, plugin tool naming and budgets, `request_id`, origin marking (D32).
- §9: a trust and permissions subsection, guarded fetch, plugin secrets from the environment, the `status` scope, and three threats: a malicious installed plugin, a plugin sending text off the instance, planted text through `email-in`.
- §10: environment variables for profiles (`SPUNTO_AI_PROFILES`, `SPUNTO_AI_<PROFILE>_BASE_URL`, `_API_KEY`, and `SPUNTO_AI_CHAT_PROFILE`, `_EMBED_PROFILE`, `_TRANSCRIBE_PROFILE`, `_VISION_PROFILE`), `SPUNTO_PLUGINS_DIR`, `SPUNTO_PLUGINS`, `SPUNTO_PLUGIN_<ID>_<KEY>`; network policy egress per enabled plugin on theficos; Uptime Kuma uses a `status` token; §10.9 becomes the `blinko-import` plugin with its CLI (`spunto plugins run blinko-import …`).
- §11: modules add `:core:plugins` (manifest cache, vocabulary renderer, form from schema, local view filters) and `:plugins:*` for device plugins; the note screen's Related list moves into "About this note".
- §12: slot renderers, palette commands from the manifest, theme loading.
- §13: `server/src/spunto/host/`, `server/src/spunto/plugins/<id>/`, `themes/<id>/`, `spec/fixtures/vocabulary/`.
- §14: the plugin contract suite, vocabulary golden tests on both clients, the theme contrast check, an SSRF suite for guarded fetch. §15: the status page lists each plugin with state, last run, AI calls and failures, and its circuit breaker.
- §16 to §18: replace the milestones with §6, add D25 to D33 to the register, and add risks: in-process plugin faults block the event loop (mitigation: sync plugin code runs in worker threads, and the circuit breaker); clustering for `label-gardener` may want `numpy` (image and memory budget; spike in M6); the vocabulary turning out too small (a new component costs one release on both clients).

**03-design.md**

- §1 and §2: principle 9 becomes "every plugin off is a complete design". Principle 3 (serif is the owner's voice) and §6.2 rule 8 (no AI prose in notes) need 05's answer for machine-written notes (D29).
- §3.1: Plugin joins Settings; the views table marks Unlabeled, Open tasks and From agents as plugin views; Review becomes the core queue for all proposals.
- §3.2 and §3.3: a "Views" group in the rail and the labels sheet; the margin becomes the note panel with collapsible sections; Android A5 gains "About this note"; the palette lists plugin commands; the composer gets a `/` menu.
- §4: L3 Review covers every plugin; §4.4 Ask becomes a command result; S5 resurfacing becomes the daily slip.
- §5.1: the suggestion row becomes the shared suggestion line with "+N"; the meta line takes at most one plugin token.
- §6 becomes "The clutter contract": the slot table of §3.4 for every plugin, AI or not, keeping rules 1 to 7.
- §5.2 A9 and §5.3 W8: Settings gains Plugins (list, switches, generated pages, "where note text goes", delete data) and Themes.
- §11: tokens split into the themeable set and the fixed set; §11.9 generates one token set per theme.
- §12 and the mockups: new frames for the note panel, Review, Settings → Plugins and the theme picker, drawn by 05.

---

## 8. Decisions

The owner settled every decision below on 2026-10-13 at its recommended option; 01 §9 and 02 §17 record each with a one-line reason. Two answers are now facts rather than options: D16's default is Ollama cloud for chat and vision with embeddings on the local Ollama (any provider stays supported), and D22's phone runs Android 16, so its on-device recognizer is the engine and the M0 spike confirms the Italian and English packs. The options stay here as the reasoning, each naming the real axis.

### D25. How does third-party plugin code run?

Stakes: crash isolation and security against simplicity; the clients do not care either way, because they only see contributions.

| Option | Consequence |
|---|---|
| First-party only, for now | No third-party risk at all; the API is still proven by the first-party plugins; the owner's own small plugins must go in the repository |
| **In-process Python after the API freezes (recommended)** | One container, simple authoring, the Obsidian trust model; a buggy plugin can stall the process and a malicious one can read everything |
| Out-of-process "remote plugins": manifest plus an HTTPS endpoint the host calls with signed webhooks, calling back with a scoped token | Any language, crash isolation, nothing in the image; a second deployment per plugin, network policy to manage, latency on every hook |
| Both | Maximum choice; two runtimes to test and document |

Recommendation: first-party only until M8, then in-process. The manifest keeps a `runtime` field so remote plugins can be added later without touching clients, and Webhooks out plus the REST API with scoped tokens already cover outside integrations. Axis: isolation against machinery for a one-owner app.

### D26. May the web app ever run plugin JavaScript?

Stakes: what plugins can express, against clutter, security and Android parity.

| Option | Consequence |
|---|---|
| **Never (recommended; the doing-nothing default)** | Every plugin looks native on both clients and inherits themes; graph views, kanban boards and calendars are impossible, which is the clutter contract working |
| A sandboxed iframe "custom view" for third-party plugins | Rich views become possible; they are web-only, ignore themes unless the plugin cooperates, and widen the security surface (CSP, message bridge) |
| Full plugin JavaScript in the app | Obsidian-level freedom and Blinko-level clutter; the 150 KB budget and the strict CSP end |

Recommendation: never for API 1; revisit only with a concrete plugin the vocabulary cannot express. Axis: expressiveness against a guaranteed calm surface.

### D27. Who can install third-party code?

Stakes: whether a stolen browser session can become code running on the server.

| Option | Consequence |
|---|---|
| **The operator only: a folder in `SPUNTO_PLUGINS_DIR` or a derived image (recommended)** | Installing needs the same access as reading the database already does; GitOps-friendly; no upload UI |
| Upload from Settings | Easier for newcomers; any XSS or stolen cookie becomes remote code execution; impossible with a read-only root filesystem without writing code to the data volume |

Recommendation: operator only. Axis: convenience against turning the web session into an installer.

### D28. Do plugins get their own SQL tables?

Stakes: plugin flexibility against guarantees about purge, backup, export and performance.

| Option | Consequence |
|---|---|
| **Host-managed storage only: kv, records, annotations, searchable text (recommended)** | Purge is one delete per table; backups and the 50,000-note budget hold; no plugin migration can break startup; plugins with relational needs must fit JSON records |
| Plugin-owned tables (`p_<id>__*`) with their own numbered migrations | Any data shape and SQL speed; migration order across plugin versions, and a failed plugin migration at startup, become new failure modes |

Recommendation: host-managed for API 1. Every plugin in the catalog fits it; revisit if a real plugin does not. Axis: flexibility against a store the core can always reason about.

### D29. How do machine-written notes behave?

Stakes: research calls generated prose in an idea store a gimmick that lowers the value of search and resurfacing, and the brief wants digests, saved answers and expansions as notes. Without a rule, the next digest summarises the last one and Ask cites its own saved answers as evidence.

| Option | Consequence |
|---|---|
| Ordinary notes; only the chip differs | Simplest; feedback loops and slow dilution of the owner's own thinking |
| **Marked notes: a source chip, excluded from every plugin's AI input and from resurfacing until the owner edits them; editing adopts the note (recommended)** | One rule in the host's note reader, using the existing `updated_by`; the owner's pile stays the owner's; 05 decides how they look |
| A separate object with its own screens | No dilution; breaks "outputs are notes" and adds the screens the clutter contract forbids |

Recommendation: marked notes. Axis: one kind of note against keeping the pile the owner's own thinking.

### D30. May the server fetch URLs?

Stakes: 01 promises the only outbound traffic is the AI provider, and MCP-8 promises no tool input makes the server fetch. Link enrichment, Blinko import from the UI, Telegram, Email-in and Webhooks all need the network.

| Option | Consequence |
|---|---|
| Never | Those plugins cannot exist; Blinko import stays a CLI command |
| **A guarded fetch in the core, used only by enabled plugins that declare it, and never triggered by notes created by agents or email unless the owner runs the command (recommended)** | Rich capture with SSRF protection in one place; MCP-8 stays true for agents; the server's IP becomes visible to sites the owner shares |
| Plugins use their own HTTP clients | No machinery; no guard, and no truthful "where note text goes" |

Recommendation: the guarded fetch. Axis: richer capture against a server that never reaches out.

### D31. What is on by default?

Stakes: the first impression of minimalism against features nobody discovers.

| Option | Consequence |
|---|---|
| Core only, even with AI | The calmest start; the owner must find and enable even label suggestions |
| **Core plus `dictation` and `quick-capture`; with AI, also `label-suggestions`, `semantic-search`, `related` and `ask` (recommended; the brief's rule)** | An AI install matches the old MVP; nothing new appears that the owner did not have before |
| Also `resurface`, `tasks`, `agent-inbox`, `digest` | Richer from day one; a daily slip, a weekly note and two views the owner did not ask for |

Recommendation: the middle option, with Settings → Plugins listing `resurface` first among the ones worth trying. Axis: discoverability against a quiet first run.

### D16 (revised). Where does note text go for AI?

Stakes: per-plugin routing lets the owner send each plugin to a different model, but an honest reading is that it helps speed and quality more than privacy: label suggestions eventually send every note once, and a digest sends every week's notes, so any cloud chat route means essentially all notes leave over time.

| Option | Consequence |
|---|---|
| Everything local on the theficos Ollama | Nothing leaves the house; CPU-only chat is slow for Ask, digests and develop |
| **Embeddings local always; one chat profile chosen by the owner; per-plugin routing used to send heavy, slow jobs to whichever model is good enough (recommended)** | Clear mental model, with "private" as the per-note opt-out; the status page names each destination |
| Mixed per plugin for privacy reasons | Feels finer-grained; in practice most notes still reach the cloud through some plugin |

Recommendation: decide the chat recipient once (the owner's privacy call), keep embeddings local, and use routing for performance. Axis: what the owner is comfortable sending, not per-feature tuning.

### D22 (carried over). Which phone confirms the dictation engine?

Stakes: if the owner's phone has no on-device recognizer, the bundled Vosk model (AND-16) adds about a week to M5.

| Option | Consequence |
|---|---|
| **The owner's daily phone, named in the M0 spike notes (recommended)** | The spike answers the question that matters; the Pixel 6a-class phone stays the performance reference |
| A Pixel 6a-class reference only | Repeatable; may say nothing about the phone actually used |
| Skip the spike | Discover the answer in M5, possibly after building around the wrong engine |

### D32 (carried over, previously unnumbered). How does MCP defend against prompt injection?

Stakes: plugins bring outside text in (`email-in`, `telegram`, `link-enrichment`), and an agent with a shell that reads "run this command" in a planted note is the real risk; the server cannot control the agent's other tools.

| Option | Consequence |
|---|---|
| Today's design: additive tools, `via` on hits, docs advising read-only tokens | No work; outside text reaches agents unmarked as untrusted |
| **Every hit and note carries `origin: owner, agent, plugin or external`, and external text is fenced in tool output as quoted data; `email-in` requires a sender allowlist (recommended)** | Agents can see what is untrusted; small token cost; nothing hidden |
| Also exclude external notes from MCP unless a token opts in | Smallest blast radius; agents miss notes the owner shared on purpose |

Recommendation: marking and fencing for everyone, the exclusion as a per-token option. Axis: agent usefulness against blast radius.

### D33 (carried over, previously unnumbered). What happens on plain HTTP on a LAN at first run?

Stakes: the five-minute first note (HOST-1). The `__Host-` session cookie requires a secure origin, so a newcomer opening `http://192.168.1.10:8080` sees a login that silently does nothing; Android blocks cleartext HTTP by default since API 28.

| Option | Consequence |
|---|---|
| Do nothing | Confusing failure on the most common first try |
| **When `SPUNTO_PUBLIC_URL` is `http://`, use a plain cookie, show a permanent "Not encrypted" line, and let Android connect after an explicit warning (recommended)** | The first note works anywhere; nobody mistakes it for a finished setup |
| Refuse `http://` except `localhost`, pointing to the Caddy compose file | Safe by construction; the five-minute promise fails for LAN-only users |
| Built-in self-signed TLS | Encrypted; certificate warnings everywhere and trust-on-first-use pinning on Android |

Recommendation: allow it, loudly. Axis: newcomer success against never sending a password in clear.

### D3 (carried over). Confirm the Python server.

Stakes: plugin authors will write in the server's language. Python gives importlib and entry points for loading, Quire's AI client and auth, and the language most self-hosters script in; TypeScript would share types with the web but still cross a boundary to Kotlin and re-prove the AI client. Recommendation: confirm Python. Doing nothing leaves M0 blocked.

### Name (carried over).

Settled: Spunto. Identifiers follow it (`spunto.plugins`, `SPUNTO_PLUGIN_*`, the `spunto_` token prefix, the Android package `io.theficos.spunto`), and M0 checks availability (01 §10).

---

## 9. Clear-cut fixes to fold in during propagation

1. **Autosave writes a revision on every pause.** 03 A5 and W3 autosave one second after typing stops, and each save adds a version, a revision row, a sync change and an embed job. Fix: clients keep writing the local draft on every pause (CAP-4 is unchanged) but send to the server after 5 seconds idle, on leaving the note, or every 60 seconds while typing. The server coalesces: a change by the same credential within 10 minutes of its previous revision of the same note, with no other credential in between, replaces that revision instead of adding one. The three-way merge falls back to the nearest older revision as its base, which still merges cleanly because identical changes on both sides count once (02 §6.4). Lands in 02 §4.2, §6.4, §6.10, §10.6 and 03 A5, W3.
2. **The monitoring token can read every note.** 02 §10.5 points Uptime Kuma at `/api/v1/status` with a read token, which also reads all notes. Fix: a `status` scope that reaches only `/api/v1/status`; the docs and the theficos monitor use it. Lands in 02 §5.3, §9.1, §10.5 and 01 MCP-2.
3. **The embedding URL defaults to the chat URL.** With chat pointed at a cloud model, every note would be embedded in the cloud without anyone choosing it. Fix: capabilities bind to named profiles and nothing inherits; a profile whose host is not a private address is flagged "leaves this network" at startup, on the status page and on every plugin page that uses it. Lands in 02 §7.1, §10.3 and 01 AI-1.
4. **MCP `create_note` is not idempotent.** A retried call makes a second note. Fix: an optional `request_id` stored in `applied_op` per token, so a retry returns the first answer; and an identical body from the same token within 10 minutes returns the existing note with `duplicate: true`. Lands in 02 §8.3 and 01 MCP-5.
5. **MCP tools overlap.** `recent_notes` duplicates `search_notes` with an empty query; `related_notes` with free text duplicates semantic search; single-id `get_note` forces one call per note. Fix: core tools become `search_notes` (empty query allowed, `sort: relevance | recent`), `get_notes` (1 to 20 IDs, same shape, same ceiling), `list_labels`, `create_note`, `append_to_note`, `set_labels`, `archive_note`; read tokens see three. `related_notes(id)` moves into the `related` plugin, by ID only. Lands in 02 §8.3, §8.5 and 01 MCP-4, MCP-5.

---

Takeaway: the app stays a small, calm notes app at its core, and grows through plugins that can only speak through a few fixed places with limits the code enforces, so adding a feature never means adding clutter.
