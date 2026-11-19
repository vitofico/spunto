import { test, expect } from "@playwright/test";

test("mobile settings destinations and search controls stay reachable without horizontal scrolling", async ({
  page,
}, info) => {
  const note = {
    id: "0192f3c4-1111-7000-8000-000000000001",
    title: "Workshop plans",
    body: "# Workshop plans\nBring the sample cards and extra masking tape.",
    labels: [],
    version: 1,
    change_seq: 1,
    created_at: "2026-11-19T09:00:00Z",
    updated_at: "2026-11-19T09:00:00Z",
    source: { kind: "web" },
    origin: "owner",
    suggestions: [],
    annotations: [],
  };
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const json = (value: unknown) => route.fulfill({ json: value });
    if (path.endsWith("/changes/wait")) return;
    if (path.endsWith("/meta"))
      return json({
        setup_pending: false,
        plugins_rev: 1,
        ai_capabilities: [],
      });
    if (path.endsWith("/auth/session"))
      return json({
        owner_id: "layout-fixture",
        credential_id: "fixture-session",
      });
    if (path.endsWith("/settings"))
      return json({
        theme: "carta",
        plugin_order: [],
        hidden_views: [],
        hidden_mcp_tools: [],
      });
    if (path.endsWith("/plugins/manifest") || path.endsWith("/plugins"))
      return json({ ui: 1, plugins_rev: 1, plugins: [] });
    if (path.endsWith("/labels")) return json({ labels: [], total: 0 });
    if (path.endsWith("/credentials")) return json([]);
    if (path.endsWith("/search"))
      return json({
        hits: [
          note,
          {
            ...note,
            id: "0192f3c4-1111-7000-8000-000000000002",
            title: "Coaching session",
            body: "# Coaching session\nPrint the handouts.",
            match: "meaning",
          },
        ],
        semantic: "on",
        next_cursor: null,
      });
    return json({});
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/settings/devices");
  const nav = page.getByRole("navigation", { name: "Settings", exact: true });
  const chooser = nav.getByRole("button", { name: "Browse settings" });
  await expect(
    nav.getByRole("link", { name: "Data", exact: true }),
  ).toBeHidden();
  await chooser.focus();
  await page.keyboard.press("Enter");
  await expect(
    nav.getByRole("link", { name: "Devices", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  const data = nav.getByRole("link", { name: "Data", exact: true });
  await expect(data).toBeVisible();
  expect(
    await nav.evaluate((element) => element.scrollWidth <= element.clientWidth),
  ).toBe(true);
  await page.screenshot({
    path: info.outputPath("settings-chooser-mobile.png"),
  });
  await data.focus();
  await page.keyboard.press("Escape");
  await expect(chooser).toBeFocused();
  await page.keyboard.press("Enter");
  await data.click();
  await expect(page).toHaveURL(/\/settings\/data$/);
  await page.goBack();
  await expect(page).toHaveURL(/\/settings\/devices$/);
  await expect(chooser).toHaveAttribute("aria-expanded", "false");
  await page.setViewportSize({ width: 1280, height: 800 });
  await expect(chooser).toBeHidden();
  await expect(
    nav.getByRole("link", { name: "Data", exact: true }),
  ).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/search?q=workshop&archived=1");
  const filters = page
    .locator("summary")
    .filter({ hasText: /^Filters \(1\)$/ });
  await expect(filters).toBeVisible();
  await expect(page.getByLabel("From date")).toBeHidden();
  await expect(page.getByText("2 notes found")).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Workshop plans" }).locator("mark"),
  ).toHaveText("Workshop");
  await expect(
    page.getByText("Bring the sample cards and extra masking tape.", {
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.getByText(/similar meaning/i)).toHaveCount(1);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(390);
  await page.screenshot({ path: info.outputPath("search-results-mobile.png") });
  await filters.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByLabel("Include archive")).toBeChecked();
  await page.getByRole("button", { name: "Clear filters" }).click();
  await expect(page).toHaveURL(/\/search\?q=workshop$/);
  await expect(
    page.locator("summary").filter({ hasText: /^Filters$/ }),
  ).toBeFocused();
});
