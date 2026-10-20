import datetime
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FILES = [
    "scripts/release-source.sh",
    "scripts/prepare-android-release.py",
    "scripts/android-version.py",
    "android/gradle.properties",
    "distribution/fdroid/metadata/io.theficos.spunto.yml",
]


class ReleaseSourceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        self.env = dict(
            os.environ,
            GIT_AUTHOR_NAME="Fixture",
            GIT_COMMITTER_NAME="Fixture",
            GIT_AUTHOR_EMAIL="fixture@example.invalid",
            GIT_COMMITTER_EMAIL="fixture@example.invalid",
        )
        for file in FILES:
            target = self.source / file
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / file, target)
        (self.source / "README.md").write_text("Synthetic release fixture.\n")
        self.git(self.source, "init", "-q", "-b", "main")
        self.git(self.source, "add", ".")
        self.git(self.source, "commit", "-qm", "Fixture source")
        properties = (self.source / "android/gradle.properties").read_text()
        name = next(
            x.split("=", 1)[1]
            for x in properties.splitlines()
            if x.startswith("spuntoVersionName=")
        )
        self.date = (
            datetime.date.fromisoformat(name[:10].replace(".", "-"))
            + datetime.timedelta(days=1)
        ).isoformat()
        self.git(self.source, "tag", f"android/{name}")

    def git(self, root, *args):
        return subprocess.check_output(
            ["git", *args], cwd=root, env=self.env, stderr=subprocess.PIPE, text=True
        ).strip()

    def clone(self, name):
        path = self.root / name
        self.git(self.root, "clone", "-q", str(self.source), str(path))
        return path

    def prepare(self, root, date, tree=""):
        return subprocess.run(
            ["bash", "scripts/release-source.sh", date, tree],
            cwd=root,
            env=self.env,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_independent_jobs_prepare_identical_trees_without_publishing(self):
        first, second = self.clone("first"), self.clone("second")
        a = self.prepare(first, self.date)
        self.assertEqual(a.returncode, 0, a.stderr)
        tree = a.stdout.strip()
        self.assertRegex(tree, r"^[0-9a-f]{40}$")
        b = self.prepare(second, self.date, tree)
        self.assertEqual(b.returncode, 0, b.stderr)
        self.assertEqual(tree, b.stdout.strip())
        self.assertEqual(self.git(first, "rev-list", "--count", "HEAD"), "1")
        self.assertEqual(
            self.git(second, "diff", "--cached", "--name-only").splitlines(), FILES[-2:]
        )

    def test_mismatched_or_unstaged_source_is_rejected(self):
        first = self.clone("first")
        result = self.prepare(first, self.date, "0" * 40)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("differs", result.stderr)
        second = self.clone("second")
        (second / "README.md").write_text("Untested source.\n")
        result = self.prepare(second, self.date)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("source outside", result.stderr)

    def test_server_only_keeps_the_existing_tree(self):
        clone = self.clone("server")
        tree = self.git(clone, "rev-parse", "HEAD^{tree}")
        result = self.prepare(clone, "", tree)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), tree)
        self.assertEqual(self.git(clone, "status", "--porcelain"), "")


if __name__ == "__main__":
    unittest.main()
