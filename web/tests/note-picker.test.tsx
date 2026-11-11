import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { NotePicker } from "../src/features/NotePicker";
import { api } from "../src/lib/api";
import type { Note } from "../src/lib/types";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("submits the selected note while a background sync refetches the list", async () => {
  const note = {
    id: "n1",
    version: 1,
    title: "Target",
    body: "Target",
    created_at: "2026-11-09T20:03:00Z",
  } as Note;
  const search = vi
    .spyOn(api, "search")
    .mockResolvedValueOnce({ hits: [note], next_cursor: null } as never)
    .mockReturnValue(new Promise(() => {}));
  const client = new QueryClient();
  const onSelect = vi.fn();
  render(
    <QueryClientProvider client={client}>
      <NotePicker
        exclude={[]}
        action="Add source"
        busy={false}
        onSelect={onSelect}
      />
    </QueryClientProvider>,
  );
  await userEvent.click(await screen.findByRole("radio", { name: /Target/ }));
  // The live change feed invalidates the picker on every remote change.
  void client.invalidateQueries({ queryKey: ["note-picker"] });
  await waitFor(() => expect(search).toHaveBeenCalledTimes(2));
  await userEvent.click(screen.getByRole("button", { name: "Add source" }));
  expect(onSelect).toHaveBeenCalledWith(note);
  client.clear();
});
