# Spunto web

The browser client uses React, TypeScript and Vite, with Node 26 or newer and the
pnpm version pinned in `package.json`. From this directory, run
`pnpm install --frozen-lockfile`, then `pnpm dev`. The Vite development server
proxies the API to `http://127.0.0.1:8080`.

Run `pnpm typecheck`, `pnpm test`, `pnpm format:check` and `pnpm build` before
submitting changes. `pnpm test:e2e` runs the Playwright browser suite; the focused
language smoke is `pnpm test:e2e e2e/language.spec.ts`.

## Notes and thinking tools

The web app requires a reachable server. Capture drafts stay in this browser until Save; it has no offline note replica. Existing note edits autosave, with overlapping changes preserved for conflict review.

The space owner can enable **Thinking desk** in Plugins. Open the dedicated page from navigation or the command palette. Choose **Trace an idea** for a timeline and directed connections, or **Combine notes** to write a new idea from two to five sources. The saved list opens the most recently edited desks first with their full titles and offers **New desk**.

Use **Save as private note** when you want a separate copy with your work and links to the originals. Changed or unavailable sources require review or removal before saving another copy. Desk revisions protect concurrent edits. See the [Thinking desk contract](../docs/decisions/2026-10-31-thinking-desk.md).

Search provides bounded excerpts around actual word matches and distinguishes them from similar-meaning results. Checklist results retain their saved state and existing toggle controls. Note sharing previews the recipient and selected read/edit capability before granting access, including linked attachments and future text edits. The grant excludes revision history, linked notes and the rest of the space; other grants or membership may also provide access, and revocation cannot erase recipient copies. [Hosted operation](../docs/hosted.md) explains collaboration and recovery.

For the shared development gate, run `scripts/check-ci web` from the repository root. Real-server Playwright cases additionally need a separately prepared disposable server through `SPUNTO_E2E_URL`; fixture tests alone do not establish packaged-server behavior.

## Languages

English is the source catalog at `src/locales/en.json`. To improve a translation,
edit the matching keys in the language's JSON file. To add a language, copy
English to a BCP 47 filename such as `pt.json`, translate every message and set
`nativeName` to the language's own name. Set `direction` to `rtl` when needed.
The picker and build discover files automatically; no code registry needs editing.
See [the translation guide](../docs/translating.md) for the shared contribution
workflow and license requirements.

Messages use stable dotted keys. Preserve every `{placeholder}`; translators may
move placeholders to suit the language. Plurals are objects with an `other` form
and any `zero`, `one`, `two`, `few`, `many` forms that the language needs. Use
`t("key", { count })` for a plural: `Intl.PluralRules` chooses the form and numbers
follow the selected UI language. Never form plurals by adding an English `s`.
The tests check catalog keys, types and placeholder contracts.

React components subscribe with `useLanguage()` and render `t("key", values)`.
Create translated arrays and objects during rendering or inside functions, so
runtime changes update their text. Keep note bodies, labels, generated plugin
output and third-party metadata unchanged. Bundled plugin metadata has explicit
translation keys derived from the plugin, command and schema field IDs.
Language preference belongs to this browser and is independent of note content.
The build extracts catalog names for the picker and loads other catalog bodies
on demand to keep the initial bundle inside its budget.
