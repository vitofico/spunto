-- Pairing secrets are short-lived hashes, bound to the issuing browser session.
CREATE TABLE pairing (
  code_hash TEXT PRIMARY KEY,
  credential_id TEXT NOT NULL REFERENCES credential(id),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX pairing_expiry ON pairing(expires_at);
ALTER TABLE credential ADD COLUMN last_sync_at INTEGER;
CREATE INDEX tombstone_change ON tombstone(change_seq);
