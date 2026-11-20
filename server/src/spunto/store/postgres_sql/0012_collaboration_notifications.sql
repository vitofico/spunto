ALTER TABLE public.spunto_membership ADD COLUMN following BOOLEAN NOT NULL DEFAULT FALSE;

-- A notification must reference the actual grant that authorizes it. Revoking and
-- regranting access therefore cannot resurrect an earlier notification.
CREATE TABLE public.spunto_notification (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 kind TEXT NOT NULL CHECK (kind IN ('space_joined','note_shared','note_created')),
 workspace_id TEXT NOT NULL,
 note_id TEXT,
 member_id TEXT,
 grantee_id TEXT,
 recipient_id TEXT GENERATED ALWAYS AS (coalesce(member_id,grantee_id)) STORED,
 created_at BIGINT NOT NULL DEFAULT (extract(epoch FROM clock_timestamp())*1000)::BIGINT,
 read BOOLEAN NOT NULL DEFAULT FALSE,
 CHECK ((kind='note_shared') = (grantee_id IS NOT NULL)),
 CHECK ((kind<>'note_shared') = (member_id IS NOT NULL)),
 CHECK ((kind<>'space_joined') = (note_id IS NOT NULL)),
 FOREIGN KEY (workspace_id,member_id) REFERENCES public.spunto_membership ON DELETE CASCADE,
 FOREIGN KEY (workspace_id,note_id,grantee_id) REFERENCES public.spunto_note_share ON DELETE CASCADE,
 FOREIGN KEY (workspace_id,note_id) REFERENCES spunto.note ON DELETE CASCADE
);
CREATE INDEX spunto_notification_inbox ON public.spunto_notification(recipient_id,created_at DESC,id DESC);
CREATE INDEX spunto_notification_note ON public.spunto_notification(workspace_id,note_id);
REVOKE ALL ON public.spunto_notification FROM PUBLIC;

CREATE FUNCTION spunto.notify_collaboration() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 IF TG_TABLE_NAME='spunto_membership' THEN
  INSERT INTO public.spunto_notification(kind,workspace_id,member_id)
  VALUES('space_joined',NEW.workspace_id,NEW.user_id);
 ELSIF TG_TABLE_NAME='spunto_note_share' THEN
  INSERT INTO public.spunto_notification(kind,workspace_id,note_id,grantee_id)
  VALUES('note_shared',NEW.workspace_id,NEW.note_id,NEW.grantee_id);
 ELSE
  INSERT INTO public.spunto_notification(kind,workspace_id,note_id,member_id)
  SELECT 'note_created',NEW.workspace_id,NEW.id,m.user_id FROM public.spunto_membership m
  WHERE m.workspace_id=NEW.workspace_id AND m.following
  AND m.user_id<>nullif(current_setting('spunto.user_id',true),'') FOR SHARE OF m;
 END IF;
 RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION spunto.notify_collaboration() FROM PUBLIC;
CREATE TRIGGER notify_new_member AFTER INSERT ON public.spunto_membership
 FOR EACH ROW WHEN (NEW.role<>'owner') EXECUTE FUNCTION spunto.notify_collaboration();
-- Conflict-copy grants created inside a scoped note transaction are automatic.
CREATE TRIGGER notify_new_share AFTER INSERT ON public.spunto_note_share
 FOR EACH ROW WHEN (nullif(current_setting('spunto.user_id',true),'') IS NULL)
 EXECUTE FUNCTION spunto.notify_collaboration();
CREATE TRIGGER notify_new_note AFTER INSERT ON spunto.note
 FOR EACH ROW WHEN (current_setting('spunto.restoring',true) IS DISTINCT FROM 'on'
 AND NEW.private=0 AND NEW.machine=0 AND NEW.trashed_at IS NULL
 AND NEW.conflict_of IS NULL AND NEW.source_kind NOT IN ('import','plugin','agent'))
 EXECUTE FUNCTION spunto.notify_collaboration();

CREATE FUNCTION spunto.withdraw_note_notifications() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
 DELETE FROM public.spunto_notification WHERE workspace_id=NEW.workspace_id AND note_id=NEW.id
 AND (kind='note_created' OR NEW.trashed_at IS NOT NULL);
 RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION spunto.withdraw_note_notifications() FROM PUBLIC;
CREATE TRIGGER withdraw_hidden_note AFTER UPDATE OF private,trashed_at ON spunto.note
 FOR EACH ROW WHEN ((NEW.private<>0 AND OLD.private=0)
 OR (NEW.trashed_at IS NOT NULL AND OLD.trashed_at IS NULL))
 EXECUTE FUNCTION spunto.withdraw_note_notifications();
