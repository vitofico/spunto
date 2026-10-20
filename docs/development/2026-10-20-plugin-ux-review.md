# Android UX and plugin review, 2026-10-20

The owner requested simpler note reading/editing, fewer repeated controls, working
AI plugins, a note chooser for Links, visible Tidy changes, understandable status,
model selection, secondary date filtering and a discoverable Ask entry. This record
covers the server/plugin audit; Android integration evidence follows separately.
Historical approved documents 01–05 remain unchanged.

## Findings and fixes

The current bundled catalog contains **26 plugins**. Every manifest and declared
server command binding was inspected. The original catalog grew through Context
packs and Decision journal, while the Blinko importer was removed.

Production's content-free aggregate inspection found Label suggestions and Label
gardener in `failed` with `plugin_circuit_open`, and no AI accounting rows for either
plugin. Both read the label vocabulary before calling a model. That query selected
`l.path` and ordered by `l.key` while grouping only by `l.id`. SQLite accepted it;
PostgreSQL rejected it because its label primary key includes the workspace. The
original query reproduces SQLSTATE `42803` on disposable PostgreSQL; grouping all
selected/ordered label columns succeeds. The regression now runs both label
commands against real PostgreSQL, reaches their synthetic model, preserves note
text and produces the expected pending label suggestion.

Three further reliability issues were fixed:

- Label suggestions can use fresh vocabulary/tagged examples while optional
  semantic neighbours are unavailable. Invalid vectors and privacy refusals still
  fail closed.
- Background plugin jobs now pause for an index that is preparing, without
  consuming attempts or opening the plugin circuit. Previously `VectorUnavailable`
  counted as a terminal validation failure.
- Title and summary now skips unavailable/private/machine/trashed input. It
  previously attempted an annotation after its eligibility check returned no note,
  creating an avoidable failed job.

Links now presents recent/searchable note titles through the existing portable
form vocabulary. Internal source/destination IDs and revisions use the already
hidden `source_choices`, `note_id` and `base_version` fields. Numbered captions
disambiguate repeated titles. Stale source/destination choices return a conflict;
existing links are not proposed again. Applied links use escaped destination
titles; a public source retains a generic caption for a private destination so its
title is not published. Existing `target_id` API callers still work.

Tidy returns grouped changes with two context lines instead of presenting the
entire note twice. The full body remains the reviewed, versioned proposal, and the
accept control says **Apply changes**. Long-line previews retain the explicit full
Review fallback. Independent Android review caught two portable comparison edge
cases: final newlines disappeared with `splitlines()`, and more than 100 grouped
hunks exceeded the native vocabulary budget. Both now have regression coverage;
final empty lines are preserved, and many scattered changes use one bounded full
hunk. The Android reviewers found no remaining server blockers after these fixes.

Missing requirements and failed model checks now explain why a plugin is Waiting.
Command errors retain stable codes and explain provider credentials, unavailable
models/indexes, timeouts, usage limits and invalid answers. Failure logs include
only plugin/operation, exception class and a validated SQLSTATE, without exception
messages, SQL parameters, provider bodies, note text or credentials.

## Catalog review

All rows below have synthetic contract coverage. Live credentials, external
mailboxes, physical microphones and owner-note quality judgments are separate
acceptance checks.

| Plugin | Reviewed behavior and result |
|---|---|
| Agent inbox | Reviewed membership syncs; later agent edits reopen review. Command/read scope and disabled-generation checks retained. |
| Ask your notes | Bounded grounded answer, eligible citations, expiring unsaved answers and explicit idempotent save. Invalid/stale sources fail closed; Android entry discovery is reviewed separately. |
| Context packs | Manual prose, source chooser, revision baselines, atomic competing edits, private/external provenance and idempotent note copies. No automatic AI rewrite. |
| Decision journal | Manual decisions and separate dated outcome notes; concurrent review recovery and private-source handling. No automatic reminders or model judgments. |
| Develop a note | Eligible source, bounded grounded result, citations and idempotent machine note. Owner source is preserved. |
| Dictation | Device-only manifest, no server jobs/model dependency. Native speech availability and microphone flow remain device checks. |
| Weekly digest | Quiet-week no-op, sampled weekly coverage, exclusions, citations, machine provenance and one result per week. |
| Duplicates | Embeddings only; versioned, explicit merge proposals and remembered pairs. Index readiness now pauses jobs safely. |
| Email in | Explicit TLS destination/sender allowlist, external provenance, bounded MIME parsing, checkpoints, replay receipts and environment-only secrets. No outgoing mail. |
| Image text | Eligible linked images only; cached OCR/search text and panels, attachment/privacy races and no note-body rewrite. Missing vision support waits with a reason. |
| Label gardener | PostgreSQL vocabulary fix; bounded taxonomy evidence, proposal quotas, permanent refusal fingerprints and atomic reviewed changes. |
| Label suggestions | PostgreSQL vocabulary fix; optional-neighbour fallback; fresh usage examples; at most three suggestions/one new label; opt-in high-confidence existing-label acceptance. |
| Link enrichment | Guarded public fetch, explicit source consent, cached details, optional bounded AI summary and reviewed title/label proposals. Works with chat off. |
| Links | Title-based search/browse, duplicate-title captions, revision guards and duplicate-link suppression; backlinks respect private-source visibility. |
| Markdown import | Preview before queueing, archive/path/size bounds, replay receipts, owner edits preserved and export round-trip coverage. Native Android continues to use the web import flow. |
| Quick capture | Device-only manifest, default switch and persistent enable/disable behavior; native widget/tile behavior is a device check. |
| Related notes | Live bounded references, current visibility, privacy/machine filtering and conflict-family exclusion. Index readiness is reported clearly. |
| Resurface | One daily slip, timezone/anniversary handling, age bounds, machine exclusion, spacing and daily idempotency. |
| Semantic search | Bounded semantic ranking, score floor and core keyword blending/visibility. Keyword search survives unavailable semantics. |
| Tasks | Checkbox parsing agrees with core open-task counts; ordinary notes and synced progress metadata; disabled output disappears. |
| Telegram | Numeric allowlist, private chat/sender matching, bounded batches, external provenance and restart-safe checkpoint receipts. No outgoing messages. |
| Tidy text | Meaning/label/link guards, versioned suggestions, focused comparison and full-result acceptance. Rejected versions are not immediately reoffered. |
| Title and summary | Long-note threshold, bounded coverage, reviewed title and synced summary; unavailable inputs now skip without a failed annotation. |
| Translate | Explicit Italian/English choice, meaning/Markdown/label/link guards, live result and separate idempotent machine-note save. |
| Voice memos | Recordings retained, bounded audio validation, eligible transcription, cached words, combined reviewed proposal and source/attachment race checks. Missing transcription still permits recording capture. |
| Webhooks | Explicit destinations, public-event eligibility rechecked before send, stable event IDs, retries and private/tombstone refusal. No credentials or note bodies in diagnostic logs. |

## Model selection and recovery

Existing server model selection was reviewed rather than replaced. Space owners
can inspect available configured-provider inventory and save per-capability model
choices in web Settings → AI. A saved choice survives restart and is scoped to the
library/workspace. Profile objects stay immutable across spaces; endpoints, keys
and limits remain operator environment configuration. Explicit plugin profile
routes take priority over default bindings. Embedding replacement probes dimensions
and backfills coherently before activation. Invalid inventory/choices preserve
the previous selection; resets are explicit.

Failed lifecycle state intentionally survives restart. After deployment, **Retry**
or the owner API `POST /api/v1/plugins/{id}/enable` with current manifest grants
resets the circuit and requeues retained dead jobs. No raw database lifecycle edit
is required. Production recovery and synthetic acceptance belong to the driver.

The driver's live follow-up confirmed healthy chat/embedding checks and a successful
Label suggestions call after recovery. Label gardener remained Enabled but rejected
a model answer after both validation attempts, leaving its scheduled job dead with
no proposal writes. The shared gateway now includes the complete schema on every
validation retry, including wrong types and semantic failures. The gardener prompt
also states the exact label grammar and explicitly permits an empty proposal list
when evidence is weak. Two synthetic regressions recover from wrong types and
spaced labels to valid abstention; permanently invalid batches still fail closed.
Final-invalid diagnostics contain only fixed validation categories, never model
values, field paths, note text or exception messages.

## Verification

- Final complete server suite: **1,132 passed**, including real PostgreSQL,
  hosted accounts, imports, plugin jobs, model selection and all follow-up
  regressions. The disposable database uses the supported runtime/identity
  provisioning helper. No failing check remains.
- Gardener follow-up AI/plugin/budget suites: 142 passed, including schema-guided
  recovery from wrong types and invalid label grammar, safe diagnostics and
  permanently invalid fail-closed cases.
- Focused AI/host/API/model suites: 182 passed.
- Complete plugin suite across all 26 manifests, capture/inbound/import/device
  contracts and manual workflows: 268 passed.
- Real disposable PostgreSQL/pgvector suite: 16 passed, including both label
  commands. The original/fixed vocabulary query comparison separately confirms
  SQLSTATE `42803` and corrected success.
- Whole-server Ruff lint and format checks pass; `git diff --check` passes.
- Test logs: `/tmp/spunto-server-final-full-tests.log`,
  `/tmp/spunto-server-gardener-followup-tests.log`, `/tmp/spunto-server-full-tests.log`,
  `/tmp/spunto-server-final-rerun.log`, `/tmp/spunto-server-collection.log`,
  `/tmp/spunto-server-plugin-tests.log`,
  `/tmp/spunto-server-all-plugin-tests.log`,
  `/tmp/spunto-server-postgres-tests.log`. These contain synthetic fixtures only.

Provider fixtures prove contracts and failure behavior, not whether a particular
live model's label choices are useful. This review does not claim physical-phone,
real mailbox, real Telegram, production note-content inspection or live model
quality acceptance.

## Android integration and delivery

The reader keeps Edit/Done in reach while flags, history, export and plugin tools
live under More. Done and either Back action finish the same editor lease and
retain a selected recording. Date ranges now use a separate filter sheet with
calendar selection, presets, validation and explicit Apply/Clear. Ask has a direct
toolbar destination when enabled, independent of the search query. Its requests
and answers are fenced by the account and replica epoch.

Note tools appear once in a searchable sheet. Commands sync pending edits first,
show progress/errors, cancel on dismissal and discard obsolete account results.
Long form choices use a searchable title picker; internal IDs/revisions remain
hidden. Tidy displays highlighted insertions/removals, whitespace descriptions and
an optional full comparison. Live panels load on expansion and expose retryable
errors. Status uses sync/model/index summaries, with diagnostics behind a separate
control. Settings now exposes AI models directly. Protected/capture surfaces use
the active replica's cached palette, appearance mode and system-bar contrast.
Private notes retain local tools such as Links while tools with AI/network
permissions stay hidden, matching the server's existing egress policy.

- 304 Android unit tests pass across model, Room, sync, plugin, dictation and app
  modules. Debug/release lint and clean app/test/release assembly pass.
- 32 targeted emulator tests pass for reader editing and Back, date filters,
  navigation, Ask, plugin vocabulary/rendering, theme cache/replica switching and
  recovery/retention of generated audio. Three additional real-API native workflows
  pass for Links, Context packs and Decision journal. Link acceptance selects a
  title, hides identifiers, preserves the source until review and then applies it.
- The original emulator remains intact. Tests used a separate task-owned emulator
  because the existing debug app's signing key differed from the local build key.
- 224 web tests, web type/format/build checks, 98 release-tooling tests and the
  repository hooks pass. Independent implementation and follow-up reviews have no
  open findings. Historical documents 01–05 remain unchanged.

Android **2026.10.20.4**, code **18**, retains AGPL-3.0-only at the owner's explicit
choice. The local signed APK is 4,639,979 bytes, SHA-256
`834763729d53721c437bcf7cb28ae4b6aa4537ff139a8da35263da44da9e4ae2`.
Package, version, owner certificate, non-debuggable state, baseline profiles and
size pass verification. The signed release launches and opens a synthetic note;
capture and saving also pass the native acceptance checks. GitHub APK publication
and physical-phone acceptance are separate; the local artifact is not a newly
published Obtainium release.

## Hosted recovery

The final image is `spunto-hosted:2026.10.20-ux-e5d556af44af`. Hardened synthetic
startup, backup and restore smoke checks pass. The existing hosted deployment was
updated after an on-node PostgreSQL backup; public HTTPS readiness is HTTP 200.
The previous image and backup remain available for rollback. No production note
bodies or provider answers were inspected or copied into this record.

Both configured providers pass fixed-content connection checks. Only the two
already-enabled failed label plugins were recovered through their supported API
with their existing grants. Temporary verification sessions were revoked. Label
suggestions subsequently recorded a successful chat call; the retained gardener
job completed with a successful validation retry. A separate temporary database
with six synthetic notes also produced one valid gardener proposal through the
configured live model, with `retried_ok`. This verifies the live request path and
retry behavior, without claiming that all model suggestions will be useful.
