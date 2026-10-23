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
