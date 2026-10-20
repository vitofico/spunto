#!/usr/bin/env python3
"""Allocate immutable server versions and verify multi-platform image provenance."""

import argparse
import datetime
import json
import re
from pathlib import Path

VERSION = re.compile(r"[0-9]{4}\.(?:0[1-9]|1[0-2])\.(?:0|[1-9][0-9]*)")
IMAGE = "ghcr.io/vitofico/spunto-server"
PLATFORMS = {"linux/amd64", "linux/arm64"}


class ReleaseError(ValueError):
    pass


def pages(value):
    if not isinstance(value, list):
        raise ReleaseError("Version inventory must be a JSON array.")
    for page in value:
        if isinstance(page, list):
            yield from pages(page)
        elif isinstance(page, dict):
            yield page
        else:
            raise ReleaseError("Malformed version inventory entry.")


def pick(date_text, refs, packages):
    date = datetime.date.fromisoformat(date_text)
    if date.isoformat() != date_text:
        raise ReleaseError("Use a canonical YYYY-MM-DD release date.")
    month = date.strftime("%Y.%m")
    # Preserve the version of the original private October release.
    micros = [0] if month == "2026.10" else [-1]
    tags = []
    for ref in pages(refs):
        if not isinstance(ref.get("ref"), str):
            raise ReleaseError("Malformed Git tag inventory.")
        tags.append(ref["ref"].removeprefix("refs/tags/"))
    for package in pages(packages):
        container = package.get("metadata", {}).get("container", {})
        values = container.get("tags")
        if not isinstance(values, list) or any(not isinstance(x, str) for x in values):
            raise ReleaseError("Malformed container tag inventory.")
        tags.extend(values)
    for tag in tags:
        if VERSION.fullmatch(tag) and tag.startswith(month + "."):
            micros.append(int(tag.rsplit(".", 1)[1]))
    return f"{month}.{max(micros) + 1}"


def verify(manifest, configs, version, source):
    if not VERSION.fullmatch(version) or not re.fullmatch(r"[0-9a-f]{40}", source):
        raise ReleaseError("Require a monthly CalVer and full source commit.")
    digest = manifest.get("digest", "")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise ReleaseError("Published image must have a SHA-256 index digest.")
    platforms = {
        f"{entry.get('platform', {}).get('os')}/{entry.get('platform', {}).get('architecture')}"
        for entry in manifest.get("manifests", [])
        if entry.get("annotations", {}).get("vnd.docker.reference.type")
        != "attestation-manifest"
    }
    if not PLATFORMS <= platforms:
        raise ReleaseError("Image index must contain Linux amd64 and arm64.")
    for platform in PLATFORMS:
        config = configs.get(platform, {})
        labels = config.get("config", {}).get("Labels", {})
        if (
            f"{config.get('os')}/{config.get('architecture')}" != platform
            or labels.get("org.opencontainers.image.version") != version
            or labels.get("org.opencontainers.image.revision") != source
            or labels.get("org.opencontainers.image.source")
            != "https://github.com/vitofico/spunto"
        ):
            raise ReleaseError(f"Image source/version differs on {platform}.")
    return {
        "version": version,
        "sourceCommit": source,
        "imageDigest": digest,
        "image": IMAGE,
        "platforms": sorted(PLATFORMS),
    }


def newest(refs):
    versions = []
    for ref in pages(refs):
        if not isinstance(ref.get("ref"), str):
            raise ReleaseError("Malformed Git tag inventory.")
        tag = ref["ref"].removeprefix("refs/tags/")
        if VERSION.fullmatch(tag):
            versions.append(tag)
    if not versions:
        raise ReleaseError("No published server tag exists.")
    return max(versions, key=lambda tag: tuple(map(int, tag.split("."))))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    allocate = commands.add_parser("pick")
    allocate.add_argument("--date", required=True)
    allocate.add_argument("--git-refs", required=True, type=Path)
    allocate.add_argument("--package-versions", required=True, type=Path)
    check = commands.add_parser("verify")
    check.add_argument("--manifest", required=True, type=Path)
    check.add_argument("--configs", required=True, type=Path)
    check.add_argument("--version", required=True)
    check.add_argument("--source", required=True)
    check.add_argument("--output", required=True, type=Path)
    latest = commands.add_parser("newest")
    latest.add_argument("--git-refs", required=True, type=Path)
    args = parser.parse_args()
    try:
        if args.command == "pick":
            print(
                pick(
                    args.date,
                    json.loads(args.git_refs.read_text()),
                    json.loads(args.package_versions.read_text()),
                )
            )
        elif args.command == "newest":
            print(newest(json.loads(args.git_refs.read_text())))
        else:
            record = verify(
                json.loads(args.manifest.read_text()),
                json.loads(args.configs.read_text()),
                args.version,
                args.source,
            )
            payload = json.dumps(record, sort_keys=True, indent=2) + "\n"
            if args.output.exists() and args.output.read_text() != payload:
                raise ReleaseError(
                    "Existing image receipt differs; refusing replacement."
                )
            args.output.write_text(payload)
            print(payload, end="")
    except (ValueError, OSError, KeyError, TypeError) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
