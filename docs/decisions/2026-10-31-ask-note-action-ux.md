# Discoverable note actions in Ask

The Ask note creation and editing flow now explains its capabilities before the
first message and keeps each proposal together with its confirmation controls.
This refines the [reviewed note actions decision](2026-10-30-reviewed-chat-note-actions.md)
without changing the mutation protocol or its authorization requirements.

## Interaction

An empty conversation offers example questions and, when the connected server
supports reviewed note actions, examples for creating and editing a note.
Choosing an example fills the composer and gives it focus. It does not send a
message or confirm a write. Examples do not replace text the user is already
writing. The composer accepts questions and requests to draft or change notes.

A proposed action appears in one card with a clear new-note or edit heading,
the complete server-provided preview, and Create note or Apply change beside
Cancel. Edit previews retain the authoritative target name and all Before/After
parts. Related note links are distinguished from the Sources of factual answers.
Clients do not reconstruct note bodies or invent targets from reference order.

After confirmation, the card acknowledges the saved result and offers Undo.
The explanation distinguishes moving a newly created note to Trash from
restoring an edited note's previous text. Undo remains guarded against later
changes and belongs to the temporary conversation. Opening a note or otherwise
leaving the chat does not preserve the chat's undo controls. Confirmed notes
remain saved. Terminal and cancelled states no longer offer confirmation.

## Boundaries

The server remains the authority for previews, operation receipts, permissions,
freshness and writes. A preview card does not make its body directly editable.
Refining a proposal uses another message and another explicit confirmation.
Older servers keep the supported question-and-answer flow; create/edit examples
are only offered when the existing capability negotiation allows them.

Web and Android retain their existing ownership and conversation lifetime
checks, strict acknowledgement matching, and protection against duplicate
confirmation. Android action state remains owned by the conversation rather
than disposable scrolling rows. No dependencies, migrations, permissions or
additional mutation endpoints are introduced.
