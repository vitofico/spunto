import { expect, test } from "@playwright/test";

const words =
  "A synthetic long paragraph retains one complete passage. ".repeat(5);
const notes = [
  {
    title: "#work/ideas A synthetic long paragraph",
    body: `#work/ideas ${words}`,
    html: `<p><a class="label" href="/l/work/ideas">#work/ideas</a> ${words}</p>`,
  },
  {
    title: "#work/ideas",
    body: "#work/ideas",
    html: '<p><a class="label" href="/l/work/ideas">#work/ideas</a></p>',
  },
  {
    title: "(#work/ideas)",
    body: "(#work/ideas)",
    html: '<p>(<a class="label" href="/l/work/ideas">#work/ideas</a>)</p>',
  },
  {
    title: "Complete heading",
    body: "## Complete heading\n\nSecond passage.",
    html: "<h2>Complete heading</h2><p>Second passage.</p>",
  },
].map((note, index) => ({
  ...note,
  id: `0192f3c4-1111-7000-8000-00000000000${index + 1}`,
  labels: ["work/ideas"],
  version: 1,
  change_seq: 1,
  created_at: "2026-01-01T12:00:00Z",
  updated_at: "2026-01-01T12:00:00Z",
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
}));

for (const width of [320, 390, 1280]) {
  test(`single-passage previews remain keyboard reachable at ${width}px`, async ({
    page,
    browserName,
  }, info) => {
    await page.setViewportSize({ width, height: 900 });
    await page.route("**/api/v1/**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      const json = (value: unknown) =>
        route.fulfill({
          contentType: "application/json",
          body: JSON.stringify(value),
        });
      if (path.endsWith("/changes/wait")) return route.abort();
      if (path.endsWith("/meta"))
        return json({
          version: "preview-fixture",
          setup_pending: false,
          plugins_rev: 0,
          ai_capabilities: [],
        });
      if (path.endsWith("/auth/session"))
        return json({ owner_id: "preview-fixture" });
      if (path.endsWith("/plugins/manifest"))
        return json({ ui: 1, plugins_rev: 0, plugins: [] });
      if (path.endsWith("/settings"))
        return json({
          theme: "carta",
          plugin_order: [],
          hidden_views: [],
          hidden_mcp_tools: [],
        });
      if (path.endsWith("/labels"))
        return json({ labels: [{ path: "work/ideas", count: 4 }], total: 1 });
      if (path.endsWith("/notes")) return json({ notes, next_cursor: null });
      const note = notes.find((item) => path.endsWith(`/notes/${item.id}`));
      if (note) return json(note);
      if (path.endsWith("/suggestions")) return json({ suggestions: [] });
      return json({});
    });
    await page.goto("/");
    const cards = page.locator("article.entry");
    await expect(cards).toHaveCount(4);
    for (const index of [0, 1, 2]) {
      const card = cards.nth(index);
      await expect(card.locator(".entry-title")).toHaveCount(0);
      await expect(card.locator(".note-body a.label")).toHaveCount(0);
      await expect(card.locator(".label-pill")).toHaveCount(1);
      await expect(card.locator("a a")).toHaveCount(0);
      const link = card.locator("a.time");
      await expect(link).toHaveAttribute("href", `/n/${notes[index].id}`);
      await page.keyboard.press(browserName === "webkit" ? "Alt+Tab" : "Tab");
      await link.focus();
      await expect(link).toBeFocused();
      const visible = await link.evaluate((element) => {
        const rect = element.getBoundingClientRect(),
          style = getComputedStyle(element);
        return {
          left: rect.left,
          right: rect.right,
          width: rect.width,
          height: rect.height,
          clip: style.clipPath,
          outline: style.outlineStyle,
        };
      });
      expect(visible.left).toBeGreaterThanOrEqual(0);
      expect(visible.right).toBeLessThanOrEqual(width);
      expect(visible.width).toBeGreaterThan(10);
      expect(visible.height).toBeGreaterThan(10);
      expect(visible.clip).toBe("none");
      expect(visible.outline).not.toBe("none");
      await page.keyboard.press(browserName === "webkit" ? "Alt+Tab" : "Tab");
      await page.keyboard.press(
        browserName === "webkit" ? "Alt+Shift+Tab" : "Shift+Tab",
      );
      await expect(link).toBeFocused();
    }
    await expect(cards.nth(0).locator(".note-body")).toHaveText(words.trim());
    await expect(cards.nth(1).locator(".note-body")).toBeEmpty();
    await expect(cards.nth(2).locator(".note-body")).toBeEmpty();
    await expect(cards.nth(3).locator(".entry-title")).toHaveText(
      "Complete heading",
    );
    await expect(cards.nth(3).locator(".note-body")).toHaveText(
      "Second passage.",
    );
    const type = await cards.nth(3).evaluate((card) => {
      const title = getComputedStyle(card.querySelector(".entry-title")!);
      const body = getComputedStyle(card.querySelector(".note-body")!);
      return {
        titleSize: title.fontSize,
        bodySize: body.fontSize,
        titleLine: title.lineHeight,
        bodyLine: body.lineHeight,
      };
    });
    expect(type.titleSize).toBe(type.bodySize);
    expect(type.titleLine).toBe(type.bodyLine);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBe(width);
    await page.screenshot({
      path: info.outputPath(`single-passage-${width}.png`),
      fullPage: true,
    });
    await cards.nth(1).locator("a.time").focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(new RegExp(`/n/${notes[1].id}$`));
  });
}
