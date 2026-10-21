# Hosted delivery — 2026-10-18

The approved [hosted revision](../decisions/2026-10-18-multi-user.md) is implemented: shared PostgreSQL storage with restricted roles and workspace row security; hosted username/password sign-in and optional generic OIDC with PKCE; personal and shared spaces with owner/editor/viewer access; and revocable individual-note grants. SQLite owner-password self-hosting remains supported. The separate private Spunto Cloud composition uses core HTTP interfaces.

Web identity changes fence requests, caches and drafts. Existing-note and shared-note autosave use version checks, merge and preserved conflicts. Android browser pairing binds device credentials and local replicas to the verified account and workspace; account changes retain separate pending outboxes and drafts. Repeated account switches reuse one Room handle per replica.

Labels remain the primary organization tool. Spaces define access boundaries; there is no project entity. Billing, quotas, SCIM, account deletion, back-channel logout, live cursors and live CRDT editing are deferred.

## Verified source and clients

| Baseline | Evidence | Result |
|---|---|---|
| Core `9f634a4` | Server CI `39132037078` | Green, including 77 real PostgreSQL cases with zero skips and browser acceptance in Chromium, Firefox and WebKit |
| Hosted HTML fix `977169b` | Server CI `39136016919` | Green, including PostgreSQL, web and both container architectures |
| Android | CI `39132037074` | Green |
| Security follow-up | CI `39136016948` | Green; CodeQL was skipped |
| Cloud `7356d36` | CI `39135202102` | Green, including 99 tests |

Follow-up server revision `977169b` fixes public hosted HTML rendering before a workspace is selected. Its independent review, 17 PostgreSQL hosted tests, 25 SQLite/web/theme regressions and required pre-commit checks passed. Anonymous HTML uses packaged themes without reading any workspace; authenticated preferences and custom themes load through scoped APIs.

The current local owner-signed Android APK has version code **9**, size **4,459,585 bytes**, and SHA256 `a55fdbaf66d332139be1d917ae3c50151bfb0e5c4f09b32e51337b6bcaf1e588`. The debug APK was regenerated at **13,740,209 bytes** and its DEX verified against the latest source. Focused `NativeNavigationTest` and `AccountReplicaSwitchTest` acceptance passed all **3 emulator tests**; the local receipt is `/tmp/spunto-local-auth-emulator-acceptance.log`. No physical-phone acceptance or APK publication is claimed.

## Remaining checks

Physical-phone acceptance remains unverified. APK publication and billing remain separate follow-up work.

## Password login and scoped import delivery

Both repositories are privately published. The CI descendants below contain only
test fixture/scheduler corrections; the runtime images and the APK above
remain unchanged.

| Delivered follow-up | Evidence | Result |
|---|---|---|
| Core runtime `6279eaef9110` | Server CI `39141399110`, descendant `09710e0` | Green; descendant fixes only the SQLite profile test fixture |
| Core security | CI `39142400335` | Green |
| Android | CI `39142400296`, descendant `7983b62` | Green; descendant fixes only a test scheduler handoff |
| Cloud `463f201224af` | CI `39140539036` | Green |

Hosted login supports usernames and passwords independently of an identity provider.
Registration remains closed by default; operators can create accounts or reset
credentials without contacting an IdP. Existing SSO accounts establish their first
local credentials after fresh, browser-bound provider verification. Local password
changes rotate browser sessions, while explicit account/credential fences prevent
late cross-tab responses from mixing accounts or losing pending drafts. Shares
accept exact usernames as well as verified email addresses and account IDs.

Local acceptance passed 988 server cases with PostgreSQL-only cases skipped, followed
by a serialized selection of **119 cases with zero skips**: **90 actual PostgreSQL
cases and 29 OIDC cases**. Web acceptance
passed 200 unit cases and 12 Chromium/WebKit browser cases. Cloud passed 116 cases.
Independent backend, client, deployment-composition and scoped-import reviews closed
with no open findings. The fixture and scheduler corrections also passed bounded
independent review before the green CI runs above.

The operator import opens only the verified owner's selected workspace and creates
no browser session. Synthetic PostgreSQL acceptance covers source timestamps,
provenance, attachment integrity, idempotent replay and preservation of owner edits
and unrelated workspaces.
