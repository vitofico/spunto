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
import { api } from "../src/lib/api";
import type { Plugin } from "../src/lib/types";
import { SettingsPage } from "../src/features/Settings";
import { ToastProvider } from "../src/components/core/Toast";

const removed: Plugin = {
  id: "retained-demo",
  name: "Retained demo",
  version: "1",
  category: "data",
  kind: "server",
  description: "Synthetic retained plugin",
  permissions: ["notes.read", "suggest", "ai.chat"],
  permission_sentences: [
    "Reads your notes",
    "Proposes edits for your review",
    "Requires the chat model",
    "Never reads private notes",
  ],
  position: 0,
  origin: "operator",
  source_kind: "folder",
  state: "removed",
  switch: "off",
  can_enable: false,
  can_delete_data: true,
  data_count: 9,
  data_counts: {
    plugin_kv: 2,
    plugin_record: 1,
    annotation: 3,
    suggestion: 2,
    note_text: 1,
  },
  data_size_bytes: 250,
  last_run_at: 1800000000000,
  last_error: "plugin_removed",
};
const installed: Plugin = {
  ...removed,
  id: "tasks",
  name: "Tasks",
  category: "organize",
  permissions: ["notes.read", "annotate"],
  permission_sentences: ["Reads your notes", "Adds note information"],
  origin: undefined,
  state: "available",
  can_enable: undefined,
  can_delete_data: undefined,
};
const staleSettings = {
  schema: {
    type: "object",
    properties: { legacy_value: { type: "string", title: "Old setting" } },
  },
  values: { legacy_value: "Synthetic old value" },
  secrets: {},
};
let client: QueryClient;
function catalog(plugins: Plugin[]) {
  return { plugins, plugins_rev: 7 };
}
function renderPage(section: string) {
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <SettingsPage section={section} />
      </ToastProvider>
    </QueryClientProvider>,
  );
}
function publish(plugins: Plugin[]) {
  act(() => client.setQueryData(["catalog"], catalog(plugins)));
}
beforeEach(() => {
  client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: Infinity },
      mutations: { retry: false },
    },
  });
  vi.spyOn(api, "catalog").mockResolvedValue(catalog([removed]));
  vi.spyOn(api, "settings").mockResolvedValue({
    theme: "carta",
    plugin_order: [removed.id],
    hidden_views: [],
    hidden_mcp_tools: [],
  });
  vi.spyOn(api, "pluginSettings").mockResolvedValue(staleSettings);
  vi.spyOn(api, "ai").mockRejectedValue(
    new Error("No AI query is expected for Removed code"),
  );
  vi.spyOn(api, "command").mockResolvedValue({});
  vi.spyOn(api, "enablePlugin").mockResolvedValue({
    ...installed,
    state: "enabled",
    switch: "on",
  });
  vi.spyOn(api, "disablePlugin").mockResolvedValue({
    ...installed,
    state: "disabled",
    switch: "off",
  });
  vi.spyOn(api, "deletePluginData").mockResolvedValue();
  HTMLDialogElement.prototype.showModal = function () {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function () {
    this.open = false;
  };
});
afterEach(() => {
  cleanup();
  client.clear();
  vi.restoreAllMocks();
});

it("waits for catalog eligibility and never requests Removed or unknown settings", async () => {
  let resolve!: (value: ReturnType<typeof catalog>) => void;
  vi.mocked(api.catalog).mockReturnValue(
    new Promise((done) => {
      resolve = done;
    }),
  );
  const view = renderPage(`plugins/${removed.id}`);
  expect(screen.getByText("Loading plugin settings…")).toBeTruthy();
  expect(api.pluginSettings).not.toHaveBeenCalled();
  await act(async () => resolve(catalog([removed])));
  expect(
    await screen.findByRole("heading", { name: removed.name }),
  ).toBeTruthy();
  expect(api.pluginSettings).not.toHaveBeenCalled();
  expect(api.ai).not.toHaveBeenCalled();
  view.rerender(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <SettingsPage section="plugins/unknown-id" />
      </ToastProvider>
    </QueryClientProvider>,
  );
  expect(
    await screen.findByRole("heading", { name: "Plugin unavailable" }),
  ).toBeTruthy();
  expect(api.pluginSettings).not.toHaveBeenCalled();
});

it("retains historical facts and count while hiding cached settings, AI and commands", async () => {
  const stale = {
    ...removed,
    pinned_by_env: 1 as const,
    contributes: {
      commands: [
        { id: "old-command", title: "Old command", where: ["palette"] },
      ],
    },
  };
  client.setQueryData(["catalog"], catalog([stale]));
  client.setQueryData(["plugin-settings", removed.id], staleSettings);
  renderPage(`plugins/${removed.id}`);
  expect(await screen.findByText("Set in the environment · Off")).toBeTruthy();
  expect(screen.getByText(/Operator-installed/)).toBeTruthy();
  expect(screen.getByRole("heading", { name: "Access" })).toBeTruthy();
  expect(screen.getByText("Reads your notes")).toBeTruthy();
  expect(screen.getByText(/plugin_removed/)).toBeTruthy();
  expect(screen.getByText(/9 records · 250 bytes/)).toBeTruthy();
  expect(screen.queryByRole("switch")).toBeNull();
  expect(screen.queryByRole("textbox", { name: "Old setting" })).toBeNull();
  expect(screen.queryByRole("heading", { name: "AI" })).toBeNull();
  expect(screen.queryByRole("heading", { name: "Run" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Old command" })).toBeNull();
  expect(api.pluginSettings).not.toHaveBeenCalled();
  expect(api.ai).not.toHaveBeenCalled();
  expect(api.command).not.toHaveBeenCalled();
  fireEvent.click(
    screen.getByRole("button", { name: "Delete this plugin’s data…" }),
  );
  const dialog = await screen.findByRole("dialog", {
    name: "Delete Retained demo’s data?",
  });
  expect(within(dialog).getByText(/Its notes stay/)).toBeTruthy();
  expect(api.deletePluginData).not.toHaveBeenCalled();
  fireEvent.click(
    within(dialog).getByRole("button", { name: "Delete 9 records" }),
  );
  await waitFor(() =>
    expect(api.deletePluginData).toHaveBeenCalledWith(removed.id),
  );
});

it.each([undefined, false, null, "true"])(
  "does not offer Removed cleanup without strict true metadata (%s)",
  async (flag) => {
    vi.mocked(api.catalog).mockResolvedValue(
      catalog([{ ...removed, can_delete_data: flag as boolean }]),
    );
    renderPage(`plugins/${removed.id}`);
    expect(
      await screen.findByRole("heading", { name: removed.name }),
    ).toBeTruthy();
    expect(
      screen.queryByRole("button", { name: "Delete this plugin’s data…" }),
    ).toBeNull();
    expect(api.deletePluginData).not.toHaveBeenCalled();
  },
);

it("labels actual origin without guessing from IDs and shows uncategorized Removed cards", async () => {
  const missingCategory = {
    ...removed,
    category: undefined as unknown as string,
  };
  vi.mocked(api.catalog).mockResolvedValue(
    catalog([
      {
        ...installed,
        id: "built-fixture",
        name: "Bundled fixture",
        origin: "bundled",
      },
      installed,
      missingCategory,
    ]),
  );
  renderPage("plugins");
  expect(await screen.findByText("Removed · Off")).toBeTruthy();
  expect(screen.queryByText(/Built-in/)).toBeNull();
  expect(screen.getByText(/^Origin unknown · /)).toBeTruthy();
  expect(
    screen.getByText("Operator-installed · Plugin code is no longer installed"),
  ).toBeTruthy();
  expect(
    screen.getByRole("link", { name: removed.name }).getAttribute("href"),
  ).toBe(`/settings/plugins/${removed.id}`);
  expect(screen.queryByRole("link", { name: "Bundled fixture" })).toBeNull();
  expect(
    screen.queryByRole("switch", { name: `Enable ${removed.name}` }),
  ).toBeNull();
});

it("keeps legacy installed permission approval and settings behavior", async () => {
  vi.mocked(api.catalog).mockResolvedValue(catalog([installed]));
  renderPage("plugins");
  fireEvent.click(await screen.findByRole("switch", { name: "Enable Tasks" }));
  expect(api.enablePlugin).not.toHaveBeenCalled();
  const dialog = await screen.findByRole("dialog", { name: "Turn on Tasks?" });
  fireEvent.click(within(dialog).getByRole("button", { name: "Turn on" }));
  await waitFor(() =>
    expect(api.enablePlugin).toHaveBeenCalledWith(
      "tasks",
      installed.permissions,
    ),
  );
});

it("drops a pending enable dialog when its catalog record becomes Removed", async () => {
  vi.mocked(api.catalog).mockResolvedValue(catalog([installed]));
  renderPage("plugins");
  fireEvent.click(await screen.findByRole("switch", { name: "Enable Tasks" }));
  expect(
    await screen.findByRole("dialog", { name: "Turn on Tasks?" }),
  ).toBeTruthy();
  publish([
    {
      ...removed,
      id: installed.id,
      name: installed.name,
      category: installed.category,
    },
  ]);
  await waitFor(() =>
    expect(screen.queryByRole("dialog", { name: "Turn on Tasks?" })).toBeNull(),
  );
  expect(screen.queryByRole("switch", { name: "Enable Tasks" })).toBeNull();
  expect(api.enablePlugin).not.toHaveBeenCalled();
});

it("refuses Undo once a disabled plugin becomes Removed", async () => {
  vi.mocked(api.catalog).mockResolvedValue(
    catalog([{ ...installed, state: "enabled", switch: "on" }]),
  );
  renderPage("plugins");
  fireEvent.click(await screen.findByRole("switch", { name: "Enable Tasks" }));
  await waitFor(() => expect(api.disablePlugin).toHaveBeenCalledWith("tasks"));
  publish([{ ...installed, state: "disabled", switch: "off" }]);
  expect(await screen.findByText("Tasks is off")).toBeTruthy();
  const undo = screen.getByRole("button", { name: "Undo" });
  publish([
    {
      ...removed,
      id: installed.id,
      name: installed.name,
      category: installed.category,
    },
  ]);
  fireEvent.click(undo);
  expect(
    await screen.findByText(
      "That plugin switch is unavailable. Review its current permissions.",
    ),
  ).toBeTruthy();
  expect(api.enablePlugin).not.toHaveBeenCalled();
});

it("retires a previously open cleanup confirmation when metadata forbids it", async () => {
  client.setQueryData(["catalog"], catalog([removed]));
  renderPage(`plugins/${removed.id}`);
  fireEvent.click(
    await screen.findByRole("button", { name: "Delete this plugin’s data…" }),
  );
  expect(
    await screen.findByRole("dialog", { name: "Delete Retained demo’s data?" }),
  ).toBeTruthy();
  publish([{ ...removed, can_delete_data: false }]);
  await waitFor(() =>
    expect(
      screen.queryByRole("dialog", { name: "Delete Retained demo’s data?" }),
    ).toBeNull(),
  );
  expect(api.deletePluginData).not.toHaveBeenCalled();
});

it("keeps installed legacy detail settings and cleanup available", async () => {
  vi.mocked(api.catalog).mockResolvedValue(catalog([installed]));
  renderPage("plugins/tasks");
  expect(await screen.findByDisplayValue("Synthetic old value")).toBeTruthy();
  expect(api.pluginSettings).toHaveBeenCalledWith("tasks");
  expect(screen.getByRole("switch", { name: "Enable Tasks" })).toBeTruthy();
  expect(
    screen.getByRole("button", { name: "Delete this plugin’s data…" }),
  ).toBeTruthy();
  expect(screen.getByText(/Origin unknown/)).toBeTruthy();
});

it("does not offer Undo for an actual toggle receipt that remains On", async () => {
  vi.mocked(api.catalog).mockResolvedValue(
    catalog([{ ...installed, state: "enabled", switch: "on" }]),
  );
  vi.mocked(api.disablePlugin).mockResolvedValue({
    ...installed,
    state: "enabled",
    switch: "on",
    plugins_rev: 8,
  });
  renderPage("plugins");
  fireEvent.click(await screen.findByRole("switch", { name: "Enable Tasks" }));
  await waitFor(() => expect(api.disablePlugin).toHaveBeenCalledWith("tasks"));
  publish([{ ...installed, state: "disabled", switch: "off" }]);
  expect(screen.queryByRole("button", { name: "Undo" })).toBeNull();
  expect(api.enablePlugin).not.toHaveBeenCalled();
});

it.each([
  ["grants were cleared", { granted: [] }],
  [
    "permissions changed",
    {
      permissions: [...installed.permissions, "ai.chat"],
      granted: installed.permissions,
    },
  ],
])(
  "does not restore unknown permission grants through Undo after %s",
  async (_, change) => {
    vi.mocked(api.catalog).mockResolvedValue(
      catalog([
        {
          ...installed,
          state: "enabled",
          switch: "on",
          granted: installed.permissions,
        },
      ]),
    );
    renderPage("plugins");
    fireEvent.click(
      await screen.findByRole("switch", { name: "Enable Tasks" }),
    );
    await waitFor(() =>
      expect(api.disablePlugin).toHaveBeenCalledWith("tasks"),
    );
    publish([
      {
        ...installed,
        state: "disabled",
        switch: "off",
        granted: installed.permissions,
      },
    ]);
    const undo = await screen.findByRole("button", { name: "Undo" });
    publish([{ ...installed, state: "disabled", switch: "off", ...change }]);
    fireEvent.click(undo);
    expect(
      await screen.findByText(
        "That plugin switch is unavailable. Review its current permissions.",
      ),
    ).toBeTruthy();
    expect(api.enablePlugin).not.toHaveBeenCalled();
  },
);

it("Undo turns a plugin back on while its grants still stand", async () => {
  vi.mocked(api.catalog).mockResolvedValue(
    catalog([
      {
        ...installed,
        state: "enabled",
        switch: "on",
        granted: installed.permissions,
      },
    ]),
  );
  renderPage("plugins");
  fireEvent.click(await screen.findByRole("switch", { name: "Enable Tasks" }));
  await waitFor(() => expect(api.disablePlugin).toHaveBeenCalledWith("tasks"));
  publish([
    {
      ...installed,
      state: "disabled",
      switch: "off",
      granted: installed.permissions,
    },
  ]);
  fireEvent.click(await screen.findByRole("button", { name: "Undo" }));
  await waitFor(() =>
    expect(api.enablePlugin).toHaveBeenCalledWith(
      "tasks",
      installed.permissions,
    ),
  );
});

it("does not expose stale Removed view or MCP arrangement actions", async () => {
  const stale = {
    ...removed,
    contributes: {
      views: [
        { id: "old-view", title: "Old view", filter: { open_tasks: true } },
      ],
      mcp_tools: [{ name: "old-tool", description: "Synthetic old tool" }],
    },
  };
  vi.mocked(api.catalog).mockResolvedValue(catalog([stale]));
  renderPage("plugins");
  expect(await screen.findByText("Removed · Off")).toBeTruthy();
  expect(
    screen.queryByRole("checkbox", { name: "Show Old view in the rail" }),
  ).toBeNull();
  expect(screen.queryByText("Expose", { exact: false })).toBeNull();
});

function capturedClick(button: HTMLElement): () => void {
  // Invoke the actual already-rendered callback even after React removes its stale control.
  const key = Object.keys(button).find((name) =>
    name.startsWith("__reactProps$"),
  );
  if (!key) throw new Error("Rendered React callback is unavailable");
  const props = (button as unknown as Record<string, { onClick: () => void }>)[
    key
  ];
  return props.onClick;
}

it("explicit permission approval still grants a replacement intentionally", async () => {
  vi.mocked(api.catalog).mockResolvedValue(
    catalog([{ ...installed, granted: [], can_enable: true }]),
  );
  renderPage("plugins");
  fireEvent.click(await screen.findByRole("switch", { name: "Enable Tasks" }));
  const dialog = await screen.findByRole("dialog", { name: "Turn on Tasks?" });
  expect(within(dialog).getByText("Reads your notes")).toBeTruthy();
  fireEvent.click(within(dialog).getByRole("button", { name: "Turn on" }));
  await waitFor(() =>
    expect(api.enablePlugin).toHaveBeenCalledWith(
      "tasks",
      installed.permissions,
    ),
  );
});

it.each([undefined, null, -1, 1.5, NaN, Infinity, "9"])(
  "refuses a malformed cleanup count (%s)",
  async (count) => {
    vi.mocked(api.catalog).mockResolvedValue(
      catalog([{ ...removed, data_count: count as number }]),
    );
    renderPage(`plugins/${removed.id}`);
    fireEvent.click(
      await screen.findByRole("button", { name: "Delete this plugin’s data…" }),
    );
    const dialog = await screen.findByRole("dialog", {
      name: "Delete Retained demo’s data?",
    });
    const confirm = within(dialog).getByRole("button", {
      name: "Delete … records",
    });
    expect((confirm as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(confirm);
    expect(api.deletePluginData).not.toHaveBeenCalled();
  },
);

it("refuses a captured cleanup callback after the current count becomes malformed", async () => {
  client.setQueryData(["catalog"], catalog([removed]));
  renderPage(`plugins/${removed.id}`);
  fireEvent.click(
    await screen.findByRole("button", { name: "Delete this plugin’s data…" }),
  );
  const dialog = await screen.findByRole("dialog", {
    name: "Delete Retained demo’s data?",
  });
  const confirm = capturedClick(
    within(dialog).getByRole("button", { name: "Delete 9 records" }),
  );
  publish([{ ...removed, data_count: null as unknown as number }]);
  act(() => confirm());
  expect(await within(dialog).findByRole("alert")).toBeTruthy();
  expect(api.deletePluginData).not.toHaveBeenCalled();
});
