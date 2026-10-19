-- Retained safe catalog identity, independent of executable plugin code or settings.
ALTER TABLE plugin ADD COLUMN manifest_snapshot TEXT NOT NULL DEFAULT '{}';
ALTER TABLE plugin ADD COLUMN origin TEXT NOT NULL DEFAULT 'unknown';
ALTER TABLE plugin ADD COLUMN source_kind TEXT NOT NULL DEFAULT 'unknown';
ALTER TABLE plugin ADD COLUMN source_identity TEXT;
