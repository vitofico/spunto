# Contributing to Spunto

Bug reports, focused improvements and documentation fixes are welcome. Search
the [issues](https://github.com/vitofico/spunto/issues) first. For a larger feature,
open a discussion in an issue before changing the product's behavior. Spunto
aims to keep capture simple and organize notes through labels; features beyond
the core generally belong in plugins.

For a bug report, include the app/server version, platform, expected behavior and
reproduction steps. Screenshots and logs help when they contain no personal
notes or credentials. Follow [SECURITY.md](SECURITY.md) for vulnerabilities.

To correct wording or add a language, follow the [translation guide](docs/translating.md).
Translation files are discovered automatically; no application-code changes are needed.

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

`scripts/check-ci` runs the web, SQLite server and Android build checks together.
Use `scripts/check-ci web`, `server` or `android` for a focused change. These are
the same commands used by the hosted jobs, including production bundle and APK
budgets. Generated API and theme checks preserve local files and ignore unrelated
uncommitted work. See `scripts/check-ci --help` for prerequisites and the browser
mode. PostgreSQL, packaged-image, emulator and security checks remain additional
gates; run the affected ones before submission. Android CI passes a disposable
server URL for real sync integration; local Android checks without that fixture
do not establish real-server acceptance.

For Android UI changes, run `ANDROID_SERIAL=emulator-<port> scripts/check-ci native`
against a disposable, booted emulator. The command builds and installs the debug
app and instrumentation APK, reinstalls their debug packages with fresh data,
runs the native suite, and retains
its output. It refuses physical devices and leaves the release package alone.
Use `SPUNTO_NATIVE_CLASS` for a focused class or `Class#method` during iteration;
run the full suite before submission. `SPUNTO_NATIVE_LOG` selects the log path.
Match CI's API 35 emulator and its default narrow viewport for layout changes.
The local smoke validation used API 36 at 320x640; it does not replace the
hosted API 35 run. Both runners disable system animations before instrumentation.
Local Docker Gradle commands use a separate cache per checkout and serialize
access with a shared container lock, so parallel terminals wait instead of racing
Gradle's process IDs. The first run after this change fills a fresh cache. The
debug signing key remains in its existing shared volume, preserving upgrade
signatures. Changes to the build Dockerfile automatically select a fresh image.
Shared preflight and native build commands use two Gradle workers, disable
project parallelism and disable filesystem watching to bound memory use during
concurrent local work and match hosted validation.

Android checks retain the 15,000,000-byte compressed ZIP payload gate in Gradle.
The debug APK container may be up to 25,000,000 bytes to accommodate signing and
alignment overhead. Signed releases keep their separate 15,000,000-byte gate.

The initial JavaScript gzip target is 150,000 bytes. Builds warn above that
target and fail above 160,000 bytes, leaving room for small compression changes
while catching substantial growth. The combined WOFF2 font cap is 320,000 bytes.

Keep changes focused, add tests for meaningful behavior changes, and explain the
problem, resulting behavior and validation in your pull request. Preserve
existing release tags and never commit signing keys, local databases, private
backups or credentials.

## Contributor License Agreement

Before a pull request from anyone other than the maintainer can be merged, each
contributor on it must sign the [Spunto Contributor License Agreement](CLA.md)
by posting a new comment on the pull request containing **exactly**:

```
I have read the Spunto Contributor License Agreement and I hereby sign the CLA
```

The maintainer reviews pull request comments before merge and will not merge
until every required signer has commented the phrase above. Automation bots that
do not author copyrightable changes (`dependabot[bot]`, `github-actions[bot]`,
`renovate[bot]`) are exempt.

The CLA does **not** change Spunto's license. What it does is let the maintainer
also license accepted contributions under other terms, including proprietary
ones for a hosted Spunto service, without re-collecting permission from every
past contributor. See [`CLA.md`](CLA.md) section 4.

## License

Spunto is licensed under [AGPL-3.0-only](LICENSE). By submitting a contribution
you agree it is provided under AGPL-3.0-only and under the terms of the
[Spunto CLA](CLA.md).
