"""Consistent copies of the database file, with a checksum beside each (02 §10.6)."""

import hashlib
import os
import re
import sqlite3
from datetime import UTC, datetime
from pathlib import Path

from spunto.errors import OperatorError

# spunto-2026-10-13T0330Z-s7.db: when it was taken (UTC) and the schema version it holds.
NAME = re.compile(r"spunto-(\d{4}-\d{2}-\d{2}T\d{4}Z)-s(\d+)\.db")
_TIME = "%Y-%m-%dT%H%MZ"


def write_snapshot(conn: sqlite3.Connection, directory: Path, now: datetime) -> Path:
    """Copy the database behind `conn` into `directory`, safely while the server writes.

    VACUUM INTO reads inside one transaction, so the copy is consistent and
    compacted without blocking writers in WAL mode. The copy appears under its
    final name only once it and its bytes are on disk.
    """
    schema = conn.execute("PRAGMA user_version").fetchone()[0]
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / f"spunto-{now.astimezone(UTC).strftime(_TIME)}-s{schema}.db"
    if target.exists():
        raise OperatorError(f"{target} already exists; take the next snapshot in a minute.")
    partial = directory / f".{target.name}.partial"
    partial.unlink(missing_ok=True)
    conn.execute("VACUUM INTO ?", (str(partial),))
    digest = _sync_and_hash(partial)
    partial.rename(target)
    checksum = checksum_path(target)
    partial_checksum = directory / f".{checksum.name}.partial"
    partial_checksum.write_text(f"{digest}  {target.name}\n")
    _sync_and_hash(partial_checksum)
    partial_checksum.rename(checksum)
    _sync_dir(directory)
    return target


def taken_at(path: Path) -> datetime | None:
    """When a snapshot was taken, from its name; None for any other file."""
    match = NAME.fullmatch(path.name)
    return datetime.strptime(match[1], _TIME).replace(tzinfo=UTC) if match else None


def checksum_path(snapshot: Path) -> Path:
    return snapshot.with_name(f"{snapshot.name}.sha256")


def verify_checksum(snapshot: Path) -> None:
    """Refuse a snapshot whose bytes differ from the sha256 written beside it."""
    try:
        expected = checksum_path(snapshot).read_text().split()[0]
    except OSError, IndexError:
        raise OperatorError(
            f"There is no readable checksum file beside {snapshot} "
            f"(expected {checksum_path(snapshot).name}), so it cannot be verified."
        ) from None
    with snapshot.open("rb") as f:
        actual = hashlib.file_digest(f, "sha256").hexdigest()
    if actual != expected:
        raise OperatorError(f"{snapshot} does not match its checksum; it is damaged or changed.")


def _sync_and_hash(path: Path) -> str:
    with path.open("rb") as f:
        os.fsync(f.fileno())
        return hashlib.file_digest(f, "sha256").hexdigest()


def _sync_dir(directory: Path) -> None:
    fd = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
