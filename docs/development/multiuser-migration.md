# SQLite library migration and recovery

This is an explicit offline operator procedure, not account enrollment. The original SQLite deployment remains supported. A migration maps one existing library to one existing OIDC account's empty personal workspace in the shared PostgreSQL service. Never infer that mapping from an email address, create a second library silently, or erase a phone's pending outbox to make a test pass.

The importer lives in `server/src/spunto/store/sqlite_import.py`. This document describes the backend contract and cutover gates; CLI wrappers use the same functions. Its automated acceptance uses synthetic SQLite files and a disposable local PostgreSQL database. Production snapshots and attachment files stay on the deployment node; they are not copied to a development laptop.

## Identity and an empty target

First obtain and verify the intended account's exact OIDC issuer and subject through a successful trusted sign-in or a reviewed provider record. Record the explicit account ID, workspace ID and mapping outside the note database. The importer neither creates identities nor links accounts. The workspace must be marked personal and have exactly one membership, with that account as owner. The operator must prepare it before the hosted notes runtime initializes it: even generated `meta` or `owner` rows make a target nonempty.

The callable contract is:

```python
report = await import_sqlite_snapshot(
    source=Path("/read-only-recovery/spunto-snapshot.db"),
    database_url=runtime_dsn,
    workspace_id=explicit_workspace_id,
    user_id=explicit_account_id,
    identity_database_url=identity_dsn,
)
```

Use the restricted runtime credential for domain writes and the separate identity credential solely to validate and lock the existing mapping. Never supply an admin credential as an application runtime credential. The public account/workspace rows remain the identity source of truth; historical SQLite `owner` data does not become a new OIDC identity. Legacy password hashes remain inert historical rows: hosted login uses OIDC and exposes no local password login.

## Consistent source capture

Stop the old service's writes and background jobs before final capture. Keep clients' drafts and native outboxes intact. Capture the database with the supported consistent SQLite snapshot mechanism, save its SHA-256 checksum, and preserve the original image, configuration, volume ownership and a recovery copy. Copying only a live WAL-mode `spunto.db` can omit committed writes; the importer refuses a nonempty adjacent `-wal`. Its source is a standalone snapshot opened in read-only immutable mode and held in a read transaction. Before committing, it checks the source device/inode, byte size, nanosecond mtime, SHA-256 and absence of a newly created nonempty WAL. It does not checkpoint, migrate or modify the source.

Only the exact supported SQLite schema is accepted. The importer builds the expected catalog from the shipped numbered migrations, including matching SQLite FTS and verified sqlite-vec tables and shadow tables. A newer or older schema, changed schema object, unknown plugin extension table/view/index/trigger, corrupt database or inconsistent foreign key causes an explicit refusal. Unrecognized extension data is never silently discarded. Use compatible code or a separately reviewed transformation on a recovery copy; do not alter the original snapshot to bypass validation.

## Preservation and the transaction

Every PostgreSQL domain table must be empty for the target workspace, including metadata, plugin state, jobs, receipts, FTS and vectors. Existing workspace credential locators, pairing codes or note-share access metadata also cause refusal. Other workspaces remain outside the bound store's transaction-local context. One PostgreSQL writer transaction copies the persistent data and token-routing trigger effects, verifies each table's row count and SHA-256 digest, checks the source checksum again, and commits once. An error or cancellation before the commit rolls back the domain rows and credential locators. If a connection fails during commit and the outcome is uncertain, keep service stopped and inspect the target against the expected digests; never assume an error authorizes an automatic merge or deletion. A repeat import refuses the nonempty target; this is not a merge or an implicit retry success.

The import preserves:

- Note IDs, hidden note row IDs, content, versions, timestamps, flags, provenance, conflict relationships and all revision rows.
- Labels and their numeric IDs, links, attachment metadata and note links, tombstones and change sequence.
- Instance identity and `sync_epoch`, device/PAT credential IDs and secret hashes, last-sync metadata and per-device idempotency receipts. Routing locators map imported device/PAT credentials to the explicitly selected account and personal workspace.
- Plugin configuration, grants, settings, storage, annotations, suggestions (including row IDs), searchable text, persistent job state/leases/receipts and instance settings.
- Embedding model descriptors/routes, chunk IDs and hashes, embedding-note records, AI statistics, and every float32 embedding from the dynamic SQLite vector tables into shared PostgreSQL vector storage.
- FTS source title/body/extra data and row associations; PostgreSQL derives its own search representation from those exact fields.

Two deliberate credential transformations appear in the report: all legacy browser-session rows are revoked, and old short-lived pairing grants are expired. Already revoked sessions retain their previous revocation timestamp. Historical credential IDs remain available for note/revision attribution. The old browser cookie cannot become an OIDC application session. Device and PAT secret hashes are preserved, so existing configured clients can authenticate to the mapped workspace without replacing their outboxes. Device/account cache identity still requires an explicit compatibility check in the native client; preserving the server epoch is necessary and does not by itself prove a physical phone migration.

The report contains source-file SHA-256, instance/sync identity, counts and sorted canonical row-hash digests for every copied table. It contains no note content, note IDs, token values, provider credentials or connection strings. `source_sha256` and `imported_sha256` differ only for the documented session/pairing transformations. Save the serialized report with mode 0600 alongside the explicit identity mapping and source checksum, using an atomic file replacement and durable directory sync. A report is evidence for an operator to verify, not authorization to skip a refused rerun.

## Required identity-sequence reconciliation

Imported numeric IDs and note/suggestion row IDs can be ahead of PostgreSQL's global identity sequences. The import report always sets `sequence_reconciliation_required=true`. Keep **all** hosted writers stopped, then run:

```python
next_values = await reconcile_import_sequences(operator_admin_dsn)
```

This separately authorized operator step uses a short admin connection, locks the five known identity tables, reads global maxima across every workspace and transactionally restarts sequences above both imported data and their previous next values. It never grants runtime users sequence administration. The admin credential must not remain in the application deployment. Retain the returned reconciliation evidence with the import report. Do not start hosted writers until this gate succeeds; an import report alone does not declare the workspace ready for service.

## Files and recovery verification

The database importer does not copy files. Its attachment manifest contains only validated lowercase SHA-256 values, nonnegative byte sizes and content-addressed paths derived from those hashes. Copy the frozen attachment tree separately to the mapped workspace's configured attachment directory, preserving the original tree for recovery. Verify with:

```python
verified_count = verify_attachment_files(target_attachment_directory, report.attachments)
```

The verifier checks size and SHA-256, refuses paths inconsistent with the content hash, and rejects symlink escape from the target root. It never rewrites files. Copy any separately stored trusted plugin packages and necessary operator configuration through their documented installation paths; database plugin metadata is not a substitute for the corresponding files. Keep UID 10001 ownership/read permissions and restrictive secret-file ACLs. The source mount remains read-only. Check that all database pools close on success and failure before releasing recovery mounts.

Before cutover, restore the PostgreSQL backup and copied files into an isolated recovery environment and verify the report, attachment checksums, preserved note/revision/label data, search, vector retrieval, job receipts, plugin configuration, a synthetic device/PAT replay and a new note/label/job allocation after sequence reconciliation. Authenticate as the explicitly mapped OIDC account and a second synthetic account to verify access and isolation. Validate pending-outbox/idempotency replay without clearing client data. Real Authentik and physical-phone checks remain separate gates and must not be claimed from synthetic tests.

Take a PostgreSQL backup paired with the migrated file tree before enabling writes. Retain the original SQLite snapshot, checksum, original filesystem tree, configuration and image until recovery acceptance. Rollback to the old SQLite service is safe only while the PostgreSQL side has accepted no new writes; after that point, use a reviewed recovery/reconciliation procedure that preserves new writes. Never restore the stale SQLite snapshot over accepted hosted edits or run both engines as writers during a cutover.
