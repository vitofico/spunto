# Reviewed note actions in Ask

The owner approved creating and editing notes from temporary Ask conversations,
with previews, explicit confirmation and undo, on 30 October 2026. This extends
the [temporary conversation decision](2026-10-30-temporary-ask-conversations.md).
Historical design documents 01–05 remain unchanged.

## User flow

Sending a message can produce an answer, a clarification or a proposed note
action. It never applies a proposed creation or edit. A creation shows the draft;
an edit identifies the target and shows the affected text before and after.
Unchanged content must be preserved, including portions outside retrieval excerpts.
Ambiguous targets require clarification. There are no bulk or permanent-delete
actions in this revision.

Drafts and replacements are bounded to 3,500 characters. A whole-note rewrite
requires the complete original body; an excerpt cannot authorize replacing unseen
content. A note created in this conversation can be revised through its confirmed
creation receipt and fresh authoring context. It remains excluded from ordinary
factual retrieval. Temporary drafts explain what to revise and are never treated
as independent factual evidence.

Create note or Apply change explicitly confirms the server-owned proposal.
After success, Undo is available in the temporary conversation. Undo creation
moves the unchanged new note to Trash. Undo editing restores the prior content
only when the note still matches the applied change. A later edit or flag change
to that note causes a refusal rather than an overwrite. Revision history, existing provenance,
attachments and unrelated metadata remain intact.

Unconfirmed proposals are temporary. Clearing the conversation, expiration,
restart or disabling Ask invalidates outstanding proposals and chat undo handles.
An explicit action already admitted for execution may finish after the view is
cleared; clearing the view does not reverse a confirmed write. Normal revision
history and Trash remain available after chat undo expires.

## Protocol and compatibility

Ask version 1.1.0 advertises the write-preview protocol. Compatible clients opt in
with strict `note_actions: true` when allocating a new conversation. The capability
is fixed for that conversation; continuations cannot enable it implicitly.
Clients omit this field against older servers. Older clients keep the previous
chat and answer-saving behavior and never receive reviewed edit commands.

The existing three commands and closed UI vocabulary remain in use. The
`save-answer` command retains legacy `answer_id` arguments. Reviewed actions use
an opaque server-owned `action_id` and a stage: `preview`, `undo` or terminal
`done`. Clients use the supplied arguments; they do not submit an authoritative
body, target or version when confirming. Acknowledgements distinguish applied,
undone and refused results. Replaying confirmation or undo never repeats a write
or reapplies an undone change.

## Authorization and integrity

The dedicated `notes.review` permission is restricted to reviewed Ask actions
from an interactive writable session or device command. It is not a general
plugin permission to modify arbitrary notes. Existing installations require
their workspace owner's acceptance of the updated Ask permissions in Settings →
Plugins. Grants must not silently expand across other workspace owners.

Proposals and undo handles are bounded and bound to the caller, workspace, host
generation and conversation. Confirmation rechecks the authoritative target,
source eligibility, evidence fingerprints, access and exact version in the
writer transaction. Undo checks the affected note rather than requiring unrelated
source notes to remain unchanged. A model response is never authorization to write. Private
notes remain excluded from model input. Creating a note in an empty library can
use the user's explicit drafting instruction; unsupported personal facts must
not be invented or presented as retrieved evidence.

All mutations use the existing core storage and revision mechanisms. No writer
is held across inference. Cancellation, retries and duplicate requests must not
create duplicate notes or apply a change twice. Existing human-authored notes
remain human-authored after a confirmed edit; new generated notes retain the
normal machine-written provenance.

## Validation

Acceptance covers creation without existing notes, preview without mutation,
explicit confirmation, preserved untouched content, idempotent replay, stale
confirmation and undo, access/privacy changes, clear/in-flight behavior and
cross-version clients. Both SQLite and PostgreSQL storage, web and Android UI,
and real-provider synthetic acceptance retain their required delivery checks.
