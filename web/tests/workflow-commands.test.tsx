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
import { api } from "../src/lib/api";
import { CommandResult } from "../src/features/Slots";
import type { Schema } from "../src/lib/types";

let client: QueryClient;
beforeEach(() => {
  client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  vi.spyOn(api, "createNote").mockResolvedValue({} as never);
});
afterEach(() => {
  cleanup();
  client.clear();
  vi.restoreAllMocks();
});
const wrap = (element: React.ReactNode) => (
  <QueryClientProvider client={client}>{element}</QueryClientProvider>
);
const form = (revision: number, rationale: string) => ({
  type: "form",
  schema: {
    type: "object",
    additionalProperties: false,
    required: ["pack_id", "revision", "rationale"],
    properties: {
      pack_id: { type: "string", default: "pack-a", title: "Pack" },
      revision: {
        type: "integer",
        default: revision,
        minimum: 1,
        title: "Revision",
      },
      rationale: {
        type: "string",
        default: rationale,
        maxLength: 600,
        title: "Rationale",
      },
    },
  } satisfies Schema,
});

it("submits a returned edit form to its current command and replaces old defaults", async () => {
  const command = vi
    .spyOn(api, "command")
    .mockResolvedValueOnce(form(2, "Current reasoning"))
    .mockResolvedValueOnce(form(3, "Fresh server reasoning"))
    .mockResolvedValueOnce({ type: "text", text: "Saved" });
  render(
    wrap(
      <CommandResult
        value={{
          type: "command",
          id: "edit",
          args: { pack_id: "pack-a" },
          title: "Edit pack",
        }}
        origin={{ plugin: "context-packs", command: "packs", args: {} }}
      />,
    ),
  );
  fireEvent.click(screen.getByRole("button", { name: "Edit pack" }));
  const field = await screen.findByLabelText("Rationale");
  expect(field.tagName).toBe("TEXTAREA");
  expect(screen.queryByLabelText("Pack")).toBeNull();
  expect(screen.queryByLabelText("Revision")).toBeNull();
  fireEvent.change(field, { target: { value: "First line\nSecond line" } });
  fireEvent.click(screen.getByRole("button", { name: "Run" }));
  await waitFor(() =>
    expect(command).toHaveBeenNthCalledWith(2, "context-packs", "edit", {
      pack_id: "pack-a",
      revision: 2,
      rationale: "First line\nSecond line",
    }),
  );
  await waitFor(() =>
    expect(
      (screen.getByLabelText("Rationale") as HTMLTextAreaElement).value,
    ).toBe("Fresh server reasoning"),
  );
  fireEvent.click(screen.getByRole("button", { name: "Run" }));
  await waitFor(() =>
    expect(command).toHaveBeenNthCalledWith(3, "context-packs", "edit", {
      pack_id: "pack-a",
      revision: 3,
      rationale: "Fresh server reasoning",
    }),
  );
  expect(await screen.findByText("Saved")).toBeTruthy();
});

it("ignores an old command response after the result changes to another plugin", async () => {
  let finish!: (value: unknown) => void;
  vi.spyOn(api, "command").mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const old = { plugin: "context-packs", command: "packs", args: {} };
  const view = render(
    wrap(
      <CommandResult
        value={{ type: "command", id: "edit", title: "Edit pack" }}
        origin={old}
      />,
    ),
  );
  fireEvent.click(screen.getByRole("button", { name: "Edit pack" }));
  await waitFor(() => expect(finish).toBeTypeOf("function"));
  view.rerender(
    wrap(
      <CommandResult
        value={{ type: "text", text: "Current journal" }}
        origin={{ plugin: "decision-journal", command: "journal" }}
      />,
    ),
  );
  await act(async () => {
    finish({ type: "text", text: "Obsolete pack" });
  });
  expect(screen.getByText("Current journal")).toBeTruthy();
  expect(screen.queryByText("Obsolete pack")).toBeNull();
});

it.each(["context-packs", "decision-journal"])(
  "keeps %s out of the generic public-note save path",
  (plugin) => {
    render(
      wrap(
        <CommandResult
          value={{ type: "markdown", text: "Curated private context" }}
          origin={{ plugin, command: "show" }}
        />,
      ),
    );
    expect(screen.getByRole("button", { name: "Copy" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Save as note" })).toBeNull();
    fireEvent.keyDown(document, { key: "s", ctrlKey: true });
    expect(api.createNote).not.toHaveBeenCalled();
  },
);
