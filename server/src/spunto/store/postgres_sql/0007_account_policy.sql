-- Generic operator-supplied account limits; no commercial plans or provider IDs.
ALTER TABLE public.spunto_account ADD COLUMN closed_at BIGINT;
CREATE TABLE public.spunto_account_policy (
 user_id TEXT PRIMARY KEY REFERENCES public.spunto_account(id) ON DELETE CASCADE,
 limits JSONB NOT NULL DEFAULT '{}', updated_at BIGINT NOT NULL
);
CREATE TABLE public.spunto_account_storage (
 user_id TEXT PRIMARY KEY REFERENCES public.spunto_account(id) ON DELETE CASCADE,
 bytes BIGINT NOT NULL DEFAULT 0 CHECK (bytes>=0)
);
CREATE TABLE public.spunto_account_counter (
 user_id TEXT NOT NULL REFERENCES public.spunto_account(id) ON DELETE CASCADE,
 period TEXT NOT NULL, action TEXT NOT NULL, used BIGINT NOT NULL CHECK(used>=0),
 PRIMARY KEY(user_id,period,action)
);
CREATE TABLE public.spunto_account_closure (
 id TEXT PRIMARY KEY, user_id TEXT UNIQUE NOT NULL, shared_choice BOOLEAN NOT NULL,
 created_at BIGINT NOT NULL, state TEXT NOT NULL DEFAULT 'prepared',
 identity_digest TEXT, workspaces JSONB NOT NULL DEFAULT '[]'
);
REVOKE ALL ON public.spunto_account_policy,public.spunto_account_storage,
 public.spunto_account_counter,public.spunto_account_closure FROM PUBLIC;

INSERT INTO public.spunto_account_storage(user_id,bytes)
 SELECT w.created_by,sum(n.bytes) FROM public.spunto_workspace w JOIN (
 SELECT workspace_id,octet_length(body)::BIGINT AS bytes FROM spunto.note UNION ALL
 SELECT workspace_id,octet_length(body)::BIGINT FROM spunto.note_revision UNION ALL
 SELECT workspace_id,size FROM spunto.attachment) n ON n.workspace_id=w.id GROUP BY w.created_by;

CREATE FUNCTION spunto.account_storage_limit() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE wid TEXT; uid TEXT; amount BIGINT; before_bytes BIGINT:=0; after_bytes BIGINT:=0;
 cap BIGINT; used_bytes BIGINT; account_closed BIGINT;
BEGIN
 IF TG_OP<>'INSERT' THEN
  wid:=OLD.workspace_id;
  before_bytes:=CASE WHEN TG_TABLE_NAME='attachment' THEN (to_jsonb(OLD)->>'size')::BIGINT
                    ELSE octet_length(to_jsonb(OLD)->>'body') END;
 END IF;
 IF TG_OP<>'DELETE' THEN
  wid:=NEW.workspace_id;
  after_bytes:=CASE WHEN TG_TABLE_NAME='attachment' THEN (to_jsonb(NEW)->>'size')::BIGINT
                   ELSE octet_length(to_jsonb(NEW)->>'body') END;
 END IF;
 amount:=after_bytes-before_bytes;
 SELECT created_by INTO uid FROM public.spunto_workspace WHERE id=wid;
 IF uid IS NULL THEN RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END; END IF;
 SELECT closed_at INTO account_closed FROM public.spunto_account WHERE id=uid FOR UPDATE;
 SELECT (limits->>'storage_bytes')::BIGINT INTO cap FROM public.spunto_account_policy WHERE user_id=uid;
 IF TG_OP<>'DELETE' AND TG_TABLE_NAME='attachment' THEN
  IF after_bytes>(SELECT (limits->>'file_bytes')::BIGINT FROM public.spunto_account_policy WHERE user_id=uid)
  THEN RAISE EXCEPTION 'account_file_limit' USING ERRCODE='P0001'; END IF;
 END IF;
 IF TG_OP<>'DELETE' AND account_closed IS NOT NULL THEN RAISE EXCEPTION 'account_closed' USING ERRCODE='P0001'; END IF;
 INSERT INTO public.spunto_account_storage(user_id,bytes) VALUES(uid,greatest(0,amount))
 ON CONFLICT(user_id) DO UPDATE SET bytes=greatest(0,spunto_account_storage.bytes+amount)
 RETURNING bytes INTO used_bytes;
 IF amount>0 AND cap IS NOT NULL AND used_bytes>cap THEN
  RAISE EXCEPTION 'account_storage_limit' USING ERRCODE='P0001';
 END IF;
 RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
REVOKE ALL ON FUNCTION spunto.account_storage_limit() FROM PUBLIC;
CREATE TRIGGER account_storage AFTER INSERT OR UPDATE OR DELETE ON spunto.note
 FOR EACH ROW EXECUTE FUNCTION spunto.account_storage_limit();
CREATE TRIGGER account_storage AFTER INSERT OR UPDATE OR DELETE ON spunto.note_revision
 FOR EACH ROW EXECUTE FUNCTION spunto.account_storage_limit();
CREATE TRIGGER account_storage AFTER INSERT OR UPDATE OR DELETE ON spunto.attachment
 FOR EACH ROW EXECUTE FUNCTION spunto.account_storage_limit();

CREATE FUNCTION spunto.account_membership_limit() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE uid TEXT; cap BIGINT;
BEGIN
 SELECT created_by INTO uid FROM public.spunto_workspace WHERE id=NEW.workspace_id;
 PERFORM id FROM public.spunto_account WHERE id=uid FOR UPDATE;
 IF NEW.role='owner' THEN
  SELECT (limits->>'owned_shared_spaces')::BIGINT INTO cap FROM public.spunto_account_policy WHERE user_id=uid;
  IF cap IS NOT NULL AND (SELECT count(*) FROM public.spunto_workspace WHERE created_by=uid AND NOT personal)>cap
  THEN RAISE EXCEPTION 'account_space_limit' USING ERRCODE='P0001'; END IF;
 ELSE
  SELECT (limits->>'members_per_shared_space')::BIGINT INTO cap FROM public.spunto_account_policy WHERE user_id=uid;
  IF cap IS NOT NULL AND (SELECT count(*) FROM public.spunto_membership WHERE workspace_id=NEW.workspace_id)>cap
  THEN RAISE EXCEPTION 'account_member_limit' USING ERRCODE='P0001'; END IF;
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION spunto.account_membership_limit() FROM PUBLIC;
CREATE TRIGGER account_membership AFTER INSERT ON public.spunto_membership
 FOR EACH ROW EXECUTE FUNCTION spunto.account_membership_limit();
