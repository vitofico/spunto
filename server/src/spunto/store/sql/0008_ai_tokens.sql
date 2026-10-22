-- Provider-reported tokens per call, so real per-account AI costs can be measured.
ALTER TABLE ai_stat ADD COLUMN input_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_stat ADD COLUMN output_tokens INTEGER NOT NULL DEFAULT 0;
