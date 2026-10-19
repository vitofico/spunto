import importlib.util
import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "android_release", SCRIPTS / "android-release.py"
)
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)
CERTIFICATE = "a" * 64
BADGING = (
    "package: name='io.theficos.spunto' versionCode='6' versionName='2026.10.15.6'\n"
)
VERIFICATION = (
    "Verified using v2 scheme (APK Signature Scheme v2): true\nSigner #1 certificate SHA-256 digest: "
    + CERTIFICATE
    + "\n"
)


class AndroidReleaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "android").mkdir()
        (self.root / "spec").mkdir()
        (self.root / "android/gradle.properties").write_text(
            "spuntoVersionName=2026.10.15.6\nspuntoVersionCode=6\n"
        )
        (self.root / "spec/android-release-certificate.sha256").write_text(
            CERTIFICATE + "\n"
        )
        self.apk = self.root / "spunto-2026.10.15.6.apk"
        self.make_apk()

    def make_apk(self, profiles=True):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr(
                "AndroidManifest.xml", b"synthetic binary-manifest fixture"
            )
            if profiles:
                archive.writestr("assets/dexopt/baseline.prof", b"fixture profile")
                archive.writestr(
                    "assets/dexopt/baseline.profm", b"fixture profile metadata"
                )

    def record(self, badging=BADGING, verification=VERIFICATION):
        return RELEASE.release_record(self.root, self.apk, badging, verification)

    def test_release_identity_and_deterministic_sidecars(self):
        record = self.record()
        self.assertEqual(record["versionCode"], 6)
        self.assertEqual(record["certificateSha256"], CERTIFICATE)
        paths = RELEASE.sidecars(self.apk, record)
        RELEASE.write_sidecars(paths)
        before = {path: path.read_bytes() for path in paths}
        RELEASE.write_sidecars(paths)
        self.assertEqual(before, {path: path.read_bytes() for path in paths})
        self.assertEqual(
            json.loads((self.root / "android-version.json").read_text()), record
        )

    def test_wrong_signer_or_unsuccessful_signature_is_rejected(self):
        for verification in (
            "",
            VERIFICATION.replace(CERTIFICATE, "b" * 64),
            VERIFICATION.replace(": true", ": false"),
            VERIFICATION
            + "Signer #2 certificate SHA-256 digest: "
            + CERTIFICATE
            + "\n",
        ):
            with self.subTest(verification=verification), self.assertRaises(ValueError):
                self.record(verification=verification)

    def test_debuggable_wrong_package_or_version_rejected(self):
        for badging in (
            BADGING + "application-debuggable\n",
            BADGING.replace("io.theficos.spunto'", "io.theficos.spunto.debug'"),
            BADGING.replace("versionCode='6'", "versionCode='5'"),
        ):
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                self.record(badging=badging)

    def test_missing_baseline_profiles_are_rejected(self):
        self.make_apk(profiles=False)
        with self.assertRaises(ValueError):
            self.record()

    def test_corrupt_archive_and_oversized_apk_are_rejected(self):
        self.apk.write_bytes(b"not a zip")
        with self.assertRaises(zipfile.BadZipFile):
            self.record()
        with self.apk.open("wb") as stream:
            stream.truncate(15_000_001)
        with self.assertRaises(ValueError):
            self.record()

    def test_existing_sidecar_mismatch_blocks_every_write(self):
        paths = RELEASE.sidecars(self.apk, self.record())
        existing = self.root / "android-version.json"
        existing.write_text("immutable existing bytes")
        with self.assertRaises(ValueError):
            RELEASE.write_sidecars(paths)
        self.assertFalse(self.apk.with_name(self.apk.name + ".sha256").exists())
        self.assertEqual(existing.read_text(), "immutable existing bytes")

    def test_asset_name_and_tag_must_match_source(self):
        record = self.record()
        with self.assertRaises(ValueError):
            RELEASE.sidecars(self.apk.with_name("app-debug.apk"), record)
        with self.assertRaises(ValueError):
            RELEASE.source_identity(self.root, record, tag="android/2026.10.15.5")

    def test_source_provenance_requires_exact_commit_and_committed_version(self):
        version = RELEASE.VERSION.read_version(
            (self.root / "android/gradle.properties").read_text()
        )
        commit = "c" * 40
        output = [
            commit,
            commit,
            "",
            (self.root / "android/gradle.properties").read_text(),
        ]
        responses = [subprocess.CompletedProcess([], 0, value) for value in output]
        with patch.object(RELEASE.subprocess, "run", side_effect=responses) as git:
            self.assertEqual(
                RELEASE.source_identity(self.root, version, tag=version["tag"]), commit
            )
        self.assertEqual(
            git.call_args_list[0].args[0][-1], "refs/tags/android/2026.10.15.6^{commit}"
        )
        with self.assertRaises(ValueError):
            RELEASE.source_identity(self.root, version, source_sha="HEAD")

    def test_different_head_dirty_source_or_changed_version_cannot_claim_provenance(
        self,
    ):
        version = RELEASE.VERSION.read_version(
            (self.root / "android/gradle.properties").read_text()
        )
        commit = "c" * 40
        for outputs in (
            [commit, "d" * 40],
            [commit, commit, "android/app/build.gradle.kts\n"],
            [
                commit,
                commit,
                "",
                "spuntoVersionName=2026.10.15.5\nspuntoVersionCode=5\n",
            ],
        ):
            responses = [subprocess.CompletedProcess([], 0, value) for value in outputs]
            with (
                self.subTest(outputs=outputs),
                patch.object(RELEASE.subprocess, "run", side_effect=responses),
                self.assertRaises(ValueError),
            ):
                RELEASE.source_identity(self.root, version, source_sha=commit)


if __name__ == "__main__":
    unittest.main()
