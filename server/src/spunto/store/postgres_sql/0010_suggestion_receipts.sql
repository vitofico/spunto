ALTER TABLE spunto.suggestion ADD COLUMN applied_version BIGINT;
ALTER TABLE spunto.suggestion ADD COLUMN receipt_version BIGINT;
ALTER TABLE spunto.suggestion ADD COLUMN receipt_hidden BIGINT NOT NULL DEFAULT 0;
ALTER TABLE spunto.suggestion ADD COLUMN undone_at BIGINT;
CREATE INDEX suggestion_receipt ON spunto.suggestion(workspace_id,note_id,applied_version)
WHERE receipt_version IS NOT NULL;

UPDATE spunto.plugin
SET settings=((settings::jsonb - 'auto_accept_existing') ||
    jsonb_build_object('change_mode',
      CASE WHEN settings::jsonb->'auto_accept_existing' = 'true'::jsonb
           THEN 'automatic' ELSE 'ask_first' END))::text
WHERE id='label-suggestions' AND NOT settings::jsonb ? 'change_mode';

CREATE FUNCTION spunto.owner_authored_credential(creator TEXT) RETURNS BOOLEAN
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT EXISTS(
   SELECT 1 FROM public.spunto_credential_locator c
   JOIN public.spunto_membership m
     ON m.workspace_id=c.workspace_id AND m.user_id=c.user_id AND m.role='owner'
   JOIN public.spunto_account a ON a.id=m.user_id AND a.closed_at IS NULL
   WHERE c.id=creator AND c.workspace_id=nullif(current_setting('spunto.workspace_id',true),'')
     AND c.user_id=nullif(current_setting('spunto.user_id',true),'')
     AND current_setting('spunto.role',true)='owner'
 );
$$;
REVOKE ALL ON FUNCTION spunto.owner_authored_credential(TEXT) FROM PUBLIC;

CREATE FUNCTION spunto.credential_belongs_to_actor(credential TEXT) RETURNS BOOLEAN
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT EXISTS(
   SELECT 1 FROM public.spunto_credential_locator c
   WHERE c.id=credential AND c.workspace_id=nullif(current_setting('spunto.workspace_id',true),'')
     AND c.user_id=nullif(current_setting('spunto.user_id',true),'')
 );
$$;
REVOKE ALL ON FUNCTION spunto.credential_belongs_to_actor(TEXT) FROM PUBLIC;
