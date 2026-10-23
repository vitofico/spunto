import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";

const spanish = JSON.parse(
  readFileSync(new URL("../src/locales/es.json", import.meta.url), "utf8"),
).messages as Record<string, string>;

test("language changes on sign-in and Settings persist across reloads and preserve entered content", async ({
  page,
}) => {
  let authenticated = false;
  const password = "My untranslated password #Ideas";
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (value: unknown, status = 200) =>
      route.fulfill({
        status,
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
      return authenticated
        ? json({
            owner_id: "language-fixture",
            credential_id: "fixture-session",
          })
        : json({ code: "unauthenticated" }, 401);
    if (path.endsWith("/auth/options")) return json({ mode: "local" });
    if (path.endsWith("/auth/login")) {
      expect(request.postDataJSON()).toEqual({ password });
      authenticated = true;
      return route.fulfill({ status: 204 });
    }
    if (path.endsWith("/plugins")) return json({ plugins: [], plugins_rev: 0 });
    if (path.endsWith("/plugins/manifest"))
      return json({ ui: 1, plugins_rev: 0, plugins: [] });
    if (path.endsWith("/settings"))
      return json({
        theme: "carta",
        plugin_order: [],
        hidden_views: [],
        hidden_mcp_tools: [],
      });
    if (path.endsWith("/labels")) return json({ labels: [], total: 0 });
    if (path.endsWith("/notes")) return json({ notes: [], next_cursor: null });
    if (path.endsWith("/suggestions")) return json({ suggestions: [] });
    if (path.endsWith("/changes/wait")) return;
    return json({ code: "fixture_route_missing" }, 404);
  });
  await page.goto("/settings/appearance");
  await expect(
    page.getByRole("heading", { name: "Unlock your Spunto" }),
  ).toBeVisible();
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("combobox", { name: "Language" }).selectOption("es");
  await expect(page.locator("html")).toHaveAttribute("lang", "es");
  await expect(page.locator("html")).toHaveAttribute("dir", "ltr");
  await expect(
    page.getByLabel(spanish["profilepanel.password"], { exact: true }),
  ).toHaveValue(password);
  await page
    .getByRole("button", { name: spanish["auth.sign_in"], exact: true })
    .click();
  await expect(
    page.getByRole("heading", {
      name: spanish["appearancepanel.appearance"],
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole("combobox", { name: spanish["language.label"] }),
  ).toHaveValue("es");
  await page.reload();
  await expect(
    page.getByRole("heading", {
      name: spanish["appearancepanel.appearance"],
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole("combobox", { name: spanish["language.label"] }),
  ).toHaveValue("es");
  await page
    .getByRole("combobox", { name: spanish["language.label"] })
    .selectOption("en");
  await expect(
    page.getByRole("heading", { name: "Appearance", exact: true }),
  ).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
});
