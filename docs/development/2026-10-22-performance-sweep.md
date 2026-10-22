# Execution performance sweep, 2026-10-22

The owner requested a multi-agent sweep for code optimization, execution performance
and leaner code, with proposed improvements implemented. The sweep started from
`569b97c` and covered server note/search/plugin reads, authentication and vector
caching; web rendering, timeline/search/query hooks and editing; and Android local
storage, search, sync and Compose rendering. An independent review of the complete
patch, including the final Android memory refinement, found no actionable correctness
issues. Historical design documents 01–05 remain unchanged.

## Implemented improvements

| Path | Avoidable work | Change |
|---|---|---|
| Server notes and plugin queries | Four metadata reads for every returned note | Fetch labels, actors, suggestions and annotations for the complete result page, retaining per-note ordering, visibility checks, expiry and output limits. Single-note callers use the same serializer. |
| Server body edits | Deleting and rebuilding unchanged label memberships | Compare parsed label keys with existing memberships and write only when they change. FTS and other body-derived state still update. |
| Web note cards and timeline | Repeated sanitization, group scans and locale date formatting during typing | Memoize unchanged cards and sanitized HTML; group notes in one Map pass; retain flattened pages/groups until query data or the local calendar day changes. |
| Android note persistence | Parsing tasks, folding body text and rewriting labels for metadata-only updates | Reuse stored body-derived values when their source text is unchanged. Actual body changes still rebuild the affected values. |
| Android epoch reconciliation | One tombstone lookup per local note and repeated scans/decoding of the growing outbox | Exclude initial tombstones in SQL; decode/group queued operations once inside each reconciliation transaction. Keep fresh note/tombstone checks after the network response and track restores added within the transaction. |

No dependencies, database migrations, operator settings or API fields were added.
Offline drafts, immutable operations, merge rules and permission checks remain part
of the regression contracts.

## Synthetic measurements

These are local fixtures, not production or physical-phone performance claims.
Operation counts are deterministic regression checks; timing depends on the host.

| Fixture / measured work | Before | After |
|---|---:|---:|
| Server list of 50 notes, SQL reads | 201 | 5 |
| Server keyword search with 50 hits, SQL reads | 202 | 6 |
| Server blank search with 50 hits, SQL reads | 202 | 6 |
| Plugin query of 10 notes, SQL reads | 41 | 5 |
| Server body edit with two unchanged labels, label writes | 5 | 0 |
| Web grouping of 5,000 notes over 30 ordinary days, locale formatter calls | 5,000 | 30 |
| Web grouping of 5,000 notes including 500 pinned, median of seven runs | 105.04 ms | 2.30 ms |
| Android ten metadata-only note updates, label writes | 20 | 0 |
| Android 24-note reconciliation, full outbox reads through first push selection | 47 | 2 |

Twenty web composer edits add no grouping, locale-formatting or sanitization calls;
query updates and date rollover still refresh the rendered cards and groups. The
Android reconciliation fixture includes typing and a tombstone during a held network
response, an existing restore, duplicate restore IDs and exact superseded operation
IDs.

The existing `scripts/benchmark-server.py --notes 50000 --samples 10` passes all
keyword/MCP latency and answer-size budgets before and after the changes. Complete
keyword p95 observations in milliseconds are below. The after run overlapped other
validation, so these observations do not establish controlled timing improvements.

| Query | Before | After |
|---|---:|---:|
| Common | 36.553 | 35.748 |
| Rare | 10.116 | 1.828 |
| Prefix | 48.172 | 44.751 |
| Accent | 31.397 | 25.257 |
| Phrase | 33.924 | 27.361 |
| Label | 37.049 | 31.928 |
| Empty / recent | 23.685 | 19.229 |

Broad-search cost still includes FTS candidate ranking. MCP timings are mixed;
all seven p95 values remain below the existing 300 ms budget. The measurements
support eliminating repeated database work, not a universal end-to-end speedup.

## Verification

- Server: 1,062 passed, 85 skipped without PostgreSQL fixture variables; Ruff lint
  and format passed. The separate real PostgreSQL 18 run passed 83 tests after the
  final plugin-query change, including hosted authentication, OIDC and SQLite import.
- Web: 291 tests in 39 files passed; API and token generation had no drift;
  typecheck, formatting and build passed. Initial JavaScript is 135,094 of 150,000
  gzip bytes; fonts total 319,488 of 320,000 bytes.
- Android: 334 unit tests passed, one optional real-server integration test skipped,
  zero failures/errors. Debug and instrumentation APKs assembled; the debug APK
  budget check passed at 13,745,909 of 15,000,000 bytes of entries. Tests cover core
  model/plugins, local storage, sync, dictation and the app. Affected local-storage
  and sync module lint passed with zero issues. No device data was changed, and
  device instrumentation and physical-phone performance were not run.
- Browser fixtures: 78 Chromium/WebKit tests passed. All 39 Firefox tests failed
  before app execution with `Could not find profile folder`; standalone
  `about:blank` launch reproduces the failure with default and `/private/tmp`
  profiles. This matches the previously recorded local Firefox sandbox limitation.
  Thirty tests requiring a real packaged server were skipped, not passed.
- Repository-wide and final changed-file pre-commit checks passed. A separate
  Gitleaks directory scan of all 15 changed/new files also passed, including the
  untracked regression files. Generated Android design tokens had no drift.

New regression coverage checks metadata ordering, plugin caps and expiry, hidden
references, workspace isolation, rendered HTML, checkbox state/permissions, query
updates, local-day grouping and midnight rollover, as well as offline reconciliation.
Raw local logs and synthetic measurement scripts are retained under
`/tmp/spunto-perf-sweep/` and its `-server`, `-web` and `-android` sibling directories.

## Follow-up opportunities

- Batch referenced-note visibility checks only with dedicated privacy fixtures;
  these remain conditional per-reference reads.
- Consider sync serialization batching separately: byte-limited pages should not
  prefetch large bodies that will not be returned.
- Profile Android label-filter reparsing and repeated outbox batch decoding before
  adding more caches. Existing ranking, paging and vector caches already avoid
  several potential costs.

These follow-ups are proposals; this patch does not implement them.
