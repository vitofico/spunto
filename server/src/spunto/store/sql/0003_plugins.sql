-- M2 plugin host: fixed core-owned storage, output and persistent jobs.
CREATE TABLE plugin (
  id            TEXT PRIMARY KEY,
  version       TEXT NOT NULL,
  kind          TEXT NOT NULL,              -- server | device | theme
  switch        TEXT NOT NULL,              -- on | off
  state         TEXT NOT NULL,              -- available | waiting | enabled | disabled | failed | removed (§2.12)
  pinned_by_env INTEGER NOT NULL DEFAULT 0, -- SPUNTO_PLUGINS locks the switch
  granted       TEXT NOT NULL DEFAULT '[]', -- JSON: the permissions the owner accepted
  settings      TEXT NOT NULL DEFAULT '{}', -- JSON, never secrets
  ai_route      TEXT,                       -- JSON: capability -> profile, where it differs from the default binding (§7.1)
  position      INTEGER NOT NULL,           -- the owner's order: panel sections, meta-token priority
  data_version  INTEGER NOT NULL DEFAULT 0,
  last_run_at   INTEGER,
  last_error    TEXT                        -- a code and a short reason, never note text
);

CREATE TABLE plugin_kv (
  plugin_id TEXT NOT NULL,
  key       TEXT NOT NULL,
  value     TEXT NOT NULL,                  -- JSON, at most 64 KB
  PRIMARY KEY (plugin_id, key)
);

CREATE TABLE plugin_record (
  plugin_id  TEXT NOT NULL,
  collection TEXT NOT NULL,
  id         TEXT NOT NULL,
  note_id    TEXT REFERENCES note(id) ON DELETE CASCADE,  -- optional; per-note lookups and purges stay cheap
  key        TEXT,                                        -- optional, for the plugin's own lookups
  data       TEXT NOT NULL,                               -- JSON, at most 64 KB
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (plugin_id, collection, id)
);
CREATE INDEX plugin_record_note ON plugin_record (plugin_id, note_id) WHERE note_id IS NOT NULL;
CREATE INDEX plugin_record_key  ON plugin_record (plugin_id, collection, key) WHERE key IS NOT NULL;

-- Proposals from every plugin (§2.8). Accepting one is the owner's edit, made under the deciding credential.
CREATE TABLE suggestion (
  id           TEXT PRIMARY KEY,
  plugin_id    TEXT NOT NULL,
  note_id      TEXT REFERENCES note(id) ON DELETE CASCADE,  -- NULL for label-level proposals in Review
  note_version INTEGER,                     -- the version it was computed from
  action       TEXT NOT NULL,               -- add_label | set_title | append_text | replace_body | merge_notes | label_change | open
  payload      TEXT NOT NULL,               -- JSON checked against the action's schema, e.g. {"path": "sync", "is_new": false}
  confidence   REAL,
  reason       TEXT,                        -- one line, shown on long-press or hover
  status       TEXT NOT NULL,               -- pending | accepted | dismissed | superseded | expired
  model        TEXT,                        -- the model behind it, if any
  change_seq   INTEGER NOT NULL,            -- suggestions sync to Android
  created_at   INTEGER NOT NULL,
  decided_at   INTEGER,
  decided_by   TEXT
);
CREATE INDEX suggestion_pending ON suggestion (note_id, plugin_id) WHERE status = 'pending';
CREATE INDEX suggestion_change  ON suggestion (change_seq);

-- Per-note plugin output that clients show offline: panel sections, meta tokens, the daily slip, view membership.
CREATE TABLE annotation (
  plugin_id  TEXT NOT NULL,
  note_id    TEXT NOT NULL REFERENCES note(id) ON DELETE CASCADE,
  kind       TEXT NOT NULL,                 -- panel | meta | slip | member:<view>
  payload    TEXT NOT NULL,                 -- vocabulary JSON (§2.8), at most 4 KB
  change_seq INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (plugin_id, note_id, kind)
);
CREATE INDEX annotation_change ON annotation (change_seq);

-- Searchable text a plugin attaches to a note: an image's text, a transcript, a page title.
-- Feeds note_fts.extra and the embedder; never shown as the note's body.
CREATE TABLE note_text (
  plugin_id  TEXT NOT NULL,
  note_id    TEXT NOT NULL REFERENCES note(id) ON DELETE CASCADE,
  text       TEXT NOT NULL,                 -- at most 20,000 characters
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (plugin_id, note_id)
);

CREATE TABLE job (
  id          INTEGER PRIMARY KEY,
  plugin_id   TEXT NOT NULL,                -- 'core' for the core's own jobs
  kind        TEXT NOT NULL,                -- core: embed | reindex | backup | purge | prune; plugins: hook:<event> | job:<name> | schedule:<id> | migrate
  note_id     TEXT,
  args        TEXT,                         -- JSON
  priority    INTEGER NOT NULL,             -- 0 interactive, 5 backfill
  status      TEXT NOT NULL,                -- queued | running | failed | dead (done jobs are deleted)
  attempts    INTEGER NOT NULL DEFAULT 0,
  run_after   INTEGER NOT NULL,
  lease_until INTEGER,
  last_error  TEXT,                         -- error code and a short reason, never note text
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
-- Repeated saves coalesce into one queued job per plugin, kind and note (ifnull: SQLite treats NULLs as distinct).
CREATE UNIQUE INDEX job_once ON job (plugin_id, kind, ifnull(note_id, '')) WHERE status = 'queued';

CREATE TABLE setting (key TEXT PRIMARY KEY, value TEXT NOT NULL);
