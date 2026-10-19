-- The M0 schema: instance facts, the owner and credentials (02 §4.2).
-- Notes, labels and the plugin host's tables arrive in their own migrations.

-- Instance facts: instance_id, sync_epoch, and later change_seq, plugins_rev and the rest.
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);

-- sync_epoch changes on every restore, so devices know to send back what the server lost (02 §6.8).
INSERT INTO meta (key, value) VALUES
  ('instance_id', lower(hex(randomblob(16)))),
  ('sync_epoch', lower(hex(randomblob(4))));

-- Exactly one owner (D8).
CREATE TABLE owner (
  id              INTEGER PRIMARY KEY CHECK (id = 1),  -- one owner, so no username: login is the password alone
  password_hash   TEXT NOT NULL,            -- argon2id
  password_source TEXT NOT NULL,            -- 'env' | 'setup' | 'ui'
  created_at      INTEGER NOT NULL
);

-- Every way in: web sessions, device tokens, personal access tokens, and one internal credential per plugin.
CREATE TABLE credential (
  id           TEXT PRIMARY KEY,            -- short public id, embedded in the token
  kind         TEXT NOT NULL,               -- session | device | pat | plugin
  name         TEXT NOT NULL,               -- 'Pixel 8', 'Claude Code on the laptop', 'Weekly digest'
  client       TEXT,                        -- pat: claude-code | codex | script | monitor; device: android; plugin: its id
  scope        TEXT NOT NULL,               -- read | write | status (pat); full (session, device); plugin (its permissions)
  external_ok  INTEGER NOT NULL DEFAULT 1,  -- pat: sees notes from outside senders (D32)
  private_ok   INTEGER NOT NULL DEFAULT 0,  -- pat: sees private notes (D19)
  secret_hash  TEXT,                        -- sha256 of the random secret; NULL for plugin credentials, which are never tokens
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER,
  last_used_at INTEGER,                     -- updated at most once a minute
  revoked_at   INTEGER
);
