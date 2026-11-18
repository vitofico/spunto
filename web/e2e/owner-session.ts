import { expect, type Page } from "@playwright/test";

export async function signInOwner(page: Page) {
  await page.goto("/login");
  const home = new URL("/", page.url());
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.SPUNTO_E2E_PASSWORD ?? "browser acceptance password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  // The authenticated layout can render before the sign-in route redirects.
  await expect(page).toHaveURL(home.href);
  await expect(page.getByLabel("New note", { exact: true })).toBeVisible();
}
