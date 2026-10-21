import { test, expect, type Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { willSentence } from "../src/features/format";

// This suite uses the packaged app and real API. Fixture-only suites also run
// against Vite when working on individual components.
test.skip(
  !process.env.SPUNTO_E2E_URL,
  "Set SPUNTO_E2E_URL to the local server image",
);

async function login(page: Page) {
  await page.goto("/");
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.SPUNTO_E2E_PASSWORD ?? "browser acceptance password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByLabel("New note")).toBeVisible();
}

test("real server capture, image, checkbox, edit history, search and label rewrite", async ({
  page,
}, info) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await login(page);
  const suffix = Date.now().toString(36) + info.project.name;
  const label = "acceptance/" + suffix;
  const title = "Browser capture " + suffix;
  const body = title + " #" + label + "\n- [ ] 🎈 Read this";
  await page.getByLabel("New note").fill(body);
  // A pause preserves the capture without publishing it.
  await page.waitForTimeout(5300);
  await expect(page.getByLabel("New note")).toHaveValue(body);
  await page.locator('input[type="file"]').setInputFiles({
    name: "fixture.png",
    mimeType: "image/png",
    buffer: Buffer.from(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlSIAAAAASUVORK5CYII=",
      "base64",
    ),
  });
  await expect(page.getByLabel("New note")).toHaveValue(
    /!\[fixture\.png\]\(\/a\//,
  );
  await page.getByLabel("New note").press("Control+Enter");
  await expect(page.getByLabel("New note")).toHaveValue("");
  const card = page.locator("article.entry").filter({ hasText: title });
  await expect(card).toHaveCount(1);
  await expect(card.locator("img")).toBeVisible();
  await card.getByRole("checkbox").click();
  await expect(card.getByRole("checkbox")).toBeChecked();
  await card.getByRole("link", { name: new RegExp(", " + title) }).click();
  await page.getByRole("button", { name: "Edit", exact: true }).click();
  await expect(page.getByLabel("Edit note")).toHaveValue(
    /- \[x\] 🎈 Read this/,
  );
  const edited =
    (await page.getByLabel("Edit note").inputValue()) + "\nUpdated in browser";
  await page.getByLabel("Edit note").fill(edited);
  await page.getByLabel("Edit note").press("Control+s");
  await expect(page.getByText("Saved", { exact: true })).toBeVisible();
  await page.getByText("Revision history").click();
  await expect(
    page.getByRole("button", { name: "Restore", exact: true }).first(),
  ).toBeVisible();
  await page.goto("/search?q=" + encodeURIComponent(suffix));
  await expect(
    page.locator("article.entry").filter({ hasText: title }),
  ).toHaveCount(1);
  await expect(
    page
      .locator("article.entry")
      .filter({ hasText: title })
      .getByRole("checkbox"),
  ).toBeChecked();
  await page
    .locator("article.entry")
    .filter({ hasText: title })
    .getByRole("link", { name: "#" + label, exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp("/l/" + label));
  await page.getByLabel("Label actions").click();
  await page.getByRole("menuitem", { name: "Rename" }).click();
  await page.getByLabel("New name").fill(label + "renamed");
  await expect(
    page.getByText("Renames #" + label + " in 1 note.", { exact: false }),
  ).toBeVisible();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Rename", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(label + "renamed"));
  await expect(
    page.locator("article.entry").filter({ hasText: title }),
  ).toHaveCount(1);
  await page.screenshot({
    path: info.outputPath("real-label.png"),
    fullPage: true,
  });
  expect(errors).toEqual([]);
});

test("real plugin switches and all-off mobile layout survive reload", async ({
  page,
}, info) => {
  await login(page);
  type CatalogPlugin = {
    id: string;
    permissions: string[];
    permission_sentences: string[];
    granted: string[];
    switch: "on" | "off";
    state: string;
    pinned_by_env: boolean | 0 | 1;
  };
  const origin = new URL(process.env.SPUNTO_E2E_URL!).origin;
  async function catalog(): Promise<CatalogPlugin[]> {
    const response = await page.request.get("/api/v1/plugins");
    expect(response.status(), "Owner catalog request").toBe(200);
    return (await response.json()).plugins;
  }
  async function setSwitch(plugin: CatalogPlugin, on: boolean) {
    const response = await page.request.post(
      `/api/v1/plugins/${encodeURIComponent(plugin.id)}/${on ? "enable" : "disable"}`,
      {
        headers: { Origin: origin },
        ...(on ? { data: { permissions: plugin.granted } } : {}),
      },
    );
    expect(response.status(), `Restore/switch ${plugin.id}`).toBe(200);
  }
  // A fresh disposable CI instance has no Tasks grants. Prepare them before
  // capturing the exact state this test must restore through the owner API.
  const fixture = await catalog();
  const freshTasks = fixture.find((plugin) => plugin.id === "tasks");
  if (
    freshTasks?.state === "available" &&
    freshTasks.switch === "off" &&
    freshTasks.granted.length === 0
  ) {
    expect(
      ["localhost", "127.0.0.1", "[::1]", "host.docker.internal"],
      "Permission preparation must use a disposable local server",
    ).toContain(new URL(origin).hostname);
    expect([false, 0], "Tasks fixture must not be operator-pinned").toContain(
      freshTasks.pinned_by_env,
    );
    const prepared = await page.request.post("/api/v1/plugins/tasks/enable", {
      headers: { Origin: origin },
      data: { permissions: freshTasks.permissions },
    });
    expect(prepared.status(), "Prepare fresh Tasks fixture grants").toBe(200);
    await setSwitch(freshTasks, false);
  }
  const original = await catalog();
  const tasks = original.find((plugin) => plugin.id === "tasks");
  expect(tasks, "The disposable fixture must include Tasks").toBeDefined();
  if (!tasks) throw new Error("Tasks is missing from the fixture catalog");
  expect([...tasks.permissions].sort()).toEqual(["annotate", "notes.read"]);
  expect(tasks.permission_sentences.length).toBeGreaterThanOrEqual(
    tasks.permissions.length,
  );
  // The owner API cannot revoke grants. Pre-grant Tasks in this disposable
  // fixture so its UI permission acceptance can be undone without changing them.
  expect(
    tasks.granted,
    "Fixture setup: pre-grant Tasks' current permissions before this test",
  ).toEqual(tasks.permissions);
  // Host.catalog preserves SQLite's 0/1 flag; fixture-only catalog responses
  // may instead serialize booleans. Only these exact unlocked values are safe.
  expect([false, 0], "Tasks must be switchable in the fixture").toContain(
    tasks.pinned_by_env,
  );
  // A newly discovered Off plugin is Available; disable normalizes it to
  // Disabled and the owner API cannot undo that distinction. Fixture setup
  // must pre-grant then disable Tasks before this exact-state restoration test.
  expect(
    tasks.state,
    "Fixture setup: normalize Available/Off Tasks with disable first; require Enabled/On or Disabled/Off",
  ).toBe(tasks.switch === "on" ? "enabled" : "disabled");
  for (const plugin of original.filter((item) => item.switch === "on")) {
    expect(
      [false, 0],
      `Fixture setup: ${plugin.id} must not be operator-pinned on`,
    ).toContain(plugin.pinned_by_env);
    expect(
      [...plugin.granted].sort(),
      `Fixture setup: ${plugin.id} needs its current grants to restore wanted-on`,
    ).toEqual([...plugin.permissions].sort());
    expect(
      ["enabled", "waiting"],
      `Fixture setup: ${plugin.id} must not be a failed wanted-on card`,
    ).toContain(plugin.state);
  }
  try {
    if (tasks.switch === "on") await setSwitch(tasks, false);
    await page.goto("/settings/plugins");
    const toggle = page.getByRole("switch", {
      name: "Enable Tasks",
      exact: true,
    });
    await expect(toggle).not.toBeChecked();
    await toggle.click();
    const sheet = page.getByRole("dialog", { name: "Turn on Tasks?" });
    await expect(sheet).toBeVisible();
    // The server's sentences, reworded to follow "It will".
    await expect(sheet.locator("ul li")).toHaveText(
      tasks.permission_sentences.map(willSentence),
    );
    const [enableRequest] = await Promise.all([
      page.waitForRequest(
        (request) =>
          new URL(request.url()).pathname === "/api/v1/plugins/tasks/enable" &&
          request.method() === "POST",
      ),
      sheet.getByRole("button", { name: "Turn on", exact: true }).click(),
    ]);
    expect(enableRequest.postDataJSON()).toEqual({
      permissions: tasks.permissions,
    });
    await expect(toggle).toBeChecked();
    await page.reload();
    await expect(toggle).toBeChecked();
    await toggle.click();
    await expect(toggle).not.toBeChecked();
    const afterTasks = await catalog();
    expect(afterTasks.find((plugin) => plugin.id === "tasks")?.granted).toEqual(
      tasks.granted,
    );
    for (const plugin of afterTasks.filter((item) => item.switch === "on")) {
      await setSwitch(plugin, false);
    }
    expect((await catalog()).every((plugin) => plugin.switch === "off")).toBe(
      true,
    );
    await page.goto("/review");
    await expect(
      page.getByText("All caught up.", { exact: true }),
    ).toBeVisible();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/");
    await expect(page.getByLabel("New note")).toBeVisible();
    await expect(page.locator("body")).toHaveJSProperty("scrollWidth", 390);
    await expect(page.getByText("Review", { exact: true })).toHaveCount(0);
    await page.screenshot({
      path: info.outputPath("real-mobile-off.png"),
      fullPage: true,
    });
  } finally {
    const current = await catalog();
    const failures: string[] = [];
    for (const plugin of original) {
      const live = current.find((item) => item.id === plugin.id);
      if (!live || live.switch === plugin.switch) continue;
      try {
        await setSwitch(plugin, plugin.switch === "on");
      } catch {
        failures.push(plugin.id);
      }
    }
    expect(
      failures,
      "Fixture state restoration failed for these plugins",
    ).toEqual([]);
    const restored = await catalog();
    expect(
      restored.map(({ id, switch: wanted, state, granted }) => ({
        id,
        wanted,
        state,
        granted,
      })),
    ).toEqual(
      original.map(({ id, switch: wanted, state, granted }) => ({
        id,
        wanted,
        state,
        granted,
      })),
    );
  }
});

test("production server applies device theme cookie before paint", async ({
  page,
}) => {
  await login(page);
  await page.context().addCookies([
    {
      name: "theme",
      value: encodeURIComponent(
        JSON.stringify({
          theme: "salvia",
          mode: "dark",
          reading: "sans",
          size: "l",
        }),
      ),
      url: process.env.SPUNTO_E2E_URL!,
    },
  ]);
  const response = await page.goto("/settings/appearance");
  const html = await response!.text();
  expect(html).toContain('data-theme="salvia"');
  expect(html).toContain('data-mode="dark"');
  expect(html).toContain('data-reading="sans"');
  expect(html).toContain('data-size="l"');
  await expect(page.locator("html")).toHaveAttribute("data-theme", "salvia");
});

test("long label titles fit a compact screen", async ({ page }) => {
  await login(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/l/layout/" + "x".repeat(110));
  await expect(page.getByLabel("Label actions")).toBeVisible();
  await expect(page.locator("body")).toHaveJSProperty("scrollWidth", 390);
  const heading = await page.locator(".label-title h1").boundingBox();
  const actions = await page.getByLabel("Label actions").boundingBox();
  expect(
    heading!.y + heading!.height <= actions!.y ||
      heading!.x + heading!.width <= actions!.x,
  ).toBe(true);
});

test("real owner profile retains identity and exports portable Markdown", async ({
  page,
}, info) => {
  const origin = new URL(process.env.SPUNTO_E2E_URL!).origin;
  expect(
    ["127.0.0.1", "localhost", "[::1]"],
    "Use a disposable loopback server",
  ).toContain(new URL(origin).hostname);
  await login(page);
  const originalResponse = await page.request.get("/api/v1/settings");
  expect(originalResponse.ok()).toBe(true);
  const original = await originalResponse.json();
  const sessionResponse = await page.request.get("/api/v1/auth/session");
  expect(sessionResponse.ok()).toBe(true);
  const session = await sessionResponse.json();
  const suffix = Date.now().toString(36) + info.project.name;
  const displayName = "Synthetic Owner " + suffix;
  const body =
    "Portable export " +
    suffix +
    "\nOwner-authored Markdown #export/fixture\n- [ ] Keep the original words.";
  try {
    // A single-owner install has no display name field; the setting stays in the API.
    await page.goto("/settings/profile");
    await expect(
      page.getByRole("heading", { name: "Password", exact: true }),
    ).toBeVisible();
    await expect(page.getByLabel("Display name (optional)")).toHaveCount(0);
    const saved = await page.request.put("/api/v1/settings", {
      headers: { Origin: origin },
      data: { owner_display_name: displayName },
    });
    expect(saved.ok()).toBe(true);
    expect((await saved.json()).owner_display_name).toBe(displayName);
    const after = await page.request.get("/api/v1/auth/session");
    expect(await after.json()).toEqual(session);
    await page.goto("/");
    await page.getByLabel("New note").fill(body);
    await page.getByLabel("New note").press("Control+Enter");
    await expect(page.getByLabel("New note")).toHaveValue("");
    await page.goto("/settings/data");
    const downloadEvent = page.waitForEvent("download");
    await page
      .getByRole("button", { name: "Export notes", exact: true })
      .click();
    const download = await downloadEvent;
    expect(download.suggestedFilename()).toBe("spunto-export.zip");
    const destination = info.outputPath("spunto-export.zip");
    await download.saveAs(destination);
    const checked = execFileSync(
      "python3",
      [
        "-c",
        [
          "import sys,zipfile,json",
          "with zipfile.ZipFile(sys.argv[1]) as archive:",
          " assert archive.testzip() is None",
          " documents=[archive.read(name).decode('utf-8') for name in archive.namelist() if name.endswith('.md')]",
          " assert any(sys.argv[2] in document for document in documents)",
          " print(json.dumps({'markdown_documents':len(documents),'fixture_present':True}))",
        ].join("\n"),
        destination,
        body,
      ],
      { encoding: "utf8" },
    );
    expect(JSON.parse(checked).fixture_present).toBe(true);
  } finally {
    const restored = await page.request.put("/api/v1/settings", {
      headers: { Origin: origin },
      data: { owner_display_name: original.owner_display_name ?? "" },
    });
    expect(
      restored.ok(),
      "Restore only this fixture's original display name",
    ).toBe(true);
  }
});
