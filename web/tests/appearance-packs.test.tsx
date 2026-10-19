import { readFileSync } from "node:fs";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  appearanceCards,
  applyAppearance,
  readDeviceAppearance,
  resolvedAppearance,
} from "../src/features/Appearance";
import { AppearancePanel } from "../src/features/AppearancePanel";
import { api } from "../src/lib/api";
import type { Manifest, Preferences, ThemeCard } from "../src/lib/types";

const palettes = JSON.parse(readFileSync("tokens.json", "utf8")).themes;
const installed: ThemeCard = {
  id: "river-ink",
  name: "River Ink",
  description: "Synthetic installed pack",
  status: "available",
  tokens: 1,
  ...palettes.ardesia,
  accents: [{ id: "forest", name: "Forest", ...palettes.salvia }],
};
const manifest = (cards: ThemeCard[]): Manifest => ({
  ui: 1,
  plugins_rev: 1,
  plugins: [],
  theme_catalog: cards,
});
const packLink = () =>
  document.querySelector<HTMLLinkElement>("link[data-spunto-theme-pack]")!;
beforeEach(() => {
  document.cookie = "theme=; Max-Age=0; Path=/";
  applyAppearance("carta", "light");
  vi.stubGlobal(
    "matchMedia",
    vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
});
afterEach(() => {
  cleanup();
  applyAppearance("carta", "light");
  document.cookie = "theme=; Max-Age=0; Path=/";
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it("validates installed palettes and one accent, retaining unavailable selections", () => {
  const cards = appearanceCards(manifest([installed]));
  expect(cards).toHaveLength(6);
  expect(cards.at(-1)).toMatchObject({
    id: installed.id,
    name: installed.name,
    status: "available",
    light: { paper: installed.light!.paper },
    dark: { paper: installed.dark!.paper },
    accents: [{ id: "forest", name: "Forest" }],
  });
  expect(cards.at(-1)?.light).not.toHaveProperty("chrome");
  expect(resolvedAppearance("river-ink", "forest", cards)).toEqual({
    theme: "river-ink",
    accent: "forest",
  });
  expect(resolvedAppearance("river-ink", "gone", cards)).toEqual({
    theme: "river-ink",
    accent: null,
  });
  document.cookie = `theme=${encodeURIComponent(JSON.stringify({ theme: "river-ink", accent: "forest", mode: "dark" }))}; Path=/`;
  expect(resolvedAppearance("river-ink", "forest", appearanceCards())).toEqual({
    theme: "carta",
    accent: null,
  });
  expect(readDeviceAppearance()).toMatchObject({
    theme: "river-ink",
    accent: "forest",
    mode: "dark",
  });
});

it("isolates malformed cards and strips every unvalidated preview value", () => {
  const hostile = {
    ...installed,
    id: "broken-pack",
    status: "failed",
    reason: "Operator needs to replace this pack",
    light: { paper: "url(https://invalid.example/private)" },
  };
  const wrongVersion = { ...installed, id: "future-pack", tokens: 2 };
  const badAccent = { ...installed, id: "bad-accent", accents: [null] };
  const cards = appearanceCards(
    manifest([
      null,
      hostile,
      wrongVersion,
      badAccent,
      installed,
    ] as unknown as ThemeCard[]),
  );
  for (const id of ["broken-pack", "future-pack", "bad-accent"]) {
    const card = cards.find((item) => item.id === id)!;
    expect(card.status).toBe("failed");
    expect(card.light).toBeUndefined();
    expect(card.dark).toBeUndefined();
    expect(card.accents).toEqual([]);
  }
  expect(cards.at(-1)?.status).toBe("available");
  expect(appearanceCards({ theme_catalog: {} } as Manifest)).toHaveLength(5);
  expect(
    appearanceCards(
      manifest([
        { ...installed, accents: [installed.accents[0], installed.accents[0]] },
      ]),
    ).at(-1)?.status,
  ).toBe("failed");
});

it("applies only loaded local CSS and ignores a late response after another choice", () => {
  applyAppearance("river-ink", "dark", "forest");
  const first = packLink();
  expect(first.getAttribute("href")).toBe(
    "/api/v1/themes/river-ink.css?accent=forest",
  );
  expect(document.documentElement.dataset.theme).toBe("carta");
  first.dispatchEvent(new Event("load"));
  expect(document.documentElement.dataset).toMatchObject({
    theme: "river-ink",
    mode: "dark",
    accent: "forest",
  });
  applyAppearance("river-ink", "light");
  const pending = [
    ...document.querySelectorAll<HTMLLinkElement>(
      "link[data-spunto-theme-pack]",
    ),
  ].find((link) => !link.href.includes("accent="))!;
  applyAppearance("corallo", "light");
  pending.dispatchEvent(new Event("load"));
  expect(document.documentElement.dataset.theme).toBe("corallo");
  expect(document.documentElement.dataset.accent).toBeUndefined();
  expect(document.querySelector("link[data-spunto-theme-pack]")).toBeNull();
});

it("waits for a server-rendered stylesheet and safely falls back after load failure", () => {
  const ssr = document.createElement("link");
  ssr.rel = "stylesheet";
  ssr.href = "/api/v1/themes/river-ink.css?accent=forest";
  document.head.append(ssr);
  document.documentElement.dataset.theme = "river-ink";
  document.documentElement.dataset.accent = "forest";
  const changed = vi.fn();
  window.addEventListener("spunto:appearance", changed);
  applyAppearance("river-ink", "dark", "forest");
  expect(changed).not.toHaveBeenCalled();
  expect(document.querySelectorAll('link[rel="stylesheet"]')).toHaveLength(1);
  ssr.dispatchEvent(new Event("error"));
  expect(document.documentElement.dataset).toMatchObject({
    theme: "carta",
    mode: "dark",
  });
  expect(document.documentElement.dataset.accent).toBeUndefined();
  expect(changed).toHaveBeenCalledOnce();
  window.removeEventListener("spunto:appearance", changed);
});

it("keeps accent/device choices independent and shows failed packs as disabled", async () => {
  let settings: Preferences = {
    theme: "river-ink",
    theme_accent: null,
    plugin_order: [],
    hidden_views: [],
    hidden_mcp_tools: [],
  };
  const failed: ThemeCard = {
    id: "broken-pack",
    name: "Broken Pack",
    status: "failed",
    tokens: 1,
    reason: "Missing dark colours",
    accents: [],
  };
  const catalog = manifest([installed, failed]);
  vi.spyOn(api, "settings").mockImplementation(async () => settings);
  vi.spyOn(api, "manifest").mockResolvedValue(catalog);
  const save = vi
    .spyOn(api, "saveSettings")
    .mockImplementation(async (patch) => {
      settings = { ...settings, ...patch };
      return settings;
    });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  client.setQueryData(["settings"], settings);
  client.setQueryData(["manifest"], catalog);
  render(
    <QueryClientProvider client={client}>
      <AppearancePanel />
    </QueryClientProvider>,
  );
  const user = userEvent.setup();
  expect(
    screen
      .getByRole("button", { name: /Broken Pack/ })
      .hasAttribute("disabled"),
  ).toBe(true);
  expect(screen.getByText("Missing dark colours")).toBeTruthy();
  expect(screen.getByRole("button", { name: "Default" })).toBeTruthy();
  await user.click(screen.getByRole("button", { name: "Forest" }));
  await waitFor(() =>
    expect(save).toHaveBeenCalledWith({ theme_accent: "forest" }),
  );
  await waitFor(() => expect(settings.theme_accent).toBe("forest"));
  await user.click(
    screen.getByRole("switch", {
      name: "Use a different theme on this device",
    }),
  );
  await user.click(screen.getByRole("button", { name: "Default" }));
  expect(readDeviceAppearance()).toMatchObject({
    theme: "river-ink",
    accent: null,
  });
  expect(settings.theme_accent).toBe("forest");
  await user.click(screen.getByRole("button", { name: "Dark" }));
  await user.click(
    screen.getByRole("switch", {
      name: "Use a different theme on this device",
    }),
  );
  expect(readDeviceAppearance()).toEqual({ mode: "dark" });
  expect(save).toHaveBeenCalledTimes(1);
  client.clear();
});
