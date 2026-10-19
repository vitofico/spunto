# Shared hosting, identity and collaboration

Status: approved by the owner on 2026-10-18; implementation and verification in progress.

The owner now authorizes multi-user support, shared PostgreSQL hosting, generic
OpenID Connect sign-in (including Authentik), a separate `spunto-cloud` repository,
shared spaces, and individual note sharing. Both repositories are to be private
on GitHub, followed by deployment after verification. This supersedes D8 and the
SQLite-only restriction for hosted deployments. The original approved documents
01–05 remain an unchanged historical design.

## Product model

An account is a stable authenticated person, identified by issuer and subject,
never by a mutable email address. Every account starts with a personal space.
Spaces define ownership and access, while labels remain the way to organize
notes. The default personal space requires no folder/project workflow. Explicit
shared spaces have owner, editor and viewer memberships. Individual note grants
give access to that note only, without exposing its owner's other notes, labels,
credentials, exports or settings. Grants are to existing accounts and are
revocable; no anonymous public-link publishing is included.

Collaborative edits use the existing versioned autosave, merge and preserved
conflict behavior. Live cursors and character-by-character concurrent editing
are deferred by the owner's explicit choice. Projects are not another entity:
use labels for projects, and shared spaces when the access boundary changes.

## Architecture

One shared application deployment serves all accounts. PostgreSQL domain tables
carry a space key with composite constraints and row-level security. Bound store
handles set transaction-local identity, preventing connection-pool context reuse.
The runtime database role cannot bypass row security. SQLite remains the simple
single-owner self-hosting backend. PostgreSQL compatibility must be exercised by
real database contract tests; SQL/search/migrations are not assumed portable.

Generic OIDC uses authorization code plus PKCE, state, nonce, strict issuer and
audience verification, trusted JWKS and opaque application sessions. Identity
provider tokens are not application sessions. Native devices can pair through
an authenticated browser without embedding an OIDC client secret. Account and
space identity must scope browser caches, drafts, device tokens and native
replicas. Roles and grants are checked on every operation, including revocation.

`spunto-cloud` composes through documented HTTP and configuration interfaces.
Account/subscription operations and future commercial entitlements stay there;
it does not fork or import the notes implementation. Billing is a later stage.
The AGPL obligations of Spunto remain applicable; repository boundaries do not
change licensing obligations.

## Delivery gates

Use synthetic identities and note fixtures. Exercise two-account positive and
negative controls, pooled-connection isolation, workspace roles, revocation,
sharing, stale edits, OIDC failures/replay, and SQLite regression coverage. Before
production cutover, verify a consistent backup, isolated recovery, migration
preservation and owner account mapping. Never auto-link the existing library by
matching an email address, and never erase a device outbox during account changes.

Real Authentik and cluster acceptance are separate from synthetic provider tests.
No launch-ready billing, live collaboration or physical-phone checks are claimed
until their actual acceptance criteria are met.
