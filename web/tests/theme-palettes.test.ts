import { readFileSync } from "node:fs";
import { expect, it } from "vitest";
import { themeCatalog } from "../src/lib/theme-catalog";
import { themeIssues, contrast } from "../scripts/theme-contrast.mjs";

const source = JSON.parse(readFileSync("tokens.json", "utf8"));
it("adds exactly two vivid themes and preserves all original palette values", () => {
  const original = JSON.parse(
    readFileSync("tests/fixtures/original-themes.json", "utf8"),
  );
  expect(themeCatalog.map(({ id }) => id)).toEqual([
    "carta",
    "ardesia",
    "salvia",
    "corallo",
    "elettrica",
  ]);
  for (const id of Object.keys(original))
    expect(source.themes[id]).toEqual(original[id]);
  expect(Object.keys(source.themes)).toEqual(Object.keys(source.catalog));
});
it("checks all specified semantic pairs in both modes without rounding", () => {
  for (const modes of Object.values(source.themes))
    expect(themeIssues(modes)).toEqual([]);
  expect(contrast("#000000", "#FFFFFF")).toBe(21);
  const broken = structuredClone(source.themes.corallo);
  broken.dark["ink-3"] = broken.dark.sunken;
  expect(themeIssues(broken)).toContain(
    "ink-3 on sunken is 1.00:1 in dark (requires 4.5:1)",
  );
  broken.light.accent = "url(https://example.invalid/font)";
  expect(themeIssues(broken)).toContain(
    "Missing or invalid colour roles in light",
  );
  const secondary = structuredClone(source.themes.elettrica);
  secondary.light["ink-2"] = secondary.light.paper;
  expect(themeIssues(secondary)).toContain(
    "ink-2 on paper is 1.00:1 in light (requires 4.5:1)",
  );
});
it("rejects coerced colour arrays and non-object modes before contrast math", () => {
  const arrays = Object.fromEntries(
    Object.keys(source.themes.carta.light).map((role) => [role, ["#000000"]]),
  );
  expect(themeIssues({ light: arrays, dark: arrays })).toEqual([
    "Missing or invalid colour roles in light",
    "Missing or invalid colour roles in dark",
  ]);
  expect(themeIssues({ light: [], dark: "#000000" })).toHaveLength(2);
});
it("ships the identical server palettes and all light/dark/system CSS previews", () => {
  expect(
    JSON.parse(
      readFileSync("../server/src/spunto/host/theme_tokens.json", "utf8"),
    ),
  ).toEqual(source.themes);
  expect(
    JSON.parse(
      readFileSync("../server/src/spunto/host/theme_catalog.json", "utf8"),
    ),
  ).toEqual(source.catalog);
  const css = readFileSync("src/styles/tokens.css", "utf8");
  const previews = readFileSync("src/styles/theme-previews.css", "utf8");
  for (const [id, modes] of Object.entries(source.themes)) {
    for (const [mode, values] of Object.entries(
      modes as Record<string, Record<string, string>>,
    )) {
      const rule = `:root[data-theme="${id}"][data-mode="${mode}"]{`;
      expect(css).toContain(rule);
      const preview = `:root[data-mode="${mode}"] [data-theme-preview="${id}"]{`;
      expect(previews).toContain(preview);
      for (const [key, value] of Object.entries(values)) {
        expect(
          css.slice(css.indexOf(rule), css.indexOf("}", css.indexOf(rule))),
        ).toContain(`--${key}:${value};`);
        expect(
          previews.slice(
            previews.indexOf(preview),
            previews.indexOf("}", previews.indexOf(preview)),
          ),
        ).toContain(`--${key}:${value};`);
      }
    }
    expect(css).toContain(`:root[data-theme="${id}"][data-mode="system"]`);
    expect(previews).toContain(
      `:root[data-mode="system"] [data-theme-preview="${id}"]`,
    );
  }
});
