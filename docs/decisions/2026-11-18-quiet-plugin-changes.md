# Quiet plugin changes with undo

**Approved for implementation on 18 November 2026.** This record revises “Plugins propose; only the owner edits” (04 §3.8, rule 3) and “Switching a plugin on never starts it directly” (05 §5.2). The acceptance criteria below govern the release.

## Why

Adding a familiar label can cost the same attention as merging two notes. Label suggestions already has an opt-in path for applying familiar labels automatically. Other proposals usually wait for the owner to find and accept them. Tidy on Android can show a comparison twice. Once a change is accepted, the owner needs a visible way to take it back.

The aim is to let small filing work happen quietly, keep a review for changes that deserve it, and make the result easy to recognize on either device.

## Three tiers

The host chooses the tier from the action, the plugin’s approved capabilities, the owner’s setting and the current note. It checks eligibility again in the write transaction.

- **Quiet.** In Automatic mode, add a high-confidence label already in use, add a heading to an eligible note, or append a transcript to a recording-only note. Each operation adds content to one note and has a working Undo.
- **One tap.** Offer a pill for a new label, a proposed link, or a quiet-eligible action in Ask first mode. Replacing an explicit title shows the proposed title before the tap and changes only that heading.
- **Reviewed.** Preview, then Apply for wording changes, merges, label renames, label merges and removals. Tidy of dictated notes stays reviewed in this rollout. A later proposal could define a separate opt-in for automatic rewriting.

## Delivery and acceptance

Steps 1 and 2 fit the original design rules. Steps 3–5 implement the revised rules. Each step must meet its criteria on web and Android wherever the feature is available.

### Step 1. Acknowledge decisions and make single-note changes reversible

Use core feedback (web toast or Android snackbar) to name the change. The note’s suggestion line also gives the owner access to Undo after the transient message disappears. Dismissing that line hides the receipt and keeps the change.

**Acceptance criteria**

- Record the actual change, its resulting revision and the suggestion responsible in the same transaction. A no-op creates no Undo. Replaying an accept or undo request changes the note at most once.
- If two labels are applied in one revision, undoing either removes only that label. The other remains independently undoable while its change is intact. Undo of an automatic label records a dismissal, excludes that label from subsequent suggestions on the note and contributes that decision to plugin feedback.
- An intervening independent edit, including a plugin or collaborator edit, invalidates a revision-bound Undo. The server preserves the newer work and the client offers **Open history**. A quiet change made while the owner was away remains discoverable after reload or sync while its Undo is valid, until dismissed.
- On Android, accept then undo offline, restart, and reconnect. The operations retain their order and survive retries. Show **Undo queued** until confirmed. If another device edited first, surface the refusal and a route to History after sync.
- A merge or library-wide label change gets acknowledgement only in this release. Before offering Undo for one, demonstrate atomic restoration of every affected note and flag, including the trashed merge source, and rejection of the entire undo if any affected note changed.

### Step 2. Apply inside command results

Keep the comparison and the decision in the same sheet. Give Android’s global commands (Ask, Digest and Library stats) an entry in the existing app-level navigation outside plugin settings.

**Acceptance criteria**

- For a reviewed action, show **Apply changes** in the sheet when it displays the comparison for that exact suggestion and base revision. A tap commits once, acknowledges the result and returns focus to a predictable place. A stale comparison requires a fresh review.
- When Tidy omits its diff, show **Review changes** and open the full comparison before enabling Apply. Exercise both an ordinary note and a single paragraph over 4,000 characters, which triggers this fallback today.
- Suppress the matching note pill only while its result is open. Closing the sheet without deciding restores the pending pill. Other pending actions remain reachable.
- On Android, invoke a global command without opening Settings or selecting a note. Note-specific commands remain in note actions.

### Step 3. Add Ask first and Automatic for labels

Expose one behavior setting per eligible plugin. Start new installations on **Ask first** and let the owner opt in to **Automatic**. Migrate existing label settings by stored value. The host persists defaults, so an untouched false and an explicit opt-out can have the same representation. Keep the confidence threshold as a separate advanced control.

**Acceptance criteria**

- Existing true becomes Automatic. Existing false or missing becomes Ask first. Both clients show the persisted value after restart. An owner can change this setting on Android without exposing unrelated server settings.
- In Automatic, only a label currently in use and at or above the configured threshold applies quietly. A new or below-threshold label remains pending. In Ask first, every eligible label remains pending.
- Quiet writes are limited to the space owner’s own notes. Check authorship, access, origin, privacy and machine status at commit time, including for a job queued before a setting or visibility change. Cover owner-created web and Android notes, another member’s note, private, machine, agent and external notes. If authorship cannot be established, skip the quiet write.
- With three suggestions visible, accepting one leaves the other two usable. After the save-triggered job has had time to run, neither accepting nor undoing a label starts a chain of automatic edits. A subsequent owner text edit can still trigger fresh suggestions.

### Step 4. Add quiet titles and recording transcripts

Treat a leading Markdown H1 as the explicit title. Spunto also derives a display title from the first nonempty line, so ordinary opening prose can already look like a title in the timeline. For recordings, Android writes a Markdown audio link and may include a caption.

**Acceptance criteria**

- Adding a quiet title preserves the opening prose. An existing explicit heading is eligible for a one-tap replacement that shows the proposed text and leaves exactly one title heading. If the owner adds a heading while the model is running, discard the stale quiet proposal.
- Automatic titles apply prospectively to eligible captures or owner edits. Enabling Title and summary on a populated library may generate pending proposals through its backfill. It makes zero automatic changes to historical notes during that backfill.
- A quiet transcript requires a body containing only verified, generated markers for that note’s attached recordings and whitespace. Preserve all audio links and append each transcript once. A caption, edited marker, unrelated attachment, concurrent edit or a setting that removes links routes the change to review or discards a stale result. Exercise both one and multiple recordings.
- A retry after automatic application preserves the plugin’s processed record, summary or transcription receipt and creates no duplicate content or provider call for the completed result. Exercise the version change caused by the plugin’s own write.
- For Link enrichment with a proposed title and three label ideas, fit the existing three-pending-item budget. Give a pending title the first slot, then fill available slots with labels in confidence order. The command completes and every displayed action works.

### Step 5. Simplify plugin enabling and expose the plugin page

Make the row title open details whether the plugin is on or off. Give eligible bundled plugins a separate one-tap switch. Keep the plugin’s purpose, behavior, data destinations and output location together on its page.

**Acceptance criteria**

- A bundled plugin with stable permissions and source, and no additional external disclosure due, enables in one tap. Operator-installed plugins and changed permissions or source identity go through review. A source change remains marked for review across refresh and restart, even when the permission names are identical.
- Bind consent to the reviewed identity and permission set. If either changes between display and enable, the server leaves the plugin off and returns the current details for review. Preserve the existing permission-mismatch refusal.
- Before enabling or re-enabling a plugin that sends data externally, show what leaves the space and its destination at the point of action. For Webhooks, show the configured destinations and that eligible note bodies are sent. For Link enrichment, explain that saved URLs are fetched from their sites. Settings → AI describes the model route separately.
- When an enabled plugin is waiting for a model, its command location explains what is missing. An owner can open the relevant setting from there. Regular note reading and capture remain available.

## UX and visual direction

Keep the note text visually dominant. Use the existing suggestion line, theme tokens and host controls on both clients.

- **Give proposals and receipts different shapes.** Keep the dashed pill and action verb for a pending change, such as **Add #recipes**. After acceptance, use plain secondary text, **Added #recipes**, with **Undo** beside it. This makes the state legible without relying on color.
- **Keep the page still while someone reads or types.** Reuse the suggestion line’s existing single-row budget and overflow affordance. Keep other pills in place when one is accepted. Background changes belong on their note. Reserve immediate snackbar or toast feedback for the note the owner is acting on.
- **Use the result sheet for the decision.** Put the affected text first and the action in a consistent footer. On a long diff, keep the action reachable and the last changed line fully scrollable above it. Reserve the accent for the primary action. Pair added and removed text with visible markers that also work in monochrome.
- **Make settings explain the effect before the switch.** A short example such as “Automatically add labels you already use” helps the owner choose. Show the confidence number under Advanced. Put model or destination details next to the control when they affect the decision.
- **Check the design in use.** On a narrow phone with enlarged text, verify that a long label, Undo and overflow remain reachable. On web, complete Apply, Undo and Review by keyboard. Check visible focus, screen-reader announcement of a change exactly once, readable contrast in light and dark themes, and reduced-motion behavior. Background updates preserve editor focus and scroll position.

## Rollout defaults

The rollout uses Ask first on fresh installations, quiet changes limited to the space owner’s own notes, and an applied item visible until dismissal or loss of undo eligibility. The first release keeps historical backfills and dictated-note rewrites under review.
