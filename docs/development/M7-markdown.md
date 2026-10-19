# M7: Markdown import

`markdown-import` is an owner-controlled, default-off server plugin with `notes.create`, `notes.update_own`, `attachments.write` and plugin routes. It uses no AI, external network, client code or process execution. GitHub publication remains disabled and all acceptance data is synthetic.

The operator CLI accepts a ZIP or folder while holding the same data lock as `serve`:

```sh
spunto plugins run markdown-import --path /path/to/vault.zip --dry-run
spunto plugins run markdown-import --path /path/to/vault.zip
```

The web Data page uploads one ZIP through the session-only `/api/v1/plugins/markdown-import/upload` endpoint. Matching Origin is required; device and agent bearers cannot upload an import. A preview reports note and attachment counts, bytes and missing local links before the owner queues the import. The plugin's `/import` route accepts the opaque preview ID, never a filesystem path. Import execution uses the durable host queue, and the owner can refresh the vocabulary report. An invalid, replaced or expired preview cannot import.

Limits are 20 MB compressed, 64 MB expanded, 20,000 entries and 10,000 Markdown notes, with 100,000 characters per note and 5 MB per attachment. ZIP members are read in memory without extraction. Absolute/parent/duplicate/Windows paths, backslashes, symbolic links, special files and encrypted archives are refused. Local links must stay inside the import root. All notes are parsed and validated before note writes begin; parsing runs outside the event loop. Folder import ignores hidden configuration and refuses symbolic links. Only the operator CLI supplies its path.

Spunto exports preserve IDs, created/updated times, body text including CRLF, canonical label display paths, private/pinned/archive/trash flags, source, origin, machine provenance and conflict references. Attachment links return to core content-addressed storage. Acceptance compares every re-exported archive member with the original. Revisions, suggestions and plugin storage remain database/snapshot data, outside the Markdown export contract.

Other vaults receive a best-effort safe front-matter subset: JSON scalars/collections, simple YAML scalars/tag lists, `created`/`created_at`/`date`, `updated`/`updated_at`, labels and flags. Unsupported YAML objects never execute. Relative note links become `/n/<id>` and attachments `/a/<id>`; code examples stay literal. Missing local links are reported and retained. This does not establish exhaustive compatibility with every Obsidian, Bear or Memos export variant.

Persistent mappings and content fingerprints make reruns idempotent. Linked attachment changes update an unchanged imported note. A note edited here, made inaccessible or removed after its first import is skipped. Imported machine/external provenance retains the core's AI and agent fences.

## Verification in progress

Eighteen parser, real-SQLite and ASGI checks passed before independent review: path/symlink/size refusal, safe front matter, folder filtering, preview, attachment updates, reruns, owner-edit protection, exact export round trip, agent/disabled refusal, invalid later-note preflight, session/Origin/bearer boundaries, queued execution/note links, invalid uploads and replaced previews. Log: `/tmp/spunto-full-product/markdown-tests.log`. Web upload/preview/queue/report checks are in progress.

Independent review found that concurrent uploads could exceed staging bounds or remove a queued import's input. The correction and concurrent-upload/queued-input regressions are in progress. Final tests, memory measurement and packaged browser acceptance remain pending; no pending result is a pass.
