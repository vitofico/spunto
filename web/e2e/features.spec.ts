import { test, expect, type Page } from "@playwright/test";
const note = {
  id: "0192f3c4-1111-7000-8000-000000000001",
  body: "A page to keep #ideas",
  title: "A page to keep",
  labels: ["ideas"],
  version: 1,
  change_seq: 1,
  created_at: "2026-10-14T08:30:00Z",
  updated_at: "2026-10-14T08:30:00Z",
  pinned: false,
  archived: false,
  trashed: false,
  private: false,
  open_tasks: 0,
  source: { kind: "web", ref: null, url: null },
  origin: "owner",
  machine: false,
  conflict_of: null,
  suggestions: [],
  annotations: [],
  html: '<p>A page to keep <a href="/l/ideas">#ideas</a></p>',
};
const plugin = {
  id: "resurface",
  name: "Resurface",
  version: "1.0.0",
  description: "Show one older note each day.",
  category: "recall",
  kind: "server",
  permissions: ["notes.read", "annotate", "schedule"],
  position: 0,
  switch: "off",
  state: "available",
  data_count: 7,
  data_size_bytes: 100,
  permission_sentences: [
    "Reads your notes",
    "Adds note information",
    "Runs scheduled jobs",
  ],
  contributes: { slip: { title: "From your notes" } },
  last_error: null,
};
async function fixture(page: Page, activeReview = false) {
  let enabled = activeReview;
  const pending = activeReview
    ? [
        {
          id: "rename",
          plugin: "resurface",
          note_id: null,
          note_version: null,
          action: "label_change",
          payload: { old: "ideas", new: "thoughts" },
          reason: "Bring the labels together",
          status: "pending",
        },
        {
          id: "label-one",
          plugin: "resurface",
          note_id: note.id,
          note_version: 1,
          action: "add_label",
          payload: { path: "notebook" },
          reason: "A note to keep",
          status: "pending",
        },
        {
          id: "label-two",
          plugin: "resurface",
          note_id: note.id,
          note_version: 1,
          action: "add_label",
          payload: { path: "journal" },
          reason: "A fresh thought",
          status: "pending",
        },
      ]
    : [];
  let releaseImpact: () => void = () => {};
  const decisions: { id: string; action: string }[] = [];
  let enabledCalls = 0;
  let deleted = false;
  let tokenBody: unknown;
  let preferences = {
    theme: "carta",
    plugin_order: ["resurface"],
    hidden_views: [],
    hidden_mcp_tools: [],
  };
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (data: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify(data),
      });
    if (path.endsWith("/changes/wait")) return route.abort("failed");
    if (path.endsWith("/meta"))
      return json({
        version: "test",
        api_version: 1,
        setup_pending: false,
        public_url: "http://127.0.0.1:5173",
        mcp_url: "http://127.0.0.1:5173/mcp",
        unencrypted: false,
        plugins_rev: enabled ? 1 : 0,
      });
    if (path.endsWith("/auth/session"))
      return json({
        owner_id: "feature-owner",
        credential_id: "session",
        password_managed: false,
      });
    if (path.endsWith("/plugins/manifest"))
      return json({
        ui: 1,
        plugins_rev: enabled ? 1 : 0,
        plugins: enabled
          ? [{ ...plugin, state: undefined, switch: undefined }]
          : [],
      });
    if (path.endsWith("/plugins"))
      return json({
        plugins: [
          {
            ...plugin,
            state: enabled ? "enabled" : "available",
            switch: enabled ? "on" : "off",
            data_count: deleted ? 0 : 7,
          },
        ],
        plugins_rev: 1,
      });
    if (path.endsWith("/enable")) {
      enabled = true;
      enabledCalls++;
      return json({ ...plugin, state: "enabled", switch: "on" });
    }
    if (path.endsWith("/disable")) {
      enabled = false;
      return json({ ...plugin, state: "available", switch: "off" });
    }
    if (path.endsWith("/resurface/data")) {
      deleted = true;
      enabled = false;
      return route.fulfill({ status: 204 });
    }
    if (path.endsWith("/resurface/settings"))
      return json({
        schema: {
          type: "object",
          properties: {
            time: {
              type: "string",
              format: "time",
              title: "Time",
              default: "08:00",
            },
            token: {
              type: "string",
              title: "Provider token",
              "x-secret": true,
            },
          },
        },
        values: { time: "08:00" },
        secrets: { token: { set: false, source: "environment" } },
      });
    if (path.endsWith("/settings")) {
      if (request.method() === "PUT")
        preferences = { ...preferences, ...request.postDataJSON() };
      return json(preferences);
    }
    if (path.endsWith("/credentials")) return json([]);
    if (path.endsWith("/tokens")) {
      tokenBody = request.postDataJSON();
      return json(
        { id: "token", token: "spunto_test_only_one_time_value" },
        201,
      );
    }
    if (path.endsWith("/labels"))
      return json({
        labels: [{ key: "ideas", path: "ideas", count: 1 }],
        total: 1,
      });
    if (path.endsWith("/labels/impact")) {
      await new Promise<void>((resolve) => {
        releaseImpact = resolve;
      });
      return json({ notes: 2 });
    }
    if (path.includes("/suggestions/") && request.method() === "POST") {
      const parts = path.split("/");
      const id = parts.at(-2)!;
      const action = parts.at(-1)!;
      decisions.push({ id, action });
      const index = pending.findIndex((item) => item.id === id);
      if (index >= 0) pending.splice(index, 1);
      return json({ applied: action === "accept" });
    }
    if (path.endsWith("/suggestions")) return json({ suggestions: pending });
    if (path.endsWith("/slip")) return json({ slip: null });
    if (path.endsWith("/notes"))
      return json({
        notes: [
          {
            ...note,
            suggestions: pending.filter((s) => s.note_id === note.id),
          },
        ],
        next_cursor: null,
      });
    if (path.includes("/notes/"))
      return json({
        ...note,
        suggestions: pending.filter((s) => s.note_id === note.id),
      });
    return json({ code: "not_found" }, 404);
  });
  return {
    enableCalls: () => enabledCalls,
    deleted: () => deleted,
    tokenBody: () => tokenBody,
    releaseImpact: () => releaseImpact(),
    decisions: () => decisions,
  };
}
test("Data retains Markdown import and export while old importer links are unavailable", async ({
  page,
}) => {
  await fixture(page);
  const requests: string[] = [];
  page.on("request", (request) =>
    requests.push(new URL(request.url()).pathname),
  );
  const json = (data: unknown) => ({
    contentType: "application/json",
    body: JSON.stringify(data),
  });
  await page.route("**/api/v1/plugins", (route) =>
    route.fulfill(
      json({
        plugins: [
          {
            ...plugin,
            id: "markdown-import",
            name: "Import Markdown",
            category: "data",
            state: "enabled",
            switch: "on",
          },
        ],
        plugins_rev: 1,
      }),
    ),
  );
  await page.route("**/api/v1/status", (route) =>
    route.fulfill(json({ database: { trashed_notes: 0 } })),
  );
  await page.route("**/api/v1/export", (route) =>
    route.fulfill({
      contentType: "application/zip",
      body: Buffer.from("504b0506000000000000000000000000000000000000", "hex"),
    }),
  );
  await page.goto("/settings/data");
  await expect(page.getByLabel("Markdown ZIP")).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Import Markdown", exact: true }),
  ).toBeVisible();
  await expect(page.getByText(/Blinko/)).toHaveCount(0);
  await expect(page.locator('a[href*="blinko-import"]')).toHaveCount(0);
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export notes", exact: true }).click();
  expect((await download).suggestedFilename()).toBe("spunto-export.zip");
  await page.goto("/settings/plugins/blinko-import");
  await expect(
    page.getByRole("heading", { name: "Plugin unavailable", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Queue import" })).toHaveCount(
    0,
  );
  expect(
    requests.filter((path) => path.startsWith("/api/v1/plugins/blinko-import")),
  ).toEqual([]);
});

test("permission sheet gates enable; Escape cancels; delete dialog names records and retained notes", async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto("/settings/plugins");
  await page.getByRole("switch", { name: "Enable Resurface" }).click();
  await expect(
    page.getByRole("dialog", { name: "Turn on Resurface?" }),
  ).toBeVisible();
  expect(state.enableCalls()).toBe(0);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(state.enableCalls()).toBe(0);
  await page.getByRole("switch", { name: "Enable Resurface" }).click();
  await page.getByRole("button", { name: "Turn on", exact: true }).click();
  await expect.poll(state.enableCalls).toBe(1);
  await page.goto("/settings/plugins/resurface");
  await expect(page.getByText("SPUNTO_PLUGIN_RESURFACE_TOKEN")).toBeVisible();
  await expect(
    page.getByRole("textbox", { name: "Provider token" }),
  ).toHaveCount(0);
  await page
    .getByRole("button", { name: "Delete this plugin’s data…" })
    .click();
  await expect(page.getByRole("dialog")).toContainText("7 plugin records");
  await expect(page.getByRole("dialog")).toContainText(
    "Its notes stay: they are notes.",
  );
  expect(state.deleted()).toBe(false);
  await page.getByRole("button", { name: "Delete 7 records" }).click();
  await expect.poll(state.deleted).toBe(true);
});
test("tokens default to read and excluded private/external text; one-time secret stays out of browser storage", async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto("/settings/tokens");
  await page.getByRole("button", { name: "Create token", exact: true }).click();
  await page.getByLabel("Name", { exact: true }).fill("Notebook agent");
  await expect(page.getByLabel("Scope")).toHaveValue("read");
  await expect(page.getByLabel("Include private notes")).not.toBeChecked();
  await expect(
    page.getByLabel("Include external-origin notes"),
  ).not.toBeChecked();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Create token", exact: true })
    .click();
  await expect(
    page.getByRole("dialog", { name: "Copy your token now" }),
  ).toBeVisible();
  await expect(page.getByText("spunto_test_only_one_time_value")).toBeVisible();
  expect(state.tokenBody()).toMatchObject({
    scope: "read",
    private_ok: false,
    external_ok: false,
  });
  expect(
    await page.evaluate(() =>
      JSON.stringify({
        local: { ...localStorage },
        session: { ...sessionStorage },
        cookie: document.cookie,
      }),
    ),
  ).not.toContain("spunto_test_only_one_time_value");
  await page.getByRole("button", { name: "Done", exact: true }).click();
  await expect(page.getByText("spunto_test_only_one_time_value")).toHaveCount(
    0,
  );
  await page.reload();
  await expect(page.getByText("spunto_test_only_one_time_value")).toHaveCount(
    0,
  );
});
test("appearance offers live theme cards and persists a device override separately", async ({
  page,
}) => {
  await fixture(page);
  await page.goto("/settings/appearance");
  await page.getByRole("button", { name: /Ardesia/ }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "ardesia");
  await page
    .getByRole("switch", { name: "Use a different theme on this device" })
    .click();
  await page.getByRole("button", { name: /Salvia/ }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "salvia");
  await expect(page.getByText("Other devices use Ardesia.")).toBeVisible();
  const cookies = await page.context().cookies();
  expect(
    JSON.parse(
      decodeURIComponent(cookies.find((c) => c.name === "theme")!.value),
    ),
  ).toMatchObject({ theme: "salvia", mode: "system" });
  await page.getByRole("button", { name: "Dark", exact: true }).click();
  await expect(page.locator("html")).toHaveAttribute("data-mode", "dark");
});

test("Review waits for impact, applies and dismisses, and hides disabled plugin output", async ({
  page,
}) => {
  const state = await fixture(page, true);
  await page.goto("/");
  await expect(page.getByText("3 suggestions ·")).toBeVisible();
  await page.getByRole("link", { name: "Review", exact: true }).click();
  await expect(page.getByText("3 waiting")).toBeVisible();
  await page.getByRole("button", { name: "Compare", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "#ideas → #thoughts" });
  await expect(dialog).toBeVisible();
  await expect(
    dialog.getByRole("button", { name: "Apply", exact: true }),
  ).toBeDisabled();
  await expect(dialog.getByText("Counting affected notes…")).toBeVisible();
  expect(state.decisions()).toEqual([]);
  state.releaseImpact();
  await expect(dialog).toContainText(
    "2 notes will change, including nested labels.",
  );
  await dialog.getByRole("button", { name: "Apply", exact: true }).click();
  await expect(page.getByText("2 waiting")).toBeVisible();
  expect(state.decisions()).toEqual([{ id: "rename", action: "accept" }]);
  await page
    .getByRole("button", { name: "Dismiss", exact: true })
    .first()
    .click();
  await expect(page.getByText("1 waiting")).toBeVisible();
  expect(state.decisions()).toHaveLength(2);
  await page.goto("/");
  await expect(
    page.getByRole("link", { name: "Review", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "#journal", exact: true }),
  ).toBeVisible();
  await page.goto("/settings/plugins");
  await page.getByRole("switch", { name: "Enable Resurface" }).click();
  await expect(
    page.getByRole("switch", { name: "Enable Resurface" }),
  ).not.toBeChecked();
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "#journal", exact: true }),
  ).toHaveCount(0);
  await page.goto("/review");
  await expect(page.getByText("All caught up.")).toBeVisible();
});
