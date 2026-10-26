-- Account closure revokes every routed credential, including credentials in
-- spaces owned by someone else. It never deletes those other spaces or notes.
CREATE FUNCTION spunto.close_account_credentials() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.closed_at IS NOT NULL AND OLD.closed_at IS NULL THEN
  UPDATE spunto.credential c SET revoked_at=NEW.closed_at
  FROM public.spunto_credential_locator l
  WHERE l.user_id=NEW.id AND l.id=c.id AND l.workspace_id=c.workspace_id
   AND c.revoked_at IS NULL;
  UPDATE public.spunto_login_session SET revoked_at=NEW.closed_at
   WHERE user_id=NEW.id AND revoked_at IS NULL;
  DELETE FROM public.spunto_pairing WHERE user_id=NEW.id;
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION spunto.close_account_credentials() FROM PUBLIC;
CREATE TRIGGER account_closure_credentials AFTER UPDATE OF closed_at ON public.spunto_account
 FOR EACH ROW EXECUTE FUNCTION spunto.close_account_credentials();
