// Regression cases derived from the independent M8 review; synthetic data only.
import React from "react";
import { readFileSync } from "node:fs";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import { render, cleanup, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  appearanceCards,
  applyAppearance,
  useInstanceAppearance,
} from "../src/features/Appearance";
import { api } from "../src/lib/api";
import type { ThemeCard, Manifest } from "../src/lib/types";
const palettes = JSON.parse(readFileSync("tokens.json", "utf8")).themes;
const installed: ThemeCard = {
  id: "fixture-pack",
  name: "Fixture pack",
  status: "available",
  tokens: 1,
  ...palettes.ardesia,
  accents: [],
};
const manifest = (cards: ThemeCard[]): Manifest => ({
  ui: 1,
  plugins_rev: 1,
  plugins: [],
  theme_catalog: cards,
});
const settings = {
  theme: "fixture-pack",
  plugin_order: [],
  hidden_views: [],
  hidden_mcp_tools: [],
};
function Hook() {
  useInstanceAppearance(true);
  return null;
}
function ssr() {
  const link = document.createElement("link");
  link.rel = "stylesheet";
  link.href = "/api/v1/themes/fixture-pack.css";
  Object.defineProperty(link, "sheet", { configurable: true, get: () => ({}) });
  document.head.append(link);
  document.documentElement.dataset.theme = "fixture-pack";
  document.documentElement.dataset.mode = "light";
  return link;
}
beforeEach(() => {
  document.cookie = "theme=; Max-Age=0; Path=/";
  applyAppearance("carta", "light");
});
afterEach(() => {
  cleanup();
  applyAppearance("carta", "light");
  vi.restoreAllMocks();
});
it("SSR selected custom stylesheet survives pending manifest", async () => {
  const link = ssr();
  vi.spyOn(api, "settings").mockResolvedValue(settings);
  vi.spyOn(api, "manifest").mockReturnValue(new Promise(() => {}));
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  client.setQueryData(["settings"], settings);
  render(
    <QueryClientProvider client={client}>
      <Hook />
    </QueryClientProvider>,
  );
  await waitFor(() => expect(api.manifest).toHaveBeenCalled());
  expect(document.documentElement.dataset.theme).toBe("fixture-pack");
  expect(link.isConnected).toBe(true);
  client.clear();
});
it("fresh catalog with same pack ID reloads its changed CSS", async () => {
  const link = ssr();
  const first = manifest([installed]);
  vi.spyOn(api, "settings").mockResolvedValue(settings);
  vi.spyOn(api, "manifest").mockResolvedValue(first);
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  client.setQueryData(["settings"], settings);
  client.setQueryData(["manifest"], first);
  render(
    <QueryClientProvider client={client}>
      <Hook />
    </QueryClientProvider>,
  );
  await waitFor(() =>
    expect(document.documentElement.dataset.theme).toBe("fixture-pack"),
  );
  await act(async () => {
    client.setQueryData(["manifest"], {
      ...first,
      plugins_rev: 2,
      theme_catalog: [{ ...installed, ...palettes.salvia }],
    });
    await new Promise((r) => setTimeout(r, 0));
  });
  expect(
    [...document.querySelectorAll("link[rel=stylesheet]")].some(
      (candidate) => candidate !== link,
    ),
  ).toBe(true);
  client.clear();
});
it("runtime catalog cannot replace canonical builtin cards or accept duplicate custom IDs", () => {
  const cards = appearanceCards(
    manifest([
      {
        ...installed,
        id: "ardesia",
        status: "failed",
        reason: "Synthetic malformed builtin",
      },
      installed,
      { ...installed, name: "Collision" },
    ]),
  );
  expect(cards.find((c) => c.id === "ardesia")?.status).toBe("available");
  expect(cards.find((c) => c.id === "fixture-pack")?.status).toBe("failed");
});
it("duplicate custom IDs cannot resolve to an available palette", () => {
  const cards = appearanceCards(
    manifest([installed, { ...installed, name: "Collision" }]),
  );
  expect(cards.find((c) => c.id === "fixture-pack")?.status).toBe("failed");
});
it("more than twenty explicit accents disables a pack", () => {
  const card = {
    ...installed,
    accents: Array.from({ length: 21 }, (_, index) => ({
      id: `accent-${index}`,
      name: "Accent",
      ...palettes.ardesia,
    })),
  };
  expect(
    appearanceCards(manifest([card])).find((c) => c.id === "fixture-pack")
      ?.status,
  ).toBe("failed");
});
