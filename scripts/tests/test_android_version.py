import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "android-version.py"
SPEC = importlib.util.spec_from_file_location("android_version", SCRIPT)
VERSION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERSION)


def properties(name="2026.10.15.5", code="5"):
    return f"# retain this comment\nspuntoVersionName={name}\nspuntoVersionCode={code}\norg.gradle.parallel=true\n"


class AndroidVersionTest(unittest.TestCase):
    def test_static_identity_and_historical_leap_day(self):
        self.assertEqual(
            VERSION.read_version(properties()),
            {
                "versionName": "2026.10.15.5",
                "versionCode": 5,
                "tag": "android/2026.10.15.5",
            },
        )
        self.assertEqual(VERSION.calendar_version("2024.02.29.1")[1], 1)

    def test_calendar_and_canonical_format_rejected(self):
        for name in (
            "2026.02.29.1",
            "2026.04.31.1",
            "0000.10.15.1",
            "2026.1.15.1",
            "2026.10.15.0",
            "2026.10.15.01",
            "2026.10.15.1-debug",
            "1.2.3",
        ):
            with self.subTest(name=name), self.assertRaises(ValueError):
                VERSION.read_version(properties(name=name))

    def test_code_bounds_and_missing_or_duplicate_source_values(self):
        for code in ("0", "-1", "01", "1.0", "2100000001", "five"):
            with self.subTest(code=code), self.assertRaises(ValueError):
                VERSION.read_version(properties(code=code))
        for text in (
            "",
            "spuntoVersionName=2026.10.15.5\n",
            properties() + "spuntoVersionCode=6\n",
        ):
            with self.subTest(text=text), self.assertRaises(ValueError):
                VERSION.read_version(text)
        self.assertEqual(
            VERSION.read_version(properties(code="2100000000"))["versionCode"],
            2100000000,
        )

    def test_same_day_bump_advances_sequence_and_code_without_other_edits(self):
        self.assertEqual(
            VERSION.advance(properties(), "2026-10-15"), properties("2026.10.15.6", "6")
        )

    def test_new_day_resets_only_calver_sequence_and_crosses_year(self):
        result = VERSION.advance(properties("2026.12.31.19", "999"), "2027-01-01")
        self.assertEqual(result, properties("2027.01.01.1", "1000"))

    def test_bump_rejects_backdating_bad_date_or_exhausted_code(self):
        for date in ("2026-10-14", "2026-02-30", "20261016", "2026-1-1"):
            with self.subTest(date=date), self.assertRaises(ValueError):
                VERSION.advance(properties(), date)
        with self.assertRaises(ValueError):
            VERSION.advance(properties(code="2100000000"), "2026-10-16")

    def test_bump_preserves_other_properties_with_indented_version_lines(self):
        original = "  spuntoVersionName = 2026.10.15.5\n\tspuntoVersionCode=5\nother=unchanged\n"
        result = VERSION.advance(original, "2026-10-16")
        self.assertEqual(VERSION.read_version(result)["versionName"], "2026.10.16.1")
        self.assertEqual(VERSION.read_version(result)["versionCode"], 6)
        self.assertIn("other=unchanged\n", result)

    def test_publishing_checks_both_local_floor_and_every_previous_release(self):
        current = VERSION.read_version(properties("2026.10.16.1", "10"))
        VERSION.check_history(current, [VERSION.read_version(properties())])
        for previous in (
            properties("2026.10.15.6", "11"),
            properties("2026.10.17.1", "9"),
        ):
            with self.subTest(previous=previous), self.assertRaises(ValueError):
                VERSION.check_history(
                    current,
                    [
                        VERSION.read_version(properties()),
                        VERSION.read_version(previous),
                    ],
                )
        for name, code in (
            ("2026.10.16.1", "5"),
            ("2026.10.16.1", "6"),
            ("2026.10.16.1", "7"),
            ("2026.10.15.5", "4"),
            ("2026.10.15.5", "6"),
            ("2026.10.15.6", "7"),
            ("2026.10.15.7", "8"),
        ):
            with self.subTest(name=name, code=code), self.assertRaises(ValueError):
                VERSION.check_history(VERSION.read_version(properties(name, code)), [])

    def test_first_public_build_must_advance_local_delivery_but_rebuild_is_valid(self):
        current = VERSION.read_version(properties("2026.10.15.7", "7"))
        self.assertEqual(current["versionCode"], 7)
        with self.assertRaises(ValueError):
            VERSION.check_history(current, [])
        VERSION.check_history(VERSION.read_version(properties("2026.10.15.8", "8")), [])

    def test_duplicate_release_identity_is_rejected(self):
        current = VERSION.read_version(properties())
        with self.assertRaises(ValueError):
            VERSION.check_history(current, [current])

    def test_fetched_tag_must_match_its_source_and_lookup_errors_propagate(self):
        current = VERSION.read_version(properties("2026.10.16.1", "8"))
        commands = [
            subprocess.CompletedProcess([], 0, "refs/tags/android/2026.10.15.4\n"),
            subprocess.CompletedProcess([], 0, properties()),
        ]
        with (
            patch.object(VERSION.subprocess, "run", side_effect=commands),
            self.assertRaises(ValueError),
        ):
            VERSION.check_tags(current)
        with (
            patch.object(
                VERSION.subprocess,
                "run",
                side_effect=subprocess.CalledProcessError(128, "git"),
            ),
            self.assertRaises(subprocess.CalledProcessError),
        ):
            VERSION.check_tags(current)

    def test_actual_apk_identity_must_match_source(self):
        current = VERSION.read_version(properties())
        correct = "package: name='io.theficos.spunto' versionCode='5' versionName='2026.10.15.5' platformBuildVersionName='15' compileSdkVersionCodename='15'\n"
        VERSION.check_badging(current, correct)
        for text in (
            "",
            correct.replace("versionCode='5'", "versionCode='4'"),
            correct.replace("2026.10.15.5", "2026.10.0"),
            correct.replace("io.theficos.spunto'", "io.theficos.spunto.debug'"),
        ):
            with self.subTest(text=text), self.assertRaises(ValueError):
                VERSION.check_badging(current, text)

    def test_cli_verification_is_read_only_and_failed_bump_preserves_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            (root / "android").mkdir()
            script = root / "scripts/android-version.py"
            source = root / "android/gradle.properties"
            script.write_bytes(SCRIPT.read_bytes())
            source.write_text(properties())
            before = source.read_bytes()
            valid = subprocess.run(
                [sys.executable, str(script)],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(valid.returncode, 0, valid.stderr)
            self.assertEqual(source.read_bytes(), before)
            invalid = subprocess.run(
                [sys.executable, str(script), "--bump", "2026-10-14"],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(invalid.returncode, 1)
            self.assertEqual(source.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
