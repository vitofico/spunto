import importlib.machinery
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(name, file):
    loader = importlib.machinery.SourceFileLoader(name, str(ROOT / "scripts" / file))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


check = load("check_translations", "check-translations.py")
starter = load("new_translation", "new-translation")


class TranslationsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.res = self.root / "android/app/src/main/res"
        self.write(
            self.res / "values/strings.xml",
            """<resources>
<string name="brand" translatable="false">Spunto</string>
<string name="greeting">Hello %1$s</string>
<plurals name="notes"><item quantity="one">%1$d note</item><item quantity="other">%1$d notes</item></plurals>
<string-array name="choices"><item>First</item><item>Second</item></string-array>
<color name="ink">#000000</color></resources>""",
        )
        self.web = self.root / "web/src/locales"
        self.write(
            self.web / "en.json",
            json.dumps(
                {
                    "nativeName": "English",
                    "direction": "ltr",
                    "messages": {
                        "hello": "Hello {name}",
                        "notes": {"one": "{count} note", "other": "{count} notes"},
                    },
                }
            ),
        )

    def write(self, file, text):
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text, encoding="utf-8")

    def test_repository_catalogs_are_complete(self):
        self.assertEqual(check.check_android(ROOT) + check.check_web(ROOT), [])

    def test_scaffold_discovers_modules_and_preserves_contracts_without_brands(self):
        second = self.root / "android/plugins/widget/src/main/res"
        self.write(
            second / "values/strings.xml",
            '<resources><string name="action">Write</string></resources>',
        )
        paths = starter.scaffold(self.root, "pt-BR", "Português (Brasil)")
        self.assertEqual(len(paths), 3)
        android = (self.res / "values-pt-rBR/strings.xml").read_text()
        self.assertNotIn('name="brand"', android)
        self.assertNotIn("<color", android)
        self.assertIn("English starter", android)
        self.assertIn("%1$s", android)
        self.assertEqual(
            check.check_android(self.root) + check.check_web(self.root), []
        )
        self.assertEqual(
            json.loads((self.web / "pt-BR.json").read_text())["nativeName"],
            "Português (Brasil)",
        )

    def test_scaffold_never_overwrites_or_partially_adds_an_existing_language(self):
        self.write(self.web / "fr.json", "existing content")
        with self.assertRaisesRegex(ValueError, "refusing to overwrite"):
            starter.scaffold(self.root, "fr")
        self.assertFalse((self.res / "values-fr").exists())
        self.assertEqual((self.web / "fr.json").read_text(), "existing content")
        for invalid in ("../de", "de/../../", "en", "de;touch", ""):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                starter.scaffold(self.root, invalid)

    def test_locale_names_and_single_client_scaffold(self):
        for source, expected in (
            ("pt-rBR", ("pt-rBR", "pt-BR")),
            ("b+zh+Hans", ("b+zh+Hans", "zh-Hans")),
            ("es-419", ("b+es+419", "es-419")),
        ):
            self.assertEqual(starter.locale_names(source), expected)
        starter.scaffold(self.root, "de", android=False)
        self.assertFalse((self.res / "values-de").exists())
        self.assertEqual(check.check_web(self.root), [])

    def test_android_missing_duplicate_type_array_and_placeholders_are_rejected(self):
        starter.scaffold(self.root, "fr", web=False)
        target = self.res / "values-fr/strings.xml"
        original = target.read_text()
        for before, after, expected in (
            ('name="greeting"', 'name="other_greeting"', "missing string/greeting"),
            ("Hello %1$s", "Bonjour %1$d", "format string/greeting"),
            ("<item>Second</item>", "", "array entries"),
            ('quantity="other"', 'quantity="one"', "missing other"),
            ("Hello %1$s", "", "blank string/greeting"),
            (
                "</resources>",
                '<string name="greeting">Encore %1$s</string></resources>',
                "duplicate string/greeting",
            ),
        ):
            with self.subTest(expected=expected):
                target.write_text(original.replace(before, after))
                self.assertTrue(
                    any(expected in issue for issue in check.check_android(self.root))
                )
        self.write(self.res / "values/strings.xml", "<resources>")
        self.assertTrue(
            any("strings.xml" in issue for issue in check.check_android(self.root))
        )

    def test_web_missing_duplicate_plural_and_placeholder_errors_are_rejected(self):
        starter.scaffold(self.root, "de", android=False)
        target = self.web / "de.json"
        original = json.loads(target.read_text())
        for value, expected in (
            ("Hallo", "placeholder contract"),
            ({"one": "{count} Notiz"}, "expected text or plural"),
            ("", "blank text"),
        ):
            data = json.loads(json.dumps(original))
            data["messages"]["hello"] = value
            target.write_text(json.dumps(data))
            self.assertTrue(
                any(expected in issue for issue in check.check_web(self.root))
            )
        target.write_text(
            '{"nativeName":"Deutsch","direction":"ltr","messages":{"hello":"a","hello":"b"}}'
        )
        self.assertTrue(
            any("duplicate JSON key" in issue for issue in check.check_web(self.root))
        )

    def test_numbered_arguments_can_reorder_but_not_change_types(self):
        self.assertEqual(check.arguments("%1$s: %2$d"), check.arguments("%2$d — %1$s"))
        self.assertNotEqual(check.arguments("%1$s"), check.arguments("%1$d"))
        self.assertEqual(check.arguments("100%% %n"), {})


if __name__ == "__main__":
    unittest.main()
