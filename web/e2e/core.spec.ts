import { test, expect, type Page } from "@playwright/test";
const initial = {
  id: "0192f3c4-1111-7000-8000-000000000001",
  body: "A first thought #ideas\n- [ ] Read it again",
  title: "A first thought",
  labels: ["ideas"],
  version: 1,
  change_seq: 1,
  created_at: "2026-10-14T08:30:00Z",
  updated_at: "2026-10-14T08:30:00Z",
  pinned: false,
  archived: false,
  trashed: false,
  private: false,
  open_tasks: 1,
  source: { kind: "web", ref: null, url: null },
  origin: "owner",
  machine: false,
  conflict_of: null as string | null,
  suggestions: [],
  annotations: [],
  html: '<p>A first thought <a href="/l/ideas">#ideas</a></p><ul><li><input type="checkbox" data-checkbox-index="0" data-checkbox-offset="26"> Read it again</li></ul>',
};
async function fixture(page: Page) {
  let notes = [structuredClone(initial)];
  let offline = false;
  let conflictNext = false;
  const patchVersions: string[] = [];
  const sentKeys: string[] = [];
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      path = url.pathname;
    const fulfill = (data: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify(data),
      });
    if (path.endsWith("/changes/wait")) return route.abort("failed");
    if (path.endsWith("/meta"))
      return fulfill({
        version: "test",
        public_url: "http://127.0.0.1:5173",
        setup_pending: false,
        unencrypted: false,
        plugins_rev: 0,
        ai_capabilities: [],
      });
    if (path.endsWith("/auth/session"))
      return fulfill({ owner_id: "owner-fixture" });
    if (path.endsWith("/plugins/manifest"))
      return fulfill({ ui: 1, plugins_rev: 0, plugins: [] });
    if (path.endsWith("/settings"))
      return fulfill({
        theme: "carta",
        hidden_views: [],
        hidden_mcp_tools: [],
        plugin_order: [],
      });
    if (path.endsWith("/labels"))
      return fulfill({
        labels: [
          { key: "ideas", path: "ideas", count: notes.length },
          { key: "project/spunto", path: "project/spunto", count: 1 },
        ],
        total: 2,
      });
    if (path.endsWith("/notes") && request.method() === "POST") {
      sentKeys.push(request.headers()["idempotency-key"]);
      if (offline) return route.abort("failed");
      const body = request.postDataJSON().body;
      const note = {
        ...initial,
        id: crypto.randomUUID(),
        body,
        title: body.split("\n")[0],
        html: "<p>" + body.replaceAll("<", "&lt;") + "</p>",
      };
      notes.unshift(note);
      return fulfill(note, 201);
    }
    if (path.endsWith("/notes")) {
      const view = url.searchParams.get("view");
      return fulfill({
        notes: notes.filter((n) =>
          view === "trash"
            ? n.trashed
            : view === "archive"
              ? n.archived
              : !n.archived && !n.trashed,
        ),
        next_cursor: null,
      });
    }
    if (path.endsWith("/search"))
      return fulfill({
        hits: notes.filter((n) =>
          n.body.includes(url.searchParams.get("q") ?? ""),
        ),
        next_cursor: null,
        semantic: "off",
      });
    const id = path.split("/")[4];
    const note = notes.find((n) => n.id === id);
    if (path.endsWith("/revisions"))
      return fulfill([
        {
          version: 1,
          body: initial.body,
          change_kind: "create",
          changed_by: "owner",
          changed_at: initial.created_at,
        },
      ]);
    if (path.endsWith("/flags") && note) {
      Object.assign(note, request.postDataJSON());
      return fulfill(note);
    }
    if (note && request.method() === "PATCH") {
      if (offline) return route.abort("failed");
      patchVersions.push(request.headers()["if-match"]);
      if (conflictNext) {
        conflictNext = false;
        notes.push({
          ...structuredClone(initial),
          id: "0192f3c4-2222-7000-8000-000000000001",
          body: request.postDataJSON().body,
          conflict_of: note.id,
        });
        note.body = "Latest saved server text";
        note.html = "<p>Latest saved server text</p>";
        note.version = 4;
        return fulfill(
          {
            code: "version_conflict",
            conflict_id: "0192f3c4-2222-7000-8000-000000000001",
          },
          409,
        );
      }
      note.body = request.postDataJSON().body;
      note.version++;
      note.html = "<p>" + note.body.replaceAll("<", "&lt;") + "</p>";
      return fulfill(note);
    }
    if (note) return fulfill(note);
    return fulfill({ code: "not_found" }, 404);
  });
  return {
    setOffline: (value: boolean) => {
      offline = value;
    },
    conflictNext: () => {
      conflictNext = true;
    },
    patchVersions,
    notes: () => notes,
    keys: sentKeys,
  };
}
test("empty long labels wrap without overlapping their compact actions", async ({
  page,
}, info) => {
  await fixture(page);
  await page.route("**/api/v1/notes?**", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ notes: [], next_cursor: null }),
    }),
  );
  await page.setViewportSize({ width: 390, height: 844 });
  const label = "layout/" + "x".repeat(110);
  await page.goto("/l/" + label);
  await expect(
    page.getByRole("heading", { name: "Nothing under #" + label + " yet." }),
  ).toBeVisible();
  await expect(page.locator(".empty p")).toHaveText(
    "Notes you write here get #" + label + " automatically.",
  );
  await expect(page.locator("body")).toHaveJSProperty("scrollWidth", 390);
  const heading = await page.locator(".label-title h1").boundingBox();
  const actions = await page.getByLabel("Label actions").boundingBox();
  expect(
    heading!.y + heading!.height <= actions!.y ||
      heading!.x + heading!.width <= actions!.x,
  ).toBe(true);
  await page.screenshot({
    path: info.outputPath("empty-long-label-mobile.png"),
    fullPage: true,
  });
});

test("all plugins off is complete; capture, label completion and keyboard palette work", async ({
  page,
}, testInfo) => {
  const state = await fixture(page);
  await page.goto("/");
  await expect(page.getByLabel("New note")).not.toBeFocused();
  await page.screenshot({
    path: testInfo.outputPath("core-home.png"),
    fullPage: true,
  });
  await expect(
    page.getByText("A first thought", { exact: false }).first(),
  ).toBeVisible();
  await expect(page.getByText("Review", { exact: true })).toHaveCount(0);
  await page.getByLabel("New note").fill("A second #pro");
  await page.getByRole("option", { name: "#project/spunto" }).click();
  await expect(page.getByLabel("New note")).toHaveValue(
    "A second #project/spunto ",
  );
  await page.getByLabel("New note").press("Control+Enter");
  await expect(page.getByLabel("New note")).toHaveValue("");
  await expect.poll(() => state.notes().length).toBe(2);
  await page.keyboard.press("Control+k");
  await expect(
    page.getByRole("dialog", { name: "Search and commands" }),
  ).toBeVisible();
  await page
    .getByRole("textbox", { name: "Search and commands" })
    .fill("second");
  await page
    .getByRole("dialog", { name: "Search and commands" })
    .getByRole("option", { name: "A second #project/spunto" })
    .click();
  await expect(page).toHaveURL(/\/n\//);
  await expect(
    page.getByRole("button", { name: "Edit", exact: true }),
  ).toBeVisible();
  // Nothing claims "Saved" before an edit, and Ctrl/Cmd+S saves in place.
  await expect(page.getByText("Saved", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Edit", exact: true }).click();
  await page.getByLabel("Edit note").fill("A changed thought #ideas");
  await page.getByLabel("Edit note").press("Control+s");
  await expect
    .poll(() => state.notes()[0].body)
    .toBe("A changed thought #ideas");
  await expect(page.getByText("Saved", { exact: true })).toBeVisible();
  await expect(page.getByLabel("Edit note")).toBeFocused();
});
test("an unreachable capture waits on the timeline across reload and retries the same key", async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto("/");
  state.setOffline(true);
  await page.getByLabel("New note").fill("Kept on this device");
  await page.getByLabel("New note").press("Control+Enter");
  // The composer is free at once; the capture shows on the timeline until the server has it.
  await expect(page.getByLabel("New note")).toHaveValue("");
  const waiting = page.locator("article.entry.queued");
  await expect(waiting).toContainText("Kept on this device");
  await expect(waiting).toContainText("Waiting to sync");
  await expect(page.locator(".rail [role=status]")).toHaveText(
    "Offline · 1 note waiting",
  );
  // The offline banner says it once; the composer does not repeat it.
  await expect(page.locator(".composer .error")).toHaveCount(0);
  await expect.poll(() => state.keys.length).toBeGreaterThan(0);
  const firstKey = state.keys[0];
  await page.reload();
  await expect(page.getByLabel("New note")).toHaveValue("");
  await expect(waiting).toContainText("Kept on this device");
  state.setOffline(false);
  await page.evaluate(() => window.dispatchEvent(new Event("online")));
  await expect(waiting).toHaveCount(0);
  expect(new Set(state.keys)).toEqual(new Set([firstKey]));
  expect(
    state.notes().filter((note) => note.body === "Kept on this device"),
  ).toHaveLength(1);
  await expect(
    page.getByText("Kept on this device", { exact: true }),
  ).toBeVisible();
});
test("compact navigation, date search, checklist and private flags remain usable", async ({
  page,
}) => {
  await fixture(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await expect(page.locator("body")).toHaveJSProperty("scrollWidth", 390);
  await page.getByRole("button", { name: "Open navigation" }).click();
  await page.getByRole("link", { name: "All notes", exact: false }).click();
  const card = page.locator("article.entry").filter({ hasText: initial.title });
  const passage = card.locator(".note-body p").first();
  await expect(passage).toHaveText("A first thought #ideas");
  // Open the visible passage at its first words, clear of the embedded label link.
  await passage.click({ position: { x: 8, y: 8 } });
  await expect(page).toHaveURL(new RegExp("/n/" + initial.id + "$"));
  await page.getByRole("checkbox").click();
  await expect(page.getByText("Saved", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "More actions" }).click();
  await page.getByRole("menuitem", { name: "Make private" }).click();
  await expect(
    page.locator(".note-meta").getByText("private", { exact: true }),
  ).toBeVisible();
  await page.getByText("Revision history").click();
  await expect(
    page.getByRole("button", { name: "Restore", exact: true }),
  ).toBeVisible();
  await page.goto("/search?q=thought");
  await expect(page.getByLabel("Search notes")).toHaveValue("thought");
  await page.getByLabel("From date").fill("2026-10-01");
  await expect(
    page.getByText("A first thought", { exact: false }).first(),
  ).toBeVisible();
});

test("conflict recovery compares both versions and keeps later local typing across reload", async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto("/n/" + initial.id);
  await page.getByRole("button", { name: "Edit", exact: true }).click();
  await page.getByLabel("Edit note").fill("Earlier submitted local text");
  state.conflictNext();
  await page.getByLabel("Edit note").press("Control+s");
  const notice = page.getByRole("region", { name: "Edited on two devices" });
  await expect(notice).toContainText(
    "Edited on two devices. Both versions are kept",
  );
  await page
    .getByLabel("Edit note")
    .fill("Later local text kept on this device");
  await page.reload();
  await expect(page.getByLabel("Edit note")).toHaveValue(
    "Later local text kept on this device",
  );
  await expect(
    page.getByRole("button", { name: "Done", exact: true }),
  ).toBeDisabled();
  await notice.getByRole("button", { name: "Compare", exact: true }).click();
  await expect(notice.locator(".diff del")).toContainText(
    "Latest saved server text",
  );
  await expect(notice.locator(".diff ins")).toContainText(
    "Later local text kept on this device",
  );
  await notice.getByRole("button", { name: "Keep mine", exact: true }).click();
  await expect(page.getByText("Saved", { exact: true })).toBeVisible();
  await expect(notice).toHaveCount(0);
  expect(
    state.notes().find((note) => note.conflict_of === initial.id)?.trashed,
  ).toBe(true);
  await expect(
    page.getByText("Later local text kept on this device", { exact: true }),
  ).toBeVisible();
  expect(state.patchVersions).toEqual(['"1"', '"4"']);
});

test("All notes restores its own focus after Search has been closed", async ({
  page,
}) => {
  await fixture(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  const search = page.getByRole("button", { name: "Search", exact: true });
  await search.click();
  await expect(
    page.getByRole("textbox", { name: "Search and commands" }),
  ).toBeFocused();
  await page.getByRole("button", { name: "Close search and commands" }).click();
  await expect(search).toBeFocused();
  const picker = page.locator(".home-picker");
  await picker.click();
  await expect(page.getByRole("dialog", { name: "Navigation" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(picker).toBeFocused();
});

test("mobile overlays contain focus and restore it without leaving the note", async ({
  page,
}) => {
  await fixture(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/n/" + initial.id);
  const edit = page.getByRole("button", { name: "Edit", exact: true });
  await expect(edit).toBeVisible();
  const navigation = page.getByRole("button", { name: "Open navigation" });
  await navigation.click();
  const drawer = page.getByRole("dialog", { name: "Navigation" });
  await expect(
    drawer.getByRole("button", { name: "Close navigation" }),
  ).toBeFocused();
  await expect(page.locator("#main")).toHaveJSProperty("inert", true);
  await drawer.getByRole("link", { name: "Settings", exact: true }).focus();
  await page.keyboard.press("Tab");
  await expect(drawer.getByRole("link", { name: "Spunto home" })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(navigation).toBeFocused();
  await expect(page).toHaveURL(new RegExp("/n/" + initial.id));

  await edit.focus();
  await page.keyboard.press("?");
  const help = page.getByRole("dialog", { name: "Keyboard shortcuts" });
  const close = help.getByRole("button", { name: "Close", exact: true });
  await expect(close).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(close).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(close).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(edit).toBeFocused();
  await expect(page).toHaveURL(new RegExp("/n/" + initial.id));

  const search = page.getByRole("button", { name: "Search", exact: true });
  await search.click();
  await expect(
    page.getByRole("textbox", { name: "Search and commands" }),
  ).toBeFocused();
  await page.getByRole("button", { name: "Close search and commands" }).click();
  await expect(search).toBeFocused();
  await expect(page).toHaveURL(new RegExp("/n/" + initial.id));
  await expect(page.locator("#main")).toHaveJSProperty("inert", false);
});

test("a native modal keeps keyboard control above the command palette", async ({
  page,
}) => {
  await fixture(page);
  await page.goto("/n/" + initial.id);
  await expect(
    page.getByRole("button", { name: "Edit", exact: true }),
  ).toBeVisible();
  await page.keyboard.press("Meta+k");
  await expect(
    page.getByRole("dialog", { name: "Search and commands" }),
  ).toBeVisible();
  await page.evaluate(() => {
    const dialog = document.createElement("dialog");
    dialog.setAttribute("aria-label", "Synthetic native dialog");
    const done = document.createElement("button");
    done.textContent = "Done";
    dialog.append(done);
    dialog.addEventListener("cancel", (event) => {
      event.preventDefault();
      dialog.close();
      dialog.remove();
    });
    document.querySelector(".palette")!.append(dialog);
    dialog.showModal();
    done.focus();
  });
  await page.keyboard.press("Meta+k");
  await expect(
    page.getByRole("dialog", { name: "Synthetic native dialog" }),
  ).toBeVisible();
  await expect(page.locator(".palette")).toHaveCount(1);
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("dialog", { name: "Synthetic native dialog" }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("dialog", { name: "Search and commands" }),
  ).toBeVisible();
  await expect(page).toHaveURL(new RegExp("/n/" + initial.id));
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page).toHaveURL(new RegExp("/n/" + initial.id));
});

test("Back to a scrolled timeline keeps the place and leaves the composer alone", async ({
  page,
}) => {
  const state = await fixture(page);
  // Long notes get "Show more" once measured, after the list is first drawn.
  const long = Array.from({ length: 16 }, (_, n) => `Line ${n + 1}.`);
  for (let index = 0; index < 30; index++) {
    const body = (
      index % 3 ? [`Note ${index}`] : [`Long ${index}`, ...long]
    ).join("\n\n");
    state.notes().push({
      ...structuredClone(initial),
      id: `0192f3c4-3333-7000-8000-${String(index).padStart(12, "0")}`,
      body,
      title: body.split("\n")[0],
      html: body
        .split("\n\n")
        .map((line) => `<p>${line}</p>`)
        .join(""),
    });
  }
  await page.goto("/");
  await expect(page.locator("article.entry")).toHaveCount(31);
  await expect(page.locator(".show-more").first()).toBeVisible();
  const card = page.locator("article.entry").nth(24);
  await card.scrollIntoViewIfNeeded();
  const place = () =>
    card.evaluate((entry) => [
      window.scrollY,
      Math.round(entry.getBoundingClientRect().top),
    ]);
  const before = await place();
  expect(before[0]).toBeGreaterThan(1000);
  await card.locator("a.time").click();
  await expect(
    page.getByRole("button", { name: "Edit", exact: true }),
  ).toBeVisible();
  await page.goBack();
  await expect(card).toBeVisible();
  await expect
    .poll(async () => {
      const after = await place();
      return after.every(
        (value, index) => Math.abs(value - before[index]) <= 2,
      );
    })
    .toBe(true);
  await expect(page.getByLabel("New note")).not.toBeFocused();
  // Focus is back on the card that was opened, and the list didn't move for it.
  await expect(card.locator("a.time")).toBeFocused();
  expect(
    (await place()).every(
      (value, index) => Math.abs(value - before[index]) <= 2,
    ),
  ).toBe(true);
});

test("label dialogs return focus to Label actions on Cancel and Escape", async ({
  page,
}) => {
  await fixture(page);
  await page.goto("/l/ideas");
  const actions = page.getByRole("button", { name: "Label actions" });
  for (const [item, close] of [
    ["Rename", "Cancel"],
    ["Merge into…", "Escape"],
  ]) {
    await actions.click();
    await page.getByRole("menuitem", { name: item }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    if (close === "Escape") await page.keyboard.press("Escape");
    else await dialog.getByRole("button", { name: close }).click();
    await expect(dialog).toHaveCount(0);
    await expect(actions).toBeFocused();
  }
});

test("focusing compact capture keeps a task target still through the first click", async ({
  page,
}) => {
  const saved = await fixture(page);
  await page.goto("/");
  const card = page.locator("article.entry").filter({ hasText: initial.title });
  await expect(card).toHaveCount(1);
  await page.evaluate(() => document.fonts.ready);
  const before = await card.boundingBox();
  await page.getByLabel("New note").focus();
  expect((await card.boundingBox())?.y).toBe(before?.y);
  const tools = await page
    .locator(".composer-tools > .row:first-child")
    .boundingBox();
  const day = await page.locator(".day").first().boundingBox();
  expect(tools!.y + tools!.height).toBeLessThanOrEqual(day!.y);
  await card.getByRole("checkbox").click();
  await expect.poll(() => saved.patchVersions.length).toBe(1);
});
