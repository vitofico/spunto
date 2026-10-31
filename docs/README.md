# Spunto docs

Spunto is a self-hosted notes app with optional AI, Android, web and MCP clients, and plugins that appear in fixed, budgeted places. Local mode uses one SQLite database and an owner password. The approved [2026-10-18 hosted revision](decisions/2026-10-18-multi-user.md) adds username/password sign-in, optional OIDC, shared PostgreSQL, personal/shared spaces and revocable note grants. Labels remain the main organization tool; there is no project entity, live CRDT editing or billing. [Hosted operation](hosted.md) describes the current behavior and the separate private Spunto Cloud composition.

Completed milestones and remaining delivery/physical-phone gates are recorded in [the delivery record](development/full-product.md). Import and retrieval acceptance use synthetic fixtures. The signed Android `2026.10.16.1` receipts are historical evidence, not proof of hosted cutover or real IdP/physical-phone acceptance. APK/container publication and offsite recovery verification remain separate gates.

**Read first:** the hosted decision and guide for the multi-account revision; `01-requirements.md` and `02-spec.md` for the original core and local mode; `03-design.md` for the interface, with the mockups open beside it. Historical approved documents 01–05 remain unchanged. `04` and `05` explain the core-plus-plugins design; the research files are background.

| File | What it is |
|---|---|
| `api.md` | REST, device sync, attachments, plugin management and MCP access |
| `hosted.md` | Multi-account/password/optional SSO behavior, spaces and note grants, PostgreSQL operation and current limits |
| `decisions/2026-10-18-multi-user.md` | Approved revision to the original single-owner/SQLite-only design |
| `decisions/2026-10-28-exact-merge-ancestor.md` | Approved conflict preservation when a stale edit's exact base revision is unavailable |
| `decisions/2026-10-30-temporary-ask-conversations.md` | Temporary cited Ask conversations, fresh passage retrieval, explicit saving and index rebuild behavior |
| `decisions/2026-10-30-reviewed-chat-note-actions.md` | Chat note creation and editing with previews, explicit confirmation, guarded undo and permission consent |
| `decisions/2026-10-31-ask-note-action-ux.md` | Discoverable Ask examples, grouped note previews, confirmation and contextual undo feedback |
| `decisions/2026-10-25-header-wave.md` | Existing wave in the compact Android header and web upper chrome, with theme and accessibility constraints |
| `development/multiuser-migration.md` | Lossless SQLite import, identity mapping, sequence reconciliation and cutover gates |
| `development/multiuser-delivery.md` | Hosted delivery evidence, production recovery and remaining acceptance checks |
| `decisions/2026-10-31-thinking-desk.md` | Manual chronological thinking histories, directed note relations and deliberate idea collisions |
| `plugins.md` | Trusted operator plugins/API1.0, colour packs and inbound capture configuration |
| `operations.md` | Build, snapshots, restore, public HTTPS deployment and Android signing |
| `android-distribution.md` | Separate debug app, CalVer, signing identity, Obtainium, F-Droid preparation, reproduction and release recovery |
| `development/android-distribution.md` | Current local Android delivery, exact reproduction, profile coverage, emulator evidence and remaining external gates |
| `development/full-product.md` | Active full-product scope, audit and delivery gates |
| `development/phone-checklist.md` | Owner checks after delivery of the updated APK and deployment |
| `development/M6.md` | Organization/development plugin contracts, reviewed server evidence and final client gates |
| `development/M7-capture.md` | Reviewed server capture contracts and pending native/physical gates |
| `development/M7-markdown.md` | Markdown import contracts, fixture evidence and review in progress |
| `development/M1.md` | Completed local M1 evidence and outstanding external checks |
| `development/M2.md` | M2 implementation, fixture verification and local validation |
| `development/M2-blinko.md` | Historical fixture evidence for the removed Blinko importer |
| `development/M3.md` | Web implementation, browser verification, review and local validation |
| `development/M4.md` | AI implementation, integration checks, review and local validation |
| `development/M4-retrieval.md` | Reproducible English/Italian synthetic retrieval comparison |
| `development/M5.md` | Native Android, offline sync, emulator validation, public HTTPS and remaining phone checks |
| `01-requirements.md` | Principles, user stories (each tagged `core` or `plugin:<id>` with a milestone), the M0 to M8 plan, non-functional targets, success criteria, and the record of decisions D1 to D33 |
| `02-spec.md` | How it is built: architecture, the plugin host and the 25-plugin catalog, data model, HTTP API, sync, AI gateway, MCP, security, self-hosting, Android, web, testing, milestones, the decision register (§17) and risks |
| `03-design.md` | How it looks and behaves: principles, navigation, flows, screens, the clutter contract (slots and budgets), copy, motion, accessibility, tokens and the three themes |
| `04-redesign.md` | Decision record for the core-plus-plugins redesign: the mechanism, the catalog's reasoning and the options behind D25 to D33 |
| `05-redesign-ux.md` | Decision record for how plugin output and themes look: U1 to U5 and the full theme contrast tables |
| `design/mockups.html` | Core screens on Android and web, in the default Carta theme (open in a browser) |
| `design/redesign.html` | Plugin slots, the plugin manager, AI flows and the three themes, frames R1 to R24 |
| `research/blinko.md` | Teardown of Blinko 1.8.8, the app Spunto replaces |
| `research/landscape.md` | Survey of note apps, capture, AI features, design and how other apps expose MCP |
| `research/stack.md` | What Spunto can reuse from Quire, Galley and the owner's cluster |

Requirement IDs (CAP-1, PLG-3) are defined in 01; original decisions (D1 to D33, U1 to U5) are recorded in 01 §9 and 02 §17. The 2026-10-18 decision supersedes the single-owner/SQLite-only restrictions for hosted mode. Other original requirements and the core-plus-plugins design continue to apply.
