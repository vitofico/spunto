import { afterEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { api } from "../src/lib/api";
import { PaletteCommands } from "../src/features/Slots";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("loads library stats only on request and renders them through the existing result sheet", async () => {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: Infinity },
      mutations: { retry: false },
    },
  });
  client.setQueryData(["manifest"], {
    ui: 1,
    plugins_rev: 1,
    plugins: [
      {
        id: "library-stats",
        name: "Library stats",
        version: "1.0.0",
        category: "organize",
        kind: "server",
        permissions: ["notes.read"],
        state: "enabled",
        switch: "on",
        position: 0,
        contributes: {
          commands: [
            {
              id: "summary",
              title: "Library stats",
              where: ["palette"],
              online: true,
            },
          ],
        },
      },
    ],
  });
  const command = vi.spyOn(api, "command").mockResolvedValue({
    ui: 1,
    items: [
      { type: "text", text: "Saved notes: 8 · Active: 7 · Archived: 1" },
      { type: "text", text: "Created in the last 30 days: 5" },
      { type: "text", text: "Active notes without labels: 5" },
      { type: "text", text: "Active pinned notes: 1" },
      { type: "text", text: "Open tasks in active notes: 3" },
    ],
  });
  const create = vi.spyOn(api, "createNote");
  const note = vi.spyOn(api, "note");
  render(
    <QueryClientProvider client={client}>
      <PaletteCommands noteId="open-note" />
    </QueryClientProvider>,
  );
  expect(command).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: /Library stats/ }));
  expect(
    await screen.findByText("Saved notes: 8 · Active: 7 · Archived: 1"),
  ).toBeTruthy();
  expect(screen.getByText("Created in the last 30 days: 5")).toBeTruthy();
  expect(screen.getByText("Active notes without labels: 5")).toBeTruthy();
  expect(screen.getByText("Open tasks in active notes: 3")).toBeTruthy();
  expect(screen.getByRole("button", { name: "Copy text" })).toBeTruthy();
  await waitFor(() =>
    expect(command).toHaveBeenCalledExactlyOnceWith(
      "library-stats",
      "summary",
      {},
    ),
  );
  expect(create).not.toHaveBeenCalled();
  expect(note).not.toHaveBeenCalled();
  client.clear();
});
