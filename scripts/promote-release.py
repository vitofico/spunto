#!/usr/bin/env python3
"""Atomically promote a tested release commit, or requeue after a main race."""

import argparse
import base64
import datetime
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROPERTIES = "android/gradle.properties"
RECIPE = "distribution/fdroid/metadata/io.theficos.spunto.yml"
ZERO = "0" * 40


class PromotionError(ValueError):
    pass


def git_environment(environment):
    result = dict(environment)
    # Traces can expose HTTP headers or write them to a file outside capture_output.
    for key in list(result):
        if key.startswith("GIT_TRACE") or key == "GIT_CURL_VERBOSE":
            del result[key]
    count = result.get("GIT_CONFIG_COUNT", "0")
    if not re.fullmatch(r"0|[1-9][0-9]*", count):
        raise PromotionError("Invalid inherited Git configuration count.")
    count = int(count)
    if count > 1000 or any(
        f"GIT_CONFIG_KEY_{i}" not in result or f"GIT_CONFIG_VALUE_{i}" not in result
        for i in range(count)
    ):
        raise PromotionError("Incomplete inherited Git configuration.")
    token = result.get("GH_TOKEN")
    if token:
        if any(ord(character) < 33 or ord(character) == 127 for character in token):
            raise PromotionError("Invalid GitHub authentication environment.")
        encoded = base64.b64encode(f"x-access-token:{token}".encode()).decode()
        # Clear inherited headers for this host before supplying the task token.
        for value in ("", f"AUTHORIZATION: basic {encoded}"):
            result[f"GIT_CONFIG_KEY_{count}"] = "http.https://github.com/.extraheader"
            result[f"GIT_CONFIG_VALUE_{count}"] = value
            count += 1
    result["GIT_CONFIG_COUNT"] = str(count)
    result["GIT_TERMINAL_PROMPT"] = "0"
    return result


def git(root, *args, env, check=True):
    return subprocess.run(
        ["git", *args], cwd=root, env=env, capture_output=True, check=check
    )


def output(root, *args, env):
    return git(root, *args, env=env).stdout.decode().strip()


def validate_inputs(repo, base, server_tag, android_tag):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*", repo):
        raise PromotionError("Use an explicit owner/repository.")
    if not re.fullmatch(r"[0-9a-f]{40}", base):
        raise PromotionError("The tested base must be a full commit SHA.")
    if not re.fullmatch(r"[0-9]{4}\.(?:0[1-9]|1[0-2])\.(?:0|[1-9][0-9]*)", server_tag):
        raise PromotionError("Use a YYYY.MM.MICRO server tag.")
    if android_tag is not None:
        android_version(android_tag)


def android_version(tag):
    match = re.fullmatch(
        r"android/([0-9]{4})\.([0-9]{2})\.([0-9]{2})\.([1-9][0-9]*)", tag
    )
    if not match:
        raise PromotionError("Use an android/YYYY.MM.DD.RUN tag.")
    version = tuple(map(int, match.groups()))
    try:
        datetime.date(*version[:3])
    except ValueError:
        raise PromotionError("The Android tag must contain a valid date.") from None
    return version


def verify_origin(root, repo, env, expected_origin):
    for mode in ((), ("--push",)):
        urls = output(
            root, "remote", "get-url", *mode, "--all", "origin", env=env
        ).splitlines()
        if len(urls) != 1:
            raise PromotionError("Origin must have exactly one fetch and push URL.")
        if expected_origin is not None:
            matches = urls[0] == str(expected_origin)
        else:
            matches = urls[0].casefold() in {
                f"https://github.com/{repo}".casefold(),
                f"https://github.com/{repo}.git".casefold(),
            }
        if not matches:
            raise PromotionError(
                "Origin does not match the published GitHub repository."
            )


def version_fields(text, values):
    for prefix, expected in values:
        matches = list(
            re.finditer(r"(?m)^" + re.escape(prefix) + r"([^\r\n]*)\r?$", text)
        )
        if len(matches) != 1 or matches[0][1] != expected:
            raise PromotionError("Release version fields do not match their source.")
        start, end = matches[0].span(1)
        text = text[:start] + "<version>" + text[end:]
    return text


def verify_prepared_commit(root, base, android_tag, env):
    head = output(root, "rev-parse", "--verify", "HEAD^{commit}", env=env)
    if output(root, "rev-parse", "--verify", f"{base}^{{commit}}", env=env) != base:
        raise PromotionError("The tested base must identify a commit.")
    if git(root, "diff", "--quiet", "HEAD", "--", env=env, check=False).returncode:
        raise PromotionError("The release checkout has tracked changes.")
    # Git omits a no-op main update and its lease; every release needs a real child.
    if output(root, "rev-list", "--parents", "-n", "1", head, env=env).split() != [
        head,
        base,
    ]:
        raise PromotionError(
            "Every release must be a single direct child of the tested base."
        )
    if android_tag is None:
        if output(root, "rev-parse", f"{head}^{{tree}}", env=env) != output(
            root, "rev-parse", f"{base}^{{tree}}", env=env
        ):
            raise PromotionError(
                "A server-only release must preserve the identical tested tree."
            )
        return head
    changed = output(
        root, "diff-tree", "--no-commit-id", "--name-only", "-r", head, env=env
    ).splitlines()
    if set(changed) != {PROPERTIES, RECIPE}:
        raise PromotionError("Only the Android and F-Droid version files may change.")
    for line in output(
        root, "diff", "--raw", "--no-abbrev", base, head, "--", env=env
    ).splitlines():
        fields = line.split("\t", 1)[0].split()
        if (
            fields[0] != ":" + fields[1]
            or fields[1] not in ("100644", "100755")
            or fields[-1] != "M"
        ):
            raise PromotionError(
                "Release preparation must preserve regular file modes."
            )
    before = git(root, "show", f"{base}:{PROPERTIES}", env=env).stdout.decode()
    after = git(root, "show", f"{head}:{PROPERTIES}", env=env).stdout.decode()
    old_name = re.findall(r"(?m)^spuntoVersionName=([^\r\n]+)\r?$", before)
    old_code = re.findall(r"(?m)^spuntoVersionCode=([0-9]+)\r?$", before)
    if len(old_name) != 1 or len(old_code) != 1:
        raise PromotionError("The tested Android version is invalid.")
    name = android_tag.removeprefix("android/")
    if android_version(android_tag) <= android_version(f"android/{old_name[0]}"):
        raise PromotionError("The Android version must increase.")
    code = str(int(old_code[0]) + 1)
    if int(code) > 2_100_000_000:
        raise PromotionError("The Android version code exceeds its supported range.")
    old_fields = [
        ("spuntoVersionName=", old_name[0]),
        ("spuntoVersionCode=", old_code[0]),
    ]
    new_fields = [("spuntoVersionName=", name), ("spuntoVersionCode=", code)]
    if version_fields(before, old_fields) != version_fields(after, new_fields):
        raise PromotionError("Android preparation changed non-version content.")
    old_fields = [
        ("  - versionName: ", old_name[0]),
        ("    versionCode: ", old_code[0]),
        ("    commit: ", f"android/{old_name[0]}"),
        ("CurrentVersion: ", old_name[0]),
        ("CurrentVersionCode: ", old_code[0]),
    ]
    new_fields = [
        ("  - versionName: ", name),
        ("    versionCode: ", code),
        ("    commit: ", android_tag),
        ("CurrentVersion: ", name),
        ("CurrentVersionCode: ", code),
    ]
    before = git(root, "show", f"{base}:{RECIPE}", env=env).stdout.decode()
    after = git(root, "show", f"{head}:{RECIPE}", env=env).stdout.decode()
    if version_fields(before, old_fields) != version_fields(after, new_fields):
        raise PromotionError("F-Droid preparation changed non-version content.")
    return head


def remote_refs(root, tags, env):
    wanted = {"refs/heads/main", *(f"refs/tags/{tag}" for tag in tags)}
    refs = {}
    for line in output(
        root, "ls-remote", "--refs", "origin", *sorted(wanted), env=env
    ).splitlines():
        fields = line.split("\t")
        if (
            len(fields) != 2
            or fields[1] not in wanted
            or fields[1] in refs
            or not re.fullmatch(r"[0-9a-f]{40}", fields[0])
        ):
            raise PromotionError("The remote ref response is invalid.")
        refs[fields[1]] = fields[0]
    if "refs/heads/main" not in refs:
        raise PromotionError("The remote main branch is missing.")
    return refs


def ancestor(root, before, after, env):
    result = git(
        root, "merge-base", "--is-ancestor", before, after, env=env, check=False
    )
    if result.returncode not in (0, 1):
        raise PromotionError("Unable to verify remote main ancestry.")
    return result.returncode == 0


def dispatch(repo, publish_android, environment):
    subprocess.run(
        [
            "gh",
            "workflow",
            "run",
            "release.yml",
            "--repo",
            repo,
            "--ref",
            "main",
            "-f",
            f"publish_android={str(publish_android).lower()}",
        ],
        env=environment,
        capture_output=True,
        check=True,
    )


def recover(
    root, repo, base, head, tags, env, publish_android, environment, *, attempted=True
):
    refs = remote_refs(root, tags, env)
    remote_main = refs["refs/heads/main"]
    # Fetch only the advertised commit: no checkout, rebase, tracking ref or FETCH_HEAD.
    git(
        root,
        "fetch",
        "--no-tags",
        "--no-write-fetch-head",
        "--refmap=",
        "origin",
        remote_main,
        env=env,
    )
    if remote_refs(root, tags, env) != refs:
        raise PromotionError("Remote refs changed during recovery verification.")
    present = [refs.get(f"refs/tags/{tag}") for tag in tags]
    if (
        attempted
        and all(value == head for value in present)
        and ancestor(root, head, remote_main, env)
    ):
        return {"published": True, "requeued": False}
    if any(value is not None for value in present):
        raise PromotionError(
            "Remote release tags are partial or inconsistent; refusing recovery."
        )
    if (
        remote_main != base
        and ancestor(root, base, remote_main, env)
        and not ancestor(root, head, remote_main, env)
    ):
        dispatch(repo, publish_android, environment)
        return {"published": False, "requeued": True}
    raise PromotionError("Promotion failed without a verified fast-forward main race.")


def promote(
    root,
    repo,
    base,
    server_tag,
    android_tag=None,
    publish_android=True,
    *,
    expected_origin=None,
    environment=None,
):
    validate_inputs(repo, base, server_tag, android_tag)
    environment = dict(os.environ if environment is None else environment)
    if expected_origin is None and not environment.get("GH_TOKEN"):
        raise PromotionError("GH_TOKEN is required for release promotion.")
    env = git_environment(environment)
    verify_origin(root, repo, env, expected_origin)
    head = verify_prepared_commit(root, base, android_tag, env)
    tags = [server_tag, *([android_tag] if android_tag else [])]
    refs = remote_refs(root, tags, env)
    if any(f"refs/tags/{tag}" in refs for tag in tags):
        raise PromotionError("A release tag already exists; use the repair workflow.")
    if refs["refs/heads/main"] != base:
        return recover(
            root,
            repo,
            base,
            head,
            tags,
            env,
            publish_android,
            environment,
            attempted=False,
        )
    for tag in tags:
        ref = f"refs/tags/{tag}"
        existing = git(
            root, "show-ref", "--verify", "--quiet", ref, env=env, check=False
        )
        if existing.returncode == 1:
            git(root, "update-ref", ref, head, ZERO, env=env)
        elif (
            existing.returncode != 0
            or output(root, "rev-parse", "--verify", ref, env=env) != head
        ):
            raise PromotionError(
                "A local release tag does not point to the prepared commit."
            )
    if output(root, "rev-parse", "--verify", "HEAD", env=env) != head:
        raise PromotionError("The prepared HEAD changed before promotion.")
    result = git(
        root,
        "push",
        "--atomic",
        f"--force-with-lease=refs/heads/main:{base}",
        "origin",
        "HEAD:refs/heads/main",
        *(f"refs/tags/{tag}:refs/tags/{tag}" for tag in tags),
        env=env,
        check=False,
    )
    if result.returncode == 0:
        return {"published": True, "requeued": False}
    return recover(root, repo, base, head, tags, env, publish_android, environment)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--base-source", required=True)
    parser.add_argument("--server-tag", required=True)
    parser.add_argument("--android-tag")
    parser.add_argument("--publish-android", choices=("true", "false"), default="true")
    args = parser.parse_args()
    try:
        result = promote(
            ROOT,
            args.repo,
            args.base_source,
            args.server_tag,
            args.android_tag,
            args.publish_android == "true",
        )
        print(json.dumps(result))
    except PromotionError as error:
        print(f"Release promotion failed: {error}", file=sys.stderr)
        return 1
    except (OSError, UnicodeError, subprocess.CalledProcessError):
        print(
            "Release promotion failed: Git or GitHub command failed; no publication confirmed.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
