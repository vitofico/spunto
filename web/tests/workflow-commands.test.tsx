import CommandResult from "../src/features/CommandResult";
import { DecisionNoteActions } from "../src/features/DecisionJournal";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Router, useLocation } from "wouter";
import { memoryLocation } from "wouter/memory-location";
import { api } from "../src/lib/api";
import { PaletteCommands, SlashCommands } from "../src/features/Slots";
import type { Note, Plugin, Schema } from "../src/lib/types";

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

function decisionPanel(id: string, summary: string) {
  return {
    ui: 1,
    items: [
      { type: "text", text: summary },
      { type: "notes", ids: [id] },
    ],
  };
}

function decisionFixture(id: string) {
  HTMLDialogElement.prototype.showModal = function () {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function () {
    this.open = false;
  };
  const key = ["plugin-panel", "decision-journal", id, "en"];
  client.setQueryDefaults(key, { refetchOnMount: false });
  client.setQueryData(key, decisionPanel(id, "Reviewed"));
  return {
    key,
    note: {
      id,
      annotations: [{ plugin: "decision-journal", kind: "member:journal" }],
    } as Note,
  };
}

it("keeps an open decision history request independent of live panel invalidation", async () => {
  manifest({ id: "decision-journal" });
  client.setQueryDefaults(["manifest"], { refetchOnMount: false });
  const { key, note } = decisionFixture("decision-a");
  let resolveHistory!: (history: unknown) => void;
  const command = vi
    .spyOn(api, "command")
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveHistory = resolve;
        }),
    )
    .mockResolvedValueOnce(decisionPanel(note.id, "Reviewed"));
  render(wrap(<DecisionNoteActions note={note} editing={false} />));
  fireEvent.click(screen.getByRole("button", { name: "Review history" }));
  await waitFor(() => expect(command).toHaveBeenCalledTimes(1));
  await act(async () => {
    await client.invalidateQueries({ queryKey: key });
    resolveHistory(decisionPanel(note.id, "Open · Review on 2030-03-01"));
  });
  const history = within(
    screen.getByRole("dialog", { name: "Review history" }),
  );
  expect(
    await history.findByText(/Open for follow-up.*Review on/),
  ).toBeTruthy();
  expect(history.queryByRole("alert")).toBeNull();
});

it("discards closed decision history and opens the current cached history offline", async () => {
  manifest({ id: "decision-journal" });
  client.setQueryDefaults(["manifest"], { refetchOnMount: false });
  const first = decisionFixture("decision-a");
  const next = decisionFixture("decision-b");
  let resolveFirst!: (history: unknown) => void;
  const command = vi.spyOn(api, "command").mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        resolveFirst = resolve;
      }),
  );
  const view = render(
    wrap(<DecisionNoteActions note={first.note} editing={false} />),
  );
  fireEvent.click(screen.getByRole("button", { name: "Review history" }));
  await waitFor(() => expect(command).toHaveBeenCalledTimes(1));
  fireEvent.click(
    within(screen.getByRole("dialog")).getByRole("button", { name: "Close" }),
  );
  vi.spyOn(navigator, "onLine", "get").mockReturnValue(false);
  view.rerender(wrap(<DecisionNoteActions note={next.note} editing={false} />));
  fireEvent.click(screen.getByRole("button", { name: "Review history" }));
  await act(async () => {
    resolveFirst(decisionPanel(first.note.id, "Open · Review on 2030-03-01"));
  });
  const history = within(
    screen.getByRole("dialog", { name: "Review history" }),
  );
  expect(await history.findByText("Reviewed")).toBeTruthy();
  expect(history.queryByText(/Open for follow-up/)).toBeNull();
  expect(command).toHaveBeenCalledTimes(1);
});

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
  const open = vi.fn();
  const memory = memoryLocation();
  render(
    wrap(
      <Router hook={memory.hook}>
        <CommandResult
          value={{ type: "text", text: "Plain result" }}
          origin={{ plugin: "summary", command: "show" }}
          onOpenNote={open}
        />
      </Router>,
    ),
  );
  fireEvent.keyDown(document, { key: "s", ctrlKey: true });
  const link = await screen.findByRole("link", { name: "Open note" });
  expect(link.getAttribute("href")).toBe("/n/fresh");
  fireEvent.click(link, { metaKey: true });
  expect(open).not.toHaveBeenCalled();
  fireEvent.click(link);
  expect(open).toHaveBeenCalledOnce();
  expect(api.createNote).toHaveBeenCalledWith(
    "Plain result",
    expect.any(String),
  );
});

it("focuses the result and presents visible note references as a navigable list", async () => {
  manifest({
    id: "context-packs",
    name: "Context packs",
    contributes: {
      commands: [{ id: "show", title: "Open context pack", where: [] }],
    },
  });
  const memory = memoryLocation();
  function Location() {
    const [location] = useLocation();
    return <output>{location}</output>;
  }
  vi.spyOn(api, "note").mockImplementation(async (id) => {
    if (id === "missing") throw new Error("Unavailable");
    return { id, title: "Source note" } as never;
  });
  const open = vi.fn();
  render(
    wrap(
      <Router hook={memory.hook}>
        <Location />
        <CommandResult
          value={{
            ui: 1,
            items: [
              { type: "text", text: "Sources selected" },
              {
                type: "notes",
                items: [
                  { id: "source", reason: "Referenced context" },
                  "missing",
                ],
              },
            ],
          }}
          onOpenNote={open}
          onDismiss={() => {}}
          surroundingTitle="Context pack"
          origin={{ plugin: "context-packs", command: "show" }}
        />
      </Router>,
    ),
  );
  const result = screen.getByRole("region", { name: "Open context pack" });
  expect(document.activeElement).toBe(within(result).getByRole("heading"));
  expect(within(result).getByText("Sources selected")).toBeTruthy();
  const notes = within(result).getByRole("list", { name: "Notes" });
  const source = await within(notes).findByRole("link", {
    name: /Source note/,
  });
  expect(within(notes).getByText("Referenced context")).toBeTruthy();
  await within(notes).findByText("Note unavailable");
  expect(within(notes).getAllByRole("link")).toHaveLength(1);
  fireEvent.click(source, { ctrlKey: true });
  expect(open).not.toHaveBeenCalled();
  fireEvent.click(source);
  expect(open).toHaveBeenCalledOnce();
  expect(screen.getByText("/n/source")).toBeTruthy();
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
  const memory = memoryLocation();
  function Location() {
    const [location] = useLocation();
    return <output>{location}</output>;
  }
  render(
    wrap(
      <Router hook={memory.hook}>
        <Location />
        <PaletteCommands noteId="note-1" onResult={() => {}} />
      </Router>,
    ),
  );
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
  expect(screen.getByText("/v/decision-journal/journal")).toBeTruthy();
  expect(run).toHaveBeenCalledTimes(1);
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

it.each([true, false])(
  "only applies a pending command proposal from the originating plugin (%s)",
  async (matching) => {
    manifest({ id: "title-summary" });
    HTMLDialogElement.prototype.showModal = function () {
      this.open = true;
    };
    HTMLDialogElement.prototype.close = function () {
      this.open = false;
    };
    const proposals = vi.spyOn(api, "suggestions").mockResolvedValue({
      suggestions: [
        {
          id: "proposal",
          plugin: matching ? "title-summary" : "link-enrichment",
          note_id: "source",
          note_version: 2,
          action: "set_title",
          status: "pending",
          payload: { title: "A clearer title" },
        },
      ],
    } as never);
    vi.spyOn(api, "note").mockResolvedValue({
      title: "Source note",
      version: 2,
    } as never);
    const decide = vi
      .spyOn(api, "decideSuggestion")
      .mockResolvedValue({ applied: true } as never);
    render(
      wrap(
        <CommandResult
          value={{
            type: "pill",
            label: "Use title",
            action: "accept",
            suggestion_id: "proposal",
          }}
          origin={{
            plugin: "title-summary",
            command: "summarize",
            args: { note_id: "source" },
          }}
        />,
      ),
    );
    fireEvent.click(screen.getByRole("button", { name: "Use title" }));
    const apply = await screen.findByRole("button", {
      name: "Apply",
    });
    expect(proposals).toHaveBeenCalledWith("note", "source");
    if (matching) {
      await waitFor(() =>
        expect((apply as HTMLButtonElement).disabled).toBe(false),
      );
      fireEvent.click(apply);
      await screen.findByText("Change applied.");
      expect(decide).toHaveBeenCalledExactlyOnceWith("proposal", "accept");
    } else {
      await screen.findByText("This proposal is no longer available.");
      expect((apply as HTMLButtonElement).disabled).toBe(true);
      expect(decide).not.toHaveBeenCalled();
    }
  },
);

it.each(["inline", "long", "stale"] as const)(
  "reviews Tidy’s %s comparison in the command result",
  async (kind) => {
    const long = kind === "long";
    const before = long ? "a".repeat(4100) : "Before cleanup";
    const after = long ? "b".repeat(4100) : "After cleanup";
    vi.spyOn(api, "suggestions").mockResolvedValue({
      suggestions: [
        {
          id: "tidy-proposal",
          plugin: "tidy",
          note_id: "source",
          note_version: 2,
          action: "replace_body",
          status: "pending",
          payload: { body: after },
        },
      ],
    } as never);
    vi.spyOn(api, "note").mockResolvedValue({
      id: "source",
      version: kind === "stale" ? 3 : 2,
      body: before,
    } as never);
    const decide = vi.spyOn(api, "decideSuggestion").mockResolvedValue({
      applied: true,
    } as never);
    const onDismiss = vi.fn();
    render(
      wrap(
        <CommandResult
          origin={{
            plugin: "tidy",
            command: "tidy",
            args: { note_id: "source" },
          }}
          onDismiss={onDismiss}
          value={{
            ui: 1,
            items: [
              long
                ? { type: "text", text: "Open Review to compare" }
                : {
                    type: "diff",
                    base_version: 2,
                    hunks: [
                      {
                        old_start: 1,
                        new_start: 1,
                        old_lines: [before],
                        new_lines: [after],
                      },
                    ],
                  },
              {
                type: "pill",
                action: "accept",
                label: "Apply changes",
                suggestion_id: "tidy-proposal",
              },
            ],
          }}
        />,
      ),
    );
    if (long) {
      const review = await screen.findByRole<HTMLButtonElement>("button", {
        name: "Review changes",
      });
      expect(
        screen.queryByRole("button", { name: "Apply changes" }),
      ).toBeNull();
      await waitFor(() => expect(review.disabled).toBe(false));
      fireEvent.click(review);
    }
    const apply = await screen.findByRole<HTMLButtonElement>("button", {
      name: "Apply changes",
    });
    if (kind === "stale") {
      await screen.findByText("This proposal is no longer available.");
      expect(apply.disabled).toBe(true);
      expect(decide).not.toHaveBeenCalled();
      return;
    }
    await screen.findByText(after);
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(apply.disabled).toBe(false);
    fireEvent.click(apply);
    await waitFor(() => expect(onDismiss).toHaveBeenCalledTimes(1));
    expect(decide).toHaveBeenCalledExactlyOnceWith("tidy-proposal", "accept");
  },
);
