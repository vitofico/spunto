import { expect, test } from "@playwright/test";
import { backgroundStyleKey } from "../src/lib/background-style";

for (const mode of ["light", "dark"] as const) {
  for (const style of ["artwork", "plain"] as const) {
    test(`auth uses ${style} alone in ${mode} appearance`, async ({
      page,
    }, info) => {
      await page.setViewportSize({ width: 390, height: 844 });
      await page.emulateMedia({ colorScheme: mode });
      await page.addInitScript(
        ({ key, value }) => localStorage.setItem(key, value),
        { key: backgroundStyleKey, value: style },
      );
      await page.route("**/api/v1/**", async (route) => {
        const path = new URL(route.request().url()).pathname;
        const value = path.endsWith("/meta")
          ? {
              version: "auth-artwork-fixture",
              setup_pending: false,
              plugins_rev: 0,
              ai_capabilities: [],
            }
          : path.endsWith("/auth/options")
            ? { mode: "local" }
            : { code: "unauthenticated" };
        await route.fulfill({
          status:
            path.endsWith("/meta") || path.endsWith("/auth/options")
              ? 200
              : 401,
          contentType: "application/json",
          body: JSON.stringify(value),
        });
      });
      await page.goto("/");
      const password = page.getByLabel("Password", { exact: true });
      await expect(password).toBeVisible();
      await expect(password).toBeEditable();
      await expect(
        page.getByRole("button", { name: "Sign in", exact: true }),
      ).toBeVisible();
      const layers = await page.locator("main.auth").evaluate((element) =>
        ["::before", "::after"].map((pseudo) => {
          const computed = getComputedStyle(element, pseudo);
          return {
            active:
              !["none", "normal"].includes(computed.content) &&
              computed.display !== "none",
            mask: computed.maskImage,
          };
        }),
      );
      expect(layers.filter((layer) => layer.active)).toHaveLength(
        style === "artwork" ? 1 : 0,
      );
      expect(layers[0].active).toBe(false);
      if (style === "artwork")
        expect(layers[1].mask).toContain("/spunto-woodgrain-mask.svg");
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth),
      ).toBe(390);
      await page.screenshot({
        path: info.outputPath(`auth-${mode}-${style}.png`),
        fullPage: true,
      });
    });
  }
}
