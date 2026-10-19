from datetime import time
from ipaddress import ip_network

import pytest

from spunto.auth.passwords import hash_now
from spunto.config import load_settings
from spunto.errors import OperatorError


def error_for(env, **variables: str) -> str:
    env(**variables)
    with pytest.raises(OperatorError) as raised:
        load_settings()
    return str(raised.value)


def test_defaults(env, data_dir):
    settings = load_settings()
    assert settings.port == 8080
    assert settings.data_dir == data_dir
    assert settings.backup_dir == data_dir / "backups"
    assert settings.backup_time == time(3, 30)
    assert (settings.backup_keep_daily, settings.backup_keep_weekly) == (7, 4)
    assert (settings.log_level, settings.log_format, settings.tz) == ("info", "json", "UTC")
    assert settings.owner_password is None and settings.owner_password_hash is None
    assert settings.local_registration_enabled is False


def test_postgres_accounts_do_not_require_an_oidc_provider(env):
    env(
        SPUNTO_DATABASE_URL="postgresql://localhost/spunto",
        SPUNTO_IDENTITY_DATABASE_URL="postgresql://localhost/spunto_identity",
    )
    settings = load_settings()
    assert settings.oidc_issuer is None and settings.oidc_client_id is None
    assert settings.local_registration_enabled is False
    env(SPUNTO_LOCAL_REGISTRATION_ENABLED="true")
    assert load_settings().local_registration_enabled is True


def test_optional_oidc_requires_complete_configuration(env):
    assert "Set both SPUNTO_OIDC_ISSUER" in error_for(
        env,
        SPUNTO_DATABASE_URL="postgresql://localhost/spunto",
        SPUNTO_IDENTITY_DATABASE_URL="postgresql://localhost/spunto_identity",
        SPUNTO_OIDC_ISSUER="https://identity.example.test",
    )


@pytest.mark.parametrize(
    ("given", "public_url", "host", "secure"),
    [
        (
            "HTTPS://Spunto.Example.org:443/",
            "https://spunto.example.org",
            "spunto.example.org",
            True,
        ),
        ("http://192.168.1.10:8080", "http://192.168.1.10:8080", "192.168.1.10:8080", False),
        ("http://localhost:80", "http://localhost", "localhost", False),
        ("https://[::1]:8443", "https://[::1]:8443", "[::1]:8443", True),
    ],
)
def test_public_url_is_reduced_to_an_origin(env, given, public_url, host, secure):
    env(SPUNTO_PUBLIC_URL=given)
    settings = load_settings()
    assert (settings.public_url, settings.public_host, settings.secure) == (
        public_url,
        host,
        secure,
    )


@pytest.mark.parametrize(
    ("given", "says"),
    [
        ("", "SPUNTO_PUBLIC_URL is required"),
        ("spunto.example.org", "SPUNTO_PUBLIC_URL must start with https:// or http://"),
        ("ftp://spunto.example.org", "SPUNTO_PUBLIC_URL must start with https:// or http://"),
        ("https://example.org/spunto", "SPUNTO_PUBLIC_URL must be the scheme, host and"),
        ("https://user@example.org", "SPUNTO_PUBLIC_URL must be the scheme, host and"),
        ("https://example.org:99999", "SPUNTO_PUBLIC_URL has a port that is not a number"),
    ],
)
def test_bad_public_url_names_the_variable(env, given, says):
    assert says in error_for(env, SPUNTO_PUBLIC_URL=given)


def test_missing_public_url_explains_what_it_is(env, monkeypatch):
    monkeypatch.delenv("SPUNTO_PUBLIC_URL")
    with pytest.raises(OperatorError, match="SPUNTO_PUBLIC_URL is required: the address"):
        load_settings()


def test_every_problem_is_reported_at_once(env):
    message = error_for(env, SPUNTO_PORT="eighty", SPUNTO_BACKUP_KEEP_DAILY="0", TZ="Mars/Base")
    assert "SPUNTO_PORT: input should be a valid integer" in message
    assert "SPUNTO_BACKUP_KEEP_DAILY: input should be greater than or equal to 1" in message
    assert "TZ is not a time zone name" in message


def test_blank_values_mean_unset(env):
    env(SPUNTO_PORT="", SPUNTO_OWNER_PASSWORD=" ", SPUNTO_TRUSTED_PROXIES="")
    settings = load_settings()
    assert settings.port == 8080
    assert settings.owner_password is None
    assert settings.trusted_proxies == ()


def test_settings_any_case(env):
    env(SPUNTO_LOG_LEVEL="DEBUG", SPUNTO_LOG_FORMAT="Text")
    settings = load_settings()
    assert (settings.log_level, settings.log_format) == ("debug", "text")


def test_trusted_proxies_are_networks(env):
    env(SPUNTO_TRUSTED_PROXIES="10.0.0.0/8, 192.168.1.1,fd00::/8")
    assert load_settings().trusted_proxies == (
        ip_network("10.0.0.0/8"),
        ip_network("192.168.1.1/32"),
        ip_network("fd00::/8"),
    )
    message = error_for(env, SPUNTO_TRUSTED_PROXIES="10.0.0.0/8,traefik")
    assert "SPUNTO_TRUSTED_PROXIES 'traefik' is not an IP address or CIDR range" in message


def test_secret_from_file(env, tmp_path):
    secret = tmp_path / "owner_password"
    secret.write_text("correct horse battery\n")
    env(SPUNTO_OWNER_PASSWORD_FILE=str(secret))
    assert load_settings().owner_password == "correct horse battery"


def test_secret_and_its_file_together_are_refused(env, tmp_path):
    secret = tmp_path / "owner_password"
    secret.write_text("correct horse battery")
    message = error_for(
        env, SPUNTO_OWNER_PASSWORD="another one", SPUNTO_OWNER_PASSWORD_FILE=str(secret)
    )
    assert message == "Set SPUNTO_OWNER_PASSWORD or SPUNTO_OWNER_PASSWORD_FILE, not both."


def test_unreadable_secret_file_names_the_variable(env, tmp_path):
    message = error_for(env, SPUNTO_OWNER_PASSWORD_FILE=str(tmp_path / "missing"))
    assert message.startswith("SPUNTO_OWNER_PASSWORD_FILE names ")
    assert "cannot be read (No such file or directory)" in message


def test_password_hash_must_be_argon2id(env, tmp_path):
    good = hash_now("correct horse battery")
    env(SPUNTO_OWNER_PASSWORD_HASH=good)
    assert load_settings().owner_password_hash == good

    message = error_for(env, SPUNTO_OWNER_PASSWORD_HASH="sesame-plaintext")
    assert "SPUNTO_OWNER_PASSWORD_HASH must hold an argon2id hash" in message
    assert "sesame-plaintext" not in message

    hash_file = tmp_path / "hash"
    hash_file.write_text("sesame-plaintext\n")
    env(SPUNTO_OWNER_PASSWORD_HASH="", SPUNTO_OWNER_PASSWORD_HASH_FILE=str(hash_file))
    message = error_for(env)
    assert "SPUNTO_OWNER_PASSWORD_HASH_FILE names a file that must hold an argon2id hash" in message
    assert "sesame-plaintext" not in message


def test_password_and_hash_together_are_refused(env):
    message = error_for(
        env,
        SPUNTO_OWNER_PASSWORD="correct horse battery",
        SPUNTO_OWNER_PASSWORD_HASH=hash_now("correct horse battery"),
    )
    assert "Set SPUNTO_OWNER_PASSWORD or SPUNTO_OWNER_PASSWORD_HASH, not both" in message


def test_password_from_the_environment_meets_the_minimum(env):
    message = error_for(env, SPUNTO_OWNER_PASSWORD="short")
    assert "SPUNTO_OWNER_PASSWORD is not accepted. The password needs at least 8" in message
