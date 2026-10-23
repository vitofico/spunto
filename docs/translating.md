# Translating Spunto

Spunto includes English, Spanish, Italian, French and German. Corrections and new
languages are welcome through [pull requests](../CONTRIBUTING.md). You can edit
the translation files on GitHub without installing Android Studio or Node.
The contributor agreement in the contribution guide also applies to translations.

## Choose a language

On Android 13 and later, Settings → Language opens Android's
per-app language picker. Older Android versions follow the device language.
The web client's language picker is available on the sign-in screen and in
Settings → Appearance. It initially follows the browser, then remembers your
choice in that browser. Unsupported languages fall back to English.

The interface language is separate from speech recognition and note content.
Changing it never translates saved notes, label names, operator-provided text or
custom plugins. Android supplies its own translations for system authentication
and permission dialogs.

## Correct existing text

- Android: edit the XML files in `android/app/src/main/res/values-<language>/`
  and `android/plugins/quick-capture/src/main/res/values-<language>/`.
- Web: edit `web/src/locales/<language>.json`. `nativeName` is the language's own
  name, `direction` is `ltr` or `rtl`, and `messages` contains the translations.
- English source text lives in Android's `values/` folders and the web's `en.json`.

Translate whole messages naturally. Keep resource names, JSON keys, placeholders
such as `%1$s`, `%2$d`, `{count}` and `{value1}`, and markup intact. Numbered Android
placeholders can be reordered. Escape XML characters such as `&` as `&amp;` and
Android apostrophes as `\'`. Keep meaningful leading/trailing spaces in web text
fragments. Keep Spunto, API names, code samples and literal command syntax intact.
Do not copy resources marked `translatable="false"` into translated files.

Plural entries need an `other` branch and the forms appropriate to the language;
retain the same placeholders in every form. String arrays keep their order and
number of entries. A translation must cover the complete English catalog for
each client it supports.

## Add a language

With Python 3 installed, run from the repository root:

```sh
python3 scripts/new-translation pt-BR --name 'Português (Brasil)'
```

This creates Android `values-pt-rBR` folders and `web/src/locales/pt-BR.json` from
English. **Every starter message still needs translation.** Existing languages
are never overwritten. Use `--android-only` or `--web-only` to contribute one
client. Android qualifiers such as `pt-rBR` and `b+zh+Hans` are also accepted.
For right-to-left web languages, set `direction` to `rtl`.

Alternatively, copy the translatable English resources and web catalog manually.
No language list or application-code edit is needed: Android and web discover
the files automatically. Android translations cover every resource module,
including the quick-capture widget.

## Check and contribute

```sh
python3 scripts/check-translations.py
python3 -m unittest discover -s scripts/tests -p 'test_translations.py'
```

These checks find missing or duplicate entries, blank messages, wrong resource
types and broken placeholders. They cannot assess translation quality. Review
the wording in context, including plural counts, long labels, narrow screens,
accessibility descriptions and right-to-left layouts where applicable. For code
changes, also run the client checks in [CONTRIBUTING.md](../CONTRIBUTING.md).
Include the language, affected screens and checks in the pull request.
