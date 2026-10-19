-- One shared vector table; model fingerprints never create physical tenant tables.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA spunto;
ALTER TABLE spunto.chunk ADD CONSTRAINT chunk_model_identity UNIQUE (workspace_id,id,model_key);
CREATE TABLE spunto.chunk_vector (
 workspace_id TEXT NOT NULL DEFAULT nullif(current_setting('spunto.workspace_id',true),''),
 model_key TEXT NOT NULL, chunk_id BIGINT NOT NULL,
 embedding spunto.vector NOT NULL CHECK (spunto.vector_dims(embedding) BETWEEN 1 AND 8192),
 PRIMARY KEY (workspace_id,model_key,chunk_id),
 FOREIGN KEY (workspace_id,chunk_id,model_key)
   REFERENCES spunto.chunk(workspace_id,id,model_key) ON DELETE CASCADE
);
ALTER TABLE spunto.chunk_vector ENABLE ROW LEVEL SECURITY;
ALTER TABLE spunto.chunk_vector FORCE ROW LEVEL SECURITY;
CREATE POLICY workspace_read ON spunto.chunk_vector FOR SELECT USING (
 workspace_id=nullif(current_setting('spunto.workspace_id',true),'')
 AND nullif(current_setting('spunto.user_id',true),'') IS NOT NULL
);
CREATE POLICY workspace_insert ON spunto.chunk_vector FOR INSERT WITH CHECK (
 workspace_id=nullif(current_setting('spunto.workspace_id',true),'')
 AND nullif(current_setting('spunto.user_id',true),'') IS NOT NULL
 AND current_setting('spunto.role',true) IN ('owner','editor')
);
CREATE POLICY workspace_update ON spunto.chunk_vector FOR UPDATE USING (
 workspace_id=nullif(current_setting('spunto.workspace_id',true),'')
 AND nullif(current_setting('spunto.user_id',true),'') IS NOT NULL
 AND current_setting('spunto.role',true) IN ('owner','editor')
) WITH CHECK (
 workspace_id=nullif(current_setting('spunto.workspace_id',true),'')
 AND nullif(current_setting('spunto.user_id',true),'') IS NOT NULL
 AND current_setting('spunto.role',true) IN ('owner','editor')
);
CREATE POLICY workspace_delete ON spunto.chunk_vector FOR DELETE USING (
 workspace_id=nullif(current_setting('spunto.workspace_id',true),'')
 AND nullif(current_setting('spunto.user_id',true),'') IS NOT NULL
 AND current_setting('spunto.role',true) IN ('owner','editor')
);
