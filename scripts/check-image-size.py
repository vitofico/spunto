#!/usr/bin/env python3
"""Enforce the compressed image budget from BuildKit's gzip OCI output.

docker buildx build --platform linux/amd64,linux/arm64 \
  --output type=oci,dest=/tmp/spunto.oci.tar,compression=gzip,force-compression=true server/
python3 scripts/check-image-size.py /tmp/spunto.oci.tar

The sum is per architecture, including shared base layers for each image.
Docker's image inspect size and docker-save archives contain uncompressed data
and cannot establish the registry download budget.
"""

import argparse
import json
import re
import sys
import tarfile
from pathlib import Path

INDEX_TYPES = {
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
}
MANIFEST_TYPES = {
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
}
GZIP_TYPES = {
    "application/vnd.oci.image.layer.v1.tar+gzip",
    "application/vnd.docker.image.rootfs.diff.tar.gzip",
}


class Layout:
    def __init__(self, path: Path):
        self.path = path
        # Owned by Layout and closed by main's finally, including failed checks.
        self.archive = None if path.is_dir() else tarfile.open(path, "r:*")  # noqa: SIM115

    def close(self):
        if self.archive is not None:
            self.archive.close()

    def read(self, name: str, *, prefix: bool = False) -> bytes:
        if self.archive is None:
            with (self.path / name).open("rb") as stream:
                return stream.read(2 if prefix else -1)
        stream = self.archive.extractfile(name)
        if stream is None:
            raise ValueError(f"OCI entry {name} is not a file")
        with stream:
            return stream.read(2 if prefix else -1)

    def blob_name(self, descriptor: dict) -> str:
        digest = descriptor["digest"]
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            raise ValueError("OCI descriptors must use SHA-256 digests")
        name = f"blobs/sha256/{digest.removeprefix('sha256:')}"
        size = (
            (self.path / name).stat().st_size
            if self.archive is None
            else self.archive.getmember(name).size
        )
        if not isinstance(descriptor["size"], int) or descriptor["size"] != size:
            raise ValueError("OCI descriptor size differs from its blob")
        return name

    def document(self, descriptor: dict) -> dict:
        return json.loads(self.read(self.blob_name(descriptor)))


def image_sizes(layout: Layout, document: dict) -> dict[str, int]:
    result = {}

    def visit(descriptor: dict):
        if descriptor.get("annotations", {}).get("vnd.docker.reference.type") == (
            "attestation-manifest"
        ):
            return
        media_type = descriptor["mediaType"]
        manifest = layout.document(descriptor)
        if media_type in INDEX_TYPES:
            for child in manifest["manifests"]:
                visit(child)
            return
        if media_type not in MANIFEST_TYPES:
            raise ValueError("Expected an OCI image manifest or index")
        config = layout.document(manifest["config"])
        platform = f"{config['os']}/{config['architecture']}"
        if platform in result:
            raise ValueError(f"Duplicate image manifest for {platform}")
        total = 0
        for layer in manifest["layers"]:
            if layer["mediaType"] not in GZIP_TYPES:
                raise ValueError(f"{platform} has a layer that is not gzip compressed")
            name = layout.blob_name(layer)
            if layout.read(name, prefix=True) != b"\x1f\x8b":
                raise ValueError(f"{platform} has an invalid gzip layer")
            total += layer["size"]
        if not total:
            raise ValueError(f"{platform} has no image layers")
        result[platform] = total

    if document["mediaType"] not in INDEX_TYPES:
        raise ValueError("Expected an OCI layout index.json")
    for descriptor in document["manifests"]:
        visit(descriptor)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("layout", type=Path, help="OCI directory or tar archive")
    parser.add_argument("--max-bytes", type=int, default=150_000_000)
    parser.add_argument("--platform", action="append", help="Required OS/architecture (repeatable)")
    args = parser.parse_args()
    if args.max_bytes <= 0:
        parser.error("--max-bytes must be positive")
    layout = None
    try:
        layout = Layout(args.layout)
        sizes = image_sizes(layout, json.loads(layout.read("index.json")))
        required = set(args.platform or ["linux/amd64", "linux/arm64"])
        missing = required - sizes.keys()
        if missing:
            raise ValueError(f"Missing image platforms: {', '.join(sorted(missing))}")
        failed = False
        for platform, size in sorted(sizes.items()):
            passed = size <= args.max_bytes
            print(
                f"{platform}: {size:,} gzip layer bytes ({size / 1_000_000:.2f} MB); "
                f"budget {args.max_bytes:,}: {'PASS' if passed else 'FAIL'}"
            )
            failed |= not passed
        return int(failed)
    except (KeyError, OSError, ValueError, tarfile.TarError) as error:
        print(f"image budget: {error}", file=sys.stderr)
        return 1
    finally:
        if layout is not None:
            layout.close()


if __name__ == "__main__":
    sys.exit(main())
