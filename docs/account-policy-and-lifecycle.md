# Account policy and lifecycle interfaces

These interfaces extend the PostgreSQL account mode. SQLite operation keeps its
existing behavior. Apply the numbered PostgreSQL migrations using the operator
connection, then run the application with its restricted runtime and identity
roles as described in [hosted operation](hosted.md).

## Optional allowance resolver

Set both `SPUNTO_AI_POLICY_URL` and `SPUNTO_AI_POLICY_TOKEN` to enable an
operator-trusted policy resolver. The token also accepts
`SPUNTO_AI_POLICY_TOKEN_FILE`. The URL must be a fixed HTTP(S) endpoint without
embedded credentials, query or fragment; it is not supplied by users. Use a
protected network and HTTPS where credentials could cross an untrusted link.
The token must have at least 32 characters. An incomplete pair, invalid URL or
use outside PostgreSQL account mode fails startup.

Core requests `GET {url}/{encodedAccountId}` with the bearer token, never follows
redirects, and bounds request time and response size. A valid response contains
`version: 1`, the exact `account_id`, integer Unix-seconds `expires_at`, `limits`
and optional `resources`. Expiry must be in the future and within five minutes.
`limits` supplies every action in `spunto.ai.quota.ACTIONS`; values are
nonnegative integers or `null` for unlimited. Resources accept only these keys:

- `storage_bytes` and `file_bytes`;
- `owned_shared_spaces` and `members_per_shared_space`;
- `mcp_writes_daily` and `ask_daily`;
- `transcription_seconds_monthly`.

A valid snapshot is cached per account for at most two minutes and never past
its expiry. Malformed, oversized, redirected, failed or expired responses use
operator defaults: the existing `SPUNTO_AI_QUOTA_PLAN`/`SPUNTO_AI_QUOTA_LIMITS`
for AI actions and `SPUNTO_ACCOUNT_POLICY_FALLBACK` for resources. The latter is
a JSON object; omitted resource fields add no cap. Configure explicit fallback
caps if resolver unavailability must restrict those resources. Failure backoff
lasts fifteen seconds and never extends an old allowance. Without a resolver,
existing AI defaults apply and these extra resource limits are not enabled.

Space storage, file size and membership limits follow its owner, including
writes by another member. Storage includes note bodies, revisions and attachment
metadata sizes. Existing data is preserved after a reduced allowance; growth is
refused by transactional database checks. AI reservations are atomic before a
provider call queues, use UTC calendar periods and persist across restarts.
Calls that never reach a provider return their reservation. With an external
policy, transcription measures WAV duration and refuses unverified formats.
MCP write admission counts the authenticated account's daily writes.

## Export and prepared closure

`GET /api/v1/account/export` requires a browser account session and streams an
archive of its owned spaces. It does not export another owner's space merely
because the account can access it.

`POST /api/v1/auth/account/closure` requires a browser session and the normal
origin check. The body supplies `confirm: "DELETE MY ACCOUNT"` and the explicit
boolean `delete_owned_shared`. A local account also supplies `current_password`.
An SSO-only account first completes fresh verification through
`/api/v1/auth/oidc/start?reauth=account-delete`; the signed provider authentication
must bind the same issuer/subject, account and session. The one-use proof expires.
That verification returns to `/cloud/account?security=reauthenticated`, an
integration route for a same-origin account UI; the bundled settings screen does
not supply the account-closure journey. An integrating UI must serve that route.

When owned shared spaces or note grants exist, preparation refuses an omitted or
false shared-data choice. Transfer ownership or explicitly authorize their
deletion. The response includes `closure_id`, `account_id` and the identity
binding. A service credential alone cannot invent a prepared operation.

`SPUNTO_ACCOUNT_SERVICE_TOKEN` (or `_FILE`) enables operator execution with a
separate bearer of at least 32 characters. It is unset by default; browser and
device credentials cannot substitute for it. Keep `/internal/` routes off public
ingress and send the exact prepared receipt:

| Method | Route suffix under `/internal/v1/accounts/{accountId}` | Contract |
|---|---|---|
| GET | `/export` | `X-Spunto-Closure` must match; returns the browser export URL |
| POST | `/close` | JSON `closure_id`; close this exact prepared account |
| POST | `/purge` | JSON `closure_id`; requires closure before owned-data purge |

Close rechecks shared-data ownership, blocks sign-in and revokes browser,
paired-device and access-token credentials, including those issued in another
owner's space. Purge removes owned spaces and their runtime files, removes this
account's membership/grants elsewhere, and retains other owners' data. Repeating
close/purge is idempotent. The operation record and a digest of a closed external
identity remain to prevent automatic recreation. Separate identity-provider and
historic-backup deletion are operator responsibilities.

## Restore quarantine

If `RESTORE-QUARANTINE.json` exists in `SPUNTO_DATA_DIR`, startup refuses before
opening a database or serving requests. A restore tool can place this marker
while reconciling current account-deletion decisions with a backup. Core does not
automatically create, reconcile or remove it. The operator must verify deletion
and credential state before removing quarantine; restoring an old snapshot alone
does not establish that current account closures are preserved.
