#!/usr/bin/env python3
"""Provision only Spunto's NFS directories, exports and existing Borg source list.

Run on theficos as root with --apply after reviewing the printed plan. Existing
configuration is preserved in a private rollback directory; no backup credentials
are printed and no backup or Kubernetes deployment is started by this script.
"""

import argparse
import datetime
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

SOURCE = "/srv/cluster-data/spunto"
NODE = "192.0.2.10"
CLIENTS = (NODE, "127.0.0.1")
EXPORTS = Path("/etc/exports.d/spunto.exports")
BORG = Path("/etc/borgmatic/config.yaml")


def update_exports(original: str | None) -> str:
    """Create or narrowly upgrade the two exports owned by this provisioner."""
    options = "rw,sync,no_subtree_check,root_squash"

    def configuration(clients: tuple[str, ...]) -> str:
        return "".join(
            f"{SOURCE}/{name} "
            + " ".join(f"{client}({options})" for client in clients)
            + "\n"
            for name in ("backups", "attachments")
        )

    updated = configuration(CLIENTS)
    if original not in (None, configuration((NODE,)), updated):
        raise ValueError(
            "Spunto exports differ from the reviewed configuration; review manually"
        )
    return updated


def add_source(text: str) -> str:
    """Insert one source without rewriting unrelated YAML or secrets."""
    lines = text.splitlines(keepends=True)
    starts = [
        i for i, line in enumerate(lines) if line.rstrip() == "source_directories:"
    ]
    if len(starts) != 1:
        raise ValueError("Borg source_directories must be one top-level block list")
    start = starts[0] + 1
    end = next(
        (i for i in range(start, len(lines)) if re.match(r"^[^\s#]", lines[i])),
        len(lines),
    )
    entries = []
    for line in lines[start:end]:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = re.fullmatch(r"  - ([^\n]+)\n?", line)
        if not match:
            raise ValueError(
                "Borg source_directories needs manual review of its list syntax"
            )
        entries.append(match[1].strip().strip("\"'"))
    if SOURCE in entries:
        return text
    if not entries:
        raise ValueError(
            "Borg has no existing source directories; refusing to initialize it"
        )
    lines.insert(start, f"  - {SOURCE}\n")
    return "".join(lines)


def atomic_write(path: Path, content: str, mode: int) -> None:
    descriptor, temporary = tempfile.mkstemp(prefix=".spunto-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w") as stream:
            os.fchmod(stream.fileno(), mode)
            if path.exists():
                previous = path.stat()
                os.fchown(stream.fileno(), previous.st_uid, previous.st_gid)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        Path(temporary).unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--apply", action="store_true", help="apply the reviewed plan as root"
    )
    args = parser.parse_args()
    root = Path(SOURCE)
    if root.parent.is_symlink() or not root.parent.is_mount():
        parser.error("/srv/cluster-data must be its own mounted filesystem")
    for path in (root, root / "backups", root / "attachments", BORG, EXPORTS):
        if path.is_symlink():
            parser.error(f"{path} must not be a symbolic link")
    original_borg = BORG.read_text()
    borg_mode = stat.S_IMODE(BORG.stat().st_mode)
    original_exports = EXPORTS.read_text() if EXPORTS.exists() else None
    try:
        updated_borg = add_source(original_borg)
        exports = update_exports(original_exports)
    except ValueError as error:
        parser.error(str(error))
    exports_changed = exports != original_exports
    borg_changed = updated_borg != original_borg
    print(
        json.dumps(
            {
                "directories": [
                    str(root / name) for name in ("backups", "attachments")
                ],
                "uid": 10001,
                "gid": 10001,
                "mode": "0750",
                "exports_file": str(EXPORTS),
                "nfs_clients": CLIENTS,
                "update_exports": exports_changed,
                "borg_config": str(BORG),
                "add_borg_source": borg_changed,
                "apply": args.apply,
            },
            indent=2,
        ),
        flush=True,
    )
    if not args.apply:
        return
    if os.geteuid() != 0:
        parser.error("--apply requires root; run with sudo on theficos")
    for directory in (root, root / "backups", root / "attachments"):
        if directory.exists() and not directory.is_dir():
            parser.error(f"{directory} must be a directory")
        if directory.exists() and directory.stat().st_uid != 10001:
            parser.error(f"{directory} is not owned by UID 10001; review it manually")
    rollback_base = Path("/var/backups/spunto-host-storage")
    for directory in (rollback_base.parent, rollback_base):
        if directory.is_symlink():
            parser.error(f"{directory} must not be a symbolic link")
        if directory.exists():
            info = directory.stat()
            if not directory.is_dir() or info.st_uid != 0 or info.st_mode & 0o022:
                parser.error(
                    f"{directory} must be a root-owned directory without other writers"
                )
    if not rollback_base.parent.is_dir():
        parser.error("/var/backups must exist before provisioning storage")
    rollback_base.mkdir(mode=0o700, exist_ok=True)
    os.chmod(rollback_base, 0o700)
    rollback = rollback_base / datetime.datetime.now(datetime.UTC).strftime(
        "%Y%m%dT%H%M%S%fZ"
    )
    rollback.mkdir(mode=0o700)
    if borg_changed:
        shutil.copy2(BORG, rollback / "borgmatic.yaml")
        os.chmod(rollback / "borgmatic.yaml", 0o600)
    had_exports = original_exports is not None
    exports_mode = stat.S_IMODE(EXPORTS.stat().st_mode) if had_exports else 0o644
    if had_exports:
        shutil.copy2(EXPORTS, rollback / "spunto.exports")
        os.chmod(rollback / "spunto.exports", 0o600)
    else:
        (rollback / "exports-was-absent").touch(mode=0o600)
    EXPORTS.parent.mkdir(mode=0o755, exist_ok=True)
    for directory in (root, root / "backups", root / "attachments"):
        directory.mkdir(mode=0o750, exist_ok=True)
        info = directory.stat()
        if (info.st_uid, info.st_gid) != (10001, 10001):
            os.chown(directory, 10001, 10001)
        if stat.S_IMODE(info.st_mode) != 0o750:
            os.chmod(directory, 0o750)
    exports_attempted = borg_attempted = False
    try:
        if exports_changed:
            exports_attempted = True
            atomic_write(EXPORTS, exports, exports_mode)
        subprocess.run(["/usr/sbin/exportfs", "-ra"], check=True, timeout=30)
        if borg_changed:
            borg_attempted = True
            atomic_write(BORG, updated_borg, borg_mode)
    except BaseException:
        failures = []
        try:
            if borg_attempted:
                atomic_write(BORG, original_borg, borg_mode)
        except OSError:
            failures.append("borg_config")
        try:
            if exports_attempted and had_exports:
                atomic_write(EXPORTS, original_exports, exports_mode)
            elif exports_attempted:
                EXPORTS.unlink(missing_ok=True)
        except OSError:
            failures.append("exports_config")
        try:
            if exports_attempted:
                result = subprocess.run(
                    ["/usr/sbin/exportfs", "-ra"], check=False, timeout=30
                )
                if result.returncode:
                    failures.append("exports_refresh")
        except (OSError, subprocess.TimeoutExpired):
            failures.append("exports_refresh")
        if failures:
            print(
                json.dumps(
                    {"rollback_failures": failures, "rollback_directory": str(rollback)}
                ),
                file=sys.stderr,
            )
        raise
    print(
        json.dumps(
            {
                "configured": True,
                "rollback_directory": str(rollback),
                "offsite_backup_verified": False,
            }
        )
    )


if __name__ == "__main__":
    main()
