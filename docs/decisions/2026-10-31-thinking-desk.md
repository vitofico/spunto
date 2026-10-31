# Manual thinking histories and idea collision desks

Status: implemented additive plugin contract; local verification is recorded by the delivery checks.

The optional **Thinking desk** plugin provides two deliberate workflows without AI:

- **History**: choose notes on a topic, read their live cards in creation order, and explicitly record directed `builds-on`, `challenges`, or `replaces` connections. The first note has the stated relation to the second. Remove a connection explicitly; replacing never edits or deletes a source.
- **Collision**: choose two to five notes from different subjects, optionally state a problem, then use analogy, disagreement/experiment, and transferred-principle prompts to write an idea. Subject diversity is a human choice; the plugin does not claim automatic topic detection.

Both modes have an authored reflection and an explicitly requested **Keep private note copy** action. The displayed desk is the preview of that snapshot. Collision snapshots require at least two sources and a nonempty authored idea. Snapshots are ordinary owner-authored private notes, not AI output, and never follow subsequent desk changes. Originals are never modified. History supports ten sources and twenty directed connections. These are workspaces, not projects, tasks, reminders, or a feed.

## Contracts and fences

The bundled plugin defaults off and requests only `notes.read` and `notes.create`. No AI, network, MCP or unattended work is declared. All commands require a writable space-owner session/device, retain the host's current account/space authorization, and use the existing generated web and Android vocabulary. Read-only credentials cannot enter the workspace. Commands require a connection; Android can read previously synced snapshot notes offline. Web has no offline replica.

Plugin records retain source IDs, revision baselines, creation dates and private/external classifications, with no source bodies, titles or excerpts. Note cards resolve current text through ordinary note visibility on every render. Unavailable sources display no cached source text. User-authored reflections and relationship records remain until explicitly changed or deleted. There is no plugin data export to agents.

Workspace edits use record compare-and-set with an expected revision. Directed connections refuse stale or unavailable sources. Snapshot creation additionally checks source visibility and revisions atomically through the host's `source_versions` contract; it always creates a private note. A changed source blocks a snapshot until the user reviews it and explicitly acknowledges current revisions. Removing an unavailable source is possible without reading it and also removes its connections. A stale workspace error instructs the user to reopen the desk and review sources; it never overwrites a concurrent edit. The UI's current editor text is preserved by the existing command form recovery.

Three commands fit the plugin contribution budget: `desk`, `edit`, and `sources`. `sources(action="connect")` exposes connection forms; command arguments and hidden chooser mappings retain the existing generated client contract. Source selection captions are live and bounded, while IDs/revisions are carried in hidden chooser mappings. Repeated snapshot requests for one workspace revision use the host's idempotent note-creation receipt.

## Acceptance

Enable Thinking desk in Plugins, then open it in the command palette or a note's actions. Create a History, select at least two notes, open **Connect notes**, and connect the newer note to the older with **challenges**. Confirm the dated chronological view, explicit direction and unchanged originals. Remove the connection and confirm only the relationship disappears.

Create a Collision, select notes from two subjects, enter a question and write an idea using the creative prompts. Review the displayed result, then keep a private note copy. Confirm the copy contains the authored idea, is private and works as an ordinary offline-synced Android note. Edit a source elsewhere: the desk reports a changed revision and snapshot saving fails. Review sources, acknowledge revisions and retry. Delete or revoke access to a source: its card disappears and saving remains blocked until it is removed. Concurrent desk edits reject the stale save with recovery guidance.

Automated coverage: `server/tests/test_thinking_desk.py`, the Thinking desk case in `web/e2e/new-features.spec.ts`, and `thinkingDeskCollisionReviewsIdeaAndKeepsPrivateSnapshot` in Android `NewFeatureWorkflowTest`. Browser/native acceptance uses disposable synthetic servers; it is not evidence of production rollout or physical-device acceptance.

Local verification on 2026-10-31 passed the server, web and Android delivery gates. The default server run passed 1,268 tests with 99 skips; a separate restricted-role PostgreSQL run passed all 146 tests. All 379 web unit tests passed. Browser acceptance passed 198 tests across Chromium, Firefox and WebKit, with six expected fixture skips; the packaged-image backup/restore smoke also passed.

The final Android native suite passed 148 tests, followed by five actual guarded real-server workflow passes without skips. History connections, Collision snapshots, saved-text export and the narrow large-text Boolean control were exercised. The label wraps beside a named switch with a 48 dp minimum target, and the control and submit action remain reachable. Initial web JavaScript is 152,966 bytes gzip against the 160,000-byte ceiling; fonts are 319,488 bytes against 320,000. These measurements satisfy the delivery ceilings; JavaScript remains above the 150,000-byte target.

## Search, recovery and sharing polish

Web search now shows bounded plain-text excerpts around a matching word, including deep matches in long notes and link destinations. Android also includes a deep match from a long first line, rather than discarding that line as a title. Both clients distinguish search-term matches from similar meaning. This is a presentation of existing search results, not a new retrieval mode.

A stale web plugin form keeps entered text mounted and explicitly advises copying it before reopening the tool and reviewing changed sources. Existing note-editor conflict copies and merge recovery remain unchanged.

The web note-sharing form previews the typed recipient and selected read/edit capability before granting access. It includes linked attachment access, excludes linked notes and the rest of the space from this grant, and acknowledges independent space membership or other grants and already-made copies. Android's existing OS text-share flow now previews the exact saved text before the recipient chooser for public and private notes. It explains that this sends a copy with written links, transfers no attachment files, grants no Spunto access, and cannot revoke recipient copies. Its existing revision/credential/export stamps still guard confirmation.

Focused hosted regressions use the real PostgreSQL fixture and HTTP authorization path: cross-space source/desk rejection, membership revocation, restricted private/external reads, and a scheduling barrier before the actual transactional snapshot creation to verify changed or trashed sources cannot produce a new snapshot.
