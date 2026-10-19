import { defineConfig, devices } from "@playwright/test";
export default defineConfig({
  testDir: "e2e",
  fullyParallel: false,
  workers: 1,
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
    { name: "firefox", use: { ...devices["Desktop Firefox"] } },
    { name: "webkit", use: { ...devices["Desktop Safari"] } },
  ],
  use: {
    baseURL: process.env.SPUNTO_E2E_URL ?? "http://127.0.0.1:5173",
    // Existing flows use a returning browser; onboarding.spec starts with empty storage.
    storageState: {
      cookies: [],
      origins: [
        {
          origin: new URL(process.env.SPUNTO_E2E_URL ?? "http://127.0.0.1:5173")
            .origin,
          localStorage: [{ name: "spunto:guide:v1", value: "complete" }],
        },
      ],
    },
    trace: "retain-on-failure",
  },
  webServer: process.env.SPUNTO_E2E_URL
    ? undefined
    : {
        command: "pnpm dev",
        url: "http://127.0.0.1:5173",
        reuseExistingServer: true,
      },
  reporter: [["list"], ["html", { open: "never" }]],
});
