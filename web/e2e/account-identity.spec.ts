import { expect, test } from "@playwright/test";

for (const width of [1280, 390]) {
  test(`signed-in account stays distinct from space name at ${width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 850 });
    const name = "Élodie 李 with a long account display name";
    await page.route("**/api/v1/**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      const json = (data: unknown, status = 200) =>
        route.fulfill({
          status,
          contentType: "application/json",
          body: JSON.stringify(data),
        });
      if (path.endsWith("/meta"))
        return json({
          setup_pending: false,
          unencrypted: false,
          plugins_rev: 0,
          ai_capabilities: [],
        });
      if (path.endsWith("/auth/session"))
        return json({
          owner_id: "alice",
          user_id: "alice",
          credential_id: "synthetic-session",
          workspace_id: "shared",
          workspace_role: "viewer",
        });
      if (path.endsWith("/auth/me"))
        return json({
          account: {
            id: "alice",
            display_name: name,
            username: "alice-login",
            email: null,
          },
        });
      if (path.endsWith("/auth/options"))
        return json({
          mode: "hosted",
          password_enabled: true,
          oidc_enabled: false,
        });
      if (path.endsWith("/auth/account/security"))
        return json({
          local_username: "alice-login",
          local_password_set: true,
          oidc_linked: false,
          reauthenticated: false,
          reauth_url: null,
        });
      if (path.endsWith("/workspaces"))
        return json({
          workspaces: [
            {
              id: "shared",
              name: "Someone else’s shared space",
              role: "viewer",
              personal: false,
            },
          ],
          active_workspace_id: "shared",
        });
      if (path.endsWith("/plugins/manifest"))
        return json({ ui: 1, plugins_rev: 0, plugins: [] });
      if (path.endsWith("/labels")) return json({ labels: [], total: 0 });
      if (path.endsWith("/notes"))
        return json({ notes: [], next_cursor: null });
      if (path.endsWith("/changes/wait")) return;
      return json({ code: "fixture_route_missing" }, 404);
    });
    await page.goto("/");
    if (width < 768)
      await page
        .getByRole("button", { name: "Open navigation", exact: true })
        .click();
    const link = page.getByRole("link", {
      name: `Account: ${name}`,
      exact: true,
    });
    await expect(link).toBeVisible();
    const identity = link.locator(".account-rail-label");
    await expect(identity).toHaveCSS("display", "grid");
    const nameBox = await identity.locator("strong").boundingBox();
    const accountBox = await identity.locator("small").boundingBox();
    expect(accountBox!.y).toBeGreaterThanOrEqual(nameBox!.y + nameBox!.height);
    await page.screenshot({
      path: testInfo.outputPath(`account-navigation-${width}.png`),
    });
    await link.click();
    const details = page.getByRole("region", { name: "Account", exact: true });
    await expect(
      details.getByText(`Signed in as ${name}`, { exact: true }),
    ).toBeVisible();
    await expect(
      details.getByText("Username: alice-login", { exact: true }),
    ).toBeVisible();
    await expect(details).toContainText("Server:");
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
    await page.screenshot({
      path: testInfo.outputPath(`account-details-${width}.png`),
    });
  });
}
