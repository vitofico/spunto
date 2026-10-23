import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useManifest } from "../src/lib/query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { useState } from "react";
import { LanguagePicker } from "../src/components/core/LanguagePicker";
import { FirstRunGuide } from "../src/components/core/FirstRunGuide";
import { ApiError } from "../src/lib/errors";
import { localizeCatalog, localizePlugin } from "../src/lib/localizePlugins";
import {
  catalogs,
  getLanguage,
  hasTranslation,
  languages,
  languageStorageKey,
  readLanguage,
  resolveLanguage,
  setLanguage,
  t,
  translate,
  type Catalog,
} from "../src/lib/i18n";
import type { Plugin } from "../src/lib/types";

const files = import.meta.glob<Catalog>("../src/locales/*.json", {
  eager: true,
  import: "default",
});
const entries = Object.entries(files).map(
  ([file, catalog]) =>
    [file.split("/").at(-1)!.replace(".json", ""), catalog] as const,
);
const english = files["../src/locales/en.json"];
const placeholders = (value: string) =>
  [
    ...new Set(
      [...value.matchAll(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g)].map(
        (match) => match[1],
      ),
    ),
  ].sort();

beforeEach(async () => {
  localStorage.clear();
  await setLanguage("en");
});
afterEach(async () => {
  cleanup();
  vi.restoreAllMocks();
  await setLanguage("en");
  localStorage.clear();
});

describe("contributor catalog contract", () => {
  it("discovers all shipped languages and gives every catalog the complete English contract", () => {
    expect(languages.map((language) => language.code)).toEqual(
      expect.arrayContaining(["en", "es", "it", "fr", "de"]),
    );
    for (const [code, catalog] of entries) {
      expect(Intl.getCanonicalLocales(code)).toHaveLength(1);
      expect(catalog.nativeName.trim()).not.toBe("");
      expect(catalog.direction ?? "ltr").toMatch(/^(ltr|rtl)$/);
      expect(Object.keys(catalog.messages).sort(), code).toEqual(
        Object.keys(english.messages).sort(),
      );
      for (const [key, source] of Object.entries(english.messages)) {
        const message = catalog.messages[key];
        expect(typeof message, `${code}:${key}`).toBe(typeof source);
        if (typeof source === "string") {
          expect(
            typeof message === "string" && message.trim(),
            `${code}:${key}`,
          ).toBeTruthy();
          expect(placeholders(message as string), `${code}:${key}`).toEqual(
            placeholders(source),
          );
        } else {
          expect(message, `${code}:${key}`).toHaveProperty("other");
          for (const [quantity, text] of Object.entries(message)) {
            expect(["zero", "one", "two", "few", "many", "other"]).toContain(
              quantity,
            );
            expect(text.trim(), `${code}:${key}.${quantity}`).not.toBe("");
            expect(placeholders(text), `${code}:${key}.${quantity}`).toEqual(
              placeholders(
                source[quantity as Intl.LDMLPluralRule] ?? source.other,
              ),
            );
          }
        }
      }
    }
  });
});

it("resolves exact tags, regional browser preferences, later supported choices and English fallback", () => {
  const supported = ["en", "es", "it", "fr", "de", "pt-BR"];
  expect(resolveLanguage(["PT-br", "es"], supported)).toBe("pt-BR");
  expect(resolveLanguage(["fr_CA"], supported)).toBe("fr");
  expect(resolveLanguage(["ja-JP", "de-AT"], supported)).toBe("de");
  expect(resolveLanguage(["ja-JP"], supported)).toBe("en");
  expect(resolveLanguage([], supported)).toBe("en");
});

it("prefers the saved choice, uses navigator languages by default and tolerates blocked storage", () => {
  vi.spyOn(navigator, "languages", "get").mockReturnValue(["ja-JP", "it-CH"]);
  localStorage.removeItem(languageStorageKey);
  expect(readLanguage()).toBe("it");
  localStorage.setItem(languageStorageKey, "es");
  expect(readLanguage()).toBe("es");
  localStorage.setItem(languageStorageKey, "unknown");
  expect(readLanguage()).toBe("it");
  vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
    throw new Error("Storage blocked");
  });
  expect(readLanguage()).toBe("it");
});

it("selects CLDR plural forms and interpolates numbers without interpreting data as markup", () => {
  const catalog: Catalog = {
    nativeName: "العربية",
    direction: "rtl",
    messages: {
      count: {
        zero: "zero {count}",
        one: "one {count}",
        two: "two {count}",
        few: "few {count}",
        many: "many {count}",
        other: "other {count}",
      },
      text: "{name}: {count}",
    },
  };
  for (const [count, form] of [
    [0, "zero"],
    [1, "one"],
    [2, "two"],
    [3, "few"],
    [11, "many"],
    [100, "other"],
  ] as const)
    expect(translate(catalog, "ar", "count", { count })).toBe(
      `${form} ${count.toLocaleString("ar")}`,
    );
  const french: Catalog = {
    nativeName: "Français",
    messages: { count: { one: "singulier", other: "pluriel" } },
  };
  expect(translate(french, "fr", "count", { count: 0 })).toBe("singulier");
  expect(translate(french, "en", "count", { count: 0 })).toBe("pluriel");
  expect(
    translate(catalog, "de", "text", {
      name: "<script>{count}</script>",
      count: 1234,
    }),
  ).toBe("<script>{count}</script>: 1.234");
  expect(() => translate(catalog, "en", "text", {})).toThrow(
    "Missing translation argument",
  );
  expect(() => t("missing.contract.key")).toThrow("Unknown translation key");
});

it("switches the picker, static guide copy and client errors at runtime while preserving typed content", async () => {
  function Draft() {
    const [body, setBody] = useState("");
    return (
      <textarea
        aria-label="User note"
        value={body}
        onChange={(event) => setBody(event.target.value)}
      />
    );
  }
  const failure = new ApiError(401, "wrong_password");
  render(
    <>
      <LanguagePicker />
      <Draft />
      <FirstRunGuide onSkip={() => {}} onFinish={() => {}} />
    </>,
  );
  const input = screen.getByLabelText("User note") as HTMLTextAreaElement;
  fireEvent.change(input, { target: { value: "My words #Ideas <script>" } });
  const picker = screen.getByRole("combobox", { name: "Language" });
  fireEvent.change(picker, { target: { value: "es" } });
  await waitFor(() => expect(getLanguage()).toBe("es"));
  expect(document.documentElement.lang).toBe("es");
  expect(document.documentElement.dir).toBe("ltr");
  expect(localStorage.getItem(languageStorageKey)).toBe("es");
  expect((screen.getByRole("combobox") as HTMLSelectElement).value).toBe("es");
  expect(
    screen.getByRole("heading", {
      name: t("timeline.a_quiet_place_for_loose_ideas"),
    }),
  ).toBeTruthy();
  expect(failure.message).toBe(t("errors.that_password_didn_t_work"));
  expect(input.value).toBe("My words #Ideas <script>");
  fireEvent.change(screen.getByRole("combobox"), { target: { value: "it" } });
  await waitFor(() => expect(getLanguage()).toBe("it"));
  expect(readLanguage()).toBe("it");
  expect(input.value).toBe("My words #Ideas <script>");
});

it("keeps third-party metadata and server details intact and translates only explicit bundled IDs", async () => {
  await act(async () => setLanguage("es"));
  const plugin = {
    id: "ask",
    name: "Operator title",
    description: "Operator description",
    contributes: {
      commands: [{ id: "ask", title: "Operator command", where: ["palette"] }],
    },
  } as Plugin;
  expect(localizePlugin({ ...plugin, origin: "operator" })).toEqual({
    ...plugin,
    origin: "operator",
  });
  expect(localizePlugin(plugin)).toBe(plugin);
  const receipt = { ...plugin, origin: "bundled" as const };
  expect(
    localizeCatalog({ plugins: [plugin] }, [receipt]).plugins[0].name,
  ).toBe(t("plugin.ask.name"));
  expect(localizePlugin({ ...plugin, origin: "operator" }, receipt).name).toBe(
    "Operator title",
  );
  const localized = localizePlugin({ ...plugin, origin: "bundled" });
  expect(localized.id).toBe("ask");
  expect(localized.contributes?.commands?.[0].id).toBe("ask");
  expect(localized.name).toBe(t("plugin.ask.name"));
  expect(localized.contributes?.commands?.[0].title).toBe(
    t("plugin.ask.commands.ask.title"),
  );
  expect(
    new ApiError(400, "custom_plugin_code", undefined, "Third-party detail")
      .message,
  ).toBe("Third-party detail");
  expect(hasTranslation("plugin.ask.name")).toBe(true);
  expect(catalogs.en.nativeName).toBe("English");
});

it("keeps the current language and saved preference when an optional catalog chunk fails", async () => {
  vi.resetModules();
  vi.doMock("../src/locales/fr.json", () => {
    throw new Error("Catalog unavailable");
  });
  try {
    const isolated = await import("../src/lib/i18n");
    await isolated.setLanguage("en");
    await expect(isolated.setLanguage("fr")).resolves.toBe(false);
    expect(isolated.getLanguage()).toBe("en");
    expect(localStorage.getItem(languageStorageKey)).toBe("en");
  } finally {
    vi.doUnmock("../src/locales/fr.json");
    vi.resetModules();
  }
});

it("every literal translation call references a source catalog key", () => {
  const sources = import.meta.glob<string>("../src/**/*.{ts,tsx}", {
    query: "?raw",
    import: "default",
    eager: true,
  });
  for (const [file, source] of Object.entries(sources)) {
    for (const match of source.matchAll(/\bt\(\s*["']([^"']+)["']/g)) {
      expect(hasTranslation(match[1]), `${file}: ${match[1]}`).toBe(true);
    }
  }
});

it("localizes compact manifest UI through trusted catalog provenance without changing cached protocol data", async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { staleTime: Infinity, retry: false } },
  });
  const plugin = {
    id: "digest",
    name: "Synthetic manifest title",
    description: "Synthetic description",
    contributes: {
      views: [{ id: "digests", title: "Synthetic view title", filter: {} }],
    },
  } as Plugin;
  client.setQueryData(["manifest"], { plugins: [plugin] });
  client.setQueryData(["catalog"], {
    plugins: [{ ...plugin, origin: "bundled" }],
  });
  function Probe() {
    const manifest = useManifest();
    return (
      <p>
        {manifest.data?.plugins[0].name} /{" "}
        {manifest.data?.plugins[0].contributes?.views?.[0].title}
      </p>
    );
  }
  render(
    <QueryClientProvider client={client}>
      <Probe />
    </QueryClientProvider>,
  );
  expect(
    screen.getByText("Synthetic manifest title / Synthetic view title"),
  ).toBeTruthy();
  await act(async () => setLanguage("es"));
  expect(
    screen.getByText(
      `${t("plugin.digest.name")} / ${t("plugin.digest.views.digests.title")}`,
    ),
  ).toBeTruthy();
  expect(
    client.getQueryData<{ plugins: Plugin[] }>(["manifest"])?.plugins[0],
  ).toBe(plugin);
  client.clear();
});
