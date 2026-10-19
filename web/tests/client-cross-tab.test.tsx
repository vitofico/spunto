import { afterEach, expect, it, vi } from "vitest";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { api } from "../src/lib/api";
import { queryClient, useAuth } from "../src/lib/query";
import { useDraftEditor } from "../src/lib/editor";
import { draftStorageKey, eraseOwnerDrafts } from "../src/lib/drafts";
import type { Note } from "../src/lib/types";

afterEach(() => {
  cleanup();
  queryClient.clear();
  localStorage.clear();
  vi.restoreAllMocks();
});

it("erases cross-tab drafts after stopping editors and ignores delayed save ACKs while retaining other owners", async () => {
  localStorage.clear();
  queryClient.clear();
  vi.spyOn(api, "meta").mockResolvedValue({ setup_pending: false } as Awaited<
    ReturnType<typeof api.meta>
  >);
  vi.spyOn(api, "session").mockResolvedValue({ owner_id: "cross-tab-owner" });
  let acknowledge!: (note: Note) => void;
  const create = vi.spyOn(api, "createNote").mockImplementation(
    () =>
      new Promise<Note>((resolve) => {
        acknowledge = resolve;
      }),
  );
  queryClient.setQueryData(["meta"], { setup_pending: false });
  queryClient.setQueryData(["session"], { owner_id: "cross-tab-owner" });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  }
  const { result } = renderHook(
    () => {
      const auth = useAuth();
      const editor = useDraftEditor({ draftKey: "in-flight" });
      return { auth, editor };
    },
    { wrapper: Wrapper },
  );
  await waitFor(() => expect(result.current.auth.status).toBe("authenticated"));
  act(() => result.current.editor.setBody("submitted private draft"));
  let save!: Promise<Note | undefined>;
  act(() => {
    save = result.current.editor.save();
  });
  act(() => result.current.editor.setBody("newer private typing"));
  const key = draftStorageKey("cross-tab-owner", "in-flight");
  const other = draftStorageKey("other-owner", "capture");
  localStorage.setItem(other, "another owner's retained draft");
  expect(localStorage.getItem(key)).toContain("newer private typing");
  // The signing-out tab erased drafts before this frozen tab received the event.
  eraseOwnerDrafts(localStorage, "cross-tab-owner");
  localStorage.setItem("spunto:signed-out", "1");
  await act(async () => {
    window.dispatchEvent(
      new StorageEvent("storage", { key: "spunto:signed-out", newValue: "1" }),
    );
  });
  expect(localStorage.getItem(key)).toBeNull();
  await act(async () => {
    acknowledge({
      id: "accepted",
      body: "submitted private draft",
      version: 1,
    } as Note);
    await save;
  });
  expect(localStorage.getItem(key)).toBeNull();
  expect(localStorage.getItem(other)).toBe("another owner's retained draft");
  expect(result.current.auth.status).toBe("anonymous");
  expect(create).toHaveBeenCalledTimes(1);
});
