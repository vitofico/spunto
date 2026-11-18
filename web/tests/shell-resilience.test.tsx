import { lazy, Suspense, useState, type ComponentType } from "react";
import { afterEach, expect, it, vi } from "vitest";
import {
  act,
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
import { draftStorageKey, keptDrafts } from "../src/lib/drafts";
import { queryClient, useAuth } from "../src/lib/query";

afterEach(() => {
  cleanup();
  queryClient.clear();
  localStorage.clear();
  sessionStorage.clear();
  document.querySelector("[data-test-entry]")?.remove();
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

function currentEntry() {
  const script = document.createElement("script");
  script.type = "module";
  script.src = "/assets/index-current.js";
  script.dataset.testEntry = "";
  document.head.append(script);
}

function failedPage() {
  const Page = failingPage();
  return render(
    <LoadBoundary location="/n/1" onRetry={vi.fn()}>
      <Suspense fallback="Opening…">
        <Page />
      </Suspense>
    </LoadBoundary>,
  );
}

it("automatically reloads at most once when the server has a new entry", async () => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  currentEntry();
  const fetch = vi
    .spyOn(globalThis, "fetch")
    .mockImplementation(
      async () =>
        new Response(
          '<script type="module" src="/assets/index-new.js"></script>',
        ),
    );
  const page = failedPage();
  expect(await screen.findByRole("alert")).toBeTruthy();
  await waitFor(() =>
    expect(
      Number(sessionStorage.getItem("spunto:chunk-reload")),
    ).toBeGreaterThan(0),
  );
  expect(fetch).toHaveBeenCalledWith(
    "/",
    expect.objectContaining({
      cache: "no-store",
      credentials: "omit",
      mode: "same-origin",
      referrerPolicy: "no-referrer",
    }),
  );
  const marker = sessionStorage.getItem("spunto:chunk-reload");
  page.unmount();
  await act(async () => {
    failedPage();
  });
  expect(sessionStorage.getItem("spunto:chunk-reload")).toBe(marker);
});

it.each(["same entry", "HTTP error", "unreachable"] as const)(
  "keeps the retry button without taking over navigation on %s",
  async (scenario) => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    currentEntry();
    const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      if (scenario === "unreachable") throw new TypeError("Network failure");
      return new Response(
        '<script type="module" src="/assets/index-current.js"></script>',
        {
          status: scenario === "same entry" ? 200 : 503,
        },
      );
    });
    await act(async () => {
      failedPage();
    });
    expect(fetch).toHaveBeenCalledOnce();
    expect(sessionStorage.getItem("spunto:chunk-reload")).toBeNull();
    expect(screen.getByText("Something didn’t load.")).toBeTruthy();
    expect(
      screen.getByRole<HTMLButtonElement>("button", { name: "Try again" })
        .disabled,
    ).toBe(false);
  },
);

it.each(["unmount", "route change", "online retry"] as const)(
  "discards a delayed build check after %s",
  async (scenario) => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    currentEntry();
    let finish!: (response: Response) => void;
    const response = new Promise<Response>((resolve) => {
      finish = resolve;
    });
    const fetch = vi.spyOn(globalThis, "fetch").mockReturnValue(response);
    const Page = failingPage();
    const view = (location: string, failed: boolean): ReactNode => (
      <LoadBoundary
        location={location}
        onRetry={() => page.rerender(view(location, false))}
      >
        <Suspense fallback="Opening…">
          {failed ? <Page /> : "Note opened"}
        </Suspense>
      </LoadBoundary>
    );
    const page = render(view("/n/1", true));
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(fetch).toHaveBeenCalledOnce();

    if (scenario === "unmount") {
      page.unmount();
    } else if (scenario === "route change") {
      page.rerender(view("/n/2", false));
      page.rerender(view("/n/1", false));
    } else {
      fireEvent(window, new Event("online"));
    }
    expect(fetch.mock.calls[0][1]?.signal?.aborted).toBe(true);
    if (scenario !== "unmount")
      expect(screen.getByText("Note opened")).toBeTruthy();

    await act(async () => {
      finish(
        new Response(
          '<script type="module" src="/assets/index-new.js"></script>',
        ),
      );
    });
    expect(sessionStorage.getItem("spunto:chunk-reload")).toBeNull();
  },
);

it("retries an ordinary failed view without reloading the document", async () => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  const retry = vi.fn();
  function Harness() {
    const [Page, setPage] = useState<ComponentType>(() =>
      lazy(() => Promise.reject(new Error("View failed"))),
    );
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
  fireEvent.click(await screen.findByRole("button", { name: "Try again" }));
  expect(retry).toHaveBeenCalledOnce();
  expect(await screen.findByText("Note opened")).toBeTruthy();
});

it("counts only this owner's saves that are still waiting for the server", () => {
  const save = (body: string, key = 1) => ({
    body,
    revision: 1,
    key: `00000000-0000-4000-8000-00000000000${key}`,
  });
  localStorage.setItem(
    draftStorageKey("owner", "note:2"),
    JSON.stringify({ body: "two", revision: 1, pending: save("two") }),
  );
  // Captures queue in the composer's outbox while it already holds new typing.
  localStorage.setItem(
    draftStorageKey("owner", "composer"),
    JSON.stringify({
      body: "not sent yet",
      revision: 3,
      outbox: [save("one", 2), save("three", 3)],
    }),
  );
  localStorage.setItem(
    draftStorageKey("owner", "composer:typing"),
    JSON.stringify({ body: "typed only", revision: 3 }),
  );
  localStorage.setItem(
    draftStorageKey("other", "composer"),
    JSON.stringify({ body: "x", revision: 1, pending: save("x") }),
  );
  expect(keptDrafts(localStorage, "owner")).toEqual({ waiting: 3, unsent: 5 });
  expect(keptDrafts(localStorage, "nobody")).toEqual({
    waiting: 0,
    unsent: 0,
  });
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

it("sends an expired hosted cookie to sign-in, not to an unreachable screen", async () => {
  // Hosted servers answer /meta with 401 when the browser still holds an expired cookie.
  vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(JSON.stringify({ code: "unauthenticated" }), {
      status: 401,
      headers: { "Content-Type": "application/problem+json" },
    }),
  );
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  const { result } = renderHook(useAuth, { wrapper });
  await waitFor(() => expect(result.current.status).toBe("anonymous"));
});
