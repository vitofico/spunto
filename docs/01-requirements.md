# 01 · Requirements

Status: approved 2026-10-13, as revised by the redesign: a minimal core plus plugins ([`04-redesign.md`](04-redesign.md)) and how plugin output and themes look ([`05-redesign-ux.md`](05-redesign-ux.md)). Inputs: [`research/blinko.md`](research/blinko.md), [`research/landscape.md`](research/landscape.md), [`research/stack.md`](research/stack.md), 04 and 05. Earlier the same day, speech to text (AND-15, dictation) joined the Android scope at the owner's request; the redesign then replaced the deadline-driven MVP with the core and the plugin waves of §5.

Nothing is built yet and no repository changes because of this document. Blinko keeps running on theficos until the owner chooses to cut over (§5.2, §8).

Conventions:

- Name: **Spunto** (§10).
- Every story carries two tags. Where it lives: `core` (part of the complete app with every plugin off) or `plugin:<id>` (a plugin from 04 §4, switched on and off in Settings → Plugins). When it ships: a milestone from §5.2 (M0 to M8), or `later` when no milestone schedules it yet. Whatever its own milestone, a story that shows something on screen appears on the web from M3 and on Android from M5.
- "Owner" is the one person an instance belongs to (decision D8).
- Decisions D1 to D33 are settled. §9 records the ones these requirements rest on, each with the chosen option and why; D12 to D24 come from the spec (`02-spec.md` §17), D25 to D33 from the redesign (04 §8).

---

## 1. Problem and vision

Ideas arrive at bad moments: on a walk, halfway through a code review, just before sleep. Today they go into Blinko on theficos, and Blinko got the core loop right: an empty box on top of a stream of cards, `#labels` typed inline, Markdown underneath. Everything around that loop grew sideways. It needs three pods and about 530 MiB of reserved memory, plus an nginx sidecar whose only job is to work around one Ollama bug. Its search vectors live in a second database file that regularly goes missing. Its Android app is a 93 MB web wrapper that cannot save a note without reaching the server. An idle browser tab burns 20 to 60 % of a CPU core. Its AI appends unvalidated model text straight into notes, and its MCP endpoint (the door Claude Code and Codex would use) runs every call as superadmin and leaks internal parameters into tool schemas. The vision is the same capture loop, rebuilt small and with care: Keep-level capture on Android that works offline, Bear-level typography, Memos-level hosting (one container, one volume), AI that quietly organizes and resurfaces but never changes the owner's words without an accept, and a built-in MCP endpoint so coding agents read from and add to the same pile of ideas the owner does. It is built as a minimal core that is a complete notes app on its own, and everything else, most AI features included, is a plugin the owner switches on and off. A plugin can appear only in a few fixed places the core already draws, within limits the code enforces, and three built-in themes set the paper it is all written on. The capture loop stays the heart.

## 2. Product principles

1. **A minimal core.** One kind of note, one search box, labels as the only structure, and a core that is a complete notes app with every plugin off. Everything else is a plugin that appears only in fixed, budgeted places (§4.10) and stays off until the owner wants it; what neither fits stays out (§7).
2. **Beautiful.** Typography is the interface: a real reading typeface, a 60 to 72 character line length, quiet paper tones with one accent per theme, short motion that explains what happened, and zero cost while idle.
3. **AI-first, never AI-dependent.** The app is designed so nothing needs filing at capture time, because AI plugins suggest labels, find notes by meaning and bring related notes forward. But AI only proposes for the owner's text, every model response is validated before it touches data, and with AI switched off the app is still complete. AI may write new notes (a digest, a saved answer), always marked as the machine's and kept out of later AI inputs until the owner edits them (D29).
4. **Self-hostable.** One container, one volume, configuration by environment variables, upgrades that back up first, backups that restore.
5. **Agent-accessible.** Claude Code and Codex are first-class clients through a built-in MCP endpoint (MCP, the Model Context Protocol, is how coding agents call tools in other programs), with a small, task-shaped tool set and compact answers.
6. **Never lose a thought.** Saves land locally before anything else, no edit silently overwrites another, and every note exports to plain Markdown at any time.
7. **Extensible without clutter.** Features beyond the core are plugins, first-party ones included, built on the same API a third party would use. A plugin never picks a position, colour, icon or size: it fills one of the slots the core already draws, within a budget the code enforces, using the core's own components. Switching one off leaves no trace on screen and keeps its data.

## 3. Target user and jobs to be done

**Primary user: the owner-developer.** One person who captures ideas mostly on an Android phone (Android 16) during the day, works at a laptop with Claude Code and Codex, self-hosts on a home Kubernetes cluster, and already runs a local Ollama. They write in English and Italian. They value calm, well-made tools and will drop one that feels heavy.

**Secondary user: the self-hoster.** People from the Memos and Blinko audience who want a capture app with local-model AI and an agent endpoint, and who judge a project by how fast `docker run` gets them to a first note. Some will write a small plugin of their own once the plugin API is frozen (M8).

| # | When... | I want to... | So that... |
|---|---|---|---|
| J1 | an idea hits me away from my desk, possibly with no signal | get it out of my head in a few seconds | I don't lose it or break my flow |
| J2 | I sit down to work on something | find what I already thought about it, even in different words | I build on past ideas instead of re-having them |
| J3 | notes pile up untagged | have them organized without filing them myself | the pile stays usable |
| J4 | I'm working with a coding agent | let it read my ideas for context and drop new ones in | I stop copy-pasting between notes and terminal |
| J5 | an old idea is still lying around | have it come back to me now and then | the pile turns into projects |
| J6 | I host the server | have it be boring: small, backed up, upgrades that don't break | I never think about it |
| J7 | I arrive from Blinko, or one day leave | bring my notes in and take them out as plain files | the app never holds my notes hostage |

## 4. User stories

Written from the owner's point of view. IDs are stable so specs and plans can refer to them. §4.1 to §4.11 hold the core; stories that moved into a plugin keep their IDs and are listed by plugin in §4.12.

### 4.1 Capture

- **CAP-1 (core · M3) Open and type.** Opening the web app or the Android app lands in an empty composer above the timeline, focused, with the Android keyboard already up.
- **CAP-2 (core · M1) No questions.** Saving needs nothing but text: no title, label, folder or note type. Wherever a title is needed (lists, exports, MCP results) the first line stands in.
- **CAP-3 (core · M3) One-gesture save.** `Ctrl/Cmd+Enter` on web, the send button on Android. The card appears at the top of the timeline at once; the composer clears and keeps focus for the next thought.
- **CAP-4 (core · M3) Drafts survive.** Text typed but not sent survives an app kill, a closed tab, a reload or a dropped connection.
- **CAP-5 (core · M3) Markdown body.** Notes are Markdown: emphasis, lists, `- [ ]` checkboxes, links, code. Cards render it with the reading typography; the editor shows the source with light highlighting.
- **CAP-6 (core · M1) Where it came from.** A note created from a share, a text selection, an import, an agent or a plugin records its origin (URL, app, importer, agent or plugin name) and shows it as a small chip on the card. Notes a plugin writes are also marked machine-written (§6.5).
- **CAP-7 (core · M1) Edit, pin, archive, trash.** I can edit any note, pin it to the top, archive it out of the timeline, and move it to trash and back. Trash empties itself after 30 days (configurable).
- **CAP-8 (core · M3) Tick boxes in place.** Checkboxes toggle directly on the card. Todos stay ordinary notes; the Tasks view comes with `tasks` (SRC-8).
- **CAP-9 (core · M3) Images.** Paste or drop an image on web, share one from Android; it is stored on the server volume and shown in the card.
- **CAP-10 (core · M1) Revision history.** Edits keep earlier versions; I can view and restore them, which also undoes any agent edit or accepted suggestion. Saves from one device within 10 minutes of each other, with no other device or agent in between, coalesce into one revision, so autosave does not flood the history (04 §9 item 1).

### 4.2 Organize: labels

- **LAB-1 (core · M1) Inline labels.** Typing `#idea` or `#project/spunto` anywhere in a note labels it, and `/` nests (`spunto` sits under `project`). A note's labels are exactly the `#tokens` in its text (D4). Headings (`# Title`), URL fragments and code are never mistaken for labels.
- **LAB-2 (core · M3) Autocomplete.** Typing `#` suggests existing labels, most used first.
- **LAB-3 (core · M3) Label tree.** A nested list of labels with note counts (a collapsible rail on web, a sheet on Android). Choosing one filters the timeline.
- **LAB-4 (core · M1) Safe rename and merge.** Renaming or merging a label rewrites every affected note in one transaction, matches whole labels only (renaming `#work` never touches `#workout`), carries child labels along, and reaches other devices on their next sync.
- **LAB-6 (core · M1) Remove a label.** Deleting a label removes its tokens from notes and leaves the rest of the text intact.

### 4.3 Recall: search and views

- **SRC-1 (core · M1) One search box.** `Ctrl/Cmd+K` on web, a search field on Android. Keyword matches are instant, accent-insensitive (`perche` finds `perché`) and work with AI off. `#label` in the query filters by label.
- **SRC-3 (core · M3) Views.** Timeline (newest first, pinned on top), archive, trash and label filters, each scrolling continuously. Plugin views join a "Views" group as declarative filters, so they work on an offline phone too (§4.10).
- **SRC-4 (core · M5) Android search offline.** With no connection, Android searches its local copy by keyword; online, it uses the server's search, hybrid when `semantic-search` is on.
- **SRC-6 (core · M1) Date filter** on search and timeline.

### 4.4 AI: the gateway

The core has no AI feature of its own. It has the gateway every AI plugin goes through, so privacy, validation and indexing are enforced in one place; the features themselves are plugins (§4.12).

- **AI-1 (core · M4) Bring your own models.** One OpenAI-compatible protocol, which covers Ollama, OpenAI, OpenRouter, vLLM and LiteLLM, plus a native Anthropic adapter (D15). The owner configures named provider profiles by environment variables and binds each capability (chat, embeddings, transcription, vision) to one. No capability inherits another's endpoint, so embeddings never follow chat to a cloud model by accident, and a profile outside the private network is flagged "leaves this network". Each plugin uses the default profile for a capability unless the owner routes it elsewhere (D16). One protocol instead of one client per vendor is what avoids Blinko's per-provider bugs.
- **AI-2 (core · M4) Off means off.** With no provider configured, no note content leaves the server, every core feature works, and AI plugins show as "Waiting" ("Needs a chat model") in Settings → Plugins and nowhere else. The same holds per capability: a plugin whose model is missing waits while the others run.
- **AI-4 (core · M4) Validated output.** Every plugin's model call goes through the gateway, which offers structured output only and checks every response against a schema and its meaning (labels against the label grammar, citations against the notes sent). On failure it retries once with the validation error in the prompt, then fails closed: no output, note untouched, failure counted per plugin on the status page. This covers models that return HTTP 200 while ignoring the requested JSON schema (the Ollama cloud behaviour already met in Quire, whose client handles it and is the reference implementation).
- **AI-5 (core · M4) Background indexing.** While any enabled plugin needs embeddings, each note is embedded after it is saved (turned into a vector of numbers that captures its meaning). One index serves every plugin. The work queue lives in the database, runs in batches, resumes after a restart, shows progress, and copes with a backfill of thousands of imported notes. Changing the embedding model re-indexes in the background. The vector size is measured from the model, never guessed from its name.
- **AI-7 (core · M4) Honest health check.** The status page runs a real schema-constrained call against every capability in use (chat and embeddings, plus transcription and vision once a plugin needs them) and says which failed and why, with a fix hint such as "run `ollama pull nomic-embed-text`".
- **AI-9 (core · M1) Private notes.** A per-note flag that keeps the note out of every AI call, out of the vector index and away from every plugin that can send text off the instance, and hides it from MCP unless a token opts in (D19). The gateway refuses private notes, so no plugin can forget the rule.

### 4.5 Android

- **AND-1 (core · M5) Native app.** Kotlin and Jetpack Compose (D2), Android 8.0 (API 26) and later, Material 3 following the system light/dark setting and the owner's theme (THM-1), dynamic color as an option.
- **AND-2 (core · M5) Works offline.** All notes are stored on the phone. Capture, edit, label, archive and search all work with no connection. Changes wait in a local outbox (a queue of pending changes) and sync when the network returns. "Saved" means written to the phone's database; unsynced notes carry a small, quiet marker.
- **AND-3 (core · M5) Share into it.** Text and links shared from any app open a small sheet with room for a comment; one tap saves and returns to the app I came from.
- **AND-4 (core · M5) Save a selection.** Selecting text in any app offers "Save to spunto" in the selection menu, which saves without leaving that app.
- **AND-5 (core · M5) Launcher shortcuts.** Long-pressing the icon offers "New note" and, with `dictation` on, "Dictate a note" (opens the capture sheet already listening, AND-15).
- **AND-6 (core · M5) Sign in once.** Server URL plus owner password gives the phone its own named, revocable device token, kept in the Android Keystore.
- **AND-7 (core · M5) The full loop.** Timeline, note view and edit, history, label tree, search (SRC-4), and every plugin slot (suggestion line, Review, "About this note", views, commands), drawn from a cached copy of the plugin manifest so they render offline.
- **AND-8 (core · M5) Distribution.** Signed APK on GitHub Releases, installable and auto-updated through Obtainium. No Play Store (Quire's Play Protect experience).
- **AND-11 (core · M5) Share images,** queued in the outbox like any other change.
- **AND-12 (core · M5) Pair by QR.** Scan a code shown in web settings instead of typing URL and password.
- **AND-13 (core · later) F-Droid** listing with reproducible builds.
- **AND-14 (plugin:notes-role, device · later) Default notes app** (Android 14+ notes role): capture from the lock screen and from the stylus button. It would ship as a device plugin, compiled into the app like `dictation` (04 §3.6), and is not in the catalog yet.

### 4.6 Web

- **WEB-1 (core · M3) Composer-first home** with the timeline below, comfortable from phone width to a wide desktop.
- **WEB-2 (core · M3) Keyboard-first.** `Ctrl/Cmd+K` for search and actions (plugin commands included), `n` focuses the composer, `/` searches (at the start of a composer line it opens the command menu), `Ctrl/Cmd+Enter` saves, `Esc` leaves, `?` lists shortcuts. Keyboard-driven actions are never animated.
- **WEB-3 (core · M3) Label rail,** collapsible, nested, with counts (LAB-3), and a Views group once a plugin brings a view.
- **WEB-4 (core · M3) Settings that matter.** Appearance (theme and its per-device override, THM-1 and THM-2; light, dark or system; reading typeface, serif or sans; text size); Plugins (PLG-1 to PLG-5); access tokens (create with a scope, shown once, revoke, last used); signed-in devices; AI status; export.
- **WEB-5 (core · M3) Agent setup snippets.** Settings shows the exact Claude Code command and Codex config for this instance, URL filled in, ready to paste.
- **WEB-6 (core · M3) Online, with safe drafts.** The web app needs the server to read and save. When the server is unreachable it shows a calm banner and the composer keeps accepting text into a local draft (CAP-4).
- **WEB-7 (core · later) Installable** as a PWA (a web app the browser can add to the home screen or dock like a native app).
- **WEB-9 (core · later) Clip from the browser:** a bookmarklet and the PWA share target, saving the selection plus its URL.
- **WEB-10 (core · later) Italian interface.**

### 4.7 Self-hosting

- **HOST-1 (core · M0) One container, one volume.** `docker run -p 8080:8080 -v spunto:/data -e SPUNTO_PUBLIC_URL=http://<host>:8080 -e SPUNTO_OWNER_PASSWORD=... ghcr.io/vitofico/spunto:<version>` gives a working instance, over plain HTTP on a LAN too (§6.4, D33). API, web app and MCP endpoint share one process and one port.
- **HOST-2 (core · M0) Configured by environment.** Every server setting, AI profiles, models and keys included, comes from environment variables (or `*_FILE` variants for secrets), and so do plugin secrets (`SPUNTO_PLUGIN_<ID>_<KEY>`) and plugin pins (PLG-5), so a GitOps deploy fully describes the instance. Bad configuration stops startup with a sentence naming the variable; a plugin that fails to load never does. Personal preferences (theme, plugin switches and settings) live in the database.
- **HOST-3 (core · M1) Closed by default.** There is no registration endpoint. The owner account comes from the environment, or from a one-time setup code printed in the log on first start.
- **HOST-4 (core · M0) Pinned, multi-arch images.** amd64 and arm64, a version tag per release plus a moving `stable` tag. The docs never tell anyone to run `latest`.
- **HOST-5 (core · M0) Safe upgrades.** Schema migrations, and plugin data migrations, run at start immediately after an automatic backup. A server older than its database refuses to start and says why.
- **HOST-6 (core · M1) Health and status.** `/healthz` and `/readyz` for orchestrators, and an owner-only status page: version, note count, indexing progress, AI health, last backup, validation failures, and each plugin's state, last run, AI calls and failures. A monitor reads it with a `status` token, which reaches nothing else (MCP-2).
- **HOST-7 (core · M1) Behind a proxy.** Works behind any reverse proxy at a configured public URL, with TLS terminated by the proxy.
- **HOST-8 (core · M1) theficos deployment.** One pod following the cluster's conventions: pod security settings, default-deny network policy with ingress from Traefik and egress to Ollama and to the destinations of enabled plugins, SOPS-encrypted secret, pinned image, database on a local volume, backups written to the NFS path that borg already covers. Public at `spunto.example.org`.
- **HOST-9 (core · later) Compose examples:** minimal, with Caddy for TLS, and with a local Ollama.
- **HOST-10 (core · later) Helm chart and one-click hosting** (PikaPods and similar).

### 4.8 MCP and agents

- **MCP-1 (core · M1) Built in.** The server exposes MCP at `/mcp` over streamable HTTP (MCP's standard network transport). It keeps no session state in memory, so a restart never drops an agent's connection.
- **MCP-2 (core · M1) Token auth with scopes.** Personal access tokens (long random strings the owner creates in settings) are accepted only in the `Authorization: Bearer` header, never in a URL. Each token is read-only, read-write or `status` (reaches only the status endpoint, for a monitor such as Uptime Kuma), stored hashed and revocable.
- **MCP-3 (core · M1) One-command setup,** documented and tested for Claude Code (`claude mcp add --transport http spunto ...`) and Codex (`[mcp_servers.spunto]` with `bearer_token_env_var`).
- **MCP-4 (core · M1) Read tools.** `search_notes` (keyword, hybrid when `semantic-search` is on; an empty query with `sort: recent` lists the latest notes; label and date filters; compact hits with ID, first line, snippet, labels and dates; paginated), `get_notes` (1 to 20 IDs in one call), `list_labels`. Enabled plugins add at most three tools each, named `<id>_<tool>` (`related_notes`, `digest_latest`); the list is computed per token, read tokens never see write tools, and the owner can hide any plugin tool.
- **MCP-5 (core · M1) Additive write tools** (read-write scope): `create_note` (an optional `request_id` makes a retry return the first answer, and an identical body from the same token within 10 minutes returns the existing note), `append_to_note` (optional `expected_version`), `set_labels` (add or remove), `archive_note`. No hard delete and no whole-body overwrite (D7).
- **MCP-6 (core · M1) Agent-friendly answers.** Tool annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`) so clients can auto-approve reads; errors as one readable line; a default search answer under about 2,000 tokens and every answer under 10,000 (where Claude Code starts warning).
- **MCP-7 (core · M1) Provenance and origin.** Notes created or changed by an agent record which one (from the client name and the token name) and show a "via Claude Code" chip. Every hit and note an agent reads carries its origin (owner, agent, plugin or external), external text such as mail or a fetched page is fenced as quoted data, and a token can exclude external notes altogether (D32). The "From agents" view comes with `agent-inbox` (MCP-10).
- **MCP-8 (core · M1) No side doors.** Tool input never makes the server run a process or fetch a URL. The server fetches only through the guarded fetch, only for enabled plugins, and never because of agent input (D30).
- **MCP-9 (core · later) Extras:** `search` and `fetch` aliases for generic clients, and a stdio shim for clients without HTTP support. The digest's tool, `digest_latest`, comes with `digest` (AI-11).

### 4.9 Data ownership

- **DATA-1 (core · M1) Export everything.** One click, or one API call, downloads a zip: one Markdown file per note with front matter (id, created, updated, labels, pinned, archived, source), an `attachments/` folder and relative links. Readable in any editor and openable as an Obsidian vault.
- **DATA-4 (core · M1) Documented REST API** (OpenAPI) for scripts, using the same tokens.
- **DATA-5 (core · later) Markdown mirror.** A folder of `.md` files on the volume kept continuously in step with the database, for grep and git.

### 4.10 Plugins

Everything beyond the core is a plugin, first-party features included: a folder with a manifest and, for server plugins, Python code running in the server. Device plugins (dictation, quick capture) are compiled into the Android app because they need the microphone or the launcher; theme packs are colour values and no code. A plugin can appear only in the slots the core draws (the suggestion line under a note, Review, the note panel, one token on the meta line, the daily slip, commands and their results, views, its settings page, search ranking and new notes), each with a budget the code enforces, and only through a closed set of components (04 §3.4). It proposes changes to the owner's notes and never makes them.

- **PLG-1 (core · M3) Switch plugins on and off.** Settings → Plugins lists every plugin by category, each with one sentence, a facts line the host computes (where note text goes, what runs when) and a switch. Switching one off removes it from every screen at once, on other devices at their next sync, while its data, settings and the notes it wrote stay; switching it back on restores its output with no backfill. No restart is needed. A plugin whose requirement is missing shows "Waiting" ("Needs a vision model") and contributes nothing. Switches change from the web or the owner's phone, never from an access token.
- **PLG-2 (core · M3) A settings page per plugin,** generated from the plugin's schema, plus sections every page shares: its AI route, its commands, its last runs and its access. Secret fields are never inputs: they come from the environment and read "Set in the environment". Per-device fields (dictation language) stay on the device. Offline on Android the page is read-only.
- **PLG-3 (core · M3) Permissions on enable.** Turning a plugin on first shows, in plain words, what it will do, where note text goes ("Chat: the note goes to qwen3.5 at ollama.home.arpa, on your network", computed from its AI route and network permissions) and where it shows up on screen. The same sheet returns when an update adds a permission. Turning one off asks nothing and offers Undo for a few seconds.
- **PLG-4 (core · M3) Delete a plugin's data.** "Delete this plugin's data…" shows a count and removes its records, suggestions and annotations; the notes it wrote stay, because they are notes. A third-party plugin whose code was removed shows "Removed" with the same offer.
- **PLG-5 (core · M2) Pin plugins from the environment.** `SPUNTO_PLUGINS="+digest,-telegram"` pins the listed plugins on or off and locks their switches with "Set in the environment", the same rule the owner password follows.
- **PLG-6 (core · M3) Review.** Proposals that do not belong to one note (label merges, new labels for clusters, duplicate pairs) and label suggestions waiting on several notes go to one Review screen, grouped by kind, for every plugin together. It is announced by a single line under the composer ("6 suggestions · Review"), shown only from three pending items, with no badge anywhere. Label changes from Review need a connection. When the queue is empty the screen says "All caught up." and the line disappears.

### 4.11 Themes

- **THM-1 (core · M3) Three themes.** Carta (warm paper and terracotta, the default), Ardesia (cool slate, an ink-blue accent) and Salvia (pale sage, a forest-green accent), each with a light and a dark set and one accent. They replace the five-accent picker. A theme changes colours only, never typefaces, because the serif marks the owner's words (05 §8). Every theme meets WCAG 2.2 AA contrast in both modes.
- **THM-2 (core · M3) Per-device override.** The theme is an instance setting that syncs; any device can use a different one instead. Mode (system, light or dark) stays per device, and Android adds pure black and wallpaper colours on top of any theme.
- **THM-3 (core · M8) Theme packs.** A pack the operator adds to `/data/themes` (colour values, no code, no fonts) appears as another card in Appearance. A pack that fails the contrast check is shown disabled, naming the failing pair, and cannot be chosen.

### 4.12 Plugin stories

Grouped as in Settings → Plugins; 04 §4 lists each plugin's slots, permissions and where its text goes. On by default (D31): `dictation` and `quick-capture`, and with AI configured `label-suggestions`, `semantic-search`, `related` and `ask`, each once its own model requirement is met. Every other plugin starts off, with `resurface` listed first among the ones worth trying.

#### Capture

- **AND-15 (plugin:dictation · M5) Dictation.** A mic in the composer's action bar (in the app, in the capture sheet and while editing a note) turns speech into text at the cursor, with the words appearing as I speak. Recognition runs on the phone, so it works offline and with AI off. Italian and English, switched with one tap and remembered on the phone, with automatic detection where the phone supports it. No audio is kept, only the resulting text syncs, and a recognizer that could send audio off the phone is used only after an explicit opt-in (D23). The engine is Android's on-device recognizer: the owner's phone runs Android 16, and the M0 spike confirms its Italian and English on-device packs (D22). Switched off, the mic leaves the action bar.
- **AND-16 (plugin:dictation · later) Dictation without a speech service.** A bundled open model (Vosk or whisper.cpp), downloaded on demand, for phones that have no on-device recognizer (for example de-Googled Android). The owner's Android 16 phone does not need it (D22), so it waits for a phone that does.
- **AND-9 (plugin:quick-capture · M5) Widget.** A home-screen widget with a "new note" button, a mic that opens the capture sheet already listening (AND-15), and the daily slip when `resurface` is on.
- **AND-10 (plugin:quick-capture · M5) Quick Settings tile** that opens the composer from anywhere, plus an optional second tile, "Dictate", that opens it already listening.
- **CAP-12 (plugin:voice-memos · M7) Voice memos.** Builds on dictation (AND-15), which never needs it: keeps the recording as an attachment, transcribes it on the server through the transcription profile, and offers the transcript as a diff to accept, never as a silent replacement; Tidy (AI-12) cleans it up. Needs attachments and a transcription model (D24).
- **CAP-11 (plugin:link-enrichment · M7) Link enrichment.** For a link I saved, the server fetches the page once through the guarded fetch and offers its title as a pill, a Source section in the note panel (title, reading time, and a short summary and label ideas when a chat model is set) and a reading time on the meta line ("23 min read"). Links in notes from agents or email are fetched only when I run "Fetch link details" (D30). No new note is written.
- **AI-18 (plugin:image-text · M7) Text in images.** Reads the text in image attachments and describes them, so keyword and meaning search find them; shown in the note panel as "In this image". Needs a vision model.
- **AI-12 (plugin:tidy · M6) Tidy.** Cleans up dictated or pasted text without changing its meaning. A minute after a dictated note is saved, one "Tidy text" pill offers it, and a command does the same for any note. The change shows as a diff: "Keep mine" dismisses it, accepting applies it as an ordinary edit, and my version stays in history. Replaces the earlier clean-up story.
- **CAP-13 (plugin:email-in · M8) Email in.** Mail from allowlisted senders to a mailbox the plugin reads becomes a note, marked external for agents (D32). The mailbox password comes from the environment.
- **CAP-14 (plugin:telegram · M8) Telegram.** Messages from allowlisted accounts to the owner's own bot become notes, marked external for agents (D32). The bot token comes from the environment.

#### Organize

- **AI-3 (plugin:label-suggestions · M4) Label suggestions.** After a save, the plugin proposes up to three labels, strongly preferring labels already in use; a brand-new label is marked as new. Suggestions appear as dashed pills in the shared suggestion line under the note on web and Android, to accept or dismiss, offline too. Accepting appends the `#label` to the note. Suggesting never blocks or delays saving.
- **AI-10 (plugin:label-suggestions · M4) Auto-accept.** An opt-in setting that applies suggestions automatically when they are labels already in use. New labels always wait for the owner (D6).
- **LAB-5 (plugin:label-suggestions · M4) Unlabeled view.** A view of notes with no labels: the pile that label suggestions help clear. Because the plugin is on by default, the view starts hidden until I pin it.
- **AI-14 (plugin:title-summary · M6) Title and summary.** For long notes only (300 words and up): a proposed title as a pill and a short summary in the note panel, never a separate note.
- **AI-15 (plugin:label-gardener · M6) Label gardener.** Once a week, at most three proposals in Review: merge labels that look like one (through the core rename and merge dialog), or a new label for a cluster of unlabeled notes. A refused proposal is never made again. It absorbs the brief's "emerging topics".
- **AI-13 (plugin:duplicates · M6) Duplicates.** Finds notes that say the same thing, from the vector index alone, and proposes each pair in Review to compare and merge. Never automatic.
- **LAB-7 (plugin:links · M6) Note links.** "Link to note…" links one note to another, and a Backlinks section in the note panel lists the notes that link here, including follow-ups from `develop` and `translate`.
- **SRC-8 (plugin:tasks · M2) Tasks view.** Collects notes with open checkboxes into a Tasks view, with a "2 of 5 done" token on their meta line. Ticking boxes stays core (CAP-8).

#### Recall

- **SRC-2 (plugin:semantic-search · M4) Search by meaning.** The same box also ranks notes by meaning. This is hybrid search: keyword and semantic results blended into one list, with no separate "AI search" mode; a hit that matched by meaning only is marked "≈ similar meaning". MCP search goes hybrid too.
- **AI-6 (plugin:related · M4) Related notes.** Opening a note shows up to five related notes in its panel ("About this note" on Android), computed from vectors alone (no chat call). Agents get `related_notes` by note ID.
- **AI-8 (plugin:ask · M4) Ask your notes.** Ask a question from search or the palette and get an answer that cites the notes it used as links, or says "Nothing in your notes covers this." Read-only: it cannot change notes. The answer is forgotten unless I save it as a note, which is then machine-written (§6.5); there is no history of questions.
- **SRC-5 (plugin:resurface · M2) Resurfacing.** Once a day the daily slip at the top of the timeline (and on the widget) shows an older note, picked at random with a bias toward notes not seen for a while, to reread, act on or dismiss for the day. No AI needed. Off by default (D31).
- **SRC-7 (plugin:resurface · M2) On this day.** When a note from the same date in an earlier year exists, the slip may show it as "On this day" instead.
- **AI-11 (plugin:digest · M6) Weekly digest.** On a chosen day and time, a note of themes, open questions and threads worth continuing, each linked to its source notes; skipped in quiet weeks, and labels I list are left out. It is a machine-written note, collected in a Digests view, and agents read the latest through `digest_latest`.

#### Develop

- **AI-16 (plugin:develop · M6) Develop this idea.** A command on a note that writes a linked follow-up note (questions, next steps, angles) from the note and its related notes. The original is never touched.
- **AI-17 (plugin:translate · M6) Translate.** Italian to English and back, shown as a command result and savable as a linked note.

#### Agents

- **MCP-10 (plugin:agent-inbox · M2) From agents.** A view of the notes agents created or changed, with "Mark reviewed". Offered when the first access token is made.
- **MCP-11 (plugin:webhooks · M7) Webhooks out.** Sends note events to URLs the owner configures (n8n, Hermes); private notes never.

#### Data

- **DATA-2 (plugin:blinko-import · M2) Import from Blinko.** A command shipped in the image (`spunto plugins run blinko-import --url ... --token ...`) pulls notes from a running Blinko through its REST API. It keeps created and updated times, inline `#tags` (same syntax, nesting included), pinned and archived state; downloads attachments into the volume and rewrites their links; folds Blinko's three note types into one, optionally labelling each with its old type; and skips trash and "Blinko AI" comments by default. A dry run reports counts first. Re-running updates instead of duplicating (keyed by Blinko ID), so it can run repeatedly until the owner cuts over. Embeddings backfill afterwards when `semantic-search` is on (AI-5). The API route is the default because it works against the live theficos instance without database credentials and avoids Blinko's `.bko` backups, which users report failing to import; verify it against Blinko 1.8.8 when building.
- **WEB-8 (plugin:blinko-import · M2) Blinko import from the UI.** The plugin's settings page runs the same import, dry run first, with the Blinko token set in the environment.
- **DATA-3 (plugin:markdown-import · M7) Import Markdown.** A zip or folder of `.md` files. Our own export round-trips without loss; Obsidian, Memos and Bear exports import on a best-effort basis, honoring front matter.

---

## 5. Scope: the core, then plugin waves

### 5.1 The core in one paragraph

The core is one loop done properly on every surface, complete with every plugin off. Capture a thought in seconds from the web, from Android (online or offline, from the share sheet or a text selection), or from a coding agent. Find it again by keyword, filter it by label, keep it private, undo any change through its history. It runs as one container with one volume and exports to Markdown whenever the owner wants to leave. Everything else is a plugin from a catalog of 25: dictation and quick capture are on from the first run, and with AI configured, label suggestions, search by meaning, related notes and Ask switch on too, which gives back exactly the AI the first plan had. The rest waits in Settings → Plugins.

Why the line sits here: something is core when a fresh install would be broken or unsafe without it, or when it is a service several plugins need that must be enforced in exactly one place (privacy, validation, sync). The check that keeps the line honest: with every plugin off, the app still captures, labels, searches by keyword, syncs offline, serves agents over MCP and exports (04 §2). The private flag (AI-9) and revision history (CAP-10) are core for that reason: the AI gateway and the undo story depend on them.

### 5.2 Milestones

No dates: Blinko keeps running, so each milestone ships when it is good. Each one ends in something the owner uses every day and can keep using if the next one waits.

| | Ships | Daily use from here | Done when |
|---|---|---|---|
| **M0 Skeleton and spikes** | Monorepo, CI, image, config validation, migrations, backup and restore (HOST-1, HOST-2, HOST-4, HOST-5) | none yet | The spikes in 02 pass, plus: a per-token, per-plugin MCP tool list seen correctly by Claude Code and Codex; enabling and disabling a test plugin without restart; on-device dictation in Italian and English on the owner's Android 16 phone (D22) |
| **M1 Core server and agents** | Notes, labels, revisions, merge, keyword search, REST API, export, tokens with the `status` scope, the seven MCP tools, theficos deployment (CAP-2, CAP-6, CAP-7, CAP-10, LAB-1, LAB-4, LAB-6, SRC-1, SRC-6, AI-9, MCP-1 to MCP-8, HOST-3, HOST-6 to HOST-8, DATA-1, DATA-4) | Agents capture and search over MCP | Criterion 5 (§8), without semantic search |
| **M2 Plugin host** | Loader, manifest validation, lifecycle, plugin storage, jobs and schedules, routes, plugin MCP tools, guarded fetch, environment pins (PLG-5); the first plugins, none needing AI: `blinko-import` (DATA-2, WEB-8), `agent-inbox` (MCP-10), `tasks` (SRC-8), `resurface` (SRC-5, SRC-7) | The owner's Blinko library is in and agents read it | The four plugins pass the contract suite; counts match the Blinko spot check (criterion 1, first half) |
| **M3 Web** | Core screens, the slot renderers, generated settings pages, Review, three themes (CAP-1, CAP-3 to CAP-5, CAP-8, CAP-9, LAB-2, LAB-3, SRC-3, WEB-1 to WEB-6, PLG-1 to PLG-4, PLG-6, THM-1, THM-2) | Daily capture and recall on the desktop | Playwright suite green; bundle under budget; every plugin off leaves a finished-looking app |
| **M4 AI gateway and wave 1** | Profiles and routing, validation loop, Anthropic adapter, vector index, health check (AI-1, AI-2, AI-4, AI-5, AI-7); `label-suggestions` (AI-3, AI-10, LAB-5), `semantic-search` (SRC-2), `related` (AI-6), `ask` (AI-8) | The first plan's AI, plugin by plugin | The fake-provider suite proves fail-closed for each plugin; the embedding-model check (D17) |
| **M5 Android** | The native app: offline replica, sync, capture entry points, slot renderer, cached manifest (AND-1 to AND-8, AND-11, AND-12, SRC-4); device plugins `dictation` (AND-15) and `quick-capture` (AND-9, AND-10) | The phone becomes the main capture surface; cutover when the owner chooses | 02's Android criteria, plus offline rendering of stored slots and views |
| **M6 Wave 2: organize, recall, develop** | `title-summary` (AI-14), `tidy` (AI-12), `duplicates` (AI-13), `label-gardener` (AI-15), `links` (LAB-7), `digest` (AI-11), `develop` (AI-16), `translate` (AI-17) | More AI, still one row of pills | Each passes the contract suite; the plugin API reaches 0.9 |
| **M7 Wave 3: capture breadth** | `link-enrichment` (CAP-11), `image-text` (AI-18), `voice-memos` (CAP-12), `markdown-import` (DATA-3), `webhooks` (MCP-11) | Shares, images and voice become searchable | Guarded fetch passes an SSRF test suite; vision and transcription checks on the status page |
| **M8 Wave 4: inbound and third party** | `email-in` (CAP-13), `telegram` (CAP-14); plugin API 1.0 frozen; third-party plugins and theme packs from `/data/plugins`, `/data/themes` or a derived image (THM-3); author docs and `spunto plugin check` | The owner's own small plugins | A sample third-party plugin passes the same suite as first-party ones |

Within a wave, plugins ship one at a time in the order listed. Cutover from Blinko is not a milestone: it happens after M5, when the owner is happy with the phone app (criterion 1). Mechanical chunks (tool schemas, the export format, plugin manifests and their contract tests, CI) are good Codex delegations.

**Not scheduled yet** (`later`): AND-13 F-Droid, AND-14 notes role, AND-16 bundled speech model, WEB-7 PWA, WEB-9 browser clipper, WEB-10 Italian interface, HOST-9 compose examples, HOST-10 Helm and one-click hosting, MCP-9 extras, DATA-5 Markdown mirror. Each joins a milestone when it earns its place.

---

## 6. Non-functional requirements

### 6.1 Offline behaviour

- **Android is offline-first.** The phone holds a full copy of all notes (trashed ones until the trash purges them), the label list and the plugin manifest. Capture (typed or dictated), edit, label, archive, search by keyword and the label tree all work with no network, and so does stored plugin output: suggestions can be shown and accepted, and panel sections, the daily slip and plugin views render from the local copy. Attachments, live plugin output (related notes, Ask, search by meaning) and plugin commands that call the server need the server; offline they are absent, never shown as broken.
- **"Saved" is honest.** Android reports a save only after the local database commit; the sync marker clears only after the server confirms. Blinko's "saved but lost" bug (#948) is the failure this rules out.
- **Web is online, drafts are local.** The web app reads and writes through the server. The composer persists drafts locally on every pause in typing and restores them on reload. Full offline web is out of scope (§7).
- **The server never depends on the internet.** With AI pointed at a local Ollama, or off, a server with no internet access runs the core and every plugin that does not exist to reach outside (`link-enrichment`, `telegram` and their like need the network they talk to).

### 6.2 Sync

- **IDs are created on the device** (time-ordered UUIDs), so notes made offline never collide and still sort by creation time.
- **Every note carries a version** assigned by the server. Clients send the version they edited from.
- **Push, then pull.** The outbox pushes changes, each tagged with an operation ID so a retried push never applies twice. The client then pulls everything changed since its last cursor, including deletions, label changes, plugin output (suggestions and annotations) and the plugin manifest's revision, so a switched plugin reaches every device.
- **Conflicts never lose text** (D5). If a body edit arrives based on an old version, the server keeps both: the newer text stays on the note and the other is saved as a linked "conflicted copy" for the owner to merge. Appends from agents apply to the latest version and never conflict, and so do accepted label and append suggestions; an accepted text change merges or becomes a conflicted copy like any edit. Flags (pinned, archived, trashed) resolve last-writer-wins.
- **When sync runs.** On app start and return to foreground, a couple of seconds after a local change, when connectivity returns, and periodically in the background through WorkManager (at Android's 15-minute minimum). The web app refreshes on focus and, while visible, waits on a change long-poll that answers as soon as anything changes, plugin switches included (spec §6.7), so it stays live without polling.
- **How fast.** A change made on one online, foregrounded client shows on another within 10 seconds. A first sync of 10,000 notes completes in under 30 seconds on Wi-Fi.
- **Sync never blocks the UI.** Failures retry with backoff; a persistent failure shows one quiet line ("3 notes waiting to sync", with the reason on tap).
- **Old apps keep working.** The sync API is versioned, and a server release must keep serving an Android app one release behind, because phones update later than servers. A plugin component newer than an old app knows falls back to one it does (04 §3.10).

### 6.3 Performance and latency targets

Reference hardware: a mid-range phone (Pixel 6a class) for performance, the owner's Android 16 phone for dictation (D22), and the theficos node. Server targets assume 50,000 notes.

| Interaction | Target |
|---|---|
| Android cold start to typing in the composer | ≤ 1.0 s median, ≤ 1.5 s p95 |
| Android warm start, or share sheet open | ≤ 300 ms |
| Save on Android (local commit, card visible) | ≤ 50 ms |
| Dictation on Android: mic tap to listening; spoken words to text on screen | ≤ 300 ms; ≤ 1 s typical (set by the phone's recognizer) |
| Save on web (server acknowledgement on LAN) | ≤ 150 ms p95 |
| Plugin hooks on save | add nothing to save latency: they run as jobs after the commit |
| Keyword search on the server | ≤ 100 ms p95 |
| Keyword search on Android (local copy) | ≤ 200 ms p95 |
| Hybrid search end to end, local Ollama embedding | ≤ 1 s p95; keyword hits shown first if the semantic half is slower |
| Related notes for an open note | ≤ 300 ms (vectors are precomputed) |
| Label suggestions visible after save | ≤ 10 s typical; never blocks anything |
| Enabling or disabling a plugin, seen on other online clients | ≤ 10 s |
| Web first load to a focused composer, cold cache, LAN | ≤ 1.5 s; initial JavaScript ≤ 150 KB gzipped |
| MCP read tool (excluding the embedding call for semantic search) | ≤ 300 ms p95 |
| Idle cost | effectively zero: no animation loops, no polling faster than every 30 s, server idle CPU under 5 millicores |

Bundle size, APK size and image size budgets (§6.6) are checked in CI so they cannot drift (the image's from M2, when first-party plugins start shipping in it).

### 6.4 Privacy and security

- **No telemetry.** No analytics, no crash reporting to third parties, no calls home. Outbound traffic goes only to the configured AI profiles and to the destinations of enabled plugins, and Settings → Plugins lists each one with what it sends.
- **AI off means nothing leaves.** With no provider configured, no note content leaves the server. With AI on, note text goes only to the profiles the owner configured, each named on the status page and on the page of every plugin that uses it; a profile outside the private network is flagged "leaves this network". The private flag (AI-9) keeps single notes out of every AI call and away from every plugin that can send text off the instance.
- **Logs never contain note content,** token values or provider keys. Secrets appear in logs only as short fingerprints. Plugins log codes and counts, never free text.
- **Tokens:** random, shown once, stored hashed, accepted only in the `Authorization` header, scoped read-only, read-write or `status` (monitoring only), revocable, with a last-used time. Device tokens are listed and revocable like access tokens. No access token can switch a plugin on or off; only a web session or the owner's own device can.
- **Secrets come from the environment.** Provider API keys and plugin secrets (`SPUNTO_PLUGIN_<ID>_<KEY>`) are never stored in the database and never sent to clients. Android never calls the AI provider directly.
- **Login hardening.** Password hashed with argon2id, and at least 8 characters wherever it is set, the environment included; rate limiting with growing back-off on failed logins; web session cookies `HttpOnly`, `SameSite`, and `Secure` on HTTPS; CSRF protection on cookie-authenticated writes. On theficos the app's own login guards a public hostname (as Blinko's does today), so this is not optional.
- **Plain HTTP on a LAN works at first run** (D33). When `SPUNTO_PUBLIC_URL` is `http://`, login uses a plain cookie, every screen shows a persistent "Not encrypted" line, and Android connects only after an explicit warning. A request that arrives on a host other than the configured one gets a clear page naming the right address, instead of a login that silently does nothing.
- **No side doors.** The server never spawns a process from configuration or from user, API or MCP input (Blinko's CVE-2026-23882 came from letting a configuration field run a command).
- **Guarded fetch** (D30). The server reaches a URL only through one guarded fetch in the core, only for enabled plugins that declare it, and never because of agent input. The fetch blocks private and internal addresses, re-checks every redirect, caps size and time, and sends no cookies. Links in notes from agents or email are fetched only when the owner runs the command.
- **Plugins are trusted code, installed by the operator.** First-party plugins ship in the image and are reviewed with the core. Third-party ones are Python in the server process, possible only from M8 and only by whoever runs the server: a folder or a derived image, never an upload from the UI (D25, D27). Their permission list is a contract and a review aid, not a sandbox. No plugin JavaScript runs on the web, and nothing is loaded at runtime on Android (D26).
- **Outside text is marked for agents** (D32). Every MCP hit and note carries its origin (owner, agent, plugin or external); external text such as mail, Telegram messages or fetched pages is fenced as quoted data, and a token can exclude external notes altogether.
- **On the phone:** the database lives in app-private storage and the device token in the Android Keystore.
- **Dictation stays on the phone.** Speech becomes text on the device; the app keeps no audio and uploads none unless the owner turns on `voice-memos`; the microphone is asked for on the first mic tap and used only while a visible dictation is running. A recognizer that could send audio off the phone is opt-in (D23).
- **No end-to-end encryption.** The server reads notes in plain text, because search and AI run there; the protection is that the server belongs to the owner (§7).

### 6.5 AI is optional, and safe when on

- The whole test suite runs with no provider configured and must pass.
- Plugin work, AI or not, runs as jobs after the save commits. There is no hook before a save, so nothing ever sits between the owner and their text.
- Model output never becomes text in the owner's notes without an explicit accept (or the opt-in auto-accept of AI-10, which is limited to existing labels).
- **Machine-written notes are marked** (D29). Plugins may write new notes: a digest, a saved answer, a follow-up, a translation. Such a note carries a "via <plugin>" chip, is set in the sans, and is left out of every plugin's AI input and out of resurfacing until the owner edits it, which makes it theirs. Unattended output (schedules, hooks) is capped at five notes a day per plugin.
- Every structured response is validated (AI-4). A fake provider that returns 200 and ignores the schema is part of the test suite, and every plugin's test passes only if no note, suggestion or annotation changes.
- AI calls are rate-limited server-wide (a token bucket, as in Quire) with a share per plugin, so a 5,000-note import backfill cannot saturate a shared Ollama.
- **Per-plugin routing is for speed, not privacy** (D16). Embeddings stay local (`embeddinggemma` on the theficos Ollama). Chat and vision default to Ollama cloud; every other provider stays supported. That one chat recipient serves every plugin, unless the owner routes a heavy, slow job such as a digest to a model that is good enough. Over time label suggestions and digests send essentially every non-private note to the chat recipient, so the private flag, not routing, is the per-note opt-out.
- The embedding model is a setting, not a constant: the docs recommend a multilingual model when notes are not all English, and changing it re-indexes in the background (AI-5).

### 6.6 Self-host footprint and resource budget

**Target: one container and one volume.** Compare Blinko on theficos today: three pods, about 105 millicores and 528 MiB requested, two persistent locations.

| Item | Budget |
|---|---|
| Containers or pods | 1 |
| Persistent storage | 1 volume at `/data` (database, attachments, local backups); an optional second mount for off-volume backups |
| Server image | ≤ 150 MB compressed per architecture, every first-party plugin included |
| Server memory | ≤ 128 MiB idle with the default plugin set on, ≤ 256 MiB during import or re-indexing (suggested limit 512 MiB); re-measured in M4, once the default set exists |
| Server CPU | about zero idle; indexing concurrency capped by a setting |
| Disk for 10,000 text notes with vectors | under 200 MB before attachments, plugin storage included |
| Android APK | ≤ 15 MB |
| Android background work | WorkManager jobs only; no persistent service, no wakelocks |
| Web initial JavaScript | ≤ 150 KB gzipped, with the three themes and the slot renderers |
| Time from README to first note, newcomer | ≤ 5 minutes |

Scale the design must handle comfortably: 50,000 notes, 1,000 labels, 5 GB of attachments, one owner with up to five devices and a few agents, and every plugin in the catalog switched on.

### 6.7 Backups and durability

- **Before every migration,** core or plugin data, an automatic snapshot (HOST-5).
- **Scheduled snapshots,** daily by default: a consistent copy of the database taken while the server runs, written to `/data/backups` or a configured directory, with retention (default 7 daily and 4 weekly).
- **Plugin data lives in the same database file** (host-managed storage, D28), so every snapshot and restore includes it, and deleting one plugin's data touches nothing else.
- **Attachments are write-once files** named by content, so any file-level backup tool (borg, restic) can copy the volume safely; the database snapshot is the consistency point.
- **Restore is one documented command,** and CI restores a snapshot and checks note counts on every release.
- **The Markdown export** (DATA-1) is the human-readable last resort.
- **On theficos,** the database lives on a local volume (SQLite on the soft-mounted NFS share would risk lock and corruption trouble, as Blinko's vector file does today), and the backup directory points at the NFS path that borg already copies off-site. This also fixes the known gap that local volumes sit outside the borg backups.

### 6.8 Design quality, accessibility and language

- **Design rules** (detailed in the design documents, 03 and 05): one reading typeface for note bodies with a serif and a sans option, line length 60 to 72 characters, body around 17 px on web and 16 sp on Android, at most two weights in normal use, three themes (Carta, the warm-paper default; Ardesia; Salvia), each with a light and a dim dark set and one accent with the other colors derived from it (pure-black option on Android), motion of 150 to 250 ms on web and spring motion on Android, hairline borders instead of heavy boxes. Themes change colours only: the serif marks the owner's words and the sans the app's, notes plugins wrote included. Plugin output uses the core's components in the core's slots and never brings its own colour, icon, size or motion.
- **Accessibility:** WCAG 2.2 AA contrast in every theme and both modes (a theme pack that fails is refused); every web action reachable by keyboard; TalkBack labels on every control, with suggestions naming the plugin that made them; Android font scaling to 200 % without clipped text; reduced-motion settings respected.
- **Language:** English interface (Italian later, WEB-10); note content in any language; dictation in Italian and English (AND-15); accent-insensitive search (SRC-1). Timestamps stored in UTC and shown in local time.

### 6.9 Compatibility

- Browsers: the last two major versions of Chrome, Firefox and Safari, desktop and mobile.
- Android: 8.0 (API 26) and later, targeting the current API level. On-device dictation (AND-15) needs Android 12 or later (13 or later for language downloads and continuous sessions); older phones dictate only through the opt-in online recognizer (D23). The owner's phone runs Android 16, so it dictates with the platform's on-device recognizer (D22).
- AI providers: anything that speaks the OpenAI-compatible chat, embeddings, transcription and vision endpoints, plus Anthropic through a native adapter (D15); Ollama is tested in CI against a real instance.
- MCP clients: Claude Code and Codex are tested before each release; other clients that support streamable HTTP with a bearer token should work.

---

## 7. Non-goals

- **More than one user per instance:** no teams, sharing, public pages, comments, follows or federation. A second person runs a second instance (D8).
- **Real-time collaborative editing.**
- **iOS, desktop and watch apps,** and a Play Store listing. The web app covers desktops and iPhones.
- **Structure beyond labels:** no folders, notebooks, note types, typed objects, properties or graph view.
- **Task management** beyond Markdown checkboxes: no due dates, reminders or projects.
- **A heavy editor:** no WYSIWYG, diagrams, math rendering or embeds.
- **A plugin or theme marketplace, installing code from the UI, and plugin code on clients** (no plugin JavaScript on the web, nothing loaded at runtime on Android). Plugins and themes themselves are in (§4.10, §4.11); they are added by whoever runs the server.
- **Notifications, badges or sounds from plugins,** and any plugin surface outside the core's slots.
- **AI that edits the owner's text without an accept:** plugins propose, and may write new notes that are marked as theirs (D29), but no in-app agent edits or deletes notes, no generated prose is inserted into a note the owner wrote, no image generation, and no chat as the main interface.
- **The app as an MCP client,** or anything that spawns processes from configuration.
- **Listening in the background,** or a wake word: dictation runs only while visible.
- **End-to-end encryption,** because search and AI run on the server.
- **A hosted service or billing.**
- **Full offline mode or in-app dictation on the web** (the operating system's own dictation works in the composer).
- **Postgres or S3 storage,** or more than one server replica (D1).
- **A proprietary backup or export format:** export is plain Markdown.
- **Read-later and full-page web archiving,** which is Karakeep's job.

## 8. Success criteria

1. **Cutover.** The owner's Blinko library is imported with nothing lost (note counts match; a spot check of 20 notes covering nested tags, attachments, pinned and archived notes passes), and Blinko is decommissioned on theficos when the owner chooses, after M5.
2. **Footprint.** On theficos the app runs as one pod requesting at most 50 millicores and 128 MiB with the default plugin set on, against Blinko's three pods and 528 MiB.
3. **Daily habit.** The phone is the main capture surface: at least five captures a week from Android for four consecutive weeks, counted from the notes' own source field (no telemetry).
4. **Speed.** The latency targets in §6.3 are met on the reference phone and in the server benchmark run in CI.
5. **Agents.** On a clean machine, Claude Code and Codex each connect with the one documented command and, in a single session, save an idea and answer "what did I note about X" using the tools; a default search answer stays under 2,000 tokens.
6. **Trust.** An automated sync suite (offline edits on two devices, concurrent edits, retried pushes, app killed mid-sync) loses no text. The schema-ignoring fake provider never changes a note, suggestion or annotation, for any plugin.
7. **AI off.** The full test suite passes with no provider configured, and a fresh install with no AI shows only the core: no broken or error-state AI controls anywhere, AI plugins listed only as "Waiting" in Settings → Plugins.
8. **Self-hosting.** A newcomer following the README reaches a first note with `docker run` in under five minutes, over plain HTTP on a LAN too, and CI restores a backup on every release.
9. **AI usefulness, per plugin.** After two weeks with a suggesting plugin on, at least half of its suggestions are accepted, measured per plugin from the accept and dismiss decisions the app already stores. A plugin that falls short is tuned or switched off.
10. **Dictation offline.** On the owner's Android 16 phone in airplane mode, with AI off on the server, a 30-second dictation in Italian and one in English each show live text while speaking and land in a saved note that syncs once the network is back.
11. **Plugins hold the contract.** Every first-party plugin passes the contract suite in CI on every pull request.
12. **Switching off leaves no trace.** Disabling any plugin removes all of its output from every screen, and switching it back on restores that output with no backfill.

---

## 9. Decisions

The owner settled every decision on 2026-10-13, each at its recommended option unless the redesign changed it. This is the record; the options and their consequences stay in the documents that raised them.

### 9.1 Raised by these requirements

| ID | Decision | Chosen | Why |
|---|---|---|---|
| D1 | Where the server keeps its data | SQLite only, with FTS5 and `sqlite-vec`; not on NFS | One container, one volume, and a backup that is one consistent file copy |
| D2 | How the Android app is built | Native Kotlin and Compose | Capture on the phone is the heart of the product, and only native gives the share target, selection action, widget and tile |
| D3 | Server language | Python with FastAPI | Reuses Quire's debugged AI client and auth, and plugin authors get the language self-hosters already script in |
| D4 | Where labels live | The text is the truth | One mental model and portable exports; renames are rare |
| D5 | Two devices editing one note | Version check plus conflicted copy, flags last-writer-wins (merge first, D12) | Zero text loss for the least machinery |
| D6 | Applying AI labels | Suggest; opt-in auto-accept for labels already in use | Low effort once trusted, and new labels always need a human |
| D7 | Agent writes over MCP | Direct, additive, with provenance | Capture from an agent stays one step, and the worst case is clutter, never lost text |
| D8 | Users per instance | One owner | No per-user scoping bugs; a second person runs a second instance |
| D9 | Owner login | Local password plus tokens; OIDC for web later | Works for every self-hoster with no identity provider |
| D10 | Source of truth | The database, with export | Sync correctness; the Markdown mirror (DATA-5) can recover hackability later |
| D11 | Licence | AGPL-3.0 | Keeps hosted forks open, consistent with Galley |

### 9.2 From the spec and the redesign, cited here

| ID | Decision | Chosen | Why |
|---|---|---|---|
| D15 | Anthropic support | A native adapter in the AI gateway (M4) | Schema-constrained output without a lossy shim |
| D16 (revised) | Where note text goes for AI | Embeddings local (`embeddinggemma` on the theficos Ollama); chat and vision default to Ollama cloud; transcription unbound until `voice-memos` needs a Whisper-compatible server; every provider stays supported and per-plugin routing can override | The owner chose Ollama cloud for chat (what Quire and Hermes already use); embeddings are the bulk of the calls and must stay on one stable model, so they run locally |
| D19 | Private notes and MCP | Hidden from MCP by default; per-token opt-in | "Private" means private from every reader the owner did not pick |
| D22 | Dictation engine | Android's on-device recognizer; the M0 spike confirms the Italian and English packs on the owner's Android 16 phone | Android 16 has it, so no bundled model is needed (AND-16 waits) |
| D23 | Online recognition where on-device is missing | Ask once per phone; off until accepted | Audio never leaves the phone unannounced |
| D24 | Keeping audio, server transcription | Not in the core; the opt-in `voice-memos` plugin does both | The owner's voice stays out of backups unless chosen |
| D25 | How third-party plugin code runs | First-party only until M8, then in-process Python; the manifest keeps a `runtime` field | One container and simple authoring, with remote plugins still possible later |
| D26 | Plugin JavaScript on the web | Never, for plugin API 1 | Every plugin looks native on both clients and the surface stays calm |
| D27 | Who installs third-party code | The operator only, as a folder or a derived image | A stolen browser session can never become code on the server |
| D28 | Plugin tables | Host-managed storage only | Purge, backup and the note budget stay guaranteed |
| D29 | Machine-written notes | Marked, and excluded from AI inputs and resurfacing until the owner edits them | No digest of digests; the pile stays the owner's own thinking |
| D30 | Server fetching URLs | One guarded fetch in the core, for enabled plugins only, never on agent input | Rich capture with SSRF protection in one place |
| D31 | What is on by default | Core plus `dictation` and `quick-capture`; with AI, also `label-suggestions`, `semantic-search`, `related`, `ask` | An AI install matches the first plan and shows nothing the owner did not have before |
| D32 | Prompt injection over MCP | Origin marking and fencing for everyone; excluding external notes is a per-token option | Agents can tell what is untrusted, and nothing the owner shared on purpose disappears |
| D33 | Plain HTTP on a LAN at first run | Allowed, with a persistent "Not encrypted" line and a clear wrong-host page | The five-minute first note works anywhere, and nobody mistakes it for a finished setup |
| U1 to U5 | Themes and plugin UX (05 §10) | Themes replace the five-accent picker; themes cannot change typefaces; views from default-on plugins start hidden; a one-time "yours now" line on adopting a machine-written note; Ask answers kept only when saved | A calm first run, and faces that keep telling whose words a note holds |

The rest of the spec's register (D12 to D14, D17, D18, D20, D21) is in 02 §17, and the full options for D25 to D33 in 04 §8.

---

## 10. Name

**Spunto**: Italian for the cue or spark that starts an idea ("prendere spunto", to draw inspiration), which is exactly what the app collects; short and easy to say in English. It joins a family that leans on craft and Italian words (galley and quire from printing and bookbinding, scalo and comanda from Italian daily life). The wordmark is lowercase, "spunto." (03 §11.8).

Identifiers follow it: the `SPUNTO_` environment prefix (plugin secrets as `SPUNTO_PLUGIN_<ID>_<KEY>`), the `spunto` Python package with first-party plugins under `spunto.plugins` (also the entry-point group for third-party ones) and the `spunto` command, the `spunto` token prefix, the image `ghcr.io/vitofico/spunto`, the Android application ID `io.theficos.spunto`, the `spunto` MCP server name, and `spunto.example.org` in examples. Availability was checked in M0 (`docs/spikes/M0-name.md`): the GitHub repository, the PyPI name and the Android application ID on Play and F-Droid are free; `spunto.com`, `.it` and `.io` are taken, which blocks nothing because each instance lives on its owner's host, while `getspunto.com` and `spuntoapp.com` are free; trademarks are pending with the owner.

---

Takeaway: Spunto is Blinko's capture loop rebuilt as a minimal core that keeps four promises (it never loses a thought, it fits in one container, its AI only proposes for the owner's text, and agents get a first-class door), with everything else as plugins that can only appear in fixed, budgeted places, shipped milestone by milestone while Blinko keeps running until the owner is ready.
