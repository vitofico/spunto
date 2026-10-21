# UI/UX, workflow and design review, 2026-10-21

The owner asked for a detailed review of the web app and the Android app against one
goal: keep Spunto beautiful, modern and minimal, and make every feature work without
friction. Four reviewers worked in parallel, each against its own disposable server
with the same 24-note synthetic library (pinned, archived, trashed, private, long,
checklists, code, quotes, Italian, nested labels). No AI provider was configured, so
AI features were reviewed only in their "unavailable" state.

| Reviewer | What it covered | Build |
|---|---|---|
| Web visual | Every screen at 1440, 1024 and 390 px, light and dark, all five themes, contrast measured per theme | `web/dist` built from `42c642e` |
| Web workflow | Every journey end to end, offline, 2 s latency, simulated 403/409/500/503, two-tab conflicts, keyboard only; Chromium and WebKit | same |
| Android | Every screen, themes, dark mode, font scale 1.3/2.0, landscape, Italian, offline sync, a forced conflict, process death, revoke, quick capture | `2026.10.20.4-debug` from `42c642e`, throwaway API 37 emulator |
| Code audit | Token parity web vs Android, component and copy consistency, feature parity, accessibility in code, slot budgets, draft/autosave races | `42c642e` |

The lead verified every P0 and the top P1s against code or screenshots. Historical
approved documents 01–05 are unchanged. Finding IDs: `WV` web visual, `WW` web
workflow, `AN` Android, `CA` code audit. Severity: **P0** broken or unusable, **P1**
a feature fails or misleads in a realistic case, a clearly visible defect or a real
accessibility barrier, **P2** polish or friction a careful user notices, **P3** nit.

## Resolution

The fixes landed the same day. Shared foundations came first: the web tokens,
toast and confirm dialog, and the Android type scale, back stack and snackbar.
Then five lanes worked in parallel, each with its own server and emulator: web
editing, web shell, web settings, Android core and Android shell. The merged
result was tested end to end in Chromium and WebKit, and an audit checked every
row of the index below against the code. The gaps it found were then closed.
Last, an independent review of the whole branch found four more problems, and
the final emulator run found three. All seven are the last rows of the first
table below, and were fixed before merge.

Both P0s and every P1 are fixed. Every row in the index is resolved, except the
deliberate differences and later work listed below. The owner's decisions are
applied:

- **The first-run guide stays.** On the web, Escape skips it and its buttons
  hold still. On Android, it has one progress line and a lighter example.
- **The plain-HTTP band stays.** It is one short line on mobile and is also
  shown on sign-in. On Android it sits in the single status slot.
- **Android Settings is one sectioned page**, as A9 describes.
- **Italian (AN-45, CA-21) comes later.**

### Found while fixing

| ID | Platform | What happened | Fix |
|---|---|---|---|
| AN-46 | Android, server | On the Status screen, sync kept triggering itself about 7 times a second while idle (owner report). Turning a plugin on or off spends a change number without writing a row. The pull cursor stopped at the last row, so the change feed always answered "changed" | On the last page, the server moves the cursor to its latest change number, and the phone won't repeat a sync that can't move its cursor. Now one plugin toggle causes one sync |
| | Android | A lost default network still counted as online, so "Offline" never showed | Offline follows the default network |
| | Android | A change the server refused stayed in "N notes waiting to sync" forever, and Sync did nothing | Refused changes leave the count. The status slot says "1 note couldn't sync · Review", and the note offers "Keep as new note" |
| | Android | Text on sunken surfaces turned danger red, because the error container used the sunken colour | The error container has its own faint tint |
| AN-26 | Android, server | Signing out left the phone's credential active | `POST /auth/logout` with a device's bearer token revokes that credential and no other, as the hosted server already does. Offline sign-out stays local |
| WW-23 | Server, Android | The daily slip came back after its note was archived | The slip skips archived and trashed notes; Undo brings it back |
| WW-15 | Android | Label counts included archived notes | Counts match the label views, as on the web |
| | Server | Search ties ranked an older duplicate first | Newer notes win ties |
| | Web | The offline count ignored queued captures, and sign-out could erase them without warning | One count of waiting and unsent notes feeds both |
| WW-10 | Web | Back from a note lost the scroll position. WebKit scrolled to the composer, and Chromium drifted when "Show more" buttons appeared | The position holds exactly, and focus returns to the opened card |
| | Web | Settings tabs overlapped on phones; plugin form choices rewrote note titles; the daily slip printed raw Markdown; WebKit dialogs lost focus on close; the palette highlight jumped while results arrived | Each fixed with a test |
| | Android | The APK budget check counted dead space left by incremental packaging (a false 19 MB) | It sums the packaged entries |
| | Server | The newer-wins tie-break made every hosted (PostgreSQL) keyword search fail | The PostgreSQL store translates the ranking score, and its suite covers ranked search |
| | Web | On a hosted server, an expired sign-in cookie showed "Can't reach the server", and Try again could not clear it | Only failures worth retrying count as unreachable; a refusal leads to sign-in |
| | Web | Enter with the caret inside a list marker started a new item | The line splits as usual |
| | Android | A decision an older build saw refused stayed queued, so an upgraded phone showed its note as refused | The first sync drops those rows and refetches the note |
| | Android | Opening a note after a web edit synced in showed the phone's older text, and Back saved it over the web edit. Every editor write kept a draft that saving never removed, and the editor always preferred it. This was older than this review | Saving clears the draft. A draft whose text the note has since replaced (it is in the phone's history) is ignored by the editor and by export; unsaved words still win |
| | Android | Search offered a chip for a label only archived notes carry, and it found nothing | Chips count the notes search can find |
| | Android | The guide's last step said notes stay on the phone until you connect, even when it was connected | It says the phone is connected |

### Deliberate differences

- **WV-29:** times and dates follow the browser's locale, so a US English
  browser shows "8:06 AM". The spec's examples are 24-hour.
- **WV-17:** cards and menus keep `--rule` edges, which are decorative. Every
  control edge uses `--field`, at 3:1 or more.
- **WV-23:** the search date inputs stay native, styled as fields.
- **Fluid sizes:** the guide headline and the wordmark keep fluid `clamp()`
  sizes, because the type scale has no fluid step.
- **AN-36:** the theme shared by every device is set in the web app; the
  phone says so.
- **Slip date:** it reads "19 March 2025", as in 05 and the timeline, where 03
  shows "March 2025".

### Later

- **Plugin manifests:** let plugins name their choices, and add a field for the
  "save as note" role, so the client stops matching a command title.
- **Display name:** the owner display name has no use in single-owner local
  mode.
- **Android sync edge cases:**
  - an original that returns 404 on refetch leaves a stale copy;
  - a base version ahead of the server (after a restore) is refused with 409
    forever;
  - "Keep as new note" drops attachments;
  - the phone could warn before saving a note over the server's
    100,000-character limit.
- **Editing during a sync:** two cases save the phone's text over a newer web
  edit, which then survives only in server History:
  - a web edit syncs in while the note is open in the phone's editor (read
    from the code, not reproduced);
  - the app is killed with unsaved words, and the note is edited on the web
    before the phone reopens it (reproduced; same as before this review).

  The editor should send the version it started from, so the server keeps a
  conflict copy.
- **Small polish:** `#` autocomplete could use the recent-use order from
  search; dialogs could remember their opener at document level; a folded
  checklist fades its last hidden row to nothing, leaving a blank band above
  "Show more".

### Verification

- **Web:**
  - typecheck, 287 unit tests, format check and build all pass (initial JS
    135,034 / 150,000 bytes gzip; fonts 319,488 / 320,000);
  - Playwright fixture suites pass 78 of 78 in Chromium and WebKit, and the
    live-server suites 8 of 8 in each;
  - an end-to-end smoke covered every journey above in both engines, all five
    themes in light and dark, at 1440 and 390 px.
- **Server:** the full pytest suite and ruff pass, and the PostgreSQL 18
  acceptance suite passes against a local container set up as in CI.
- **Android:**
  - build, unit tests in every module and the APK budget pass (about 13.7 of
    15 MB);
  - the instrumented suite passes on a throwaway API 37 emulator: 92 passed,
    none failed, and 10 skipped that run only with opt-in arguments;
  - the editor fix was checked on the emulator against a local server: an
    upgrade from the previous build with a leftover draft, repeated web-edit
    rounds, unsaved words after a forced stop with the phone's clock 10
    minutes behind, and export;
  - every changed flow was checked by hand on the emulator, including dark
    mode, font scale 2.0 and landscape.
- **Not verified:**
  - real phones: iPhone Safari, and the owner's Android 16 phone;
  - Firefox;
  - AI-on states, since no provider was configured;
  - TalkBack reading a folded note.

## Verdict

The foundations are good. Colour is generated from one source and every text pair
passes WCAG AA in all ten theme and mode combinations. Capture is fast: on the web a
note lands in about 135 ms, and on the phone no word was lost through offline edits,
a real conflict, `kill -9`, a device revoke or sign-out. The serif/sans voice, the
sign-in page and the plugin permission dialog are calm and honest.

The surface does not yet keep the "never clutter the space" promise, and the editing
loop around capture leaks trust:

- On the web, a note card's checklist can lose a tick and spawn a conflict copy, the
  caret lands before the title when you click to edit, and a quick exit leaves a
  stale draft that later replays as "Unsaved changes".
- On both clients, conflicts are kept but effectively invisible, there is no Undo for
  archive or trash, and the list forgets where you were.
- On Android, the keyboard pops up every time you come back to the timeline, quick
  capture drops you inside Spunto instead of back where you were, and most interface
  text is set in the reading serif or Roboto rather than Inter.
- Both clients stack status lines, permanent action rows and admin-style settings
  pages where the design asks for silence.

Counts after merging duplicates across reviewers: **2 P0, about 30 P1, about 80 P2,
about 40 P3** (173 raw findings).

## Fix first

Ranked by user harm per unit of work. Each is a small, local change unless noted.

| # | Sev | Platform | What happens | Cause | Fix |
|---|---|---|---|---|---|
| 1 | P0 | Web | With about 15+ labels, the rail footer ("Settings", "Synced") is painted over the label list; clicking `#longform` opens Settings (WV-01) | `.label-tree{min-height:60px}` never scrolls, `web/src/styles/global.css:277` | `.label-tree{flex:1 1 auto;min-height:0;overflow-y:auto}` and `.rail>:not(.label-tree){flex-shrink:0}` |
| 2 | P0 | Web | Opening a note offline, or in a tab that outlived a deploy, blanks the whole app with no message or retry (WW-01) | Routes are `React.lazy` (`web/src/App.tsx:34-46`) with no error boundary anywhere | Error boundary around the routed area with "Try again", one automatic reload on a chunk 404, prefetch the Note chunk after first paint |
| 3 | P1 | Web | Leave an edit within 5 s: the save succeeds, but the draft stays; reopening shows "Unsaved changes", writes an extra revision and can create a false conflict copy (WW-03) | Cleanup calls `controller.deactivate()` right after `leave()` (`web/src/lib/editor.ts:116-118`), so `drafts.ts:316` returns before `removeItem` | Always clear the stored draft once the saved body matches it; use `allowed` only to gate UI updates |
| 4 | P1 | Web | Two quick ticks on a card's checklist lose one tick and create a conflict copy; a tick shows nothing for the length of the request (WW-04) | `NoteCard.tsx:95-103` builds each request from the render-time `body` and `version` | Optimistic update plus a per-note queue so each tick uses the latest body and version |
| 5 | P1 | Web | Click into a note to edit and type: text lands before the title; the checklist button then corrupts the first line (WW-02, Chromium and WebKit) | Caret state (`Editor.tsx:55`) is never applied, `autoFocus` puts it at 0, checklist insert uses `body.endsWith` (`Editor.tsx:171`) | Set the selection at the end (or the clicked point) before focusing; toolbar inserts at the selection |
| 6 | P1 | Both | Conflicts are kept but not surfaced. Android: the open note silently switches to the server text and your edit survives only as a "Conflicted copy" findable by search (AN-24). Web: the conflict screen has no compare and its only exit overwrites the other edit (WW-08) | `SyncEngine.kt:133` treats `conflict` like `applied`; `Note.tsx:79-157` | The designed "Edited on two devices. Both versions are kept · Compare" line with a warn dot, and a compare view with keep mine / keep saved / keep both (03 §8) |
| 7 | P1 | Both | No Undo anywhere: archive and trash act instantly with no confirmation, and focus falls to `<body>` on the web (WW-06, WV-03, AN-16, CA-29) | No toast/snackbar layer on either client | One `role="status"` toast on web and a Snackbar on Android carrying "Archived · Undo", "Moved to trash · Undo"; move focus to the next card |
| 8 | P1 | Web | Offline is never shown: the rail keeps saying "Synced" while captures wait as local drafts (WW-07, CA-43) | Status and banner derive only from the cached `meta` query (`App.tsx:202-208`, `451-455`) | One sync state from online/offline events, change-feed failures and pending drafts: "Offline · 2 notes waiting", "Saving", "Synced" |
| 9 | P1 | Android | The keyboard opens on every return to the timeline: Back from a note, Settings or Search, after the mic dialog, after any theme or font change (AN-06) | `Composer.kt:147` focuses and shows the keyboard in a `LaunchedEffect` that reruns on every composition entry | Autofocus only on a cold launcher start or "New note", guarded by a `rememberSaveable` flag |
| 10 | P1 | Both | The list forgets your place: Android after opening a note or rotating (AN-07); web after returning from a note, and Back/Escape always go to "/" even from a label, Archive or Search (WW-10, AN-20) | Android builds a new Pager per composition with no `cachedIn` (`Screens.kt:186`) and no saved list state; web navigates to "/" (`Note.tsx:49`, `App.tsx:354`) | Android: pager in the ViewModel with `cachedIn` and a `LazyListState` per route. Web: history back when the previous page is in-app, scroll restore per view |
| 11 | P1 | Android | Quick capture from the tile, widget or shortcut returns you into Spunto instead of the app you were in (AN-27) | `QuickCapture.kt:23` adds `FLAG_ACTIVITY_NEW_TASK` and `CaptureActivity` keeps the app's task affinity | `taskAffinity=""`, `excludeFromRecents="true"` on `CaptureActivity`, and `finishAndRemoveTask()` on Save |
| 12 | P1 | Both | Error copy misleads. Android: a first wrong password says the device was revoked and shows "Request failed · wrong_password", which then follows you onto the timeline (AN-01, AN-02). Web: AI and plugin failures and a 403 show generic or wrong text (CA-17, WW-16) | `SpuntoViewModel.kt:113` sets `reauthenticate` on the login call's own 401 and never clears `message`; web `problem()` drops the server's safe `detail` (`errors.ts:42-56`); `Note.tsx:190-198` maps every error to "Can't reach the server" | Use the server `detail` when present on both clients and delete the client-side AI tables; don't reauthenticate from login; clear the message on success |
| 13 | P1 | Web | No Sign out. The only way is Settings → Devices → Revoke this browser, which also leaves drafts in storage (WW-05) | The logout mutation (`web/src/lib/query.ts:424-466`) has no caller | A Sign out item using the existing mutation |
| 14 | P1 | Android | Save and "# Label" sit under the keyboard in the editor and in a long composer (AN-14) | The action bar is inside the scrolling content (`Composer.kt:233-303`) | Dock the bar in a bottom slot with `ime` plus `navigationBars` insets (03 A5) |

## Themes

### 1. Calm: remove what doesn't earn its place

- **Long notes are not clamped.** One note is 1,401 px tall on desktop and 2,547 px on
  mobile; Android silently cuts at 1,200 characters (WV-04, WW-24, CA-24). Clamp at 8
  lines with "Show more" on both.
- **Permanent action rows.** Web mobile shows Edit, Pin, Archive and Trash under every
  entry (WV-05, CA-39); Android shows an accent "Open" on every entry although the
  entry is already tappable (AN-10). Use one `more` button on web mobile (the glyph
  exists in `Icon.tsx`, unused) and long-press/swipe on Android (AN-22, both promised
  in 03:281).
- **Status lines stack.** Android stacks the plain-HTTP line, "Offline" and "1 notes
  waiting to sync", with different left edges, pushing the composer down about 120 dp;
  the sync line flashes after every edit (AN-09, CA-38). Use one status slot, empty
  when all is well, with the sync notice only after a real delay. The persistent
  plain-HTTP band itself is by design (D33, 03 W11); on HTTPS it never shows.
- **Note screens carry three bands of chrome.** Android: a "Back" word, a "Note"
  title, the HTTP line and a tinted header band with Edit and ⋮; editing opens a boxed
  composer (AN-15). Web note page shows five buttons including "Delete forever"
  (CA-39). Design A5: back arrow, pin and ⋮; tap the text to edit.
- **Entries sit 95–140 px apart instead of 20** because hidden actions keep their
  layout space (WV-11, CA-06). "Pinned" appears twice on web (WV-12).
- **Settings reads like an admin console.** Web Settings is squeezed into a 429 px
  column at 1440 px because its grid sits inside the reading column (WV-06); Devices
  shows raw user-agent strings and a "Re/vo/ke" button wrapped by syllable (WV-07,
  WW-27); generated plugin forms show raw keys like `min_notes` (WV-08); the plugin
  list is 7,366 px tall, repeats "no model is configured" about 12 times and seven
  descriptions end in the placeholder "… through guarded owner actions." (WV-24,
  WW-19); with AI off the AI page is a wall of disabled controls where 05 asks for
  "AI is off." and one line (WV-09, WW-17). Android Settings is a page of links to
  pages with no summaries, duplicate titles and an "Ai" title (AN-33, AN-34, CA-20).

### 2. Motion and feedback

There is no motion on either client (WV-03, CA-10): archive, pin, trash, the drawer
and the palette change instantly, and a new note on Android lands off screen with no
"Saved" (AN-12). Add the toast/snackbar from fix 7, 120–240 ms fades (respecting
reduced motion), and optimistic inserts for new notes and ticks (WW-13).

### 3. Design system: share more than colour

Colour is generated once for web, server and Android; type, spacing, radius,
elevation and motion are copied by hand, and that is where the drift lives.

- **Android typography (P1).** `readingTypography` defines 4 of 15 styles
  (`core/design/Theme.kt:156`) and sets `bodyLarge` to the reading face, so unstyled
  text, fields and sheet rows render in Literata, and about 105 other call sites fall
  back to Roboto (CA-01, AN-40). Build the full §11.2 type scale with Inter for UI and
  a separate reading style.
- **Dark mode depth is inverted on web (P1).** `--e2`, `--e3` and `--scrim` use
  `rgb(var(--sh))`, and `sh` is the ink colour, light in dark mode, so dialogs glow
  and the scrim lightens the page (CA-02, `global.css:44-46`). Emit `sh` as black for
  dark modes in the generator. Modal shadow and backdrop are also hard-coded
  (`#0003`, `#0005`) (WV-39, CA-12).
- **Android dark launch flashes white for about 1.3 s** (AN-39): `styles.xml` uses a
  Light parent with no night variant. The `#F8F5F0` bar colours there have no effect
  at targetSdk 35 and can go; bar icons already follow the theme (`Theme.kt:146-150`).
- **Control edges are too faint.** Composer and search use `--rule-strong`
  (1.51–2.94:1) and cards, segments and the modal use `--rule` (1.16–1.59:1) as their
  only edge, under WCAG 1.4.11's 3:1 and against 03's own rule (WV-17). `--field`
  measures 3.31–5.01:1 in every theme.
- **No type, spacing or radius tokens on web** (WV-18, WV-19, CA-04, CA-11): 23 font
  sizes, off-scale 10/14 px spacing, radii 3/5/6/10/15/16/18. `tokens.json` already
  holds the scale but the generator only reads colours, and CI doesn't check
  `Tokens.kt`, `theme-catalog.ts` or `theme-previews.css` for drift (CA-05).
- **Component drift.** `Feature.module.css` redefines `.muted`, `.field`, `.notice`
  and the overline with different values than `global.css` (CA-13); three
  confirmation patterns including native `window.confirm` for the most destructive
  actions (CA-14, WV-20); browser-blue checkboxes outside notes (WV-16); task lists
  render a disc bullet next to a 13 px native checkbox (WV-13); focus rings double up
  on the autofocused composer and draw square on rounded fields (WV-15).

### 4. Accessibility

- Web cards are `role="link"` divs whose `aria-label` replaces the note, wrapping
  checkboxes and links (CA-31, WW-25). Use a real link on the time.
- Android checklist checkboxes have no accessible name and only the box toggles
  (AN-42); entries announce only "Note, time, title" with no labels, pin, progress or
  conflict (CA-33, AN-43); message, offline and HTTP lines have no live region
  (CA-32).
- Web mobile tap targets under 44 px: back links 17 px tall, task checkboxes 13 px,
  switches 38×22, "Revision history" 21 px (WV-10).
- Every web page title is "Spunto" (WW-25); label autocomplete lacks combobox wiring
  (CA-34, WW-21); focus is lost after pin, archive, a wrong password and Escape in
  the pairing dialog (WW-25).

### 5. Organize and find

- Android inline `#labels` render as plain ink and do nothing when tapped
  (`core/design/Markdown.kt:112`, AN-08); 03:270 asks for accent and tap-to-filter.
- Web label counts include archived notes while label views don't, so `#archive 1`
  opens "No notes with this label yet." (WW-15, `server/src/spunto/core/notes.py:1217`).
- Renaming a label onto an existing one merges silently with no undo (WW-14).
- The web palette has no arrow-key selection and Enter always opens Search; it shows
  an `e` shortcut that does nothing; j/k and the other 03 keys are missing (WW-11,
  CA-28). The slash menu ignores Enter (WW-22).
- Search: a punctuation-only query returns every note, no match highlighting, filters
  not in the URL (WW-28, WV-23). Android: field not focused on open, results without
  snippets, an active date filter shown only as a dot (AN-20, AN-21).

### 6. Copy

Raw codes and internals leak: "Request failed · wrong_password", an OkHttp exception
for a URL without a scheme (AN-03), "plugin kv · 0" (AN-37), status pages of raw
values (WV-44, WW-19), "Make public to configured tools" (CA-18). Plurals: "1 notes"
on both clients. "⌘ K" on every OS (WW-34, CA-22). Times as "08:06 AM" with US dates
(WV-29). Wording drifts from the spec table: "Conflict copy", "via agent", "Written
by" (CA-18). Three different empty-state styles and home copy that differs from the
spec's "A quiet place for loose ideas." (WV-45, CA-19). Android "Check AI
connection" tells the owner that the owner manages AI because the endpoint needs a
browser session (AN-35).

## Decisions for the owner

These findings touch approved design or product choices rather than defects.

1. **First-run guide.** Both reviewers found the 4-step guide slows the first note
   (AN-05, WW-33). Keeping it costs four taps once; replacing it with one empty-state
   card gets the owner typing at once and "Show guide" stays in Settings.
   Recommendation: make the guide skippable with Escape and drop the per-step
   example cards; collapse it only if you agree the design should change.
2. **Plain-HTTP band.** By design it persists (D33). The reviewers suggest moving it
   to sign-in only (WV-27, AN-09). Recommendation: keep it, but make it one short line
   on mobile and show it on the sign-in page too, where the password is typed.
3. **Android Settings shape.** A9 describes one sectioned page; the app has a page of
   links. This is drift, not a decision, unless you prefer the current shape.

## Full findings index

Merged duplicates share a row. Paths are relative to the client source root unless
given in full.

### Web

| ID | Sev | Finding | Where |
|---|---|---|---|
| WV-01 | P0 | Rail footer over label list, steals clicks | `styles/global.css:277` |
| WW-01 | P0 | Failed lazy chunk blanks the app | `App.tsx:34-46` |
| WW-02 | P1 | Caret at 0 on click-to-edit; checklist insert at end | `Editor.tsx:55,112-118,171` |
| WW-03 | P1 | Stale draft replays after quick exit | `lib/editor.ts:116`, `lib/drafts.ts:316` |
| WW-04 | P1 | Double tick loses one, creates conflict copy | `NoteCard.tsx:95-103` |
| WW-05 | P1 | No Sign out | `lib/query.ts:424` |
| WW-06, WV-03 | P1 | No toast, no Undo, no motion; focus lost after archive | `NoteCard.tsx:115-171`, `Settings.tsx:474` |
| WW-07 | P1 | Offline shows "Synced" | `App.tsx:202-208,451-455` |
| WW-08 | P1 | Conflict screen: no compare, only exit overwrites | `pages/Note.tsx:79-157` |
| WV-02 | P1 | Server unreachable on cold load: bare wordmark forever | `App.tsx:388-395` |
| WV-04, WW-24 | P1 | Long notes not clamped | `NoteCard.tsx` |
| WV-05 | P1 | Mobile action row under every entry | `global.css:924` |
| WV-06 | P1 | Settings squeezed to 429 px | `Feature.module.css:1-3` |
| WV-07, WW-27 | P1 | Devices: wrapped Revoke, raw UA names, "Last used never" | `Feature.module.css:42-48`, `Settings.tsx:987-1069` |
| WV-08 | P1 | Plugin forms show raw keys and lowercase enums | `server/src/spunto/plugins/*/plugin.toml` |
| WV-09, WW-17 | P1 | AI off: wall of disabled controls; contradicting guidance | `features/AISettings.tsx` |
| WV-10 | P1 | Mobile tap targets under 44 px | `global.css` 599 px block |
| CA-02 | P1 | Dark shadows and scrim use light ink | `global.css:44-46`, `server/src/spunto/host/themes.py:106` |
| CA-17, WW-16 | P1 | Server `detail` dropped; wrong error copy | `lib/errors.ts:42-56`, `pages/Note.tsx:190-198` |
| CA-31 | P1 | `role="link"` card wraps interactive body | `NoteCard.tsx:79-104` |
| WW-09 | P2 | Settings and plugin links reload the page | `Settings.tsx:76-87`, `Slots.tsx:345-412` |
| WW-10 | P2 | Scroll reset; Back/Escape always to "/" | `pages/Note.tsx:49`, `App.tsx:354` |
| WW-11, CA-28 | P2 | Palette lacks arrow keys; dead `e`; missing keys | `Palette.tsx:108-113,157`, `App.tsx:322-387` |
| WW-12 | P2 | Ctrl/Cmd+S exits edit mode | `Editor.tsx:131-136` |
| WW-13 | P2 | Typing during capture save appends to saved note | `lib/drafts.ts:317-356` |
| WW-14 | P2 | Label rename merges silently, no undo, generic errors | `pages/Timeline.tsx:84-177` |
| WW-15 | P2 | Label counts include archived notes | `server/src/spunto/core/notes.py:1217-1224` |
| WW-18 | P2 | Plugin Undo notice off screen; Settings link on off plugins | `Settings.tsx:474-489` |
| WW-19, WV-24 | P2 | Placeholder plugin descriptions, raw labels, long plugin page | `Settings.tsx:207`, `plugin.toml` |
| WW-20 | P2 | Markdown import: about 9 steps, 2 reloads | Settings → Data |
| WW-21 | P2 | "#" without leading space; Enter doesn't continue lists | `Editor.tsx:163,224-239` |
| WW-22 | P2 | Slash menu ignores Enter; raw markdown results | `Slots.tsx:685-780` |
| WW-23 | P2 | Daily slip stays after Archive | `Slots.tsx:376-379` |
| WW-25 | P2 | Page title always "Spunto"; focus loss; unnamed actions | `NoteCard.tsx`, `Editor.tsx:185-204` |
| WW-26 | P2 | Back-off message without duration; focus lost | `pages/Auth.tsx` |
| WV-11, CA-06 | P2 | Entries 95–140 px apart; metrics off spec | `global.css:429-432,534,970` |
| WV-12 | P2 | "Pinned" shown twice | `NoteCard.tsx:108,196` |
| WV-13 | P2 | Task lists: bullet plus native checkbox | `global.css:531` |
| WV-14 | P2 | Reading column 624 px vs 36rem | `global.css:301` |
| WV-15 | P2 | Focus rings double or square | `global.css:362-365,702` |
| WV-16 | P2 | Browser-blue checkboxes | `global.css:531` |
| WV-17 | P2 | Control edges below 3:1 | `global.css`, `Feature.module.css` |
| WV-18, WV-19, CA-04, CA-11 | P2 | No type/spacing/radius tokens; literal drift | `tokens.json`, `scripts/generate-tokens.mjs` |
| WV-20, CA-14 | P2 | Native `<select>` and `window.confirm` | `pages/Timeline.tsx:84,289`, `pages/Note.tsx:242,283` |
| WV-21 | P2 | Note view shifts 144 px for an empty panel | `global.css:833-836` |
| WV-22 | P2 | Edit action 14 px, others 12 px | `global.css:557` |
| WV-23 | P2 | No search highlighting; native date inputs | `pages/Search.tsx` |
| WV-25 | P2 | "Show guide" on every Settings page | `Settings.tsx:91` |
| WV-26 | P2 | Guide "Next" moves between steps | `FirstRunGuide.module.css` |
| WV-27 | P2 | HTTP band missing on sign-in, two lines on mobile | `App.tsx:456-461` |
| WV-28 | P2 | Theme card name is its whole preview | `features/AppearancePanel.tsx` |
| CA-13 | P2 | Module CSS redefines global primitives | `Feature.module.css:72,79,155,349,438,642` |
| CA-18 | P2 | Wording drifts from spec | `NoteCard.tsx:110-111`, `pages/Note.tsx:59-61,220` |
| CA-34 | P2 | Autocomplete lacks combobox ARIA | `Editor.tsx:225-230` |
| CA-40, CA-41 | P2 | Three "active plugin" predicates; hard-coded plugin IDs | `Slots.tsx:15-44,484,515` |
| CA-43 | P2 | Rail "Synced" ignores unsent drafts | `App.tsx:202-208` |
| WW-28 | P3 | Punctuation query matches all; long query error | `server/src/spunto/core/text.py:253-270` |
| WW-29 | P3 | Label-view composer garbles fast typing | `pages/Timeline.tsx:182-189` |
| WW-30 | P3 | History offers restoring the current version; no diff | `pages/Note.tsx:264-301` |
| WW-31 | P3 | Trashed notes editable; "1 notes"; unexplained archive | `pages/Timeline.tsx:269-309` |
| WW-32, WV-36 | P3 | Spaces and `/shared` error in single-owner mode | `Settings.tsx`, `pages/SharedNotes.tsx` |
| WW-33 | P3 | Guide blocks first capture; Escape doesn't skip | `FirstRunGuide.tsx` |
| WW-34, CA-22, WV-31 | P3 | "⌘" on every platform and on touch | `App.tsx:108,564`, `Editor.tsx:209` |
| WW-35 | P3 | Enter doesn't submit token dialog; raw import bytes | `Settings.tsx` |
| WW-36 | P3 | No proper not-found page | `pages/Timeline.tsx:106` |
| WV-29 | P3 | "08:06 AM" and US dates; gutter off grid | `NoteCard.tsx:74,185` |
| WV-30 | P3 | Hover sticks on touch | `global.css:131` |
| WV-32, WV-42 | P3 | Help is `<br>` text; empty "ACTIONS" header | `Palette.tsx:215` |
| WV-33 | P3 | Sign-in error shifts layout, no invalid state | `pages/Auth.tsx` |
| WV-34 | P3 | "never saved" caption reads like data loss | `FirstRunGuide.tsx` |
| WV-35 | P3 | Review "0 waiting" off baseline | `features/Review.tsx` |
| WV-37, WV-45, CA-19 | P3 | Plurals; three empty-state styles; home copy off spec | `pages/Timeline.tsx:229`, `features/Review.tsx:122` |
| WV-38 | P3 | Preview "#ideas" in sans; 10 px pill on mobile | `Feature.module.css:692` |
| WV-39, CA-12 | P3 | Hard-coded modal shadow/scrim; dead danger fallbacks | `Feature.module.css:402-433,617` |
| WV-40 | P3 | Unused back/more/lock/pin/edit glyphs | `components/core/Icon.tsx` |
| WV-41 | P3 | "Saved" before any edit; autocomplete opens on entry | `components/core/Editor.tsx` |
| WV-43 | P3 | Two active-item styles | `global.css`, `Feature.module.css` |
| WV-44 | P3 | Status page raw values | `features/Settings.tsx` |
| WV-46 | P3 | No print stylesheet | `global.css` |
| CA-08, CA-15, CA-16 | P3 | Focus offset 3–4 px; Literata 500; switch colour differs | `global.css:98`, `Feature.module.css:63,143` |
| CA-35, CA-36 | P3 | Pill provenance only in tooltip; `.sr-only` incomplete | `Slots.tsx:139`, `global.css:185` |
| CA-44 | P3 | Dead alias, unused vars and font | `Slots.tsx:413`, `global.css:42-43` |

### Android

Paths are relative to `android/app/src/main/java/io/theficos/spunto/` unless given.

| ID | Sev | Finding | Where |
|---|---|---|---|
| AN-01, AN-02 | P1 | Wrong password says "revoked", raw code, error persists | `SpuntoViewModel.kt:113,122-133` |
| AN-06 | P1 | Keyboard pops up on every return | `Composer.kt:147` |
| AN-07 | P1 | Timeline loses scroll on note open and rotation | `Screens.kt:186,191` |
| AN-08 | P1 | Inline #labels plain and inert | `core/design/.../Markdown.kt:112` |
| AN-14 | P1 | Action bar not docked to keyboard | `Composer.kt:233-303` |
| AN-24 | P1 | Conflicts kept but not announced | `data/sync/.../SyncEngine.kt:133`, `Screens.kt:214` |
| AN-27 | P1 | Quick capture returns into Spunto | `plugins/quick-capture/.../QuickCapture.kt:23`, `AndroidManifest.xml` |
| AN-39 | P1 | White window for about 1.3 s in dark mode | `res/values/styles.xml` |
| AN-42 | P1 | Checklist checkboxes unnamed; text doesn't toggle | `core/design/.../Markdown.kt` |
| CA-01, AN-40 | P1 | UI text in Literata or Roboto instead of Inter | `core/design/.../Theme.kt:156-158` |
| CA-32 | P1 | Status lines without live region | `Screens.kt:136-139` |
| CA-37 | P1 | Note panel shows loading and "Nothing to show yet." | `PluginUI.kt:93,115-122` |
| AN-03 | P2 | URL without scheme shows OkHttp exception | `SpuntoViewModel.kt:113` |
| AN-04 | P2 | Sign-in shows every field; Next instead of Go; model-code name | `LoginScreen.kt:63-65` |
| AN-05 | P2 | Four-step guide with progress bar and example cards | `FirstRunGuide.kt` |
| AN-09, CA-38 | P2 | Status lines stack and flash; "1 notes" | `Screens.kt:136-138,176-181` |
| AN-10 | P2 | "Open" button on every entry | `Screens.kt:209` |
| AN-11, CA-25 | P2 | Text-link toolbar; no checkbox/image tools; "#" appended at end | `Composer.kt:233-303,237,245` |
| AN-12, CA-23 | P2 | No day headers; no "Saved" feedback | `Screens.kt:191-196` |
| AN-13, CA-42 | P2 | Empty state and "Note unavailable." flash while loading | `Screens.kt:195,240` |
| AN-15 | P2 | Three bands of chrome on the note screen | `Screens.kt:123,225-260` |
| AN-16 | P2 | Actions sheet of serif links; no Copy/Share; no Undo | `Screens.kt:282,297` |
| AN-17 | P2 | History shows full bodies and raw kinds | `Screens.kt:298-305` |
| AN-19, CA-46 | P2 | Busy labels sheet; two label browsers | `LabelsSheet.kt:23`, `Screens.kt:376` |
| AN-20 | P2 | Search: extra taps, no snippets, Back goes home | `Screens.kt:86-89,351-375` |
| AN-21 | P2 | Active date filter is a dot; 3 taps to clear | `Screens.kt:127` |
| AN-22 | P2 | No long-press or swipe | timeline entries |
| AN-23 | P2 | Trash without retention line or inline Restore | trash view |
| AN-25 | P2 | "Offline" shown when signed out | `Screens.kt:137` |
| AN-26 | P2 | Sign out leaves the credential active on the server | `SpuntoViewModel.kt:135` |
| AN-28 | P2 | Share sheet differs from A3 | `CaptureActivity.kt` |
| AN-29 | P2 | Widget unstyled (code only) | `QuickCapture.kt:37-53` |
| AN-31 | P2 | Mic permission states contradict | `plugins/dictation/.../DictationSession.kt:26,50` |
| AN-32 | P2 | Stale download message after language switch | `Composer.kt:298` |
| AN-33, CA-20 | P2 | Settings as pages of links; duplicate titles | `SettingsScreens.kt`, `OwnerWebSettingsScreen.kt` |
| AN-34 | P2 | AI title reads "Ai" | `Screens.kt:122` |
| AN-35 | P2 | "Check AI connection" fails with a wrong message for the owner | `StatusScreen.kt:36,62` |
| AN-36 | P2 | Appearance: tall cards, silent override, no instance theme | `Screens.kt:396-454` |
| AN-37 | P2 | Plugin manager long; plugin page an AlertDialog with internals | `SettingsScreens.kt:72`, `NativePluginControls.kt:33` |
| AN-41 | P2 | Landscape: no reading measure, draws under cutout | `Screens.kt:120` |
| AN-43, CA-33 | P2 | Entries announce too little; sync glyph unannounced | `Screens.kt:199-223` |
| CA-07 | P2 | ink-3, highlight, warn unreachable | `core/design/.../Theme.kt:142` |
| CA-26, CA-27 | P2 | Suggestion line and daily slip differ from web | `PluginUI.kt:143-172`, `Screens.kt:194` |
| LD-01 | P2 | Markdown list bullets dropped: list items render as bare lines (lead observation, `12-timeline-scrolled.png`) | `core/design/.../Markdown.kt` |
| AN-18 | P3 | Autocomplete suggests the typed label; checked items not dimmed | `Composer.kt` |
| AN-30 | P3 | Static shortcut targets the release package | `res/xml/shortcuts.xml` |
| AN-38 | P3 | Export opens "Data"; "Not configured" in accent | settings |
| AN-44 | P3 | "Dismiss message" target 58×24 dp; "1 notes" | `Screens.kt` |
| AN-45, CA-21 | P3 | Italian locale mixes languages; strings not in resources | intended for later |
| CA-09 | P3 | Note surface has tonal elevation | `Screens.kt:260` |
| CA-45 | P3 | `Screens.kt` 479 lines / 45 KB, `Settings.tsx` 1,538 lines | split by screen |

### Shared

| ID | Sev | Finding | Where |
|---|---|---|---|
| CA-03 | P2 | `chrome` tokens unused; no web `theme-color` meta | `server/src/spunto/host/themes.py:107-110` |
| CA-05 | P2 | CI doesn't drift-check `Tokens.kt` and other generated files | `.github/workflows/server.yml:79` |
| CA-10 | P3 | No motion tokens on either client | 03 §11 |
| CA-29 | P2 | No Undo after archive or trash on either client | 03:147,281 |

## Contrast

All text pairs pass AA in carta, ardesia, salvia, corallo and elettrica, light and
dark; the tightest is corallo light accent on `accent-soft` at 4.58:1. Two non-text
pairs fail everywhere: `rule-strong` on paper as a control's only edge (1.51–2.94:1)
and `rule` on raised for cards, segments and the modal (1.16–1.59:1). `field`
passes at 3.31–5.01:1.

## Keep

1. One generated colour source with contrast checks on both clients; dark modes tuned,
   not inverted; Appearance previews match the applied theme.
2. Capture speed: composer focused on load, a web card in 135–265 ms, Android cold
   start to typing in one tap.
3. Self-healing saves: offline captures and edits sync on reconnect, a 500 is retried,
   non-overlapping edits merge, and nothing was lost through process death or revoke.
4. `drafts.ts`: synchronous persist, idempotent creates, revision-exact
   acknowledgement (fix 3 is one early return, not a design problem).
5. Literata for writing, Inter for interface, the wordmark with its accent full stop.
6. The plugin permission dialog on both clients, and AI plugins that leave no trace
   while waiting for a provider.
7. `Dialog.tsx` with `useModalFocus.ts`: native dialog, focus trap and restore.
8. Android re-auth copy ("Your notes and unsynced changes are still on this phone")
   and font scale 2.0 holding everywhere tested.

## Not verified

Firefox (headless launch failed in the sandbox); AI results, Review, Ask and
suggestions (no provider); Android dictation listening and recognition (no speech
pack), voice memos and the attachment player, a placed widget, share and selection
task behaviour from real apps, API 36 specifics and the owner's physical phone.
