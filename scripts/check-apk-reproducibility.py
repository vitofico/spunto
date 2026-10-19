#!/usr/bin/env python3
"""Verify APK reproduction using signature copying, cryptographic verification and exact bytes."""

import argparse
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path


def verify(signed, unsigned, signer):
    from apksigcopier import APKSigCopierError, do_copy

    with tempfile.TemporaryDirectory(prefix="spunto-apk-reproduction-") as directory:
        reproduced = Path(directory) / "reproduced.apk"
        try:
            do_copy(str(signed), str(unsigned), str(reproduced))
        except APKSigCopierError as error:
            raise ValueError(f"Signature copying failed: {error}") from error
        result = subprocess.run(
            [str(signer), "verify", "--verbose", "--print-certs", str(reproduced)],
            capture_output=True,
            text=True,
            check=True,
        )
        expected = hashlib.sha256(signed.read_bytes()).hexdigest()
        actual = hashlib.sha256(reproduced.read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError(
                "Signature copied but APK bytes do not exactly reproduce the signed release."
            )
        return {
            "reproduced": True,
            "sha256": actual,
            "signature_verification": result.stdout,
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("signed", type=Path)
    parser.add_argument("unsigned", type=Path)
    parser.add_argument(
        "--apksigner",
        type=Path,
        required=True,
        help="SDK build-tools 34 apksigner, or a wrapper invoking it",
    )
    args = parser.parse_args()
    try:
        if sys.version_info[:2] != (3, 12):
            raise ValueError(
                "Use Python 3.12 for pinned apksigcopier; newer zipfile versions "
                "can change copied signature metadata."
            )
        print(json.dumps(verify(args.signed, args.unsigned, args.apksigner), indent=2))
    except (ImportError, OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"APK reproduction failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
