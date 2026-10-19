import { expect, test, type Page } from "@playwright/test";

test.use({ storageState: { cookies: [], origins: [] } });
const marker = "spunto:guide:v1";
const guide = (page: Page) =>
  page.getByRole("main", { name: "Getting started with Spunto" });

async function fixture(
  page: Page,
  { signedIn = true, setup = false, oidc = false } = {},
) {
  let authenticated = signedIn;
  const requests: { path: string; method: string }[] = [];
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    requests.push({ path, method: request.method() });
    const json = (body: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify(body),
      });
    if (path.endsWith("/meta"))
      return json({
        version: "fixture",
        setup_pending: setup,
        unencrypted: false,
        plugins_rev: 0,
        ai_capabilities: [],
      });
    if (path.endsWith("/auth/session"))
      return authenticated
        ? json({ owner_id: "guide-owner" })
        : json({ code: "unauthenticated" }, 401);
    if (path.endsWith("/auth/options"))
      return json({
        mode: oidc ? "oidc" : "local",
        ...(oidc
          ? { label: "Fixture SSO", login_url: "/api/v1/auth/oidc/start" }
          : {}),
      });
    if (path.endsWith("/auth/login")) {
      authenticated = true;
      return route.fulfill({ status: 204 });
    }
    if (path.endsWith("/auth/oidc/start")) {
      authenticated = true;
      return route.fulfill({
        contentType: "text/html",
        body: '<!doctype html><meta http-equiv="refresh" content="0;url=/">',
      });
    }
    if (path.endsWith("/settings"))
      return json({
        theme: "carta",
        hidden_views: [],
        hidden_mcp_tools: [],
        plugin_order: [],
      });
    if (path.endsWith("/plugins/manifest"))
      return json({ ui: 1, plugins_rev: 0, plugins: [] });
    if (path.endsWith("/labels")) return json({ labels: [], total: 0 });
    if (path.endsWith("/notes")) return json({ notes: [], next_cursor: null });
    if (path.endsWith("/search"))
      return json({ hits: [], next_cursor: null, semantic: "off" });
    if (path.endsWith("/changes/wait")) return route.abort("failed");
    return json({ code: "not_found" }, 404);
  });
  return requests;
}

test("first entry gates capture; keyboard navigation and Skip persist on reload", async ({
  page,
}) => {
  const requests = await fixture(page);
  await page.goto("/");
  await expect(guide(page)).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "A quiet place for loose ideas." }),
  ).toBeFocused();
  await expect(page.getByLabel("New note")).toHaveCount(0);
  await expect(page.getByRole("main")).toHaveCount(1);
  expect(requests.some(({ path }) => path.endsWith("/notes"))).toBe(false);
  for (const key of ["n", "/", "Control+k", "?", "g", "s"])
    await page.keyboard.press(key);
  await expect(guide(page)).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.evaluate(() =>
    window.dispatchEvent(
      new CustomEvent("spunto:command-result", { detail: "Synthetic result" }),
    ),
  );
  await page.keyboard.press("Tab");
  await expect(
    page.getByRole("button", { name: "Next", exact: true }),
  ).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(
    page.getByRole("heading", { name: "Labels live in your words." }),
  ).toBeFocused();
  await page.getByRole("button", { name: "Back", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "A quiet place for loose ideas." }),
  ).toBeFocused();
  await page.getByRole("button", { name: "Skip", exact: true }).click();
  await expect(page.getByLabel("New note")).toBeFocused();
  expect(await page.evaluate((key) => localStorage.getItem(key), marker)).toBe(
    "complete",
  );
  await page.reload();
  await expect(page.getByLabel("New note")).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(requests.filter(({ method }) => method !== "GET")).toEqual([]);
});

test("finishing all steps opens capture without creating examples or calling AI", async ({
  page,
}) => {
  const requests = await fixture(page);
  await page.goto("/");
  for (let step = 0; step < 3; step++)
    await page.getByRole("button", { name: "Next", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Your notes, across your devices." }),
  ).toBeFocused();
  await expect(page.getByText(/Android keeps a local copy/)).toBeVisible();
  expect(requests.some(({ path }) => /\/ai|\/notes|\/pair/.test(path))).toBe(
    false,
  );
  await page
    .getByRole("button", { name: "Start writing", exact: true })
    .click();
  await expect(page.getByLabel("New note")).toBeFocused();
  await expect(page.getByLabel("New note")).toHaveValue("");
  await page.reload();
  await expect(page.getByLabel("New note")).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  expect(requests.filter(({ method }) => method !== "GET")).toEqual([]);
});

test("Settings replay returns to its trigger on Skip and can finish at All notes", async ({
  page,
}) => {
  await fixture(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Skip", exact: true }).click();
  await page.goto("/settings/appearance");
  const replay = page.getByRole("button", { name: "Show guide", exact: true });
  await replay.click();
  await expect(guide(page)).toBeVisible();
  await expect(page.getByRole("main")).toHaveCount(1);
  await page.evaluate(() =>
    window.dispatchEvent(
      new CustomEvent("spunto:command-result", { detail: "Synthetic result" }),
    ),
  );
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("button", { name: "Next", exact: true }).click();
  await page.getByRole("button", { name: "Skip", exact: true }).click();
  await expect(page).toHaveURL(/\/settings\/appearance$/);
  await expect(replay).toBeFocused();
  expect(await page.evaluate((key) => localStorage.getItem(key), marker)).toBe(
    "complete",
  );
  await replay.click();
  await expect(
    page.getByRole("heading", { name: "A quiet place for loose ideas." }),
  ).toBeFocused();
  for (let step = 0; step < 3; step++)
    await page.getByRole("button", { name: "Next", exact: true }).click();
  await page
    .getByRole("button", { name: "Start writing", exact: true })
    .click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByLabel("New note")).toBeFocused();
});

for (const path of [
  "/search?q=book",
  "/l/reading/books",
  "/?from=sso",
  "/#return",
]) {
  test(`defers automatic guide for ${path} until normal All notes`, async ({
    page,
  }) => {
    await fixture(page);
    await page.goto(path);
    await expect(
      page.getByRole("link", { name: "All notes", exact: true }),
    ).toBeVisible();
    await expect(guide(page)).toHaveCount(0);
    expect(
      await page.evaluate((key) => localStorage.getItem(key), marker),
    ).toBeNull();
    await page.getByRole("link", { name: "All notes", exact: true }).click();
    await expect(guide(page)).toBeVisible();
  });
}

test("waits for password authentication before offering the guide", async ({
  page,
}) => {
  await fixture(page, { signedIn: false });
  await page.goto("/login");
  await expect(
    page.getByRole("heading", { name: "Unlock your Spunto" }),
  ).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
  await page
    .getByLabel("Password", { exact: true })
    .fill("synthetic guide passphrase");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(guide(page)).toBeVisible();
  await expect(page.getByLabel("New note")).toHaveCount(0);
});

for (const mode of ["setup", "sso"] as const) {
  test(`does not interrupt ${mode}`, async ({ page }) => {
    await fixture(page, {
      signedIn: false,
      setup: mode === "setup",
      oidc: mode === "sso",
    });
    await page.goto(mode === "setup" ? "/setup" : "/login?return=notes");
    if (mode === "setup")
      await expect(
        page.getByRole("heading", { name: "Create your home" }),
      ).toBeVisible();
    else
      await expect(
        page.getByRole("link", { name: "Continue with Fixture SSO" }),
      ).toBeVisible();
    await expect(guide(page)).toHaveCount(0);
    expect(
      await page.evaluate((key) => localStorage.getItem(key), marker),
    ).toBeNull();
  });
}

test("offers the guide only after the synthetic SSO round trip finishes", async ({
  page,
}) => {
  await fixture(page, { signedIn: false, oidc: true });
  await page.goto("/login");
  await expect(guide(page)).toHaveCount(0);
  await page.getByRole("link", { name: "Continue with Fixture SSO" }).click();
  await expect(guide(page)).toBeVisible();
  await expect(page.getByLabel("New note")).toHaveCount(0);
});

test("Skip remains usable when completion storage is blocked", async ({
  page,
}) => {
  await page.addInitScript(() => {
    const get = Storage.prototype.getItem;
    const set = Storage.prototype.setItem;
    Storage.prototype.getItem = function (key) {
      if (key === "spunto:guide:v1")
        throw new DOMException("Blocked", "SecurityError");
      return get.call(this, key);
    };
    Storage.prototype.setItem = function (key, value) {
      if (key === "spunto:guide:v1")
        throw new DOMException("Blocked", "SecurityError");
      set.call(this, key, value);
    };
  });
  await fixture(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Skip", exact: true }).click();
  await expect(page.getByLabel("New note")).toBeVisible();
  await page.getByRole("link", { name: "Archive", exact: true }).click();
  await page.getByRole("link", { name: "All notes", exact: true }).click();
  await expect(page.getByLabel("New note")).toBeVisible();
  await expect(guide(page)).toHaveCount(0);
});

test("four steps remain reachable at 320px and 200% text in all themes", async ({
  page,
}, info) => {
  await fixture(page);
  await page.setViewportSize({ width: 320, height: 700 });
  await page.goto("/");
  await expect(guide(page)).toBeVisible();
  await page.evaluate(() => {
    document.documentElement.style.fontSize = "200%";
  });
  expect(
    await page
      .getByRole("button", { name: "Skip", exact: true })
      .evaluate((button) => {
        const range = document.createRange();
        range.selectNodeContents(button);
        return range.getClientRects().length;
      }),
  ).toBe(1);
  for (const theme of ["carta", "ardesia", "salvia", "corallo", "elettrica"]) {
    for (const mode of ["light", "dark"]) {
      await page.evaluate(
        ({ theme, mode }) => {
          document.documentElement.dataset.theme = theme;
          document.documentElement.dataset.mode = mode;
        },
        { theme, mode },
      );
      await expect(page.locator("body")).toHaveJSProperty("scrollWidth", 320);
    }
  }
  for (let step = 0; step < 4; step++) {
    await expect(page.locator("body")).toHaveJSProperty("scrollWidth", 320);
    for (const button of await guide(page).getByRole("button").all()) {
      expect((await button.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    }
    await page.screenshot({
      path: info.outputPath(`guide-compact-text200-step${step + 1}.png`),
      fullPage: true,
    });
    await page
      .getByRole("button", {
        name: step === 3 ? "Start writing" : "Next",
        exact: true,
      })
      .click();
  }
  await expect(page.getByLabel("New note")).toBeVisible();
});
