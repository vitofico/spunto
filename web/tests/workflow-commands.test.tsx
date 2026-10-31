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
import {
  CommandResult,
  PaletteCommands,
  SlashCommands,
} from "../src/features/Slots";
import type { Plugin, Schema } from "../src/lib/types";

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

function manifest(...plugins: Partial<Plugin>[]) {
  client.setQueryData(["manifest"], {
    ui: 1,
    plugins_rev: 1,
    plugins: plugins.map((p, position) => ({
      name: p.id,
      version: "1",
      description: "",
      category: "develop",
      kind: "server",
      permissions: [],
      state: "enabled",
      switch: "on",
      position,
      ...p,
    })),
  });
}

it.each([
  ["context-packs", "owner"],
  ["decision-journal", "owner"],
  ["unknown-plugin", undefined],
] as const)(
  "keeps %s out of the generic public-note save path",
  (plugin, creates) => {
    if (creates) manifest({ id: plugin, creates });
    render(
      wrap(
        <CommandResult
          value={{ type: "markdown", text: "Curated private context" }}
          origin={{ plugin, command: "show" }}
        />,
      ),
    );
    expect(screen.getByRole("button", { name: "Copy text" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Save as note" })).toBeNull();
    fireEvent.keyDown(document, { key: "s", ctrlKey: true });
    expect(api.createNote).not.toHaveBeenCalled();
  },
);

it("offers Save as note for a plugin that creates no notes and links to the new note", async () => {
  manifest({ id: "summary" });
  vi.mocked(api.createNote).mockResolvedValue({ id: "fresh" } as never);
  render(
    wrap(
      <CommandResult
        value={{ type: "text", text: "Plain result" }}
        origin={{ plugin: "summary", command: "show" }}
      />,
    ),
  );
  fireEvent.keyDown(document, { key: "s", ctrlKey: true });
  expect(
    (await screen.findByRole("link", { name: "Open note" })).getAttribute(
      "href",
    ),
  ).toBe("/n/fresh");
  expect(api.createNote).toHaveBeenCalledWith(
    "Plain result",
    expect.any(String),
  );
});

it("runs the highlighted slash command on Enter and moves with the arrows", () => {
  manifest(
    {
      id: "decision-journal",
      contributes: {
        commands: [
          { id: "record", title: "Record decision", where: ["slash"] },
        ],
      },
    },
    {
      id: "packs",
      contributes: {
        commands: [{ id: "packs", title: "Context packs", where: ["slash"] }],
      },
    },
  );
  const choose = vi.fn();
  render(
    wrap(
      <>
        <textarea aria-label="Body" />
        <SlashCommands query="" onChoose={choose} />
      </>,
    ),
  );
  const body = screen.getByLabelText("Body");
  const options = screen.getAllByRole("option");
  expect(options.map((o) => o.getAttribute("aria-selected"))).toEqual([
    "true",
    "false",
  ]);
  fireEvent.keyDown(body, { key: "ArrowDown" });
  expect(screen.getAllByRole("option")[1].getAttribute("aria-selected")).toBe(
    "true",
  );
  const enter = new KeyboardEvent("keydown", {
    key: "Enter",
    bubbles: true,
    cancelable: true,
  });
  body.dispatchEvent(enter);
  expect(enter.defaultPrevented).toBe(true);
  expect(choose).toHaveBeenCalledWith(
    expect.objectContaining({ plugin: "packs", id: "packs" }),
  );
});

it("lists a command that is both a note action and a palette command once, on the open note", async () => {
  manifest({
    id: "decision-journal",
    contributes: {
      commands: [
        {
          id: "record",
          title: "Record decision",
          where: ["palette", "slash", "note-actions"],
        },
        { id: "journal", title: "Decision journal", where: ["palette"] },
      ],
    },
  });
  const run = vi.spyOn(api, "command").mockResolvedValue({});
  render(wrap(<PaletteCommands noteId="note-1" onResult={() => {}} />));
  expect(
    screen.getAllByRole("button", { name: /Record decision/ }),
  ).toHaveLength(1);
  fireEvent.click(screen.getByRole("button", { name: /Record decision/ }));
  await waitFor(() =>
    expect(run).toHaveBeenCalledWith("decision-journal", "record", {
      note_id: "note-1",
    }),
  );
  await waitFor(() =>
    expect(
      (
        screen.getByRole("button", {
          name: /Decision journal/,
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(false),
  );
  fireEvent.click(screen.getByRole("button", { name: /Decision journal/ }));
  await waitFor(() =>
    expect(run).toHaveBeenLastCalledWith("decision-journal", "journal", {}),
  );
});

it("preserves typed entries after a stale form rejection and explains safe recovery", async () => {
  const { ApiError } = await import("../src/lib/errors");
  vi.spyOn(api, "command").mockRejectedValue(
    new ApiError(409, "version_conflict"),
  );
  render(
    wrap(
      <CommandResult
        value={form(2, "Original reasoning")}
        origin={{
          plugin: "context-packs",
          command: "edit",
          args: { pack_id: "pack-a" },
        }}
      />,
    ),
  );
  fireEvent.change(screen.getByLabelText("Rationale"), {
    target: { value: "Keep my unsaved reasoning" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Run" }));
  await screen.findByRole("alert");
  expect(screen.getByRole("alert").textContent).toContain(
    "Copy any text you want to keep before reopening",
  );
  expect(
    (screen.getByLabelText("Rationale") as HTMLTextAreaElement).value,
  ).toBe("Keep my unsaved reasoning");
});
