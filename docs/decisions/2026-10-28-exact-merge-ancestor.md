# Preserve edits when their exact merge ancestor is unavailable

Status: approved by the owner on 2026-10-28.

A stale edit may merge only against its exact base revision. When revision
coalescing or retention has removed that revision, preserve the submitted body
through the existing conflict-copy flow and retain the current note unchanged.
Do not substitute a nearby older revision. If the submitted body already equals
the current body, acknowledge it without creating an unnecessary conflict copy:
those exact bytes are already preserved and no merge inference is needed.

This applies to ordinary REST edits, individual-note collaboration and device
sync, including restore reconciliation. Future-version checks still request
reconciliation as before; this decision does not weaken account, space, note or
attachment access checks. A repeated sync operation retains its existing durable
receipt and cannot create another conflict copy.

The previous nearest-older fallback could silently ignore a deliberate revert.
For example, rapid saves `A` (v1), `B` (v2), then `C` (v3) remove v2 through
coalescing. An offline client editing v2 from `B` back to `A` would merge against
v1=`A`; the merge treated its incoming `A` as unchanged and acknowledged `C`.
The approved behavior instead preserves `A` as a conflict copy beside `C`.

The tradeoff is more explicit conflicts when a client's exact base has expired or
coalesced, even when an approximate merge might appear possible. Exact-base
independent edits still merge normally, and coalesced visible history and its
retention budgets remain unchanged. No additional merge-base persistence is
introduced.

This decision supersedes the nearest-older fallback in historical
`02-spec.md` §4.2 and §6.4. Approved documents 01–05 remain unchanged.
SQLite verification covers both domain edit paths, coalesced reverts, identical
bodies, future-version guards, retained exact-base merges, retention-expired
ancestors and idempotent sync conflict retries. PostgreSQL fixtures verify
coalesced missing-ancestor reverts in both domain edit paths. Existing tests and
source review protect the shared-grant and restore contracts.
