# Contributing to Spunto

Bug reports, focused improvements and documentation fixes are welcome. Search
the [issues](https://github.com/vitofico/spunto/issues) first. For a larger feature,
open a discussion in an issue before changing the product's behavior. Spunto
aims to keep capture simple and organize notes through labels; features beyond
the core generally belong in plugins.

For a bug report, include the app/server version, platform, expected behavior and
reproduction steps. Screenshots and logs help when they contain no personal
notes or credentials. Follow [SECURITY.md](SECURITY.md) for vulnerabilities.

## Working on the code

Start with the [development documentation](docs/README.md) and the repository's
[working instructions](AGENTS.md). The Python server, React web client and native
Android client live in `server/`, `web/` and `android/`. Shared API and sync
contracts live in `spec/`.

- Server: use the committed `uv.lock`; run Ruff and the affected pytest tests.
- Web: use the pinned pnpm version and lockfile; run type checking, unit tests,
  formatting and a production build. Check changed flows in Playwright.
- Android: follow the [Android development guide](android/README.md) for the
  toolchain, JVM tests, lint and emulator tests.
- Before submitting, run `pre-commit run --all-files` and the checks required by
  the affected project instructions. Use synthetic fixtures for tests.

Keep changes focused, add tests for meaningful behavior changes, and explain the
problem, resulting behavior and validation in your pull request. Preserve
existing release tags and never commit signing keys, local databases, private
backups or credentials. Contributions are licensed under the project's
[AGPL-3.0-only license](LICENSE).
