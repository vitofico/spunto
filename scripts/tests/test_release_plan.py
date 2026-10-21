import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/release.yml"
REPO = "owner/repository"
PLAN_NAME = "Select and prepare the source for all checks"
RETRY_NAME = "Validate retry source before preparing a release"


def workflow_plan(name=PLAN_NAME):
    text = WORKFLOW.read_text()
    lines = text.splitlines()
    marker = f"      - name: {name}"
    starts = [index for index, line in enumerate(lines) if line == marker]
    if len(starts) != 1:
        raise AssertionError("The workflow must have one matching named release step.")
    start = starts[0]
    run = next(
        index
        for index in range(start + 1, len(lines))
        if lines[index] == "        run: |"
    )
    body = []
    for line in lines[run + 1 :]:
        if line and not line.startswith("          "):
            break
        body.append(line[10:] if line else "")
    return text, "\n".join(lines[start:run]), "\n".join(body) + "\n"


class ReleasePlanTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow, cls.step, cls.plan = workflow_plan()
        _, cls.retry_step, cls.retry = workflow_plan(RETRY_NAME)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.remote = self.directory / "origin.git"
        self.seed = self.directory / "seed"
        self.checkout = self.directory / "checkout"
        self.bin = self.directory / "bin"
        self.bin.mkdir()
        self.log = self.directory / "gh.jsonl"
        self.output = self.directory / "outputs"
        self.summary = self.directory / "summary"
        self.environment = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith(("GIT_", "GH_", "GITHUB_", "ANDROID_RELEASE_"))
        }
        # Commits and pushes start detached housekeeping that can still be writing
        # into a repository while cleanup removes it.
        quiet = self.directory / "gitconfig"
        quiet.write_text(
            "[maintenance]\n\tauto = false\n[gc]\n\tauto = 0\n[receive]\n\tautogc = false\n"
        )
        self.environment.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=str(quiet))
        self.git(
            self.directory, "init", "--bare", "--initial-branch=main", str(self.remote)
        )
        self.git(self.directory, "init", "--initial-branch=main", str(self.seed))
        self.git(self.seed, "config", "user.name", "Release plan test")
        self.git(self.seed, "config", "user.email", "plan-test@example.invalid")
        (self.seed / "README").write_text("synthetic tested source\n")
        self.git(self.seed, "add", "README")
        self.git(self.seed, "commit", "-m", "tested source")
        self.base = self.git(self.seed, "rev-parse", "HEAD")
        self.git(self.seed, "remote", "add", "origin", str(self.remote))
        self.git(self.seed, "push", "origin", "main")
        self.git(self.directory, "clone", str(self.remote), str(self.checkout))
        self.git(self.checkout, "checkout", "--detach", self.base)
        gh = self.bin / "gh"
        gh.write_text(
            "#!/usr/bin/env python3\n"
            "import json, os, sys\n"
            "args = sys.argv[1:]\n"
            "with open(os.environ['FIXTURE_GH_LOG'], 'a') as log:\n"
            "    log.write(json.dumps(args) + '\\n')\n"
            "if args == ['api', 'repos/owner/repository/git/ref/heads/main', '--jq', '.object.sha']:\n"
            "    print(os.environ['FIXTURE_TIP'])\n"
            "elif args[:3] == ['workflow', 'run', 'release.yml']:\n"
            "    pass\n"
            "else:\n"
            "    print('Unexpected GitHub fixture command', file=sys.stderr)\n"
            "    sys.exit(90)\n"
        )
        gh.chmod(0o755)

    def git(self, root, *args):
        return subprocess.run(
            ["git", *args],
            cwd=root,
            env=self.environment,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()

    def advance(self):
        self.git(self.seed, "commit", "--allow-empty", "-m", "promoted release child")
        tip = self.git(self.seed, "rev-parse", "HEAD")
        tags = ("2026.10.2", "android/2026.10.20.1")
        for tag in tags:
            self.git(self.seed, "tag", tag)
        self.git(self.seed, "push", "--atomic", "origin", "main", *tags)
        return tip

    def files(self):
        return {
            path.relative_to(self.checkout).as_posix(): path.read_bytes()
            for path in self.checkout.rglob("*")
            if path.is_file() and ".git" not in path.relative_to(self.checkout).parts
        }

    def run_plan(self, tip, *, attempt, event, publish_android, script=None):
        self.log.write_text("")
        self.output.unlink(missing_ok=True)
        self.summary.unlink(missing_ok=True)
        environment = {
            **self.environment,
            "PATH": str(self.bin) + os.pathsep + self.environment["PATH"],
            "FIXTURE_GH_LOG": str(self.log),
            "FIXTURE_TIP": tip,
            "GITHUB_REPOSITORY": REPO,
            "GITHUB_EVENT_NAME": event,
            "GITHUB_SHA": self.base,
            "GITHUB_RUN_ID": "123",
            "GITHUB_OUTPUT": str(self.output),
            "GITHUB_STEP_SUMMARY": str(self.summary),
            "RUN_ATTEMPT": str(attempt),
            "PUBLISH_ANDROID": str(publish_android).lower(),
            "SIGNING_READY": "false",
            "RELEASE_SOURCE": self.base,
        }
        self.assertNotIn("ANDROID_RELEASE_KEYSTORE_BASE64", environment)
        self.assertNotIn("ANDROID_RELEASE_KEYSTORE_PASSWORD", environment)
        before = self.files()
        refs_before = self.git(self.remote, "show-ref")
        result = subprocess.run(
            [
                "bash",
                "--noprofile",
                "--norc",
                "-e",
                "-o",
                "pipefail",
                "-c",
                self.plan if script is None else script,
            ],
            cwd=self.checkout,
            env=environment,
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )
        self.assertEqual(self.files(), before)
        self.assertEqual(self.git(self.checkout, "rev-parse", "HEAD"), self.base)
        self.assertEqual(self.git(self.checkout, "status", "--porcelain"), "")
        self.assertEqual(self.git(self.remote, "rev-parse", "main"), tip)
        self.assertEqual(self.git(self.remote, "show-ref"), refs_before)
        return result, [json.loads(line) for line in self.log.read_text().splitlines()]

    def test_plan_pins_both_event_types_to_the_original_run_commit(self):
        plan_job = self.workflow.split("\n  plan:\n", 1)[1].split("\n  checks:\n", 1)[0]
        self.assertEqual(
            re.findall(r"(?m)^          ref: (.+)$", plan_job), ["${{ github.sha }}"]
        )
        self.assertIn("RUN_ATTEMPT: ${{ github.run_attempt }}", self.step)

    def test_rerunning_push_or_manual_after_promotion_fails_without_dispatch(self):
        tip = self.advance()
        for event, publish_android in (("push", True), ("workflow_dispatch", False)):
            with self.subTest(event=event):
                result, calls = self.run_plan(
                    tip, attempt=2, event=event, publish_android=publish_android
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Repair", result.stdout + result.stderr)
                self.assertEqual(
                    calls,
                    [
                        [
                            "api",
                            f"repos/{REPO}/git/ref/heads/main",
                            "--jq",
                            ".object.sha",
                        ]
                    ],
                )
                self.assertFalse(self.output.exists())
                self.assertFalse((self.checkout / ".git/FETCH_HEAD").exists())

    def test_first_attempt_stale_forward_source_requeues_without_preparation(self):
        tip = self.advance()
        for event, publish_android in (("push", True), ("workflow_dispatch", False)):
            with self.subTest(event=event):
                result, calls = self.run_plan(
                    tip, attempt=1, event=event, publish_android=publish_android
                )
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(self.output.read_text(), "ready=false\n")
                self.assertEqual(
                    calls,
                    [
                        [
                            "api",
                            f"repos/{REPO}/git/ref/heads/main",
                            "--jq",
                            ".object.sha",
                        ],
                        [
                            "workflow",
                            "run",
                            "release.yml",
                            "--repo",
                            REPO,
                            "--ref",
                            "main",
                            "-f",
                            f"publish_android={str(publish_android).lower()}",
                        ],
                    ],
                )

    def test_missing_signing_on_current_main_fails_before_preparation(self):
        for event in ("push", "workflow_dispatch"):
            with self.subTest(event=event):
                result, calls = self.run_plan(
                    self.base, attempt=1, event=event, publish_android=True
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("signing secrets", result.stdout + result.stderr)
                self.assertEqual(
                    calls,
                    [
                        [
                            "api",
                            f"repos/{REPO}/git/ref/heads/main",
                            "--jq",
                            ".object.sha",
                        ]
                    ],
                )
                self.assertFalse(self.output.exists())
                self.assertFalse((self.checkout / ".git/FETCH_HEAD").exists())

    def test_publisher_retry_guard_precedes_preparation_and_allocation(self):
        release_job = self.workflow.split("\n  release:\n", 1)[1]
        names = re.findall(r"(?m)^      - name: (.+)$", release_job)
        self.assertEqual(
            names[:2], [RETRY_NAME, "Commit exactly the validated versioned tree"]
        )
        self.assertLess(
            release_job.index(RETRY_NAME),
            release_job.index("Allocate an unused immutable server version"),
        )
        self.assertIn("RUN_ATTEMPT: ${{ github.run_attempt }}", self.retry_step)
        self.assertIn(
            "RELEASE_SOURCE: ${{ needs.plan.outputs.source }}", self.retry_step
        )

    def test_publisher_retry_after_promotion_fails_without_dispatch(self):
        tip = self.advance()
        result, calls = self.run_plan(
            tip,
            attempt=2,
            event="workflow_dispatch",
            publish_android=False,
            script=self.retry,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Repair", result.stdout + result.stderr)
        self.assertEqual(
            calls, [["api", f"repos/{REPO}/git/ref/heads/main", "--jq", ".object.sha"]]
        )
        self.assertFalse(self.output.exists())
        self.assertFalse((self.checkout / ".git/FETCH_HEAD").exists())

    def test_publisher_retry_unchanged_main_is_allowed(self):
        result, calls = self.run_plan(
            self.base,
            attempt=2,
            event="workflow_dispatch",
            publish_android=False,
            script=self.retry,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(
            calls, [["api", f"repos/{REPO}/git/ref/heads/main", "--jq", ".object.sha"]]
        )
        self.assertFalse(self.output.exists())
        self.assertFalse((self.checkout / ".git/FETCH_HEAD").exists())

    def test_publisher_first_attempt_does_not_query_or_dispatch(self):
        tip = self.advance()
        result, calls = self.run_plan(
            tip, attempt=1, event="push", publish_android=True, script=self.retry
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(calls, [])
        self.assertFalse(self.output.exists())
        self.assertFalse((self.checkout / ".git/FETCH_HEAD").exists())


if __name__ == "__main__":
    unittest.main()
