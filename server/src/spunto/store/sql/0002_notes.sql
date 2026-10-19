INSERT INTO meta (key, value) VALUES ('change_seq', '0'), ('plugins_rev', '0');

CREATE TABLE note (
  id TEXT PRIMARY KEY,
  body TEXT NOT NULL,
  title TEXT NOT NULL,
  body_hash TEXT NOT NULL,
  version INTEGER NOT NULL,
  change_seq INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  pinned INTEGER NOT NULL DEFAULT 0,
  archived_at INTEGER,
  trashed_at INTEGER,
  private INTEGER NOT NULL DEFAULT 0,
  pinned_clock INTEGER NOT NULL DEFAULT 0,
  archived_clock INTEGER NOT NULL DEFAULT 0,
  trashed_clock INTEGER NOT NULL DEFAULT 0,
  private_clock INTEGER NOT NULL DEFAULT 0,
  open_tasks INTEGER NOT NULL DEFAULT 0,
  source_kind TEXT NOT NULL,
  source_ref TEXT,
  source_url TEXT,
  origin TEXT NOT NULL,
  machine INTEGER NOT NULL DEFAULT 0,
  created_by TEXT NOT NULL,
  updated_by TEXT NOT NULL,
  conflict_of TEXT REFERENCES note(id) ON DELETE SET NULL,
  import_key TEXT UNIQUE
);
CREATE INDEX note_timeline ON note (trashed_at, archived_at, pinned DESC, created_at DESC, id DESC);
CREATE INDEX note_change ON note (change_seq);
CREATE TABLE tombstone (
  kind TEXT NOT NULL, id TEXT NOT NULL, change_seq INTEGER NOT NULL, deleted_at INTEGER NOT NULL,
  PRIMARY KEY (kind, id)
);
CREATE TABLE note_revision (
  note_id TEXT NOT NULL REFERENCES note(id) ON DELETE CASCADE,
  version INTEGER NOT NULL,
  body TEXT NOT NULL,
  change_kind TEXT NOT NULL,
  changed_by TEXT NOT NULL,
  changed_at INTEGER NOT NULL,
  PRIMARY KEY (note_id, version)
);
CREATE TABLE label (
  id INTEGER PRIMARY KEY, key TEXT NOT NULL UNIQUE, path TEXT NOT NULL, created_at INTEGER NOT NULL
);
CREATE TABLE note_label (
  note_id TEXT NOT NULL REFERENCES note(id) ON DELETE CASCADE,
  label_id INTEGER NOT NULL REFERENCES label(id) ON DELETE CASCADE,
  PRIMARY KEY (note_id, label_id)
);
CREATE INDEX note_label_by_label ON note_label (label_id);
CREATE VIRTUAL TABLE note_fts USING fts5(
  title, body, extra, tokenize='unicode61 remove_diacritics 2'
);
CREATE TABLE attachment (
  id TEXT PRIMARY KEY, sha256 TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL,
  filename TEXT, width INTEGER, height INTEGER, created_at INTEGER NOT NULL
);
CREATE TABLE note_attachment (
  note_id TEXT NOT NULL REFERENCES note(id) ON DELETE CASCADE,
  attachment_id TEXT NOT NULL REFERENCES attachment(id) ON DELETE CASCADE,
  PRIMARY KEY (note_id, attachment_id)
);
CREATE TABLE applied_op (
  credential_id TEXT NOT NULL, op_id TEXT NOT NULL, result TEXT NOT NULL, applied_at INTEGER NOT NULL,
  PRIMARY KEY (credential_id, op_id)
);
