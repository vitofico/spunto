"""Independent environment-only provider connections; credentials never serialize."""

import os
import re
from ipaddress import ip_address
from pathlib import Path
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, SecretStr

from spunto.errors import OperatorError

CAPABILITIES = ("chat", "embed", "transcribe", "vision")


def embedding_prefixes(model):
    name = (model or "").rsplit("/", 1)[-1].split(":", 1)[0]
    if name in {"embeddinggemma", "embeddinggemma-2"}:
        return "task: search result | query: ", "title: none | text: "
    if name == "nomic-embed-text":
        return "search_query: ", "search_document: "
    return "", ""


class Profile(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")
    name: str
    provider: str = "openai"
    base_url: str
    api_key: SecretStr = Field(default_factory=lambda: SecretStr(""), exclude=True, repr=False)
    chat_model: str | None = None
    embed_model: str | None = None
    transcribe_model: str | None = None
    vision_model: str | None = None
    embed_query_prefix: str = ""
    embed_document_prefix: str = ""
    embed_query_prefix_explicit: bool = Field(False, exclude=True, repr=False)
    embed_document_prefix_explicit: bool = Field(False, exclude=True, repr=False)
    rate_per_min: int = Field(30, ge=1, le=100000)
    concurrency: int = Field(1, ge=1, le=128)
    timeout_s: float = Field(60, gt=0, le=3600)

    def leaves_network(self, capability):
        model = getattr(self, capability + "_model", "") or ""
        hostname = urlsplit(self.base_url).hostname or ""
        try:
            local = ip_address(hostname).is_private or ip_address(hostname).is_loopback
        except ValueError:
            local = hostname == "localhost" or hostname.endswith(
                (".local", ".svc", ".cluster.local")
            )
        return not local or "-cloud" in model or model.endswith(":cloud")

    def public(self):
        # Credentials in paths/query strings are disallowed at configuration time.
        return self.model_dump()

    def with_model(self, capability, model):
        updates = {capability + "_model": model}
        if capability == "embed":
            old = embedding_prefixes(self.embed_model)
            new = embedding_prefixes(model)
            for field, previous, replacement in zip(
                ("embed_query_prefix", "embed_document_prefix"), old, new, strict=True
            ):
                if not getattr(self, field + "_explicit") and getattr(self, field) == previous:
                    updates[field] = replacement
        return self.model_copy(update=updates)


def load_profiles(value):
    if isinstance(value, dict):
        return value
    names = [x.strip() for x in (value or "").split(",") if x.strip()]
    if len(set(names)) != len(names) or any(
        not re.fullmatch(r"[a-z][a-z0-9_]{0,23}", n) for n in names
    ):
        raise OperatorError("SPUNTO_AI_PROFILES must name distinct lowercase profiles.")
    result = {}
    for name in names:
        prefix = "SPUNTO_AI_" + name.upper() + "_"

        def read(key, default=None, prefix=prefix):
            return os.environ.get(prefix + key, default) or default

        provider = read("PROVIDER", "openai")
        if provider not in {"openai", "anthropic"}:
            raise OperatorError(prefix + "PROVIDER must be openai or anthropic.")
        base_url = read(
            "BASE_URL", "https://api.anthropic.com" if provider == "anthropic" else None
        )
        try:
            parts = urlsplit(base_url or "")
            _ = parts.port
        except ValueError:
            raise OperatorError(prefix + "BASE_URL must be a valid HTTP URL.") from None
        if (
            parts.scheme not in {"http", "https"}
            or not parts.hostname
            or parts.username
            or parts.password
            or parts.query
            or parts.fragment
        ):
            raise OperatorError(
                prefix + "BASE_URL must be an HTTP URL without credentials or query."
            )
        key, filename = read("API_KEY"), read("API_KEY_FILE")
        if key and filename:
            raise OperatorError(
                "Set " + prefix + "API_KEY or " + prefix + "API_KEY_FILE, not both."
            )
        if filename:
            try:
                key = Path(filename).read_text().rstrip("\r\n")
            except OSError:
                raise OperatorError(prefix + "API_KEY_FILE cannot be read.") from None
        if provider == "anthropic" and not key:
            raise OperatorError(prefix + "API_KEY or API_KEY_FILE is required for Anthropic.")
        models = {cap + "_model": read(cap.upper() + "_MODEL") for cap in CAPABILITIES}
        if provider == "anthropic" and (models["embed_model"] or models["transcribe_model"]):
            raise OperatorError(prefix + "PROVIDER does not serve embeddings or transcription.")
        default_query, default_document = embedding_prefixes(models["embed_model"])
        limits = {}
        for variable, convert, default, upper in (
            ("RATE_PER_MIN", int, "30", 100000),
            ("CONCURRENCY", int, "1", 128),
            ("TIMEOUT_S", float, "60", 3600),
        ):
            try:
                limit = convert(read(variable, default))
                if not 0 < limit <= upper:
                    raise ValueError
                limits[variable.lower()] = limit
            except ValueError:
                raise OperatorError(
                    prefix + variable + " must be a valid positive limit."
                ) from None
        try:
            result[name] = Profile(
                name=name,
                provider=provider,
                base_url=base_url.rstrip("/"),
                api_key=SecretStr(key or ""),
                **models,
                embed_query_prefix=os.environ.get(prefix + "EMBED_QUERY_PREFIX", default_query),
                embed_document_prefix=os.environ.get(
                    prefix + "EMBED_DOCUMENT_PREFIX", default_document
                ),
                embed_query_prefix_explicit=prefix + "EMBED_QUERY_PREFIX" in os.environ,
                embed_document_prefix_explicit=prefix + "EMBED_DOCUMENT_PREFIX" in os.environ,
                **limits,
            )
        except ValueError:
            raise OperatorError(
                prefix + "limits must be valid positive rate, concurrency and timeout."
            ) from None
    return result
