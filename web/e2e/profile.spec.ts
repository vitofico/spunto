import { test, expect } from "@playwright/test";

test("password-only unlock leads to a single-owner profile with password and sign out", async ({
  page,
}, testInfo) => {
  let authenticated = false;
  let loggedOut = false;
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const json = (data: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify(data),
      });
    if (path === "/api/v1/meta")
      return json({
        version: "fixture",
        setup_pending: false,
        plugins_rev: 0,
        ai_capabilities: [],
        unencrypted: false,
      });
    if (path === "/api/v1/auth/session")
      return authenticated
        ? json({
            owner_id: "profile-fixture",
            credential_id: "fixture-session",
          })
        : json({ code: "unauthenticated" }, 401);
    if (path === "/api/v1/auth/options") {
      expect(request.method()).toBe("GET");
      return json({ mode: "local" });
    }
    if (path === "/api/v1/auth/login") {
      expect(request.postDataJSON()).toEqual({
        password: "correct horse battery",
      });
      authenticated = true;
      return route.fulfill({ status: 204 });
    }
    if (path === "/api/v1/auth/logout") {
      expect(request.method()).toBe("POST");
      authenticated = false;
      loggedOut = true;
      return route.fulfill({ status: 204 });
    }
    // A quiet server holds the long poll open; a failed feed now reads as offline.
    if (path === "/api/v1/changes/wait") return;
    if (path === "/api/v1/plugins/manifest")
      return json({ ui: 1, plugins_rev: 0, plugins: [] });
    if (path === "/api/v1/labels") return json({ labels: [], total: 0 });
    if (path === "/api/v1/settings") {
      expect(authenticated).toBe(true);
      expect(request.method()).toBe("GET");
      return json({
        theme: "carta",
        plugin_order: [],
        hidden_views: [],
        hidden_mcp_tools: [],
        owner_display_name: "",
      });
    }
    return json({ code: "fixture_route_missing" }, 404);
  });
  await page.goto("/settings/profile");
  await expect(
    page.getByRole("heading", { name: "Unlock your Spunto" }),
  ).toBeVisible();
  await expect(page.getByText(/There is no username to enter/)).toBeVisible();
  await expect(page.getByLabel("Username", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("link", { name: /Continue with/ })).toHaveCount(
    0,
  );
  await expect(
    page.getByRole("button", { name: "Create an account" }),
  ).toHaveCount(0);
  await expect(page.locator("input")).toHaveCount(1);
  await expect(page.getByLabel("Password", { exact: true })).toHaveAttribute(
    "type",
    "password",
  );
  await page.screenshot({
    path: testInfo.outputPath("login.png"),
  });
  await page
    .getByLabel("Password", { exact: true })
    .fill("correct horse battery");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Profile", exact: true }),
  ).toBeVisible();
  // A single-owner server has no use for a display name; the password lives here.
  await expect(page.getByLabel(/Display name/)).toHaveCount(0);
  await expect(
    page.getByRole("link", { name: "Spaces", exact: true }),
  ).toHaveCount(0);
  await expect(page.getByLabel(/New password/)).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Show guide", exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("profile.png"),
  });
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Unlock your Spunto" }),
  ).toBeVisible();
  expect(loggedOut).toBe(true);
});
