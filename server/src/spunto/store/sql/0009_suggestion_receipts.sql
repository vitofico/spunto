ALTER TABLE suggestion ADD COLUMN applied_version INTEGER;
ALTER TABLE suggestion ADD COLUMN receipt_version INTEGER;
ALTER TABLE suggestion ADD COLUMN receipt_hidden INTEGER NOT NULL DEFAULT 0;
ALTER TABLE suggestion ADD COLUMN undone_at INTEGER;
CREATE INDEX suggestion_receipt ON suggestion(note_id,applied_version)
WHERE receipt_version IS NOT NULL;

UPDATE plugin
SET settings=json_set(
  json_remove(settings,'$.auto_accept_existing'),
  '$.change_mode',
  CASE WHEN json_type(settings,'$.auto_accept_existing')='true'
       THEN 'automatic' ELSE 'ask_first' END
)
WHERE id='label-suggestions' AND json_type(settings,'$.change_mode') IS NULL;
