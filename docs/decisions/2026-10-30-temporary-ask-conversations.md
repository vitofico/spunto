# Temporary Ask conversations and fresh evidence

Status: approved by the owner on 2026-10-30.

Ask your notes becomes a temporary conversation with follow-up questions,
citations on each answer and explicit per-answer saving. This extends the
single-question flow in the historical design without changing those documents.
The conversation is not a note or a persistent chat history.

## Interaction

Web palette questions and Android direct/search entry open the same conversation
experience. Previous questions and answers remain visible while the conversation
is open. Each answer has its own numbered source links, Copy action and, when
supported by current sources, Save as note action. A failed or unsupported answer
does not offer Save. A refused save must show its explanation rather than claim
that a note was created.

Follow-up questions retrieve notes again. Earlier questions and still-current
answers help resolve references such as “why?” or “compare those two.” Earlier
assistant prose is conversation context, never evidence for a new factual claim.
If a previous answer's cited evidence changed or is unavailable, that answer is
omitted from model context. Fresh source text alone supports the new answer.

New conversation, closing the result, leaving the native screen, changing account
or workspace, or disabling Ask discards the client conversation. Clients keep
messages only in React/Compose memory, never browser storage, Room, saved instance
state or the note database. At most 20 turns remain in the visible client list.

The server keeps at most 32 conversations per workspace host, eight recent turns
per conversation and 256 expiring answer receipts. Idle conversations expire
after 15 minutes; process restart also forgets them. Handles are opaque random
values bound to the exact caller credential, account/workspace authority and
plugin generation. No question history or note body is written to logs. Existing
content-free AI usage statistics remain.

## Command compatibility and ordering

The existing plugin command vocabulary remains in use; there is no new UI
component type or plugin permission. The three declared commands are `ask`,
`save-answer` and `clear-conversation`.

- `ask({})` retains the existing question form for older clients.
- `ask({start: true})` allocates an empty conversation and returns continuation
  controls without calling a model. `start` is a strict boolean and cannot be
  combined with question or continuation arguments.
- A continuation command has ID `ask` and arguments `conversation_id` and
  `after_turn`. A client adds a nonblank `question` of at most 500 Unicode
  codepoints. The turn counter must be an exact nonnegative integer matching the
  current conversation. Only one question per conversation may be in flight.
- A completed response contains the usual text/Markdown and source components,
  an optional `save-answer` command for that answer, and the next continuation
  plus `clear-conversation` controls. Unsupported answers still allow follow-ups.
- `ask({question: ...})` remains supported for older one-shot clients; the server
  creates a conversation automatically and returns the same result vocabulary.
- `clear-conversation({conversation_id: ...})` invalidates that conversation,
  future saves from its unsaved receipts and in-flight answer publication. A
  saved note survives. An explicit Save already accepted before Clear may finish;
  Clear does not undo an accepted note-creation request.

New clients allocate a handle before the first inference. If allocation completes
after the view was cleared or disposed, they clear the returned handle and never
start inference. Clearing or switching identities fences late callbacks; aborting
an HTTP request alone is insufficient. The server also checks the live
conversation object and active turn after inference, preventing a late request
from recreating cleared history or receipts. Cancellation cannot retract data
already sent to a provider.

Expired/stale handles and concurrent turns fail with stable problem codes and
instructions to retry or start a new conversation. A provider error preserves the
previous completed turns; a retry uses the same known conversation handle.

## Retrieval and passage boundaries

Ask owns its embedding route, independent of the Semantic search extension and
its interactive search deadline. It combines bounded semantic candidates,
any-term keyword candidates and recent cited references, then reloads eligible
notes. Ordinary note search retains its all-term behavior. Inline labels apply
to every candidate, with canonical exact/descendant matching. A follow-up keeps
the previous label scope unless it explicitly supplies another scope.

Each turn supplies at most eight fresh sources and a shared 10,500-character
source budget. That is a character bound, not a token or price guarantee. Recent
conversation context is separately bounded to 6,000 escaped characters and
fenced by the gateway as untrusted data. Answers remain at most 1,200 characters.
Sources never include private, trashed or unedited machine-written notes;
external-origin restrictions and workspace authorization remain in force.

The AI gateway reloads full note bodies and enabled extracted text before each
provider attempt. It assembles query-relevant passages within the shared and
per-note budgets. Semantic candidates carry only bounded chunk ordinals and
hashes; the gateway reconstructs chunks from fresh authorized evidence and accepts
only exact ordinal/hash matches. Stale or forged hints fall back to fresh lexical
passages. Caller-supplied bodies and excerpts are never used as authoritative
source text.

An evidence fingerprint covers the exact body plus enabled extracted-text rows
and their relevant ordering. The gateway checks the fingerprint before sending
and again after asynchronous passage selection. Ask rechecks sources after
inference. Explicit saving validates both note versions and cited evidence
fingerprints inside the existing serialized writer transaction. Editing a
transcript, disabling its extractor or changing a note therefore cannot silently
save an answer grounded in obsolete evidence.

## Index format and operation

Chunk format version 2 bounds oversized single paragraphs to 1,000-character
chunks with 200-character overlap. The format version participates in the index
fingerprint. On upgrade, the existing active/target route machinery prepares a
new index and backfills eligible notes. The previous active index remains
available until the target is complete. Old hints that do not match fresh chunks
use the lexical fallback.

This is an automatic index rebuild using configured embedding providers and can
consume embedding capacity or provider allowance. It requires no database schema
migration or manual destructive reindex. Check indexing in Settings → AI after
upgrade. No production reindex, publication or deployment is implied by local
implementation and testing.

The existing owner-only plugin-command authorization policy remains. Expanding
Ask to other workspace roles requires a separate access/egress policy decision.
The change does not introduce a general guarantee that revoking a session aborts
every already-authorized API request; fresh source checks and conversation Clear
have the specific behavior above.

## Acceptance

Tests cover deep passages, oversized paragraphs, extracted-only evidence,
semantic hint reconstruction, Unicode/Italian queries, source budgets,
source/extractor changes, validation retries, atomic saving, per-caller handles,
ordered/concurrent turns, Clear during inference, idle expiry, bounded escaped
history, per-answer save refusals and client disposal/identity changes. Model
quality acceptance uses synthetic notes only. Fake-provider contract checks must
be distinguished from actual-provider answer quality and deployment acceptance.
