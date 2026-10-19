import fcntl
import os
from pathlib import Path
from types import TracebackType

from spunto.errors import OperatorError

LOCK_NAME = "spunto.lock"


class DataLock:
    """An exclusive lock on the data directory, held for the life of the process.

    SQLite allows one writer and the server keeps its queues in memory, so two
    processes on one volume would corrupt each other's view (02 §2.2). The kernel
    drops the lock when the process exits, however it exits.
    """

    def __init__(self, data_dir: Path):
        try:
            data_dir.mkdir(parents=True, exist_ok=True)
            self._fd = os.open(data_dir / LOCK_NAME, os.O_RDWR | os.O_CREAT, 0o600)
        except OSError as exc:
            raise OperatorError(
                f"Cannot write to the data directory {data_dir} ({exc.strerror}). "
                "Mount a writable volume there; the image runs as UID 10001."
            ) from None
        try:
            fcntl.flock(self._fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            os.close(self._fd)
            raise OperatorError(
                f"Another Spunto process is using {data_dir}. Stop it first: "
                "two processes on one database would corrupt it."
            ) from None

    def release(self) -> None:
        os.close(self._fd)

    def __enter__(self) -> DataLock:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.release()
