# 05 · Redesign UX: a quiet core, plugins and three themes

Status: approved 2026-10-13 and folded into 01-03; kept as the decision record. Companion to [`04-redesign.md`](04-redesign.md), which owns the mechanism (what is core, how a plugin is built, loaded and switched); this document owns how plugin output and themes look and behave. Mockups: [`design/redesign.html`](design/redesign.html), 24 frames (R1 to R24) on an Android phone and a desktop browser, with a switch for the three themes and for light, dark or both. §9 lists the edits that were made to 03, which is current where a detail differs.

Conventions: R-numbers are frames in `redesign.html`; A- and W-numbers are frames in the original [`design/mockups.html`](design/mockups.html). Plugin ids are in `code`. "Slot", "Review", "daily slip", "command result" and the decision numbers (D16, D29 to D31) mean what 04 says they mean.

---

## 1. The story

The owner said the first scope was too narrow: more AI features, a couple of built-in themes, plugins for everything that is not the heart of the app, and one condition, *never clutter the space*. That condition is the hard part. Every feature in a notes app wants a button, a badge, a section or a banner, and each one is reasonable on its own. Twenty-five plugins, each with one reasonable button, make a dashboard.

So this design starts from the opposite end: **plugins never choose where they go.** The core already has a handful of quiet places that exist without any plugin: the line of dashed proposals under a note, the margin beside it, the command palette, the meta line with the time. A plugin fills one of those places, inside a budget the host enforces, using components the host draws. The result is R10: eighteen plugins switched on, and the home screen is still a column of notes with one Review line, one slip of paper at the top, and three extra names in the rail.

The rest of this document says, place by place, what a plugin may put there (§3); shows that turning the default plugins on changes about 60 dp of an Android screen (§4); designs the one screen where plugins are visible as such (§5); walks through six AI flows end to end (§6); settles how notes written by a machine look (§7); and defines three themes with their contrast (§8).

---

## 2. Principles, revised

03's principles 1 to 8 stand. Three change or join:

- **3, extended. Serif is the owner's voice; sans is the app's, including notes the app wrote.** A note written by a plugin (a digest, a saved answer, a follow-up) is set in the sans, carries a chip naming the plugin with the spark glyph, and ends with one quiet line saying so. The first time the owner edits it, it becomes theirs: serif, a plain chip, no line (§7). *Rule:* a glance tells you whose words a note holds, even in the timeline.
- **9, replaced. Every plugin off is a complete design.** With nothing switched on, no screen looks like it is missing something, and switching a plugin off leaves no trace on screen (its data stays). *Rule:* R1 and R3 are the reference; every frame must still look finished with all plugin output deleted.
- **10, new. Plugins borrow the furniture.** A plugin never picks a position, colour, icon, size or animation. It fills a slot the core already draws (§3), within that slot's budget, using the closed component vocabulary of 04 §3.4. *Rule:* if a plugin needs a surface that no slot provides, the slot is designed in the core for every plugin, or the plugin does not ship. The only exceptions are the device plugins' mic, widget and tile (§3.12).

The **spark glyph** changes meaning slightly. In 03 it was reserved for Ask. Now it means "a model reads or writes here": the Ask row, the chip on a machine-written note, and the AI line on a plugin's row in Settings. It still never appears on suggestions, whose dashed outline already says "proposed", and it is never decoration.

---

## 3. The clutter contract, in visual terms

04 §3.4 defines the slots and their budgets in code. This section is how each one looks and what overflowing it does. A slot that no enabled plugin uses draws nothing: no header, no empty state, no gap.

| Slot | Android | Web | Budget | Frames |
|---|---|---|---|---|
| Suggestion line | Under an entry and at the end of a note | Same | One row for all plugins; "+N" for the rest | R2, R4, R5, R7, R15 |
| Review | One line under the composer; a full screen | One line under the composer; a page | One line; one screen | R2, R4, R10, R11 |
| Note panel | "About this note" colophon and sheet | Right margin from 1280 px, else below the note | One collapsed section per plugin | R5, R6, R7 |
| Meta token | The entry's meta line | Same | One token per entry | R5, R7 |
| Daily slip | Top of the timeline, and the widget | Top of the timeline | One item a day | R10 |
| Commands | Note action sheet, composer `/` strip | ⌘K palette, composer `/` menu | Three per plugin per place | R8, R9 |
| Command result | Bottom sheet | Panel inside the palette | One at a time, never stored | R12, R14 |
| Views | "Views" group in the labels sheet | "Views" group in the rail | One per plugin, hideable | R10 |
| Settings page | Settings → Plugins → name | Same | One generated page | R19, R22 |
| Search source | "≈ similar meaning" on a hit | Same | A marker, no surface | (03 A6, W3) |
| New notes | Timeline entry with a "via" chip | Same | Five unattended notes a day per plugin | R10, R13, R16 |
| Device surfaces | Composer mic, widget, Quick Settings tile | None | Compiled-in device plugins only | (03 A1, A8, A9) |

What has **no slot at all**, so no plugin can ask for it: a toolbar or top-bar button, a FAB, a header icon, a badge or unread count, a notification or sound, a banner, a toast of its own, a modal that opens by itself, an onboarding tour, a colour or icon of its own, and any animation beyond the host's.

### 3.1 Suggestion line

- **Where.** Under an entry in the timeline and at the end of an open note, on both platforms. Pills are the 03 proposal: dashed accent outline, sans, a leading glyph chosen by the host from the action. `+` for things that add (a label, a title, a link); a wand for things that change the owner's words (`replace_body`, R15). A grey "Dismiss" ends the row.
- **Budget.** One row shared by every plugin. At most three pending suggestions per plugin per note (04 enforces this in the write API). The host ranks across plugins: the owner's plugin order first, then the plugin's own confidence.
- **Overflow.** Whatever does not fit the width folds into a "+N" chip, drawn in `sunken` with no dashes because it is not itself a proposal. Tapping it unfolds a second row for that note only; leaving the note folds it again. There is never a second row the owner did not ask for.
- **Can.** Offer one of the core actions of 04 §3.4: add a label, set a title, append text (a link to another note), replace the body (opens a diff), merge two notes (opens the core Compare), open a note or view. Labels not yet in the vocabulary carry 03's `new` mark.
- **Cannot.** Choose its glyph, colour or position; add an explanation to the row (a long-press or hover shows "Suggested by Link enrichment" and the plugin's reason); appear while the composer is focused on that note; move anything when it arrives (03 §6.2 rule 4).
- **Keys and gestures.** Unchanged from 03: tap or click accepts one pill; `a` accepts the row, `x` dismisses it; hover shows a × per pill on the web.

### 3.2 Review

- **Where.** One line under the composer, in `ink-2` with "Review" in the accent: "6 suggestions · Review". It opens the Review screen (Android full screen, R11; web, the same layout in the centre column at `/review`).
- **Budget.** One line for every plugin together, shown only from three pending items up. No count in the rail, no badge anywhere.
- **The screen.** Grouped by kind, each group headed by an overline and the plugin's name in grey: Notes (label suggestions waiting on more than one note), Labels (merges and clusters from `label-gardener`), Duplicates (pairs from `duplicates`). Review cards may be boxed, because they are proposals rather than notes (03 keeps boxes for non-notes). Each card asks one question in the sans, quotes the owner's notes in the serif, and offers two answers: the change and its refusal ("Merge" / "Not the same", "Compare" / "Keep both", the dashed pill / "Dismiss").
- **Overflow.** A group shows three cards, then "4 more" in the accent. "3 notes wait for labels · One by one" opens 03's one-note-at-a-time review (A7).
- **Can.** Label merges and renames, which go through the core impact dialog and need a connection (offline the button reads "Needs a connection"); cluster labels as dashed pills; duplicate pairs into Compare.
- **Cannot.** Have a screen of its own, a card layout of its own, or interrupt. When the queue is empty the screen says "All caught up." and the line under the composer disappears.

### 3.3 Note panel

- **Where.** On the web, the right margin of an open note from 1280 px (03's marginalia), below the note on narrower windows (R7). On Android, the note ends with one colophon line, "About this note · Source · Related 5 · Backlinks 1", that opens a sheet (R6). No icon is added to the note's top bar.
- **Budget.** One section per plugin; each starts collapsed as an overline plus a one-line summary ("Related · 5 notes"); at most ten items inside. The owner orders sections once with "Arrange" (drag), and that order also ranks the meta token and suggestions.
- **Overflow.** A section opens only when the owner opens it; the open state is remembered per device. Sections with nothing to say are absent, which is why the short note in R6 has no Summary. On the web the margin scrolls with the page, never inside itself.
- **Can.** Text, Markdown, note references and commands from the vocabulary. Summaries live here (`title-summary`'s "Summary"), not as notes (04 §3.13). The last line of a section may credit its source in `ink-3`: "Link enrichment · fetched 18:03 · summary by qwen3.5".
- **Cannot.** Expand itself, show pills (proposals go to the suggestion line), show a form, show a section while empty, or show "loading" (live sections simply appear when ready, and are absent offline).

### 3.4 Entry meta token

- **Where.** The meta line under an entry and under a note's date, after the core chips (source, agent, unsynced ring).
- **Budget.** One token per entry across all plugins, at most 24 characters, sans `meta` in `ink-3`. When two plugins have a token, the owner's plugin order picks one; the other plugin shows that fact in its panel section instead.
- **Can.** A short fact: "23 min read" (`link-enrichment`), "2 of 5 done" (`tasks`).
- **Cannot.** Use colour or the accent, be a button, or wrap to a second line.

### 3.5 Daily slip

- **Where.** At the top of the timeline, under the composer and the Review line, on both platforms; on Android the widget shows the same item (03 A8). Drawn as 03's slip: a `sunken` box (the only boxed thing in the timeline), an overline the host writes from the item's kind ("From your notes · 19 March 2025", "On this day · 2024"), the owner's note in the serif, and Open, Archive, × ("not today").
- **Budget.** One item a day across all plugins. Dismissing is final until tomorrow. In the catalog only `resurface` uses it.
- **Cannot.** Show more than one item, rotate, come back the same day, add model-written text, or show a plugin's name (the overline is enough).

### 3.6 Commands

- **Where.** Web: the ⌘K palette (R8) and a `/` menu in the composer (type `/` at the start of a line). With a note open, the palette leads with a "This note" group: plugin verbs first, each with its plugin's name in grey on the right, then core actions with their keys. Android: the note's action sheet (R9), where plugin verbs sit between two hairlines under the four core tiles and above Move to trash; and the composer's `/` strip above the keyboard, drawn like label autocomplete.
- **Budget.** Three commands per plugin per place, titles of at most 24 characters. Within a group, verbs are ordered by how often the owner uses them. The Android sheet shows six plugin verbs, then "More".
- **Cannot.** Have a toolbar twin, a FAB, a header icon, a keyboard shortcut of its own, or a context menu anywhere else. Commands that need the server are absent offline, not greyed.

### 3.7 Command result

- **Where.** Web: a panel that replaces the palette's list (R12). Android: a bottom sheet over the screen the command ran from (R14), with the results underneath still in place.
- **Look.** Sans throughout, numbered citations in `accent-soft` chips, cited notes in the serif, a footer naming the model and where it runs. Actions: Copy and, as the primary, "Save as note" (⌘S on the web).
- **Budget.** Vocabulary only; one result at a time; nothing is stored unless the owner saves it. Esc, or swiping the sheet down, forgets it.
- **Cannot.** Stay on screen after dismissal, become a note without the owner's save, or open a second sheet.

### 3.8 Views

- **Where.** A "Views" group between the core items and Labels in the web rail, and in the Android labels sheet. The group is absent until a plugin brings a view (R3 has none).
- **Budget.** One view per plugin; a view is a declarative filter (04 §3.5), drawn as an ordinary timeline of entries with the core components. Counts in `ink-3`, never badges.
- **Visibility.** A view from a plugin the owner switched on by hand appears at once. A view from a plugin that came on by default (D31: Unlabeled from `label-suggestions`) starts **hidden**: ⌘K and the labels sheet's "Show more views" reach it, and one click pins it to the rail. Any view can be hidden from its own menu.
- **Cannot.** Have its own layout, sort or header controls.

### 3.9 Settings page

One page per plugin, generated from its schema plus host-drawn sections (§5.3). Background-only plugins (`email-in`, `webhooks`, `telegram`) use only this slot.

### 3.10 Search source

"≈ similar meaning" in `ink-3` next to a hit that matched by meaning only (03 S1). No section, no separate list. Offline, search runs on the phone by keyword and the marker is absent.

### 3.11 New notes

A plugin-written note enters the timeline at the time it was written, with a "via Weekly digest" chip carrying the spark. The body is in the sans, and one quiet line ends the note (§7). Unattended output (schedules, hooks) is capped at five notes a day per plugin; notes the owner asked for (Save as note, Develop this idea) are not capped.

### 3.12 Device surfaces

The composer's mic (`dictation`), the home-screen widget and the Quick Settings tile (`quick-capture`) are the only plugin-owned buttons in the product, because they are compiled into the APK and reviewed like core code (04 §3.6). Switched off, the mic leaves the action bar; a widget already on the home screen shows one line, "Quick capture is off. Turn it on in Settings → Plugins.", and the tile reads as unavailable.

### 3.13 MCP tools

No screen. A plugin's tools are listed in plain words on its page, under Access ("Adds one tool for agents, `digest_latest`").

---

## 4. Core alone, then the default set

**Core with nothing switched on** (R1 Android, R3 web) is a finished notebook: composer, timeline with day headers, labels typed inline, keyword search, sync with its unsynced ring, the core "via Claude Code" chip on agent notes, Archive and Trash. The web rail has no Views group and its status line says only "Synced". The palette searches by keyword and lists core actions. Nothing hints at a missing feature.

**The default set** of D31 (core plus `dictation` and `quick-capture`; with AI also `label-suggestions`, `semantic-search`, `related` and `ask`) changes the home screen in exactly these places (R2, R4):

| Change | Where | When it shows |
|---|---|---|
| "6 suggestions · Review" | One line under the composer | From three pending proposals |
| Dashed label pills | Under a fresh note | After a save, until decided or 14 days pass |
| "Synced · AI ready" | Web rail status line | Always; "Indexing 1,240 of 5,000" during the backfill |
| "≈ similar meaning" | Search hits | Only while searching |
| "Ask your notes" row | Palette, Android search | Only while searching |
| Related | Note panel | Only on an open note |
| Unlabeled view | Rail and labels sheet | Hidden at first (§3.8) |

On the Android home screen that is about 60 dp of a 780 dp screen: the Review line and one row of pills. Dictation and quick capture add nothing to the timeline; their mic and widget live in the composer and on the home screen, as in 03.

**Eighteen plugins on** (R10, web) adds, beyond the default set: the daily slip from `resurface`, a saved Ask answer and a weekly digest as ordinary entries in the sans, and three views in the rail (From agents 9, Tasks 7, Digests 6). Still one Review line, still one row of pills per note.

---

## 5. The plugin manager

Plugins are visible as plugins in exactly one place: Settings → Plugins. Everywhere else the owner sees only what they do.

### 5.1 The list (R17 web, R20 Android)

- **Header.** One sentence of what plugins are ("Everything beyond notes, labels and search. Switch one on and it takes its place; switch it off and it leaves the screen, while its data and any notes it wrote stay."), the line "New plugins are added by whoever runs the server, in `/data/plugins`." (D27: there is no store and no install button), and one AI summary line: "Chat qwen3.5 · Embed nomic-embed-text · at ollama.home.arpa", linking to AI settings.
- **Filter.** A two-way segmented control, "All 25" / "On 18". No search box; 25 rows read in one pass.
- **Groups.** Capture, Organize, Recall, Develop, Agents, Data, in that order, each headed by an overline with a count ("Capture · 5 of 8 on"). Theme packs (category `look`) are not listed here: they appear as cards in Appearance (§8.4). Rows keep catalog order so nothing jumps when toggled; `resurface` leads Recall among the ones worth trying (D31).
- **A row.** No icon, no colour. Name in `ui` 500; a platform tag ("Android") for device plugins; one sentence in `ink-2`; one grey facts line; the switch on the right, with "Settings" before it when the plugin is on and has a page. Tags "Default with AI" mark the D31 set.
- **The facts line** is computed by the host, never written by the plugin. It names each place text leaves the server, with a glyph per kind: a spark and the capability for AI ("Chat: page and note to qwen3.5 at ollama.home.arpa"), a globe for fetching ("Fetches the linked page"), an envelope for mail and bots ("Talks to api.telegram.org"), an arrow-out for destinations outside the network ("Chat: the note to gpt-oss:120b at ollama.com, outside your network"), a lock for nothing ("Stays on your server", "Stays on the phone"), a clock for schedules ("Sundays 18:00").

**States** (04 §3.7), drawn without colour:

| State | The switch place shows | The facts line adds |
|---|---|---|
| Available | Switch off | n/a |
| Enabled | "Settings" and the switch on | n/a |
| Requirement missing | "Needs a vision model", linking to AI settings; a plugin that was on stays on underneath and resumes when the model appears (04's Waiting) | "Vision: no vision model is set up" |
| Pinned by the operator | A lock and "Set in the environment"; the state in words ("On") | The variable, on its page: `SPUNTO_PLUGINS` |
| Failed | The switch, still on | "Paused after 5 failed runs · Details" in `ink-2`, linking to the status page |
| Removed (third-party code gone) | "Removed" | "214 records kept · Delete data…" |

Third-party plugins look the same; their page header says "Third party" instead of "built in" (§5.3).

### 5.2 Turning one on: the permissions sheet (R18 web, R21 Android)

Switching a plugin on never starts it directly. A dialog on the web, a bottom sheet on Android, always with the same four parts, generated from the manifest and the plugin's AI route:

1. **Title and what it does.** "Turn on Link enrichment?" and the plugin's sentence.
2. **It will.** One line per permission, in plain words, each with a qualifier in `ink-2`: "Fetch the page your note links to · Once, from your server, only for links you saved yourself. The site sees your server's address." "Read notes that contain a link · Never private notes." "Propose a title and labels · As dashed pills you accept or dismiss."
3. **AI and where note text goes.** One line per AI capability, built from a fixed sentence: *Capability: what goes to model at host, on your network / outside your network.* For example: "Chat: page text and your note go to qwen3.5 at ollama.home.arpa, on your network." When the plugin is routed differently from the rest (D16), the line says so and links to "Change route": "Chat: the note goes to gpt-oss:120b at ollama.com, outside your network. Other plugins use qwen3.5 at ollama.home.arpa." "Outside your network" is said in words, never in red. A plugin with no AI and no network gets one line: "Stays on your server" (or "Stays on the phone").
4. **Where it shows up.** The slots in words: "Pills under the note, a Source section in About this note, and the reading time on the note." Or "'Translate…' in ⌘K, the / menu and the note menu. Nothing else on screen."

Buttons: Cancel, and "Turn on" as the primary. The same sheet appears again, headed "Weekly digest now also wants to…", when an update adds a permission. Switching off asks nothing: the plugin leaves the screen at once, and one line offers "Undo" for a few seconds.

### 5.3 A plugin's own page (R19, R22)

Every plugin's page has the same shape, so learning one teaches all of them:

1. **Header.** Name, the on switch, the sentence, and a grey line: "Recall · built in · 1.0.0 · on since 2 September".
2. **Settings,** generated from the plugin's schema:

| Schema | Web | Android |
|---|---|---|
| `boolean` | Switch | Switch |
| `enum`, up to 3 values | Segmented control | Segmented control |
| `enum`, more values | Select | Menu |
| `string` | Text field | Text field |
| `string` with `format: time` | Time field | Material time picker |
| `integer`, `number` with bounds | Stepper | Stepper |
| `string` list | Chips with "Add" | Chips with "Add" |
| `string` list of labels | Label chips with autocomplete | Label chips with autocomplete |
| `x-secret` | Read-only row: "Set in the environment" and the variable in mono, `SPUNTO_PLUGIN_TELEGRAM_BOT_TOKEN` | Same |
| `x-scope: device` | The control, with "This browser only" | The control, with "This phone only" |

   A field's `title` is its label and its `description` the helper line under it. Secrets are never inputs (R22), so a plugin page can never be where a token leaks. Offline on Android the page renders from the cached manifest and is read-only, with one line: "Changes need a connection."
3. **Host-drawn sections,** the same for every plugin and never written by it: **AI** (the route, as a select over the owner's AI profiles, and the "where note text goes" line); **Run** (its commands as buttons, each with what to expect: "Usually under a minute. The note appears in your timeline and in Digests."); **Activity** ("Last run Sunday 12 October, 18:00 · read 31 notes · wrote 1 note · no failures"); **Access** (the permission sentences, caps and MCP tools); and **"Delete this plugin's data…"**, a danger-coloured link that opens a dialog with the count and what stays ("Its notes stay: they are notes.").

---

## 6. Flows

### 6.1 Ask your notes (R12 web, R14 Android)

1. **Web.** ⌘K, then a question. With three or more words typed, "Ask your notes" with the spark appears among the results; if the query ends with `?` (or reads as a question) it is highlighted so ↵ asks, otherwise ⌘↵ asks (03 §4.4, unchanged). **Android.** The search screen's Ask row, at the top of the results.
2. **Waiting.** "Reading 8 notes…" with 03's three-dot pulse, the only waiting animation in the app.
3. **Answer.** A command result: the answer in the sans at `ui-lg`, numbered citations, a Sources list of the owner's notes in the serif with their dates, and a footer: "From 3 notes · qwen3.5 at ollama.home.arpa · read-only". On Android it rises as a sheet over the search results, which stay underneath for a plain look.
4. **Nothing found.** "Nothing in your notes covers this." with "Search instead". The model is never asked to guess.
5. **Keep it.** ⌘S or "Save as note" writes a new note headed by the question, with the answer as the body and the citations as links to the notes. It enters the timeline as a machine-written note (§7, R10): sans, "via Ask your notes" chip, quiet line. It is left out of later answers until the owner edits it, so Ask never cites itself (D29).
6. **Forget it.** Esc or swiping the sheet down. Nothing is stored: no history of questions, no "recent answers".
7. **Offline.** The Ask row is absent. **Model unreachable:** one line, "Couldn't reach the model. Search results are below."

Private notes are never read. Up to 8 notes go to the chat model per question, as the plugin's row says.

### 6.2 Weekly digest (R13, R19, R10)

1. **Off by default.** Turning it on shows the sheet: "A week of notes goes to qwen3.5 at ollama.home.arpa, on your network. Private notes never do. Runs every Sunday at 18:00. Adds one tool for agents."
2. **Settings** (R19): Day (Monday / Friday / Sunday, a segmented control), Time (18:00), "Skip weeks with fewer notes than" (a stepper, 5), and "Leave out: notes with these labels are never read" (label chips, for example `#diario`, `#health`).
3. **Sunday, 18:00.** The job reads the week's non-private notes the owner wrote (never earlier digests or other machine-written notes) and writes one note, "Week 41 · 6 to 12 October", with four short sections: Themes, Open questions, Worth continuing, Quiet this week. Every claim cites its notes. No notification, no badge: the note is simply in the timeline at 18:00, with "via Weekly digest · read 31 notes".
4. **Reading it.** On the web from 1280 px the citations render as sidenotes in the margin, in the serif, because they point at the owner's notes; narrower, and on Android, as a Sources list at the end.
5. **The Digests view.** The plugin's view (all notes from `plugin:digest`) shows in the rail, since the owner switched the plugin on by hand.
6. **Quiet week.** No note at all; the plugin's Activity line says "Skipped: 3 notes this week".
7. **Now, by hand.** "Write this week's digest" in ⌘K and on the plugin's page. If this week already has one, the command says so and opens it instead of writing a second.
8. **Agents** can read the latest digest through `digest_latest`.

### 6.3 Develop this idea (R8, R9, R16)

1. **Start.** On an open note, ⌘K shows "Develop this idea" first under "This note" (R8); on Android it is in the note's action sheet (R9). It is never a button on the note.
2. **While it works.** The new note opens at once in a waiting state: its date, the chip, the "Develops" block, and "Reading 5 related notes…" where the body will be. Going back cancels nothing; the note arrives in the timeline when ready. If the model fails, the screen says "Couldn't reach the model. Nothing was written." and no empty note is ever created.
3. **The result** (R16). A new note "via Develop this idea", opening with a "Develops" block that quotes the original in the serif (the owner's words) and links to it. Below, in the sans: a short heading, then Questions, Next steps and Angles, citing the related notes it read. The quiet line ends it.
4. **Links both ways.** The link to the original is ordinary text in the new note, so it works with any plugin set. With `links` on, the original also lists the follow-up under Backlinks in its panel; with it off, the original shows nothing.
5. **Never an edit.** The original is untouched. Owner-started, so no daily cap. The note and up to five related notes go to the chat model, as the row says.

### 6.4 Tidy a dictated note (R15)

1. **Trigger.** A minute after a dictated note is saved (source "dictated"), so the owner can keep typing first, Tidy offers one pill, "Tidy text", with the wand glyph. It shares the suggestion line with label pills. The same verb is in ⌘K and the note's actions for any note, for pasted text.
2. **The diff.** Tapping opens a sheet (Android, stacked) or an inline diff (web): removed words struck through in `ink-3`, proposed words with the dashed underline of a proposal, in the serif because they are the owner's sentence. One sans line says what changed: "Removed filler words and a repeat, added punctuation and capitals. Nothing else changed."
3. **Decide.** "Keep mine" dismisses it; "Use tidy text" applies it as an ordinary edit against the version it was made from, so anything typed meanwhile merges as any edit would. "Your version stays in History."
4. **Superseded.** If the owner edits the note before deciding, the pill quietly goes away; Tidy may offer again after the next quiet minute.
5. **The result is the owner's** and stays in the serif: Tidy changed their words with their consent, it did not write a note.
6. **Setting.** "Offer for: Dictated notes / Dictated and shared / Every new note" (a segmented control).

### 6.5 Link enrichment (R5, R6, R7, R21)

1. **Capture.** The owner shares a page from Chrome (03 A3) or pastes a link. The note's meta line carries the core source chip: "inkandswitch.com · from Chrome".
2. **Fetch.** The server fetches the page once, through the guarded fetch (D30), and only for links the owner saved; notes from agents or email are fetched only if the owner runs "Fetch link details" from the note's commands.
3. **What appears.** On the suggestion line, "+ Page title" (set title) next to label pills, folding into "+2" on a phone (R5). In the note panel, a **Source** section: page title, authors and year, reading time, a three-line summary in the sans, "Open page", "Copy link", and the credit line "Link enrichment · fetched 18:03 · summary by qwen3.5" (R6, R7). In the meta line, one token: "23 min read".
4. **No new note.** The summary lives in the panel, never as a note (04 §3.13).
5. **Without a chat model** it still offers the page title and a Source section without summary or label ideas. **When the fetch fails** (paywall, 404), the Source section shows the link only, or is absent; nothing appears in the timeline.

### 6.6 Label gardener (R11)

1. **Rhythm.** Once a week, after the digest's slot, it looks at the label tree and the unlabeled notes. It sends at most three proposals a week (a setting), so Review never turns into a chore.
2. **Where.** Proposals go to Review, never under a single note. The Review line counts them with everything else.
3. **A merge** card asks: "Merge `#reading/essay` into `#reading/essays`? They look like one label." with the stakes in grey: "3 notes and 19 notes · whole labels only". "Merge" opens the core rename and merge dialog with its impact line, so the change is the same one the owner could make by hand; it needs a connection.
4. **A cluster** card says "7 unlabeled notes are about running routes in Rome." (the count opens those notes) and offers a dashed "+ running/routes new" pill. Accepting adds the label to all seven, with one undo.
5. **Not the same / Dismiss** is remembered: that pair or cluster is never proposed again.
6. It replaces the brief's separate "Emerging topics" (04 §4).

---

## 7. Machine-written notes (D29)

Saved answers, digests, follow-ups and translations are notes, but notes the owner did not write. They must be readable as such at a glance, and must not feed the next machine.

| | Machine-written | After the owner's first edit |
|---|---|---|
| Body | Sans (`ui-lg` on the web, 16 sp on Android) | Serif, like any note |
| Chip | Spark + "via Weekly digest" | Plain "from Weekly digest", no spark |
| Last line | "Written by Weekly digest with qwen3.5. Plugins leave it out until you edit it." in `ink-3` | Gone |
| AI inputs, resurfacing | Excluded | Included |
| Search, labels, views, export | Like any note | Like any note |

Editing adopts the whole note: there is no per-paragraph ownership. The first adoption ever shows one line under the note, once per account: "This note is yours now: plugins can read it and it can resurface." Screen readers announce such a note as "Note written by Weekly digest, Sunday 18:00…".

Notes that agents write through MCP keep 03's treatment (serif, "via Claude Code" chip with the terminal glyph, the From agents view), because an agent writes at the owner's request. Whether agent notes should also stay out of AI inputs is D29's question, not this document's.

---

## 8. Themes

### 8.1 What a theme changes, and what it cannot

A theme is a pack of colour values and nothing else. That keeps plugin output from looking right in one theme and wrong in another, and keeps 03's typographic meaning intact.

- **Themeable:** the colour roles of 03 §11.3: `paper`, `raised`, `sunken`, `ink`, `ink-2`, `ink-3`, `rule`, `rule-strong`, `field`, `accent`, `accent-strong`, `accent-soft`, `on-accent`, `highlight`, `danger`, `warn`, plus `scrim` and the hover and press state layers, each in a light and a dark set.
- **Fixed:** typefaces, the type scale, spacing, radii, elevation (shadows derive from `ink`), motion, icons, and the dashed proposal style.
- **Faces stay out of themes.** The serif marks the owner's words and the sans the machine's (principle 3); a theme that set notes in a sans would erase that. The reading face (Serif or Sans) stays the owner's per-device setting, as in 03. This narrows 04 §5, which lets packs choose faces.
- **One accent per theme.** 03's five-accent picker is replaced by the three themes. Cotto becomes Carta's accent unchanged; Inchiostro is retuned as Ardesia's for cool paper; Salvia becomes the Salvia theme with a deeper green; Prugna and Ocra retire (either can return as a pack). The pack format may keep an accent set (04 §5); the picker shows an Accent row only when the chosen pack has more than one, and no built-in does.

Derived values, identical in every theme: `scrim` is `ink` at 32 % in light and black at 50 % in dark; the state layers are `ink` at 5 % (hover) and 9 % (press) in light, 6 % and 10 % in dark.

### 8.2 The three built-in themes

- **Carta** ("paper"): the original warm paper and terracotta, 03's values unchanged. The default.
- **Ardesia** ("slate"): cool grey-blue paper, blue-black ink, an ink-blue accent. Crisp on bright screens and for long sessions at a desk.
- **Salvia** ("sage"): pale sage paper, green-black ink, a forest-green accent. The softest of the three.

Contrast is WCAG 2.2 relative luminance, computed against the background named after each ratio: **p** `paper`, **r** `raised`, **s** `sunken`, **soft** `accent-soft`, **acc** `accent`. Text pairs need 4.5:1 (AA, normal text) and `field` needs 3:1 (non-text). Every pair passes, in every theme and mode. `rule`, `rule-strong` and the fills are decorative or backgrounds, so they carry no ratio; `warn` marks always sit beside words (03 §10). The page computes the same table live from the values the frames use.

#### Carta

| Token | Role | Light | Contrast | Dark | Contrast |
|---|---|---|---|---|---|
| `paper` | Page background | `#F8F5F0` |  | `#171412` |  |
| `raised` | Composer, sheets, palette | `#FEFDFA` |  | `#211D1A` |  |
| `sunken` | Rail, slip, quotes, chips | `#F0EDE6` |  | `#100E0C` |  |
| `ink` | Note text, titles | `#221D18` | 15.4 p · 16.4 r · 14.3 s | `#EAE5DD` | 14.6 p · 13.3 r · 15.4 s |
| `ink-2` | Secondary UI text | `#5B544C` | 6.9 p · 7.3 r · 6.4 s | `#BDB6AE` | 9.1 p · 8.3 r · 9.6 s |
| `ink-3` | Meta, counts, placeholders | `#6E6860` | 5.1 p · 5.4 r · 4.7 s · 4.7 soft | `#9D978F` | 6.3 p · 5.8 r · 6.7 s · 4.8 soft |
| `rule` | Hairlines, decorative only | `#E3DFD8` |  | `#2D2925` |  |
| `rule-strong` | Dividers, link underlines | `#CCC7BE` |  | `#413C37` |  |
| `field` | Field and switch borders (3:1) | `#8A857E` | 3.4 p · 3.6 r | `#736E67` | 3.6 p · 3.3 r |
| `accent` | Labels, primary, focus, proposals | `#9F4C2A` | 5.5 p · 5.8 r · 5.1 s · 5.0 soft | `#E7986F` | 8.0 p · 7.3 r · 8.4 s · 6.0 soft |
| `accent-strong` | Accent text on soft fills | `#883A1D` | 7.2 p · 6.7 soft | `#F6B38B` | 10.3 p · 7.7 soft |
| `accent-soft` | Selected rows, citation chips | `#FAE9DE` |  | `#3F271D` |  |
| `on-accent` | Text on accent fills | `#FEF9F4` | 5.7 acc | `#24140C` | 7.7 acc |
| `highlight` | Search match fill (ink on it) | `#F5E4B0` | ink 13.2 | `#5F4A1A` | ink 6.8 |
| `danger` | Destructive actions | `#AF2934` | 6.1 p · 6.5 r | `#ED7473` | 6.4 p · 5.9 r |
| `warn` | Warning marks beside words | `#CD9130` |  | `#E1AD57` |  |

#### Ardesia

| Token | Role | Light | Contrast | Dark | Contrast |
|---|---|---|---|---|---|
| `paper` | Page background | `#F4F6F8` |  | `#13161A` |  |
| `raised` | Composer, sheets, palette | `#FCFDFE` |  | `#1C2026` |  |
| `sunken` | Rail, slip, quotes, chips | `#EBEEF2` |  | `#0D0F12` |  |
| `ink` | Note text, titles | `#161B22` | 16.0 p · 17.0 r · 14.9 s | `#E4E8EE` | 14.8 p · 13.3 r · 15.6 s |
| `ink-2` | Secondary UI text | `#4A5361` | 7.2 p · 7.6 r · 6.7 s | `#B3BAC5` | 9.3 p · 8.4 r · 9.8 s |
| `ink-3` | Meta, counts, placeholders | `#5E6775` | 5.3 p · 5.6 r · 4.9 s · 4.8 soft | `#929AA6` | 6.4 p · 5.8 r · 6.8 s · 5.0 soft |
| `rule` | Hairlines, decorative only | `#DEE2E8` |  | `#272C33` |  |
| `rule-strong` | Dividers, link underlines | `#C5CBD4` |  | `#3A4049` |  |
| `field` | Field and switch borders (3:1) | `#7D8592` | 3.4 p · 3.7 r | `#6C7480` | 3.8 p · 3.5 r |
| `accent` | Labels, primary, focus, proposals | `#2F5E9E` | 6.0 p · 6.4 r · 5.6 s · 5.4 soft | `#8DB2EE` | 8.4 p · 7.6 r · 8.9 s · 6.6 soft |
| `accent-strong` | Accent text on soft fills | `#234B82` | 8.1 p · 7.3 soft | `#B0CBF5` | 11.0 p · 8.6 soft |
| `accent-soft` | Selected rows, citation chips | `#E3EBF7` |  | `#1E2B40` |  |
| `on-accent` | Text on accent fills | `#F7FAFE` | 6.2 acc | `#0F1A2B` | 8.1 acc |
| `highlight` | Search match fill (ink on it) | `#F2E5A8` | ink 13.6 | `#57481A` | ink 7.3 |
| `danger` | Destructive actions | `#B4232F` | 6.0 p · 6.4 r | `#F07A7A` | 6.7 p · 6.1 r |
| `warn` | Warning marks beside words | `#C98A1E` |  | `#E0AE55` |  |

#### Salvia

| Token | Role | Light | Contrast | Dark | Contrast |
|---|---|---|---|---|---|
| `paper` | Page background | `#F3F5F0` |  | `#121614` |  |
| `raised` | Composer, sheets, palette | `#FBFCF9` |  | `#1B201D` |  |
| `sunken` | Rail, slip, quotes, chips | `#E9EDE5` |  | `#0C0F0D` |  |
| `ink` | Note text, titles | `#1A1F1B` | 15.2 p · 16.2 r · 14.1 s | `#E2E8E2` | 14.7 p · 13.3 r · 15.5 s |
| `ink-2` | Secondary UI text | `#4D564F` | 6.9 p · 7.4 r · 6.4 s | `#B2BCB4` | 9.3 p · 8.5 r · 9.9 s |
| `ink-3` | Meta, counts, placeholders | `#616A63` | 5.1 p · 5.4 r · 4.7 s · 4.6 soft | `#919C94` | 6.4 p · 5.8 r · 6.8 s · 4.8 soft |
| `rule` | Hairlines, decorative only | `#DCE1D8` |  | `#262C28` |  |
| `rule-strong` | Dividers, link underlines | `#C3CABF` |  | `#39413B` |  |
| `field` | Field and switch borders (3:1) | `#7E877F` | 3.4 p · 3.6 r | `#6B756D` | 3.8 p · 3.5 r |
| `accent` | Labels, primary, focus, proposals | `#2D6A4F` | 5.8 p · 6.2 r · 5.4 s · 5.3 soft | `#7FC4A0` | 9.0 p · 8.1 r · 9.5 s · 6.6 soft |
| `accent-strong` | Accent text on soft fills | `#22553F` | 7.8 p · 7.1 soft | `#A6DABD` | 11.6 p · 8.6 soft |
| `accent-soft` | Selected rows, citation chips | `#DDEEE3` |  | `#1C3327` |  |
| `on-accent` | Text on accent fills | `#F6FBF7` | 6.1 acc | `#0D1F15` | 8.4 acc |
| `highlight` | Search match fill (ink on it) | `#EFE3A8` | ink 12.9 | `#4F4A1C` | ink 7.2 |
| `danger` | Destructive actions | `#B0282F` | 6.0 p · 6.4 r | `#EE7C78` | 6.8 p · 6.1 r |
| `warn` | Warning marks beside words | `#C08A2A` |  | `#DDB05A` |  |

### 8.3 Pure black and wallpaper colours (Android)

Both stay Android options that apply on top of any theme (04 §5). **Pure black** sets `paper` and `sunken` to `#000000` and `raised` to a near-black tinted toward the theme: `#110F0D` (Carta), `#0F1114` (Ardesia), `#0E110F` (Salvia). On pure black, `ink` reaches 16.8, 17.1 and 16.9; `ink-3` 7.3, 7.4 and 7.4; `accent` 9.1, 9.7 and 10.3. On the tinted `raised`, `ink-3` stays at 6.6 or above. **Wallpaper colours** replace the theme's paper and accent while keeping its ink steps, as 03 describes for dynamic colour.

### 8.4 The theme picker (R23 web, R24 Android)

- **Where.** Settings → Appearance, first section.
- **Cards, not names.** Each theme is a small live page in its own colours and the current mode: a time, a line of serif text, a label, a dashed proposal, and five swatches (paper, ink, accent, accent-soft, highlight). Under it, the name and one sentence. The selected card has a 2 px accent ring and a check. Three cards in a row on the web; three compact cards across the phone.
- **Which devices.** The theme is an instance setting: choosing it changes every device. One switch below the cards, "Use a different theme on this device" (web) or "Only on this phone" (Android), turns the cards into a per-device choice; a grey line then says "Other devices use Carta."
- **Mode** (System / Light / Dark) is per device, as in 03. Android adds **Pure black** (enabled in dark only) and **Wallpaper colours**.
- **Also here, unchanged from 03:** reading face (Serif / Sans) and text size (S / M / L).
- **More themes.** Packs the operator adds in `/data/themes` appear as more cards. A pack that fails the contrast check at load is shown disabled, naming the failing pair ("`ink-3` on `sunken` is 4.1:1 in dark"), and cannot be chosen.
- **Switching** is instant, with no transition (03 §9.2), and the server writes the theme and mode into `index.html` so the web never flashes the wrong one (04 §5).

---

## 9. Changes for 03-design.md

Applied only after approval, together with 04 §7's list for 03, which this refines. Each line is a concrete edit.

- **Status line.** Add `05-redesign-ux.md` and `design/redesign.html` as inputs.
- **§1.** Keep the scene; add one sentence: with eighteen plugins on, the scene is the same.
- **§2.** Principle 3 gains machine-written notes and adoption by edit (§2 here). Principle 5 becomes "one accent per theme, used for meaning". Principle 9 becomes "Every plugin off is a complete design". Add principle 10, "Plugins borrow the furniture".
- **§3.1.** Objects: Suggestion becomes "a proposal from a plugin (label, title, link, text change, merge)"; add Plugin under Settings. Views table: From agents → `agent-inbox`, Unlabeled → `label-suggestions`, Open tasks → `tasks` (renamed "Tasks"), add Digests → `digest`; mark them as plugin views, hideable, and those from default-on plugins hidden at first; Review suggestions becomes the core Review screen for every plugin.
- **§3.2.** Tree: the labels sheet gains a Views group; the note gains "About this note" (sheet); the note action sheet gains plugin verbs between hairlines; the composer gains the `/` strip; "N notes have suggestions · Review" becomes "N suggestions · Review".
- **§3.3.** Rail: a Views group, present only when a plugin brings one; status line "Synced · AI ready". Margin: becomes the note panel with collapsible sections and "Arrange". Palette: a "This note" group with plugin verbs and their plugin names. URLs: `/agents`, `/unlabeled` become plugin view paths (`/v/<plugin>/<view>`); add `/settings/plugins`, `/settings/plugins/<id>`.
- **§3.5.** Add ⌘S (save a command result as a note) and `/` at the start of a composer line (command menu).
- **§4.3.** S4 Related moves into the note panel. S5 becomes the daily slip (one item a day across plugins).
- **§4.4.** Ask becomes a command result with "Save as note" (§6.1 here); the footer adds the host; nothing is stored without a save.
- **§4.5.** Add Review for every plugin (grouped queue, R11) and machine-written notes (§7 here).
- **§5.1.** The suggestion row becomes the shared suggestion line: one row, "+N" unfolds on demand, `+` or wand glyph by action. The meta line takes at most one plugin token after the core chips. Machine-written entries: sans body and spark chip.
- **§5.2.** A4 ends with the "About this note" colophon; A5 gains Views; A7 becomes the grouped Review (R11); A8's widget slip comes from `resurface`; A9 settings gains Plugins and Appearance (R20, R24).
- **§5.3.** W2's margin becomes the note panel (R7); W3 gains "This note" (R8); W4 becomes R12; W6's Settings gains Plugins, plugin pages and Appearance (R17, R19, R22, R23).
- **§6.** Rename "The clutter contract". Replace §6.1's surface map with §3 of this document. Rule 2: the spark means "a model reads or writes here" (Ask row, machine-written chips, AI lines in Plugins), never on suggestions. Rule 5: one row for all plugins with "+N". Rule 6: the Review line counts proposals from every plugin. Rule 8: "No AI prose in the owner's notes: machine-written text is a separate, marked note, and changes to the owner's words are diffs to accept (Tidy)". §6.3: the lifecycle covers every suggestion action and adds "superseded" for text changes when the note is edited first.
- **§8.3.** Add the new messages: "Needs a chat model", "Set in the environment", "Stays on your server", "Changes need a connection.", "All caught up.", "Nothing in your notes covers this.", "This note is yours now: plugins can read it and it can resurface."
- **§9.2.** "+N" unfolding and panel sections use the standard 180 ms (`dur-2`) expand; keyboard-driven changes do not move.
- **§10.** Contrast is per theme, both modes, values in §8.2 here. Suggestion pills announce their plugin ("Suggested title, from Link enrichment"); machine-written notes announce their writer; plugin switches are labelled "Weekly digest, off"; the permissions sheet's four parts are headings.
- **§11.3.** Replace the five-accent table with the three themes (§8.2 here); Carta keeps today's values. Dynamic colour and pure black apply on top of any theme. The M3 mapping is unchanged.
- **§11 intro and §11.9.** Split tokens into themeable and fixed (§8.1 here); `tokens.json` holds one colour set per theme and the generator emits all three.
- **§12.** Add `redesign.html` and its frames (§11 here); mark A7, W2 and W4 as superseded by R11, R7 and R12.
- **§13.** "Cotto as the default accent" becomes "Carta as the default theme". Add: views from default-on plugins start hidden; Ask answers are kept only on save; the slip's dismissal is final for the day; "+N" instead of a second row; faces are not themeable.

---

## 10. Decisions

Five UX choices with real alternatives, each cheap to reverse. The owner settled all five on 2026-10-13 at the recommended option, and the five differences from 04 listed below were resolved in 01 to 03 as this document proposes.

| # | Question | Recommended | Alternative and its consequence |
|---|---|---|---|
| U1 | Do themes replace the five-accent picker? | **Yes: three themes, one accent each.** Prugna and Ocra retire. | Keep accents inside themes: 3 × 5 combinations to tune and test for contrast, and an extra row in Appearance. |
| U2 | May a theme change typefaces? | **No.** Faces carry meaning (whose words); the reading face stays a per-device setting. | Allow it (04 §5): richer packs, and a pack can make notes and machine text look alike. |
| U3 | Do views from default-on plugins show at once? | **No, hidden until the owner pins them.** The first run stays as calm as 03. | Show them: an AI install starts with an Unlabeled view in the rail the owner never asked for. |
| U4 | How does adoption of a machine-written note announce itself? | **A one-time line, once per account.** | Silent: the switch to serif surprises once. A line every time: nagging. |
| U5 | Are Ask answers kept anywhere without a save? | **No.** Esc forgets; no history. | A "Recent answers" list: handy, and a second, invisible archive of model text. |

**Where this differs from 04, stated rather than silently diverging:**

1. **`link-enrichment` uses the meta token** ("23 min read"). 04 §4 lists only the suggestion line and the panel for it; its slots line needs "meta token" added.
2. **The digest's "Leave out" labels setting** is not in 04 §3.2's manifest; it needs one more schema field, a string list of labels. Label lists also need a hint in the schema subset (for example `format: "label"`) so clients draw label chips.
3. **Faces and accent sets in packs:** 04 §5 lets a pack pick faces and carry several accents; this document makes faces fixed and shows an accent row only for packs with more than one accent (U1, U2).
4. **The spark glyph is redefined** from "Ask only" (03) to "a model reads or writes here". 04 does not mention it, but its catalog's "AI" column maps to exactly these places.
5. **Label gardener's weekly cap** (three proposals a week) is a UX budget not in 04 §3.12's enforced list.

---

## 11. Frames in `redesign.html`

| Frame | Shows | Covers |
|---|---|---|
| R1 · Core only, Android | Timeline with nothing switched on | Core, principle 9 |
| R2 · Default set, Android | The same screen with the D31 set: Review line, pills | D31, §4 |
| R3 · Web, core only | Rail without Views, "Synced", keyword palette | Core |
| R4 · Web, default set | Review line, pills with hover ×, "AI ready" | §4 |
| R5 · Note, Android | Suggestion line with "+2", meta token, colophon | §3.1, §3.3, §3.4 |
| R6 · About this note | The panel as a sheet: Source, Related, Backlinks, Arrange | §3.3, §6.5 |
| R7 · Note, web | The margin as the note panel | §3.3, §6.5 |
| R8 · ⌘K on an open note | "This note" verbs with plugin names | §3.6, §6.3 |
| R9 · Note actions, Android | Plugin verbs between hairlines | §3.6, §6.3 |
| R10 · Home with eighteen plugins | Review line, daily slip, saved answer, digest, three views | §3.2, §3.5, §3.8, §4 |
| R11 · Review, Android | One grouped queue: notes, labels, duplicates | §3.2, §6.6 |
| R12 · Ask your notes, web | Command result in the palette, ⌘S to save | §3.7, §6.1 |
| R13 · Weekly digest, web | A machine-written note with sidenote citations | §6.2, §7 |
| R14 · Ask your notes, Android | The result as a sheet over search | §3.7, §6.1 |
| R15 · Tidy a dictated note | Wand pill and the diff sheet | §6.4 |
| R16 · Develop this idea | The follow-up note, linked to the original | §6.3, §7 |
| R17 · Plugins, web | The grouped list, facts lines, states | §5.1 |
| R18 · Turning on a plugin, web | The four-part dialog, routed outside the network | §5.2 |
| R19 · A plugin's own page | Generated settings and host sections (Weekly digest) | §5.3, §6.2 |
| R20 · Plugins, Android | The same list, thumb-sized, device plugins | §5.1, §3.12 |
| R21 · Turning on, Android | The four-part sheet for Link enrichment | §5.2, §6.5 |
| R22 · A generated page with a secret | "Set in the environment" for Telegram's token | §5.3 |
| R23 · Appearance, web | Theme cards, device override, mode, face, size | §8.4 |
| R24 · Appearance, Android | Compact cards, pure black, wallpaper colours | §8.3, §8.4 |

The page also shows each theme as a specimen in light and dark, and a token table whose contrast column is computed in the browser from the same values the frames use.

Takeaway: plugins get no space of their own, only budgeted turns in a few places the core already draws, so the app with eighteen plugins on still looks like a quiet page of notes, in whichever of three papers the owner prefers.
