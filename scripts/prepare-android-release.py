#!/usr/bin/env python3
"""Advance the Android source identity and its matching upstream F-Droid recipe."""

import argparse
import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = importlib.util.spec_from_file_location(
    "android_version", Path(__file__).with_name("android-version.py")
)
VERSION = importlib.util.module_from_spec(MODULE)
MODULE.loader.exec_module(VERSION)
PROPERTIES = Path("android/gradle.properties")
RECIPE = Path("distribution/fdroid/metadata/io.theficos.spunto.yml")


def check_tags(root, current):
    refs = subprocess.run(
        ["git", "for-each-ref", "--format=%(refname)", "refs/tags/android/"],
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.splitlines()
    history = []
    for ref in refs:
        source = subprocess.run(
            ["git", "show", f"{ref}:android/gradle.properties"],
            cwd=root,
            capture_output=True,
            text=True,
            check=True,
        ).stdout
        previous = VERSION.read_version(source)
        if ref != f"refs/tags/{previous['tag']}":
            raise ValueError(f"Android tag does not match its source version: {ref}.")
        history.append(previous)
    VERSION.check_history(current, history)


def prepare_recipe(text, previous, current):
    fields = [
        ("  - versionName: ", previous["versionName"], current["versionName"]),
        (
            "    versionCode: ",
            str(previous["versionCode"]),
            str(current["versionCode"]),
        ),
        ("    commit: ", previous["tag"], current["tag"]),
        ("CurrentVersion: ", previous["versionName"], current["versionName"]),
        (
            "CurrentVersionCode: ",
            str(previous["versionCode"]),
            str(current["versionCode"]),
        ),
    ]
    # Support the checked-in single-build format, preserving every unrelated byte.
    builds = list(re.finditer(r"(?m)^Builds:[ \t]*\r?$", text))
    keys = re.findall(
        r"(?m)^[ \t]*(?:-[ \t]+)?[\"']?"
        r"(versionName|versionCode|commit|CurrentVersion|CurrentVersionCode)[\"']?[ \t]*:",
        text,
    )
    if len(builds) != 1 or len(keys) != 5 or len(set(keys)) != 5:
        raise ValueError(
            "F-Droid recipe must have one build and exactly five version fields."
        )
    end = re.search(r"(?m)^\S", text[builds[0].end() :])
    build_end = builds[0].end() + end.start() if end else len(text)
    if len(re.findall(r"(?m)^  - ", text[builds[0].end() : build_end])) != 1:
        raise ValueError("F-Droid recipe must contain exactly one build.")
    replacements = []
    for index, (prefix, old, new) in enumerate(fields):
        matches = list(
            re.finditer(r"(?m)^" + re.escape(prefix) + r"([^\r\n]*)\r?$", text)
        )
        if len(matches) != 1 or matches[0][1] != old:
            raise ValueError(
                "F-Droid recipe version fields must match the Android source."
            )
        match = matches[0]
        if index < 3 and not builds[0].end() <= match.start() < build_end:
            raise ValueError(
                "F-Droid build version fields must belong to its Builds block."
            )
        replacements.append((match.start(1), match.end(1), new))
    for start, end, new in sorted(replacements, reverse=True):
        text = text[:start] + new + text[end:]
    return text


def write_files(changes):
    staged = []
    replaced = []
    try:
        for target, before, after in changes:
            pair = []
            for content in (before, after):
                with tempfile.NamedTemporaryFile(
                    prefix=".spunto-release-", dir=target.parent, delete=False
                ) as temporary:
                    filename = Path(temporary.name)
                    staged.append(filename)
                    pair.append(filename)
                    temporary.write(content)
                    os.fchmod(temporary.fileno(), target.stat().st_mode & 0o777)
            pair.append(target)
            replaced.append(pair)
        if any(target.read_bytes() != before for target, before, _ in changes):
            raise ValueError("Release inputs changed while preparing the version.")
        applied = []
        try:
            for backup, prepared, target in replaced:
                os.replace(prepared, target)
                applied.append((backup, target))
        except OSError:
            for backup, target in reversed(applied):
                os.replace(backup, target)
            raise
    finally:
        for filename in staged:
            filename.unlink(missing_ok=True)


def prepare(root, date_text):
    paths = [root / PROPERTIES, root / RECIPE]
    originals = [path.read_bytes() for path in paths]
    previous = VERSION.read_version(originals[0].decode())
    properties = VERSION.advance(originals[0].decode(), date_text)
    current = VERSION.read_version(properties)
    VERSION.check_history(current, [previous])
    check_tags(root, current)
    recipe = prepare_recipe(originals[1].decode(), previous, current)
    write_files(list(zip(paths, originals, [properties.encode(), recipe.encode()])))
    return current


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date", required=True, metavar="YYYY-MM-DD")
    parser.add_argument(
        "--root", type=Path, default=ROOT, help="checkout with fetched android/* tags"
    )
    args = parser.parse_args()
    try:
        print(json.dumps(prepare(args.root.resolve(), args.date)))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Android release preparation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
