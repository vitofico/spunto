import { readFileSync, writeFileSync } from "node:fs";
import { themeIssues } from "./theme-contrast.mjs";
const tokens = JSON.parse(readFileSync("tokens.json", "utf8"));
if (
    JSON.stringify(Object.keys(tokens.catalog)) !==
    JSON.stringify(Object.keys(tokens.themes))
)
    throw new Error("Theme catalog and palette IDs must match in order.");
for (const [id, modes] of Object.entries(tokens.themes)) {
    const issues = themeIssues(modes);
    if (issues.length) throw new Error(`${id}: ${issues.join("; ")}`);
}
writeFileSync(
    "../server/src/spunto/host/theme_tokens.json",
    JSON.stringify(tokens.themes, null, 2) + "\n",
);
writeFileSync(
    "../server/src/spunto/host/theme_catalog.json",
    JSON.stringify(tokens.catalog, null, 2) + "\n",
);
writeFileSync(
    "src/lib/theme-catalog.ts",
    "// Generated from web/tokens.json; run pnpm generate:tokens.\n" +
        `export const themeCatalog = ${JSON.stringify(
            Object.entries(tokens.catalog).map(([id, details]) => ({
                id,
                ...details,
            })),
            null,
            2,
        )} as const;\n` +
        'export type BuiltinTheme = (typeof themeCatalog)[number]["id"];\n' +
        "export type Theme = string;\n",
);
const rules = Object.entries(tokens.themes).flatMap(([theme, modes]) =>
    Object.entries(modes).map(
        ([mode, values]) =>
            `:root[data-theme="${theme}"][data-mode="${mode}"]{${Object.entries(
                values,
            )
                .map(([k, v]) => `--${k}:${v};`)
                .join("")}color-scheme:${mode};}`,
    ),
);
const systemRules = rules
    .filter((r) => r.includes('data-mode=\"light\"'))
    .map((r) => r.replace('data-mode=\"light\"', 'data-mode=\"system\"'));
const darkRules = rules
    .filter((r) => r.includes('data-mode=\"dark\"'))
    .map((r) => r.replace('data-mode=\"dark\"', 'data-mode=\"system\"'));
writeFileSync(
    "src/styles/tokens.css",
    "/* Generated from web/tokens.json; run node scripts/generate-tokens.mjs. */\n" +
        rules.join("\n") +
        "\n" +
        systemRules.join("\n") +
        "\n@media(prefers-color-scheme:dark){" +
        darkRules.join("\n") +
        "}\n",
);
const previews = Object.entries(tokens.themes).flatMap(([theme, modes]) =>
    Object.entries(modes).map(
        ([mode, values]) =>
            `:root[data-mode="${mode}"] [data-theme-preview="${theme}"]{${Object.entries(
                values,
            )
                .map(([k, v]) => `--${k}:${v};`)
                .join("")}}`,
    ),
);
writeFileSync(
    "src/styles/theme-previews.css",
    "/* Generated from web/tokens.json; run pnpm generate:tokens. */\n" +
        previews.join("\n") +
        "\n" +
        previews
            .filter((r) => r.includes('data-mode="light"'))
            .map((r) => r.replace('data-mode="light"', 'data-mode="system"'))
            .join("\n") +
        "\n@media(prefers-color-scheme:dark){" +
        previews
            .filter((r) => r.includes('data-mode="dark"'))
            .map((r) => r.replace('data-mode="dark"', 'data-mode="system"'))
            .join("\n") +
        "}\n",
);
