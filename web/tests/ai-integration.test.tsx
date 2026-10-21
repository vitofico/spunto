import { afterEach, beforeEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { api } from "../src/lib/api";
import { AISettings, AIRouting } from "../src/features/AISettings";
import { StatusPage } from "../src/features/Settings";
import { NotePanel } from "../src/features/Slots";
import { Palette } from "../src/components/core/Palette";
import Search from "../src/pages/Search";
import type { AIStatus, Note, Plugin, SearchPage } from "../src/lib/types";
let client: QueryClient;
const note: Note = {
  id: "0199c426-0800-7000-8000-000000000001",
  body: "Coffee in Rome",
  title: "Coffee",
  version: 1,
  change_seq: 1,
  created_at: "2026-10-14T09:00:00Z",
  updated_at: "2026-10-14T09:00:00Z",
  private: false,
  machine: false,
  updated_via_agent: false,
  archived: false,
  trashed: false,
  pinned: false,
  source: { kind: "web", ref: null },
  origin: "owner",
  conflict_of: null,
  open_tasks: 0,
  labels: [],
  suggestions: [],
  annotations: [],
};
const ai: AIStatus = {
  capabilities: ["ai.chat", "ai.embed"],
  profiles: [
    {
      name: "local",
      provider: "openai",
      base_url: "http://localhost:11434/v1",
      embed_model: "embeddinggemma",
      leaves_network: { embed: false },
    },
    {
      name: "cloud",
      provider: "anthropic",
      base_url: "https://api.anthropic.com",
      chat_model: "fixture-chat",
      leaves_network: { chat: true },
    },
  ],
  bindings: { chat: "cloud", embed: "local" },
  health: [],
  indexing: {
    available: true,
    enabled: true,
    chunks: 4,
    routes: [
      {
        profile: "local",
        active_key: "old",
        target_key: "new",
        indexed: 2,
        total: 5,
      },
    ],
  },
};
const ask: Plugin = {
  id: "ask",
  name: "Ask your notes",
  version: "1",
  description: "Ask",
  category: "recall",
  kind: "server",
  permissions: ["notes.read", "notes.create", "ai.chat", "ai.embed"],
  creates: "machine",
  position: 0,
  switch: "on",
  state: "enabled",
  contributes: {
    commands: [
      { id: "ask", title: "Ask your notes", where: ["palette"] },
      { id: "save-answer", title: "Save as note", where: [] },
    ],
  },
};
function wrap(child: React.ReactNode) {
  return render(
    <QueryClientProvider client={client}>{child}</QueryClientProvider>,
  );
}
function pending<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
beforeEach(() => {
  client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  vi.spyOn(api, "manifest").mockResolvedValue({
    ui: 1,
    plugins_rev: 1,
    plugins: [],
  });
  vi.spyOn(api, "ai").mockResolvedValue(ai);
  vi.spyOn(api, "labels").mockResolvedValue({ labels: [], total: 0 });
  vi.spyOn(api, "search").mockResolvedValue({
    hits: [],
    semantic: "off",
    next_cursor: null,
  });
  vi.spyOn(api, "note").mockResolvedValue(note);
  window.history.replaceState(null, "", "/");
});
afterEach(() => {
  cleanup();
  client.clear();
  vi.restoreAllMocks();
});
it("shows environment-owned profiles, checks providers, and queues index work", async () => {
  const check = vi.spyOn(api, "checkAI").mockResolvedValue({
    health: [
      { capability: "embed", profile: "local", ok: true, dimension: 768 },
    ],
  });
  const reindex = vi
    .spyOn(api, "reindexAI")
    .mockResolvedValue({ indexing: ai.indexing! });
  wrap(<AISettings />);
  expect(
    await screen.findByText("embeddinggemma", { exact: false }),
  ).toBeTruthy();
  expect(screen.getByText("fixture-chat", { exact: false })).toBeTruthy();
  expect(
    screen.getByText("local: 2 of 5 notes indexed.", { exact: false }),
  ).toBeTruthy();
  expect(screen.queryByRole("textbox")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Check providers" }));
  expect(
    await screen.findByText("768 dimensions", { exact: false }),
  ).toBeTruthy();
  expect(check).toHaveBeenCalledOnce();
  fireEvent.click(screen.getByRole("button", { name: "Reindex notes" }));
  expect(await screen.findByText("Index work queued.")).toBeTruthy();
  expect(reindex).toHaveBeenCalledOnce();
});
it("routes only declared capabilities and restores defaults with an empty map", async () => {
  const save = vi.spyOn(api, "saveAIRoute").mockResolvedValue({});
  wrap(<AIRouting plugin={{ ...ask, ai_route: { chat: "cloud" } }} />);
  const chat = await screen.findByRole("combobox", { name: "Chat profile" });
  expect(screen.queryByRole("combobox", { name: "Vision profile" })).toBeNull();
  fireEvent.change(chat, { target: { value: "" } });
  fireEvent.click(screen.getByRole("button", { name: "Save AI routes" }));
  await waitFor(() => expect(save).toHaveBeenCalledWith("ask", {}));
});
// Search marks the matched word, which splits the paragraph's text.
const body = (text: string) => (_content: string, element: Element | null) =>
  !!element?.classList.contains("note-body") &&
  element.textContent?.trim() === text;
it("shows keyword hits while hybrid is pending and then swaps one list", async () => {
  window.history.replaceState(null, "", "/search?q=coffee");
  const blended = pending<SearchPage>();
  vi.mocked(api.search).mockImplementation((_q, filters) =>
    filters?.mode === "hybrid"
      ? blended.promise
      : Promise.resolve({ hits: [note], semantic: "off", next_cursor: null }),
  );
  wrap(<Search />);
  expect(await screen.findByText(body("Coffee in Rome"))).toBeTruthy();
  expect(document.querySelector("mark[data-match]")?.textContent).toBe(
    "Coffee",
  );
  await waitFor(() =>
    expect(api.search).toHaveBeenCalledWith(
      "coffee",
      expect.objectContaining({ mode: "hybrid" }),
      expect.any(AbortSignal),
    ),
  );
  blended.resolve({
    hits: [
      {
        ...note,
        id: "meaning-note",
        body: "A caffè in Milan",
        match: "meaning",
      },
    ],
    semantic: "on",
    next_cursor: null,
  });
  expect(await screen.findByText("A caffè in Milan")).toBeTruthy();
  expect(screen.queryByText(body("Coffee in Rome"))).toBeNull();
  expect(screen.getByText("≈ similar meaning")).toBeTruthy();
});
it("keeps keyword search when semantic service is unavailable", async () => {
  window.history.replaceState(null, "", "/search?q=coffee");
  vi.mocked(api.search).mockImplementation((_q, filters) =>
    Promise.resolve({
      hits: [note],
      semantic: filters?.mode === "hybrid" ? "unavailable" : "off",
      next_cursor: null,
    }),
  );
  wrap(<Search />);
  expect(
    await screen.findByText(
      "Similar meaning is unavailable. Showing keyword matches.",
    ),
  ).toBeTruthy();
  expect(screen.getByText(body("Coffee in Rome"))).toBeTruthy();
});
it("loads live related output through the core panel endpoint", async () => {
  const related: Plugin = {
    ...ask,
    id: "related",
    name: "Related",
    permissions: ["ai.embed", "mcp"],
    contributes: {
      panel: { mode: "live", route: "notes/{id}", title: "Related" },
    },
  };
  vi.mocked(api.manifest).mockResolvedValue({
    ui: 1,
    plugins_rev: 1,
    plugins: [related],
  });
  const panel = vi.spyOn(api, "pluginPanel").mockResolvedValue({
    ui: 1,
    items: [{ type: "notes", ids: ["neighbor"] }],
  });
  wrap(<NotePanel note={note} />);
  expect(await screen.findByText("Related · 1 item")).toBeTruthy();
  expect(panel).toHaveBeenCalledWith(
    "related",
    note.id,
    expect.any(AbortSignal),
  );
});
it("runs Ask forms and saves via the plugin action, preserving server provenance", async () => {
  vi.mocked(api.manifest).mockResolvedValue({
    ui: 1,
    plugins_rev: 1,
    plugins: [ask],
  });
  const create = vi.spyOn(api, "createNote");
  const command = vi
    .spyOn(api, "command")
    .mockImplementation(async (_plugin, id, args) => {
      if (id === "save-answer") return { type: "notes", ids: ["saved-answer"] };
      if (!args?.question)
        return {
          type: "form",
          schema: {
            type: "object",
            required: ["question"],
            properties: {
              question: {
                type: "string",
                title: "Question",
                minLength: 1,
                maxLength: 500,
              },
            },
          },
        };
      return {
        ui: 1,
        items: [
          {
            type: "markdown",
            text: "Coffee answer",
            html: "<p>Coffee answer</p>",
          },
          { type: "notes", ids: [note.id] },
          {
            type: "command",
            id: "save-answer",
            title: "Save as note",
            args: { answer_id: "receipt" },
          },
        ],
      };
    });
  wrap(<Palette onClose={() => {}} />);
  fireEvent.click(
    await screen.findByRole("button", {
      name: /Ask your notes/,
    }),
  );
  fireEvent.change(await screen.findByLabelText("Question"), {
    target: { value: "Where is coffee?" },
  });
  // The form submits with the command's own title, not a generic "Run".
  fireEvent.click(screen.getByRole("button", { name: "Ask your notes" }));
  expect(await screen.findByText("Coffee answer")).toBeTruthy();
  expect(command).toHaveBeenCalledWith("ask", "ask", {
    question: "Where is coffee?",
  });
  expect(screen.getAllByRole("button", { name: "Save as note" })).toHaveLength(
    1,
  );
  fireEvent.click(screen.getByRole("button", { name: "Save as note" }));
  await waitFor(() =>
    expect(command).toHaveBeenCalledWith("ask", "save-answer", {
      answer_id: "receipt",
    }),
  );
  expect(create).not.toHaveBeenCalled();
});

it("reports enabled AI on server status rather than the M3 off placeholder", async () => {
  vi.spyOn(api, "status").mockResolvedValue({
    version: "fixture",
    schema: 4,
    ai: {
      enabled: true,
      capabilities: ["ai.chat", "ai.embed"],
      health: [
        { profile: "local", capability: "embed", ok: true, dimension: 768 },
      ],
      indexing: ai.indexing,
      stats: [],
    },
  });
  wrap(<StatusPage />);
  expect(await screen.findByText("Chat · Embeddings")).toBeTruthy();
  expect(screen.getByText("4 embedding chunks · Indexing on")).toBeTruthy();
  expect(screen.queryByText("Off.")).toBeNull();
});

it("counts note references and hides an empty wrapped live panel", async () => {
  const related: Plugin = {
    ...ask,
    id: "related",
    name: "Related",
    permissions: ["ai.embed"],
    contributes: { panel: { mode: "live", title: "Related" } },
  };
  vi.mocked(api.manifest).mockResolvedValue({
    ui: 1,
    plugins_rev: 1,
    plugins: [related],
  });
  const panel = vi.spyOn(api, "pluginPanel").mockResolvedValue({
    ui: 1,
    items: [{ type: "notes", ids: ["one", "two"] }],
  });
  wrap(<NotePanel note={note} />);
  expect(await screen.findByText("Related · 2 items")).toBeTruthy();
  panel.mockResolvedValue({ ui: 1, items: [] });
  await client.invalidateQueries({ queryKey: ["plugin-panel"] });
  await waitFor(() =>
    expect(screen.queryByText("Related · 2 items")).toBeNull(),
  );
  expect(document.querySelector("summary")).toBeNull();
});

it("does not query AI live panels for private or machine-written notes", async () => {
  const related: Plugin = {
    ...ask,
    id: "related",
    name: "Related",
    permissions: ["ai.embed"],
    contributes: { panel: { mode: "live", title: "Related" } },
  };
  vi.mocked(api.manifest).mockResolvedValue({
    ui: 1,
    plugins_rev: 1,
    plugins: [related],
  });
  const panel = vi.spyOn(api, "pluginPanel");
  client.setQueryData(["plugin-panel", "related", note.id, note.version], {
    ui: 1,
    items: [{ type: "notes", ids: ["cached-neighbor"] }],
  });
  wrap(
    <>
      <NotePanel note={{ ...note, private: true }} />
      <NotePanel note={{ ...note, id: "machine-note", machine: true }} />
    </>,
  );
  await waitFor(() => expect(client.getQueryData(["manifest"])).toBeTruthy());
  expect(panel).not.toHaveBeenCalled();
  expect(document.querySelector("summary")).toBeNull();
});
