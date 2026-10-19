#!/usr/bin/env python3
"""Check the installable Android APK against the 15 MB distribution budget."""

import argparse
import sys
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--max-bytes", type=int, default=15_000_000)
    args = parser.parse_args()
    if args.max_bytes <= 0:
        parser.error("--max-bytes must be positive")
    try:
        size = args.apk.stat().st_size
        with zipfile.ZipFile(args.apk) as archive:
            if "AndroidManifest.xml" not in archive.namelist() or archive.testzip():
                raise ValueError("not a valid Android APK")
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"APK check failed: {error}", file=sys.stderr)
        return 1
    print(f"APK: {size:,} bytes / {args.max_bytes:,}")
    return 0 if size <= args.max_bytes else 1


if __name__ == "__main__":
    sys.exit(main())
