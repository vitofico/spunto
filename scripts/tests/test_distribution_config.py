import json
import re
import unittest
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]


class DistributionConfigTest(unittest.TestCase):
    def test_obtainium_selects_native_release_titles_and_only_signed_apks(self):
        config = json.loads((ROOT / "distribution/obtainium.json").read_text())
        settings = config["additionalSettings"]
        self.assertEqual(config["id"], "io.theficos.spunto")
        self.assertEqual(config["url"], "https://github.com/vitofico/spunto")
        self.assertIs(settings["releaseTitleAsVersion"], True)
        self.assertIs(settings["includePrereleases"], False)
        self.assertIs(settings["verifyLatestTag"], False)
        self.assertEqual(settings["sortMethodChoice"], "smartname")
        title = re.compile(settings["filterReleaseTitlesByRegEx"])
        apk = re.compile(settings["apkFilterRegEx"])
        self.assertIsNotNone(title.fullmatch("2026.10.15.6"))
        for excluded in ("2026.10.0", "Spunto Debug", "2026.10.15.6-debug"):
            self.assertIsNone(title.fullmatch(excluded))
        names = [
            "spunto-2026.10.15.6.apk",
            "spunto-2026.10.15.6.apk.sha256",
            "app-debug.apk",
            "android-version.json",
            "spunto-2026.10.15.6-debug.apk",
        ]
        self.assertEqual(
            [name for name in names if apk.fullmatch(name)], ["spunto-2026.10.15.6.apk"]
        )

    def test_public_certificate_is_canonical_sha256(self):
        pin = (ROOT / "spec/android-release-certificate.sha256").read_text().strip()
        self.assertRegex(pin, r"^[0-9a-f]{64}$")
        self.assertEqual(
            pin, "27799072ba669055e4451cb07af03548658505a9399743a51b62c221bb45cc86"
        )

    def test_obtainium_import_link_contains_the_same_credential_free_config(self):
        config = json.loads((ROOT / "distribution/obtainium.json").read_text())
        link = (ROOT / "distribution/obtainium-link.txt").read_text().strip()
        prefix = "obtainium://app/"
        self.assertTrue(link.startswith(prefix))
        self.assertEqual(json.loads(unquote(link[len(prefix):])), config)
        self.assertNotRegex(link.casefold(), r"token|password|credential|authorization")

    def test_fdroid_recipe_matches_the_release_version_and_pinned_signer(self):
        recipe = (
            ROOT / "distribution/fdroid/metadata/io.theficos.spunto.yml"
        ).read_text()
        text = (ROOT / "android/gradle.properties").read_text()
        properties = dict(re.findall(r"(?m)^(spuntoVersionName|spuntoVersionCode)=(.+)$", text))
        version = properties["spuntoVersionName"]
        code = properties["spuntoVersionCode"]
        self.assertNotRegex(recipe, r"(?m)^\s*(?:Disabled|disable):")
        self.assertIn(f"  - versionName: {version}\n", recipe)
        self.assertIn(f"    versionCode: {code}\n", recipe)
        self.assertIn(f"    commit: android/{version}\n", recipe)
        self.assertIn(f"CurrentVersion: {version}\n", recipe)
        self.assertIn(f"CurrentVersionCode: {code}\n", recipe)
        self.assertIn(
            "AllowedAPKSigningKeys:\n  - "
            + (ROOT / "spec/android-release-certificate.sha256").read_text().strip(),
            recipe,
        )
        binaries = re.search(r"(?m)^Binaries: (.+)$", recipe).group(1)
        self.assertEqual(
            binaries.replace("%v", version),
            f"https://github.com/vitofico/spunto/releases/download/"
            f"android%2F{version}/spunto-{version}.apk",
        )
        tags = re.search(r"(?m)^UpdateCheckMode: Tags (.+)$", recipe).group(1)
        self.assertIsNotNone(re.fullmatch(tags, f"android/{version}"))
        for excluded in ("android/2026.10.0", f"android/{version}-debug", "main"):
            self.assertIsNone(re.fullmatch(tags, excluded))
        extraction = re.search(r"(?m)^UpdateCheckData: (.+)$", recipe).group(1).split("|")
        self.assertEqual(extraction[0], "android/gradle.properties")
        self.assertEqual(extraction[2], "android/gradle.properties")
        self.assertEqual(re.search(extraction[1], text).group(1), code)
        self.assertEqual(re.search(extraction[3], text).group(1), version)


if __name__ == "__main__":
    unittest.main()
