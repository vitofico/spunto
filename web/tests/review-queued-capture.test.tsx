import { afterEach, expect, it, vi } from "vitest";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { api } from "../src/lib/api";
import { ApiError } from "../src/lib/errors";
import { queryClient, useAuth } from "../src/lib/query";
import { useDraftEditor } from "../src/lib/editor";
import type { Note } from "../src/lib/types";
afterEach(() => {
  cleanup();
  queryClient.clear();
  localStorage.clear();
  vi.restoreAllMocks();
  vi.useRealTimers();
});
it("automatically retries an explicitly queued capture restored after reload", async () => {
  localStorage.clear();
  queryClient.clear();
  vi.spyOn(api, "meta").mockResolvedValue({ setup_pending: false } as Awaited<
    ReturnType<typeof api.meta>
  >);
  vi.spyOn(api, "session").mockResolvedValue({ owner_id: "review-owner" });
  const create = vi
    .spyOn(api, "createNote")
    .mockRejectedValueOnce(new ApiError(0, "unreachable"))
    .mockRejectedValueOnce(new ApiError(0, "unreachable"))
    .mockImplementation(
      async (body) => ({ id: "review-note", body, version: 1 }) as Note,
    );
  queryClient.setQueryData(["meta"], { setup_pending: false });
  queryClient.setQueryData(["session"], { owner_id: "review-owner" });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  }
  const useEditor = () => {
    useAuth();
    return useDraftEditor({ draftKey: "review-composer" });
  };
  const first = renderHook(useEditor, { wrapper: Wrapper });
  await waitFor(() => expect(first.result.current.body).toBe(""));
  act(() => first.result.current.setBody("queued capture"));
  await act(async () => {
    await first.result.current.save();
  });
  expect(create).toHaveBeenCalledTimes(1);
  await act(async () => {
    first.unmount();
  });
  expect(create).toHaveBeenCalledTimes(2);
  const restored = renderHook(useEditor, { wrapper: Wrapper });
  await waitFor(() =>
    expect(restored.result.current.body).toBe("queued capture"),
  );
  vi.useFakeTimers();
  await act(async () => {
    window.dispatchEvent(new Event("online"));
    await vi.advanceTimersByTimeAsync(16000);
  });
  expect(create).toHaveBeenCalledTimes(3);
});
