-- A claim owns its lease independently of SQLite's reusable integer row ID.
ALTER TABLE job ADD COLUMN run_receipt TEXT;
