# Hosted accounts, spaces and collaboration

Spunto has two storage/authentication modes in the same reusable core. Local mode
uses SQLite and one owner password; its quickstart and recovery commands are in
[operations](operations.md). Hosted mode uses shared PostgreSQL/pgvector,
username/password authentication and optional OpenID Connect, including Authentik.
It serves multiple accounts from the same application deployment. The
[approved revision](decisions/2026-10-18-multi-user.md)
supersedes the original single-owner and SQLite-only restrictions for this mode.

## Start with your notes

Signing in creates a personal space. It is the default, so ordinary capture and
search need no organization setup. Labels remain the main way to organize notes,
including project-related notes. There is no separate project entity.

Use **Settings → Spaces** when a group needs a shared library. Create a named
space, then invite an existing account by its exact username or verified email. Select
**Can edit** or **Can view**. The owner manages membership and configuration;
editors can write notes; viewers cannot edit. Personal-space membership cannot
be expanded. Switch the active space from the same settings page; notes, labels
and drafts follow that selection.

Use a note's **Share** action when one note is enough. Invite an existing account
with view or edit access; change or revoke that grant from the same dialog.
Recipients open **Shared with me** and use its dedicated note editor. A grant
does not open the source library's timeline, labels, settings, export or revision
history. Linked attachments are checked against the note grant. There are no
anonymous links, invitation emails, email search or account directory.

Existing notes and shared notes autosave; **Save** can send an edit immediately.
Each write carries its base version. Independent edits can merge; overlapping
changes preserve the submitted text in a conflict copy and offer review. A server
acknowledgement does not discard typing done while the request was in flight.
This is versioned collaboration, without live cursors or character-level CRDTs.

The default personal space stays out of the main writing flow. A space is useful
when access changes; labels are usually enough when only the topic changes.

The space owner can choose **Settings → AI** models from the configured providers'
available inventory. Choices persist for that space; another account's personal
space keeps its own choices. Providers, endpoint URLs, credentials and limits stay
under operator control. Resetting a capability inherits the environment default;
choosing an available vision or transcription model can enable a previously unbound
capability. An embedding change rebuilds its index while retaining the prior
index until replacement is ready. Keep the old provider model installed if old
semantic queries must remain available during that rebuild.

## Account and client boundaries

Hosted sign-in accepts a username and password. Usernames use 3–64 ASCII letters,
numbers, periods, underscores or hyphens, starting with a letter or number. They
are normalized to lowercase. They
are separate from email addresses; local accounts need no email or SMTP service.
Passwords need at least 8 characters and at most 1024 UTF-8 bytes. Registration
appears only when the operator enables it; it is closed by default.

SSO is optional. When configured, the login page also offers the provider's
button. Core owns code/PKCE exchange, state/nonce validation, signed-token checks
and opaque application cookies. Provider tokens and client secrets never belong
in web or Android code. The provider identity key is its exact `(issuer, subject)`.
An email match never creates an account link, including when both identities have
the same email. SSO enrollment, recovery and MFA follow the IdP's policy.

Use **Settings → Account** to manage sign-in. An existing SSO account can add a
username/password after fresh provider verification; this opens the same account,
notes and spaces. Existing password accounts must supply their current password
for changes. They can explicitly connect optional SSO by verifying that password
and then signing in with the provider. An identity already linked to another
account cannot be linked again. Creating a separate account at signup creates a
separate library; it does not recover or merge an existing one.

Password changes sign out other browser sessions while retaining this browser.
An operator password reset signs out all browser sessions. Paired devices and
PATs need separate explicit credential revocation. There is no email password
reset or email verification service; ask the operator if all sign-in methods are
lost. **Settings → Profile** keeps an optional display name for the current space;
it does not rename the linked provider identity or change credentials.

Browser requests select a space with `X-Spunto-Workspace`; core checks membership
and role on each request. Active browser requests also assert `X-Spunto-Account`
and `X-Spunto-Credential`, including account settings outside a workspace. These
opaque IDs are not authentication secrets: core still authenticates the cookie
and rejects an account/session mismatch before accessing domain data. Both
assertions must match when supplied; older clients may omit both. A 409
`identity_changed` locks the stale browser authority and clears its private query
cache while preserving account-scoped drafts. Initial login and deliberate cookie
rotation hydrate a new session without old assertions. Client caches and retained drafts are scoped by user,
space and credential; switching context cancels cached requests and rejects late
responses. A device or PAT is bound to the account/space where it was issued and
cannot switch authority by sending another workspace header. The API contract is
in [API and agent access](api.md#hosted-authentication-and-collaboration).

Android can pair through a signed-in browser's QR/code flow, without a provider
secret on the device. Local replicas and work queues belong to an account and
space. A 401 or account switch must retain their pending work for the same
identity; signing in as someone else must never upload it to that new account.
Individual-note collaboration is currently a web flow; Android's offline sync
remains scoped to its paired space. Synthetic/emulator checks do not establish
physical-phone or real-provider acceptance.

## Operator setup

The public Spunto core runs this mode on its own: the server includes the web app,
username/password accounts, optional OIDC, spaces and note sharing. No separate
account-pages repository or hosted service is required. Build the core from source
as described in [operations](operations.md#build-and-start), or use a core image
whose version and immutable digest you have verified.

Use PostgreSQL major 18 with pgvector. Provision distinct database roles:

- An operator/admin connection performs numbered migrations and grants access.
  It must not be mounted in a running application container.
- The runtime role owns no schema, has no administrative or `BYPASSRLS` privilege,
  and works through forced row-level security with transaction-local context.
- A separate restricted identity role accesses account/membership/session/grant
  metadata and cannot read the domain tables directly.

For a new operator-owned database, create the restricted login roles, then run
the migration command with protected configuration files:

```sh
SPUNTO_DATABASE_ADMIN_URL_FILE=/run/secrets/admin_dsn \
SPUNTO_DATABASE_ROLE=spunto_runtime \
SPUNTO_IDENTITY_DATABASE_ROLE=spunto_identity \
  spunto database migrate
```

The long-running core receives only:

```text
SPUNTO_PUBLIC_URL=https://notes.example.org
SPUNTO_DATABASE_URL_FILE=/run/secrets/runtime_dsn
SPUNTO_IDENTITY_DATABASE_URL_FILE=/run/secrets/identity_dsn
SPUNTO_LOCAL_REGISTRATION_ENABLED=false
```

A standalone hosted installation needs no OIDC provider. To enable optional SSO, add:

```text
SPUNTO_OIDC_ISSUER=https://identity.example.org/application/o/spunto/
SPUNTO_OIDC_CLIENT_ID=<registered client ID>
SPUNTO_OIDC_CLIENT_SECRET_FILE=/run/secrets/oidc_client_secret
SPUNTO_OIDC_LABEL=Authentik
```

Register the exact callback
`https://notes.example.org/api/v1/auth/oidc/callback` with a confidential
authorization-code client, RS256 signing and S256 PKCE. Authentik's per-provider
issuer includes the trailing slash. The local-mode owner password setting is not
the hosted account credential store.
Use HTTPS; loopback-only insecure settings are for disposable local fixtures.
Secret files must be readable only by their intended container UID/administrator.
Core and cloud run as UID 10001 with a read-only root and dropped capabilities.

Bootstrap a local account with the restricted identity connection and a password
from the interactive prompt or protected standard input:

```sh
spunto account create --username operator --display-name Operator
spunto account set-password --user <exact-existing-account-uuid> --username operator
```

The first command creates a new account/personal space. The second establishes or
resets credentials on that exact existing account, including an existing SSO
account, and revokes its browser sessions. Neither command needs the admin DSN or
opens a browser session. Never put passwords in command arguments or logs. Keep
`SPUNTO_LOCAL_REGISTRATION_ENABLED=false` for operator-managed accounts; setting
it to `true` explicitly opens local signup. Optional SSO enrollment remains the
provider's separate policy.

### Monthly AI allowances

Every AI call in a space counts against the space owner's account before it joins
a provider queue, so creating more spaces never adds allowance, and members of a
shared space use its owner's allowance. Each action has its own counter, renewed
on the first of each month in UTC:

| Action | What counts | `free` | `paid` |
|---|---|---|---|
| `labels` | One auto-labelling run | 100 | 2,000 |
| `ask` | One Ask question | 20 | 300 |
| `chat` | One action of any other AI plugin | 50 | 1,000 |
| `vision` | One image read | 10 | 200 |
| `transcribe` | One transcription | 10 | 100 |
| `embed` | One embedding request for search | unlimited | unlimited |

`SPUNTO_AI_QUOTA_PLAN` picks the tier every account gets: `free` when unset,
`paid`, or `unlimited`, which still counts. `SPUNTO_AI_QUOTA_LIMITS` overrides
single actions with a JSON object, where `null` means unlimited:

```text
SPUNTO_AI_QUOTA_PLAN=paid
SPUNTO_AI_QUOTA_LIMITS={"ask": 500, "vision": 0}
```

A used allowance pauses that action until the renewal. An interactive action
answers `429 ai_quota_exhausted` with the renewal date; background runs stay
queued and look again hourly, so a raised limit resumes them without waiting for
the month. Settings → AI shows the month's usage. Notes, sync and search keep
working. A call refused before it reached a provider is not counted. Both
variables apply only to hosted servers; a SQLite server keeps no allowance.

Route `/cloud/*` to cloud and other paths to core on the same HTTPS origin.
Application cookies are host-only. Cloud calls a fixed private core URL, forwards
only application session cookies and never follows upstream redirects or forwards
provider tokens. Keep PostgreSQL private and configure trusted proxies explicitly.

Check liveness/readiness, two-account isolation, shared-space roles, note grants,
revocation and conflicts with synthetic notes before enabling enrollment. The
hosted worker uses leased jobs and recovers only expired leases; restarting one
process must not reset another worker's active receipt. Filesystem and deployment
topology still need an operator plan even though database isolation is shared.

## Migration, backup and recovery

Moving an existing SQLite library requires explicit owner mapping and an empty
personal workspace. Follow the [migration runbook](development/multiuser-migration.md):
capture a consistent standalone source, verify every table and file, reconcile
global numeric sequences with all writers stopped, and prove isolated recovery
before cutover. Do not auto-link a library by email or clear a phone's outbox.

Back up PostgreSQL with operator database tooling and pair it with the persistent
workspace files, attachment tree, trusted plugin packages and protected
configuration. A database-only backup cannot recover attachment bytes. Keep
administrator credentials out of the application. SQLite `spunto backup` and
`spunto restore` refuse PostgreSQL configuration; the hosted service reports
operator-managed database backup requirements rather than creating a SQLite file.

Plugin migrations take a consistent scoped PostgreSQL snapshot in the workspace
backup directory. This is an additional recovery tool, not a replacement for a
full database/files backup. Its `spunto-pg-workspace/1` format is a private mode-0700
directory containing mode-0600 `manifest.json`, `manifest.sha256` and one binary
COPY file for each of the 27 domain tables. One RLS-bound repeatable-read
transaction exports notes, revisions, plugin state, jobs, FTS source data and
binary pgvector values. The manifest records workspace ID, PostgreSQL major,
column signatures, per-table counts, sizes and SHA-256 hashes. Generated columns
are recomputed. Public identity, membership, OIDC-session, share and credential
locator tables are outside this format.

The operator utility
`spunto.store.postgres_snapshot.restore_postgres_snapshot(owner_bound_store, path)`
restores only into an **empty, stopped, same-workspace** domain with matching
schema and PostgreSQL major. It checks hashes/counts/scope before committing one
transaction. It needs database `TEMPORARY` permission for the runtime role and
identity sequence floors already above the saved IDs; an operator must reconcile
those floors separately while all writers are stopped. If `TEMPORARY` has been
revoked from `PUBLIC`, grant it narrowly to the runtime role on this database.
The utility never grants itself privileges or resets global sequences.

Restore preserves instance identity, rotates `sync_epoch`, clears pairing grants
and restores external credential IDs only as revoked rows with token hashes
scrubbed. Internal plugin credentials remain usable. Devices/PAT clients must
authenticate again; Android should retain the same-account/space outbox for
reconciliation. Global browser sessions are not part of the snapshot: a new login
creates fresh domain authority. Explicit note grants removed while emptying the
workspace need to be granted again. Do not use this utility to transfer ownership,
merge workspaces or perform an automatic production rollback.

## Verification and limits

Server CI runs SQLite regressions plus real PostgreSQL/pgvector 18 contracts,
signed synthetic OIDC cases, hosted HTTP authorization, lossless import and scoped
snapshot restore. The PostgreSQL job creates separate least-privileged fixture
roles and runs the operator migration command. Its three disposable test URLs are
`SPUNTO_TEST_POSTGRES_URL` (runtime), `SPUNTO_TEST_IDENTITY_URL` (identity) and
`SPUNTO_TEST_POSTGRES_ADMIN_URL` (operator); missing URLs must not silently skip
the acceptance job. Never point these tests at a production database.

The Linux image job runs Chromium, Firefox and WebKit. Collaboration browser
tests use controlled API fixtures for role changes, cache/draft boundaries,
shared editing and conflicts; real PostgreSQL HTTP tests separately exercise
server authorization. Cloud CI checks HTTP contracts, lint, locked dependencies,
secrets, the hardened image and known image vulnerabilities. A CI definition is
not a receipt of a successful hosted run, deployment or physical-device check.

Billing, per-account plans (one allowance tier applies to the whole server),
anonymous publishing, CRDTs, SCIM and back-channel logout are absent. An IdP logout/deletion alone does not revoke an existing
application session. Operators must revoke application credentials separately;
full application-account suspension/deletion and retention policies need an
explicit contract. Public APK/container distribution, offsite recovery and real
IdP/physical-phone acceptance retain their separate delivery gates.
