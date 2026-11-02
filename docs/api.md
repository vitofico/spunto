# API and agent access

This is the implementation reference for local and hosted modes. The original design remains in `02-spec.md`, with hosted changes approved in [the multi-user decision](decisions/2026-10-18-multi-user.md). The running server's `/api/v1/openapi.json` describes its REST contract; `/api/v1/docs` renders a local HTML reference without remote assets. All paths below except probes, `/a/{id}` downloads and `/mcp` are under `/api/v1`.

## Hosted authentication and collaboration

`GET /auth/options` returns `{mode: "local"}` for SQLite. Hosted mode returns
`{mode: "hosted", password_enabled: true, registration_enabled: boolean, oidc_enabled: boolean}`,
with `login_url: "/api/v1/auth/oidc/start"` and `label` only when optional SSO is
configured. Clients must load these options before presenting signup or SSO.

| Method and path | Hosted browser contract |
|---|---|
| `POST /auth/login` | `{username, password}` signs in to an existing account |
| `POST /auth/register` | `{username, password, display_name?}` creates an account only when registration is enabled |
| `GET /auth/account/security` | `{local_username, local_password_set, oidc_linked, reauthenticated, reauth_url}` |
| `PUT /auth/account/password` | `{username?, new_password, current_password?}` sets/changes credentials on the current account; returns 204 |
| `POST /auth/account/oidc-link` | `{current_password}` verifies an existing local password and returns the bound provider `login_url` |

Usernames are normalized lowercase, 3–64 ASCII letters/digits/`._-`, starting with
an ASCII letter or digit; passwords are
8 characters minimum and 1024 UTF-8 bytes maximum. Local accounts have no invented
email or verified-email claim. Registration is disabled by default. Existing local
password changes always require the current password; wrong current passwords
return 401 `wrong_password` without signing out this browser. First password setup
on an SSO account requires fresh bound provider reauthentication through
`/auth/oidc/start?reauth=local-password`, returning to
`/settings/account?security=reauthenticated`. Successful password changes rotate
this cookie and revoke other browser sessions, while device/PAT credentials need
separate revocation. Explicit SSO linking verifies the current local password,
then fresh provider proof; it never matches or merges accounts by email.

Core validates signed provider identities and owns opaque cookies; web and Android
never hold OIDC client secrets. `GET /auth/session` adds `user_id`, `workspace_id`,
`workspace_role`, `credential_id` and `auth_method`; `owner_id` is the account ID for
compatibility. `GET /auth/me` returns account (including nullable `username`/`email`),
workspace and auth metadata: `provider: "local"|"oidc"` is the current session
method, `oidc_linked` describes the account, and `issuer` is nullable. A local
session may have a linked issuer. `POST /auth/logout` revokes the application
session. Cookie writes require the exact configured `Origin`.

Active browser requests assert both `X-Spunto-Account` (user ID) and
`X-Spunto-Credential` (session credential ID), even for account-global APIs. Core
matches them against its cookie session before domain access; partial or mismatched
assertions return 409 `identity_changed`. These IDs do not authenticate a request.
Both may be omitted by legacy/native clients. Public auth and initial/rotation
session hydration omit old assertions. On a current mismatch, web clears private
queries and locks until explicit sign-in, preserving account-scoped drafts; it must
not silently adopt an unexpected cookie account.

Workspace APIs require a browser session. `X-Spunto-Workspace` selects a workspace only after authoritative membership checks; omitting it selects the account's personal space. A PAT/device credential remains bound to its issued account and workspace and cannot use the header to switch authority. Membership/role changes are checked on subsequent requests. Browser caches and retained drafts are partitioned by user, space and credential, with pending requests fenced when that context changes.

| Method and path | Contract |
|---|---|
| `GET /workspaces` | `{workspaces: [{id, name, role, personal}], active_workspace_id}` |
| `POST /workspaces` | `{name}` creates a shared workspace owned by the current account |
| `GET /workspaces/{id}/members` | `{members: [{user_id, username, email, display_name, role}]}` for an authorized member |
| `POST /workspaces/{id}/members` | Owner grants/changes exactly one `{username, role: "editor"\|"viewer"}`, `{email, role}` or `{user_id, role}` |
| `DELETE /workspaces/{id}/members/{user_id}` | Owner revokes a non-owner member |
| `GET /notes/{id}/shares` | Owner reads `{shares: [{user_id, username, email, display_name, role}]}` |
| `POST /notes/{id}/shares` | Owner grants/changes exactly one `{username, role: "editor"\|"viewer"}`, `{email, role}` or `{user_id, role}` |
| `DELETE /notes/{id}/shares/{user_id}` | Owner revokes an individual-note grant |
| `GET /shared-notes` | `{notes: [...]}` containing granted notes with `workspace_id`, `share_role` and `shared_by` |
| `GET /shared-notes/{id}?workspace_id={source_workspace}` | Reads that granted note without opening its source library |
| `PATCH /shared-notes/{id}?workspace_id={source_workspace}` | Editor sends `{body, version}`; returns the updated note or a preserved conflict |
| `GET /shared-notes/{id}/attachments/{attachment_id}?workspace_id={source_workspace}` | Reads a linked attachment only while that note grant remains valid |

Recipients must already have an account with the exact username, verified email or user ID. A grant request accepts exactly one recipient selector; username lookup is normalized and exact. There is no email search, directory endpoint, email delivery or anonymous share link. Personal-space membership cannot be expanded: create a shared space or grant one note. Owners manage access; editors can edit notes; viewers are read-only. Individual-note grants do not expose the source workspace's label browser, settings or revision-history APIs. Sharing/membership routes require a browser cookie even when a PAT has write scope.

Treat a shared note's identity as `(workspace_id, id)`: imported libraries can contain the same note ID. Always retain the source workspace in requests, browser links (`/shared/n/{id}?workspace_id=…`), cache/draft keys and conflict links. Omitting it is accepted only for one unambiguous legacy grant. Shared-note edits use the normal versioned autosave/merge mechanism; a 409 includes `conflict_id` and preserves the incoming text. Keep the edited draft until acknowledged. Neither CRDTs nor live cursors are implemented.

Hosted Android authentication pairs a device through the signed-in browser. The password-only owner setup/rotation below apply to SQLite local mode; hosted account passwords use the routes above. MCP still uses scoped workspace-bound PATs and the same visibility rules; a note grant alone does not grant access through the ordinary notes or MCP APIs. See [hosted operation](hosted.md) for provider configuration, lifecycle limits and recovery.

## Local password authentication

`POST /setup` consumes the one-time setup code and sets the owner password. `POST /auth/login` accepts the password and issues an owner session cookie; `POST /auth/logout` signs out: with the cookie it ends that browser session, and with a device's `Authorization: Bearer …` it revokes that device's own credential and no other, as on a hosted server. Cookie-authenticated writes require an `Origin` exactly matching `SPUNTO_PUBLIC_URL`. `GET /credentials` lists active credentials for the signed-in owner. `POST /tokens` creates a token from an owner session and returns its secret once; the body accepts `name`, `client` (`claude-code`, `codex`, `script`, `monitor`), `scope` (`read`, `write`, `status`), `private_ok`, `external_ok` and optional future timezone-qualified `expires_at`. `DELETE /credentials/{id}` revokes an owner-managed credential. `PUT /auth/password` changes a database-managed owner password and signs out web sessions; an environment-managed password must be changed in that environment. Personal tokens travel in `Authorization: Bearer …`, never in URLs. Read/write scope, private-note access and external-origin visibility are checked in the shared service, including MCP resources.

Spunto has one owner, so login takes only the owner password. The optional display name in web **Settings → Profile** is cosmetic, private to owner settings, and never becomes a username. Owner-session `GET`/`PUT /settings` includes `owner_display_name`: an empty string by default, trimmed on save, at most 80 characters, with control characters rejected. Clearing it does not change credentials or the stable owner identity. Public metadata and session responses do not expose the name.

## Create an agent token in local mode

After starting the server and completing owner setup, the verified CLI syntax is:

```sh
docker exec spunto spunto token create --name 'local coding agent' \
  --client codex --scope read --no-external
```

The command prints the token once. Store it in the agent's secret configuration rather than a tracked file. Select `--scope write` when that agent needs capture/edit tools, `--private` only when it should read private notes, and `--expires-at` with a future ISO 8601 timestamp including a timezone when expiry is wanted. Omit `--no-external` to include notes originating from external sources. Client labels also accept `claude-code`, `script` and `monitor`; they identify the client rather than select a different transport. A dedicated monitor uses `--scope status` and reaches `/api/v1/status` only.

## Notes

| Method and path | Behavior |
|---|---|
| `GET /notes` | Timeline, archive, trash or conflicts; label/date filters; cursor pagination |
| `POST /notes` | Create plain Markdown; optional UUID `Idempotency-Key` |
| `GET /notes/{id}` | Read a note; optional `render=html` |
| `PATCH /notes/{id}` | Replace body with `If-Match: "<version>"`; nonoverlapping stale edits merge |
| `POST /notes/{id}/append` | Append text; optional expected version and paragraph/line separator |
| `POST /notes/{id}/flags` | Pin, archive, trash or mark private; optional per-flag clocks |
| `DELETE /notes/{id}` | Permanently delete; owner session required |
| `POST /trash/empty` | Empty trash; owner session required |
| `GET /notes/{id}/revisions` | Revision history |
| `POST /notes/{id}/revisions/{version}/restore` | Restore saved body; owner session required |
| `GET /search` | Folded keyword search, labels/date filters, relevance or recent order; semantic mode reports off through M3 |
| `GET /labels` | Label vocabulary, optional prefix |
| `POST /labels/rename`, `POST /labels/remove` | Rewrite recognized inline labels; owner session required |
| `GET /export` | Markdown ZIP export with metadata; owner session or authorized PAT, never a device token |
| `GET /status` | Operational counts, database/backup state and active credentials; owner session or status token only |
| `GET /meta` | Public version, public URL, setup state, encryption flag and sync epoch |

New captures require a nonempty body; edits can leave an existing note empty, including when removing its final inline label. Writes update the note, revision, labels and FTS index in the same short transaction. A missing/malformed `If-Match` returns 428. Overlapping stale edits return 409 with `conflict_id`: the incoming text is preserved in a separate note. Errors use `application/problem+json` with a stable `code`. Clients must retain their edited text until a successful save or resolve the returned conflict; replacing it with the server's body would lose their work.

Creation retains revision 1. Revision coalescing reduces rapid edits in the same session without dropping that creation ancestor. The initial M1 implementation uses token identity for agent provenance: an agent token's name becomes `source.ref`, and its `origin`/`machine` state is set by the server. `source.url` separately preserves an optional link supplied with the capture; it does not replace that provenance.

A simple read after obtaining a token:

```sh
# Set SPUNTO_TOKEN privately in this terminal; do not paste its value into docs or logs.
curl -H "Authorization: Bearer $SPUNTO_TOKEN" \
  'http://localhost:8080/api/v1/search?q=idea&mode=keyword'
```

## MCP

Configure an agent's Streamable HTTP transport to the exact URL `https://<your-host>/mcp`, with an `Authorization` bearer header supplied from its secret/environment configuration. Local HTTP works with the server's configured public origin. The endpoint is stateless: do not rely on an MCP session ID or reconnect to `/mcp/` as a different address. Cookies alone do not authenticate MCP.

Create a separate PAT for each client, with the matching `--client` value. Set `SPUNTO_TOKEN` privately in the environment that launches that client, then add the local instance with one command. Replace the URL with your configured public URL when it differs.

For Codex:

```sh
codex mcp add spunto --url http://localhost:8080/mcp --bearer-token-env-var SPUNTO_TOKEN
```

For Claude Code, single quotes preserve the environment reference in the saved configuration:

```sh
claude mcp add --transport http spunto http://localhost:8080/mcp \
  --header 'Authorization: Bearer ${SPUNTO_TOKEN}'
```

The command flags were checked against the installed CLIs. Environment-backed bearer authentication follows [OpenAI's MCP documentation](https://learn.chatgpt.com/docs/extend/mcp?surface=cli) and [Claude Code's header expansion documentation](https://code.claude.com/docs/en/mcp#environment-variable-expansion-in-mcp-json). Use read tokens for read-only work; keep confirmation enabled for edit tools. The [real-client verification](development/mcp-client-verification.md) records the tested Codex session and the remaining Claude check.

A read token exposes exactly three core tools: `search_notes`, `get_notes`, `list_labels`. A write token exposes those plus `create_note`, `append_to_note`, `set_labels`, `archive_note` (seven total). A status token cannot read notes through MCP. Private notes are excluded unless explicitly granted; external-origin notes can also be excluded. External-origin note contents are fenced as untrusted data, and answers have bounded size. `create_note` accepts a `request_id` for retry deduplication; writes are rate-limited. Appends do not accept that key, so retrying an append after an uncertain response requires checking the note first.

`search_notes` accepts `mode: "keyword"` to restrict results to text matches, or `mode: "hybrid"` (the default) to include enabled semantic search sources. Results preserve each hit's `match` (`keyword`, `meaning`, or `both`) and report semantic search availability as `off`, `on`, or `unavailable`.

Resources use `note://<id>` and the template `note://{id}`. Resource listing combines accessible pinned and recent notes; reading a resource applies the same visibility checks as a tool. Token revocation or changed access must be honored on subsequent requests.

The web client, AI gateway, semantic search and native Android sync are implemented. Attachments use authenticated `/a/{id}` downloads; SVG and other active formats download as files. The native Android app keeps a local replica and encrypted device credential; physical-phone dictation checks remain pending.


## Plugin APIs

The four M2 plugins default off. Owner sessions and devices can read `GET /api/v1/plugins` and the enabled-only `/plugins/manifest` (ETag), and switch plugins through `POST /plugins/{id}/enable` or `/disable`. Enable carries the manifest's exact `permissions` list; changed permissions require a new sheet. `SPUNTO_PLUGINS=+tasks,-resurface` pins switches, rejects unknown IDs and prevents UI overrides.

`GET /plugins/{id}/settings` returns the schema and public values; secret fields report only whether they are set. `PUT` settings and `DELETE /plugins/{id}/data` require an owner session. Deleting plugin data preserves its notes. Secret values come exclusively from `SPUNTO_PLUGIN_<ID>_<KEY>` or `_FILE`; they never enter settings JSON. Commands and live routes use the enabled-only dispatcher. Token access must be explicitly declared by a route, and read tokens cannot write through any host adapter.

Plugin views use `GET /notes?view=<plugin>/<view>`, for example `tasks/tasks` and `agent-inbox/reviewed`. Stored output is vocabulary-validated, bounded, hidden while disabled, and filtered for the caller's note visibility. Markdown components receive sanitized HTML for the web. `GET /suggestions` lists pending proposals; `/suggestions/{id}/accept` and `/dismiss` require write access, recheck visibility and apply atomically under the deciding credential. Label renames require a session. Replacement proposals become superseded when their base changes; pending proposals expire after fourteen days.

Waiting catalog entries carry a human-readable `last_error` explaining missing requirements or a failed model check. AI command errors retain their stable `code` and include safe actionable `detail`; an index that is preparing returns `503 vectors_unavailable`. Repeating `POST /plugins/{id}/enable` with the exact manifest grants is the supported retry for Failed plugins: it resets the circuit counter and requeues retained dead work. A restart preserves Failed states.

The optional, default-off `library-stats` plugin contributes `summary` through the existing `POST /api/v1/plugins/library-stats/commands/summary` owner-session/device command transport. It returns text vocabulary with scoped metadata counts, without note bodies or identifiers, and does not mutate notes. Existing hosted space-owner, visibility and disabled-plugin checks apply. Metric definitions and the additive Host API 1.0 count helper are in [Library stats](plugins.md#library-stats).

The MCP list includes enabled, visible plugin tools per token scope. Plugin tools retain declared destructive/idempotent hints, have closed input schemas, and pass outside text through the same fencing and response ceiling as the core. Invalid plugin tool schemas cannot break core discovery. Tools from bundled and operator-installed plugins use the same permission-checked host. See [operator plugins](plugins.md) for API1.0 loading, compatibility and configuration.

Owner-session `GET`/`PUT /api/v1/settings` supports `plugin_order`, `hidden_mcp_tools`, `hidden_views`, the instance `theme` and nullable `theme_accent`. The five built-in IDs are `carta`, `ardesia`, `salvia`, `corallo` and `elettrica`; available operator packs and explicit accents come from the validated `theme_catalog` in `/plugins/manifest`. `GET /api/v1/changes/wait` waits up to 25 seconds for a new epoch/sequence cursor or plugin revision. Status includes plugin states, job counts and outbound destination-host counts, with no request paths, query strings or secrets.

## M3 web contracts

`GET /api/v1/auth/session` requires an owner cookie and returns a stable `owner_id`, the current `credential_id`, and `password_managed` (true when the password comes from the environment). Browser drafts use the stable identity rather than a session ID. A note creation's `Idempotency-Key` is shared across the owner's browser sessions so an uncertain save can safely retry after sign-in; PAT and device operation namespaces remain separate.

`POST /api/v1/attachments` accepts exactly one multipart field named `file`, with a configured file-byte limit. `SPUNTO_MAX_UPLOAD_MB` defaults to 20 and accepts whole decimal MB from 1 to 1000; one MB is 1,000,000 bytes. Public `GET /meta` exposes the exact `max_upload_bytes`. Oversized files return 413 `attachment_too_large`; the streaming multipart envelope has a separate 65,536-byte allowance. Uploads require write permission and a matching Origin for cookie authentication. The response includes an authenticated `/a/{id}` URL and Markdown snippet. The server checks image signatures and deduplicates content. Only verified image formats are served inline; other formats, including recordings, use authenticated attachment downloads with private/no-store caching. The general upload route does not certify an audio container or duration. `GET /notes`, `GET /notes/{id}` and `GET /search` accept `render=html` for sanitized Markdown with trusted checkbox source offsets. Offsets count UTF-16 units, matching browser string indexes.

Owner-session `POST /api/v1/labels/impact` accepts `{old, new?}` and returns `{notes}`: the number of distinct bodies a vocabulary rewrite changes, including archived and trashed notes and child labels. It validates the replacement before the owner applies the change.

`GET /api/v1/slip` returns `{slip: null}` or today's enabled, visible slip, with its rendered parent note independently of timeline pagination. Owner session/device `POST /api/v1/slip/dismiss` accepts `{plugin_id, note_id}` and dismisses that day's slip, leaving the note intact. The plugin catalog includes `data_count`, `data_counts` and `data_size_bytes` for deletion previews.

The production host serves immutable hashed assets and sends the SPA index with `Cache-Control: no-store`. A URL-encoded JSON `theme` cookie sets optional device theme, mode (`system`, `light`, `dark`), reading face (`serif`, `sans`) and size (`s`, `m`, `l`) before the first paint. Without a device theme override, the instance theme applies. Unknown API/attachment paths and missing assets remain errors.

## M4 AI contracts

`GET /api/v1/ai` requires a browser session or device token and returns safe profile configuration, independent capability bindings, probe results, daily counters, provider quirks and indexing progress. `can_manage` is true only for the space owner; hosted editors/viewers may inspect status but cannot change models, run checks or reindex. `POST /ai/check` runs fixed-content checks; `POST /ai/reindex` accepts only `{"drop": false}` (or true to discard retained vectors). Both require a space-owner browser session and matching Origin. These endpoints accept no provider keys or endpoint URLs.

`GET /api/v1/ai/models` lists models from the configured provider profiles, optionally filtered by exact `?profile=name`. It requires a space-owner browser session or device. The response is `{"profiles":[{"profile":"ollama","models":[{"id":"embeddinggemma-2:270m","capabilities":["embed"],"leaves_network":false}],"error":null}]}`. Capabilities use `chat`, `embed`, `transcribe` and `vision`; `null` means the provider did not verify compatibility and an empty list means none is advertised. Multimodal embeddings do not establish generative vision or transcription. A failed profile returns an empty model list with `error="ai_inventory_unavailable"`; successful profiles remain usable. Inventory requests are bounded, send no notes and never pull/install models.

AI status includes `model_selection: {revision, selections, defaults}`. Both maps have optional capability keys with `{profile, model}` values. `defaults` describes operator environment bindings; `selections` contains this space's saved overrides. `PUT /api/v1/ai/models` accepts the complete `{revision, selections}` object and returns updated full AI status including `can_manage` and indexing progress. It requires the space-owner browser session and matching Origin. Omitted capability keys inherit the environment; an empty map resets all overrides. Choosing a model also binds that capability to its selected profile, including a previously unbound capability. An explicit per-plugin profile route still wins over the default binding; a selected model changes that capability's model wherever its profile is used.

Changed choices require fresh provider inventory and compatible capabilities when known. Embedding changes additionally run a fixed-content finite, nonzero dimension probe before persistence. Unchanged saved choices remain intact when inventory is temporarily unavailable or a model is removed. Resetting to environment defaults requires no provider request. A stale revision returns `409 ai_selection_changed`; an unavailable/incompatible choice returns `422 ai_model_invalid`; failed provider verification returns `503 ai_inventory_unavailable`. None changes the saved selection. Revisions also change when operator endpoint/default/model configuration changes, so an old browser cannot silently overwrite the new context. Provider credentials remain environment/file-only. Saved choices are scoped by the existing SQLite library or PostgreSQL workspace setting row.

Embedding replacement retains its old index and descriptor while a distinct target backfills through leased jobs. Current note versions must be covered before activation; dimensions are probed and never assumed equal. A removed old model cannot prevent target backfill. Old-model queries may be unavailable until replacement completes; keyword search remains available. A model selection does not request a destructive index drop.

`PUT /api/v1/plugins/{id}/ai-route` requires an owner cookie session and accepts a closed mapping of the plugin's declared `chat`, `embed`, `transcribe` and `vision` capabilities to configured profile names. `{}` restores default bindings. `GET /plugins/{id}/panel?note_id=...` returns a declared live panel after core note visibility checks; disabled or unavailable contributions return 404. Related exposes the read-only `related_notes` MCP tool.

Search accepts `mode=keyword|hybrid`. The hybrid result applies the same core filters and combines keyword results with at most 50 references per enabled search source using reciprocal rank fusion. Each hit carries `match: keyword|meaning|both`; `semantic: off|on|unavailable` explains source availability. Source failures and the two-second deadline preserve keyword results.

Ask starts with its command form and returns sanitized answer components plus validated note references. Its `save-answer` command accepts the opaque server-issued `answer_id` only, writes a machine-created note with an idempotent request ID, and rechecks sources. Unsaved answers remain in bounded, expiring process memory; questions and answers are not retained in plugin storage.

## Organization, capture and operator extensions

The eight organization/development plugins (`title-summary`, `tidy`, `duplicates`, `label-gardener`, `links`, `digest`, `develop`, `translate`) default off. Enable only the desired plugins and review their declared grants; unavailable required capabilities leave them Waiting. Proposals use ordinary Review/Compare acceptance, and generated follow-ups are cited machine notes rather than silent edits of the source. Contracts are in [M6](development/M6.md).

Voice memos, image text, link enrichment and webhooks also default off. Native retained recordings use Ogg Opus links such as `[Recording 0:30](/a/{id})`. Voice memos must be switched on with all declared grants; a Waiting transcription profile still allows native recording-only capture. Recording and ordinary offline Dictate are separate actions. Transcription requires a configured healthy binding and supported linked audio, rechecks privacy/source eligibility, and produces an owner-accepted proposal. Native validation and server transcription support bounded Ogg Opus; the server also recognizes PCM WAV, with a maximum one-hour transcription duration and the configured attachment-byte cap. Other uploaded media is retained without implying transcription support. See [capture contracts](development/M7-capture.md).

Markdown import is an explicit preview/import flow: owner-session multipart `POST /plugins/markdown-import/upload` stages a ZIP and returns `{archive_id, report}`; the web flow explicitly queues `POST /plugins/markdown-import/import` with that `archive_id`, and reads the report after the durable job finishes. ZIPs remain capped at 20,000,000 bytes/64,000,000 expanded bytes, with individual assets capped at the smaller of 5,000,000 bytes and `max_upload_bytes`. Raising the ordinary upload limit does not raise importer limits. See [Markdown import](development/M7-markdown.md) for exact frontmatter/round-trip and owner-edit safeguards.

Catalog cards report authoritative `origin` (`bundled`, `operator`, `unknown`), safe `source_kind`, `can_enable`, `can_delete_data` and scoped `data_count`/`data_counts`/`data_size_bytes`. A retained missing plugin is `state=removed`, `switch=off`, `can_enable=false`; no missing code is imported or dispatched. A known retained ID can remain `pinned_by_env` when listed in `SPUNTO_PLUGINS`; truly unknown IDs still reject startup. Removed settings/enable/commands return 404. Unpinned Removed disable returns its actual Off receipt; environment-pinned disable returns 409 `pinned_by_environment`. Owner-session+Origin `DELETE /plugins/{id}/data` remains available for a known Removed card, preserves its notes and metadata card, and clears only scoped plugin data. Devices use the web owner-sign-in management action rather than receiving DELETE authority.

Successful enable/disable responses include the current identity/state row and actual synchronous `plugins_rev`. A later independent catalog read can have a higher revision from other changes: consumers use the returned state/revision and a monotonic publication fence, never assume requested Off or revision+1. Detailed stored-data counts come from `GET /plugins`.

## M5 device authentication and offline sync

`POST /auth/devices` accepts `{password, name}` and returns a named owner device credential `{id, token, name}` once. The server stores only its hash. Devices use `Authorization: Bearer …` and can be revoked through owner Settings → Devices; a phone signing out revokes its own with `POST /auth/logout`. A 401 preserves the phone replica, drafts and outbox for sign-in to the same server.

Owner-session `POST /auth/pairing` creates a five-minute, session-bound, one-use code and returns `{code, public_url, expires_at}`. The web QR contains `{server, code}`; Android can scan it or paste that JSON. `POST /auth/pairing/exchange` accepts `{code, name}` and returns a device credential. A replacement grant invalidates the previous grant from that session; exchange, session revocation and database restore cannot revive used grants. Credentials and codes never travel in URL queries.

The following routes require a device token; owner cookies and personal agent tokens do not grant sync access:

| Route | Contract |
|---|---|
| `POST /sync/push` | `{epoch, ops}` with at most 100 ordered operations; successful operations commit independently with durable idempotent receipts, while rejected operations do not block later entries. Returns `{epoch, results}`. Types are `note.create`, `note.update`, `note.flags`, `note.restore` and `suggestion.decide`. |
| `GET /sync/pull` | Accepts optional `cursor` and `limit` (1–500, default 500). Returns `{epoch, cursor, has_more, plugins_rev, items}` with ordered notes, stored plugin output and tombstones; pages also have a 2,000,000-byte payload target. Treat the epoch/sequence cursor as opaque. |
| `POST /sync/reconcile` | Accepts `{epoch, notes: [{id, body_hash, updated_at}]}` using the current server epoch and a timezone-qualified ISO `updated_at`, up to 1,000 entries. Returns `{epoch, restore_ids}` for missing, locally newer or equal-time body-divergent notes; known purge tombstones are excluded. |

Offline body updates carry `body` and `base_version`; the server loads the base body from revision history. Operation action clocks (`at` and optional `pinned_at`, `archived_at`, `trashed_at`, `private_at`) and `created_at` use integer milliseconds. Push results distinguish `applied`, `merged`, `conflict`, `duplicate` (with its saved `original` result) and `rejected` (with `code`) operations. An outdated epoch returns 409 `resync_required`; the phone must fetch the current epoch before reconciliation. Restore preserves validated source/machine provenance and independent flag intent. The phone freezes sent operations; later typing remains queued when an acknowledgement or pull arrives. A rejected operation stays visible instead of being silently discarded. Golden contracts and scenarios are in `spec/fixtures/sync-api.json` and `sync-scenarios.json`.

Notes include `body_hash`, `flag_times` and `updated_via_agent`; the latter reflects the latest actor recorded on the note, including flag writes. Plugin manifests include the instance light/dark theme tokens and hidden views. Native appearance settings remain device-local. Global server settings, credentials, labels rewrites, plugin configuration writes and AI check/reindex controls retain owner-session restrictions; Android links to the web controls when necessary.
