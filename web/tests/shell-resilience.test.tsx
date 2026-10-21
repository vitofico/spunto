import { lazy, Suspense, useState, type ComponentType } from "react";
import { afterEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { LoadBoundary } from "../src/App";
import { api } from "../src/lib/api";
import { ApiError } from "../src/lib/errors";
import { draftStorageKey, waitingDrafts } from "../src/lib/drafts";
import { queryClient, useAuth } from "../src/lib/query";

afterEach(() => {
  cleanup();
  queryClient.clear();
  localStorage.clear();
  sessionStorage.clear();
});

function failingPage() {
  return lazy(() =>
    Promise.reject(
      new TypeError(
        "Failed to fetch dynamically imported module: /assets/Note.js",
      ),
    ),
  );
}

it("turns a failed page chunk into a calm retry state and reloads at most once", async () => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  const retry = vi.fn();
  function Harness() {
    const [Page, setPage] = useState<ComponentType>(failingPage);
    return (
      <LoadBoundary
        location="/n/1"
        onRetry={() => {
          retry();
          setPage(() => () => <p>Note opened</p>);
        }}
      >
        <Suspense fallback="Opening…">
          <Page />
        </Suspense>
      </LoadBoundary>
    );
  }
  render(<Harness />);
  expect(await screen.findByRole("alert")).toBeTruthy();
  expect(screen.getByText("Something didn’t load.")).toBeTruthy();
  // The one automatic reload is recorded so a second failure cannot loop.
  const marker = sessionStorage.getItem("spunto:chunk-reload");
  expect(Number(marker)).toBeGreaterThan(0);
  fireEvent.click(screen.getByRole("button", { name: "Try again" }));
  expect(retry).toHaveBeenCalledOnce();
  expect(await screen.findByText("Note opened")).toBeTruthy();
  expect(sessionStorage.getItem("spunto:chunk-reload")).toBe(marker);
});

it("counts only this owner's saves that are still waiting for the server", () => {
  const pending = (body: string) =>
    JSON.stringify({
      body,
      revision: 1,
      pending: {
        body,
        revision: 1,
        key: "00000000-0000-4000-8000-000000000001",
      },
    });
  localStorage.setItem(draftStorageKey("owner", "composer"), pending("one"));
  localStorage.setItem(draftStorageKey("owner", "note:2"), pending("two"));
  localStorage.setItem(
    draftStorageKey("owner", "composer:typing"),
    JSON.stringify({ body: "not sent yet", revision: 3 }),
  );
  localStorage.setItem(draftStorageKey("other", "composer"), pending("x"));
  expect(waitingDrafts(localStorage, "owner")).toBe(2);
  expect(waitingDrafts(localStorage, "nobody")).toBe(0);
});

it("reports an unreachable server instead of waiting on a wordmark forever", async () => {
  vi.spyOn(api, "meta").mockRejectedValue(new ApiError(0, "unreachable"));
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  const { result } = renderHook(useAuth, { wrapper });
  await waitFor(() => expect(result.current.status).toBe("unreachable"), {
    timeout: 5000,
  });
  vi.mocked(api.meta).mockResolvedValue({ setup_pending: true } as Awaited<
    ReturnType<typeof api.meta>
  >);
  result.current.reconnect();
  await waitFor(() => expect(result.current.status).toBe("setup"));
});
