"""Language of bundled plugin UI, scoped to a request or a queued command."""

import json
from contextlib import contextmanager
from contextvars import ContextVar
from functools import lru_cache
from importlib.resources import files

LANGUAGES = {"en": "English", "it": "Italian", "es": "Spanish", "fr": "French", "de": "German"}
_language = ContextVar("plugin_language", default=None)


def negotiate(header: str) -> str | None:
    ranked = []
    for item in header[:1024].split(","):
        tag, _, parameters = item.strip().partition(";")
        code = tag.casefold().split("-", 1)[0]
        if code not in LANGUAGES:
            continue
        quality = 1.0
        if parameters:
            key, _, value = parameters.strip().partition("=")
            if key.casefold() == "q":
                try:
                    quality = float(value)
                except ValueError:
                    continue
        if 0 < quality <= 1:
            ranked.append((quality, code))
    return max(ranked, key=lambda entry: entry[0])[1] if ranked else None


def language() -> str:
    return _language.get() or "en"


@contextmanager
def use_language(code):
    token = _language.set(code if code in LANGUAGES else None)
    try:
        yield
    finally:
        _language.reset(token)


@lru_cache(maxsize=4)
def _catalog(code):
    return json.loads(files(__package__).joinpath("locales", f"{code}.json").read_text("utf-8"))


def tr(message, **values):
    code = language()
    if code != "en":
        message = _catalog(code).get(message, message)
    return message.format(**values) if values else message


def answer_language():
    code = _language.get()
    if code is None:
        return ""
    return (
        f"Use {LANGUAGES[code]} for your response unless the user explicitly requests another "
        "language. Preserve quotations and any existing note text in their original language.\n"
    )
