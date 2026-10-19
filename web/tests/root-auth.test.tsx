import { afterEach, expect, it, vi } from "vitest";
import { renderHook, waitFor, cleanup } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { api } from "../src/lib/api";
import { queryClient, useAuth } from "../src/lib/query";
import type { ReactNode } from "react";

afterEach(() => {
  cleanup();
  queryClient.clear();
  vi.restoreAllMocks();
});

it("an initial session 401 reaches the sign-in screen without cancelling its own query", async () => {
  queryClient.clear();
  queryClient.setQueryData(["settings"], {
    owner_display_name: "Private fixture",
  });
  vi.spyOn(api, "meta").mockResolvedValue({ setup_pending: false } as Awaited<
    ReturnType<typeof api.meta>
  >);
  vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(JSON.stringify({ code: "unauthenticated" }), { status: 401 }),
  );
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  }
  const { result } = renderHook(useAuth, { wrapper: Wrapper });
  await waitFor(() => expect(result.current.status).toBe("anonymous"));
  expect(queryClient.getQueryData(["session"])).toBeNull();
  expect(queryClient.getQueryData(["settings"])).toBeUndefined();
});
