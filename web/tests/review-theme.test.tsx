import { afterEach, expect, it, vi } from "vitest";
import { render, cleanup, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { SettingsPage } from "../src/features/Settings";
import { api } from "../src/lib/api";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
it("applies an instance theme changed by another device after settings refetch", async () => {
  document.cookie = "theme=; Max-Age=0; Path=/";
  document.documentElement.dataset.theme = "carta";
  vi.stubGlobal(
    "matchMedia",
    vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
  const settings = {
    theme: "carta" as const,
    plugin_order: [],
    hidden_views: [],
    hidden_mcp_tools: [],
  };
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  client.setQueryData(["settings"], settings);
  vi.spyOn(api, "settings").mockResolvedValue(settings);
  render(
    <QueryClientProvider client={client}>
      <SettingsPage section="appearance" />
    </QueryClientProvider>,
  );
  await act(async () => {
    client.setQueryData(["settings"], { ...settings, theme: "salvia" });
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
  expect(document.documentElement.dataset.theme).toBe("salvia");
  client.clear();
});

it("restores Unicode drafts within the server code-point body budget", async () => {
  const { DraftController } = await import("../src/lib/drafts");
  const entries = new Map<string, string>();
  const storage = {
    getItem: (key: string) => entries.get(key) ?? null,
    setItem: (key: string, value: string) => {
      entries.set(key, value);
    },
    removeItem: (key: string) => {
      entries.delete(key);
    },
  };
  const transport = { create: vi.fn(), update: vi.fn() };
  const body = "🎈".repeat(60000);
  expect([...body]).toHaveLength(60000);
  const first = new DraftController(storage, "draft", transport);
  first.setBody(body);
  const restored = new DraftController(storage, "draft", transport);
  expect(restored.getSnapshot().body.length).toBe(body.length);
});
