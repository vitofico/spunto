-- Core-owned embedding descriptors contain no credentials or note text.
CREATE TABLE embedding_model (
  model_key TEXT PRIMARY KEY,
  profile TEXT NOT NULL,
  descriptor TEXT NOT NULL,
  dimension INTEGER NOT NULL CHECK (dimension > 0),
  created_at INTEGER NOT NULL
);
CREATE TABLE embedding_route (
  profile TEXT PRIMARY KEY,
  active_key TEXT REFERENCES embedding_model(model_key),
  target_key TEXT NOT NULL REFERENCES embedding_model(model_key)
);
CREATE TABLE chunk (
  id INTEGER PRIMARY KEY,
  note_id TEXT NOT NULL REFERENCES note(id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL,
  text_hash TEXT NOT NULL,
  model_key TEXT NOT NULL REFERENCES embedding_model(model_key),
  UNIQUE (note_id, ordinal, model_key)
);
CREATE INDEX chunk_model ON chunk (model_key, note_id);
CREATE TABLE embedding_note (
  note_id TEXT NOT NULL REFERENCES note(id) ON DELETE CASCADE,
  model_key TEXT NOT NULL REFERENCES embedding_model(model_key),
  text_hash TEXT NOT NULL,
  body_hash TEXT NOT NULL,
  PRIMARY KEY (note_id, model_key)
);
CREATE TABLE ai_stat (
  day TEXT NOT NULL,
  plugin_id TEXT NOT NULL,
  capability TEXT NOT NULL,
  outcome TEXT NOT NULL,
  n INTEGER NOT NULL,
  PRIMARY KEY (day, plugin_id, capability, outcome)
);
