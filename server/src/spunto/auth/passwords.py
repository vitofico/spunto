"""The owner password: argon2id, hashed off the event loop (02 §9.3)."""

import asyncio
from concurrent.futures import ThreadPoolExecutor

from argon2 import PasswordHasher, profiles
from argon2.exceptions import InvalidHashError, VerificationError

MIN_CHARS = 8
# argon2 accepts any length, so an enormous password would only be a way to burn CPU.
MAX_BYTES = 1024

_hasher = PasswordHasher.from_parameters(profiles.RFC_9106_LOW_MEMORY)

# Each hash takes 64 MiB for a moment. Running them one at a time keeps a burst
# of logins inside the memory budget (01 §6.6) and slows guessing further.
_one_at_a_time = ThreadPoolExecutor(max_workers=1, thread_name_prefix="argon2")


def problem(password: str) -> str | None:
    """Why a new password is not acceptable, or None when it is."""
    if len(password) < MIN_CHARS:
        return f"The password needs at least {MIN_CHARS} characters."
    if len(password.encode()) > MAX_BYTES:
        return f"The password must fit in {MAX_BYTES} bytes."
    return None


def hash_now(password: str) -> str:
    """Blocking; for the command line. The server uses hash_password."""
    return _hasher.hash(password)


async def hash_password(password: str) -> str:
    return await asyncio.get_running_loop().run_in_executor(_one_at_a_time, _hasher.hash, password)


async def verify_password(stored_hash: str, password: str) -> bool:
    if len(password.encode()) > MAX_BYTES:
        return False
    try:
        return await asyncio.get_running_loop().run_in_executor(
            _one_at_a_time, _hasher.verify, stored_hash, password
        )
    except VerificationError, InvalidHashError:
        return False
