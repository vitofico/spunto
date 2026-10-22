"""Settings, read from SPUNTO_ environment variables (02 §10.3).

Secrets also accept a _FILE variant that names a file holding the value. A bad
value stops startup with a sentence naming the variable (HOST-2).
"""

import json
import re
from datetime import time
from ipaddress import IPv4Network, IPv6Network, ip_network
from pathlib import Path
from typing import Annotated, Literal, Self
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from argon2 import Type, extract_parameters
from argon2.exceptions import InvalidHashError
from pydantic import Field, ValidationError, ValidationInfo, field_validator, model_validator
from pydantic_core import ErrorDetails
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict

from spunto.ai.profiles import CAPABILITIES, Profile, load_profiles
from spunto.ai.quota import ACTIONS, PLANS
from spunto.auth.passwords import problem as password_problem
from spunto.errors import OperatorError

PREFIX = "SPUNTO_"

_PUBLIC_URL_REQUIRED = (
    "is required: the address people and agents open, "
    "such as https://spunto.example.org or http://192.168.1.10:8080"
)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix=PREFIX)

    public_url: str
    database_url: str | None = Field(None, repr=False)
    database_url_file: Path | None = None
    identity_database_url: str | None = Field(None, repr=False)
    identity_database_url_file: Path | None = None
    oidc_issuer: str | None = None
    oidc_client_id: str | None = None
    oidc_client_secret: str | None = Field(None, repr=False)
    oidc_client_secret_file: Path | None = None
    oidc_label: str = "SSO"
    oidc_allow_insecure_loopback: bool = False
    local_registration_enabled: bool = False
    ai_profiles: Annotated[dict[str, Profile], NoDecode] = Field(default_factory=dict, repr=False)
    ai_chat_profile: str | None = None
    ai_embed_profile: str | None = None
    ai_transcribe_profile: str | None = None
    ai_vision_profile: str | None = None
    ai_quota_plan: Literal["free", "paid", "unlimited"] | None = None
    ai_quota_limits: Annotated[dict[str, Annotated[int, Field(ge=0)] | None], NoDecode] = Field(
        default_factory=dict
    )
    max_upload_mb: int = Field(20, ge=1, le=1000)

    @field_validator("max_upload_mb", mode="before")
    @classmethod
    def _whole_upload_megabytes(cls, value):
        if type(value) is int:
            return value
        if isinstance(value, str) and re.fullmatch(r"[0-9]+", value):
            digits = value.lstrip("0") or "0"
            if len(digits) <= 4:
                return int(digits)
        raise ValueError("must be a whole number of MB from 1 to 1000")

    @property
    def max_upload_bytes(self):
        return self.max_upload_mb * 1_000_000

    @field_validator("ai_profiles", mode="before")
    @classmethod
    def _ai_profiles(cls, value):
        return load_profiles(value)

    @field_validator("ai_quota_limits", mode="before")
    @classmethod
    def _quota_limits_json(cls, value):
        if not isinstance(value, str):
            return value
        try:
            return json.loads(value) if value.strip() else {}
        except ValueError:
            raise ValueError('must be a JSON object such as {"ask": 300}') from None

    @field_validator("ai_quota_limits")
    @classmethod
    def _known_quota_actions(cls, value):
        if unknown := set(value) - set(ACTIONS):
            raise ValueError(
                "names unknown AI actions ("
                + ", ".join(sorted(unknown))
                + "); use "
                + ", ".join(ACTIONS)
            )
        return value

    @property
    def ai_quota(self):
        """Monthly per-account limits for hosted servers; None is unlimited."""
        return {**PLANS[self.ai_quota_plan or "free"], **self.ai_quota_limits}

    @property
    def ai_bindings(self):
        return {
            cap: getattr(self, "ai_" + cap + "_profile")
            for cap in CAPABILITIES
            if getattr(self, "ai_" + cap + "_profile")
        }

    port: int = Field(8080, ge=1, le=65535)
    data_dir: Path = Path("/data")
    backup_dir: Path | None = None
    backup_time: time = time(3, 30)
    backup_keep_daily: int = Field(7, ge=1)
    backup_keep_weekly: int = Field(4, ge=0)
    trash_days: int = Field(30, ge=1)
    revision_days: int = Field(180, ge=1)
    attachments_dir: Path | None = None
    web_dir: Path | None = None
    mcp_enabled: bool = True
    mcp_writes_per_day: int = Field(1000, ge=1)
    plugins: str = ""
    plugins_dir: Path | None = None
    themes_dir: Path | None = None

    @field_validator("plugins_dir", "themes_dir")
    @classmethod
    def _operator_directory(cls, value):
        if value is not None and value.exists() and not value.is_dir():
            raise ValueError("must name a directory")
        return value

    owner_password: str | None = None
    owner_password_file: Path | None = None
    owner_password_hash: str | None = None
    owner_password_hash_file: Path | None = None
    trusted_proxies: Annotated[tuple[IPv4Network | IPv6Network, ...], NoDecode] = ()
    log_level: Literal["debug", "info", "warning", "error"] = "info"
    log_format: Literal["json", "text"] = "json"
    tz: str = Field("UTC", validation_alias="TZ")

    @field_validator("*", mode="before")
    @classmethod
    def _blank_means_unset(cls, value: object, info: ValidationInfo) -> object:
        # Compose and shells hand over "" for a variable that is declared but empty.
        field = cls.model_fields[info.field_name]
        if isinstance(value, str) and not value.strip() and not field.is_required():
            return field.get_default(call_default_factory=True)
        return value

    @field_validator("public_url")
    @classmethod
    def _origin_only(cls, value: str) -> str:
        if not value.strip():
            raise ValueError(_PUBLIC_URL_REQUIRED)
        parts = urlsplit(value.strip())
        if parts.scheme not in ("http", "https"):
            raise ValueError("must start with https:// or http://, e.g. https://spunto.example.org")
        if (
            not parts.hostname
            or parts.username is not None
            or parts.path not in ("", "/")
            or parts.query
            or parts.fragment
        ):
            raise ValueError(
                "must be the scheme, host and optional port only, without a path, "
                "e.g. https://spunto.example.org"
            )
        try:
            port = parts.port
        except ValueError:
            raise ValueError("has a port that is not a number from 1 to 65535") from None
        host = f"[{parts.hostname}]" if ":" in parts.hostname else parts.hostname
        default_port = 443 if parts.scheme == "https" else 80
        netloc = host if port in (None, default_port) else f"{host}:{port}"
        return f"{parts.scheme}://{netloc}"

    @field_validator("owner_password_hash")
    @classmethod
    def _argon2id(cls, value: str | None) -> str | None:
        return None if value is None else _argon2id_hash(value)

    @field_validator("trusted_proxies", mode="before")
    @classmethod
    def _cidr_list(cls, value: object) -> object:
        if not isinstance(value, str):
            return value
        networks = []
        for item in filter(None, (part.strip() for part in value.split(","))):
            try:
                networks.append(ip_network(item, strict=False))
            except ValueError:
                raise ValueError(
                    f"'{item}' is not an IP address or CIDR range, e.g. 10.0.0.0/8"
                ) from None
        return tuple(networks)

    @field_validator("log_level", "log_format", mode="before")
    @classmethod
    def _any_case(cls, value: object) -> object:
        return value.strip().lower() if isinstance(value, str) else value

    @field_validator("plugins")
    @classmethod
    def _plugin_pins(cls, value: str) -> str:
        import re

        pins = [part.strip() for part in value.split(",") if part.strip()]
        if any(not re.fullmatch(r"[+-][a-z][a-z0-9-]{1,23}", pin) for pin in pins):
            raise ValueError("must be comma-separated +plugin or -plugin switches")
        if len({pin[1:] for pin in pins}) != len(pins):
            raise ValueError("must name each pinned plugin once")
        return ",".join(pins)

    @field_validator("tz")
    @classmethod
    def _known_zone(cls, value: str) -> str:
        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError, ValueError:
            raise ValueError("is not a time zone name, such as Europe/Rome or UTC") from None
        return value

    @model_validator(mode="after")
    def _resolve(self) -> Self:
        for name in ("database_url", "identity_database_url", "oidc_client_secret"):
            path = getattr(self, name + "_file")
            if path is not None:
                if getattr(self, name) is not None:
                    raise ValueError(f"Set SPUNTO_{name.upper()} or its _FILE variant, not both.")
                try:
                    setattr(self, name, path.read_text().rstrip("\r\n"))
                except OSError:
                    raise ValueError(f"SPUNTO_{name.upper()}_FILE cannot be read.") from None
        if self.database_url:
            if not self.database_url.startswith(("postgresql://", "postgres://")):
                raise ValueError("SPUNTO_DATABASE_URL must be a PostgreSQL connection URL.")
            if not self.identity_database_url:
                raise ValueError("PostgreSQL hosting requires SPUNTO_IDENTITY_DATABASE_URL.")
            if bool(self.oidc_issuer) != bool(self.oidc_client_id):
                raise ValueError("Set both SPUNTO_OIDC_ISSUER and SPUNTO_OIDC_CLIENT_ID.")
            if self.oidc_client_secret and not self.oidc_issuer:
                raise ValueError("SPUNTO_OIDC_CLIENT_SECRET requires an OIDC issuer and client ID.")
            if self.owner_password or self.owner_password_hash:
                raise ValueError(
                    "Hosted accounts use account credentials; remove the owner password."
                )
        elif any((self.identity_database_url, self.oidc_issuer, self.oidc_client_id)):
            raise ValueError("Hosted identity settings require SPUNTO_DATABASE_URL.")
        elif self.ai_quota_plan or self.ai_quota_limits:
            raise ValueError(
                "SPUNTO_AI_QUOTA_PLAN and SPUNTO_AI_QUOTA_LIMITS apply only to hosted servers."
            )
        for cap, name in self.ai_bindings.items():
            profile = self.ai_profiles.get(name)
            if profile is None or not getattr(profile, cap + "_model"):
                raise OperatorError(
                    f"SPUNTO_AI_{cap.upper()}_PROFILE needs a configured profile and model."
                )
        for name in ("owner_password", "owner_password_hash"):
            path = getattr(self, f"{name}_file")
            if path is None:
                continue
            variable = PREFIX + name.upper()
            if getattr(self, name) is not None:
                raise ValueError(f"Set {variable} or {variable}_FILE, not both.")
            try:
                secret = path.read_text().rstrip("\r\n")
            except OSError as exc:
                raise ValueError(
                    f"{variable}_FILE names {path}, which cannot be read ({exc.strerror})."
                ) from None
            if name == "owner_password_hash":
                try:
                    secret = _argon2id_hash(secret)
                except ValueError as exc:
                    raise ValueError(f"{variable}_FILE names a file that {exc}.") from None
            setattr(self, name, secret)
        if self.owner_password is not None and self.owner_password_hash is not None:
            raise ValueError(
                "Set SPUNTO_OWNER_PASSWORD or SPUNTO_OWNER_PASSWORD_HASH, not both; "
                "the hash is the safer choice."
            )
        if self.owner_password is not None and (reason := password_problem(self.owner_password)):
            raise ValueError(f"SPUNTO_OWNER_PASSWORD is not accepted. {reason}")
        if self.database_url and (self.owner_password or self.owner_password_hash):
            raise ValueError("Hosted accounts use account credentials; remove the owner password.")
        if self.backup_dir is None:
            self.backup_dir = self.data_dir / "backups"
        if self.attachments_dir is None:
            self.attachments_dir = self.data_dir / "attachments"
        if self.web_dir is None:
            self.web_dir = Path(__file__).resolve().parents[3] / "web" / "dist"
        return self

    @property
    def secure(self) -> bool:
        """Whether people reach the server over HTTPS; plain HTTP is allowed loudly (D33)."""
        return self.public_url.startswith("https://")

    @property
    def public_host(self) -> str:
        """The host and port a request must name, as they appear in the public URL."""
        return self.public_url.split("://", 1)[1]

    @property
    def zone(self) -> ZoneInfo:
        return ZoneInfo(self.tz)


def load_settings() -> Settings:
    """Read the environment, or raise OperatorError with one sentence per problem."""
    try:
        return Settings()
    except ValidationError as exc:
        raise OperatorError(" ".join(_explain(error) for error in exc.errors())) from None


def _argon2id_hash(value: str) -> str:
    try:
        if extract_parameters(value.strip()).type is Type.ID:
            return value.strip()
    except InvalidHashError:
        pass
    raise ValueError(
        "must hold an argon2id hash, such as the output of `spunto owner hash-password`"
    )


def _explain(error: ErrorDetails) -> str:
    message = error["msg"].removeprefix("Value error, ")
    if not error["loc"]:
        return message
    field = str(error["loc"][0])
    variable = field if field.isupper() else PREFIX + field.upper()
    if error["type"] == "missing":
        return f"{variable} {_PUBLIC_URL_REQUIRED if field == 'public_url' else 'is required'}."
    if error["type"] == "value_error":
        return f"{variable} {message}."
    return f"{variable}: {message[0].lower()}{message[1:]}."
