import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useSyncExternalStore,
} from "react";
import { api } from "./api";
import { DraftController, draftStorageKey, type DraftStorage } from "./drafts";
import {
  currentOwner,
  currentSession,
  useWorkspaceRole,
  invalidateContent,
  subscribeOwner,
  useOwner,
} from "./query";
import { toggleCheckbox } from "./text";
import type { Note } from "./types";
const unavailableStorage: DraftStorage = {
  getItem: () => null,
  setItem: () => {
    throw new Error("storage unavailable");
  },
  removeItem: () => {
    throw new Error("storage unavailable");
  },
};
function storage(): DraftStorage {
  try {
    return localStorage;
  } catch {
    return unavailableStorage;
  }
}
export function useDraftEditor({
  note,
  draftKey,
  onSaved,
  update = api.updateNote,
  enabled = true,
}: {
  note?: Note;
  draftKey: string;
  onSaved?: (note: Note) => void;
  update?: (id: string, body: string, version: number) => Promise<Note>;
  enabled?: boolean;
}) {
  // Automatic saves require either an existing note or a durable explicit submission.
  const scope = useOwner();
  const role = useWorkspaceRole();
  const owner =
    enabled && (update !== api.updateNote || role !== "viewer") ? scope : null;
  const callback = useRef(onSaved);
  callback.current = onSaved;
  const controller = useMemo(
    () =>
      new DraftController(
        storage(),
        draftStorageKey(owner ?? "locked", draftKey),
        { create: api.createNote, update },
        note,
        undefined,
        (saved) => {
          void invalidateContent();
          callback.current?.(saved);
        },
      ),
    [owner, draftKey, note?.id, update],
  );
  useEffect(() => {
    if (note) controller.syncNote(note);
  }, [controller, note?.version, note?.body]);
  const snapshot = useSyncExternalStore(
    controller.subscribe,
    controller.getSnapshot,
    controller.getSnapshot,
  );
  useEffect(() => {
    if (!owner) {
      controller.deactivate();
      return;
    }
    controller.activate();
    const unsubscribe = subscribeOwner(() => {
      if (
        currentOwner() !== owner ||
        (update === api.updateNote &&
          currentSession()?.workspace_role === "viewer")
      )
        controller.deactivate();
    });
    const interval = setInterval(() => {
      void controller.saveAutomatically();
    }, 60000);
    const leave = () => {
      controller.persist();
      void controller.saveAutomatically();
    };
    const keyboard = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        void controller.save();
      }
    };
    const online = () => {
      void controller.saveAutomatically();
    };
    const visibility = () => {
      if (document.visibilityState === "hidden") leave();
    };
    window.addEventListener("pagehide", leave);
    window.addEventListener("keydown", keyboard);
    window.addEventListener("online", online);
    document.addEventListener("visibilitychange", visibility);
    return () => {
      leave();
      controller.deactivate();
      unsubscribe();
      clearInterval(interval);
      window.removeEventListener("pagehide", leave);
      window.removeEventListener("keydown", keyboard);
      window.removeEventListener("online", online);
      document.removeEventListener("visibilitychange", visibility);
    };
  }, [controller, owner]);
  useEffect(() => {
    if (
      !owner ||
      !controller.canSaveAutomatically() ||
      snapshot.state === "saving"
    )
      return;
    const timeout = setTimeout(
      () => {
        void controller.saveAutomatically();
      },
      snapshot.error ? 15000 : 5000,
    );
    return () => clearTimeout(timeout);
  }, [
    controller,
    owner,
    snapshot.body,
    snapshot.state,
    snapshot.dirty,
    snapshot.conflict,
    snapshot.error,
    snapshot.submitted,
    snapshot.retryBlocked,
  ]);
  const uploadAttachment = useCallback(
    async (file: File) => {
      const attachment = await api.uploadAttachment(file);
      const body = controller.getSnapshot().body;
      controller.setBody(
        body + (body && !body.endsWith("\n") ? "\n" : "") + attachment.snippet,
      );
      return attachment;
    },
    [controller],
  );
  return {
    ...snapshot,
    setBody: controller.setBody,
    save: controller.save,
    resumeReviewed: controller.resumeReviewed,
    uploadAttachment,
    toggleCheckbox: (index: number) =>
      controller.setBody(toggleCheckbox(controller.getSnapshot().body, index)),
  };
}
