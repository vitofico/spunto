import { expect, test, type Page } from "@playwright/test";

async function hosted(
  page: Page,
  oidc = false,
  registration = false,
  signedIn = false,
) {
  let authenticated = signedIn;
  let reauthenticated = false;
  let configured = false;
  let username: string | null = null;
  let credential = "existing-sso-session";
  const writes: { path: string; body: Record<string, unknown> }[] = [];
  const sessionRequests: { account: string; workspace: string }[] = [];
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
        setup_pending: false,
        unencrypted: false,
        plugins_rev: 0,
        ai_capabilities: [],
      });
    if (path.endsWith("/auth/options"))
      return json({
        mode: "hosted",
        password_enabled: true,
        registration_enabled: registration,
        oidc_enabled: oidc,
        ...(oidc
          ? { label: "Authentik", login_url: "/api/v1/auth/oidc/start" }
          : {}),
      });
    if (path.endsWith("/auth/oidc/start")) {
      expect(new URL(request.url()).searchParams.get("reauth")).toBe(
        "local-password",
      );
      reauthenticated = true;
      return route.fulfill({
        contentType: "text/html",
        body: '<!doctype html><meta http-equiv="refresh" content="0;url=/settings/account?security=reauthenticated">',
      });
    }
    if (path.endsWith("/auth/session")) {
      if (!authenticated) return json({ code: "unauthenticated" }, 401);
      const workspace =
        request.headers()["x-spunto-workspace"] ?? "existing-library";
      sessionRequests.push({ account: "same-account", workspace });
      return json({
        owner_id: "same-account",
        user_id: "same-account",
        workspace_id: workspace,
        workspace_role: "owner",
        credential_id: credential,
        auth_method: oidc ? "oidc" : "local",
      });
    }
    if (path.endsWith("/auth/login") || path.endsWith("/auth/register")) {
      writes.push({ path, body: request.postDataJSON() });
      if (request.postDataJSON().password === "incorrect fixture")
        return json(
          {
            code: "invalid_credentials",
            detail: "The username or password is incorrect.",
          },
          401,
        );
      authenticated = true;
      return route.fulfill({ status: 204 });
    }
    if (path.endsWith("/auth/account/security"))
      return json({
        local_username: username,
        local_password_set: configured,
        oidc_linked: oidc,
        reauthenticated,
        reauth_url: oidc
          ? "/api/v1/auth/oidc/start?reauth=local-password"
          : null,
      });
    if (path.endsWith("/auth/account/password")) {
      writes.push({ path, body: request.postDataJSON() });
      expect(reauthenticated).toBe(true);
      username = String(request.postDataJSON().username).toLowerCase();
      configured = true;
      reauthenticated = false;
      credential = "rotated-same-account";
      return route.fulfill({ status: 204 });
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
    if (path.endsWith("/workspaces"))
      return json({
        workspaces: [
          {
            id: "existing-library",
            name: "Personal",
            role: "owner",
            personal: true,
          },
        ],
        active_workspace_id: "existing-library",
      });
    if (path.endsWith("/notes")) return json({ notes: [], next_cursor: null });
    if (path.endsWith("/changes/wait")) return route.abort();
    return json({ code: "fixture_route_missing" }, 404);
  });
  return { writes, sessionRequests };
}

test("standalone hosted password sign-in works without an SSO provider", async ({
  page,
}) => {
  const state = await hosted(page);
  await page.goto("/login?register=1");
  await expect(
    page.getByRole("heading", { name: "Sign in to Spunto" }),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: /Continue with/ })).toHaveCount(
    0,
  );
  await expect(
    page.getByRole("button", { name: "Create an account" }),
  ).toHaveCount(0);
  await page.getByLabel("Username").fill("Standalone.User");
  await page.getByLabel("Password", { exact: true }).fill("incorrect fixture");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page.getByLabel("Username")).toHaveValue("Standalone.User");
  await page
    .getByLabel("Password", { exact: true })
    .fill("synthetic passphrase");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("link", { name: "All notes", exact: true }),
  ).toBeVisible();
  expect(state.writes).toEqual([
    {
      path: "/api/v1/auth/login",
      body: { username: "Standalone.User", password: "incorrect fixture" },
    },
    {
      path: "/api/v1/auth/login",
      body: { username: "Standalone.User", password: "synthetic passphrase" },
    },
  ]);
});

test("registration appears only when enabled and leaves optional SSO available", async ({
  page,
}) => {
  const state = await hosted(page, true, true);
  await page.goto("/login?register=1");
  await expect(
    page.getByRole("heading", { name: "Create your account" }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Continue with Authentik" }),
  ).toHaveAttribute("href", "/api/v1/auth/oidc/start");
  await page.getByLabel("Username").fill("New.Person");
  await page
    .getByLabel("Password", { exact: true })
    .fill("synthetic passphrase");
  await page.getByLabel("Confirm password").fill("synthetic passphrase");
  await page
    .getByRole("button", { name: "Create account", exact: true })
    .click();
  await expect(
    page.getByRole("link", { name: "All notes", exact: true }),
  ).toBeVisible();
  expect(state.writes).toEqual([
    {
      path: "/api/v1/auth/register",
      body: { username: "New.Person", password: "synthetic passphrase" },
    },
  ]);
});

test("SSO account verifies again and adds local credentials to its existing library", async ({
  page,
}) => {
  const state = await hosted(page, true, false, true);
  await page.goto("/settings/account");
  await expect(
    page.getByRole("link", { name: "Verify with SSO" }),
  ).toBeVisible();
  await expect(page.getByLabel("New password", { exact: true })).toHaveCount(0);
  await page.getByRole("link", { name: "Verify with SSO" }).click();
  await page.getByLabel("Username").fill("Existing.Person");
  await page
    .getByLabel("New password", { exact: true })
    .fill("synthetic passphrase");
  await page.getByLabel("Confirm new password").fill("synthetic passphrase");
  await page.getByRole("button", { name: "Add password sign-in" }).click();
  await expect(
    page.getByText("Username: existing.person", { exact: true }),
  ).toBeVisible();
  await page.getByText("Account details", { exact: true }).click();
  await expect(
    page.getByText("Account ID: same-account", { exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Current password")).toBeVisible();
  expect(state.writes).toEqual([
    {
      path: "/api/v1/auth/account/password",
      body: {
        username: "Existing.Person",
        new_password: "synthetic passphrase",
      },
    },
  ]);
  expect(state.sessionRequests.length).toBeGreaterThan(1);
  expect(
    state.sessionRequests.every(
      (value) =>
        value.account === "same-account" &&
        value.workspace === "existing-library",
    ),
  ).toBe(true);
});
