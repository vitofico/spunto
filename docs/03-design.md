# 03 · Design: UX and visual language

Status: revised 2026-10-13 to match the approved redesign: a small core plus plugins ([`04-redesign.md`](04-redesign.md)) and how plugin output and themes look ([`05-redesign-ux.md`](05-redesign-ux.md)). Inputs: [`01-requirements.md`](01-requirements.md), [`research/landscape.md`](research/landscape.md), [`research/blinko.md`](research/blinko.md), 04 and 05. Mockups: [`design/mockups.html`](design/mockups.html) for the core screens and [`design/redesign.html`](design/redesign.html) for plugins and themes (open them in a browser; they show an Android phone and a desktop browser, in light and dark). Dictation is the `dictation` device plugin, on by default; see F7, A13 and mockup A9.

Nothing is built yet. This document fixes how the app looks, moves and behaves; it does not change any code or the theficos cluster. It rests on decisions the owner has settled, listed with their reasons in §13.

Conventions: features that belong to a plugin carry its id in `code` (`ask`, `resurface`); everything else is core. The old (M), (S) and (C) scope marks are gone: the milestones of 01 §5.2 decide the order. A- and W-numbers name the Android and web screens of §5.2 and §5.3; in a "Mockup frames" column, or written as "mockup A9", they are frames in `mockups.html` (§12). R-numbers are frames in `redesign.html`.

Name: **Spunto**. The wordmark stays lowercase, "spunto." (§11.8); identifiers follow it (`SPUNTO_` variables, the `spunto` MCP server, `spunto.theficos.dedyn.io` in examples).

---

## 1. The design in one paragraph

Picture someone on a tram who has just had an idea. They pull down the notification shade, tap one tile, and a blank page is already waiting with the keyboard up. They type a sentence and tap Save; the page folds away and they are back where they were, in about two seconds. That evening, at the laptop, the same sentence sits at the top of a quiet column of text set in a book typeface, and underneath it two labels the app thinks fit are drawn with a dashed outline, waiting. One click and the dashed outline turns solid and moves into the note: now it is the owner's label, not the machine's guess. With eighteen plugins switched on, the scene is the same: plugins only take budgeted turns in a few places the core already draws, so the page is still a column of notes. Everything in this document serves that scene: **writing is instant, reading is pleasant, and anything the app proposes looks like a proposal until the owner says yes.**

## 2. Design principles

Each principle comes with the concrete rule it produces, so it can settle an argument later.

1. **The text is the loudest thing on screen.** Note bodies get the best typeface, the largest size and the darkest ink. Chrome (buttons, timestamps, counts) is smaller, lighter and set in a quieter sans. *Rule:* if a screen has something more prominent than the notes, it needs a reason.
2. **Capture asks nothing.** No title, no label, no folder, no type, no confirmation. *Rule:* every capture entry point opens with the cursor in the field and the keyboard up, and saves with one gesture.
3. **Serif is the owner's voice; sans is the app's, including notes the app wrote.** Anything the owner wrote, and anything an agent wrote at the owner's request, is set in the reading serif. Everything the app says (menus, hints, suggestions, answers) is set in the sans. A note written by a plugin (a digest, a saved answer, a follow-up) is set in the sans, carries a chip naming the plugin with the spark glyph, and ends with one quiet line saying so; the first time the owner edits it, it becomes theirs: serif, a plain chip, no line (§4.5). *Rule:* generated prose is never set in the serif, so a glance tells you whose words a note holds, even in the timeline.
4. **Proposals look like proposals.** Suggestions are drawn with a dashed outline and the sans; accepted labels are solid and live in the serif text. *Rule:* nothing dashed is ever stored in a note. The change from dashed to solid is the moment of consent.
5. **One accent per theme, used for meaning.** The accent marks labels, the primary action and focus. It never decorates. *Rule:* if a screen has more than three accent-coloured things that are not labels, remove some.
6. **Quiet until spoken to.** No badges, no red dots, no counters that grow on their own, no idle animation. The app tells you things in one line of plain text, once. *Rule:* an idle screen draws zero frames.
7. **Motion explains where things went.** A saved note slides into the timeline; an accepted label moves into the text; a sheet rises from where it was summoned. *Rule:* motion is 120 to 240 ms on web and springs on Android; keyboard-driven actions do not move (§9).
8. **Two native homes, one family.** Android follows Material 3 and feels like Android (back gesture, bottom sheets, springs); the web app is keyboard-first and feels like a well-made desktop tool. Both share type, colour, spacing and copy. *Rule:* never port a gesture or layout across platforms just for sameness.
9. **Every plugin off is a complete design.** With nothing switched on, no screen looks like it is missing something, and switching a plugin off leaves no trace on screen (its data stays). AI off is the common case: with no provider configured, the AI plugins wait in Settings → Plugins and appear nowhere else. *Rule:* R1 and R3 are the reference; every frame must still look finished with all plugin output deleted.
10. **Plugins borrow the furniture.** A plugin never picks a position, colour, icon, size or animation. It fills a slot the core already draws (§6.1), within that slot's budget, using the closed component vocabulary of 04 §3.4. *Rule:* if a plugin needs a surface no slot provides, the slot is designed in the core for every plugin, or the plugin does not ship. The only exceptions are the device plugins' mic, widget and tile, which are compiled into the app.

---

## 3. Information architecture and navigation

### 3.1 Objects and views

There are only four things the owner deals with:

| Object | What it is | Where it shows |
|---|---|---|
| **Note** | Markdown text with a creation time, flags (pinned, archived, trashed, private), an optional source and an optional agent | Timeline entries, the note view, search results |
| **Label** | A `#token` in note text, nestable with `/`. The label tree is an index built from text (D4) | Inline in notes, the label rail or sheet, autocomplete |
| **Suggestion** | A proposal from a plugin (label, title, link, text change, merge), pending until accepted or dismissed | The suggestion line under an entry, Review |
| **Settings** | Appearance (themes), Plugins, devices, access tokens, agent setup, AI status, data | Settings screens |

A **plugin** lives under Settings: it is a switch and a page in Settings → Plugins, never an object on the page. Everywhere else the owner sees only what it does.

Everything else is a **view** of notes: a filter plus an order. Views never contain other views, so navigation never gets deeper than two levels.

| View | Filter | Order | From |
|---|---|---|---|
| All notes (home) | not archived, not trashed | pinned first, then newest | core |
| Label | has `#label` or a child of it | pinned first, then newest | core |
| Search results | matches query (blended with meaning when `semantic-search` is on) | relevance | core |
| Archive | archived | newest | core |
| Trash | trashed, kept 30 days | most recently trashed | core |
| From agents | created or changed through MCP | newest | `agent-inbox` (MCP-10) |
| Unlabeled | no labels | newest | `label-suggestions` (LAB-5) |
| Tasks | has an unchecked `- [ ]` | newest | `tasks` (SRC-8) |
| Digests | written by `digest` | newest | `digest` |

Plugin views are declarative filters drawn as ordinary timelines (04 §3.5), so they work offline on Android. They sit in a **Views** group that exists only when a plugin brings one, and each can be hidden from its own menu. A view from a plugin the owner switched on by hand appears at once; one from a plugin that came on by default (Unlabeled) starts hidden until the owner pins it (U3). Review is not a view: it is the core screen where proposals from every plugin wait (L3).

### 3.2 Android navigation

One main screen, two sheets, and full-screen pages pushed on top. No navigation drawer, no tab bar.

```
Timeline (home, composer on top)
├── tap title "All notes ▾"  → Labels sheet (core views, Views group, label tree, Settings)
├── tap search icon          → Search (full screen)
├── tap an entry             → Note (full screen, edit in place)
│                               ├── "About this note" → note panel sheet → Note
│                               └── ⋮ or long-press   → note action sheet (core actions, plugin verbs)
├── "N suggestions · Review" → Review (full screen, every plugin)
└── Settings (from the Labels sheet) → Settings sections, Plugins, Appearance

Outside the app (each opens a translucent capture activity over whatever was on screen):
  Share sheet target · Text-selection action · Launcher shortcuts "New note" and "Dictate a note" · Quick Settings tiles and widget (`quick-capture`)
```

Typing `/` at the start of a composer line turns the action bar into a strip of plugin commands, drawn like label autocomplete.

Why no bottom navigation bar: there is nothing to switch between. The timeline is the app; labels are a filter on it; search is a mode of it. A title that doubles as the filter switch (like Gmail's account switcher, or Google Keep's label picker) keeps the screen for notes.

The system back gesture always does the obvious thing: closes a sheet, leaves search, leaves a note (saving it), clears a label filter back to All notes, then exits.

### 3.3 Web navigation

```
┌──────────────┬──────────────────────────────────────────┬──────────────┐
│ Label rail   │  Column (composer + timeline, or a note)  │  Note panel  │
│ 248 px,      │  measure ≈ 70 characters, centred         │  240 px,     │
│ collapsible  │                                           │  note view   │
│              │                                           │  only, ≥1280 │
└──────────────┴──────────────────────────────────────────┴──────────────┘
```

- **Rail** (left): wordmark, the search trigger (`⌘K`), All notes, Archive and Trash, a **Views** group only when a plugin brings one, the label tree, and at the bottom Settings plus one line of status ("Synced", "Synced · AI ready", "Indexing 1,240 of 5,000", "Offline"). `[` collapses it.
- **Column** (centre): the composer and timeline, a label or plugin view, or an open note. On a note, a small "← All notes" link replaces the composer.
- **Note panel** (right margin, note view only, ≥ 1280 px wide): one collapsible section per plugin (Related, Source, Backlinks, Summary), set like marginalia in a printed book. Each starts closed as an overline and a one-line summary ("Related · 5 notes"); "Arrange" orders them once. Below 1280 px the panel moves under the note.
- **Command palette** (`⌘K` or `/`): one box for search, labels, views, actions, plugin commands and Ask (`ask`). With a note open it leads with a "This note" group: plugin verbs, each with its plugin's name in grey, then core actions with their keys. It is the only modal on the web.
- **Composer command menu**: `/` at the start of a composer line lists the same plugin commands.

URLs are stable and shareable within the instance:

| Path | Screen |
|---|---|
| `/` | Home |
| `/l/project/spunto` | Label view (nested path) |
| `/n/01JA…` | Note (time-ordered UUID) |
| `/search?q=…` | Search results as a page (the palette's "see all") |
| `/archive`, `/trash` | Core views |
| `/review` | Review, every plugin's proposals |
| `/v/<plugin>/<view>` | Plugin views, for example `/v/agent-inbox/from-agents`, `/v/label-suggestions/unlabeled` |
| `/settings/{appearance,plugins,devices,tokens,agents,ai,data}` | Settings |
| `/settings/plugins/<id>` | A plugin's own page |
| `/status` | Owner-only status page (HOST-6) |

### 3.4 Breakpoints (web)

| Name | Width | Layout |
|---|---|---|
| Compact | < 600 px | No rail; a top bar with the title switch and search, like Android. Composer on top. Touch targets 44 px. |
| Medium | 600 to 1023 px | Rail hidden behind a button in a slim top bar; column centred. |
| Expanded | 1024 to 1279 px | Rail visible; the note panel under the note. |
| Wide | ≥ 1280 px | Rail visible; the note panel in the right margin. |

### 3.5 Keyboard map (web)

| Key | Action |
|---|---|
| `n` | Focus the composer |
| `⌘/Ctrl ↵` | Save (composer) or finish editing (note) |
| `⌘/Ctrl K`, `/` | Command palette |
| `/` at the start of a composer line | Command menu (plugin commands) |
| `⌘/Ctrl S` | Save the command result on screen (an Ask answer, a translation) as a note |
| `Esc` | Leave the field, close the palette or forget a command result, go back from a note |
| `j` / `k` | Next / previous entry in the timeline |
| `↵` / `o` | Open the focused entry |
| `e` | Edit the focused entry in place |
| `p` | Pin or unpin |
| `a` | Accept the focused entry's suggestion line |
| `x` | Dismiss the focused entry's suggestion line |
| `#` | On a focused entry, open "add label" |
| `y` / `Shift y` | Archive / move to trash (undo appears in a toast) |
| `[` | Collapse or expand the rail |
| `g` then `a`, `l`, `t`, `s` | Go to All notes, Labels, Trash, Settings |
| `?` | Show this list |

Single-letter keys are ignored while a text field has focus. Every one of them also has a visible control, so nothing is keyboard-only. Plugins get no keys of their own: their commands are reached through `⌘K` and the `/` menu.

---

## 4. Key flows

### 4.1 Capture

The target from requirements is "thought to typing" in under two seconds from a warm phone. The table is the budget we design and test against on the reference phone (Pixel 6a class).

| Entry point | Steps for the owner | Budget to typing | Budget to back where they were |
|---|---|---|---|
| Launcher icon | tap icon | ≤ 1.0 s cold, ≤ 0.3 s warm | n/a (stays in app) |
| Launcher long-press "New note" | long-press, tap | ≤ 0.8 s | n/a |
| Quick Settings tile (`quick-capture`) | swipe down, tap tile | ≈ 0.9 s (0.4 swipe, 0.2 tap, ≤ 0.3 open) | ≤ 0.3 s after Save |
| Widget (`quick-capture`) | tap "Write a note" | ≈ 0.5 s | ≤ 0.3 s after Save |
| Mic in the composer (`dictation`) | tap mic, speak | ≤ 0.3 s to listening | n/a |
| Launcher long-press "Dictate a note" (`dictation`) | long-press, tap, speak | ≤ 1.0 s to listening | ≤ 0.3 s after Save |
| Widget mic or "Dictate" tile (`quick-capture` with `dictation`) | tap mic; or swipe down, tap tile | ≈ 0.6 s; ≈ 1.0 s to listening | ≤ 0.3 s after Save |
| Share sheet | Share, pick spunto, tap Save | n/a (no typing needed) | ≈ 1.5 s total |
| Text selection | select, tap "Save to spunto" | n/a | ≈ 1 s total; never leaves the source app |
| Web | open tab or press `n` | ≤ 1.5 s cold load; instant with `n` | n/a |
| Agent | "save this idea" in Claude Code or Codex | n/a | appears on other devices within 10 s while foregrounded |

**F1. In-app capture (Android).** Launch lands on the timeline with the composer at the top, focused, keyboard up. The composer's action bar is docked to the top of the keyboard, so the Save button sits under the right thumb even though the text is at the top of the screen. Tap Save: the text becomes the first entry with a short spring (the entry slides down from where the composer was), the composer empties and keeps focus for the next thought. "Saved" is never announced with a toast: the entry appearing is the confirmation, because it is only drawn after the local database commit (AND-2). Until the server confirms, the entry's time carries a small hollow ring (§5.1).

**F2. Quick capture sheet (tile, widget, launcher shortcut).** These open a translucent activity: a compact sheet holding only the composer and its action bar, rising over a scrim on top of whatever app was showing. Save closes the sheet and returns there. Back with text in the field keeps the draft (CAP-4) and says so in one line the next time the sheet opens ("Draft restored"). The tile must be in the first row of quick settings to hit the budget; the onboarding card on first launch suggests putting it there.

**F3. Share into spunto.** The share sheet opens over the source app with the comment field **not** focused, so one tap on Save does the whole job. The shared thing is shown as a quoted block (page title and domain for links, the first lines for text). Below the comment field, a single row offers the three most recently used labels as optional toggles; they are never required. Tapping the comment field brings up the keyboard. Save closes the sheet and returns to the source app. Android's Sharing Shortcuts publish "spunto" as a direct-share target so it appears in the top row of the system sheet after first use.

**F4. Save a selection.** "Save to spunto" appears in the text-selection menu of any app. Tapping it saves immediately (the selection as a Markdown quote, the source app as the note's origin) and shows a small confirmation pill at the bottom of the screen for 2.5 s: "Saved to spunto · Add a thought". Tapping "Add a thought" opens the share sheet on the new note. The owner never leaves the app they were reading.

**F5. Web capture.** The home page opens with the composer focused. `⌘↵` saves; the new entry appears at the top of the timeline without movement (keyboard rule, §9) and gets a brief highlight that fades over 600 ms. Drafts are written to local storage on every pause in typing and restored on reload; when the server is unreachable a one-line banner says "Can't reach the server. What you type is kept on this device." and Save stays enabled, queueing the note in the draft store until the server is back.

**F6. Agent capture.** The owner tells Claude Code or Codex to save an idea. The note appears in the timeline within a second or two while the tab is visible (the change long-poll, spec §6.7), or on the next focus, with a "via Claude Code" chip (core) and, like any note, gets label suggestions a few seconds later when `label-suggestions` is on. With `agent-inbox` on, the From agents view collects them for a weekly look.

**F7. Dictate (Android).** The owner is out on a run, phone in hand, no signal. Long-press the icon, tap "Dictate a note": the capture sheet rises already listening, with no keyboard. They say "Idea per il weekend: mappa dei nasoni lungo il Tevere" and the words arrive in the composer as they speak, grey while the phone is still deciding and ink once settled. Save stops listening, waits a moment for the last words and saves; the sheet folds away. The same mic sits in the composer's action bar inside the app, where tapping it swaps the keyboard for the listening panel and dictates at the cursor, and tapping the keyboard glyph swaps back. Nothing about this needs the server or AI; the screen spec is A13. Dictation is the `dictation` device plugin: compiled into the app, on by default, and switched like any plugin. On the owner's Android 16 phone the platform's on-device recognizer is the engine (D22); an M0 spike confirms its Italian and English packs.

### 4.2 Labeling

**L1. While typing.** Typing `#` opens autocomplete. On web it is a small popover at the caret; on Android the composer's action bar turns into a horizontal strip of label suggestions right above the keyboard, which is exactly where the thumb is. Labels used most in the last 30 days come first, then the rest by overall use, filtered by prefix as you type; nested labels show the parent dimmed (`project/`**`spunto`**). Typing a name that does not exist offers "New label #urbanism", and just continuing to type works too. `Tab` or a tap completes; a space ends the label.

**L2. Accepting a suggestion inline.** With `label-suggestions` on, a few seconds after a save, up to three dashed pills appear on the entry's suggestion line: `+ project/spunto`, `+ idea`, `+ urbanism  new`. Tap or click a pill to accept it: it turns solid, slides into the label line at the end of the note and becomes text (the label is appended to the note body, D4). To dismiss just one, a small `×` appears on hover on the web, and a long-press does it on Android (TalkBack gets it as a custom action); "Dismiss" at the end of the row dismisses the rest. Ignoring them is fine: pending suggestions stay for 14 days, then quietly expire, and nothing in the UI nags about them. The line is shared with other plugins: Link enrichment's "+ Page title" or Tidy's wand pill sit in the same row, and what does not fit folds into "+N" (§6.1).

**L3. Review.** When three or more proposals from any plugin are pending, one line of text appears under the composer: "6 suggestions · Review", in `ink-2` with "Review" in the accent. Review is one core screen for every plugin (A8, W6, R11), grouped by kind, each group headed by an overline and the plugin's name in grey: Notes (label suggestions still waiting), Labels (merges and cluster labels from `label-gardener`), Duplicates (pairs from `duplicates`). Cards may be boxed, because they are proposals rather than notes. Each asks one question in the sans, quotes the owner's notes in the serif, and offers the change and its refusal ("Merge" / "Not the same", "Compare" / "Keep both"). A group shows three cards, then "4 more". Label merges and renames go through the L4 dialog and need a connection (offline the button reads "Needs a connection").

"3 notes wait for labels · One by one" opens the one-note-at-a-time mode: the note with its label pills, pre-selected when they are labels already in use and unselected when new. The owner taps to adjust, then "Apply" (or `a`) moves to the next; "Skip" (or `s`) leaves them pending; `x` dismisses them. A thin progress line shows position. When the queue is empty: "All caught up." and the line under the composer disappears.

**L4. Rename and merge.** From the label tree, the label's menu offers Rename, Merge into…, and Remove. The rename dialog shows the impact before anything happens: "Rewrites 18 notes. Child labels move too: #project/spunto/android." If the new name already exists, the dialog turns into a merge and says so. Confirming applies it in one transaction. A rename then offers Undo for 10 seconds (the inverse rename); a merge cannot be undone that way, because the inverse would also move notes that already had the target label, so the merge dialog says "This can't be undone" and offers no Undo. Matching is whole-label only, so renaming `#work` never touches `#workout` (LAB-4).

**L5. Browse by label.** Choosing a label in the rail or sheet filters the timeline and shows the label as the screen title in the serif ("#project/spunto", "18 notes"), with the label's menu beside it. Parents include their children's notes.

### 4.3 Finding

**S1. Search on web.** `⌘K` opens the palette; results update as you type with no animation. Notes come first: the first line in the serif, a snippet with the matching words highlighted, then date and labels. With `semantic-search` on, results that matched by meaning rather than by words carry a small "≈ similar meaning" note, so the owner understands why a note without the word "conflict" showed up for "conflict". `#label` in the query filters (`#reading local-first`). `↵` opens the highlighted note; "See all results" opens `/search`.

**S2. Search on Android.** The search icon opens a full-screen search with the field focused. Before typing it shows recent searches and the top labels as shortcuts. Results use the same anatomy as the web.

**S3. Offline search (Android).** With no connection, one quiet line sits above the results: "Offline. Searching this phone by keyword." There is no meaning-based ranking and no Ask row, and nothing is shown as broken.

**S4. Related notes (`related`).** Opening a note shows up to five related notes (vectors only, no chat call) as the Related section of the note panel: in the right margin on wide web screens, under the note on narrower ones, and in the "About this note" sheet on Android. Each is one line of serif text plus date and labels. When the note is not indexed yet, the section is simply absent.

**S5. The daily slip (`resurface`).** Once a day, the top of the timeline shows one older note on a slip of "paper" (a `sunken` box, the only boxed thing in the timeline), under an overline the host writes from the item's kind ("From your notes · March 2025", "On this day · 2024"), with Open, Archive and × ("not today"). The slot holds one item a day across all plugins, and dismissing is final until tomorrow. On Android the widget shows the same item. Only `resurface` uses it, and it is off by default (D31), first among the plugins worth trying.

### 4.4 Ask your notes (`ask`, AI-8)

1. The owner opens the palette and types a question, for example "what did I decide about sync conflicts?".
2. With three or more words typed, the first row reads "Ask your notes" with the spark glyph. If the query ends with `?` that row is highlighted, so `↵` asks; otherwise the top note is highlighted and `⌘↵` asks. The highlighted row always shows what `↵` will do.
3. While the answer is prepared the panel reads "Reading 8 notes…" with a three-dot pulse (the only waiting animation in the app; it stops when the answer arrives and is static with reduced motion).
4. The answer is a command result: it replaces the palette's list, in the sans at `ui-lg` (principle 3), with numbered citations in `accent-soft` chips. Below it the cited notes are listed in the serif with their dates, numbered, each clickable.
5. Footer: "From 3 notes · gpt-oss:120b at ollama.com, outside your network · read-only", naming the model and where it runs (D16). Actions: Copy and, as the main action, "Save as note" (`⌘S`).
6. If nothing relevant exists: "Nothing in your notes covers this." with "Search instead". The model is never asked to guess.
7. **Keep it.** "Save as note" writes a new note headed by the question, with the answer as the body and the citations as links to the notes. It enters the timeline as a machine-written note (§4.5) and is left out of later answers until the owner edits it, so Ask never cites itself (D29).
8. **Forget it.** `Esc`, or swiping the sheet down on Android. Nothing is stored without a save: no history of questions, no "recent answers" (U5).
9. Offline, the Ask row is absent. With the model unreachable: one line, "Couldn't reach the model. Search results are below."

Android gets the same through the search screen: an "Ask your notes" row at the top of the results, and the answer rises as a bottom sheet over them, with the results still underneath (R14). Private notes are never read; up to 8 notes go to the chat model per question, as the plugin's row in Settings → Plugins says.

### 4.5 Reviewing what agents and plugins did

- **Agent notes** carry a chip ("via Claude Code", "via Codex"; core) and, with `agent-inbox` on, gather in From agents. An agent's append to an existing note marks that note "edited via Codex" in its meta line until the owner's next edit (derived from who made the latest change, so no "seen" state needs storing or syncing). Revision history (CAP-10, core) is reached from the note's menu and also undoes agent and plugin edits.
- **Review** collects every plugin's proposals in one queue (L3); nothing a plugin proposes changes a note before the owner accepts it.
- **Auto-accepted labels (AI-10, a `label-suggestions` setting).** When the owner turns on auto-accept, labels already in use are applied directly and the note's meta line says "AI added #idea · Undo" until the note's next edit. New labels still wait as dashed pills.
- **Failures** are invisible in the timeline. A model that ignores the schema produces no suggestion, the note is untouched (AI-4), and the failure is counted on the status page, where the rail's status line links when the failure rate is notable ("AI: 3 failed calls today"). A plugin that fails five runs in a row pauses, and its row in Settings → Plugins says "Paused after 5 failed runs · Details".

**Machine-written notes (D29).** Saved answers, digests, follow-ups and translations are notes, but notes the owner did not write. They must read as such at a glance, and must not feed the next machine:

| | Machine-written | After the owner's first edit |
|---|---|---|
| Body | Sans (`ui-lg` on the web, 16 sp on Android) | Serif, like any note |
| Chip | Spark + "via Weekly digest" | Plain "from Weekly digest", no spark |
| Last line | "Written by Weekly digest with qwen3.5. Plugins leave it out until you edit it." in `ink-3` | Gone |
| AI inputs, resurfacing | Excluded | Included |
| Search, labels, views, export | Like any note | Like any note |

Editing adopts the whole note; there is no per-paragraph ownership. The first adoption ever shows one line under the note, once per account (U4): "This note is yours now: plugins can read it and it can resurface." Unattended output (schedules, hooks) is capped at five notes a day per plugin; notes the owner asked for ("Save as note", "Develop this idea") are not capped. Notes agents write through MCP keep the agent treatment above (serif, "via Claude Code" chip), because an agent writes at the owner's request.

---

## 5. Screen-by-screen spec

### 5.1 The entry (shared component)

The entry is the most repeated thing in the app, so it gets the most care. It is deliberately **not a card**: entries are blocks of text on the page separated by space and a hairline, like dated passages in a notebook. Boxes are kept for things that are not the owner's notes (the daily slip, Review cards, sheets, the palette).

```
 14:32   Widget idea: show one old note a day instead of the clock.      ⋯
         Resurfacing without opening the app, like a slip of paper
         left on the desk.
         #project/spunto  #idea                         ← label line (serif, accent)
         ↗ github.com · via Claude Code · ○             ← meta line (sans, ink-3)
         ┆+ android┆ ┆+ design  new┆ +1  Dismiss       ← suggestion line (sans, dashed)
```

| Part | Web | Android | Rules |
|---|---|---|---|
| Time | Hangs in a 64 px left gutter, tabular figures, `meta` style | Starts the meta line | Day headers carry the date, so entries show only the time; older than a week shows the date |
| Body | `body` serif, measure ≈ 70 ch | `body` serif, full width minus 20 dp margins | Markdown rendered. A leading `# Heading` becomes the entry title (`heading` style). Clamped at 8 lines with "Show more" |
| Inline labels | serif, accent colour, no background | same | Tap filters by that label |
| Label line | If the last line holds only labels, it is drawn as a separate row with 8 px gaps | same | Accepted suggestions append here |
| Links | ink with a `rule-strong` underline | same | Never accent, so links and labels stay distinct |
| Checkboxes | 16 px rounded square; checked = accent fill, text turns `ink-3` | 20 dp, 48 dp touch target | Toggle in place |
| Source chip | "↗ domain.tld" or "from Chrome" | same | Never fetches a favicon. The core never fetches URLs; only `link-enrichment` does, through the guarded fetch (D30) |
| Agent chip | ">_ via Claude Code" | same | Core. Opens the From agents view when `agent-inbox` is on |
| Plugin token | One short fact after the core chips: "23 min read", "2 of 5 done" | same | At most one per entry across all plugins, 24 characters, `meta` in `ink-3`, never a button or a colour; the owner's plugin order picks between two |
| Sync ring | n/a | 7 dp hollow ring after the time | TalkBack: "Not synced yet". Disappears on confirmation, no "synced" tick |
| Pinned | Pin glyph replaces the time in the gutter | Pin glyph leads the meta line | Pinned entries form a group at the top |
| Private | Lock glyph + "private" in meta | same | Kept out of every plugin's AI input |
| Conflict | "Conflicted copy · Compare" with a warn-coloured dot | same | Compare opens a side-by-side (web) or stacked (Android) view to merge |
| Actions | `⋯` on hover or focus: Pin, Edit, Copy, Archive, Trash | Long-press menu, plus swipe right to archive (with Undo) | Every swipe has a menu equivalent |
| Suggestion line | One row shared by every plugin: dashed pills, sans, a leading `+` for things that add or a wand for things that change the owner's words; what does not fit folds into a "+N" chip on `sunken`, which unfolds a second row for that note only; `×` on hover | same; long-press dismisses one | §6 |
| Machine-written | Body in the sans at `ui-lg`, spark chip "via Weekly digest", one closing line in `ink-3` | Body in the sans at 16 sp, same chip and line | Becomes serif with a plain chip on the owner's first edit (§4.5) |

Spacing: 20 px between entries, a `rule` hairline inset to the text column, 32 px above a day header.

### 5.2 Android screens

**A1. Timeline with composer (home).**
- Top app bar (64 dp): the current view as the title with a ▾ ("All notes ▾"), search icon on the right. The bar is the page colour and gains a hairline only once content scrolls under it.
- Composer: a raised surface with a 12 dp radius at the top of the list. Focused on launch; placeholder in the serif italic, "What's on your mind?". When the list scrolls, it collapses to one line and scrolls away with the content; a 56 dp FAB with the pencil glyph appears at bottom-right and scrolls back to it.
- Action bar (docked to the keyboard, 48 dp): `#` (inserts `#` and opens label suggestions), checkbox, image, mic (`dictation`, A13; absent when the plugin is off), and Save on the right as a filled accent pill. Save is disabled, not hidden, while the field is empty.
- Label autocomplete: replaces the action bar's tools with a scrollable row of label suggestions while a `#token` is being typed; Save stays put. The `/` strip works the same way for plugin commands, typed at the start of a line.
- Timeline: day headers ("Today", "Yesterday", "Saturday 11 October", "September 2026") in the `overline` style, entries (§5.1).
- Review line: "6 suggestions · Review" in `ink-2` with "Review" in the accent, only from three pending proposals across all plugins (L3).
- Daily slip: under the Review line, when `resurface` is on (S5).
- Sync problem line: "3 notes waiting to sync · Server unreachable", only after sync has failed for more than five minutes; tap for details.

**A2. Quick capture sheet** (tile, widget, launcher shortcut). A bottom sheet over a 32 % scrim: drag handle, the composer (3 to 8 lines, grows with text), the action bar with Save, then the keyboard. No timeline, no title. Swipe down or Back closes it and keeps the draft. Opened from "Dictate a note", the widget's mic or the "Dictate" tile, it starts in the listening state of A13 instead of with the keyboard.

**A3. Share sheet.** Bottom sheet with a 28 dp top radius over the source app:
- Header row: "Save to spunto" in `ui` medium; Save (filled pill) on the right.
- Shared item: a sunken block with the page title in the serif (2 lines max) and the domain or app name in `meta`, or the first 4 lines of shared text.
- Comment field: placeholder "Add a thought (optional)". Not focused on open.
- Recent labels: up to three outline pills; tapping one toggles it solid. Optional.
- After Save: sheet closes, the source app is back. No toast.

**A4. Selection saved pill.** A floating pill 16 dp above the gesture bar: check glyph, "Saved to spunto", and the text button "Add a thought". Auto-dismisses after 2.5 s. Not shown again if the owner saves another selection within the same minute; the count updates instead ("2 saved").

**A5. Note.** Full screen, edit in place.
- Top bar: back, then pin and `⋮`. No plugin adds an icon here.
- `⋮` or a long-press opens the note action sheet (R9): four core tiles (Pin, Copy, Share, Archive), then plugin verbs between two hairlines, each with its plugin's name in grey (six at most, then "More"), then Private, History and Move to trash.
- Meta header in `overline`: "SATURDAY 11 OCTOBER · 22:14", then source and agent chips and the one plugin token.
- Body in the serif; tapping anywhere in the text places the cursor there. While editing, the composer's action bar (A1) docks to the keyboard, mic included (A13). Every pause writes the local draft; the edit goes to the server after 5 seconds idle, on leaving the note, or every 60 seconds while typing, with a version check (edits that do not overlap merge; overlapping ones make a conflicted copy, spec D12), and the server folds a burst of saves into one revision (04 §9).
- The suggestion line, then one colophon line in `ink-3`, "About this note · Source · Related 5 · Backlinks 1", naming the note panel's sections. It opens the "About this note" sheet (R6): one section per plugin, each closed as an overline and a one-line summary, at most ten items inside, "Arrange" to reorder by drag, open state remembered on this phone. Sections with nothing to say are absent; with none at all, so is the colophon.

**A6. Labels sheet.** A modal bottom sheet, 90 % tall when expanded:
- A "Find a label" field at the top (filters the tree as you type).
- Core views: All notes (with its count in `ink-3`), Archive, Trash, each with a glyph.
- A "Views" overline and group, only when a plugin brings one: From agents, Tasks, Digests, each with a count in `ink-3`, never a badge. Views from default-on plugins (Unlabeled) start hidden; "Show more views" on the overline reaches them, and one tap pins one here.
- "Labels" overline with "Edit" on the right (enters rename, merge and remove mode).
- The tree: 48 dp rows, 16 dp indent per level, chevrons on parents, counts aligned right in tabular figures. The active view or label gets an `accent-soft` pill background with accent text.
- Footer row: Settings.

**A7. Search.** M3 search view, full screen: field focused with back arrow and clear; under it, before typing, recent queries and top labels; while typing, the Ask row (`ask`) then results. Each result: serif first line (2 lines max), a snippet with `highlight` marks, meta (date, labels, "≈ similar meaning" when applicable). Offline line as in S3. Ask's answer rises as a bottom sheet over the results (R14).

**A8. Review** (R11). Full screen: close `×` and "Review"; the queue grouped by kind, each group under an overline with the plugin's name in grey, three cards a group, then "N more" in the accent. "3 notes wait for labels · One by one" opens the one-by-one mode (mockup A7): "Suggestions", "3 of 12"; a 2 dp progress line; the note on a raised surface; the suggestions as 40 dp pills (existing labels pre-selected, new ones not); bottom bar with Skip (text button) and "Apply 2 labels" (filled). Swipe left skips, swipe right applies; both have the visible buttons as the primary path. Label-level cards need a connection; note-level ones work offline.

**A9. Settings.** One list page with sections: Appearance (R24: three compact theme cards, Carta, Ardesia and Salvia, and "Only on this phone"; Mode: System, Light, Dark; Pure black, enabled in dark only; Wallpaper colours (Android 12+); Reading face: Serif or Sans; text size), Plugins (R20: the list of §6.4 at thumb size, device plugins tagged "Android"; turning one on opens the permissions sheet, R21; each plugin's generated page, read-only offline), Server (URL, this device's name, Sign out), Sync (last synced, pending changes, "Sync now"), AI (read-only status lines: each AI profile, its host, and "on your network" or "outside your network"), About (version, licences, export link that opens the web export). Dictation's settings live on its plugin page, Settings → Plugins → Dictation: default language (Italian, English, or Automatic where the phone can detect it), each language's offline status with Download, and "Use online recognition" only on phones where it is the sole option (spec D23). Auto-accept lives on Label suggestions' page.

**A10. Sign in.** Server URL (with `https://` prefilled), owner password, device name prefilled from the phone model. Or "Scan pairing code". One button. Errors are one sentence under the field that caused them ("No server answered at that address. Check the URL, including the port."). An `http://` address connects only after an explicit warning (D33): "Not encrypted. Your password and notes cross this network in clear." with "Connect anyway".

**A11. Widget (`quick-capture`).** Two sizes. 4×1: a paper-coloured pill "Write a note" with the pencil glyph, and a mic at its end (with `dictation` on) that opens A2 already listening. 4×2: the same pill plus the day's slip from `resurface` in two serif lines under its overline ("From your notes · March 2025"); with `resurface` off, only the pill. Follows the system mode and the theme; uses wallpaper colours only if the app does. With the plugin switched off, a widget already on the home screen shows one line: "Quick capture is off. Turn it on in Settings → Plugins."

**A12. Quick Settings tile (`quick-capture`).** Label "New note", the pencil glyph, no state (always inactive). Opens A2. An optional second tile, "Dictate" with the mic glyph, opens A2 already listening; the owner adds whichever they use. With the plugin off, the tile reads as unavailable.

**A13. Dictation (`dictation`, AND-15).** A state of the composer, wherever its action bar appears (A1, A2, editing in A5). Mockup A9.
- *Start.* Tap the mic, or arrive from a dictation entry point (A2). The keyboard slides away and a listening panel takes its place at the same height, so the text does not move.
- *Action bar while listening.* The keyboard glyph on the left (back to typing, which stops listening), the language switch `IT | EN` next to it (a third segment, `Auto`, only where the phone can detect the language), and Save on the right. Save stops, waits up to 1.5 s for the last words and saves: still one gesture.
- *Panel.* A 72 dp stop button (accent fill, an `on-accent` rounded square), inside an `accent-soft` halo whose size follows the voice level; under it "Listening in Italian" in `ui`, `ink-2`, and one `meta` line with the lock glyph: "On this phone · audio not kept" ("Online · audio goes to the phone's speech service" when D23's fallback is in use).
- *Text.* Words stream in at the cursor in the serif, because they are the owner's words (principle 3). Words still being recognised are `ink-3` and turn `ink` once final, without moving. No dashed outline and no spark: dictation is input, not a proposal, and it stays available with AI off.
- *Stopping.* The stop button, Save, the keyboard glyph, touching the text (the caret lands where touched and the keyboard returns), leaving the app or locking the screen, or 10 s without speech, which leaves "Stopped after a pause" in the action bar for 2.5 s. Whatever was on screen stays, in the draft (CAP-4).
- *Language.* Switching applies at once; settled words stay. The choice is remembered on this phone as the next default.
- *Permission.* The first mic tap opens Android's own microphone dialog directly; there is no explainer screen first, because the tap is the context. Allowed: listening starts with no second tap. Refused: the panel shows "Dictation needs the microphone. Audio stays on this phone." with "Allow". Once Android stops asking: "Microphone access is off for spunto." with "Open settings".
- *Unavailable.* No recogniser on the phone (and no consent to an online one): the mic is absent, not greyed out, and the Dictation plugin's page explains. Switched off, the mic leaves the action bar. Language not downloaded yet: the panel offers "Italian needs a one-time download to work offline · Download" and, meanwhile, the other language. Offline needs no state of its own, because recognition runs on the phone; only a phone using the online fallback (D23) shows an offline line (§8.3).
- *Feel.* A light haptic tick at start and stop; the app plays no sounds. Android's own microphone indicator is the only other signal.

### 5.3 Web screens

**W1. Home.** Rail, then the column: the composer (raised surface, 14 px radius, hairline border; focused on load, placeholder in serif italic), its tool row (`#`, checkbox, image on the left; a "/ commands" hint when a plugin brings commands, the "⌘↵" hint and Save on the right), the Review line, the daily slip (`resurface`), pinned entries, then the timeline grouped by day. Infinite scroll loads 50 entries at a time with no spinner (the next page is fetched before the end is reached).

**W2. Label view.** Same as home, but the composer is pre-filled with the label on focus (typing in `#project/spunto` view starts the note with that label at the end), and the column starts with the label as a title, its count, and a `⋯` menu (Rename, Merge into…, Remove).

**W3. Note.** "← All notes" link, meta header, body in edit-in-place mode (click to edit; the source is shown as Markdown with light syntax colouring: `#labels` in accent, markup characters in `ink-3`), the suggestion line, and the note panel in the margin or below (R7). Autosave as on Android (A5). A small line under the body: "Saved" in `ink-3` that appears after each save and stays (no fading toast).

**W4. Command palette.** 640 px wide, 12 % from the top, `elevation-3`, over a 32 % scrim. The input in `ui` at 17 px with a search glyph; sections: This note (only with a note open: plugin verbs first, each with its plugin's name in grey on the right, then core actions with their keys; R8), Ask (`ask`), Notes (up to 6), Labels (up to 4), Views (including hidden ones), Actions (New note, Go to Archive, Toggle dark mode, Export…, and plugin commands with their plugin's name). Plugin verbs are ordered by use, three per plugin at most; ones that need the server are absent offline. Footer with key hints. Opens instantly from the keyboard, with a 120 ms fade and scale from 98 % when opened by mouse.

**W5. Command result** (R12). Ask and commands such as Translate answer in a panel that replaces the palette's list; the palette grows to 720 px. For Ask: the question in serif italic `body-sm` at the top, the answer in the sans at `ui-lg`, citations in `accent-soft` chips, sources in the serif, the footer naming the model and its host, Copy, and "Save as note" (`⌘S`) as the main action. One result at a time; `Esc` returns to the results with the query kept and forgets the result.

**W6. Review** (`/review`). The centre column shows the same grouped queue as A8, with the one-by-one mode's Apply, Skip and Dismiss buttons and their keys, and progress at the top.

**W7. Archive and Trash.** Ordinary timelines with a title. Trash adds one line: "Notes here are deleted for good after 30 days." and each entry offers Restore. "Empty trash" sits at the end of the list, never at the top, and asks for confirmation that names the count.

**W8. Settings.** Two columns: a section list on the left (Appearance, Plugins, Devices, Access tokens, Agents, AI, Data) and the section on the right in the column measure.
- *Appearance* (R23): the theme picker first. Three cards in a row, each a small live page in its own colours and the current mode: a time, a line of serif text, a label, a dashed proposal, and five swatches (paper, ink, accent, accent-soft, highlight), with the name and one sentence under it. The selected card has a 2 px accent ring and a check. The theme is an instance setting that changes every device; "Use a different theme on this device" turns the cards into a per-device choice, and a grey line then says "Other devices use Carta." Then mode (System, Light, Dark), reading face (Serif, Sans) and text size (S, M, L: body 16, 17, 19 px), all per device. Packs the operator adds in `/data/themes` appear as more cards; one that fails the contrast check is shown disabled, naming the failing pair ("`ink-3` on `sunken` is 4.1:1 in dark"). An Accent row appears only for a pack with more than one accent; no built-in has one.
- *Plugins* (R17, R19, R22): the list, the permissions dialog and each plugin's page (§6.4).
- *Devices:* each signed-in device with name, last seen, and Revoke.
- *Access tokens:* name, scope (read-only, read-write, or status for monitoring), created, last used, Revoke; "New token" asks for a name, the client (Claude Code, Codex, script or monitor), a scope and two options, "Leave out notes from outside (email, Telegram, shared pages)" (D32) and "Include private notes", off by default (D19), then shows the token once in a mono field with Copy and the line "You won't see this again."
- *Agents:* "Connect Claude Code" and "Connect Codex" blocks with the exact command and config for this instance, URL filled in, each with Copy (WEB-5). A short note on scopes, the "via" chip, and that agents see outside text marked as such (D32). Below, the tools enabled plugins add (`digest_latest`, `related_notes`), each with a switch that hides it from agents.
- *AI:* status rows per AI profile (chat and embedding model with measured dimension, its host, "on your network" or "outside your network", and the plugins routed to it), index progress, validation failures in the last 7 days, each with a plain sentence and a fix hint when red. With AI off: "AI is off. Set the `SPUNTO_AI_*` variables and restart to turn it on." and a docs link.
- *Data:* Export (one button, makes the zip). Imports are plugins: `blinko-import` and `markdown-import` have their own pages under Plugins.

**W9. Status page** (`/status`). The HOST-6 facts as a quiet two-column list: version, note count, database size, index progress, AI health (from the real health checks), last backup with its path, validation failures, and each plugin with its state, last run, AI calls and failures. No charts.

**W10. Sign in.** The wordmark, one password field, one button. The setup-code variant (HOST-3) asks for the code printed in the log first. Opened at an address other than the server's public URL, the server shows a plain page instead of a login that silently fails: "This server lives at https://spunto.theficos.dedyn.io. Open it there to sign in." (D33).

**W11. Offline banner.** A 36 px band under the top of the column on `sunken`: "Can't reach the server. What you type is kept on this device." Disappears by itself on reconnect. On plain HTTP (D33) the same band stays for good: "Not encrypted · This server uses plain HTTP. Fine on your own network; set up HTTPS before using it elsewhere." Android shows the same line under the timeline's top app bar.

---

## 6. The clutter contract

Every feature beyond notes, labels and search is a plugin, AI or not, and **plugins never choose where they go**. The core draws a handful of quiet places that exist without any plugin; a plugin fills one of them, inside a budget the host enforces, with components the host draws (04 §3.4). A slot that no enabled plugin uses draws nothing: no header, no empty state, no gap.

### 6.1 The slots

| Slot | Android | Web | Looks like | Budget and overflow | Mockup frames |
|---|---|---|---|---|---|
| Suggestion line | Under an entry and at the end of a note | Same | Dashed sans pills; `+` or wand glyph picked by the host; grey "Dismiss" ends the row | One row for all plugins, ranked by the owner's plugin order, then confidence; the rest fold into "+N", which unfolds a second row for that note only; at most 3 pending per plugin per note | A2, W1, R5, R7, R15 |
| Review | One line under the composer; a full screen | One line under the composer; `/review` | "6 suggestions · Review"; grouped cards (L3) | One line, from three pending up; one screen; "N more" after three cards a group | R2, R4, R11 |
| Note panel | "About this note" colophon and sheet | Right margin from 1280 px, else below the note | One section per plugin: overline and a one-line summary when closed; may end with a credit line in `ink-3` ("Link enrichment · fetched 18:03 · summary by qwen3.5") | Closed until opened, open state remembered per device; at most 10 items; absent when empty; never "loading" | R5, R6, R7 |
| Meta token | The entry's meta line | Same | One short fact in `meta`, `ink-3` | One per entry across all plugins, 24 characters, no colour, never a button | R5, R7 |
| Daily slip | Top of the timeline, and the widget | Top of the timeline | 03's slip: `sunken` box, overline written by the host, the note in the serif, Open, Archive, × | One item a day across all plugins; dismissal final until tomorrow; no model text, no plugin name | W1, A8, R10 |
| Commands | Note action sheet, composer `/` strip | ⌘K palette ("This note" group), composer `/` menu | Rows with the plugin's name in grey | Three per plugin per place, titles of at most 24 characters, ordered by use; six on the Android sheet, then "More"; absent offline when they need the server | R8, R9 |
| Command result | Bottom sheet | Panel inside the palette | Sans throughout, citations in `accent-soft` chips, cited notes in the serif, a footer naming the model and its host; Copy and "Save as note" | One at a time; nothing stored unless saved; `Esc` or swiping down forgets it | W4, R12, R14 |
| Views | "Views" group in the labels sheet | "Views" group in the rail | An ordinary timeline of entries; counts in `ink-3` | One per plugin, hideable; hidden at first when the plugin came on by default (U3) | R10 |
| Settings page | Settings → Plugins → name | Same | Generated from the plugin's schema plus host-drawn sections (§6.4) | One page | R19, R22 |
| Search source | "≈ similar meaning" on a hit | Same | A marker in `ink-3` | No surface of its own | A6, W3 |
| New notes | Timeline entry with a "via" chip | Same | Machine-written note (§4.5) | Five unattended notes a day per plugin; owner-started ones uncapped | R10, R13, R16 |
| Device surfaces | Composer mic, widget, Quick Settings tile | None | Core controls, compiled into the app | `dictation` and `quick-capture` only | A1, A8, A9 |

What has **no slot at all**, so no plugin can ask for it: a toolbar or top-bar button, a FAB, a header icon, a badge or unread count, a notification or sound, a banner, a toast of its own, a modal that opens by itself, an onboarding tour, a colour or icon of its own, a keyboard shortcut of its own, and any animation beyond the host's.

**Off, offline and failing.** A disabled plugin's output disappears on the next sync while its data stays. Live output (Related, Ask, search sources) is absent offline and while its model is unreachable, never shown as broken; stored output (suggestions, panel sections from storage, views) works offline on Android. Failures are counted on the status page and on the plugin's row in Settings → Plugins, never in the timeline.

### 6.2 The rules

1. **Dashed means proposed.** A dashed outline in the accent, sans text, and a leading glyph the host picks: `+` for things that add (a label, a title, a link), a wand for things that change the owner's words. Nothing else in the app uses a dashed outline; the "+N" chip is drawn on `sunken` without dashes, because it is not itself a proposal.
2. **The spark means "a model reads or writes here".** It marks the Ask row, the chip on a machine-written note and the AI line on a plugin's row in Settings, and nothing else. Suggestions do not get sparkles, gradients or a special AI colour; their dashed outline already says "proposed". The spark is never decoration.
3. **Machine words in the sans.** Answers and machine-written notes are set in the sans at `ui-lg`. Citations jump into the owner's notes, which are in the serif. The difference is visible from across the room.
4. **Never in the way.** Suggestions arrive after the save, below the note. They never move the composer, never take focus, never pop up, and never appear while the composer is focused on that note. If the owner is typing in the composer when suggestions for the previous note arrive, they fade in with no height animation so the composer does not jump.
5. **One row for all plugins.** Overflow folds into "+N" and unfolds only when tapped, for that note only; leaving the note folds it again. There is never a second row the owner did not ask for.
6. **Easy to ignore.** Pending suggestions expire after 14 days. There is no badge, no unread count in the rail, and the Review line counts proposals from every plugin and appears only from three up.
7. **Every decision is stored.** Accept and dismiss are both recorded (success criterion 9 depends on it), and future prompts can use them as examples. A dismissed proposal is never offered again.
8. **No AI prose in the owner's notes.** There is no "continue writing", no summary appended, no AI comment thread. Machine-written text is a separate, marked note (§4.5), and changes to the owner's words are diffs to accept (`tidy`), never an in-place rewrite.

### 6.3 Suggestion lifecycle

```
saved ─▶ pending (plugin working, nothing shown)
            ├─▶ proposed (dashed pill on the suggestion line, or a card in Review)
            │      ├─▶ accepted   → the core action runs: label appended, title set, text appended,
            │      │                 diff applied as an ordinary edit, or Compare opened for a merge
            │      ├─▶ dismissed  → pill fades out (120 ms); never proposed again
            │      ├─▶ superseded → a text change whose note was edited first; the pill quietly goes
            │      └─▶ expired after 14 days → silently gone
            └─▶ failed validation twice → nothing shown, counted on Status
```

Editing a note while it has proposed suggestions keeps them, unless the owner typed one of the proposed labels by hand, in which case that pill disappears. A text change (`replace_body`) is superseded by any edit, and its plugin may offer again after the next quiet minute.

### 6.4 Settings → Plugins

Plugins are visible as plugins in exactly one place. Everywhere else the owner sees only what they do.

**The list** (R17 web, R20 Android). A header sentence ("Everything beyond notes, labels and search. Switch one on and it takes its place; switch it off and it leaves the screen, while its data and any notes it wrote stay."), the line "New plugins are added by whoever runs the server." (D27: no store, no install button), and one AI summary line naming the chat and embedding models and their host, linking to AI settings. A two-way filter, "All 25" / "On 18", and no search box. Groups in a fixed order (Capture, Organize, Recall, Develop, Agents, Data), each under an overline with a count ("Capture · 5 of 8 on"); rows keep catalog order so nothing jumps when toggled. Theme packs are not listed here; they are cards in Appearance.

A **row** has no icon and no colour: the name in `ui` 500, a platform tag ("Android") for device plugins, a "Default with AI" tag for the D31 set, one sentence in `ink-2`, one grey facts line, and the switch on the right, with "Settings" before it when the plugin is on and has a page. The **facts line** is computed by the host, never written by the plugin, and names each place text leaves the server, with a glyph per kind: the spark for AI ("Chat: page and note to qwen3.5 at ollama.theficos.lan"), a globe for fetching ("Fetches the linked page"), an envelope for mail and bots ("Talks to api.telegram.org"), an arrow out for destinations outside the network ("…at ollama.com, outside your network"), a lock for nothing ("Stays on your server", "Stays on the phone"), a clock for schedules ("Sundays 18:00").

States, drawn without colour:

| State | The switch place shows | The facts line adds |
|---|---|---|
| Available | Switch off | n/a |
| Enabled | "Settings" and the switch on | n/a |
| Requirement missing | "Needs a chat model" (or a vision model), linking to AI settings; a plugin that was on stays on underneath and resumes when the model appears | "Chat: no chat model is set up" |
| Pinned by the operator | A lock and "Set in the environment"; the state in words ("On") | The variable, on its page: `SPUNTO_PLUGINS` |
| Failed | The switch, still on | "Paused after 5 failed runs · Details" in `ink-2`, linking to Status |
| Removed (third-party code gone) | "Removed" | "214 records kept · Delete data…" |

**Turning one on: the permissions sheet** (R18 web dialog, R21 Android sheet). Switching a plugin on never starts it directly. The sheet is generated from the manifest and the plugin's AI route, always in four parts, each a heading for screen readers:

1. **Title and what it does.** "Turn on Link enrichment?" and the plugin's sentence.
2. **It will.** One line per permission in plain words, each with a qualifier in `ink-2`: "Fetch the page your note links to · Once, from your server, only for links you saved yourself. The site sees your server's address."
3. **AI and where note text goes.** One line per AI capability from a fixed sentence, *Capability: what goes to model at host, on your network / outside your network*: "Chat: page text and your note go to qwen3.5 at ollama.theficos.lan, on your network." A plugin routed differently from the rest says so and links to "Change route" (D16). "Outside your network" is said in words, never in red. A plugin with no AI and no network gets one line: "Stays on your server" (or "Stays on the phone").
4. **Where it shows up.** The slots in words: "Pills under the note, a Source section in About this note, and the reading time on the note."

Buttons: Cancel, and "Turn on" as the primary. The same sheet returns, headed "Weekly digest now also wants to…", when an update adds a permission. Switching off asks nothing: the plugin leaves the screen at once and one line offers "Undo" for a few seconds.

**A plugin's own page** (R19, R22) has the same shape for every plugin, so learning one teaches all of them: a header (name, switch, sentence, and a grey line: "Recall · built in · 1.0.0 · on since 2 September"; "Third party" instead of "built in" for the operator's own); **settings** generated from the plugin's schema (a switch for a boolean; a segmented control for up to three choices, else a select or menu; text, time, stepper, chips with "Add", label chips with autocomplete; "This browser only" or "This phone only" beside per-device fields); and host-drawn sections the plugin never writes: **AI** (the route, as a select over the owner's AI profiles, and the "where note text goes" line), **Run** (its commands as buttons, each with what to expect), **Activity** ("Last run Sunday 12 October, 18:00 · read 31 notes · wrote 1 note · no failures"), **Access** (the permission sentences, caps and MCP tools), and "Delete this plugin's data…", a `danger` link to a dialog with the count and what stays ("Its notes stay: they are notes."). Secrets are never inputs: a secret field is a read-only row, "Set in the environment", with the variable in mono (`SPUNTO_PLUGIN_TELEGRAM_BOT_TOKEN`), so a plugin page can never be where a token leaks. Offline on Android the page renders from the cached manifest, read-only, with one line: "Changes need a connection."

---

## 7. Label UX

- **Grammar on screen.** `#` followed by letters, digits, `_`, `-` and `/` for nesting. Headings (`# Title`, a hash followed by a space), URL fragments and code never render as labels. The renderer and the Android parser share one grammar and one test corpus (LAB-1).
- **Rendering.** Inline labels are the serif in the accent colour, with no pill or background, so a sentence like "need to check #homelab backups" still reads as a sentence. A last line made only of labels becomes the label line (§5.1).
- **No per-label colours or icons.** One accent keeps the page calm; the tree's structure and names do the work. (Blinko lets each tag have an icon; it adds a decision at every new label.)
- **The tree.** Counts include children. Parents are collapsible; collapsed state is remembered per device. Sorted by name, with an option to sort by count. Labels used once in the last year are not hidden: the owner decides what to remove.
- **Autocomplete** (L1) prefers labels already in use, so the vocabulary stays small. New labels are allowed and marked "New label" so a typo is noticed.
- **New-label proposals from AI** carry a small `new` mark in `ink-3` and are never pre-selected in review, because every new label grows the vocabulary.
- **Rename, merge, remove** go through one dialog each, always with an impact line; only rename offers Undo, because merge and remove have no exact inverse (L4). Merges and cluster labels proposed by `label-gardener` wait in Review and open the same dialogs. Remove deletes the tokens and keeps the rest of the text: "Removes #reading from 19 notes. The notes stay."
- **Labels in the composer of a label view** are pre-filled at the end of the note, so capture inside `#project/spunto` lands in that label without a step.

---

## 8. Empty states and copy

### 8.1 Voice

Short, plain, warm. Sentence case. No exclamation marks, no "Oops", no "Uh-oh", no emoji. Say what happened and what to do, in that order. Italian later (WEB-10) should translate meaning, not words.

### 8.2 Empty states

Empty states teach the next useful move, in the serif display style for the headline and the sans for the instructions.

| Where | Headline | Body |
|---|---|---|
| First run, web | "A quiet place for loose ideas." | "Write anything above and press ⌘↵. Add #labels anywhere, or let them be suggested later. Connect Claude Code or Codex in Settings → Agents." ("or let them be suggested later" only while `label-suggestions` is on) |
| First run, Android | "A quiet place for loose ideas." | "Share anything to spunto from any app, or select text and choose Save to spunto. Tap the mic to speak a note, even offline. Long-press the icon for New note or Dictate a note, or add the New note tile to Quick Settings." (The mic and tile sentences only while `dictation` and `quick-capture` are on.) |
| Label with no notes | "Nothing under #reading yet." | "Notes you write here get #reading automatically." |
| Search, no results | "No notes match ‘perche’." | "Try fewer words. Search ignores accents, so perche finds perché." (plus the Ask row when `ask` is on) |
| Archive | "Nothing archived." | "Archive keeps a note out of the timeline without deleting it." |
| Trash | "Trash is empty." | "Deleted notes stay here for 30 days." |
| Unlabeled (`label-suggestions`) | "Every note has a label." | none |
| Review | "All caught up." | none |
| From agents (`agent-inbox`) | "No notes from agents yet." | "Connect Claude Code or Codex in Settings → Agents." |
| Note panel section | (section absent) | n/a |
| Ask, nothing relevant | "Nothing in your notes covers this." | "Search instead" |
| AI off (Settings → AI) | "AI is off." | "Everything else works. To turn it on, set the `SPUNTO_AI_*` variables and restart the server." |

### 8.3 Common messages

| Situation | Copy |
|---|---|
| Web, server unreachable | "Can't reach the server. What you type is kept on this device." |
| Android, sync failing | "3 notes waiting to sync · Server unreachable" |
| Archive done | "Archived · Undo" |
| Trash done | "Moved to trash · Undo" |
| Rename done | "Renamed in 18 notes · Undo" |
| Token created | "Copy this token now. You won't see it again." |
| Conflict | "Edited on two devices. Both versions are kept · Compare" |
| Wrong password | "That password didn't work. Try again in 4 seconds." (back-off shown honestly) |
| Dictation, microphone refused | "Dictation needs the microphone. Audio stays on this phone. · Allow" |
| Dictation, microphone off for good | "Microphone access is off for spunto. · Open settings" |
| Dictation, language not on the phone | "Italian needs a one-time download to work offline · Download" |
| Dictation, nothing heard | "Didn't catch anything. Tap the mic to try again." |
| Dictation, microphone busy | "The microphone is in use by another app." |
| Dictation, silence | "Stopped after a pause" (action bar, 2.5 s) |
| Dictation, online-only phone with no network (D23) | "This phone can't dictate Italian offline. Type, or download Italian in Settings → Plugins → Dictation." |
| Plugin waiting for a model | "Needs a chat model" (or "Needs a vision model") |
| Plugin pinned by the operator | "Set in the environment" |
| Plugin with no AI and no network | "Stays on your server" (or "Stays on the phone") |
| Plugin paused | "Paused after 5 failed runs · Details" |
| Plugin switched off | "Weekly digest is off · Undo" |
| Plugin page offline (Android) | "Changes need a connection." |
| Label merge offline (Review) | "Needs a connection" |
| Ask, model unreachable | "Couldn't reach the model. Search results are below." |
| Command, model unreachable | "Couldn't reach the model. Nothing was written." |
| Machine-written note, closing line | "Written by Weekly digest with qwen3.5. Plugins leave it out until you edit it." |
| First adoption of a machine-written note (once per account) | "This note is yours now: plugins can read it and it can resurface." |
| Widget with `quick-capture` off | "Quick capture is off. Turn it on in Settings → Plugins." |
| Plain HTTP (D33), web band and Android line | "Not encrypted · This server uses plain HTTP. Fine on your own network; set up HTTPS before using it elsewhere." |
| Wrong address (D33) | "This server lives at https://spunto.theficos.dedyn.io. Open it there to sign in." |

---

## 9. Motion

### 9.1 Principles

- **Explain, don't decorate.** Animate where something went: a saved note into the timeline, a label into the text, a sheet from the edge it belongs to.
- **Short and interruptible.** 120 to 240 ms on web, ease-out for entrances, faster ease-in for exits. On Android, springs from Material 3's standard motion scheme, which can be interrupted mid-flight.
- **Keyboard actions don't move.** Anything triggered by a key (save with `⌘↵`, opening the palette with `⌘K`, `j`/`k`, archive with `y`) resolves instantly, as WEB-2 requires. The only feedback allowed is a colour change that never delays input, such as the 600 ms highlight fade on a just-saved entry.
- **Zero frames when idle.** No looping animation anywhere, ever: no breathing dots, no shimmer skeletons, no animated gradients. The single exception is the three-dot waiting pulse of a command result ("Reading 8 notes…"), which exists only while a request is open. (The dictation halo is not a loop: it follows the voice level and stops with the session.) Blinko's idle-CPU bug came from a hidden looping SVG; a CI check fails the build on `animation-iteration-count: infinite` outside that one component.
- **Respect reduced motion.** With `prefers-reduced-motion` or Android's "Remove animations", movement becomes a 100 ms fade or nothing.

### 9.2 Catalogue

| Moment | Web (pointer) | Web (keyboard) | Android |
|---|---|---|---|
| Save from composer | New entry fades in and slides 8 px down, entries below shift with it (FLIP), 180 ms ease-out | Instant; 600 ms highlight fade | Spatial spring; entry emerges from the composer's position |
| Accept suggestion | Pill turns solid (border dash to solid, 120 ms), then moves into the label line, 180 ms | Same, no movement: instant swap | Shared-element spring from pill to label line |
| Dismiss suggestion | Fade and scale to 96 %, 120 ms ease-in | Instant | Fade, fast effects spring |
| Suggestions arrive | Fade in 240 ms; height grows 180 ms only if the composer is not focused | Same | Fade; height spring |
| "+N" unfolds, a panel section opens | Height expands 180 ms (`dur-2`) ease-out | Instant | Height spring |
| Open palette | Fade + scale 98→100 %, 120 ms | Instant | n/a |
| Open sheet | n/a | n/a | M3 bottom sheet spring with scrim fade |
| Open note | Cross-fade 150 ms | Instant | Container transform from the entry (shared element) |
| Toast | Slide up 8 px + fade 180 ms; leave 120 ms | Same | M3 snackbar |
| Pin | Entry moves to the pinned group, 240 ms | Instant | Spring |
| Theme or mode switch | No transition (a cross-fade of the whole page costs more than it gives) | Same | System default |
| Dictation | n/a | n/a | Keyboard out and panel in on the IME's own timing; the halo follows the voice level only while listening (at most 30 updates a second, from the recogniser's level callback) and stops with the session; words change from `ink-3` to `ink` with no movement. With "Remove animations", a static halo and the "Listening" line |

### 9.3 Waiting

- Saves never wait: they are local (Android) or optimistic with a draft backup (web).
- Searches show keyword results immediately and append meaning-based results when they arrive, without reshuffling what the owner is looking at: meaning-only hits are inserted below the keyword hits that are already visible.
- Long jobs (import, re-index) show a static count that updates on refresh, never a spinner.

---

## 10. Accessibility

- **Contrast.** Every text token pair meets WCAG 2.2 AA (4.5:1) in every theme, in light and dark; the computed ratios are in §11.3, and a theme pack that misses one is refused at load. The smallest text (`meta`, 12.5 px) still meets 4.5:1 because `ink-3` was tuned for it. Field borders use the `field` token at ≥ 3:1. The hairline `rule` tokens are decorative only and never the sole boundary of a control.
- **Never colour alone.** Labels are accent-coloured *and* start with `#`. Suggestions are dashed *and* start with `+` or the wand *and* are announced with their plugin ("Suggested label, from Label suggestions"; "Suggested title, from Link enrichment"). The sync ring has a text equivalent. The conflict dot comes with the word "Conflicted".
- **Focus.** A 2 px accent ring with 2 px offset on `:focus-visible`, never removed. The timeline is a `role="feed"` of `article` elements, so screen-reader users can move entry by entry; `j`/`k` move the same focus.
- **Keyboard.** Every web action is reachable by keyboard (§3.5), and every keyboard shortcut has a visible control.
- **Screen readers.** Each entry reads as "Note, 14:32, Widget idea: show one old note… Labels: project spunto, idea. 2 suggested labels." Suggestions arriving are announced politely once ("2 label suggestions for your last note"). On Android, every entry exposes custom accessibility actions (Pin, Archive, Accept suggestions, Dismiss suggestions) so swipes and long-presses are never the only route. A machine-written note announces its writer ("Note written by Weekly digest, Sunday 18:00…"). Plugin switches are labelled with name and state ("Weekly digest, off"), and the permissions sheet's four parts are headings.
- **Dictation.** The mic is labelled "Dictate", and "Stop dictation" while listening. With TalkBack on, the app announces "Listening in Italian" before it opens the microphone, so the announcement is not transcribed; it never reads partial words aloud, and at the end announces "Stopped. 24 words added." Every dictation state has a line of text; the halo is never the only signal.
- **Targets.** 48 dp minimum on Android; 44 px on compact web; 32 px minimum with 8 px spacing on pointer layouts (WCAG 2.2 target size is 24 px).
- **Text scaling.** Android uses `sp` throughout and is tested at 200 % font scale: entries reflow, the action bar's tools scroll horizontally, nothing clips. Web uses `rem`; the text-size setting and browser zoom to 200 % reflow to the compact layout without horizontal scrolling.
- **Reading.** Line length 60 to 72 characters at default sizes; line height 1.55 to 1.6 for the body; paragraph spacing of 0.6 em. Italian and English both render with correct diacritics in all three families.
- **Motion.** §9.1. No parallax, no auto-scrolling.
- **Language.** The interface sets `lang="en"`. Note bodies are not tagged by language; a per-note language hint can come later if screen-reader users need it.

---

## 11. Design tokens

Tokens are named by role, not by value, so a theme change touches values only. Web gets them as CSS custom properties; Android as a Compose `ColorScheme` plus a small `SpuntoTokens` object for the extras M3 has no slot for (highlight, warn, field). The mockups are built only from them: `mockups.html` in Carta, `redesign.html` in all three themes.

Tokens come in two sets. **Themeable:** the colour roles of §11.3 (`paper`, `raised`, `sunken`, `ink` to `ink-3`, `rule`, `rule-strong`, `field`, the `accent` family, `highlight`, `danger`, `warn`), plus `scrim` and the hover and press state layers, each in a light and a dark set. **Fixed:** typefaces, the type scale, spacing, radii, elevation (shadows derive from `ink`), motion, icons, and the dashed proposal style. A theme is a pack of colour values and nothing else, so plugin output cannot look right in one theme and wrong in another, and faces keep their meaning: the serif marks the owner's words and the sans the machine's, which a theme setting notes in a sans would erase (U2). The reading face stays the owner's per-device setting.

### 11.1 Typefaces and licences

| Role | Family | Why | Licence | Shipping |
|---|---|---|---|---|
| Reading serif (default note face) | **Literata** (TypeTogether, commissioned for Google Play Books) | Designed for long reading on screens; large x-height; a real optical-size axis (7 to 72) so one family covers 12 px meta to 34 px display; full italics; complete Latin Extended for Italian | SIL Open Font License 1.1 | Web: self-hosted variable WOFF2 (roman + italic), subset to Latin, Latin-1 and Latin Extended-A. Android: bundled in `res/font` |
| UI sans, and the sans reading option | **Inter** (Rasmus Andersson) | Built for UI at small sizes; tabular figures (`tnum`) for times and counts; optical-size axis; calm next to Literata | SIL OFL 1.1 | Same as above |
| Mono | **JetBrains Mono** | Code blocks, tokens and the agent setup snippets; clear `0O` and `1lI` | SIL OFL 1.1 | Web: subset WOFF2, loaded only on pages with code. Android: bundled, regular weight only |
| Icons | **Material Symbols Rounded**, weight 300, grade 0, no fill (fill for active states) | Native on Android; light weight matches the thin type; one set on both platforms | Apache License 2.0 | Web: the ~30 glyphs used, inlined as SVG (about 10 KB). Android: vector drawables |

The web app never loads fonts from Google's CDN at runtime: no calls home (§6.4 of requirements), and LAN-only installs must look the same. Budget: all font files together ≤ 320 KB WOFF2, cached immutably; the body face is preloaded so the composer never reflows after first paint. On Android the bundled fonts add about 1.2 MB, inside the 15 MB APK budget. Variable fonts work on Android from API 26 through `FontVariation.Settings`, which matches the minimum SDK.

### 11.2 Type scale

At most two weights per family in normal use: Literata 400 and 600, Inter 400 and 500.

| Token | Use | Family | Web size / line height | Android sp / line height | Weight | Notes |
|---|---|---|---|---|---|---|
| `display` | Empty-state headlines, sign-in wordmark | Literata, opsz 48 | 34 / 40 px | 30 / 36 | 400 | Tracking −0.01 em |
| `title` | Label view title, settings page title, note `# heading` in note view | Literata, opsz 30 | 24 / 31 px | 22 / 28 | 600 | |
| `heading` | Entry titles from `# Heading`, `##` in notes | Literata | 19 / 27 px | 18 / 25 | 600 | |
| `body` | Note text | Literata, opsz auto | 17 / 27 px (1.6) | 16 / 25 (1.55) | 400 | Paragraph gap 0.6 em; measure ≈ 70 ch |
| `body-sm` | Related notes, search snippets, share preview, Ask question | Literata | 15 / 23 px | 15 / 22 | 400 | |
| `ui-lg` | Palette input, command results, machine-written note bodies (web) | Inter | 16 / 25 px | 16 / 24 | 400 | Machine words use this, principle 3 |
| `ui` | Buttons, menus, rail items, settings rows | Inter | 14 / 20 px | 14 / 20 | 400, 500 for buttons and active items | |
| `meta` | Times, counts, chips, suggestions | Inter, `tnum` | 12.5 / 16 px | 12 / 16 | 400, 500 for chips | |
| `overline` | Day headers, section labels | Inter, uppercase | 11.5 / 16 px | 11 / 16 | 500 | Tracking +0.08 em |
| `mono` | Code, tokens, snippets | JetBrains Mono | 13.5 / 21 px | 13 / 20 | 400 | Inline code at 0.88 em on `sunken` |

The **sans reading option** swaps `body`, `body-sm` and `heading` to Inter at 16.5 / 27 px (Inter runs larger than Literata at the same size). The **text-size setting** on web scales all tokens by 16/17, 1 or 19/17.

### 11.3 Colour

Three built-in themes, each a light and a dark set with one accent (U1): **Carta** ("paper"), the warm paper and terracotta, and the default; **Ardesia** ("slate"), cool grey-blue paper, blue-black ink and an ink-blue accent, crisp on bright screens and for long sessions at a desk; **Salvia** ("sage"), pale sage paper, green-black ink and a forest-green accent, the softest of the three. All values are derived in OKLCH (a colour space where equal steps look equal, the approach Linear uses), which keeps the three accents at the same perceived weight. Contrast ratios are computed against the token in brackets; every text pair clears 4.5:1 and `field` 3:1, in every theme and mode.

The themes replace the earlier five-accent picker: Cotto became Carta's accent unchanged; Inchiostro was retuned as Ardesia's for cool paper; Salvia became the Salvia theme with a deeper green; Prugna and Ocra retired, and either can return as a pack.

#### Carta (default)

**Light**

| Token | Hex | Role | Contrast |
|---|---|---|---|
| `paper` | `#F8F5F0` | Page background | n/a |
| `raised` | `#FEFDFA` | Composer, sheets, palette, popovers | n/a |
| `sunken` | `#F0EDE6` | Rail, quoted blocks, code, daily slip, offline band | n/a |
| `ink` | `#221D18` | Note text, titles | 15.4 [paper] · 14.3 [sunken] |
| `ink-2` | `#5B544C` | Secondary UI text | 6.9 [paper] · 6.4 [sunken] |
| `ink-3` | `#6E6860` | Meta, placeholders, counts | 5.1 [paper] · 4.7 [sunken] · 4.7 [accent-soft] |
| `rule` | `#E3DFD8` | Hairlines between entries | decorative |
| `rule-strong` | `#CCC7BE` | Link underlines, dividers in menus | decorative |
| `field` | `#8A857E` | Form field borders | 3.4 [paper] · 3.6 [raised] |
| `accent` (Cotto) | `#9F4C2A` | Labels, primary button, focus ring, checked boxes | 5.5 [paper] · 5.1 [sunken] · 5.0 [accent-soft] |
| `accent-strong` | `#883A1D` | Pressed primary, accent text on `accent-soft` when small | 7.2 [paper] |
| `accent-soft` | `#FAE9DE` | Selected row in tree, active filter pill | n/a |
| `on-accent` | `#FEF9F4` | Text on accent fills | 5.7 [accent] |
| `highlight` | `#F5E4B0` | Search match background | `ink` on it 13.2 |
| `danger` | `#AF2934` | Destructive confirm button, errors | 6.1 [paper] |
| `warn` | `#CD9130` | Conflict dot (always with text) | non-text |
| `scrim` | `#221D18` at 32 % | Behind sheets and the palette | n/a |
| `state-hover` / `state-press` | `#221D18` at 5 % / 9 % | Overlay for hovered and pressed rows on any surface (M3 state layers) | n/a |

**Dark**

| Token | Hex | Contrast |
|---|---|---|
| `paper` | `#171412` | n/a |
| `raised` | `#211D1A` | n/a |
| `sunken` | `#100E0C` | n/a |
| `ink` | `#EAE5DD` | 14.6 [paper] · 13.3 [raised] |
| `ink-2` | `#BDB6AE` | 9.1 [paper] · 8.3 [raised] |
| `ink-3` | `#9D978F` | 6.3 [paper] · 5.8 [raised] · 4.8 [accent-soft] |
| `rule` | `#2D2925` | decorative |
| `rule-strong` | `#413C37` | decorative |
| `field` | `#736E67` | 3.6 [paper] · 3.3 [raised] |
| `accent` | `#E7986F` | 8.0 [paper] · 7.3 [raised] · 6.0 [accent-soft] |
| `accent-strong` | `#F6B38B` | 10.3 [paper] |
| `accent-soft` | `#3F271D` | n/a |
| `on-accent` | `#24140C` | 7.7 [accent] |
| `highlight` | `#5F4A1A` | `ink` on it 6.8 |
| `danger` | `#ED7473` | 6.4 [paper] |
| `warn` | `#E1AD57` | non-text |
| `scrim` | `#000000` at 50 % | n/a |
| `state-hover` / `state-press` | `#EAE5DD` at 6 % / 10 % | n/a |

#### Ardesia and Salvia

The roles are Carta's. Contrast is against **p** `paper`, **r** `raised`, **s** `sunken`, **soft** `accent-soft` and **acc** `accent`; `scrim` and the state layers are derived the same way in every theme (`ink` at 32 % in light, black at 50 % in dark; hover and press at 5 % and 9 % of `ink` in light, 6 % and 10 % in dark).

| Token | Ardesia light | Contrast | Ardesia dark | Contrast | Salvia light | Contrast | Salvia dark | Contrast |
|---|---|---|---|---|---|---|---|---|
| `paper` | `#F4F6F8` | | `#13161A` | | `#F3F5F0` | | `#121614` | |
| `raised` | `#FCFDFE` | | `#1C2026` | | `#FBFCF9` | | `#1B201D` | |
| `sunken` | `#EBEEF2` | | `#0D0F12` | | `#E9EDE5` | | `#0C0F0D` | |
| `ink` | `#161B22` | 16.0 p · 14.9 s | `#E4E8EE` | 14.8 p · 13.3 r | `#1A1F1B` | 15.2 p · 14.1 s | `#E2E8E2` | 14.7 p · 13.3 r |
| `ink-2` | `#4A5361` | 7.2 p · 6.7 s | `#B3BAC5` | 9.3 p · 8.4 r | `#4D564F` | 6.9 p · 6.4 s | `#B2BCB4` | 9.3 p · 8.5 r |
| `ink-3` | `#5E6775` | 5.3 p · 4.9 s · 4.8 soft | `#929AA6` | 6.4 p · 5.8 r · 5.0 soft | `#616A63` | 5.1 p · 4.7 s · 4.6 soft | `#919C94` | 6.4 p · 5.8 r · 4.8 soft |
| `rule` | `#DEE2E8` | decorative | `#272C33` | decorative | `#DCE1D8` | decorative | `#262C28` | decorative |
| `rule-strong` | `#C5CBD4` | decorative | `#3A4049` | decorative | `#C3CABF` | decorative | `#39413B` | decorative |
| `field` | `#7D8592` | 3.4 p · 3.7 r | `#6C7480` | 3.8 p · 3.5 r | `#7E877F` | 3.4 p · 3.6 r | `#6B756D` | 3.8 p · 3.5 r |
| `accent` | `#2F5E9E` | 6.0 p · 5.6 s · 5.4 soft | `#8DB2EE` | 8.4 p · 7.6 r · 6.6 soft | `#2D6A4F` | 5.8 p · 5.4 s · 5.3 soft | `#7FC4A0` | 9.0 p · 8.1 r · 6.6 soft |
| `accent-strong` | `#234B82` | 8.1 p · 7.3 soft | `#B0CBF5` | 11.0 p · 8.6 soft | `#22553F` | 7.8 p · 7.1 soft | `#A6DABD` | 11.6 p · 8.6 soft |
| `accent-soft` | `#E3EBF7` | | `#1E2B40` | | `#DDEEE3` | | `#1C3327` | |
| `on-accent` | `#F7FAFE` | 6.2 acc | `#0F1A2B` | 8.1 acc | `#F6FBF7` | 6.1 acc | `#0D1F15` | 8.4 acc |
| `highlight` | `#F2E5A8` | `ink` on it 13.6 | `#57481A` | `ink` on it 7.3 | `#EFE3A8` | `ink` on it 12.9 | `#4F4A1C` | `ink` on it 7.2 |
| `danger` | `#B4232F` | 6.0 p · 6.4 r | `#F07A7A` | 6.7 p · 6.1 r | `#B0282F` | 6.0 p · 6.4 r | `#EE7C78` | 6.8 p · 6.1 r |
| `warn` | `#C98A1E` | non-text | `#E0AE55` | non-text | `#C08A2A` | non-text | `#DDB05A` | non-text |

`redesign.html` shows each theme as a specimen in light and dark, with the full table computed live from the values its frames use; 05 §8.2 holds every pair.

**Pure black (Android option, dark only, any theme):** `paper` and `sunken` become `#000000`, and `raised` a near-black tinted toward the theme: `#110F0D` (Carta, whose `rule` also becomes `#2B2825`), `#0F1114` (Ardesia), `#0E110F` (Salvia); every other dark token stays. On black, `ink` reaches 16.8, 17.1 and 16.9, `ink-3` 7.3, 7.4 and 7.4, `accent` 9.1, 9.7 and 10.3; on the tinted `raised`, `ink-3` stays at 6.6 or above.

Why Carta by default: warm paper plus terracotta reads as a craft object rather than an app, and it fits the family's Italian names. The cost is that its accent, Cotto, sits near red, so `danger` is kept deeper and more crimson (hue 22 against 42), and destructive actions never rely on colour: they are always labelled ("Delete 4 notes forever") and confirmed. Owners who find that too close pick Ardesia.

**Wallpaper colours (Android 12+, opt-in).** When on, M3's wallpaper-derived scheme replaces the theme's paper, surfaces and accent; the theme's `ink` and `rule` steps are kept because M3's neutrals are cooler than ours. Default is off: the paper is part of the identity. Pure black and wallpaper colours both apply on top of any theme.

**Which theme applies.** The theme is an instance setting that syncs to every device; each device may override it, and the mode (System, Light, Dark) is always per device. The picker is in Settings → Appearance (W8, A9). Theme packs the operator adds in `/data/themes` carry only these colour roles, in both modes; the host checks the contrast pairs above at load and refuses a pack that misses one, naming the pair (04 §5).

**Mapping to Material 3 roles (Android):**

| M3 role | Token |
|---|---|
| `background`, `surface` | `paper` |
| `surfaceContainerLowest` | `raised` (composer) |
| `surfaceContainerLow` | `raised` (sheets) |
| `surfaceContainerHigh` | `sunken` |
| `onSurface` | `ink` |
| `onSurfaceVariant` | `ink-2` |
| `outline` | `field` |
| `outlineVariant` | `rule` |
| `primary` / `onPrimary` | `accent` / `on-accent` |
| `primaryContainer` / `onPrimaryContainer` | `accent-soft` / `accent-strong` |
| `error` | `danger` |
| `scrim` | `scrim` |

### 11.4 Spacing

A 4 px base, named by value so nobody has to remember what `space-7` means. The same numbers are dp on Android.

| Token | Value | Typical use |
|---|---|---|
| `s-2` | 2 | Hairline offsets, focus ring offset |
| `s-4` | 4 | Icon to text in chips |
| `s-8` | 8 | Gap between pills, label line gaps |
| `s-12` | 12 | Composer inner padding (vertical), menu item padding |
| `s-16` | 16 | Composer inner padding (horizontal), Android screen margin in sheets |
| `s-20` | 20 | Between entries; Android screen margin |
| `s-24` | 24 | Between sections inside a screen |
| `s-32` | 32 | Above day headers |
| `s-48` | 48 | Rail to column gap, top of column |
| `s-64` | 64 | Web time gutter width |
| `s-96` | 96 | Empty-state top margin |

Layout constants: rail 248 px; measure 36 rem (≈ 576 px of text, 66 to 72 characters at 17 px Literata); note panel 240 px; palette 640 px (720 px for a command result); Android top bar 64 dp, action bar 48 dp, FAB 56 dp.

### 11.5 Radii

| Token | Value | Use |
|---|---|---|
| `r-4` | 4 | Checkboxes, inline code, highlight marks |
| `r-8` | 8 | Menu items, small buttons, tree row highlight |
| `r-12` | 12 | Composer (Android), inputs, snippets |
| `r-14` | 14 | Composer (web), daily slip, popovers |
| `r-20` | 20 | Palette, dialogs, widget inner surface |
| `r-28` | 28 | Android bottom sheet top corners (M3 default), widget |
| `r-full` | 999 | Buttons, pills (suggestions, chips), FAB uses M3's 16 |

### 11.6 Elevation

Elevation is mostly done with surface colour and hairlines; shadows are reserved for things that float above the page.

| Token | Light | Dark | Use |
|---|---|---|---|
| `e-0` | none | none | Entries, rail |
| `e-1` | `0 0 0 1px rule` | `0 0 0 1px rule` | Composer, daily slip, cards in settings |
| `e-2` | `0 1px 2px rgb(34 29 24 / .06), 0 8px 24px rgb(34 29 24 / .08)` | `0 1px 2px rgb(0 0 0 / .4), 0 8px 24px rgb(0 0 0 / .35)`, plus `inset 0 1px 0 rgb(255 255 255 / .04)` | Menus, autocomplete, toasts, FAB |
| `e-3` | `0 2px 6px rgb(34 29 24 / .08), 0 24px 64px rgb(34 29 24 / .18)` | `0 2px 6px rgb(0 0 0 / .5), 0 24px 64px rgb(0 0 0 / .55)`, plus the inset highlight | Palette, dialogs, sheets |

In dark mode, raised things are also lighter (`raised` over `paper`), because shadows are hard to see on dark backgrounds. On Android, M3 tonal elevation is turned off (`tonalElevation = 0.dp`) so surfaces keep our warm tones instead of picking up an accent tint.

### 11.7 Motion tokens

| Token | Value | Use |
|---|---|---|
| `dur-0` | 0 ms | Anything keyboard-driven |
| `dur-1` | 120 ms | Hover, press, exits, dismiss |
| `dur-2` | 180 ms | Entry insert, suggestion accept, toasts, menus |
| `dur-3` | 240 ms | Pin move, suggestions fading in |
| `dur-flash` | 600 ms | New-entry highlight fade (colour only) |
| `ease-out` | `cubic-bezier(.2, .8, .2, 1)` | Entrances |
| `ease-in` | `cubic-bezier(.4, 0, 1, 1)` | Exits |
| `ease-in-out` | `cubic-bezier(.4, 0, .2, 1)` | Moves within the page |
| Android | `MotionScheme.standard()` (Material 3 Expressive), spatial and effects springs from `MaterialTheme.motionScheme` | Everything; the "expressive" scheme's bouncier springs are not used, because calm |

### 11.8 Iconography and the mark

- Material Symbols Rounded at weight 300, 20 px on web and 24 dp on Android, drawn in `ink-2`; active states use the filled variant in `ink`. Glyphs in use: search, edit (pencil), tag (`#`), check box, image, mic, stop, keyboard, push pin, archive, delete, more horizontal and vertical, close, arrow back, chevron, settings, sync problem, lock, terminal (agent chip), open in new (source chip), check, undo, spark ("a model reads or writes here", §6.2), wand (proposals that change text), info (About this note), drag handle (Arrange), and for the facts lines in Settings → Plugins a globe, an envelope, an arrow out and a clock. Plugins never bring glyphs of their own; the host picks them.
- **The mark.** "spunto" contains *punto*, the point. The wordmark is "spunto" in Literata 600 with a period in the accent colour. The app icon is the same point alone: a terracotta dot slightly right of centre on warm paper, like the first mark on a blank page. The adaptive icon's monochrome layer is the dot only, so themed icons on Android 13+ work.

### 11.9 Token source of truth

One `tokens.json` in the repository holds the fixed tokens (type, space, radius, elevation, motion) once and one colour set per theme, light and dark. At build time it generates `tokens.css` for the web (each built-in theme under `:root[data-theme=…][data-mode=…]`) and `Tokens.kt` for Android, emitting all three themes. Nobody edits generated files. A third-party pack's `theme.toml` never passes through the generator: the server validates it at load and serves it as a stylesheet to the web and as values in the cached manifest to Android (04 §5). The mockups are the visual reference until the generator exists.

---

## 12. What the mockups show

Two self-contained files (inline CSS, a few lines of script, Google Fonts as their only external resource; the product itself self-hosts its fonts), each with a switch for light, dark or both side by side. [`design/mockups.html`](design/mockups.html) shows the **core screens**, drawn in Carta, with the plugins this instance has on. [`design/redesign.html`](design/redesign.html) shows **plugins and themes**: the slots, the plugin manager, the AI flows and the three themes, with a theme switch of its own.

**`mockups.html`, core screens**

| Frame | Shows | Requirement IDs |
|---|---|---|
| A1 Capture | Composer focused on launch, keyboard up, label autocomplete in the action bar | CAP-1, CAP-3, LAB-2 |
| A2 Timeline | Day headers, dashed suggestions, agent chip, unsynced ring, checklist, FAB, Review line | AI-3, MCP-7, AND-2, CAP-8 |
| A3 Share sheet | One-tap save over the source app, optional comment and recent labels | AND-3, CAP-6 |
| A4 Note | Edit-in-place reading view, suggestion line, the "About this note" colophon (the sheet is R6) | AI-6, AND-7 |
| A5 Labels sheet | Core views, a Views group with "Show more views", nested tree with counts, active label | LAB-3, LAB-5, MCP-10 |
| A6 Search | Hybrid results with "≈ similar meaning", Ask row | SRC-1, SRC-2, AI-8 |
| A7 Review, one by one | One note at a time, existing labels pre-selected, new ones not; the grouped screen it opens from is R11 | AI-3, D6 |
| A8 Home screen | Widget with the daily slip and its mic, app icon, launcher shortcuts "New note" and "Dictate a note" | AND-5, AND-9, SRC-5 |
| A9 Dictation | Listening panel in place of the keyboard, live words (in-flight ones in `ink-3`), IT/EN switch, "on this phone" in airplane mode | AND-15, AND-5, CAP-4 |
| W1 Home | Rail with a Views group, composer, Review line, daily slip, pinned group, timeline with hanging times, suggestions | WEB-1, WEB-3, SRC-5 |
| W2 Note | Reading view with the note panel in the margin, Related open (the full panel is R7) | AI-6 |
| W3 Palette | Hybrid search, labels and actions in one box | SRC-1, SRC-2, WEB-2 |
| W4 Ask | A command result: answer in the sans with citations to serif notes, "Save as note" (R12 is the reference) | AI-8 |
| W5 Label view | Label title, rename dialog with impact line | LAB-4 |
| W6 Settings · Agents | Claude Code and Codex snippets, tokens; the settings list with Plugins | WEB-5, MCP-2, MCP-3 |
| W7 First run | Empty-state teaching capture, labels and agents | WEB-1 |

A7, W2 and W4 are superseded as references by R11, R7 and R12; they stay because they show the same screens in the core's frame.

**`redesign.html`, plugins and themes**

| Frame | Shows | Sections here |
|---|---|---|
| R1 · Core only, Android | Timeline with nothing switched on | Principle 9 |
| R2 · Default set, Android | The same screen with the D31 set: Review line, pills | §6.1 |
| R3 · Web, core only | Rail without Views, "Synced", keyword palette | §3.3 |
| R4 · Web, default set | Review line, pills with hover ×, "AI ready" | §6.1 |
| R5 · Note, Android | Suggestion line with "+2", meta token, colophon | §5.1, A5 |
| R6 · About this note | The panel as a sheet: Source, Related, Backlinks, Arrange | A5 |
| R7 · Note, web | The margin as the note panel | §3.3, W3 |
| R8 · ⌘K on an open note | "This note" verbs with plugin names | W4 |
| R9 · Note actions, Android | Plugin verbs between hairlines | A5 |
| R10 · Home with eighteen plugins | Review line, daily slip, saved answer, digest, three views | §6.1, S5 |
| R11 · Review, Android | One grouped queue: notes, labels, duplicates | L3, A8 |
| R12 · Ask your notes, web | Command result in the palette, ⌘S to save | §4.4, W5 |
| R13 · Weekly digest, web | A machine-written note with sidenote citations | §4.5 |
| R14 · Ask your notes, Android | The result as a sheet over search | §4.4, A7 |
| R15 · Tidy a dictated note | Wand pill and the diff sheet | §6.2 |
| R16 · Develop this idea | The follow-up note, linked to the original | §4.5 |
| R17 · Plugins, web | The grouped list, facts lines, states | §6.4 |
| R18 · Turning on a plugin, web | The four-part dialog, routed outside the network | §6.4 |
| R19 · A plugin's own page | Generated settings and host sections (Weekly digest) | §6.4 |
| R20 · Plugins, Android | The same list, thumb-sized, device plugins | §6.4, A9 |
| R21 · Turning on, Android | The four-part sheet for Link enrichment | §6.4 |
| R22 · A generated page with a secret | "Set in the environment" for Telegram's token | §6.4 |
| R23 · Appearance, web | Theme cards, device override, mode, face, size | W8 |
| R24 · Appearance, Android | Compact cards, pure black, wallpaper colours | A9, §11.3 |

The page also shows each theme as a specimen in light and dark, and a token table whose contrast column is computed in the browser from the same values the frames use.

---

## 13. Decisions and choices, and how to reverse them

### 13.1 Decided by the owner

Settled on 2026-10-13. Each was the recommended option; the full reasoning is in 02 §17, 04 §8 and 05 §10.

| Decision | Chosen | Why |
|---|---|---|
| D2 Android client | Native Kotlin and Compose | Capture on the phone is the heart of the product, and only native gives the share target, selection action, widget and tile |
| D4 Where labels live | In the note text | One mental model and portable exports; renames are rare |
| D6 AI and labels | Suggest; opt-in auto-accept for labels already in use | The dashed-to-solid moment stays the owner's consent, and new labels always need a human |
| D16 Where note text goes | Embeddings local; chat and vision default to Ollama cloud; per-plugin routing for performance | Every sheet and facts line names the host, so "outside your network" is always visible |
| D22 Dictation engine | Android's on-device recognizer; an M0 spike confirms the Italian and English packs on the owner's Android 16 phone | Offline, no audio kept, no bundled model |
| D29 Machine-written notes | Marked, and kept out of AI inputs and resurfacing until the owner edits them | The pile stays the owner's own thinking (§4.5) |
| D31 What is on by default | Core, `dictation`, `quick-capture`; with AI also `label-suggestions`, `semantic-search`, `related`, `ask` | An AI install looks like the first plan; nothing appears the owner did not have |
| D33 Plain HTTP on a LAN | Allowed at first run, with a persistent "Not encrypted" band and a clear wrong-address page | The first note works anywhere; nobody mistakes it for a finished setup |
| U1 Accent picker | Replaced by three themes with one accent each | One choice instead of fifteen combinations to tune and test |
| U2 Faces in themes | Not themeable | The serif and sans say whose words they are |
| U3 Views from default-on plugins | Hidden until pinned | The first run stays as calm as the core alone |
| U4 Adopting a machine-written note | A one-time line, once per account | Explains the switch to serif without nagging |
| U5 Ask answers | Kept only when saved as a note | No second, invisible archive of model text |

### 13.2 Choices made here

Routine choices with a clear default; listed so they are visible, not hidden.

| Choice | Why | Cost of reversing later |
|---|---|---|
| Entries, not cards | Reads like a notebook; denser without feeling dense; boxes are kept for non-notes | Low: one component |
| Composer at the top on Android, actions docked to the keyboard | New entries appear right under where you typed, and Save is still under the thumb | Medium: the capture screen's layout |
| Title doubles as the label switch; no navigation drawer or bottom bar | Nothing to switch between; more room for notes | Low |
| Carta as the default theme | Warm, distinctive, the original paper and terracotta; Ardesia and Salvia are one tap away | None: a setting |
| "+N" instead of a second row of suggestions | One row whatever the number of plugins; the rest is one tap away | Low |
| The daily slip's dismissal is final for the day | One item a day is the slot's whole budget; a second chance would make it a feed | None: a constant |
| Literata + Inter + JetBrains Mono | Open licences, screen-tuned, full Latin Extended, optical sizes | Low: tokens |
| Edits autosave; new notes save explicitly | The composer clears on save, so it needs a moment of commitment; editing should feel like paper | Medium: interacts with versioning |
| No per-label colours or icons | Calm page, fewer decisions per label | Low to add later, hard to take away |
| Pending suggestions expire after 14 days | Keeps the pile from turning into a second inbox | None: a constant |
| No density setting | One well-tuned default; settings are the first thing a calm app cuts | Low |
| Dictation swaps the keyboard for a listening panel; in-flight words in `ink-3`, never dashed | The owner sees that the phone is listening, in which language, and which words are settled; dashed stays reserved for proposals | Low: one composer state |
| Dictation language is a per-phone default switched in the action bar, not a per-note field | Two taps at most, no data-model change; notes have no language field | Low |

Takeaway: the app is designed as a quiet page where the owner's words are set like a book, everything the machine proposes stays visibly dashed until a person makes it solid, and plugins only borrow the furniture, so eighteen of them on still look like a page of notes in whichever of three papers the owner prefers.
