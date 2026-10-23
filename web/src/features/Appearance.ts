import { t } from "../lib/i18n";
import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../lib/api";
import { themeCatalog, type Theme } from "../lib/theme-catalog";
import type { Manifest, ThemeCard } from "../lib/types";
import { colorRoles, themeIssues } from "../../scripts/theme-contrast.mjs";
export type { Theme } from "../lib/theme-catalog";
export type Mode = "system" | "light" | "dark";
export type DeviceAppearance = {
  theme?: Theme;
  accent?: string | null;
  mode: Mode;
  reading?: "serif" | "sans";
  size?: "s" | "m" | "l";
};
const builtinIds = new Set<string>(themeCatalog.map((theme) => theme.id));
export const appearanceReady = (theme: string, manifest?: Manifest) =>
  builtinIds.has(theme) || !!manifest;
export const validThemeId = (value: unknown): value is string =>
  typeof value === "string" && /^[a-z][a-z0-9-]{1,23}$/.test(value);
const record = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === "object" && !Array.isArray(value);
const label = (value: unknown, fallback: string) =>
  typeof value === "string" && value.trim() ? value.slice(0, 500) : fallback;
function safeModes(value: Record<string, unknown>) {
  if (themeIssues(value).length) return undefined;
  return Object.fromEntries(
    ["light", "dark"].map((mode) => [
      mode,
      Object.fromEntries(
        colorRoles.map((role) => [
          role,
          (value[mode] as Record<string, string>)[role],
        ]),
      ),
    ]),
  ) as { light: Record<string, string>; dark: Record<string, string> };
}
export function appearanceCards(manifest?: Manifest): ThemeCard[] {
  const cards = themeCatalog.map((theme): ThemeCard => ({
    ...theme,
    description: t(`theme.${theme.id}.description`),
    status: "available",
    tokens: 1,
    accents: [],
  }));
  const incomingCards: unknown[] = Array.isArray(manifest?.theme_catalog)
    ? manifest.theme_catalog
    : [];
  const counts = new Map<string, number>();
  for (const card of incomingCards)
    if (record(card) && validThemeId(card.id))
      counts.set(card.id, (counts.get(card.id) ?? 0) + 1);
  const seen = new Set<string>();
  for (const incoming of incomingCards) {
    if (!record(incoming) || !validThemeId(incoming.id)) continue;
    if (builtinIds.has(incoming.id) || seen.has(incoming.id)) continue;
    seen.add(incoming.id);
    const modes = safeModes(incoming);
    const accents = Array.isArray(incoming.accents)
      ? incoming.accents.map((accent) => {
          if (!record(accent) || !validThemeId(accent.id)) return undefined;
          const colors = safeModes(accent);
          return colors
            ? { id: accent.id, name: label(accent.name, accent.id), ...colors }
            : undefined;
        })
      : undefined;
    const available =
      incoming.status === "available" &&
      incoming.tokens === 1 &&
      !!modes &&
      !!accents &&
      accents.length <= 20 &&
      counts.get(incoming.id) === 1 &&
      accents.every((accent) => accent !== undefined) &&
      new Set(accents.map((accent) => accent?.id)).size === accents.length;
    const card: ThemeCard = {
      id: incoming.id,
      name: label(incoming.name, incoming.id),
      description: label(
        incoming.description,
        t("appearance.installed_colour_pack"),
      ),
      tokens: 1,
      status: available ? "available" : "failed",
      reason: available
        ? undefined
        : incoming.status === "failed"
          ? label(
              incoming.reason,
              t("appearance.this_colour_pack_is_unavailable"),
            )
          : t("appearance.this_colour_pack_is_invalid_or_needs_a"),
      accents: available
        ? accents.filter((accent) => accent !== undefined)
        : [],
      ...(available ? modes : {}),
    };
    const index = cards.findIndex((candidate) => candidate.id === card.id);
    if (index < 0) cards.push(card);
    else cards[index] = card;
  }
  return cards;
}
export function resolvedAppearance(
  theme: string,
  accent: string | null | undefined,
  cards: ThemeCard[],
) {
  const card = cards.find(
    (item) => item.id === theme && item.status === "available",
  );
  return {
    theme: card?.id ?? "carta",
    accent:
      accent && card?.accents.some((item) => item.id === accent)
        ? accent
        : null,
  };
}
export function readDeviceAppearance(): DeviceAppearance {
  try {
    const value = JSON.parse(
      decodeURIComponent(
        document.cookie
          .split("; ")
          .find((c) => c.startsWith("theme="))
          ?.slice(6) ?? "%7B%7D",
      ),
    );
    return {
      theme: validThemeId(value.theme) ? value.theme : undefined,
      accent:
        value.theme && (value.accent === null || validThemeId(value.accent))
          ? value.accent
          : undefined,
      mode: ["system", "light", "dark"].includes(value.mode)
        ? value.mode
        : "system",
      reading: ["serif", "sans"].includes(value.reading)
        ? value.reading
        : undefined,
      size: ["s", "m", "l"].includes(value.size) ? value.size : undefined,
    };
  } catch {
    return { mode: "system" };
  }
}
let appearanceGeneration = 0;
export function applyAppearance(
  theme: Theme,
  mode: Mode,
  accent?: string | null,
  revision?: number,
) {
  const generation = ++appearanceGeneration;
  const selected = validThemeId(theme) ? theme : "carta";
  const choice = validThemeId(accent) ? accent : null;
  const apply = () => {
    if (generation !== appearanceGeneration) return;
    document.documentElement.dataset.theme = selected;
    document.documentElement.dataset.mode = mode;
    if (choice) document.documentElement.dataset.accent = choice;
    else delete document.documentElement.dataset.accent;
    window.dispatchEvent(new Event("spunto:appearance"));
  };
  const links = [
    ...document.querySelectorAll<HTMLLinkElement>('link[rel="stylesheet"]'),
  ].filter((link) =>
    new URL(link.href, location.href).pathname.startsWith("/api/v1/themes/"),
  );
  if (builtinIds.has(selected)) {
    links.forEach((link) => link.remove());
    apply();
    return;
  }
  const query = new URLSearchParams();
  if (choice) query.set("accent", choice);
  if (
    typeof revision === "number" &&
    Number.isSafeInteger(revision) &&
    revision >= 0
  )
    query.set("v", String(revision));
  const href = `/api/v1/themes/${encodeURIComponent(selected)}.css${query.size ? `?${query}` : ""}`;
  const existing = links.find((link) => link.getAttribute("href") === href);
  if (existing?.sheet) {
    apply();
    return;
  }
  const link = existing ?? document.createElement("link");
  link.rel = "stylesheet";
  link.dataset.spuntoThemePack = "";
  link.onload = () => {
    if (generation !== appearanceGeneration) {
      link.remove();
      return;
    }
    links.filter((old) => old !== link).forEach((old) => old.remove());
    apply();
  };
  link.onerror = () => {
    if (generation !== appearanceGeneration) return;
    link.remove();
    links.filter((old) => old !== link).forEach((old) => old.remove());
    document.documentElement.dataset.theme = "carta";
    document.documentElement.dataset.mode = mode;
    delete document.documentElement.dataset.accent;
    window.dispatchEvent(new Event("spunto:appearance"));
  };
  link.setAttribute("href", href);
  if (!existing) document.head.append(link);
}
/** The settings cache changes on plugins_rev, including a theme changed on another device. */
export function useInstanceAppearance(enabled: boolean) {
  const settings = useQuery({
    queryKey: ["settings"],
    queryFn: api.settings,
    enabled,
  });
  const manifest = useQuery({
    queryKey: ["manifest"],
    queryFn: api.manifest,
    enabled,
  });
  useEffect(() => {
    if (!enabled || !settings.data) return;
    const device = readDeviceAppearance();
    const requested = device.theme ?? settings.data.theme;
    const selected = resolvedAppearance(
      requested,
      device.theme ? device.accent : settings.data.theme_accent,
      appearanceCards(manifest.data),
    );
    if (appearanceReady(requested, manifest.data))
      applyAppearance(
        selected.theme,
        device.mode,
        selected.accent,
        manifest.data?.plugins_rev,
      );
    else document.documentElement.dataset.mode = device.mode;
    if (device.reading)
      document.documentElement.dataset.reading = device.reading;
    if (device.size) document.documentElement.dataset.size = device.size;
  }, [enabled, settings.data, manifest.data]);
}
