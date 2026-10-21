import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  cleanup,
  render,
  screen,
  fireEvent,
  waitFor,
  within,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { api } from "../src/lib/api";
import { SettingsPage } from "../src/features/Settings";
import {
  EntryMeta,
  NotePanel,
  ReviewLine,
  SuggestionLine,
} from "../src/features/Slots";
import type { Note, Plugin } from "../src/lib/types";
const plugin = {
  id: "tasks",
  name: "Tasks",
  version: "1.0.0",
  description: "Collect notes with checkboxes.",
  category: "organize",
  kind: "server",
  permissions: ["notes.read", "annotate"],
  position: 0,
  switch: "off",
  state: "available",
  data_count: 2,
  data_size_bytes: 200,
  contributes: {
    views: [{ id: "tasks", title: "Tasks", filter: { open_tasks: true } }],
  },
} as Plugin;
const imagePlugin: Plugin = {
  ...plugin,
  id: "image-text",
  name: "Text in images",
  category: "capture",
  description: "Make synthetic image captures searchable.",
  permissions: ["attachments.read", "ai.vision", "search.text"],
  ai_route: { vision: "vision-override" },
  ai_destinations: {
    vision: {
      profile: "vision-override",
      host: "vision.synthetic.test",
      model: "fixture-vision",
      leaves_network: false,
    },
  },
};
const visionFact =
  "Vision: fixture-vision at vision.synthetic.test · On your network";
let client: QueryClient;
beforeEach(() => {
  client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  vi.stubGlobal(
    "matchMedia",
    vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
  HTMLDialogElement.prototype.showModal = function () {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function () {
    this.open = false;
  };
  vi.spyOn(api, "catalog").mockResolvedValue({
    plugins: [plugin],
    plugins_rev: 1,
  });
  vi.spyOn(api, "settings").mockResolvedValue({
    theme: "carta",
    plugin_order: ["tasks"],
    hidden_views: [],
    hidden_mcp_tools: [],
  });
});
afterEach(() => {
  cleanup();
  client.clear();
  vi.restoreAllMocks();
});
const wrap = (child: React.ReactNode) =>
  render(<QueryClientProvider client={client}>{child}</QueryClientProvider>);
describe("plugin lifecycle controls", () => {
  it("uses the unavailable-plugin fallback for an old importer link without requesting its settings or routes", async () => {
    const settings = vi.spyOn(api, "pluginSettings");
    const route = vi.spyOn(api, "pluginRoute");
    wrap(<SettingsPage section="plugins/blinko-import" />);
    expect(
      await screen.findByRole("heading", { name: "Plugin unavailable" }),
    ).toBeTruthy();
    expect(settings).not.toHaveBeenCalled();
    expect(route).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Queue import" })).toBeNull();
  });
  it("keeps Markdown import and export in Data without the retired import flow", async () => {
    vi.mocked(api.catalog).mockResolvedValue({
      plugins: [
        {
          ...plugin,
          id: "markdown-import",
          name: "Import Markdown",
          state: "enabled",
          switch: "on",
        },
      ],
      plugins_rev: 1,
    });
    vi.spyOn(api, "status").mockResolvedValue({
      database: { trashed_notes: 0 },
    });
    const exporting = vi
      .spyOn(api, "exportNotes")
      .mockRejectedValue(new Error("Synthetic export failure"));
    wrap(<SettingsPage section="data" />);
    expect(await screen.findByLabelText("Markdown ZIP")).toBeTruthy();
    expect(screen.queryByText(/Blinko/)).toBeNull();
    expect(document.querySelector('a[href*="blinko-import"]')).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Export notes" }));
    expect(await screen.findByRole("alert")).toHaveProperty(
      "textContent",
      "Synthetic export failure",
    );
    expect(exporting).toHaveBeenCalledOnce();
    expect(
      screen.getByRole("link", { name: "Open Trash" }).getAttribute("href"),
    ).toBe("/trash");
  });
  it("shows the configured plugin route in the catalog and permission dialog while off", async () => {
    vi.mocked(api.catalog).mockResolvedValue({
      plugins: [imagePlugin],
      plugins_rev: 1,
    });
    const ai = vi.spyOn(api, "ai").mockRejectedValue(new Error("Unavailable"));
    const enable = vi.spyOn(api, "enablePlugin").mockResolvedValue({
      ...imagePlugin,
      switch: "on",
      state: "enabled",
    });
    wrap(<SettingsPage section="plugins" />);
    expect(
      await screen.findByText(`Origin unknown · ${visionFact}`),
    ).toBeTruthy();
    const control = screen.getByRole("switch", {
      name: "Enable Text in images",
    });
    expect((control as HTMLInputElement).checked).toBe(false);
    expect((control as HTMLInputElement).disabled).toBe(false);
    expect(screen.queryByText(/AI is not configured/)).toBeNull();
    expect(ai).not.toHaveBeenCalled();
    fireEvent.click(control);
    const dialog = await screen.findByRole("dialog", {
      name: "Turn on Text in images?",
    });
    expect(within(dialog).getByText(visionFact)).toBeTruthy();
    expect(enable).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole("button", { name: "Turn on" }));
    await waitFor(() =>
      expect(enable).toHaveBeenCalledWith(
        "image-text",
        imagePlugin.permissions,
      ),
    );
  });
  it("keeps configured destination facts in plugin details when AI status fails", async () => {
    vi.mocked(api.catalog).mockResolvedValue({
      plugins: [
        {
          ...imagePlugin,
          ai_destinations: {
            vision: {
              profile: "vision-override",
              host: null,
              model: "fixture-vision",
              leaves_network: true,
            },
          },
        },
      ],
      plugins_rev: 1,
    });
    vi.spyOn(api, "pluginSettings").mockResolvedValue({
      schema: { type: "object", properties: {} },
      values: {},
      secrets: {},
    });
    vi.spyOn(api, "ai").mockRejectedValue(
      new Error("AI status is unavailable"),
    );
    wrap(<SettingsPage section="plugins/image-text" />);
    expect(
      await screen.findByText(
        "Vision: fixture-vision at vision-override · Outside your network",
        { exact: false },
      ),
    ).toBeTruthy();
    expect(await screen.findByRole("alert")).toHaveProperty(
      "textContent",
      "AI status is unavailable",
    );
    expect(screen.queryByText(/AI is not configured/)).toBeNull();
  });
  it.each(["waiting", "failed"])(
    "describes a configured destination independently of a %s plugin state",
    async (state) => {
      vi.mocked(api.catalog).mockResolvedValue({
        plugins: [{ ...imagePlugin, state, switch: "on" }],
        plugins_rev: 1,
      });
      wrap(<SettingsPage section="plugins" />);
      expect(
        await screen.findByText(visionFact, { exact: false }),
      ).toBeTruthy();
      expect(
        (
          screen.getByRole("switch", {
            name: "Enable Text in images",
          }) as HTMLInputElement
        ).checked,
      ).toBe(true);
      expect(screen.queryByText(/AI is not configured/)).toBeNull();
    },
  );
  it("names only the missing capability when other AI models are configured", async () => {
    vi.mocked(api.catalog).mockResolvedValue({
      plugins: [
        { ...imagePlugin, ai_destinations: {} },
        {
          ...plugin,
          id: "voice-memos",
          name: "Voice memos",
          permissions: ["ai.transcribe"],
          ai_destinations: {},
        },
        {
          ...plugin,
          id: "tidy",
          name: "Tidy",
          permissions: ["ai.chat"],
          ai_destinations: {
            chat: {
              profile: "chat",
              model: "fixture-chat",
              host: "chat.synthetic.test",
              leaves_network: false,
            },
          },
        },
        {
          ...plugin,
          id: "describe",
          name: "Describe",
          permissions: ["ai.chat", "ai.vision"],
          ai_destinations: {
            chat: {
              profile: "chat",
              model: "fixture-chat",
              host: "chat.synthetic.test",
              leaves_network: false,
            },
          },
        },
      ],
      plugins_rev: 1,
    });
    wrap(<SettingsPage section="plugins" />);
    expect(
      await screen.findByText("Origin unknown · Needs a vision model"),
    ).toBeTruthy();
    expect(
      screen.getByText("Origin unknown · Needs a transcription model"),
    ).toBeTruthy();
    expect(
      screen.getByText(
        "Origin unknown · Chat: fixture-chat at chat.synthetic.test · On your network",
      ),
    ).toBeTruthy();
    expect(
      screen.getByText(
        "Chat: fixture-chat at chat.synthetic.test · On your network; Vision: no vision model is set up",
        { exact: false },
      ),
    ).toBeTruthy();
    expect(screen.queryByText(/AI is off/)).toBeNull();
    fireEvent.click(
      screen.getByRole("switch", { name: "Enable Text in images" }),
    );
    const dialog = await screen.findByRole("dialog", {
      name: "Turn on Text in images?",
    });
    expect(within(dialog).getByText("Needs a vision model")).toBeTruthy();
  });
  it("uses neutral model requirements when a legacy catalog omits destinations", async () => {
    vi.mocked(api.catalog).mockResolvedValue({
      plugins: [{ ...plugin, permissions: ["ai.chat", "ai.embed"] }],
      plugins_rev: 1,
    });
    wrap(<SettingsPage section="plugins" />);
    expect(
      await screen.findByText(
        "Origin unknown · Needs a chat model and an embedding model",
      ),
    ).toBeTruthy();
    expect(
      screen.queryByText(/not configured|no model is configured/),
    ).toBeNull();
  });
  it("keeps the catalog usable when a network plugin has no configured destination", async () => {
    const unconfigured = {
      ...plugin,
      id: "network-fixture",
      name: "Network fixture",
      category: "data",
      permissions: ["net.out:<configured>"],
      network_destinations: [null, ""],
    };
    vi.mocked(api.catalog).mockResolvedValue({
      plugins: [plugin, unconfigured],
      plugins_rev: 1,
    });
    wrap(<SettingsPage section="plugins" />);
    expect(
      await screen.findByRole("switch", { name: "Enable Tasks" }),
    ).toBeTruthy();
    expect(
      screen.getByText(
        "Origin unknown · Connects to the configured server · Never reads private notes",
      ),
    ).toBeTruthy();
  });
  it("does not enable until the owner accepts the permission dialog", async () => {
    const enable = vi
      .spyOn(api, "enablePlugin")
      .mockResolvedValue({ ...plugin, switch: "on", state: "enabled" });
    wrap(<SettingsPage section="plugins" />);
    fireEvent.click(
      await screen.findByRole("switch", { name: "Enable Tasks" }),
    );
    expect(enable).not.toHaveBeenCalled();
    expect(
      await screen.findByRole("dialog", { name: "Turn on Tasks?" }),
    ).toBeTruthy();
    expect(screen.getByText("Reads your notes")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Turn on" }));
    await waitFor(() =>
      expect(enable).toHaveBeenCalledWith("tasks", ["notes.read", "annotate"]),
    );
  });
  it("environment-pinned plugins provide no editable switch", async () => {
    vi.mocked(api.catalog).mockResolvedValue({
      plugins: [{ ...plugin, pinned_by_env: true }],
      plugins_rev: 1,
    });
    wrap(<SettingsPage section="plugins" />);
    expect(
      await screen.findByText("Set in the environment · Off"),
    ).toBeTruthy();
    expect(screen.queryByRole("switch")).toBeNull();
  });
  it("data deletion names the count and preserves notes before submitting", async () => {
    vi.spyOn(api, "pluginSettings").mockResolvedValue({
      schema: { type: "object", properties: {} },
      values: {},
      secrets: {},
    });
    const remove = vi.spyOn(api, "deletePluginData").mockResolvedValue();
    wrap(<SettingsPage section="plugins/tasks" />);
    fireEvent.click(
      await screen.findByRole("button", { name: "Delete this plugin’s data…" }),
    );
    expect(remove).not.toHaveBeenCalled();
    expect(
      await screen.findByRole("dialog", { name: "Delete Tasks’s data?" }),
    ).toBeTruthy();
    expect(screen.getByText(/This deletes 2 plugin records/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Delete 2 records" }));
    await waitFor(() => expect(remove).toHaveBeenCalledWith("tasks"));
  });
});
describe("quiet slots", () => {
  it("draws no output or placeholder chrome while plugins are off", () => {
    client.setQueryData(["manifest"], { ui: 1, plugins_rev: 1, plugins: [] });
    const note = {
      id: "n",
      suggestions: [
        {
          id: "s",
          plugin: "tasks",
          status: "pending",
          payload: { path: "task" },
          action: "add_label",
        },
      ],
      annotations: [
        {
          id: "a",
          plugin: "tasks",
          kind: "meta",
          payload: { type: "text", text: "2 of 5 done" },
        },
      ],
    } as unknown as Note;
    const { container } = wrap(
      <>
        <ReviewLine />
        <SuggestionLine note={note} />
        <EntryMeta note={note} />
        <NotePanel note={note} />
      </>,
    );
    expect(container.childElementCount).toBe(0);
  });
  it("keeps one suggestion row with three pills and an explicit overflow control", async () => {
    client.setQueryData(["manifest"], {
      ui: 1,
      plugins_rev: 1,
      plugins: [
        { ...plugin, state: undefined },
        { ...plugin, id: "another", position: 1, state: undefined },
      ],
    });
    const suggestions = Array.from({ length: 6 }, (_, i) => ({
      id: String(i),
      plugin: i < 3 ? "tasks" : "another",
      status: "pending",
      action: "add_label",
      payload: { path: `label${i}` },
      reason: "",
    }));
    const note = { id: "n", suggestions, annotations: [] } as unknown as Note;
    wrap(<SuggestionLine note={note} />);
    expect(screen.getAllByRole("button", { name: /^#label/ })).toHaveLength(3);
    fireEvent.click(screen.getByRole("button", { name: "+3" }));
    expect(screen.getAllByRole("button", { name: /^#label/ })).toHaveLength(6);
  });
});

describe("review and result safety", () => {
  it("waits for the server’s nested-label impact count before enabling Apply", async () => {
    const { ReviewPage } = await import("../src/features/Review");
    vi.spyOn(api, "manifest").mockResolvedValue({
      ui: 1,
      plugins_rev: 1,
      plugins: [{ ...plugin, state: undefined }],
    });
    vi.spyOn(api, "suggestions").mockResolvedValue({
      suggestions: [
        {
          id: "label-change",
          plugin: "tasks",
          note_id: null,
          note_version: null,
          action: "label_change",
          payload: { old: "project", new: "work" },
          reason: "Join the labels",
          status: "pending",
          confidence: null,
          change_seq: 1,
          created_at: "2026-10-14T08:00:00Z",
          decided_at: null,
        },
      ],
    });
    let resolve: (value: { notes: number }) => void = () => {};
    vi.spyOn(api, "request").mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done;
        }) as never,
    );
    wrap(<ReviewPage />);
    fireEvent.click(await screen.findByRole("button", { name: "Compare" }));
    const apply = screen.getByRole("button", { name: "Apply" });
    expect((apply as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("Counting affected notes…")).toBeTruthy();
    resolve({ notes: 12 });
    await waitFor(() =>
      expect(
        screen.getByText("12 notes will change, including nested labels."),
      ).toBeTruthy(),
    );
    expect((apply as HTMLButtonElement).disabled).toBe(false);
    expect(api.request).toHaveBeenCalledWith("/api/v1/labels/impact", {
      method: "POST",
      body: { old: "project", new: "work" },
    });
  });
  it("retries an uncertain Save as note with the same idempotency key", async () => {
    const { CommandResult } = await import("../src/features/Slots");
    const create = vi
      .spyOn(api, "createNote")
      .mockRejectedValueOnce(new Error("Connection interrupted"))
      .mockResolvedValueOnce({ id: "new" } as Note);
    wrap(<CommandResult value={{ type: "text", text: "Keep this result" }} />);
    fireEvent.click(screen.getByRole("button", { name: "Save as note" }));
    await screen.findByRole("alert");
    fireEvent.click(screen.getByRole("button", { name: "Save as note" }));
    await screen.findByRole("button", { name: "Saved as note" });
    expect(create).toHaveBeenCalledTimes(2);
    expect(create.mock.calls[0][1]).toBe(create.mock.calls[1][1]);
    expect(create.mock.calls[0][0]).toBe("Keep this result");
  });
  it("holds the one-time token outside QueryClient mutation data", async () => {
    vi.spyOn(api, "credentials").mockResolvedValue([]);
    vi.spyOn(api, "createToken").mockResolvedValue({
      id: "pat",
      token: "test_one_time_secret",
    });
    wrap(<SettingsPage section="tokens" />);
    fireEvent.click(screen.getByRole("button", { name: "Create token" }));
    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Test agent" },
    });
    fireEvent.click(screen.getAllByRole("button", { name: "Create token" })[1]);
    await screen.findByText("test_one_time_secret");
    expect(
      JSON.stringify(
        client
          .getMutationCache()
          .getAll()
          .map((m) => m.state.data),
      ),
    ).not.toContain("test_one_time_secret");
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(screen.queryByText("test_one_time_secret")).toBeNull();
  });
});

describe("live instance appearance", () => {
  it("applies a refreshed instance theme from the settings cache on every route", async () => {
    const { useInstanceAppearance } =
      await import("../src/features/Appearance");
    const Observer = () => {
      useInstanceAppearance(true);
      return null;
    };
    document.cookie = "theme=; Max-Age=0; Path=/";
    client.setQueryData(["settings"], {
      theme: "carta",
      plugin_order: [],
      hidden_views: [],
      hidden_mcp_tools: [],
    });
    wrap(<Observer />);
    await waitFor(() =>
      expect(document.documentElement.dataset.theme).toBe("carta"),
    );
    client.setQueryData(["settings"], {
      theme: "salvia",
      plugin_order: [],
      hidden_views: [],
      hidden_mcp_tools: [],
    });
    await waitFor(() =>
      expect(document.documentElement.dataset.theme).toBe("salvia"),
    );
  });
  it("keeps this device’s theme and mode when another device changes the instance", async () => {
    const { useInstanceAppearance } =
      await import("../src/features/Appearance");
    const Observer = () => {
      useInstanceAppearance(true);
      return null;
    };
    document.cookie = `theme=${encodeURIComponent(JSON.stringify({ theme: "ardesia", mode: "dark", reading: "sans", size: "l" }))}; Path=/`;
    client.setQueryData(["settings"], {
      theme: "carta",
      plugin_order: [],
      hidden_views: [],
      hidden_mcp_tools: [],
    });
    wrap(<Observer />);
    client.setQueryData(["settings"], {
      theme: "salvia",
      plugin_order: [],
      hidden_views: [],
      hidden_mcp_tools: [],
    });
    await waitFor(() =>
      expect(document.documentElement.dataset.theme).toBe("ardesia"),
    );
    expect(document.documentElement.dataset.mode).toBe("dark");
    expect(document.documentElement.dataset.reading).toBe("sans");
    expect(document.documentElement.dataset.size).toBe("l");
    document.cookie = "theme=; Max-Age=0; Path=/";
  });
});
