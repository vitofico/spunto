"""The `spunto` command: serve, backup, restore and owner (02 §10.1)."""

import argparse
import asyncio
import getpass
import json
import signal
import sys
from contextlib import closing
from datetime import UTC, datetime
from pathlib import Path

import psycopg
import uvicorn

from spunto import logs
from spunto.auth import owner, passwords, tokens
from spunto.auth.tokens import Principal
from spunto.config import Settings, load_settings
from spunto.errors import OperatorError
from spunto.host import ContextError, ManifestError
from spunto.jobs import backup
from spunto.main import create_app, startup
from spunto.store.db import DB_NAME, Store, connect
from spunto.store.migrations import LATEST, schema_version


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="spunto", description="Self-hosted, AI-first notes.")
    commands = parser.add_subparsers(dest="command", required=True, metavar="command")
    commands.add_parser("serve", help="run the server")
    database = commands.add_parser("database", help="operator-only hosted storage commands")
    database_commands = database.add_subparsers(dest="database_command", required=True)
    database_commands.add_parser("migrate", help="migrate PostgreSQL and grant restricted roles")
    importer = database_commands.add_parser(
        "import-sqlite", help="verify and import an offline snapshot"
    )
    importer.add_argument("--snapshot", type=Path, required=True)
    importer.add_argument("--workspace", required=True)
    importer.add_argument("--user", required=True)
    database_commands.add_parser(
        "reconcile-sequences", help="advance imported PostgreSQL identities"
    )
    commands.add_parser("backup", help="write a snapshot of the database now, while it runs")
    ai_commands = commands.add_parser(
        "ai", help="manage the embedding index with the server stopped"
    ).add_subparsers(dest="ai_command", required=True)
    ai_reindex = ai_commands.add_parser("reindex", help="rebuild enabled embedding routes")
    ai_reindex.add_argument(
        "--drop", action="store_true", help="drop retained vectors before rebuilding"
    )
    restore = commands.add_parser("restore", help="replace the database with a snapshot")
    restore.add_argument("snapshot", type=Path)
    owner_commands = commands.add_parser("owner", help="the owner password").add_subparsers(
        dest="owner_command", required=True, metavar="command"
    )
    owner_commands.add_parser("set-password", help="set a new password; signs out the web")
    owner_commands.add_parser(
        "hash-password", help="print an argon2id hash for SPUNTO_OWNER_PASSWORD_HASH"
    )
    account_commands = commands.add_parser(
        "account", help="operator-only hosted local accounts"
    ).add_subparsers(dest="account_command", required=True)
    account_create = account_commands.add_parser(
        "create", help="create a local account and empty personal space"
    )
    account_create.add_argument("--username", required=True)
    account_create.add_argument("--display-name")
    account_reset = account_commands.add_parser(
        "set-password",
        help="reset one exact account's local credential and revoke browser sessions",
    )
    account_reset.add_argument(
        "--user", required=True, help="exact existing account UUID; never an email address"
    )
    account_reset.add_argument(
        "--username", help="required when adding the account's first local credential"
    )
    token_commands = commands.add_parser("token", help="personal access tokens").add_subparsers(
        dest="token_command", required=True
    )
    token_create = token_commands.add_parser("create", help="create a token and show it once")
    token_create.add_argument("--name", required=True)
    token_create.add_argument(
        "--client", choices=["claude-code", "codex", "script", "monitor"], default="script"
    )
    token_create.add_argument("--scope", choices=["read", "write", "status"], default="read")
    token_create.add_argument("--private", action="store_true", dest="private_ok")
    token_create.add_argument("--no-external", action="store_false", dest="external_ok")
    token_create.add_argument("--expires-at", help="optional ISO 8601 expiry with time zone")
    plugin_commands = commands.add_parser("plugins", help="bundled plugin commands").add_subparsers(
        dest="plugins_command", required=True
    )
    plugin_run = plugin_commands.add_parser("run", help="run a plugin with the server stopped")
    plugin_run.add_argument("plugin_id", choices=["markdown-import"])
    plugin_run.add_argument("--user-id", help="exact hosted workspace owner UUID")
    plugin_run.add_argument("--workspace-id", help="exact hosted target workspace UUID")
    plugin_run.add_argument("--path", type=Path, help="Markdown ZIP or folder (operator CLI only)")
    plugin_run.add_argument("--dry-run", action="store_true")
    plugin_check = (
        commands.add_parser("plugin", help="operator plugin checks")
        .add_subparsers(dest="plugin_command", required=True)
        .add_parser("check", help="validate a folder without executing its code")
    )
    plugin_check.add_argument("path", type=Path)
    args = parser.parse_args(argv)

    try:
        if args.command == "account":
            asyncio.run(_hosted_account(args, _new_password("New account password: ")))
            return 0
        if args.command == "database":
            if args.database_command == "migrate":
                from spunto.store.hosted_ops import migrate_from_environment

                asyncio.run(migrate_from_environment())
                print("PostgreSQL migrated; restricted roles granted.")
            elif args.database_command == "import-sqlite":
                from dataclasses import asdict

                from spunto.store.hosted_ops import operator_secret
                from spunto.store.sqlite_import import import_sqlite_snapshot

                report = asyncio.run(
                    import_sqlite_snapshot(
                        args.snapshot,
                        operator_secret("SPUNTO_DATABASE_URL"),
                        args.workspace,
                        args.user,
                        identity_database_url=operator_secret("SPUNTO_IDENTITY_DATABASE_URL"),
                    )
                )
                print(json.dumps(asdict(report), sort_keys=True))
            else:
                from spunto.store.hosted_ops import operator_secret
                from spunto.store.sqlite_import import reconcile_import_sequences

                print(
                    json.dumps(
                        asyncio.run(
                            reconcile_import_sequences(
                                operator_secret("SPUNTO_DATABASE_ADMIN_URL"),
                            )
                        ),
                        sort_keys=True,
                    )
                )
            return 0
        if args.command == "plugin":
            from spunto.host.loader import check_folder

            value = check_folder(args.path)
            print(f"{value['id']}: valid API {value['api']} manifest and Python syntax")
            return 0
        if args.command == "owner" and args.owner_command == "hash-password":
            print(passwords.hash_now(_new_password()))
            return 0
        settings = load_settings()
        if settings.database_url and args.command not in {"serve", "plugins"}:
            raise OperatorError(
                "Use hosted database operator tools or the scoped HTTP API; "
                "SQLite backup, restore and owner commands do not operate on PostgreSQL."
            )
        logs.configure(settings.log_level, settings.log_format)
        match args.command:
            case "serve":
                asyncio.run(_serve(settings))
            case "backup":
                print(backup.backup_now(settings))
            case "restore":
                print(backup.restore(settings, args.snapshot))
            case "owner":
                asyncio.run(_set_password(settings))
            case "token":
                asyncio.run(_create_token(settings, args))
            case "plugins":
                asyncio.run(_run_plugin(settings, args))
            case "ai":
                asyncio.run(_ai_reindex(settings, args.drop))
    except (OperatorError, ContextError, ManifestError, ValueError) as exc:
        print(f"spunto: {exc}", file=sys.stderr)
        return 1
    except psycopg.Error:
        print(
            "spunto: PostgreSQL operation failed; check database access and schema.",
            file=sys.stderr,
        )
        return 1
    return 0


async def _run_plugin(settings, args):
    if settings.database_url:
        if not args.user_id or not args.workspace_id:
            raise OperatorError("Hosted imports require both --user-id and --workspace-id.")
        from spunto.operator_workspace import scoped_operator_services

        context = scoped_operator_services(settings, args.user_id, args.workspace_id)
    else:
        if args.user_id or args.workspace_id:
            raise OperatorError("--user-id and --workspace-id apply only to hosted PostgreSQL.")
        # SQLite startup takes the data lock; hosted operations use the operator gate.
        context = startup(settings)
    async with context as services:
        host = services.host
        if args.path is None:
            raise OperatorError("markdown-import needs --path.")
        from spunto.host import Ctx
        from spunto.plugins.markdown_import import read_path, run_import

        await host.enable(args.plugin_id, permissions=host.manifests[args.plugin_id]["permissions"])
        ctx = Ctx(
            host,
            args.plugin_id,
            principal=Principal("operator", "session", "Owner", "full", private_ok=True),
        )
        source = await ctx.to_thread(read_path, args.path)
        report = await run_import(ctx, source, dry_run=args.dry_run)
        print(json.dumps(report, ensure_ascii=False, sort_keys=True))


async def _ai_reindex(settings, drop):
    async with startup(settings) as services:
        await services.host._sync_vectors()
        await services.vectors.reindex(drop=drop)
        await services.vectors.backfill()
        print(json.dumps(await services.vectors.status(), sort_keys=True))


async def _serve(settings: Settings) -> None:
    async with startup(settings) as services:
        server = uvicorn.Server(
            uvicorn.Config(
                create_app(services),
                host="0.0.0.0",  # noqa: S104 - inside a container, the published port decides exposure
                port=settings.port,
                lifespan="off",
                log_config=None,
                access_log=False,
                proxy_headers=False,
                server_header=False,
            )
        )
        # uvicorn re-raises the stop signal once it has stopped serving. Catching it
        # here lets the database close and the data lock go before the process exits.
        loop = asyncio.get_running_loop()
        for stop in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(stop, server.handle_exit, stop, None)
        await server.serve()


async def _set_password(settings: Settings) -> None:
    if settings.owner_password is not None or settings.owner_password_hash is not None:
        raise OperatorError(
            "The owner password comes from the environment (SPUNTO_OWNER_PASSWORD or "
            "SPUNTO_OWNER_PASSWORD_HASH); change it there and restart."
        )
    db = settings.data_dir / DB_NAME
    if not db.exists():
        raise OperatorError(f"There is no database at {db} yet; start the server once first.")
    with closing(connect(db)) as conn:
        if schema_version(conn) != LATEST:
            raise OperatorError("The database needs an upgrade; start the server once first.")
    new_hash = passwords.hash_now(_new_password())
    # Safe beside a running server: SQLite serialises the two writers.
    store = await Store.open(db, readers=1)
    try:
        await owner.set_password(store, new_hash, "ui")
    finally:
        await store.close()
    print("Owner password set. Every web session has been signed out.")


async def _create_token(settings: Settings, args: argparse.Namespace) -> None:
    if not 1 <= len(args.name.strip()) <= 120:
        raise OperatorError("--name must contain 1 to 120 characters.")
    expiry = None
    if args.expires_at:
        try:
            moment = datetime.fromisoformat(args.expires_at)
            if moment.tzinfo is None or moment <= datetime.now(UTC):
                raise ValueError
            expiry = int(moment.timestamp() * 1000)
        except ValueError:
            raise OperatorError(
                "--expires-at must be a future ISO 8601 time with a time zone."
            ) from None
    db = settings.data_dir / DB_NAME
    if not db.exists():
        raise OperatorError("Start the server and finish owner setup before creating a token.")
    with closing(connect(db)) as conn:
        if schema_version(conn) != LATEST:
            raise OperatorError("The database needs an upgrade; start the server once first.")
    store = await Store.open(db, readers=1)
    try:
        if await owner.password_hash(store) is None:
            raise OperatorError("Finish owner setup before creating a token.")
        _, token = await tokens.create(
            store,
            name=args.name,
            client=args.client,
            scope=args.scope,
            private_ok=args.private_ok,
            external_ok=args.external_ok,
            expires_at=expiry,
        )
        print(token)
    finally:
        await store.close()


async def _hosted_account(args, password):
    import uuid

    from spunto.api.errors import Problem
    from spunto.auth.accounts import Accounts
    from spunto.store.hosted_ops import operator_secret

    accounts = await Accounts.open(operator_secret("SPUNTO_IDENTITY_DATABASE_URL"))
    try:
        if args.account_command == "create":
            account, _, _ = await accounts.register_local(
                args.username, password, args.display_name, create_session=False
            )
            print("Hosted account created: " + account["id"])
        else:
            try:
                parsed = uuid.UUID(args.user)
                if args.user not in (str(parsed), parsed.hex):
                    raise ValueError
                user_id = args.user
            except ValueError:
                raise OperatorError("--user must be the exact existing account UUID.") from None
            await accounts.set_local_password(
                user_id, "", args.username, password, None, operator=True
            )
            print("Local credential set for the exact account; all browser sessions revoked.")
    except Problem as exc:
        raise OperatorError(exc.detail) from None
    finally:
        await accounts.close()


def _new_password(prompt="New owner password: ") -> str:
    """Ask twice on a terminal; read one line when piped, for scripts."""
    if sys.stdin.isatty():
        password = getpass.getpass(prompt)
        if getpass.getpass("Again: ") != password:
            raise OperatorError("The two passwords differ.")
    else:
        password = sys.stdin.readline().rstrip("\r\n")
    if reason := passwords.problem(password):
        raise OperatorError(reason)
    return password
