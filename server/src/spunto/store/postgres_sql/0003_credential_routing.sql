-- Internal plugin IDs are deterministic per workspace and are never bearer tokens.
DELETE FROM public.spunto_credential_locator l USING spunto.credential c
 WHERE c.id=l.id AND c.workspace_id=l.workspace_id AND c.kind='plugin';
CREATE OR REPLACE FUNCTION spunto.route_credential() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP IN ('DELETE','UPDATE') THEN
  DELETE FROM public.spunto_credential_locator WHERE id=OLD.id AND workspace_id=OLD.workspace_id;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 END IF;
 IF NEW.kind='plugin' THEN RETURN NEW; END IF;
 INSERT INTO public.spunto_credential_locator(id,workspace_id,user_id)
 VALUES(NEW.id,NEW.workspace_id,current_setting('spunto.user_id'));
 RETURN NEW;
END;
$$;
CREATE TRIGGER credential_locator_update AFTER UPDATE OF id,kind ON spunto.credential
 FOR EACH ROW EXECUTE FUNCTION spunto.route_credential();
