import base64
import contextlib
import importlib.util
import io
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "promote-release.py"
SPEC = importlib.util.spec_from_file_location("promote_release", SCRIPT)
PROMOTE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROMOTE)
REPO = "owner/repository"
SERVER = "2026.10.2"
ANDROID = "android/2026.10.20.1"


class PromoteReleaseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.remote = self.directory / "remote.git"
        self.root = self.directory / "release"
        self.environment = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith(("GIT_", "GH_", "GITHUB_"))
        }
        # Commits and pushes start detached housekeeping that can still be writing
        # into a repository while cleanup removes it.
        quiet = self.directory / "gitconfig"
        quiet.write_text(
            "[maintenance]\n\tauto = false\n[gc]\n\tauto = 0\n[receive]\n\tautogc = false\n"
        )
        self.environment.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=str(quiet))
        self.run_git(
            self.directory, "init", "--bare", "--initial-branch=main", str(self.remote)
        )
        self.run_git(self.directory, "init", "--initial-branch=main", str(self.root))
        self.configure(self.root)
        (self.root / "README").write_text("synthetic initial source\n")
        self.prior = self.commit(self.root, "initial")
        self.write_versions("2026.10.19.3", 14)
        self.base = self.commit(self.root, "tested base")
        self.run_git(self.root, "remote", "add", "origin", str(self.remote))
        self.run_git(self.root, "push", "origin", "main")
        self.other = self.directory / "other"
        self.run_git(self.directory, "clone", str(self.remote), str(self.other))
        self.configure(self.other)

    def run_git(self, root, *args):
        return subprocess.run(
            ["git", *args],
            cwd=root,
            env=self.environment,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    def configure(self, root):
        self.run_git(root, "config", "user.name", "Release test")
        self.run_git(root, "config", "user.email", "release-test@example.invalid")

    def commit(self, root, message):
        self.run_git(root, "add", "--all")
        self.run_git(root, "commit", "-m", message)
        return self.run_git(root, "rev-parse", "HEAD")

    def write_versions(self, version, code):
        properties = self.root / PROMOTE.PROPERTIES
        recipe = self.root / PROMOTE.RECIPE
        properties.parent.mkdir(parents=True, exist_ok=True)
        recipe.parent.mkdir(parents=True, exist_ok=True)
        properties.write_text(
            f"# retained\nspuntoVersionName={version}\nspuntoVersionCode={code}\n"
        )
        recipe.write_text(
            "License: AGPL-3.0-only\nBuilds:\n"
            f"  - versionName: {version}\n    versionCode: {code}\n"
            f"    commit: android/{version}\n    subdir: android/app\n"
            f"CurrentVersion: {version}\nCurrentVersionCode: {code}\n"
        )

    def prepare(self):
        self.write_versions("2026.10.20.1", 15)
        return self.commit(self.root, "prepare release")

    def prepare_server(self):
        self.run_git(
            self.root, "commit", "--allow-empty", "-m", "prepare server release"
        )
        return self.run_git(self.root, "rev-parse", "HEAD")

    def refs(self):
        lines = self.run_git(
            self.remote, "for-each-ref", "--format=%(refname) %(objectname)"
        )
        return dict(line.split() for line in lines.splitlines())

    def promote(self, android_tag=ANDROID, **kwargs):
        return PROMOTE.promote(
            self.root,
            REPO,
            self.base,
            SERVER,
            android_tag,
            expected_origin=self.remote,
            environment=self.environment,
            **kwargs,
        )

    def advance(self):
        (self.other / "README").write_text("synthetic concurrent change\n")
        head = self.commit(self.other, "concurrent main")
        self.run_git(self.other, "push", "origin", "main")
        return head

    def test_atomic_main_and_two_lightweight_tags_publish_prepared_commit(self):
        head = self.prepare()
        with patch.object(PROMOTE, "dispatch") as dispatch:
            self.assertEqual(self.promote(), {"published": True, "requeued": False})
        dispatch.assert_not_called()
        self.assertEqual(
            self.refs(),
            {
                "refs/heads/main": head,
                f"refs/tags/{SERVER}": head,
                f"refs/tags/{ANDROID}": head,
            },
        )
        self.assertEqual(self.run_git(self.root, "status", "--porcelain"), "")
        for tag in (SERVER, ANDROID):
            self.assertEqual(self.run_git(self.root, "cat-file", "-t", tag), "commit")

    def test_server_only_publishes_identical_tree_child_without_an_android_tag(self):
        head = self.prepare_server()
        self.assertEqual(
            self.promote(None, publish_android=False),
            {"published": True, "requeued": False},
        )
        self.assertEqual(
            self.refs(),
            {"refs/heads/main": head, f"refs/tags/{SERVER}": head},
        )
        self.assertNotEqual(head, self.base)
        self.assertEqual(
            self.run_git(self.root, "rev-parse", "HEAD^{tree}"),
            self.run_git(self.root, "rev-parse", f"{self.base}^{{tree}}"),
        )

    def test_server_only_cannot_use_a_no_op_main_ref(self):
        with self.assertRaisesRegex(PROMOTE.PromotionError, "single direct child"):
            self.promote(None, publish_android=False)
        self.assertEqual(self.refs(), {"refs/heads/main": self.base})

    def test_server_only_child_cannot_change_the_tested_tree(self):
        (self.root / "README").write_text("unreviewed source change\n")
        self.commit(self.root, "changed tree")
        with self.assertRaisesRegex(PROMOTE.PromotionError, "identical tested tree"):
            self.promote(None, publish_android=False)
        self.assertEqual(self.refs(), {"refs/heads/main": self.base})

    def test_concurrent_main_advance_atomically_rejects_every_tag_and_requeues(self):
        head = self.prepare()
        original = PROMOTE.git
        advanced = []

        def race(root, *args, **kwargs):
            if args[0] == "push":
                advanced.append(self.advance())
            return original(root, *args, **kwargs)

        with (
            patch.object(PROMOTE, "git", side_effect=race),
            patch.object(PROMOTE, "dispatch") as dispatch,
        ):
            self.assertEqual(self.promote(), {"published": False, "requeued": True})
        dispatch.assert_called_once_with(REPO, True, self.environment)
        self.assertEqual(self.refs(), {"refs/heads/main": advanced[0]})
        self.assertEqual(self.run_git(self.root, "rev-parse", "HEAD"), head)
        self.assertEqual(self.run_git(self.root, "status", "--porcelain"), "")
        self.assertFalse((self.root / ".git/FETCH_HEAD").exists())

    def test_server_only_race_requeues_false_without_claiming_ancestry_as_publication(
        self,
    ):
        self.prepare_server()
        original = PROMOTE.git
        advanced = []

        def race(root, *args, **kwargs):
            if args[0] == "push":
                advanced.append(self.advance())
            return original(root, *args, **kwargs)

        with (
            patch.object(PROMOTE, "git", side_effect=race),
            patch.object(PROMOTE, "dispatch") as dispatch,
        ):
            result = self.promote(None, publish_android=False)
        self.assertEqual(result, {"published": False, "requeued": True})
        dispatch.assert_called_once_with(REPO, False, self.environment)
        self.assertEqual(self.refs(), {"refs/heads/main": advanced[0]})

    def post_advertisement_hook(self, target):
        hook = self.remote / "hooks/pre-receive"
        hook.write_text(
            "#!/bin/sh\n"
            "env -u GIT_QUARANTINE_PATH -u GIT_OBJECT_DIRECTORY "
            "-u GIT_ALTERNATE_OBJECT_DIRECTORIES "
            f'git --git-dir="{self.remote}" update-ref refs/heads/main {target} {self.base}\n'
        )
        hook.chmod(0o700)

    def test_server_only_post_advertisement_forward_race_publishes_zero_tags(self):
        head = self.prepare_server()
        advanced = self.advance()
        self.run_git(self.remote, "update-ref", "refs/heads/main", self.base, advanced)
        self.post_advertisement_hook(advanced)
        with patch.object(PROMOTE, "dispatch") as dispatch:
            self.assertEqual(
                self.promote(None, publish_android=False),
                {"published": False, "requeued": True},
            )
        dispatch.assert_called_once_with(REPO, False, self.environment)
        self.assertEqual(self.refs(), {"refs/heads/main": advanced})
        self.assertEqual(self.run_git(self.root, "rev-parse", "HEAD"), head)

    def test_server_only_post_advertisement_rewind_publishes_zero_tags_and_fails(self):
        self.prepare_server()
        self.post_advertisement_hook(self.prior)
        with (
            patch.object(PROMOTE, "dispatch") as dispatch,
            self.assertRaises(PROMOTE.PromotionError),
        ):
            self.promote(None, publish_android=False)
        dispatch.assert_not_called()
        self.assertEqual(self.refs(), {"refs/heads/main": self.prior})

    def test_tag_rejecting_hook_denies_entire_transaction_without_requeue(self):
        self.prepare()
        hook = self.remote / "hooks/update"
        hook.write_text('#!/bin/sh\ncase "$1" in refs/tags/*) exit 1;; esac\nexit 0\n')
        hook.chmod(0o755)
        with (
            patch.object(PROMOTE, "dispatch") as dispatch,
            self.assertRaises(PROMOTE.PromotionError),
        ):
            self.promote()
        dispatch.assert_not_called()
        self.assertEqual(self.refs(), {"refs/heads/main": self.base})

    def test_failed_transport_after_actual_push_verifies_all_tags_and_main(self):
        head = self.prepare()
        original = PROMOTE.git

        def ambiguous(root, *args, **kwargs):
            result = original(root, *args, **kwargs)
            if args[0] == "push":
                self.assertEqual(result.returncode, 0)
                return subprocess.CompletedProcess(
                    args, 1, b"", b"synthetic connection closed"
                )
            return result

        with (
            patch.object(PROMOTE, "git", side_effect=ambiguous),
            patch.object(PROMOTE, "dispatch") as dispatch,
        ):
            self.assertEqual(self.promote(), {"published": True, "requeued": False})
        dispatch.assert_not_called()
        self.assertEqual(set(self.refs().values()), {head})

    def test_main_ancestry_without_complete_matching_tags_never_confirms_android(self):
        self.prepare()
        original = PROMOTE.git

        def main_only(root, *args, **kwargs):
            if args[0] == "push":
                self.run_git(self.root, "push", "origin", "HEAD:refs/heads/main")
                return subprocess.CompletedProcess(
                    args, 1, b"", b"synthetic partial result"
                )
            return original(root, *args, **kwargs)

        with (
            patch.object(PROMOTE, "git", side_effect=main_only),
            patch.object(PROMOTE, "dispatch") as dispatch,
            self.assertRaises(PROMOTE.PromotionError),
        ):
            self.promote()
        dispatch.assert_not_called()
        self.assertEqual(list(self.refs()), ["refs/heads/main"])

    def test_partial_or_mismatched_remote_tag_refuses_recovery(self):
        self.prepare()
        original = PROMOTE.git
        for value in ("HEAD", self.base):
            with self.subTest(value=value):

                def partial(root, *args, value=value, **kwargs):
                    if args[0] == "push":
                        self.run_git(
                            self.root, "push", "origin", f"{value}:refs/tags/{SERVER}"
                        )
                        return subprocess.CompletedProcess(
                            args, 1, b"", b"synthetic lost response"
                        )
                    return original(root, *args, **kwargs)

                with (
                    patch.object(PROMOTE, "git", side_effect=partial),
                    patch.object(PROMOTE, "dispatch") as dispatch,
                    self.assertRaises(PROMOTE.PromotionError),
                ):
                    self.promote()
                dispatch.assert_not_called()
                self.run_git(self.remote, "update-ref", "-d", f"refs/tags/{SERVER}")

    def test_hard_rewound_remote_main_cannot_trigger_requeue(self):
        self.prepare()
        original = PROMOTE.git

        def rewind(root, *args, **kwargs):
            if args[0] == "push":
                self.run_git(self.remote, "update-ref", "refs/heads/main", self.prior)
                return subprocess.CompletedProcess(
                    args, 1, b"", b"synthetic rejected push"
                )
            return original(root, *args, **kwargs)

        with (
            patch.object(PROMOTE, "git", side_effect=rewind),
            patch.object(PROMOTE, "dispatch") as dispatch,
            self.assertRaises(PROMOTE.PromotionError),
        ):
            self.promote()
        dispatch.assert_not_called()
        self.assertEqual(self.refs(), {"refs/heads/main": self.prior})

    def test_real_rewind_between_snapshot_and_push_rejects_main_and_all_tags(self):
        self.prepare()
        original = PROMOTE.git

        def rewind(root, *args, **kwargs):
            if args[0] == "push":
                self.run_git(self.remote, "update-ref", "refs/heads/main", self.prior)
            return original(root, *args, **kwargs)

        with (
            patch.object(PROMOTE, "git", side_effect=rewind),
            patch.object(PROMOTE, "dispatch") as dispatch,
            self.assertRaises(PROMOTE.PromotionError),
        ):
            self.promote()
        dispatch.assert_not_called()
        self.assertEqual(self.refs(), {"refs/heads/main": self.prior})

    def test_matching_tags_without_main_ancestry_do_not_confirm_publication(self):
        self.prepare()
        original = PROMOTE.git

        def tags_only(root, *args, **kwargs):
            if args[0] == "push":
                self.run_git(
                    self.root,
                    "push",
                    "origin",
                    f"HEAD:refs/tags/{SERVER}",
                    f"HEAD:refs/tags/{ANDROID}",
                )
                return subprocess.CompletedProcess(
                    args, 1, b"", b"synthetic lost response"
                )
            return original(root, *args, **kwargs)

        with (
            patch.object(PROMOTE, "git", side_effect=tags_only),
            patch.object(PROMOTE, "dispatch") as dispatch,
            self.assertRaises(PROMOTE.PromotionError),
        ):
            self.promote()
        dispatch.assert_not_called()
        self.assertEqual(self.refs()["refs/heads/main"], self.base)

    def test_network_failure_with_unchanged_main_does_not_requeue(self):
        self.prepare()
        original = PROMOTE.git

        def fail_push(root, *args, **kwargs):
            if args[0] == "push":
                return subprocess.CompletedProcess(
                    args, 1, b"", b"synthetic network failure"
                )
            return original(root, *args, **kwargs)

        with (
            patch.object(PROMOTE, "git", side_effect=fail_push),
            patch.object(PROMOTE, "dispatch") as dispatch,
            self.assertRaises(PROMOTE.PromotionError),
        ):
            self.promote()
        dispatch.assert_not_called()
        self.assertEqual(self.refs(), {"refs/heads/main": self.base})

    def test_failed_fresh_remote_lookup_cannot_dispatch_or_confirm(self):
        self.prepare()
        original = PROMOTE.git
        failed = []

        def disconnected(root, *args, **kwargs):
            if args[0] == "push":
                self.advance()
                failed.append(True)
                return subprocess.CompletedProcess(
                    args, 1, b"", b"synthetic network failure"
                )
            if args[0] == "ls-remote" and failed:
                raise subprocess.CalledProcessError(
                    1, "git", stderr=b"synthetic permission error"
                )
            return original(root, *args, **kwargs)

        with (
            patch.object(PROMOTE, "git", side_effect=disconnected),
            patch.object(PROMOTE, "dispatch") as dispatch,
            self.assertRaises(subprocess.CalledProcessError),
        ):
            self.promote()
        dispatch.assert_not_called()

    def test_preexisting_main_race_requeues_without_attempting_a_stale_push(self):
        self.prepare()
        advanced = self.advance()
        original = PROMOTE.git
        with (
            patch.object(PROMOTE, "git", wraps=original) as calls,
            patch.object(PROMOTE, "dispatch") as dispatch,
        ):
            self.assertEqual(self.promote(), {"published": False, "requeued": True})
        self.assertFalse(any(c.args[1] == "push" for c in calls.call_args_list))
        dispatch.assert_called_once_with(REPO, True, self.environment)
        self.assertEqual(self.refs(), {"refs/heads/main": advanced})

    def test_version_only_content_cannot_hide_a_file_mode_change(self):
        self.prepare()
        properties = self.root / PROMOTE.PROPERTIES
        properties.chmod(0o755)
        self.run_git(self.root, "add", "--all")
        self.run_git(self.root, "commit", "--amend", "--no-edit")
        with self.assertRaisesRegex(PROMOTE.PromotionError, "file modes"):
            self.promote()
        self.assertEqual(self.refs(), {"refs/heads/main": self.base})

    def test_existing_remote_tag_even_at_expected_commit_fails_before_push(self):
        head = self.prepare()
        self.run_git(self.root, "push", "origin", f"{head}:refs/tags/{SERVER}")
        original = PROMOTE.git
        with (
            patch.object(PROMOTE, "git", wraps=original) as calls,
            patch.object(PROMOTE, "dispatch") as dispatch,
            self.assertRaisesRegex(PROMOTE.PromotionError, "already exists"),
        ):
            self.promote()
        self.assertFalse(any(c.args[1] == "push" for c in calls.call_args_list))
        dispatch.assert_not_called()

    def test_non_version_content_and_unrelated_files_are_rejected(self):
        for filename in (PROMOTE.PROPERTIES, PROMOTE.RECIPE, "README"):
            with self.subTest(filename=filename):
                self.run_git(self.root, "reset", "--hard", self.base)
                self.prepare()
                path = self.root / filename
                path.write_text(path.read_text() + "# unrelated edit\n")
                self.run_git(self.root, "add", "--all")
                self.run_git(self.root, "commit", "--amend", "--no-edit")
                with self.assertRaises(PROMOTE.PromotionError):
                    self.promote()
                self.assertEqual(self.refs(), {"refs/heads/main": self.base})

    def test_production_origin_rejects_local_and_mismatched_push_urls(self):
        self.prepare()
        with self.assertRaisesRegex(PROMOTE.PromotionError, "Origin does not match"):
            PROMOTE.promote(
                self.root,
                REPO,
                self.base,
                SERVER,
                ANDROID,
                environment={**self.environment, "GH_TOKEN": "synthetic-auth-value"},
            )
        self.run_git(
            self.root, "remote", "set-url", "--push", "origin", str(self.other)
        )
        with self.assertRaisesRegex(PROMOTE.PromotionError, "Origin does not match"):
            self.promote()
        self.assertEqual(self.refs(), {"refs/heads/main": self.base})

    def test_git_credentials_append_config_without_mutating_source_environment(self):
        self.prepare()
        token = "synthetic-auth-value"
        environment = {
            **self.environment,
            "GH_TOKEN": token,
            "GIT_CONFIG_COUNT": "1",
            "GIT_CONFIG_KEY_0": "advice.detachedHead",
            "GIT_CONFIG_VALUE_0": "false",
            "GIT_TRACE_CURL": "1",
            "GIT_CURL_VERBOSE": "1",
        }
        before = dict(environment)
        original = PROMOTE.git
        seen = []

        def inspect(root, *args, **kwargs):
            seen.append((args, dict(kwargs["env"])))
            return original(root, *args, **kwargs)

        stdout, stderr = io.StringIO(), io.StringIO()
        with (
            patch.object(PROMOTE, "git", side_effect=inspect),
            contextlib.redirect_stdout(stdout),
            contextlib.redirect_stderr(stderr),
        ):
            PROMOTE.promote(
                self.root,
                REPO,
                self.base,
                SERVER,
                ANDROID,
                expected_origin=self.remote,
                environment=environment,
            )
        self.assertEqual(environment, before)
        encoded = base64.b64encode(f"x-access-token:{token}".encode()).decode()
        for args, env in seen:
            self.assertEqual(env["GIT_CONFIG_COUNT"], "3")
            self.assertEqual(env["GIT_CONFIG_KEY_0"], "advice.detachedHead")
            self.assertEqual(env["GIT_CONFIG_VALUE_0"], "false")
            self.assertEqual(env["GIT_CONFIG_VALUE_1"], "")
            self.assertEqual(
                env["GIT_CONFIG_VALUE_2"], f"AUTHORIZATION: basic {encoded}"
            )
            self.assertNotIn("GIT_TRACE_CURL", env)
            self.assertNotIn("GIT_CURL_VERBOSE", env)
            self.assertNotIn(token, " ".join(args))
            if args[0] == "push":
                self.assertIn("--atomic", args)
                self.assertIn(f"--force-with-lease=refs/heads/main:{self.base}", args)
                self.assertNotIn("--force", args)
                self.assertFalse(any(value.startswith("+") for value in args))
        for text in (
            stdout.getvalue(),
            stderr.getvalue(),
            (self.root / ".git/config").read_text(),
            (self.remote / "config").read_text(),
        ):
            self.assertNotIn(token, text)
            self.assertNotIn(encoded, text)
            self.assertNotIn("extraheader", text)

    def test_invalid_inherited_config_and_auth_are_rejected(self):
        for environment in (
            {"GIT_CONFIG_COUNT": "-1"},
            {"GIT_CONFIG_COUNT": "one"},
            {"GIT_CONFIG_COUNT": "1"},
            {"GH_TOKEN": "synthetic\nvalue"},
        ):
            with (
                self.subTest(environment=environment),
                self.assertRaises(PROMOTE.PromotionError),
            ):
                PROMOTE.git_environment(environment)

    def test_cli_failure_does_not_print_child_diagnostics_or_credentials(self):
        error = subprocess.CalledProcessError(
            1, "synthetic command", stderr=b"synthetic-auth-value"
        )
        stdout, stderr = io.StringIO(), io.StringIO()
        with (
            patch(
                "sys.argv",
                [
                    str(SCRIPT),
                    "--repo",
                    REPO,
                    "--base-source",
                    self.base,
                    "--server-tag",
                    SERVER,
                ],
            ),
            patch.object(PROMOTE, "promote", side_effect=error),
            contextlib.redirect_stdout(stdout),
            contextlib.redirect_stderr(stderr),
        ):
            self.assertEqual(PROMOTE.main(), 1)
        self.assertEqual(stdout.getvalue(), "")
        self.assertNotIn("synthetic-auth-value", stderr.getvalue())
        self.assertIn("no publication confirmed", stderr.getvalue())

    def test_requeue_command_uses_explicit_repo_main_and_boolean(self):
        with patch.object(PROMOTE.subprocess, "run") as run:
            PROMOTE.dispatch(REPO, False, self.environment)
        self.assertEqual(
            run.call_args.args[0],
            [
                "gh",
                "workflow",
                "run",
                "release.yml",
                "--repo",
                REPO,
                "--ref",
                "main",
                "-f",
                "publish_android=false",
            ],
        )
        self.assertTrue(run.call_args.kwargs["capture_output"])


if __name__ == "__main__":
    unittest.main()
