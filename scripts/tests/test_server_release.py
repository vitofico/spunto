import copy
import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "server_release", Path(__file__).resolve().parents[1] / "server-release.py"
)
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)
SHA = "a" * 40


class ServerReleaseTest(unittest.TestCase):
    def test_inventory_includes_orphaned_images_and_every_page(self):
        refs = [
            [{"ref": "refs/tags/2026.10.1"}],
            [{"ref": "refs/tags/android/2026.10.20.1"}],
        ]
        packages = [
            [{"metadata": {"container": {"tags": ["2026.10.2", "stable"]}}}],
            [{"metadata": {"container": {"tags": ["2026.10.12", "2025.10.99"]}}}],
        ]
        self.assertEqual(RELEASE.pick("2026-10-20", refs, packages), "2026.10.13")
        self.assertEqual(RELEASE.pick("2026-11-01", [], []), "2026.11.0")
        self.assertEqual(RELEASE.pick("2026-10-20", [], []), "2026.10.1")

    def test_bad_inventory_is_not_an_empty_inventory(self):
        for refs, packages in [
            ({}, []),
            ([{}], []),
            ([], [{}]),
            ([], [{"metadata": {"container": {"tags": [3]}}}]),
        ]:
            with (
                self.subTest(refs=refs, packages=packages),
                self.assertRaises(ValueError),
            ):
                RELEASE.pick("2026-10-20", refs, packages)
        for date in ["2026-1-1", "2026-02-30", "20260101"]:
            with self.subTest(date=date), self.assertRaises(ValueError):
                RELEASE.pick(date, [], [])

    def fixtures(self):
        manifest = {
            "digest": "sha256:" + "b" * 64,
            "manifests": [
                {"platform": {"os": "linux", "architecture": arch}}
                for arch in ["amd64", "arm64"]
            ],
        }
        configs = {
            f"linux/{arch}": {
                "os": "linux",
                "architecture": arch,
                "config": {
                    "Labels": {
                        "org.opencontainers.image.version": "2026.10.2",
                        "org.opencontainers.image.revision": SHA,
                        "org.opencontainers.image.source": "https://github.com/vitofico/spunto",
                    }
                },
            }
            for arch in ["amd64", "arm64"]
        }
        return manifest, configs

    def test_repair_selects_the_numeric_latest_server_only(self):
        refs = [
            [
                {"ref": "refs/tags/2026.10.12"},
                {"ref": "refs/tags/2026.10.2"},
                {"ref": "refs/tags/android/2027.01.01.1"},
            ]
        ]
        self.assertEqual(RELEASE.newest(refs), "2026.10.12")
        refs.append([{"ref": "refs/tags/2026.11.0"}])
        self.assertEqual(RELEASE.newest(refs), "2026.11.0")
        with self.assertRaises(ValueError):
            RELEASE.newest([])

    def test_both_platforms_are_bound_to_source_and_version(self):
        manifest, configs = self.fixtures()
        self.assertEqual(
            RELEASE.verify(manifest, configs, "2026.10.2", SHA)["sourceCommit"], SHA
        )
        for platform in configs:
            for field in [
                "org.opencontainers.image.version",
                "org.opencontainers.image.revision",
                "org.opencontainers.image.source",
            ]:
                changed = copy.deepcopy(configs)
                changed[platform]["config"]["Labels"][field] = "different"
                with (
                    self.subTest(platform=platform, field=field),
                    self.assertRaises(ValueError),
                ):
                    RELEASE.verify(manifest, changed, "2026.10.2", SHA)

    def test_missing_platform_attestations_and_bad_digest_fail(self):
        manifest, configs = self.fixtures()
        for key, value in [
            ("digest", "not-a-digest"),
            ("manifests", manifest["manifests"][:1]),
            (
                "manifests",
                [
                    {
                        "platform": {"os": "linux", "architecture": "arm64"},
                        "annotations": {
                            "vnd.docker.reference.type": "attestation-manifest"
                        },
                    }
                ],
            ),
        ]:
            changed = copy.deepcopy(manifest)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                RELEASE.verify(changed, configs, "2026.10.2", SHA)
        with self.assertRaises(ValueError):
            RELEASE.verify(manifest, {}, "2026.10.2", SHA)


if __name__ == "__main__":
    unittest.main()
