import { test, expect } from "@playwright/test";

test.skip(!process.env.SPUNTO_E2E_URL, "Pairing needs the real local server");

test("owner pairs a device once and can revoke its access", async ({
  page,
  request,
}, info) => {
  await page.goto("/");
  await page
    .getByLabel("Password", { exact: true })
    .fill("browser acceptance password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByLabel("New note")).toBeVisible();
  await page.goto("/settings/devices");
  const grantReply = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/v1/auth/pairing") &&
      response.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "Pair an Android device", exact: true })
    .click();
  const grant = await (await grantReply).json();
  const dialog = page.getByRole("dialog", { name: "Pair an Android device" });
  await expect(dialog.locator("svg")).toBeVisible();
  await dialog
    .locator("svg")
    .screenshot({ path: info.outputPath("pairing-qr.png") });
  const exchange = await page.request.post("/api/v1/auth/pairing/exchange", {
    data: { code: grant.code, name: "Synthetic browser paired device" },
  });
  expect(exchange.status()).toBe(200);
  const device = await exchange.json();
  const reuse = await page.request.post("/api/v1/auth/pairing/exchange", {
    data: { code: grant.code, name: "Must not be created" },
  });
  expect(reuse.status()).toBe(401);
  const authenticated = await request.get("/api/v1/notes", {
    headers: { Authorization: `Bearer ${device.token}` },
  });
  expect(authenticated.status()).toBe(200);
  const origin = new URL(page.url()).origin;
  const revoke = await page.request.delete(`/api/v1/credentials/${device.id}`, {
    headers: { Origin: origin },
  });
  expect(revoke.status()).toBe(204);
  const afterRevoke = await request.get("/api/v1/notes", {
    headers: { Authorization: `Bearer ${device.token}` },
  });
  expect(afterRevoke.status()).toBe(401);
  const storage = await page.evaluate(() =>
    JSON.stringify({
      local: { ...localStorage },
      session: { ...sessionStorage },
    }),
  );
  expect(storage).not.toContain(grant.code);
  expect(storage).not.toContain(device.token);
});
