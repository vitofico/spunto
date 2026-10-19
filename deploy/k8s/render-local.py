#!/usr/bin/env python3
"""Render the imported-image deployment without editing the base or contacting a cluster."""

import argparse
import re
import shutil
import subprocess
import tempfile
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "image",
        help="imported digest reference or tag ending in -<12–64 hex fingerprint>",
    )
    args = parser.parse_args()
    # Restrict both the image identity and YAML interpolation. No latest/dev tag is accepted.
    component = r"[a-z0-9]+(?:[._-][a-z0-9]+)*"
    name = rf"(?:{component}(?::[0-9]+)?/)*{component}"
    if not re.fullmatch(
        rf"(?:{name}@sha256:[0-9a-f]{{64}}|{name}:[a-zA-Z0-9._-]+-[0-9a-f]{{12,64}})",
        args.image,
    ):
        parser.error("use an imported sha256 digest or a tag ending in a content fingerprint")
    if shutil.which("kustomize"):
        command = ["kustomize", "build"]
    elif shutil.which("kubectl"):
        command = ["kubectl", "kustomize"]
    else:
        parser.error("install kustomize or kubectl to render locally")
    with tempfile.TemporaryDirectory(prefix="spunto-k8s-") as temporary:
        target = Path(temporary) / "base"
        shutil.copytree(Path(__file__).resolve().parent, target)
        patch = target / "local-image.yaml"
        patch.write_text(
            "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: spunto\nspec:\n"
            "  template:\n    spec:\n      containers:\n        - name: spunto\n"
            f"          image: {args.image}\n          imagePullPolicy: Never\n"
        )
        kustomization = target / "kustomization.yaml"
        with kustomization.open("a") as stream:
            stream.write("\npatches:\n  - path: local-image.yaml\n")
        # Fixed renderer arguments and a generated local path; execution never uses a shell.
        subprocess.run([*command, str(target)], check=True)  # noqa: S603


if __name__ == "__main__":
    main()
