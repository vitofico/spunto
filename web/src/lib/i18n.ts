import { useSyncExternalStore } from "react";
import englishJson from "../locales/en.json";

export type PluralMessage = Partial<Record<Intl.LDMLPluralRule, string>> & {
  other: string;
};
export interface Catalog {
  nativeName: string;
  direction?: "ltr" | "rtl";
  messages: Record<string, string | PluralMessage>;
}
const english = englishJson as Catalog;
export const languageStorageKey = "spunto:language";
const loaders = import.meta.glob<Catalog>(
  ["../locales/*.json", "!../locales/en.json"],
  {
    import: "default",
  },
);
const names = import.meta.glob<string>("../locales/*.json", {
  import: "default",
  query: "?locale-metadata",
  eager: true,
});
const codeOf = (file: string) =>
  file
    .split("/")
    .at(-1)!
    .replace(/\.json$/, "");
export const catalogs: Record<string, Catalog> = { en: english };
export const languages = Object.entries(names).map(([file, nativeName]) => ({
  code: codeOf(file),
  nativeName,
}));
export function resolveLanguage(
  preferred: readonly string[],
  available = languages.map((language) => language.code),
): string {
  for (const requested of preferred) {
    const canonical = requested.toLowerCase().replaceAll("_", "-");
    const exact = available.find((code) => code.toLowerCase() === canonical);
    if (exact) return exact;
    const base = canonical.split("-")[0];
    const language = available.find((code) => code.toLowerCase() === base);
    if (language) return language;
  }
  return "en";
}
export function readLanguage(): string {
  let saved: string | null = null;
  try {
    saved = localStorage.getItem(languageStorageKey);
  } catch {
    /* Storage can be disabled. */
  }
  return resolveLanguage([
    ...(saved ? [saved] : []),
    ...(typeof navigator === "undefined" ? [] : navigator.languages),
  ]);
}
let locale = "en";
let requested = 0;
const listeners = new Set<() => void>();
function applyDocumentLanguage() {
  if (typeof document !== "undefined") {
    document.documentElement.lang = locale;
    document.documentElement.dir = catalogs[locale]?.direction ?? "ltr";
  }
}
applyDocumentLanguage();
export function getLanguage() {
  return locale;
}
export async function setLanguage(value: string) {
  const request = ++requested;
  const next = resolveLanguage([value]);
  if (!catalogs[next]) {
    const load = Object.entries(loaders).find(
      ([file]) => codeOf(file) === next,
    )?.[1];
    if (!load) return;
    try {
      catalogs[next] = await load();
    } catch {
      // A failed optional language chunk leaves the current UI and saved choice usable.
      return false;
    }
  }
  if (request !== requested) return;
  locale = next;
  try {
    localStorage.setItem(languageStorageKey, locale);
  } catch {
    /* Switching still works without persistence. */
  }
  applyDocumentLanguage();
  listeners.forEach((listener) => listener());
}
if (typeof window !== "undefined") {
  window.addEventListener("storage", (event) => {
    if (event.key === languageStorageKey) void setLanguage(readLanguage());
  });
  const initial = readLanguage();
  if (initial !== "en") void setLanguage(initial);
}
export function useLanguage() {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    getLanguage,
    () => "en",
  );
}
export function translate(
  catalog: Catalog,
  language: string,
  key: string,
  values: Record<string, string | number> = {},
): string {
  const message =
    catalog.messages[key] ?? (english.messages as Catalog["messages"])[key];
  if (message === undefined) throw new Error(`Unknown translation key: ${key}`);
  const template =
    typeof message === "string"
      ? message
      : (message[new Intl.PluralRules(language).select(Number(values.count))] ??
        message.other);
  return template.replace(
    /\{([A-Za-z_][A-Za-z0-9_]*)\}/g,
    (_, name: string) => {
      const value = values[name];
      if (value === undefined)
        throw new Error(`Missing translation argument: ${key}.${name}`);
      return typeof value === "number" ? value.toLocaleString(language) : value;
    },
  );
}
export function t(key: string, values?: Record<string, string | number>) {
  return translate(catalogs[locale] ?? english, locale, key, values);
}

export function hasTranslation(key: string) {
  return Object.hasOwn(english.messages, key);
}
