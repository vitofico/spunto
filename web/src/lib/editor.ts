import { useEffect, useMemo, useRef, useSyncExternalStore } from "react";
import type { InfiniteData } from "@tanstack/react-query";
import { api } from "./api";
import {
  DraftController,
  draftStorageKey,
  type DraftStorage,
  type PendingSave,
} from "./drafts";
import {
  currentOwner,
  currentSession,
  useWorkspaceRole,
  invalidateContent,
  queryClient,
  subscribeOwner,
  useOwner,
} from "./query";
import { caseFold, labelTokens, toggleCheckbox } from "./text";
import type { Note, NoteFilters, NotePage } from "./types";
const unavailableStorage: DraftStorage = {
  getItem: () => null,
  setItem: () => {
    throw new Error("storage unavailable");
  },
  removeItem: () => {
    throw new Error("storage unavailable");
  },
};
/** Shows a capture at the top of every loaded timeline it belongs to until the server answers. */
function showCapture(capture: PendingSave, queued: boolean) {
  const id = "queued:" + capture.key;
  const tokens = labelTokens(capture.body);
  const now = new Date().toISOString();
  const note = {
    id,
    body: capture.body,
    title: capture.body
      .trim()
      .split("\n")[0]!
      .replace(/^#+\s*/, ""),
    html: null,
    created_at: now,
    updated_at: now,
    version: 0,
    labels: tokens.map((token) => token.path),
    pinned: false,
    archived: false,
    trashed: false,
    private: false,
    machine: false,
    origin: "owner",
    source: { kind: "web" },
    conflict_of: null,
    open_tasks: 0,
    change_seq: 0,
    updated_via_agent: false,
    suggestions: [],
    annotations: [],
  } as unknown as Note;
  for (const [key, data] of queryClient.getQueriesData<InfiniteData<NotePage>>({
    queryKey: ["notes"],
  })) {
    const filters = (key[1] ?? {}) as NoteFilters;
    const first = data?.pages[0];
    if (
      !first ||
      (filters.view ?? "timeline") !== "timeline" ||
      (filters.label &&
        !tokens.some((token) => token.key === caseFold(filters.label!)))
    )
      continue;
    // Already as wanted: no write, so re-showing after every refetch cannot loop.
    if (first.notes.some((item) => item.id === id) === queued) continue;
    const rest = first.notes.filter((item) => item.id !== id);
    queryClient.setQueryData<InfiniteData<NotePage>>(key, {
      ...data,
      pages: [
        { ...first, notes: queued ? [note, ...rest] : rest },
        ...data.pages.slice(1),
      ],
    });
  }
}
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
        showCapture,
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
    // ⌘S saves an open note in place; the composer only saves from its own field.
    const keyboard = (event: KeyboardEvent) => {
      if (
        note &&
        (event.ctrlKey || event.metaKey) &&
        event.key.toLowerCase() === "s"
      ) {
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
    // Captures waiting to sync stay on the timeline across reloads and refetches until each is saved.
    const reshow = () => {
      for (const capture of controller.waiting()) showCapture(capture, true);
    };
    const refetched = note
      ? undefined
      : queryClient.getQueryCache().subscribe((event) => {
          if (
            event.type === "updated" &&
            event.action.type === "success" &&
            event.query.queryKey[0] === "notes"
          )
            reshow();
        });
    if (!note) reshow();
    window.addEventListener("pagehide", leave);
    window.addEventListener("keydown", keyboard);
    window.addEventListener("online", online);
    document.addEventListener("visibilitychange", visibility);
    return () => {
      leave();
      controller.deactivate();
      unsubscribe();
      refetched?.();
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
  return {
    ...snapshot,
    setBody: controller.setBody,
    save: controller.save,
    resumeReviewed: controller.resumeReviewed,
    discard: controller.discard,
    uploadAttachment: api.uploadAttachment,
    toggleCheckbox: (index: number) =>
      controller.setBody(toggleCheckbox(controller.getSnapshot().body, index)),
  };
}
