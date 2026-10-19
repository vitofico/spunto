#!/usr/bin/env python3
"""Verify a signed Android release and create its deterministic public sidecars."""

import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = importlib.util.spec_from_file_location(
    "android_version", Path(__file__).with_name("android-version.py")
)
VERSION = importlib.util.module_from_spec(MODULE)
MODULE.loader.exec_module(VERSION)


def source_identity(root, version, source_sha=None, tag=None):
    if not source_sha and not tag:
        return None
    if tag and tag != version["tag"]:
        raise ValueError("Requested Android tag differs from the source CalVer.")
    if source_sha and re.fullmatch(r"[0-9a-f]{40}", source_sha) is None:
        raise ValueError("Use the full tested Git commit SHA for release provenance.")
    revision = f"refs/tags/{tag}^{{commit}}" if tag else source_sha
    commit = subprocess.run(
        ["git", "rev-parse", "--verify", revision],
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    head = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    if commit != head:
        raise ValueError("Source checkout differs from the requested release commit.")
    dirty = subprocess.run(
        ["git", "diff", "--name-only", "HEAD", "--"],
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    if dirty:
        raise ValueError("Tracked release source differs from its commit.")
    committed = subprocess.run(
        ["git", "show", f"{commit}:android/gradle.properties"],
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    if VERSION.read_version(committed) != version:
        raise ValueError("Android version differs from the release commit.")
    return commit


def release_record(
    root, apk, badging, verification, source_sha=None, tag=None, certificate_file=None
):
    version = VERSION.read_version((root / "android/gradle.properties").read_text())
    VERSION.check_badging(version, badging)
    if "application-debuggable" in badging:
        raise ValueError("Refusing a debuggable release APK.")
    expected = (
        (certificate_file or root / "spec/android-release-certificate.sha256")
        .read_text()
        .strip()
    )
    if re.fullmatch(r"[0-9a-f]{64}", expected) is None:
        raise ValueError("Set the public Android release certificate SHA-256.")
    signers = re.findall(
        r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
        verification,
        re.MULTILINE,
    )
    if signers != [expected]:
        raise ValueError("APK signer differs from the pinned owner certificate.")
    if not re.search(
        r"^Verified using v[23] scheme .*: true$", verification, re.MULTILINE
    ):
        raise ValueError("Require successful apksigner v2/v3 signature verification.")
    if not 0 < apk.stat().st_size <= 15_000_000:
        raise ValueError("Release APK exceeds the 15 MB budget or is empty.")
    with zipfile.ZipFile(apk) as archive:
        for name in ("assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"):
            if name not in archive.namelist() or not archive.read(name):
                raise ValueError("Release APK must retain its baseline profile assets.")
    record = {
        **version,
        "packageName": "io.theficos.spunto",
        "certificateSha256": expected,
        "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    }
    commit = source_identity(root, version, source_sha, tag)
    if commit:
        record["sourceCommit"] = commit
    return record


def sidecars(apk, record):
    expected_name = f"spunto-{record['versionName']}.apk"
    if apk.name != expected_name:
        raise ValueError(f"Release asset must be named {expected_name}.")
    return {
        apk.with_name(
            apk.name + ".sha256"
        ): f"{record['apkSha256']}  {apk.name}\n".encode(),
        apk.with_name("android-version.json"): (
            json.dumps(record, indent=2, sort_keys=True) + "\n"
        ).encode(),
    }


def write_sidecars(files):
    # Validate every existing sidecar before adding any missing one.
    for path, content in files.items():
        if path.exists() and path.read_bytes() != content:
            raise ValueError(f"Existing release sidecar differs: {path.name}.")
    for path, content in files.items():
        if not path.exists():
            with path.open("xb") as stream:
                stream.write(content)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--badging", type=Path, required=True)
    parser.add_argument("--verification", type=Path, required=True)
    parser.add_argument(
        "--certificate-sha256",
        type=Path,
        help="trusted current public pin for historical release recovery",
    )
    identity = parser.add_mutually_exclusive_group()
    identity.add_argument(
        "--source-sha",
        help="verify the tested commit before creating a new release tag",
    )
    identity.add_argument(
        "--tag", help="verify an existing tag when repairing its release"
    )
    args = parser.parse_args()
    try:
        record = release_record(
            args.root,
            args.apk,
            args.badging.read_text(),
            args.verification.read_text(),
            args.source_sha,
            args.tag,
            args.certificate_sha256,
        )
        write_sidecars(sidecars(args.apk, record))
        print(json.dumps(record, sort_keys=True))
    except (
        OSError,
        ValueError,
        zipfile.BadZipFile,
        subprocess.CalledProcessError,
    ) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
