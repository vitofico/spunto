import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/prepare-android-release.py"
SPEC = importlib.util.spec_from_file_location("prepare_android_release", SCRIPT)
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


def properties(name="2026.10.19.3", code=14):
    return (
        "# Keep the source-controlled identity and unrelated properties.\n"
        f"spuntoVersionName={name}\nspuntoVersionCode={code}\n"
        "org.gradle.parallel=true\n"
    )


def recipe(name="2026.10.19.3", code=14):
    return f"""# Keep this comment and the entire build configuration.
License: AGPL-3.0-only
RepoType: git
Repo: https://github.com/vitofico/spunto.git
Binaries: https://github.com/vitofico/spunto/releases/download/android%2F%v/spunto-%v.apk

Builds:
  - versionName: {name}
    versionCode: {code}
    commit: android/{name}
    subdir: android/app
    sudo:
      - apt-get install -y openjdk-21-jdk-headless
    gradle:
      - yes
    prebuild: sdkmanager 'platforms;android-35' 'build-tools;34.0.0'

AllowedAPKSigningKeys:
  - 27799072ba669055e4451cb07af03548658505a9399743a51b62c221bb45cc86

MaintainerNotes: |-
  Preserve baseline profiles and the original developer signature.
AutoUpdateMode: Version
CurrentVersion: {name}
CurrentVersionCode: {code}
"""


class PrepareAndroidReleaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.properties = self.root / PREPARE.PROPERTIES
        self.recipe = self.root / PREPARE.RECIPE
        self.properties.parent.mkdir(parents=True)
        self.recipe.parent.mkdir(parents=True)
        self.properties.write_text(properties())
        self.recipe.write_text(recipe())
        (self.root / "untouched.txt").write_text("Keep every unrelated file.\n")
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Release test")
        self.git("config", "user.email", "release@example.invalid")
        self.commit()

    def git(self, *args, **kwargs):
        return subprocess.run(
            ["git", *args],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=True,
            **kwargs,
        ).stdout.strip()

    def commit(self):
        self.git("add", ".")
        self.git("commit", "--allow-empty", "-m", "Synthetic release fixture")

    def tag(self, name, code, tag=None, annotated=False):
        current = self.properties.read_bytes()
        self.properties.write_text(properties(name, code))
        self.commit()
        args = ["tag"]
        if annotated:
            args += ["-a", "-m", "Synthetic release"]
        self.git(*args, tag or f"android/{name}")
        self.properties.write_bytes(current)
        self.commit()

    def snapshot(self):
        return {
            str(file.relative_to(self.root)): file.read_bytes()
            for file in self.root.rglob("*")
            if file.is_file() and ".git" not in file.relative_to(self.root).parts
        }

    def assert_rejected_without_changes(self, error=ValueError, date="2026-10-20"):
        before = self.snapshot()
        with self.assertRaises(error):
            PREPARE.prepare(self.root, date)
        self.assertEqual(self.snapshot(), before)

    def test_same_day_changes_exactly_the_two_files_and_preserves_every_other_byte(
        self,
    ):
        self.tag("2026.10.18.2", 10, annotated=True)
        self.properties.chmod(0o640)
        self.recipe.chmod(0o644)
        before = self.snapshot()
        result = PREPARE.prepare(self.root, "2026-10-19")
        self.assertEqual(
            result,
            {
                "versionName": "2026.10.19.4",
                "versionCode": 15,
                "tag": "android/2026.10.19.4",
            },
        )
        self.assertEqual(self.properties.read_text(), properties("2026.10.19.4", 15))
        self.assertEqual(self.recipe.read_text(), recipe("2026.10.19.4", 15))
        self.assertEqual(self.properties.stat().st_mode & 0o777, 0o640)
        self.assertEqual(self.recipe.stat().st_mode & 0o777, 0o644)
        after = self.snapshot()
        self.assertEqual(set(before), set(after))
        self.assertEqual(
            {name for name in before if before[name] != after[name]},
            {str(PREPARE.PROPERTIES), str(PREPARE.RECIPE)},
        )

    def test_new_day_resets_run_but_not_independent_code(self):
        result = PREPARE.prepare(self.root, "2026-10-20")
        self.assertEqual(result["versionName"], "2026.10.20.1")
        self.assertEqual(result["versionCode"], 15)
        self.assertEqual(self.recipe.read_text(), recipe("2026.10.20.1", 15))

    def test_new_year_resets_run_and_preserves_independent_code(self):
        self.properties.write_text(properties("2026.12.31.9", 99))
        self.recipe.write_text(recipe("2026.12.31.9", 99))
        result = PREPARE.prepare(self.root, "2027-01-01")
        self.assertEqual(result["versionName"], "2027.01.01.1")
        self.assertEqual(result["versionCode"], 100)

    def test_every_tag_is_checked_for_higher_code_even_when_calendar_is_older(self):
        self.tag("2026.10.18.1", 99)
        self.tag("2026.10.19.1", 13)
        self.assert_rejected_without_changes()

    def test_higher_calendar_is_rejected_even_when_historical_code_is_lower(self):
        self.tag("2026.10.21.1", 13)
        self.assert_rejected_without_changes()

    def test_duplicate_candidate_tag_is_rejected(self):
        self.tag("2026.10.20.1", 15)
        self.assert_rejected_without_changes()

    def test_tag_name_must_match_its_exact_properties_source(self):
        self.tag("2026.10.18.1", 10, tag="android/2026.10.18.2")
        self.assert_rejected_without_changes()

    def test_unreadable_tag_source_fails_closed(self):
        blob = self.git(
            "hash-object", "-w", "--stdin", input="Synthetic non-commit tag\n"
        )
        self.git("tag", "android/2026.10.18.1", blob)
        self.assert_rejected_without_changes(subprocess.CalledProcessError)

    def test_git_lookup_error_fails_closed(self):
        with patch.object(
            PREPARE.subprocess,
            "run",
            side_effect=subprocess.CalledProcessError(128, "git"),
        ):
            self.assert_rejected_without_changes(subprocess.CalledProcessError)

    def test_invalid_dates_or_code_exhaustion_never_write(self):
        for date in ("2026-10-18", "2026-02-30", "2026-1-1", "20261020"):
            with self.subTest(date=date):
                self.assert_rejected_without_changes(date=date)
        self.properties.write_text(properties(code=2_100_000_000))
        self.recipe.write_text(recipe(code=2_100_000_000))
        self.assert_rejected_without_changes()

    def test_malformed_or_mismatched_recipe_never_partially_updates_properties(self):
        original = recipe()
        variants = [
            original.replace("Builds:\n", ""),
            original + "\nBuilds:\n",
            original.replace("    commit: android/2026.10.19.3\n", ""),
            original.replace("    versionCode: 14", "    versionCode: 13"),
            original.replace(
                "  - versionName: 2026.10.19.3", "  - versionName: 2026.10.18.1"
            ),
            original.replace(
                "CurrentVersion: 2026.10.19.3", "CurrentVersion: 2026.10.18.1"
            ),
            original.replace("CurrentVersionCode: 14", "CurrentVersionCode: 13"),
            original.replace("    commit: android/2026.10.19.3", "    commit: main"),
            original + "\nCurrentVersionCode: 14\n",
            original + "\n'CurrentVersionCode': 14\n",
            original.replace(
                "    versionCode: 14", "    versionCode: 14\n    versionCode: 14"
            ),
            original.replace("    subdir: android/app", "  - subdir: android/app"),
            original.replace("CurrentVersion:", "  CurrentVersion:"),
            original.replace("    commit: android/2026.10.19.3\n", "")
            + "    commit: android/2026.10.19.3\n",
        ]
        for text in variants:
            with self.subTest(recipe=text):
                self.recipe.write_text(text)
                self.assert_rejected_without_changes()

    def test_second_replace_failure_rolls_back_properties_and_cleans_staging(self):
        replace = os.replace
        failed = False

        def fail_recipe_once(source, target):
            nonlocal failed
            if target == self.recipe and not failed:
                failed = True
                raise OSError("Synthetic second-file failure")
            return replace(source, target)

        with patch.object(PREPARE.os, "replace", side_effect=fail_recipe_once):
            self.assert_rejected_without_changes(OSError)
        self.assertTrue(failed)

    def test_checked_in_recipe_supports_preparation_without_mutating_real_source(self):
        properties_path = ROOT / PREPARE.PROPERTIES
        recipe_path = ROOT / PREPARE.RECIPE
        properties_before = properties_path.read_bytes()
        recipe_before = recipe_path.read_bytes()
        previous = PREPARE.VERSION.read_version(properties_before.decode())
        date = PREPARE.VERSION.calendar_version(previous["versionName"])[0].isoformat()
        current = PREPARE.VERSION.read_version(
            PREPARE.VERSION.advance(properties_before.decode(), date)
        )
        prepared = PREPARE.prepare_recipe(recipe_before.decode(), previous, current)
        self.assertIn(f"CurrentVersion: {current['versionName']}\n", prepared)
        self.assertIn(f"    commit: {current['tag']}\n", prepared)
        self.assertEqual(properties_path.read_bytes(), properties_before)
        self.assertEqual(recipe_path.read_bytes(), recipe_before)

    def test_cli_uses_fixture_root_and_prints_expected_json_without_extra_files(self):
        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--root",
                str(self.root),
                "--date",
                "2026-10-20",
            ],
            capture_output=True,
            text=True,
            check=True,
        )
        self.assertEqual(
            json.loads(result.stdout),
            {
                "versionName": "2026.10.20.1",
                "versionCode": 15,
                "tag": "android/2026.10.20.1",
            },
        )
        self.assertEqual(result.stderr, "")
        self.assertEqual(
            set(self.snapshot()),
            {str(PREPARE.PROPERTIES), str(PREPARE.RECIPE), "untouched.txt"},
        )


if __name__ == "__main__":
    unittest.main()
