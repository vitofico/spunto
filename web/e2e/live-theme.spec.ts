import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";

const palettes: Record<
  string,
  Record<string, Record<string, string>>
> = JSON.parse(readFileSync("tokens.json", "utf8")).themes;
const rgb = (hex: string) =>
  `rgb(${[1, 3, 5].map((start) => parseInt(hex.slice(start, start + 2), 16)).join(", ")})`;

test("live theme controls recolor the wordmark, labels, Ask, and wave without reloading", async ({
  page,
}) => {
  let settings = {
    theme: "carta",
    plugin_order: [],
    hidden_views: [],
    hidden_mcp_tools: [],
  };
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "onLine", { get: () => true });
  });
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    const json = (value: unknown) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify(value),
      });
    if (path.endsWith("/meta"))
      return json({
        version: "fixture",
        setup_pending: false,
        plugins_rev: 1,
        ai_capabilities: [],
      });
    if (path.endsWith("/auth/session"))
      return json({
        owner_id: "theme-fixture",
        credential_id: "theme-session",
      });
    if (path.endsWith("/plugins/manifest"))
      return json({
        ui: 1,
        plugins_rev: 1,
        plugins: [
          {
            id: "ask",
            name: "Ask your notes",
            version: "1",
            position: 0,
            contributes: {
              commands: [
                { id: "ask", title: "Ask your notes", where: ["palette"] },
              ],
            },
          },
        ],
        theme_catalog: [
          {
            id: "river-ink",
            name: "River Ink",
            status: "available",
            tokens: 1,
            ...palettes.ardesia,
            accents: [{ id: "forest", name: "Forest", ...palettes.salvia }],
          },
        ],
      });
    if (path.endsWith("/themes/river-ink.css")) {
      const palette =
        url.searchParams.get("accent") === "forest"
          ? palettes.salvia
          : palettes.ardesia;
      const css = ["light", "dark"]
        .map(
          (mode) =>
            `:root[data-theme="river-ink"][data-mode="${mode}"]{${Object.entries(
              palette[mode]!,
            )
              .map(([role, color]) => `--${role}:${color}`)
              .join(";")}}`,
        )
        .join("\n");
      return route.fulfill({ contentType: "text/css", body: css });
    }
    if (path.endsWith("/settings")) {
      if (route.request().method() === "PUT")
        settings = { ...settings, ...route.request().postDataJSON() };
      return json(settings);
    }
    if (path.endsWith("/labels"))
      return json({ labels: [{ path: "ideas", count: 1 }], total: 1 });
    if (path.endsWith("/notes"))
      return json({
        notes: [
          {
            id: "0192f3c4-1111-7000-8000-000000000001",
            body: "Keep #ideas",
            title: "Keep",
            html: '<p>Keep <a href="/l/ideas">#ideas</a></p>',
            labels: ["ideas"],
            version: 1,
            change_seq: 1,
            created_at: "2026-10-14T08:30:00Z",
            updated_at: "2026-10-14T08:30:00Z",
            pinned: false,
            archived: false,
            trashed: false,
            private: false,
            open_tasks: 0,
            source: { kind: "web", ref: null, url: null },
            origin: "owner",
            machine: false,
            conflict_of: null,
            suggestions: [],
            annotations: [],
          },
        ],
        next_cursor: null,
      });
    if (path.endsWith("/search")) return json({ hits: [], next_cursor: null });
    if (path.endsWith("/suggestions")) return json({ suggestions: [] });
    if (path.endsWith("/changes/wait")) return route.abort("failed");
    return json({});
  });
  await page.goto("/settings/appearance");
  await page.getByRole("button", { name: "Ardesia", exact: true }).click();
  await expect.poll(() => settings.theme).toBe("ardesia");
  await expect(
    page.locator(".rail-heading .wordmark > span:last-child"),
  ).toHaveCSS("color", rgb(palettes.ardesia!.light!.accent!));
  await page
    .getByRole("switch", { name: "Use a different theme on this device" })
    .click();

  const verifyColors = async (palette: Record<string, string>) => {
    const accent = rgb(palette.accent!);
    await expect(
      page.locator(".rail-heading .wordmark > span:last-child"),
    ).toHaveCSS("color", accent);
    await expect(page.locator('.label-tree a[href="/l/ideas"]')).toHaveCSS(
      "color",
      rgb(palette["ink-2"]!),
    );
    await expect(page.locator("body")).toHaveCSS(
      "background-color",
      rgb(palette.paper!),
    );
    await expect
      .poll(() =>
        page
          .locator(".rail-heading")
          .evaluate(
            (element) => getComputedStyle(element, "::before").backgroundColor,
          ),
      )
      .toBe(accent);
    await page.locator(".rail-search").click();
    await expect(
      page.locator(".palette-row").filter({ hasText: "Ask your notes" }),
    ).toHaveCSS("color", rgb(palette["ink-2"]!));
    await page.keyboard.press("Escape");
    await page.locator(".rail-heading .wordmark").click();
    await expect(page.locator('.note-body a[href="/l/ideas"]')).toHaveCSS(
      "color",
      accent,
    );
    await page.locator('.rail-foot a[href="/settings/appearance"]').click();
  };
  const chooseMode = async (mode: string) =>
    page
      .getByRole("group", { name: "Mode", exact: true })
      .getByRole("button", { name: mode, exact: true })
      .click();
  for (const [id, name] of Object.entries({
    carta: "Carta",
    ardesia: "Ardesia",
    salvia: "Salvia",
    corallo: "Corallo",
    elettrica: "Elettrica",
  })) {
    await page.getByRole("button", { name, exact: true }).click();
    for (const mode of ["light", "dark"]) {
      await chooseMode(mode === "light" ? "Light" : "Dark");
      await verifyColors(palettes[id]![mode]!);
    }
  }
  await page.getByRole("button", { name: "River Ink", exact: true }).click();
  for (const [accent, palette] of [
    ["Default", palettes.ardesia],
    ["Forest", palettes.salvia],
  ] as const) {
    await page
      .getByRole("group", { name: "Accent", exact: true })
      .getByRole("button", { name: accent, exact: true })
      .click();
    for (const mode of ["light", "dark"]) {
      await chooseMode(mode === "light" ? "Light" : "Dark");
      await verifyColors(palette![mode]!);
    }
  }
  await page.getByRole("button", { name: "Carta", exact: true }).click();
  await verifyColors(palettes.carta!.dark!);
  await expect(page.locator("link[data-spunto-theme-pack]")).toHaveCount(0);
  await expect(page.locator("html")).not.toHaveAttribute("data-accent");
  await page.reload();
  await verifyColors(palettes.carta!.dark!);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator(".topbar .wordmark > span")).toHaveCSS(
    "color",
    rgb(palettes.carta!.dark!.accent!),
  );
  // Mobile artwork follows the shared ambient surface, independently of the
  // header layout (approved background/home composition, 26 October 2026).
  const artwork = page.locator('.app[data-artwork-surface="true"]');
  await expect(artwork).toBeVisible();
  await expect
    .poll(() =>
      artwork.evaluate(
        (element) => getComputedStyle(element, "::before").content,
      ),
    )
    .not.toBe("none");
  await expect
    .poll(() =>
      artwork.evaluate(
        (element) => getComputedStyle(element, "::before").maskImage,
      ),
    )
    .toContain("/spunto-woodgrain-mask.svg");
  await expect
    .poll(() =>
      artwork.evaluate(
        (element) => getComputedStyle(element, "::before").backgroundColor,
      ),
    )
    .toBe(rgb(palettes.carta!.dark!.accent!));
  await page.screenshot({
    path: test.info().outputPath("live-theme-mobile.png"),
    fullPage: true,
  });
});
