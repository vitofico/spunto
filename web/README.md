# Spunto web

The browser client uses React, TypeScript and Vite. From this directory, run
`pnpm install --frozen-lockfile`, then `pnpm dev`. The Vite development server
proxies the API to `http://127.0.0.1:8080`.

Run `pnpm typecheck`, `pnpm test`, `pnpm format:check` and `pnpm build` before
submitting changes. `pnpm test:e2e` runs the Playwright browser suite; the focused
language smoke is `pnpm test:e2e e2e/language.spec.ts`.

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
