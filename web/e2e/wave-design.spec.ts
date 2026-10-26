import { expect, test } from "@playwright/test";

test("the complete wordmark follows every theme accent, including the collapsed rail and custom accents", async ({
  page,
}) => {
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const json = (value: unknown) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify(value),
      });
    if (path.endsWith("/meta"))
      return json({
        version: "fixture",
        setup_pending: false,
        unencrypted: false,
        plugins_rev: 0,
        ai_capabilities: [],
      });
    if (path.endsWith("/auth/session"))
      return json({
        owner_id: "wave-fixture",
        credential_id: "fixture-session",
      });
    if (path.endsWith("/plugins/manifest"))
      return json({ ui: 1, plugins_rev: 0, plugins: [] });
    if (path.endsWith("/settings"))
      return json({
        theme: "carta",
        plugin_order: [],
        hidden_views: [],
        hidden_mcp_tools: [],
      });
    if (path.endsWith("/labels"))
      return json({ labels: [{ path: "ideas", count: 2 }], total: 1 });
    if (path.endsWith("/notes"))
      return json({
        notes: [
          {
            id: "0192f3c4-1111-7000-8000-000000000001",
            title: "A thought for later",
            body: "A thought for later\n\nThe best ideas often arrive in unrelated moments. Keep noticing...",
            html: "<h2>A thought for later</h2><p>The best ideas often arrive in unrelated moments. Keep noticing...</p>",
          },
          {
            id: "0192f3c4-1111-7000-8000-000000000002",
            title: "Weekend plans",
            body: "Weekend plans\n\n- [ ] Book the coastal train tickets\n- [ ] Look up breakfast spots",
            html: '<h2>Weekend plans</h2><ul><li><input type="checkbox" data-checkbox-offset="18"> Book the coastal train tickets</li><li><input type="checkbox" data-checkbox-offset="55"> Look up breakfast spots</li></ul>',
          },
        ].map((note) => ({
          ...note,
          labels: ["ideas"],
          version: 1,
          change_seq: 1,
          created_at: new Date().toISOString(),
          updated_at: new Date().toISOString(),
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
        })),
        next_cursor: null,
      });
    if (path.endsWith("/suggestions")) return json({ suggestions: [] });
    if (path.endsWith("/changes/wait")) return;
    return json({});
  });
  await page.goto("/settings/appearance");
  await expect(
    page.getByRole("heading", { name: "Appearance", exact: true }),
  ).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute(
    "data-background",
    "artwork",
  );
  const ambient = () =>
    page
      .locator(".app")
      .evaluate((element) => getComputedStyle(element, "::before").content);
  expect(await ambient()).not.toBe("none");
  await page.getByRole("button", { name: "Plain", exact: true }).click();
  await expect(page.locator("html")).toHaveAttribute(
    "data-background",
    "plain",
  );
  expect(await ambient()).toBe("none");
  await page.reload();
  await expect(
    page.getByRole("button", { name: "Plain", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "Artwork", exact: true }).click();
  await expect(page.locator("html")).toHaveAttribute(
    "data-background",
    "artwork",
  );
  const settingsSurface = page.locator("main section");
  const paper = await page.evaluate(() => {
    const probe = document.createElement("span");
    probe.style.background = "var(--paper)";
    document.body.append(probe);
    const value = getComputedStyle(probe).backgroundColor;
    probe.remove();
    return value;
  });
  await expect(settingsSurface.locator("..")).toHaveCSS(
    "background-color",
    paper,
  );
  await page.screenshot({
    path: test.info().outputPath("background-appearance-light.png"),
    animations: "disabled",
    fullPage: true,
  });
  await page.evaluate(() => {
    document.documentElement.dataset.mode = "dark";
  });
  await page.screenshot({
    path: test.info().outputPath("background-appearance-dark.png"),
    animations: "disabled",
    fullPage: true,
  });
  await page.evaluate(() => {
    document.documentElement.dataset.mode = "light";
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await expect(
    page.getByText("A thought for later", { exact: true }),
  ).toBeVisible();
  await page.locator("textarea").evaluate((element) => element.blur());
  expect(
    (await page.locator(".composer.compact").boundingBox())!.height,
  ).toBeLessThanOrEqual(80);
  await page.screenshot({
    path: test.info().outputPath("spunto-carta-populated-artwork.png"),
    fullPage: true,
    animations: "disabled",
  });
  for (const width of [320, 390]) {
    await page.setViewportSize({ width, height: 844 });
    for (const scale of ["100%", "200%"]) {
      await page.evaluate(
        (scale) => (document.documentElement.style.fontSize = scale),
        scale,
      );
      await expect(page.locator("body")).toHaveJSProperty("scrollWidth", width);
      await expect
        .poll(() =>
          page
            .locator("textarea")
            .evaluate(
              (element) => element.clientHeight >= element.scrollHeight,
            ),
        )
        .toBe(true);

      for (const element of [
        page.locator(".topbar .wordmark"),
        page.locator(".hero-search"),
        page.locator(".home-settings"),
      ]) {
        await expect(element).toBeVisible();
        const box = await element.boundingBox();
        expect(box!.x).toBeGreaterThanOrEqual(0);
        expect(box!.x + box!.width).toBeLessThanOrEqual(width);
      }
      await page.screenshot({
        path: test.info().outputPath(`home-${width}-${scale}.png`),
        fullPage: true,
        animations: "disabled",
      });
    }
  }
  await page.evaluate(() => (document.documentElement.style.fontSize = "100%"));
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto("/settings/appearance");
  await page.emulateMedia({ reducedMotion: "no-preference" });
  for (const width of [1280, 390]) {
    await page.setViewportSize({ width, height: 844 });
    for (const mode of ["light", "dark", "light"]) {
      const mismatches = await page.evaluate(
        async ({ width, mode }) => {
          const controls = document.querySelectorAll(
            width < 1024 ? ".topbar > .button" : ".rail-search",
          );
          document.documentElement.dataset.mode = mode;
          const start = performance.now();
          const mismatches = [];
          do {
            const paper = getComputedStyle(document.body).backgroundColor;
            for (const control of controls) {
              const background = getComputedStyle(control).backgroundColor;
              if (background !== paper)
                mismatches.push({ mode, width, background, paper });
            }
            await new Promise(requestAnimationFrame);
          } while (performance.now() - start < 160);
          return mismatches;
        },
        { width, mode },
      );
      expect(mismatches).toEqual([]);
    }
  }
  await page.setViewportSize({ width: 1280, height: 844 });
  const wordmark = page.locator(".rail-heading .wordmark");
  const dot = wordmark.locator("span:last-child");
  for (const theme of ["carta", "ardesia", "salvia", "corallo", "elettrica"]) {
    for (const mode of ["light", "dark"]) {
      await page.evaluate(
        ({ theme, mode }) => {
          document.documentElement.dataset.theme = theme;
          document.documentElement.dataset.mode = mode;
        },
        { theme, mode },
      );
      await expect(wordmark).toHaveText("spunto.");
      await expect(dot).toHaveCSS(
        "color",
        await page.evaluate(() => {
          const probe = document.createElement("span");
          probe.style.color = "var(--accent)";
          document.body.append(probe);
          const value = getComputedStyle(probe).color;
          probe.remove();
          return value;
        }),
      );
    }
  }
  await page.evaluate(() =>
    document.documentElement.style.setProperty("--accent", "rgb(43, 140, 120)"),
  );
  await expect(dot).toHaveCSS("color", "rgb(43, 140, 120)");
  await page.keyboard.press("[");
  await expect(page.locator(".app")).toHaveClass(/collapsed/);
  await expect(wordmark.locator(".wordmark-text")).toBeVisible();
  await expect(wordmark).toHaveText("spunto.");
  await expect(dot).toHaveCSS("color", "rgb(43, 140, 120)");
  await page.evaluate(() => {
    document.documentElement.style.fontSize = "200%";
  });
  await expect(wordmark).toHaveCSS("font-size", "30px");
  await expect
    .poll(() =>
      wordmark.evaluate((element) => {
        const rail = element.closest(".rail")!;
        const padding = parseFloat(getComputedStyle(rail).paddingRight);
        return (
          element.getBoundingClientRect().right + padding <=
          rail.getBoundingClientRect().right
        );
      }),
    )
    .toBe(true);
  await page.screenshot({
    path: test.info().outputPath("wave-collapsed-text-200.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator(".topbar .wordmark")).toHaveText("spunto.");
  await expect(page.locator(".topbar .wordmark span:last-child")).toHaveCSS(
    "color",
    "rgb(43, 140, 120)",
  );
  await expect(page.locator(".topbar .wordmark")).toHaveCSS(
    "font-size",
    "48px",
  );
  await expect
    .poll(() =>
      page.locator(".topbar .wordmark").evaluate((element) => {
        const bounds = element.getBoundingClientRect();
        const previous =
          element.previousElementSibling!.getBoundingClientRect();
        const next = element.nextElementSibling!.getBoundingClientRect();
        return bounds.left >= previous.right && bounds.right <= next.left;
      }),
    )
    .toBe(true);
  await expect(page.locator("body")).toHaveJSProperty("scrollWidth", 390);
  await page.screenshot({
    path: test.info().outputPath("wave-appearance-mobile.png"),
    fullPage: true,
  });
  await page.evaluate(() => {
    document.documentElement.dir = "rtl";
  });
  const mobileWordmark = page.locator(".topbar .wordmark");
  await expect(mobileWordmark).toHaveCSS("direction", "ltr");
  await expect
    .poll(() =>
      mobileWordmark.evaluate((element) => {
        const text = document.createRange();
        text.selectNodeContents(element.firstChild!);
        return (
          element.querySelector("span")!.getBoundingClientRect().left >=
          text.getBoundingClientRect().right - 1
        );
      }),
    )
    .toBe(true);
});
