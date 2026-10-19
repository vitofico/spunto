-- Narrow, transactional bridges between access metadata and scoped note writes.
CREATE FUNCTION spunto.lock_shared_note(target_note TEXT) RETURNS TEXT
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE access_role TEXT;
BEGIN
 SELECT role INTO access_role FROM public.spunto_note_share
 WHERE workspace_id=nullif(current_setting('spunto.workspace_id',true),'')
 AND note_id=target_note AND grantee_id=nullif(current_setting('spunto.user_id',true),'')
 FOR SHARE;
 RETURN access_role;
END;
$$;
CREATE FUNCTION spunto.grant_conflict_copy(original_note TEXT, conflict_note TEXT) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE original_grant public.spunto_note_share%ROWTYPE;
DECLARE space TEXT := nullif(current_setting('spunto.workspace_id',true),'');
DECLARE account TEXT := nullif(current_setting('spunto.user_id',true),'');
BEGIN
 SELECT * INTO original_grant FROM public.spunto_note_share
 WHERE workspace_id=space AND note_id=original_note AND grantee_id=account AND role='editor'
 FOR SHARE;
 IF NOT FOUND OR NOT EXISTS (
   SELECT 1 FROM spunto.note WHERE workspace_id=space AND id=conflict_note
   AND conflict_of=original_note AND created_by='share:'||account
 ) THEN RAISE EXCEPTION 'shared conflict is unavailable'; END IF;
 INSERT INTO public.spunto_note_share(workspace_id,note_id,grantee_id,role,created_by,created_at)
 VALUES(space,conflict_note,account,'editor',original_grant.created_by,
        (extract(epoch FROM clock_timestamp())*1000)::BIGINT)
 ON CONFLICT DO NOTHING;
END;
$$;
REVOKE ALL ON FUNCTION spunto.lock_shared_note(TEXT),
 spunto.grant_conflict_copy(TEXT,TEXT) FROM PUBLIC;

-- The public opaque session is authoritative across all of its workspace handles.
CREATE FUNCTION spunto.revoke_browser_session() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.kind='session' AND NEW.revoked_at IS NOT NULL AND OLD.revoked_at IS NULL THEN
   UPDATE public.spunto_login_session SET revoked_at=NEW.revoked_at
   WHERE token_hash=NEW.secret_hash AND revoked_at IS NULL;
   DELETE FROM public.spunto_pairing p USING spunto.credential c
   WHERE p.credential_id=c.id AND c.kind='session' AND c.secret_hash=NEW.secret_hash;
 END IF;
 RETURN NEW;
END;
$$;
CREATE FUNCTION spunto.revoke_workspace_sessions() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.revoked_at IS NOT NULL AND OLD.revoked_at IS NULL THEN
   UPDATE spunto.credential SET revoked_at=NEW.revoked_at
   WHERE kind='session' AND secret_hash=NEW.token_hash AND revoked_at IS NULL;
   DELETE FROM public.spunto_pairing p USING spunto.credential c
   WHERE p.credential_id=c.id AND c.kind='session' AND c.secret_hash=NEW.token_hash;
 END IF;
 RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION spunto.revoke_browser_session(),
 spunto.revoke_workspace_sessions() FROM PUBLIC;
CREATE TRIGGER revoke_browser_session AFTER UPDATE OF revoked_at ON spunto.credential
 FOR EACH ROW EXECUTE FUNCTION spunto.revoke_browser_session();
CREATE TRIGGER revoke_workspace_sessions AFTER UPDATE OF revoked_at ON public.spunto_login_session
 FOR EACH ROW EXECUTE FUNCTION spunto.revoke_workspace_sessions();
