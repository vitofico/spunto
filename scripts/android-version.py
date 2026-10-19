#!/usr/bin/env python3
"""Read, advance and check the source-controlled Android CalVer identity."""

import argparse
import datetime
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROPERTIES = ROOT / "android/gradle.properties"
VERSION_PATTERN = re.compile(r"([0-9]{4})\.([0-9]{2})\.([0-9]{2})\.([1-9][0-9]*)")
MAX_CODE = 2_100_000_000
# APK 2026.10.15.7 is reserved for local delivery; public builds must advance.
LOCAL_DELIVERY = ("2026.10.15.7", 7)


def calendar_version(name):
    match = VERSION_PATTERN.fullmatch(name)
    if match is None:
        raise ValueError(
            "Android version must use CalVer YYYY.MM.DD.<run>, starting at run 1."
        )
    year, month, day, run = map(int, match.groups())
    return datetime.date(year, month, day), run


def read_version(text):
    values = {}
    for line in text.splitlines():
        key, separator, value = line.strip().partition("=")
        key = key.strip()
        if separator and key in {"spuntoVersionName", "spuntoVersionCode"}:
            if key in values:
                raise ValueError(f"Duplicate {key} in android/gradle.properties.")
            values[key] = value.strip()
    if len(values) != 2:
        raise ValueError(
            "Set both Android version properties in android/gradle.properties."
        )
    name = values["spuntoVersionName"]
    calendar_version(name)
    raw_code = values["spuntoVersionCode"]
    if re.fullmatch(r"[1-9][0-9]*", raw_code) is None or int(raw_code) > MAX_CODE:
        raise ValueError("Android versionCode must be an integer from 1 to 2100000000.")
    return {"versionName": name, "versionCode": int(raw_code), "tag": f"android/{name}"}


def advance(text, date_text):
    current = read_version(text)
    previous_date, previous_run = calendar_version(current["versionName"])
    target_date = datetime.date.fromisoformat(date_text)
    if target_date.isoformat() != date_text:
        raise ValueError("Use an explicit date in YYYY-MM-DD format.")
    if target_date < previous_date:
        raise ValueError(
            "The next Android release date cannot precede the current version."
        )
    code = current["versionCode"] + 1
    if code > MAX_CODE:
        raise ValueError("Android versionCode has reached its maximum.")
    run = previous_run + 1 if target_date == previous_date else 1
    name = f"{target_date.year:04d}.{target_date.month:02d}.{target_date.day:02d}.{run}"
    result = re.sub(
        r"(?m)^([ \t]*spuntoVersionName[ \t]*=)[^\r\n]*$",
        lambda match: match[1] + name,
        text,
    )
    result = re.sub(
        r"(?m)^([ \t]*spuntoVersionCode[ \t]*=)[^\r\n]*$",
        lambda match: match[1] + str(code),
        result,
    )
    return result


def check_history(current, history):
    current_calendar = calendar_version(current["versionName"])
    for previous in history:
        if current["versionCode"] <= previous["versionCode"]:
            raise ValueError(
                "Android versionCode must exceed every previous Android release."
            )
        if current_calendar <= calendar_version(previous["versionName"]):
            raise ValueError(
                "Android CalVer must exceed every previous Android release."
            )
    floor_name, floor_code = LOCAL_DELIVERY
    if current["versionCode"] <= floor_code or current_calendar <= calendar_version(
        floor_name
    ):
        raise ValueError(
            "Prepare a new Android CalVer and code after the APK already delivered locally."
        )


def check_tags(current):
    result = subprocess.run(
        ["git", "for-each-ref", "--format=%(refname)", "refs/tags/android/"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    history = []
    for ref in result.stdout.splitlines():
        previous = subprocess.run(
            ["git", "show", f"{ref}:android/gradle.properties"],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
        version = read_version(previous.stdout)
        if ref != f"refs/tags/{version['tag']}":
            raise ValueError(f"Android tag does not match its source version: {ref}.")
        history.append(version)
    check_history(current, history)


def check_badging(current, text):
    package = next(
        (line for line in text.splitlines() if line.startswith("package: ")), ""
    )
    values = dict(
        re.findall(r"(?:^|[ \t])(name|versionCode|versionName)='([^']*)'", package)
    )
    expected = {
        "name": "io.theficos.spunto",
        "versionCode": str(current["versionCode"]),
        "versionName": current["versionName"],
    }
    if values != expected:
        raise ValueError(
            "Built APK package/version differs from android/gradle.properties."
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--bump", metavar="YYYY-MM-DD", help="advance the source version for this date"
    )
    parser.add_argument(
        "--check-tags",
        action="store_true",
        help="check all fetched android/* tags before publication",
    )
    parser.add_argument(
        "--apk-badging",
        type=Path,
        help="check aapt dump badging output against the source version",
    )
    args = parser.parse_args()
    if args.bump and (args.check_tags or args.apk_badging):
        parser.error("Bump and verification are separate steps.")
    try:
        text = PROPERTIES.read_text()
        if args.bump:
            text = advance(text, args.bump)
        current = read_version(text)
        if args.check_tags:
            check_tags(current)
        if args.apk_badging:
            check_badging(current, args.apk_badging.read_text())
        if args.bump:
            PROPERTIES.write_text(text)
        print(json.dumps(current))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
