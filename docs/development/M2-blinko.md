# Blinko 1.8.8 importer contract

Historical implementation and acceptance evidence. The importer was [removed on 2026-10-19](../decisions/2026-10-19-remove-blinko.md); existing imported notes and attachments are preserved.

The importer was checked against the public `1.8.8` tag, commit
`178e4b3538a21823a50ac2481e653d56cb1ffa86`. This is source verification,
with synthetic HTTP fixtures; it is not a check of the owner's running instance.

- [Release](https://github.com/blinkospace/blinko/releases/tag/1.8.8).
- [REST router](https://github.com/blinkospace/blinko/blob/178e4b3538a21823a50ac2481e653d56cb1ffa86/server/routerTrpc/note.ts):
  `POST /v1/note/list`, JSON body `page`, `size`, `type: -1`,
  `isArchived: null`, `isRecycle: false`, `orderBy: asc`, `isUseAiQuery: false`.
  Listing returns an array, not a `{items, total}` envelope. Passing null for
  archive state is necessary to include archived notes. It includes attachments,
  inline content and comments with account names.
- [Server mount](https://github.com/blinkospace/blinko/blob/178e4b3538a21823a50ac2481e653d56cb1ffa86/server/index.ts):
  OpenAPI middleware is mounted at `/api`, giving `/api/v1/note/list`.
- [Schema](https://github.com/blinkospace/blinko/blob/178e4b3538a21823a50ac2481e653d56cb1ffa86/prisma/schema.prisma):
  note types are numeric; state is `isTop`, `isArchived`, `isRecycle`, times
  are `createdAt` and `updatedAt`. Attachment metadata carries `path`, `name`,
  `size` and `type`.
- [File router](https://github.com/blinkospace/blinko/blob/178e4b3538a21823a50ac2481e653d56cb1ffa86/server/routerExpress/file/file.ts):
  the returned attachment path is used for downloads; the normal file endpoint
  is `/api/file/...` and accepts authentication. Same-origin downloads receive
  the bearer token. External storage URLs are rejected before a request rather
  than sending that token to a different origin.
- [AI comments](https://github.com/blinkospace/blinko/blob/178e4b3538a21823a50ac2481e653d56cb1ffa86/server/aiServer/index.ts):
  generated comments have `guestName: 'Blinko AI'`. They are excluded by default;
  matching account name/nickname is excluded too. Ordinary comments are appended
  with Markdown separators, and optional type labels are `#blinko/blinko`,
  `#blinko/note`, `#blinko/todo`.

The plugin starts off. The destination is the owner's saved `url` setting;
`net.out:<configured>` resolves to exactly that host for guarded fetching.
Tokens come only from `SPUNTO_PLUGIN_BLINKO_IMPORT_TOKEN` or its `_FILE` variant.
Commands never take a token or network destination from an agent. LAN destinations
require both that configured host and the corresponding resolved permission.

Dry run counts notes by type and state, attachment count and declared byte volume,
and returns up to twenty examples of inline tags that the core parser does not
recognize. It does not download attachment bodies or create notes. Import uses
`blinko:<id>`, original times (including the UUIDv7 creation timestamp), pin,
archive and optionally trash state. Attachment bytes go through content-addressed
core storage and links become `/a/<id>`. The M2 guarded-fetch and attachment
write limit is 5 MB per attachment. Dry run counts oversized attachments; import
counts their notes as failed, creates no partial note or import mapping, and keeps
them retryable. HTTP Range downloads are not implemented in M2.

A repeat import updates only untouched notes created by this plugin. Owner-edited
notes are counted as skipped, including when the remote copy has not changed.
Failed attachment requests leave the note unwritten. Successfully downloaded
attachments may remain unlinked if a later download fails; the core's normal
orphan cleanup handles these. Pagination is bounded and duplicate IDs cause a
safe error because they indicate the source moved during the scan. A live library
should be quiescent for a consistent spot check; the REST API has no snapshot
cursor.

The CLI holds the same exclusive data lock as the server, so stop the server for
local CLI imports. A running server accepts owner-authenticated queued dry-run and
import routes under `/api/v1/plugins/blinko-import/`; dry run comes first and the
report is retrieved from the plugin's `report` route. Access tokens cannot reach
these routes. The UI itself is M3.
