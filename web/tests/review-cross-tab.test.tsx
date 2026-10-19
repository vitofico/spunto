import { afterEach, expect, it, vi } from "vitest";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { api } from "../src/lib/api";
import { queryClient, useAuth } from "../src/lib/query";
import { useDraftEditor } from "../src/lib/editor";
import { draftStorageKey, eraseOwnerDrafts } from "../src/lib/drafts";
afterEach(() => {
  cleanup();
  queryClient.clear();
  localStorage.clear();
  vi.restoreAllMocks();
});
it("does not resurrect drafts when a delayed cross-tab sign-out event arrives after erasure", async () => {
  localStorage.clear();
  queryClient.clear();
  vi.spyOn(api, "meta").mockResolvedValue({ setup_pending: false } as Awaited<
    ReturnType<typeof api.meta>
  >);
  vi.spyOn(api, "session").mockResolvedValue({ owner_id: "cross-tab-owner" });
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
      const editor = useDraftEditor({ draftKey: "cross-tab" });
      return { auth, editor };
    },
    { wrapper: Wrapper },
  );
  await waitFor(() => expect(result.current.auth.status).toBe("authenticated"));
  act(() => result.current.editor.setBody("private draft"));
  const key = draftStorageKey("cross-tab-owner", "cross-tab");
  expect(localStorage.getItem(key)).toContain("private draft");
  // The other tab completed sign-out while this tab was frozen; its storage event is delivered on resume.
  eraseOwnerDrafts(localStorage, "cross-tab-owner");
  await act(async () => {
    window.dispatchEvent(
      new StorageEvent("storage", { key: "spunto:signed-out", newValue: "1" }),
    );
  });
  expect(localStorage.getItem(key)).toBeNull();
});
