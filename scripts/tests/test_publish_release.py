import importlib.util
import io
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "publish-release.py"
SPEC = importlib.util.spec_from_file_location("publish_release", SCRIPT)
PUBLISH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLISH)
REPO, TAG = "owner/repository", "v2026.09.29.274"


def release(names):
    return {
        "tag_name": TAG,
        "draft": False,
        "prerelease": False,
        "assets": [
            {"name": name, "id": index, "size": len(data)}
            for index, (name, data) in enumerate(names.items())
        ],
    }


class PublishReleaseTest(unittest.TestCase):
    def test_tag_lookup_uses_authenticated_api_and_encodes_namespaced_tag(self):
        missing = subprocess.CalledProcessError(
            1,
            "gh",
            output=b"HTTP/2.0 404 Not Found\r\ncontent-type: application/json\r\n",
        )
        with patch.object(PUBLISH, "gh", side_effect=missing) as gh:
            PUBLISH.require_absent_tag(REPO, "android/2026.10.18.1")
        gh.assert_called_once_with(
            "api", "--include", f"repos/{REPO}/git/ref/tags/android%2F2026.10.18.1"
        )

    def test_present_tag_blocks_publication(self):
        with (
            patch.object(PUBLISH, "gh", return_value=b"HTTP/2.0 200 OK\r\n{}"),
            self.assertRaisesRegex(ValueError, "tag already exists"),
        ):
            PUBLISH.require_absent_tag(REPO, TAG)

    def test_only_exact_not_found_status_allows_a_new_tag(self):
        for output in (
            b"HTTP/2.0 401 Unauthorized\r\n",
            b"HTTP/2.0 403 Forbidden\r\n",
            b"HTTP/2.0 429 Too Many Requests\r\n",
            b"HTTP/2.0 500 Internal Server Error\r\n",
            b"HTTP/2.0 4040 Unknown\r\n",
            b"untrusted error text: HTTP 404",
            b"",
        ):
            failure = subprocess.CalledProcessError(
                1, "gh", output=output, stderr=b"gh: diagnostic mentions HTTP 404"
            )
            with (
                self.subTest(output=output),
                patch.object(PUBLISH, "gh", side_effect=failure),
                self.assertRaisesRegex(ValueError, "tag lookup failed"),
            ):
                PUBLISH.require_absent_tag(REPO, TAG)
        for protocol in (b"HTTP/1.1", b"HTTP/2.0"):
            missing = subprocess.CalledProcessError(
                1, "gh", output=protocol + b" 404 Not Found\r\n{}"
            )
            with patch.object(PUBLISH, "gh", side_effect=missing):
                PUBLISH.require_absent_tag(REPO, TAG)

    def test_tag_lookup_failure_does_not_print_raw_diagnostics(self):
        failure = subprocess.CalledProcessError(
            1,
            "sensitive command",
            output=b"HTTP/2.0 403 Forbidden\r\n",
            stderr=b"synthetic private diagnostic",
        )
        stderr = io.StringIO()
        with (
            patch(
                "sys.argv",
                [str(SCRIPT), "--repo", REPO, "--tag", TAG, "--require-absent-tag"],
            ),
            patch.object(PUBLISH, "gh", side_effect=failure),
            patch("sys.stderr", stderr),
            patch.object(PUBLISH, "publish") as publish,
        ):
            self.assertEqual(PUBLISH.main(), 1)
        publish.assert_not_called()
        self.assertEqual(
            stderr.getvalue(), "GitHub tag lookup failed; refusing publication.\n"
        )

    def test_native_calver_title_is_distinct_from_namespaced_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "spunto-2026.10.15.6.apk"
            apk.write_bytes(b"synthetic APK")
            with (
                patch.object(PUBLISH, "find_release", return_value=None),
                patch.object(PUBLISH, "gh") as gh,
            ):
                PUBLISH.publish(
                    REPO, "android/2026.10.15.6", [apk], "false", title="2026.10.15.6"
                )
            args = gh.call_args.args
            self.assertEqual(args[args.index("--title") + 1], "2026.10.15.6")
            self.assertIn("android/2026.10.15.6", args)

    def test_only_definitive_not_found_allows_release_creation(self):
        for status in (401, 403, 429, 500):
            error = subprocess.CalledProcessError(
                1, "gh", stderr=f"HTTP {status}".encode()
            )
            with (
                self.subTest(status=status),
                patch.object(PUBLISH, "gh", side_effect=error),
                self.assertRaises(subprocess.CalledProcessError),
            ):
                PUBLISH.find_release(REPO, TAG)
        missing = subprocess.CalledProcessError(
            1, "gh", stderr=b"gh: Not Found (HTTP 404)"
        )
        with patch.object(PUBLISH, "gh", side_effect=missing):
            self.assertIsNone(PUBLISH.find_release(REPO, TAG))

    def test_release_creation_checks_existing_tag_and_never_clobbers(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app-release.apk"
            apk.write_bytes(b"apk")
            with (
                patch.object(PUBLISH, "find_release", return_value=None),
                patch.object(PUBLISH, "gh") as gh,
            ):
                PUBLISH.publish(REPO, TAG, [apk], "false")
                args = gh.call_args.args
                self.assertIn("--verify-tag", args)
                self.assertIn("--latest=false", args)
                self.assertNotIn("--clobber", args)
                self.assertNotIn("--prerelease", args)

    def test_preview_creation_is_explicit_and_cannot_be_latest(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "preview.apk"
            apk.write_bytes(b"preview")
            with (
                patch.object(PUBLISH, "find_release", return_value=None),
                patch.object(PUBLISH, "gh") as gh,
            ):
                PUBLISH.publish(REPO, TAG, [apk], "false", prerelease=True)
                self.assertIn("--prerelease", gh.call_args.args)
                self.assertIn("--latest=false", gh.call_args.args)
            with (
                patch.object(PUBLISH, "find_release") as lookup,
                patch.object(PUBLISH, "gh") as gh,
                self.assertRaisesRegex(ValueError, "cannot be marked latest"),
            ):
                PUBLISH.publish(REPO, TAG, [apk], "true", prerelease=True)
            lookup.assert_not_called()
            gh.assert_not_called()

    def test_preview_repair_preserves_published_bytes_and_status(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, checksum = Path(directory) / "preview.apk", Path(directory) / "sum.txt"
            apk.write_bytes(b"preview")
            checksum.write_bytes(b"checksum")
            existing = release({apk.name: b"preview"})
            existing["prerelease"] = True
            with (
                patch.object(PUBLISH, "find_release", return_value=existing),
                patch.object(
                    PUBLISH,
                    "download",
                    side_effect=lambda repo, asset, path: path.write_bytes(b"preview"),
                ),
                patch.object(PUBLISH, "gh") as gh,
            ):
                PUBLISH.publish(REPO, TAG, [apk, checksum], "false", prerelease=True)
            gh.assert_called_once_with(
                "release", "upload", TAG, "--repo", REPO, str(checksum)
            )

    def test_repair_rejects_preview_status_changes_before_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "preview.apk"
            apk.write_bytes(b"preview")
            for requested, published in ((True, False), (False, True)):
                existing = release({})
                existing["prerelease"] = published
                with (
                    self.subTest(requested=requested),
                    patch.object(PUBLISH, "find_release", return_value=existing),
                    patch.object(PUBLISH, "gh") as gh,
                    self.assertRaisesRegex(ValueError, "preview status"),
                ):
                    PUBLISH.publish(REPO, TAG, [apk], "false", prerelease=requested)
                gh.assert_not_called()

    def test_identical_assets_skip_and_only_missing_sidecar_is_uploaded(self):
        with tempfile.TemporaryDirectory() as directory:
            apk, checksum = (
                Path(directory) / "app-release.apk",
                Path(directory) / "app-release.apk.sha256",
            )
            apk.write_bytes(b"original apk")
            checksum.write_bytes(b"checksum")

            def download(repo, asset, destination):
                destination.write_bytes(b"original apk")

            with (
                patch.object(
                    PUBLISH,
                    "find_release",
                    return_value=release({apk.name: apk.read_bytes()}),
                ),
                patch.object(PUBLISH, "download", side_effect=download),
                patch.object(PUBLISH, "gh") as gh,
            ):
                PUBLISH.publish(REPO, TAG, [apk, checksum], "true")
                gh.assert_called_once_with(
                    "release", "upload", TAG, "--repo", REPO, str(checksum)
                )

    def test_mismatch_fails_before_any_mutation_even_when_other_asset_is_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            missing, apk = (
                Path(directory) / "missing.json",
                Path(directory) / "app-release.apk",
            )
            missing.write_bytes(b"new sidecar")
            apk.write_bytes(b"new APK")

            def download(repo, asset, destination):
                destination.write_bytes(b"published APK")

            with (
                patch.object(
                    PUBLISH,
                    "find_release",
                    return_value=release({apk.name: b"published APK"}),
                ),
                patch.object(PUBLISH, "download", side_effect=download),
                patch.object(PUBLISH, "gh") as gh,
                self.assertRaises(ValueError),
            ):
                PUBLISH.publish(REPO, TAG, [missing, apk], "false")
            gh.assert_not_called()

    def test_recovery_fetches_original_bytes_and_reports_missing_without_build(self):
        data = b"original signed APK"
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "nested/app-release.apk"
            with (
                patch.object(
                    PUBLISH,
                    "find_release",
                    return_value=release({destination.name: data}),
                ),
                patch.object(PUBLISH, "gh", return_value=data),
            ):
                self.assertTrue(
                    PUBLISH.fetch_existing(REPO, TAG, destination.name, destination)
                )
                self.assertEqual(destination.read_bytes(), data)
            with patch.object(PUBLISH, "find_release", return_value=None):
                self.assertFalse(
                    PUBLISH.fetch_existing(REPO, TAG, destination.name, destination)
                )

    def test_short_download_duplicate_names_and_empty_local_assets_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "app-release.apk"
            with (
                patch.object(PUBLISH, "gh", return_value=b"short"),
                self.assertRaises(ValueError),
            ):
                PUBLISH.download(REPO, {"id": 1, "size": 99, "name": path.name}, path)
            with self.assertRaises(ValueError):
                PUBLISH.asset_map({"assets": [{"name": "same"}, {"name": "same"}]})
            with patch.object(PUBLISH, "gh") as gh, self.assertRaises(ValueError):
                PUBLISH.publish(REPO, TAG, [path], "false")
            gh.assert_not_called()


if __name__ == "__main__":
    unittest.main()
