import { act, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { api } from "../src/lib/api";
import {
  currentSession,
  queryClient,
  useAuth,
  useLiveChanges,
} from "../src/lib/query";
import { useDraftEditor } from "../src/lib/editor";
import { draftStorageKey } from "../src/lib/drafts";
import type { Note, Session } from "../src/lib/types";
let root: Root;
let container: HTMLDivElement;
let editor: ReturnType<typeof useDraftEditor>;
let auth: ReturnType<typeof useAuth>;
function Harness({ children, note }: { children?: ReactNode; note?: Note }) {
  auth = useAuth();
  editor = useDraftEditor({ draftKey: "compose", note });
  return children;
}
beforeEach(async () => {
  vi.useFakeTimers();
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  localStorage.clear();
  queryClient.clear();
  container = document.createElement("div");
  document.body.appendChild(container);
  root = createRoot(container);
  queryClient.setQueryData(["meta"], { setup_pending: false });
  queryClient.setQueryData(["session"], { owner_id: "owner" });
  vi.spyOn(api, "meta").mockResolvedValue({ setup_pending: false } as Awaited<
    ReturnType<typeof api.meta>
  >);
  vi.spyOn(api, "session").mockResolvedValue({ owner_id: "owner" });
  await act(async () => {
    root.render(
      <QueryClientProvider client={queryClient}>
        <Harness />
      </QueryClientProvider>,
    );
    await vi.advanceTimersByTimeAsync(0);
  });
});
afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
  queryClient.clear();
  vi.restoreAllMocks();
  vi.useRealTimers();
});
it("keeps new capture local across idle and leave until explicitly saved", async () => {
  const create = vi
    .spyOn(api, "createNote")
    .mockImplementation(
      async (body) => ({ id: "new-note", body, version: 1 }) as Note,
    );
  // Controller binds transport at mount, so re-mount after replacing it.
  await act(async () => {
    root.render(
      <QueryClientProvider client={queryClient}>
        <Harness key="test" />
      </QueryClientProvider>,
    );
  });
  act(() => editor.setBody("draft"));
  expect(
    JSON.parse(localStorage.getItem(draftStorageKey("owner", "compose"))!).body,
  ).toBe("draft");
  await act(async () => {
    await vi.advanceTimersByTimeAsync(4999);
  });
  expect(create).not.toHaveBeenCalled();
  await act(async () => {
    await vi.advanceTimersByTimeAsync(60001);
    window.dispatchEvent(new Event("pagehide"));
  });
  expect(create).not.toHaveBeenCalled();
  await act(async () => {
    await editor.save();
  });
  expect(create).toHaveBeenCalledTimes(1);
  expect(editor.body).toBe("");
});
it("does not publish a new capture while typing continuously", async () => {
  const create = vi
    .spyOn(api, "createNote")
    .mockImplementation(
      async (body) => ({ id: "new-note", body, version: 1 }) as Note,
    );
  await act(async () => {
    root.render(
      <QueryClientProvider client={queryClient}>
        <Harness key="continuous" />
      </QueryClientProvider>,
    );
  });
  for (let i = 0; i < 15; i++) {
    act(() => editor.setBody(`draft ${i}`));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4000);
    });
  }
  expect(create).not.toHaveBeenCalled();
});
it("does not loop on validation failures and allows a corrected draft", async () => {
  const { ApiError } = await import("../src/lib/errors");
  const create = vi
    .spyOn(api, "createNote")
    .mockRejectedValue(new ApiError(422, "validation_failed"));
  await act(async () => {
    root.render(
      <QueryClientProvider client={queryClient}>
        <Harness key="validation" />
      </QueryClientProvider>,
    );
  });
  act(() => editor.setBody("invalid"));
  await act(async () => {
    await editor.save();
  });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(120000);
  });
  expect(create).toHaveBeenCalledTimes(1);
  act(() => editor.setBody("corrected"));
  await act(async () => {
    await editor.save();
  });
  expect(create).toHaveBeenCalledTimes(2);
});

it("locks stale sessions while keeping owner drafts, then restores them after sign-in", async () => {
  vi.spyOn(api, "login").mockResolvedValue(undefined);
  vi.spyOn(api, "createNote").mockImplementation(() => new Promise(() => {}));
  act(() => editor.setBody("recover me"));
  vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(JSON.stringify({ code: "unauthenticated" }), { status: 401 }),
  );
  await act(async () => {
    await api.request("/api/v1/notes").catch(() => undefined);
  });
  expect(localStorage.getItem(draftStorageKey("owner", "compose"))).toContain(
    "recover me",
  );
  expect(editor.body).toBe("");
  await act(async () => {
    await auth.login("test password");
    await vi.advanceTimersByTimeAsync(0);
  });
  expect(editor.body).toBe("recover me");
});
it("rehydrates the same account and active space after password setup rotates its cookie", async () => {
  const existing: Session = {
    owner_id: "existing-account",
    user_id: "existing-account",
    workspace_id: "shared-library",
    workspace_role: "editor",
    credential_id: "sso-cookie",
    auth_method: "oidc",
  };
  vi.spyOn(api, "login").mockResolvedValue(undefined);
  const register = vi.spyOn(api, "register");
  vi.mocked(api.session).mockResolvedValue(existing);
  await act(async () => {
    await auth.login("existing sso fixture");
    await vi.advanceTimersByTimeAsync(0);
  });
  queryClient.setQueryData(["private-settings"], { stale: true });
  const rotated: Session = {
    ...existing,
    credential_id: "password-cookie",
    auth_method: "local",
  };
  vi.mocked(api.session).mockResolvedValue(rotated);
  const selected = vi.spyOn(api, "workspaceSession").mockResolvedValue(rotated);
  await act(async () => {
    await auth.refresh();
    await vi.advanceTimersByTimeAsync(0);
  });
  expect(selected).toHaveBeenCalledExactlyOnceWith("shared-library");
  expect(currentSession()).toEqual(rotated);
  expect(auth.status).toBe("authenticated");
  expect(queryClient.getQueryData(["session"])).toEqual(rotated);
  expect(queryClient.getQueryData(["private-settings"])).toBeUndefined();
  expect(register).not.toHaveBeenCalled();
});
it("long-polls only while visible and aborts the previous poll on hide", async () => {
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    value: "visible",
  });
  const changes = vi
    .spyOn(api, "changes")
    .mockImplementation(() => new Promise(() => {}));
  function Watcher() {
    useLiveChanges(true);
    return null;
  }
  await act(async () => {
    root.render(
      <QueryClientProvider client={queryClient}>
        <Harness>
          <Watcher />
        </Harness>
      </QueryClientProvider>,
    );
  });
  expect(changes).toHaveBeenCalledTimes(1);
  const signal = changes.mock.calls[0][2];
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    value: "hidden",
  });
  act(() => document.dispatchEvent(new Event("visibilitychange")));
  expect(signal.aborted).toBe(true);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(60000);
  });
  expect(changes).toHaveBeenCalledTimes(1);
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    value: "visible",
  });
  act(() => document.dispatchEvent(new Event("visibilitychange")));
  expect(changes).toHaveBeenCalledTimes(2);
});
it("invalidates note content and plugin slots on visible long-poll changes", async () => {
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    value: "visible",
  });
  vi.spyOn(api, "changes")
    .mockResolvedValueOnce({ cursor: "epoch.4", plugins_rev: 2, changed: true })
    .mockImplementation(() => new Promise(() => {}));
  const invalidate = vi
    .spyOn(queryClient, "invalidateQueries")
    .mockResolvedValue();
  function Watcher() {
    useLiveChanges(true);
    return null;
  }
  await act(async () => {
    root.render(
      <QueryClientProvider client={queryClient}>
        <Harness>
          <Watcher />
        </Harness>
      </QueryClientProvider>,
    );
  });
  const keys = invalidate.mock.calls.map(([options]) => options?.queryKey?.[0]);
  for (const key of [
    "notes",
    "note",
    "slip",
    "plugin-panel",
    "manifest",
    "catalog",
    "settings",
    "plugin-settings",
  ])
    expect(keys).toContain(key);
});

it("retries queued captures restored from storage after idle without an online event", async () => {
  const key = "00000000-0000-4000-8000-000000000001";
  localStorage.setItem(
    draftStorageKey("owner", "compose"),
    JSON.stringify({
      body: "queued",
      revision: 1,
      pending: { body: "queued", revision: 1, key },
    }),
  );
  const create = vi
    .spyOn(api, "createNote")
    .mockImplementation(
      async (body) => ({ id: "created", body, version: 1 }) as Note,
    );
  await act(async () => {
    root.render(
      <QueryClientProvider client={queryClient}>
        <Harness key="queued" />
      </QueryClientProvider>,
    );
  });
  expect(editor.submitted).toBe(true);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(4999);
  });
  expect(create).not.toHaveBeenCalled();
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1);
  });
  expect(create).toHaveBeenCalledWith("queued", key);
  expect(editor.body).toBe("");
  expect(editor.submitted).toBe(false);
});

it("automatically saves newer typing after an earlier submitted capture ACK and keeps the next capture local", async () => {
  let acknowledge!: (note: Note) => void;
  const create = vi.spyOn(api, "createNote").mockImplementation(
    () =>
      new Promise<Note>((resolve) => {
        acknowledge = resolve;
      }),
  );
  const update = vi
    .spyOn(api, "updateNote")
    .mockImplementation(
      async (id, body, version) => ({ id, body, version: version + 1 }) as Note,
    );
  await act(async () => {
    root.render(
      <QueryClientProvider client={queryClient}>
        <Harness key="newer" />
      </QueryClientProvider>,
    );
  });
  act(() => editor.setBody("submitted"));
  act(() => {
    void editor.save();
  });
  act(() => editor.setBody("newer typing"));
  await act(async () => {
    acknowledge({ id: "created", body: "submitted", version: 1 } as Note);
  });
  expect(editor.body).toBe("newer typing");
  expect(editor.submitted).toBe(true);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(5000);
  });
  expect(update).toHaveBeenCalledWith("created", "newer typing", 1);
  expect(editor.body).toBe("");
  expect(editor.submitted).toBe(false);
  act(() => editor.setBody("next capture"));
  await act(async () => {
    window.dispatchEvent(new Event("online"));
    await vi.advanceTimersByTimeAsync(60000);
    window.dispatchEvent(new Event("pagehide"));
  });
  expect(create).toHaveBeenCalledTimes(1);
  expect(update).toHaveBeenCalledTimes(1);
  expect(editor.body).toBe("next capture");
});

it("explicit sign-out clears only this owner and prevents the editor from saving", async () => {
  vi.spyOn(api, "logout").mockResolvedValue(undefined);
  const create = vi
    .spyOn(api, "createNote")
    .mockImplementation(() => new Promise(() => {}));
  act(() => editor.setBody("private"));
  localStorage.setItem(draftStorageKey("other", "compose"), "other account");
  await act(async () => {
    await auth.logout();
  });
  expect(localStorage.getItem(draftStorageKey("owner", "compose"))).toBeNull();
  expect(localStorage.getItem(draftStorageKey("other", "compose"))).toBe(
    "other account",
  );
  await act(async () => {
    await vi.advanceTimersByTimeAsync(60000);
  });
  expect(create).not.toHaveBeenCalled();
});

it("autosaves an existing note after idle and on leaving", async () => {
  vi.spyOn(api, "login").mockResolvedValue(undefined);
  const note = { id: "existing", body: "before", version: 1 } as Note;
  const update = vi
    .spyOn(api, "updateNote")
    .mockImplementation(async (id, body, version) => ({
      ...note,
      id,
      body,
      version: version + 1,
    }));
  await act(async () => {
    root.render(
      <QueryClientProvider client={queryClient}>
        <Harness key="existing" note={note} />
      </QueryClientProvider>,
    );
  });
  await act(async () => {
    await auth.login("fixture");
  });
  act(() => editor.setBody("after idle"));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(4999);
  });
  expect(update).not.toHaveBeenCalled();
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1);
  });
  expect(update).toHaveBeenCalledWith("existing", "after idle", 1);
  act(() => editor.setBody("on leave"));
  await act(async () => {
    window.dispatchEvent(new Event("pagehide"));
  });
  expect(update).toHaveBeenLastCalledWith("existing", "on leave", 2);
});
