import { localeMetadata } from "./scripts/locale-metadata.ts";
import { defineConfig } from "vitest/config";
export default defineConfig({
  plugins: [localeMetadata()],
  test: {
    environment: "jsdom",
    include: ["tests/**/*.test.{ts,tsx}"],
    restoreMocks: true,
  },
});
