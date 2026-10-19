import { test, expect } from "@playwright/test";

// This opt-in suite needs the synthetic river-ink and broken-pack operator fixtures.
test.skip(
  !process.env.SPUNTO_E2E_URL || !process.env.SPUNTO_E2E_THEME_PACKS,
  "Set the packaged fixture URL and install the synthetic theme packs",
);

test("installed colour pack and accent survive reload, device override and reset", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/");
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.SPUNTO_E2E_PASSWORD ?? "browser acceptance password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByLabel("New note")).toBeVisible();
  const originalResponse = await page.request.get("/api/v1/settings");
  expect(originalResponse.ok()).toBe(true);
  const original = await originalResponse.json();
  try {
    await page.goto("/settings/appearance");
    await expect(
      page.getByRole("button", { name: /Broken Pack/ }),
    ).toBeDisabled();
    await page.getByRole("button", { name: /River Ink/ }).click();
    await expect(page.locator("html")).toHaveAttribute(
      "data-theme",
      "river-ink",
    );
    await page.getByRole("button", { name: "Forest", exact: true }).click();
    await expect(page.locator("html")).toHaveAttribute("data-accent", "forest");
    await page.getByRole("button", { name: "Dark", exact: true }).click();
    await page.reload();
    await expect(page.locator("html")).toHaveAttribute(
      "data-theme",
      "river-ink",
    );
    await expect(page.locator("html")).toHaveAttribute("data-accent", "forest");
    await expect(page.locator("html")).toHaveAttribute("data-mode", "dark");
    await expect(
      page.locator(
        'link[rel="stylesheet"][href^="/api/v1/themes/river-ink.css?accent=forest"]',
      ),
    ).toHaveCount(1);
    const palette = await page.evaluate(() => {
      const style = getComputedStyle(document.documentElement);
      return {
        paper: style.getPropertyValue("--paper").trim(),
        accent: style.getPropertyValue("--accent").trim(),
      };
    });
    expect(palette.paper).toMatch(/^#[0-9a-f]{6}$/i);
    expect(palette.accent).toMatch(/^#[0-9a-f]{6}$/i);
    await page
      .getByRole("switch", { name: "Use a different theme on this device" })
      .click();
    await page.getByRole("button", { name: /Corallo/ }).click();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "corallo");
    const unchangedInstance = await (
      await page.request.get("/api/v1/settings")
    ).json();
    expect(unchangedInstance).toMatchObject({
      theme: "river-ink",
      theme_accent: "forest",
    });
    await page
      .getByRole("switch", { name: "Use a different theme on this device" })
      .click();
    await expect(page.locator("html")).toHaveAttribute(
      "data-theme",
      "river-ink",
    );
    await expect(page.locator("html")).toHaveAttribute("data-accent", "forest");
    const cookie = (await page.context().cookies()).find(
      (item) => item.name === "theme",
    );
    expect(JSON.parse(decodeURIComponent(cookie!.value))).toEqual({
      mode: "dark",
    });
    await page.getByRole("button", { name: "Sans", exact: true }).click();
    await page.getByRole("button", { name: "Large · 19", exact: true }).click();
    await page.reload();
    await expect(page.locator("html")).toHaveAttribute("data-reading", "sans");
    await expect(page.locator("html")).toHaveAttribute("data-size", "l");
    expect(errors).toEqual([]);
  } finally {
    const restored = await page.request.put("/api/v1/settings", {
      headers: { Origin: new URL(process.env.SPUNTO_E2E_URL!).origin },
      data: {
        theme: original.theme,
        theme_accent: original.theme_accent ?? null,
      },
    });
    expect(restored.ok()).toBe(true);
  }
});
