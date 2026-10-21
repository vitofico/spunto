# Landscape survey: note apps, capture, AI, design and MCP

Status: research input for requirements and specs. As of 2026-10-13.

Why this exists: before writing requirements for a minimal, AI-first note app (Android, web, easy self-hosted server, MCP for Codex and Claude Code), we want to know what the best tools already do well, which AI features actually earn their place, what "minimal and beautiful" looks like in concrete terms, and how other note apps expose themselves to coding agents.

Confidence: facts about MCP tool lists, Claude Code and Codex configuration, Memos, Reflect, Tana, Mem, Notion and Capacities were checked against current docs or changelogs (see Sources). Items marked *(memory)* come from prior knowledge and should be spot-checked before they drive a decision. Items marked *(secondary)* come from third-party reporting, not the vendor.

---

## TL;DR for our app

1. **The proven shape is the "card timeline":** flomo started it, Memos and Blinko copied it. You open the app and the first thing on screen is an empty input box; notes are short untitled cards in a reverse-chronological stream, with `#labels` typed inline. That is the right base for "free notes to collect ideas". Our edge has to come from capture speed, AI that tidies up after you, and craft.
2. **Capture must ask zero questions.** No title, no folder, no label at capture time. AI and the user sort things out later. On Android this means a share target, a text-selection action, a Quick Settings tile, a widget with a voice button, and the system "default notes app" role (Android 14+) so capture works from the lock screen.
3. **The AI that helps is quiet and reviewable:** label suggestions drawn from your existing labels, hybrid keyword and semantic search, a "related notes" panel, random or spaced resurfacing, voice-to-clean-note, and a weekly digest with links back to the source notes. The gimmicks write text you did not think, or reorganize things without asking.
4. **Every AI output gets validated against a schema before it touches data.** Some Ollama cloud models return HTTP 200 and ignore `json_schema`. The app must reject, retry once, then fail closed (no labels applied, note untouched) instead of trusting the response.
5. **"Minimal and beautiful" is mostly typography, restraint and motion.** One reading typeface, a 60 to 72 character line length, warm neutrals with one accent, and 150 to 250 ms motion. On Android, Material 3 Expressive gives the motion and shape system for free.
6. **For MCP, the winning pattern is a server built into the app, over streamable HTTP, authenticated with a personal access token.** Memos ships exactly this. Use a small set of curated, task-shaped tools in the style of basic-memory, not one tool per REST endpoint. Return compact results, because Claude Code warns once a tool result passes 10k tokens.

---

## 1. The landscape, one line each

### Note apps

| App | What it does best | Take for us |
|---|---|---|
| **Blinko** (baseline, runs on the owner's home server today) | Self-hosted AI card notes with RAG search (retrieval-augmented generation: the AI answers from your own notes), Ollama support, a Telegram bot for capture, and Tauri desktop and Android builds. | Keep the card model and local-LLM support. Fix what feels heavy: the UI density and the AI surface area. Its CVE-2026-23882 (an MCP config field could run arbitrary commands) is a warning: never let user config spawn processes. |
| **Memos** (usememos) | The leanest self-hosted capture timeline: one Go binary or container, SQLite by default, inline tags, a built-in MCP server. | The hosting bar to meet: a single container, SQLite default, one env var to start. Its MCP design is covered in section 4. Android is served by community clients *(memory)*, which leaves a gap. |
| **Obsidian** | Local Markdown files you own forever ("file over app"), plus a huge plugin ecosystem. 1.12 added an official CLI that talks to the running app. | Markdown as the portable body format. Easy export as a folder of `.md` files. |
| **Bear** | The most beautiful Markdown editor on Apple platforms: hidden syntax, inline nested `#tags/like/this`, a custom typeface and polished themes. | The typographic bar, and inline nested tags as the only organizing primitive. Apple-only, no first-party AI or MCP. |
| **Apple Notes Quick Note** | Capture from anywhere (hot corner, Control Center, a corner swipe on iPad). It records the app or web page you were on and shows the note again when you return there. iOS 26 added Markdown export and call-recording transcripts. | The "context backlink": when capture comes from a share or a selection, store the source URL or app and show it on the card. |
| **Reflect** | Fast, calm daily-notes app with AI backlink suggestions. MCP for coding agents (read and search in March 2026, edit in April 2026). In July 2026 it went open source as Reflect Open: local Markdown, bring-your-own AI provider, CLI. | Proof that "notes plus MCP for Claude and Codex" is a feature users now ask for. Its private-note flag keeps marked content away from AI, which is worth copying. |
| **Mem** (Mem 2.0, Oct 2025) | AI-first resurfacing. "Heads Up" shows related notes while you write; Deep Search finds notes by meaning; Clean Up tidies a messy note in one click; Voice Mode. No Android app. | Related-notes-while-writing and one-click cleanup are the strongest AI ideas in the category. Mem has left the Android slot open. |
| **Capacities** | Typed objects (person, book, idea) with properties, a daily note used as an inbox, and an AI assistant that proposes creates, edits and deletes for you to approve (release 68). | "AI proposes, human approves" is the right contract for AI edits. Typed objects are more structure than we want. |
| **Tana** | Supertags: a tag that carries fields and AI instructions. Its voice memo turns speech into a structured node with fields filled in. Official local MCP server (alpha). | Voice memo to structured note. Labels that can carry an AI hint ("#idea: suggest a next step"). |
| **Google Keep** | The fastest capture on Android: widgets, Wear, the first app to qualify as the system's default notes app (lock-screen capture), and Gemini "Keep Live" voice brain dumps *(secondary)*. | The Android capture bar to match or beat. |
| **Notion AI** | Workspace-scale agents: Custom Agents (GA May 2026) on schedules and triggers, AI Meeting Notes that kick off agents, and a hosted MCP server with OAuth. | The MCP tool naming (`notion-search`, `notion-fetch`, ...). Otherwise the opposite of minimal. |

### Also worth knowing (not in the brief, directly relevant)

| App | What it does best | Take for us |
|---|---|---|
| **flomo** | Originated the card-memo pattern: no titles, inline `#tag/sub`, and a "Daily Review" that resurfaces random old memos *(memory)*. | The resurfacing ritual. Memos and Blinko both descend from it. |
| **mymind** | No folders at all. The AI tags everything you save, search works by keyword, color and topic, and "Serendipity" resurfaces old saves at random. Calm, serif-forward design. | Closest to the "AI organizes, you just save" philosophy, and a strong visual reference. |
| **Drafts** | Opens to a blank page every time. Text first, decide where it goes later. | Cold start lands in the composer with the keyboard up. |
| **Karakeep** (formerly Hoarder) | Self-hosted bookmarks with LLM auto-tagging and summaries through Ollama or any OpenAI-compatible provider. | Same self-host and Ollama audience. Its docs show the Ollama setup pitfalls our users will hit too. |

### Existing MCP note servers

| Server | What it does best |
|---|---|
| **basic-memory** | The reference design for "notes as agent memory": Markdown files are the source of truth, with curated tools for write, read, edit by section, hybrid search, `build_context` (follow links from a `memory://` URL to a chosen depth), and `recent_activity`. |
| **Memos built-in MCP** | Zero-install: `https://<instance>/mcp` over streamable HTTP with a bearer token. 20 tools generated from the REST API's OpenAPI schema, with read-only and destructive annotations. |
| **mcp-obsidian** (MarkusPfundstein) and similar | Vault access through the Obsidian Local REST API plugin: list, get, search, patch at a heading, append, delete. Needs Obsidian running. Newer servers wrap the official 1.12 CLI (80+ operations behind one tool). |
| **Notion hosted MCP** | Remote, OAuth, hosted by Notion at `mcp.notion.com/mcp`: search, fetch, create and update pages, plus database and comment tools. |
| **Tana Local API/MCP** | Official, but runs inside the desktop app at `localhost:8262/mcp`, approved through OAuth in the app. Alpha, and only works while the app is open. |
| **Reflect MCP** | Enabled in the desktop app settings, with one-line setup documented for Claude Code and Codex. Search, read, then edit. |
| **Bear and Capacities community servers** | Show the fragile end of the spectrum. Bear servers read its private SQLite database and break on app updates. Capacities v1 servers stopped working when the beta API shut down on 2026-09-01. |

---

## 2. Capture UX: frictionless free-form idea collection

**Principle.** Capture is measured in seconds from "I have a thought" to "my thumbs are typing". The budget is under 2 seconds from a warm start. Capture asks no questions: titles, labels and links come later from AI or from the user. Capture must never lose text: it saves locally first and syncs when it can.

### Android

| Pattern | Mechanism | Who does it well | Priority |
|---|---|---|---|
| Open straight into the composer | Launcher opens to an empty input with the keyboard up. A long-press on the icon offers "New note" and "Voice note" (static app shortcuts). | Drafts, Memos, flomo | P0 |
| Share sheet | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` for text, URLs and images. Shows a small bottom sheet with an optional comment, saves in one tap, and returns you to the source app. Sharing shortcuts can surface recent labels as direct targets. | Keep, Blinko | P0 |
| Text-selection action | `ACTION_PROCESS_TEXT` adds "Save to <app>" to the menu that appears when you select text in any app, saving the selection without leaving. | Few note apps do this, which makes it an easy win | P0 |
| Quick Settings tile | A `TileService` that opens the composer. On Android 14+ it must launch through `startActivityAndCollapse(PendingIntent)`. Reachable from anywhere with one swipe. | Keep | P1 |
| Home-screen widget | A Jetpack Glance widget with "type" and "voice" buttons, optionally showing the last note or a resurfaced one. | Keep, Tana (widgets also on the lock screen) | P1 |
| Lock-screen capture and stylus | Android 14+ `ROLE_NOTES`: become the user's default notes app and handle `Intent.ACTION_CREATE_NOTE`. This unlocks the lock-screen note shortcut and the stylus button, works without unlocking, and allows system screenshot capture into a note. | Keep (first to qualify), OneNote | P1 |
| Voice | Hold-to-record from the widget or composer. Short clips use on-device `SpeechRecognizer` for live text; long memos are uploaded and transcribed on the server. Keep the audio and raw transcript, then offer an AI cleaned-up version. | Tana voice memo, Keep Live, Mem Voice Mode | P1 |
| Offline queue | Write to local storage first, sync with retries, never show a spinner before the save. | Keep | P0 |
| Notification quick reply | An optional ongoing notification with an inline reply field (`RemoteInput`). | Niche, power users | P2 |
| Wear OS tile | Voice capture from the watch. | Keep | P2 |

### Web

| Pattern | Mechanism | Priority |
|---|---|---|
| The home page is the composer | The input sits pinned at the top of the timeline. `Cmd/Ctrl+Enter` saves, `Esc` blurs, and focus returns to the input after saving. | P0 |
| Command palette and single-key shortcuts | `Cmd/Ctrl+K` for search and actions, `n` or `c` for a new note, `/` for search. Linear made this pattern the norm. | P0 |
| Paste and drop anything | Paste a URL and fetch its title and preview later in the background. Drop images. Paste Markdown. | P0 |
| Installable PWA with Web Share Target | The `share_target` entry in the web app manifest makes the installed web app a share destination. Chromium browsers only, mostly Android. A useful bridge before the native app ships. | P1 |
| Browser extension or bookmarklet | Clip the current selection plus its URL (same idea as Apple's context backlink). | P2 |
| CLI | `note "idea"` or `echo ... \| note` against the server API. This doubles as the path for scripts and agents, and is the only system-wide hotkey route for web-only users (bind it in Raycast, Alfred or the shell). | P1 |
| Inbound channels | Email-in, or a Telegram bot like Blinko's. | P2 |

**Anti-patterns to avoid:** a required title field; picking a folder before saving; modal dialogs over the composer; a login wall in front of the widget or tile; a slow cold start (a heavy WebView shell on Android hurts here); any path where unsynced text can be lost.

---

## 3. AI-first patterns: what genuinely helps versus gimmicks

**Ground rules that cut across everything below:**

- AI never blocks capture. All enrichment runs asynchronously after the save.
- Every AI change is attributed ("suggested by AI") and reversible. The original text is never overwritten without an explicit accept.
- The app is fully usable with AI switched off. The AI provider is pluggable: anything OpenAI-compatible, plus Ollama.
- Structured output is validated (with zod or pydantic) before use. On a schema failure: retry once with the validation error included in the prompt, then fail closed and log it. This guards against the known Ollama cloud behavior of returning 200 while ignoring `json_schema`.
- A per-note "private" flag excludes that note from every AI call (Reflect Open has this).

### Helps

| Pattern | Why it helps | References | Implementation notes |
|---|---|---|---|
| **Label suggestions from your own vocabulary** | You capture without tagging, and the stream still ends up organized. | mymind, Karakeep, Tana supertag autofill | Constrain the model to existing labels. New labels come back only as separate "proposals", which stops label soup. Suggestions appear as chips to accept or dismiss, with an optional auto-accept above a confidence threshold. Store each decision so future prompts can use it as an example. |
| **Hybrid search** (exact keyword plus meaning) | You find notes by what you meant, not only the words you used, while exact matches stay instant. | basic-memory (hybrid is its default), Mem Deep Search, Blinko RAG | Full-text index plus embeddings, merged with reciprocal rank fusion (a standard way to blend two ranked lists). Keyword search must work with no AI configured. |
| **Related notes while writing** | Connects a new idea to old ones at the moment it is cheapest to link them. | Mem Heads Up, Obsidian Smart Connections plugin | A passive side panel or a strip under the composer. Never a popup. Embeddings only, no LLM call needed. |
| **Resurfacing** | Idea collections die from never being reread. Resurfacing keeps them alive. | flomo Daily Review, mymind Serendipity, Readwise daily review, "on this day" | Start with random or spaced selection, which needs no LLM. Later, an LLM can pick "worth revisiting" notes. Show them in the widget and at the top of the timeline. |
| **Link suggestions** | Builds a web of ideas without manual `[[links]]`. | Reflect AI backlinks, Capacities | Suggest, never auto-insert. |
| **Voice to clean note** | Talking is the fastest capture. AI removes the filler. | Tana voice memo plus supertag, Keep Live, Mem Voice Mode | Keep the audio, the raw transcript and the cleaned version. The cleaned version is a suggestion. |
| **One-click clean-up and title** | Turns a messy brain dump into something readable on demand. | Mem Clean Up | On demand only, shown as a diff, undoable. |
| **Weekly digest** | Turns a pile of notes into themes, open questions and threads worth continuing. | Notion and Mem summaries; our own design | Every claim links back to the source notes. Readable in the app and over MCP (so Claude Code can pull "this week's ideas"). Optional email. |
| **Ask your notes, with citations** | Answers questions from your own material. | Capacities (shows sources), Blinko RAG | Answers must cite note IDs and show them. With no supporting notes, say so instead of guessing. |
| **Duplicate and merge suggestions** | Ideas get captured twice. Merging keeps the stream clean. | Few apps do this well | Embedding similarity above a threshold leads to a "merge?" suggestion. |
| **Agent access via MCP** | For this user, "AI-first" largely means Claude Code and Codex reading and writing ideas during real work. | Reflect, basic-memory, Memos | See section 5. |

### Gimmicks (skip, or keep far from the core)

| Pattern | Why it fails here |
|---|---|
| "Continue writing" and generated prose inside notes | It fills an idea store with text you did not think, and later lowers the value of search and resurfacing. |
| AI image generation (Capacities release 70 *(secondary)*) | Unrelated to collecting ideas. |
| Auto-reorganizing notes into folders without consent | Breaks the user's mental map. Trust is lost the first time a note "disappears". |
| Summarizing short notes | The summary of a two-line idea is longer and worse than the idea. Summaries belong to digests and long voice memos only. |
| Decorative graph views | Pretty on first open, rarely used afterwards. Related-notes lists do the same job better. |
| Chat as the only interface | Browsing and scanning the stream is the core loop. Chat is a side tool. |
| Unreviewed agentic edits | Capacities requires approval for AI edits, which is the right default. MCP writes from agents should be visibly attributed for the same reason. |
| Unbounded auto-tags | Every note gets three new tags, and the label list becomes useless within a month. |

---

## 4. Design references for "minimal and beautiful"

| Reference | What makes it work | What to steal |
|---|---|---|
| **iA Writer** | Typography is the interface. Purpose-built fonts (Mono, Duo, Quattro), a controlled line length, focus mode, almost no chrome. | The editor is the page. Body text gets the best typeface we can afford. |
| **Bear** | Warm themes, hidden Markdown syntax while editing, inline nested tags, a custom typeface, careful spacing. | Inline `#label/sub` as the only organizing primitive. Theme quality as a feature. |
| **Things 3** | Generous whitespace, one accent color, purposeful motion (the "Magic Plus" button you drag to the spot where the new item belongs), satisfying completion animations. | Motion that explains where things go. Restraint with color. |
| **Linear** | Keyboard-first, `Cmd+K` everywhere, dense but calm. Its redesign generates the whole theme in the LCH color space (a perceptually even color space) from three inputs: base color, accent and contrast. | Generate light and dark themes from a few tokens. Single-key shortcuts. Thin borders instead of heavy boxes. |
| **Tot** (Iconfactory) | Seven colored dots, each one a note. Extreme constraint as delight. | The courage to ship fewer features. Color as navigation. |
| **Drafts** | Always opens to a blank draft. | Composer first. |
| **mymind** | No folders. Masonry of visual cards, serif accents, generous negative space, calm and private in tone. | A visual card grid as an alternative view. Serif display type for warmth. |
| **flomo / Memos** | A timeline of small cards with a heatmap calendar. Low density per card, high scan speed. | The card anatomy: time, body, labels, source chip. |
| **Obsidian Minimal theme** (kepano) | Proves a power tool can look quiet: muted UI, strong text hierarchy, user-tunable accent. | Settings for line width, font and accent instead of dozens of themes. |
| **Readwise Reader** | Reading typography and comfortable measure on both web and Android. | The reading view for long notes and digests. |
| **Material 3 Expressive** (Google, 2025) | Android's current design language: spring-based motion physics, shape morphing, emphasized type, dynamic color. | Use it natively in Compose for the Android app instead of fighting the platform. It brings springs and shape morphs for free. |
| **Emil Kowalski** (Sonner, Vaul) and **Rauno Freiberg** (interaction design essays) | Web motion craft: short ease-out transitions, interruptible animations, no animation on high-frequency keyboard actions. | The web app's motion rules. |
| **Vercel Geist** | A coherent type plus grid plus color system, published as a design system. | A template for our own small token set. |

### Distilled into starting rules

- **Typography.** A neutral sans for UI chrome (Inter, or the system font). A reading face for note bodies: offer a serif (Literata, designed for Google Play Books, or Newsreader) and a sans (Inter or iA Writer Quattro, which is open-licensed). Mono for code (JetBrains Mono). Body around 17px on web and 16sp on Android, line-height 1.5 to 1.6, line length 60 to 72 characters. Two weights at most in normal use.
- **Density.** A comfortable card stream by default, with a compact toggle. Labels and timestamps are small and quiet. The text is the loudest thing on screen.
- **Color.** Warm off-white "paper" in light mode. A dim, non-pure-black dark mode, with an optional pure-black OLED mode on Android. One accent color, user-selectable, with all other tokens generated from it (Linear's LCH approach). Android uses dynamic color when the user opts in.
- **Motion.** 150 to 250 ms with ease-out on the web. Springs on Android (M3 Expressive). Animate state changes such as a saved card sliding into the timeline or a label chip being accepted. Never animate typing, search-as-you-type or other keyboard-driven high-frequency actions. Respect reduced-motion settings.
- **Chrome.** No permanent sidebars on mobile. On web, a collapsible label rail. Hairline borders, few shadows, no gradients.
- **Empty states and copy.** Short and friendly. Empty states teach the capture shortcuts.

---

## 5. MCP: how note apps expose themselves to coding agents

### 5.1 Three architectures in the wild

1. **Files on disk, and the MCP server reads the files.** basic-memory, and Obsidian via the Local REST API plugin or the 1.12 CLI. This is portable and greppable, but in the Obsidian case the desktop app must be running.
2. **Built into the app's own server, over streamable HTTP.** Memos (`/mcp`, personal access token), Notion (hosted, OAuth), and on the desktop Tana (`localhost:8262`, OAuth) and Reflect. Nothing extra to install, it works remotely, and permissions match the app's.
3. **Third-party wrappers around an app's private database or API.** Bear (reads its SQLite file) and Capacities. These are fragile: Bear servers break on app updates, and Capacities v1 servers died with the beta API on 2026-09-01.

For a self-hosted server that has to work from a laptop, a CI box or a phone, architecture 2 is the right fit. The MCP endpoint ships inside the same container as the API.

### 5.2 Tool inventories

| Server | Transport and auth | Tools |
|---|---|---|
| **basic-memory** | stdio locally, plus a cloud option | `write_note` (title, content, directory, tags, note_type, metadata, overwrite), `read_note` (by title, permalink or `memory://` URL, paged), `edit_note` (operations such as append, prepend, find-and-replace and replace-section, with optional `expected_checksum` for safe concurrent edits), `move_note`, `delete_note`, `search_notes` (text, title, permalink, vector or hybrid, filters by type, category and date, paginated), `build_context` (follow relations from a `memory://` URL to a depth, within a timeframe that defaults to 7 days), `recent_activity`, `list_directory`, `read_content`, `view_note`, project tools, `schema_validate/infer/diff`, plus `search` and `fetch` wrappers for ChatGPT compatibility. |
| **Memos** (v0.30+) | Streamable HTTP at `/mcp`, `Authorization: Bearer <PAT>`. Stateless and tools-only. | 20 tools generated from the OpenAPI spec and prefixed by service: `memo_list_memos`, `memo_create_memo`, `memo_get_memo`, `memo_update_memo`, `memo_delete_memo`, memo comments, attachments, reactions and relations, `attachment_*`, `user_list_memo_views`, `auth_get_current_user`. Carries read-only, destructive and idempotent annotations. Errors return `isError: true` with readable text. v0.30 dropped prompts and resources. v0.31 renamed a tool (`shortcut_list_shortcuts` became `user_list_memo_views`), which broke clients. |
| **mcp-obsidian** | stdio (Python), calls the Local REST API plugin on `127.0.0.1:27124` with an API key | `list_files_in_vault`, `list_files_in_dir`, `get_file_contents`, `search`, `patch_content` (insert at a heading, block or frontmatter field), `append_content`, `delete_file`. Larger community servers add periodic notes, recent changes and batch reads. |
| **Notion hosted** | Streamable HTTP at `mcp.notion.com/mcp`, OAuth | `notion-search`, `notion-fetch`, `notion-create-pages`, `notion-update-page`, `notion-move-pages`, `notion-duplicate-page`, plus database, comment and user/team tools. |
| **Tana** | HTTP at `localhost:8262/mcp`, OAuth approved in the desktop app | Read and write your Tana graph. Alpha, and unavailable when the app is closed, so no unattended runs. |
| **Reflect** | Local server enabled in the desktop app | Search and read (March 2026), then edit and write (April 2026). |

### 5.3 What works well in Claude Code and Codex

- **One-command setup over HTTP with a bearer token.** Both clients support it natively:
  - Claude Code: `claude mcp add --transport http spunto https://spunto.example.com/mcp --header "Authorization: Bearer $SPUNTO_TOKEN"`. Scopes are local (default), project (`.mcp.json`, shared through git), or user. `.mcp.json` supports `${VAR}` expansion in URLs and headers.
  - Codex: `~/.codex/config.toml` with `[mcp_servers.spunto]`, `url = "https://spunto.example.com/mcp"` and `bearer_token_env_var = "SPUNTO_TOKEN"`. Or `codex mcp add spunto --url ...`.
  - Both also support OAuth for streamable HTTP. Personal access tokens are enough for a single-user self-hosted server; OAuth can come later.
- **A small, task-shaped tool set.** About 8 to 10 tools that map to what an agent wants to do ("capture this idea", "find what I wrote about X", "append to that note"), not one tool per REST endpoint. Memos's 20 generated tools are complete but low-level. basic-memory's curated set reads better to a model. Codex users can trim a server with `enabled_tools` / `disabled_tools`, and Claude Code defers loading tool definitions through tool search, but descriptions still cost context and still steer behavior.
- **Compact outputs.** Claude Code warns when a tool result passes 10,000 tokens and caps it at 25,000 by default (`MAX_MCP_OUTPUT_TOKENS`). Text results over 50,000 characters are saved to a file instead. So search returns IDs, titles or first lines, labels, dates and short snippets, with pagination. Reading returns one note. Nothing dumps the whole corpus.
- **Section-level edits with concurrency control.** Append, prepend and replace-section instead of whole-note rewrites, plus an optional version or checksum so an agent cannot overwrite a newer edit made on the phone (basic-memory's `expected_checksum`).
- **Tool annotations.** Mark tools `readOnlyHint`, `destructiveHint` or `idempotentHint` (Memos does) so clients can auto-approve reads and confirm writes. Consider offering archive or trash only over MCP, never hard delete.
- **Stable IDs and URIs, plus continuity tools.** Stable note IDs (and a URI scheme like basic-memory's `memory://`), `recent_activity`, and a related-notes or context tool let an agent pick up "what was I thinking about last week" at the start of a session.
- **`search` and `fetch` aliases.** basic-memory adds these two generic tools for ChatGPT compatibility. They are cheap to add and widen the set of clients that work.
- **Provenance and scopes.** Notes written over MCP carry a source field (for example `via: claude-code`) and appear with a visible badge so the user can review them. Tokens are scoped read-only or read-write.
- **Tools first.** Coding agents mostly use tools. Memos removed prompts and resources in 0.30 without much loss. Resources can come later if useful.
- **No process spawning from config.** Blinko's CVE-2026-23882 came from an MCP feature that ran user-supplied commands. Our server should never execute anything derived from user or API input.
- **House know-how.** galley already ships a stdio MCP server built on `@modelcontextprotocol/sdk` with `registerTool`. The same SDK serves streamable HTTP, so tool-definition patterns and tests can be reused.

### 5.4 Candidate tool set (input for the spec, not a decision)

| Tool | Purpose | Kind |
|---|---|---|
| `search_notes` | Hybrid search with label and date filters, returns compact hits | read |
| `get_note` | One note with labels, links, source and version | read |
| `recent_notes` | Latest N notes or activity in a timeframe | read |
| `related_notes` | Notes semantically close to a note or to a piece of text | read |
| `list_labels` | The label vocabulary with counts | read |
| `create_note` | Capture: body plus optional labels and source. Server-side AI enrichment runs afterwards as for any note | write |
| `append_to_note` | Append, or replace a section, with an optional version check | write |
| `set_labels` | Add or remove labels on a note | write, idempotent |
| `archive_note` | Soft delete, reversible | write, destructive-lite |
| `get_digest` | The latest weekly digest, with note links | read |

---

## 6. Implications for the requirements phase

Questions this survey raises for the requirements and spec work:

1. **Storage truth.** Is the database the source of truth with Markdown export (Memos, Blinko), or are Markdown files the truth (basic-memory, Obsidian)? This drives sync, MCP edits and the "easy to host" story. SQLite plus a vector extension keeps hosting to one container. Postgres plus pgvector suits the owner's cluster but raises the hosting bar for everyone else.
2. **Android stack.** Native Compose (best capture integrations, M3 Expressive, the notes role, Glance widgets, a fast cold start) versus a shared web shell. quire already proves out the native path.
3. **Offline-first sync model** for Android: local store, an outbox of pending writes, and conflict handling with per-note versions (the same versions MCP edits rely on).
4. **AI provider abstraction**: OpenAI-compatible plus Ollama, an embeddings model choice, schema validation with retry and fail-closed behavior, and the per-note privacy flag.
5. **MCP auth**: personal access tokens with read and write scopes first; OAuth later if remote clients need it.
6. **Scope discipline**: which of the "Helps" patterns are v1 (likely label suggestions, hybrid search, related notes, random resurfacing, voice transcription) versus later (digest, link suggestions, merge suggestions, ask-your-notes).

Takeaway: the category already proves the card timeline, quiet AI and built-in MCP. The opening is to combine them with Keep-level Android capture, Bear-level craft, and Memos-level ease of hosting, in one small self-hosted package.

---

## Sources

- basic-memory MCP tools reference: https://docs.basicmemory.com/reference/mcp-tools-reference
- Memos MCP integration docs: https://www.usememos.com/docs/integrations/mcp
- Memos 0.31.0-rc.1 changelog: https://usememos.com/changelog/0-31-0-rc-1
- Blinko repository: https://github.com/blinkospace/blinko
- Blinko MCP CVE-2026-23882: https://releasealert.dev/cve/CVE-2026-23882
- mcp-obsidian: https://github.com/MarkusPfundstein/mcp-obsidian
- Obsidian 1.12 CLI guide (secondary): https://blog.wenhaofree.com/en/posts/articles/obsidian-1-12-cli-ultimate-guide/
- Obsidian MCP 2026 guide (secondary): https://mcp.directory/blog/obsidian-mcp-complete-guide-2026
- Tana Local API and MCP: https://tana.inc/docs/local-api-mcp
- Tana voice memo: https://tana.inc/docs/voice-memo
- Reflect blog (MCP March and April 2026, Reflect Open July 2026): https://reflect.app/blog
- Mem 2.0 launch: https://get.mem.ai/blog/mem-2-dot-0
- Mem 2.0 coverage: https://alternativeto.net/news/2025/10/mem-2-0-relaunches-with-smarter-note-taking-tools-offline-support-and-a-new-agentic-chat
- Capacities release 68: https://www.capacities.io/whats-new/release-68
- Capacities AI assistant docs: https://docs.capacities.io/reference/ai-assistant
- Capacities release tracker (secondary): https://releasebot.io/updates/capacities
- Capacities MCP (community, v1 shutdown note): https://mcpservers.org/servers/nyrest/capacities-mcp-enhanced
- Notion hosted MCP (ToolHive guide): https://docs.stacklok.com/toolhive/guides-mcp/notion-remote
- Notion release 2026-07-31: https://www.notion.com/zh-tw/releases/2026-07-31
- Google Keep Live (secondary): https://www.analyticsinsight.net/news/google-gemini-voice-tools-arrive-in-gmail-docs-keep-whats-new
- Android notes role and lock-screen capture: https://www.androidauthority.com/google-keep-notes-on-lock-screen-3418322/
- AOSP capture content for notes: https://source.android.com/docs/core/permissions/capture-content
- Apple Notes in iOS 26: https://9to5mac.com/2025/10/16/heres-everything-new-for-apple-notes-in-ios-26/
- Bear community MCP servers: https://enterprisedna.co/directories/mcp/vasylenko-claude-desktop-extension-bear-notes
- mymind help, how it works: https://mymind.helpscoutdocs.com/article/68-how-mymind-works
- Karakeep AI providers: https://docs.karakeep.app/guides/different-ai-providers
- Claude Code MCP docs: https://code.claude.com/docs/en/mcp
- Codex MCP docs: https://learn.chatgpt.com/docs/extend/mcp?surface=cli
