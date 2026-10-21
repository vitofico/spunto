import { afterEach, beforeEach, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { navigate } from "wouter/use-browser-location";
import { api } from "../src/lib/api";
import { Palette } from "../src/components/core/Palette";
import Search from "../src/pages/Search";

let client: QueryClient;
function wrap(child: React.ReactNode) {
  return render(
    <QueryClientProvider client={client}>{child}</QueryClientProvider>,
  );
}
beforeEach(() => {
  client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  window.history.replaceState(null, "", "/");
  vi.spyOn(api, "labels").mockResolvedValue({ labels: [], total: 0 });
  vi.spyOn(api, "manifest").mockResolvedValue({
    ui: 1,
    plugins_rev: 0,
    plugins: [],
  } as Awaited<ReturnType<typeof api.manifest>>);
  vi.spyOn(api, "search").mockResolvedValue({
    hits: [],
    next_cursor: null,
    semantic: "off",
  });
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("palette rows are chosen with the arrows and opened with Enter", async () => {
  const close = vi.fn();
  wrap(<Palette onClose={close} />);
  const input = screen.getByRole("textbox", { name: "Search and commands" });
  fireEvent.change(input, { target: { value: "trash" } });
  // The search row is active as soon as there is a query.
  await waitFor(() =>
    expect(
      document.getElementById(input.getAttribute("aria-activedescendant")!)
        ?.textContent,
    ).toMatch(/^Search for/),
  );
  fireEvent.keyDown(input, { key: "ArrowUp" });
  await waitFor(() =>
    expect(
      document.getElementById(input.getAttribute("aria-activedescendant")!)
        ?.textContent,
    ).toMatch(/^Go to Trash/),
  );
  // Rows are options and only the highlighted one is selected; its ↵ hint follows it.
  expect(screen.getAllByRole("option", { selected: true })).toEqual([
    document.getElementById(input.getAttribute("aria-activedescendant")!),
  ]);
  fireEvent.keyDown(input, { key: "Enter" });
  expect(close).toHaveBeenCalled();
  expect(window.location.pathname).toBe("/trash");
});

it("plugin commands that load after the palette opens are options too", async () => {
  let load!: () => void;
  vi.spyOn(api, "manifest").mockReturnValue(
    new Promise((resolve) => {
      load = () =>
        resolve({
          ui: 1,
          plugins_rev: 1,
          plugins: [
            {
              id: "ask",
              name: "Ask your notes",
              state: "enabled",
              switch: "on",
              contributes: {
                commands: [
                  { id: "ask", title: "Ask your notes", where: ["palette"] },
                ],
              },
            },
          ],
        } as unknown as Awaited<ReturnType<typeof api.manifest>>);
    }),
  );
  wrap(<Palette onClose={() => {}} />);
  // Everything else the palette asks for has settled before the commands arrive.
  await act(() => new Promise((settle) => setTimeout(settle, 50)));
  await act(async () => load());
  expect(
    await screen.findByRole("option", { name: /^Ask your notes/ }),
  ).toBeTruthy();
});

it("palette leaves out groups with nothing to offer", async () => {
  wrap(<Palette onClose={() => {}} />);
  fireEvent.change(screen.getByRole("textbox"), {
    target: { value: "zzzz" },
  });
  expect(await screen.findByText("No notes match this search.")).toBeTruthy();
  expect(screen.queryByRole("group", { name: "Actions" })).toBeNull();
  expect(screen.queryByRole("group", { name: "This note" })).toBeNull();
});

it("palette closes when anything navigates, such as a link in a result", () => {
  const close = vi.fn();
  wrap(<Palette onClose={close} />);
  expect(close).not.toHaveBeenCalled();
  act(() => navigate("/n/elsewhere"));
  expect(close).toHaveBeenCalled();
});

it("search keeps its filters in the address and checks the date range", async () => {
  window.history.replaceState(
    null,
    "",
    "/search?q=coffee&from=2026-10-10&to=2026-10-01",
  );
  wrap(<Search />);
  expect(
    await screen.findByText("The start date is after the end date."),
  ).toBeTruthy();
  expect(screen.getByLabelText("From date").getAttribute("aria-invalid")).toBe(
    "true",
  );
  expect(api.search).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText("To date"), {
    target: { value: "2026-10-20" },
  });
  fireEvent.click(screen.getByLabelText("Include archive"));
  await waitFor(() =>
    expect(api.search).toHaveBeenCalledWith(
      "coffee",
      expect.objectContaining({
        from: "2026-10-10",
        to: "2026-10-20",
        include_archived: true,
      }),
      expect.any(AbortSignal),
    ),
  );
  expect(window.location.search).toBe(
    "?q=coffee&from=2026-10-10&to=2026-10-20&archived=1",
  );
});

it("search explains an over-long query instead of sending it", async () => {
  window.history.replaceState(null, "", "/search");
  wrap(<Search />);
  fireEvent.change(screen.getByLabelText("Search notes"), {
    target: { value: "a".repeat(501) },
  });
  expect(
    await screen.findByText(
      "Searches can be up to 500 characters. This one has 501.",
    ),
  ).toBeTruthy();
  fireEvent.submit(screen.getByRole("search"));
  expect(window.location.search).toBe("");
});
