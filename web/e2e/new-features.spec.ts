import { readFileSync } from "node:fs";
import { test, expect, type Locator, type Page } from "@playwright/test";
import { signInOwner } from "./owner-session";

// Run against a disposable local server, with its real API and packaged client.
test.skip(
  !process.env.SPUNTO_E2E_URL,
  "Set SPUNTO_E2E_URL to a local test server",
);
test.setTimeout(60_000);
test.use({ locale: "en-US" });

type Output = {
  type?: string;
  id?: string;
  title?: string;
  args?: Record<string, unknown>;
  ids?: string[];
  items?: Output[];
};

type Note = {
  id: string;
  body: string;
  version: number;
  private: boolean;
  machine: boolean;
  origin: string;
};

function origin() {
  return new URL(process.env.SPUNTO_E2E_URL!).origin;
}

async function login(page: Page, pluginId: string) {
  const host = new URL(process.env.SPUNTO_E2E_URL!).hostname;
  expect(
    ["localhost", "127.0.0.1", "[::1]", "host.docker.internal"],
    "New-feature acceptance must use a disposable local server",
  ).toContain(host);
  await signInOwner(page);
  await enablePlugin(page, pluginId);
}

async function enablePlugin(page: Page, pluginId: string) {
  const response = await page.request.get("/api/v1/plugins");
  expect(response.status()).toBe(200);
  const plugins: { id: string; permissions: string[]; review_token: string }[] =
    (await response.json()).plugins;
  const plugin = plugins.find((item) => item.id === pluginId);
  expect(
    plugin,
    `Bundled ${pluginId} must be in the real catalog`,
  ).toBeDefined();
  // The live change feed refreshes commands after activation. Reloading here
  // races its in-flight requests when WebKit tears down the document.
  const enabled = await page.request.post(
    `/api/v1/plugins/${pluginId}/enable`,
    {
      headers: { Origin: origin() },
      data: {
        permissions: plugin!.permissions,
        review_token: plugin!.review_token,
      },
    },
  );
  expect(enabled.status(), await enabled.text()).toBe(200);
}

async function palette(page: Page, title: string) {
  await expect(
    page.getByRole("complementary", { name: "Navigation", exact: true }),
  ).toBeVisible();
  await page.keyboard.press("Control+k");
  const dialog = page.getByRole("dialog", {
    name: "Search and commands",
    exact: true,
  });
  await expect(dialog).toBeVisible();
  await dialog
    .getByRole("searchbox", { name: "Search and commands" })
    .fill(title);
  return dialog;
}

async function uiCommand(
  page: Page,
  plugin: string,
  command: string,
  action: () => Promise<unknown>,
): Promise<Output> {
  const path = `/api/v1/plugins/${plugin}/commands/${command}`;
  const [response] = await Promise.all([
    page.waitForResponse(
      (value) =>
        new URL(value.url()).pathname === path &&
        value.request().method() === "POST",
    ),
    action(),
  ]);
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

// These are the actions the user takes after filling in each form.
const formTitles: Record<string, string> = {
  "record-decision": "Save decision",
  "review-decision": "Record outcome",
  edit: "Edit context pack",
  sources: "Pack sources",
};

async function run(
  page: Page,
  dialog: Locator,
  plugin: string,
  command: string,
) {
  return uiCommand(page, plugin, command, () =>
    dialog
      .getByRole("button", {
        name: formTitles[`${plugin}:${command}`] ?? formTitles[command],
        exact: true,
      })
      .last()
      .click(),
  );
}

function noteIds(output: Output): string[] {
  return [...(output.ids ?? []), ...(output.items ?? []).flatMap(noteIds)];
}

async function pickSource(
  page: Page,
  dialog: Locator,
  plugin: string,
  text: string,
) {
  const search = dialog.getByRole("searchbox", {
    name: "Search notes",
    exact: true,
  });
  await search.fill(text);
  await dialog.getByRole("radio", { name: new RegExp(text) }).check();
  await uiCommand(page, plugin, "sources", () =>
    dialog.getByRole("button", { name: "Add source", exact: true }).click(),
  );
  await expect(
    dialog.getByRole("button", { name: "Add source", exact: true }),
  ).toBeDisabled();
}

async function note(page: Page, id: string): Promise<Note> {
  const response = await page.request.get(`/api/v1/notes/${id}`);
  expect(response.status()).toBe(200);
  return response.json();
}

async function editNote(
  page: Page,
  current: Note,
  body: string,
): Promise<Note> {
  const response = await page.request.patch(`/api/v1/notes/${current.id}`, {
    headers: { Origin: origin(), "If-Match": `"${current.version}"` },
    data: { body },
  });
  expect(response.status(), await response.text()).toBe(200);
  return response.json();
}

async function selectStatus(
  dialog: Locator,
  value: "Open for follow-up" | "Closed",
) {
  await dialog
    .getByRole("group", { name: "Decision status", exact: true })
    .getByRole("button", { name: value, exact: true })
    .click();
}

test("Link to note searches, disambiguates and inserts in one action", async ({
  page,
}, info) => {
  await login(page, "links");
  const title = `Target ${Date.now().toString(36)} ${info.project.name}`;
  const create = async (body: string): Promise<Note> => {
    const response = await page.request.post("/api/v1/notes", {
      headers: { Origin: origin(), "Idempotency-Key": crypto.randomUUID() },
      data: { body },
    });
    expect(response.status()).toBe(201);
    return response.json();
  };
  const source = await create("Synthetic link source");
  const first = await create(`# ${title}\nKeep the blue draft.`);
  const second = await create(`# ${title}\nKeep the green draft.`);
  await page
    .locator(`article.entry[data-note-id="${source.id}"] a.time`)
    .click();
  const shortcut = page
    .locator(".note-tool-shortcut")
    .filter({ hasText: "Link to note" });
  await shortcut.click();
  const picker = page.getByRole("dialog", {
    name: "Note tools",
    exact: true,
  });
  const search = picker.getByRole("searchbox", {
    name: "Search notes",
    exact: true,
  });
  const insert = picker.getByRole("button", {
    name: "Insert link",
    exact: true,
  });
  await search.fill(title);
  const green = picker.getByRole("radio", { name: /green draft/ });
  await expect(picker.getByRole("radio", { name: /blue draft/ })).toBeVisible();
  await green.check();
  await expect(insert).toBeEnabled();
  await search.fill("no matching title ux regression sentinel");
  await expect(insert).toBeDisabled();
  await search.fill(title);
  await green.check();
  await uiCommand(page, "links", "link", () => insert.click());
  await expect(picker).toBeHidden();
  // The link shows twice: as a chip in the text, named by the target, and as a row under Links to.
  const link = page.locator(`a[href="/n/${second.id}"]`);
  await expect(page.locator(".note-body").locator(link)).toHaveText(title);
  await expect(
    page.getByRole("region", { name: "Linked notes" }).locator(link),
  ).toContainText("Keep the green draft.");
  const result = await note(page, source.id);
  expect(result.body).toContain(`/n/${second.id}`);
  expect(result.body).not.toContain(`/n/${first.id}`);
});

test("decision journal creates, reviews and reopens through the browser", async ({
  page,
}, info) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await login(page, "decision-journal");
  const suffix = `${Date.now().toString(36)}-${info.project.name}`;
  const composer = page.getByLabel("New note");
  await composer.fill("Ordinary draft stays separate.");
  const capture = page.getByRole("button", {
    name: "Record decision",
    exact: true,
  });
  await capture.click();
  let dialog = page.getByRole("dialog", {
    name: "Record decision",
    exact: true,
  });
  await dialog.getByRole("button", { name: "Close", exact: true }).click();
  await expect(capture).toBeFocused();
  await expect(composer).toHaveValue("Ordinary draft stays separate.");
  await page.getByRole("link", { name: "Decisions", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Decisions", exact: true }),
  ).toBeVisible();
  await uiCommand(page, "decision-journal", "record-decision", () =>
    capture.click(),
  );
  await dialog.locator("summary").filter({ hasText: "Add details" }).click();
  await dialog
    .getByLabel("Title (optional)", { exact: true })
    .fill(`Decision ${suffix}`);
  await dialog
    .getByLabel("What did you decide?", { exact: true })
    .fill("Use a single SQLite file.");
  await dialog
    .getByLabel("Alternatives considered (optional)", { exact: true })
    .fill("A separate database service.");
  await dialog
    .getByLabel("Why this choice? (optional)", { exact: true })
    .fill("One owner needs simple operations.\nKeep backups easy to restore.");
  await dialog
    .getByLabel("Expected outcome (optional)", { exact: true })
    .fill("Recover the synthetic library in under five minutes.");
  await dialog
    .getByLabel("Review date (optional)", { exact: true })
    .fill("2030-02-01");
  const created = await run(
    page,
    dialog,
    "decision-journal",
    "record-decision",
  );
  await expect(dialog.getByRole("status")).toHaveText("Decision saved");
  const rootId = noteIds(created)[0];
  expect(rootId).toBeTruthy();
  await dialog.locator(`a[href="/n/${rootId}"]`).click();
  const decision = page.getByRole("region", {
    name: "Decision journal",
    exact: true,
  });
  await expect(
    decision.getByText(/Open for follow-up.*Review on/),
  ).toBeVisible();
  const review = decision.getByRole("button", {
    name: "Review decision",
    exact: true,
  });
  await uiCommand(page, "decision-journal", "review-decision", () =>
    review.click(),
  );
  dialog = page.getByRole("dialog", { name: "Review decision", exact: true });
  await dialog
    .getByLabel("What happened or changed?", { exact: true })
    .fill("The synthetic restore finished in three minutes.");
  await dialog
    .getByLabel("What did you learn? (optional)", { exact: true })
    .fill("Keep a separate backup copy.");
  await selectStatus(dialog, "Closed");
  const reviewed = await run(
    page,
    dialog,
    "decision-journal",
    "review-decision",
  );
  await expect(dialog.getByRole("status")).toHaveText("Outcome saved");
  const outcomeId = noteIds(reviewed)[0];
  await dialog.locator(`a[href="/n/${outcomeId}"]`).click();
  await expect(
    decision.getByRole("link", { name: "Open original decision", exact: true }),
  ).toBeVisible();
  await uiCommand(page, "decision-journal", "review-decision", () =>
    review.click(),
  );
  await dialog
    .getByLabel("What happened or changed?", { exact: true })
    .fill("A new storage constraint needs another review.");
  await dialog
    .getByLabel("Next review date (optional)", { exact: true })
    .fill("2030-03-01");
  await selectStatus(dialog, "Open for follow-up");
  await run(page, dialog, "decision-journal", "review-decision");
  await expect(dialog.getByRole("status")).toHaveText("Outcome saved");
  await dialog.locator(`a[href="/n/${rootId}"]`).click();
  const open = decision.getByRole("button", {
    name: "Review history",
    exact: true,
  });
  await open.click();
  dialog = page.getByRole("dialog", { name: "Review history", exact: true });
  await expect(dialog.getByText(/Open for follow-up.*Review on/)).toBeVisible();
  await expect(dialog.locator(`a[href="/n/${outcomeId}"]`)).toBeVisible();
  await page.screenshot({
    path: info.outputPath("decision-journal.png"),
    fullPage: true,
  });
  await dialog.getByRole("button", { name: "Close", exact: true }).click();
  await expect(open).toBeFocused();
  await open.click();
  await dialog.locator(`a[href="/n/${rootId}"]`).click();
  await expect(dialog).toHaveCount(0);
  await expect(page).toHaveURL(new RegExp(`/n/${rootId}$`));
  await expect(open).toBeFocused();
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole("button", { name: "Open navigation", exact: true })
    .click();
  const navigation = page.getByRole("dialog", {
    name: "Navigation",
    exact: true,
  });
  await navigation
    .getByRole("link", { name: "Decisions", exact: true })
    .click();
  await expect(navigation).not.toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Decisions", exact: true }),
  ).toBeVisible();
  await capture.focus();
  await page.keyboard.press("Enter");
  dialog = page.getByRole("dialog", { name: "Record decision", exact: true });
  await dialog
    .getByLabel("What did you decide?", { exact: true })
    .fill("Keep the weekly planning slot.");
  await expect(
    dialog.getByLabel("Title (optional)", { exact: true }),
  ).not.toBeVisible();
  await expect(
    dialog.getByRole("button", { name: "Save decision", exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(390);
  await page.screenshot({
    path: info.outputPath("decision-capture-mobile.png"),
  });
  expect(errors).toEqual([]);
});

test("journal capture stays available outside contextual note tools", async ({
  page,
}) => {
  await login(page, "decision-journal");
  await enablePlugin(page, "links");
  const response = await page.request.post("/api/v1/notes", {
    headers: { Origin: origin() },
    data: { body: "# Synthetic architecture discussion", private: true },
  });
  expect(response.status()).toBe(201);
  const source = (await response.json()) as Note;
  await page.goto(`/n/${source.id}`);
  await expect(
    page.getByRole("button", { name: "Review decision", exact: true }),
  ).toHaveCount(0);
  const commands = await palette(page, "Review decision");
  await expect(
    commands.getByRole("option", { name: /^Review decision/ }),
  ).toHaveCount(0);
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Note tools", exact: true }).click();
  const tools = page.getByRole("dialog", { name: "Note tools", exact: true });
  await expect(
    tools.getByRole("button", { name: /^Record decision/ }),
  ).toHaveCount(0);
  await tools.getByRole("button", { name: "Close", exact: true }).click();
  await page.getByRole("link", { name: "Decisions", exact: true }).click();
  await uiCommand(page, "decision-journal", "record-decision", () =>
    page.getByRole("button", { name: "Record decision", exact: true }).click(),
  );
  const capture = page.getByRole("dialog", {
    name: "Record decision",
    exact: true,
  });
  await capture.getByRole("switch", { name: "Private", exact: true }).check();
  await capture
    .getByLabel("What did you decide?", { exact: true })
    .fill("Store the backup in a separate directory.");
  await page.context().setOffline(true);
  await expect(
    capture.getByLabel("What did you decide?", { exact: true }),
  ).toHaveValue("Store the backup in a separate directory.");
  await expect(
    capture.getByRole("button", { name: "Save decision", exact: true }),
  ).toBeDisabled();
  await expect(
    capture.getByLabel("What did you decide?", { exact: true }),
  ).toBeEditable();
  await page.context().setOffline(false);
  const saved = await run(page, capture, "decision-journal", "record-decision");
  const decision = await note(page, noteIds(saved)[0]);
  expect(decision.private).toBe(true);
  expect((await note(page, source.id)).body).toBe(source.body);
});

test("bundled plugin settings and decision outcomes follow Italian with an English browser", async ({
  page,
}, info) => {
  const italian = JSON.parse(
    readFileSync(new URL("../src/locales/it.json", import.meta.url), "utf8"),
  ).messages as Record<string, string>;
  const name = (key: string) => italian[key];
  await login(page, "decision-journal");
  expect(await page.evaluate(() => navigator.language)).toBe("en-US");
  await page.goto("/settings/appearance");
  await page
    .getByRole("combobox", { name: "Language", exact: true })
    .selectOption("it");
  await expect(page.locator("html")).toHaveAttribute("lang", "it");
  await page.goto("/settings/plugins/digest");
  await expect(
    page.getByRole("heading", {
      name: name("plugin.digest.name"),
      exact: true,
    }),
  ).toBeVisible();
  const weekday = page.getByLabel(
    name("plugin.digest.settings.weekday.title"),
    { exact: true },
  );
  await expect(
    weekday.getByRole("option", { name: name("choice.sunday"), exact: true }),
  ).toBeAttached();
  await expect(
    page.getByText(name("settingsplugins.reads_your_notes"), { exact: true }),
  ).toBeVisible();
  await expect(page.getByText("Reads your notes", { exact: true })).toHaveCount(
    0,
  );
  await page.screenshot({ path: info.outputPath("plugin-settings-it.png") });

  await enablePlugin(page, "library-stats");
  await page.goto("/archive");
  const stats = page.getByRole("button", {
    name: new RegExp(name("plugin.library-stats.commands.summary.title")),
  });
  const openNavigation = page.getByRole("button", {
    name: name("app.open_navigation"),
  });
  await expect(stats.or(openNavigation)).toBeVisible();
  if (await openNavigation.isVisible()) await openNavigation.click();
  await uiCommand(page, "library-stats", "summary", () => stats.click());
  await expect(page.getByText(/Note salvate: /)).toBeVisible();
  await expect(page.getByText(/Saved notes: /)).toHaveCount(0);
  await page.screenshot({ path: info.outputPath("library-stats-it.png") });
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("dialog", { name: name("plugin.library-stats.name") }),
  ).toHaveCount(0);
  await expect(page).toHaveURL(/\/archive$/);

  await enablePlugin(page, "thinking-desk");
  await page.goto("/settings/plugins/thinking-desk");
  await page
    .getByRole("main")
    .getByRole("link", { name: name("plugin.thinking-desk.name"), exact: true })
    .click();
  const italianNew = page.getByRole("link", {
    name: "Nuovo tavolo",
    exact: true,
  });
  await expect(
    italianNew.or(
      page.getByRole("button", { name: "Combina le note", exact: true }),
    ),
  ).toBeVisible();
  if (await italianNew.isVisible()) await italianNew.click();
  await expect(
    page.getByRole("button", { name: "Ricostruisci un’idea", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Combina le note", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Combine notes", exact: true }),
  ).toHaveCount(0);
  await page.screenshot({
    path: info.outputPath("thinking-desk-choices-it.png"),
  });
  await page
    .getByRole("button", { name: "Combina le note", exact: true })
    .click();
  await expect(
    page.getByLabel("Nome del tavolo", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Crea tavolo", exact: true }),
  ).toBeVisible();
  await expect(page.getByText("ID della richiesta di creazione")).toHaveCount(
    0,
  );

  await page.goto("/v/decision-journal/journal");
  const commandHeaders: string[] = [];
  const mutations: string[] = [];
  page.on("request", (request) => {
    if (
      !new URL(request.url()).pathname.includes("/decision-journal/commands/")
    )
      return;
    commandHeaders.push(request.headers()["accept-language"]);
    if (request.postDataJSON()?.request_id) mutations.push(request.url());
  });
  const captureTitle = name(
    "plugin.decision-journal.commands.record-decision.title",
  );
  await page.getByRole("button", { name: captureTitle, exact: true }).click();
  let dialog = page.getByRole("dialog", { name: captureTitle, exact: true });
  const decision = `Keep Tuesday’s draft as written ${info.project.name}`;
  await dialog
    .getByLabel(name("decisions.form.decision"), { exact: true })
    .fill(decision);
  await dialog
    .getByRole("button", { name: name("decisions.save_decision"), exact: true })
    .click();
  await dialog.getByRole("link", { name: decision, exact: true }).click();
  const reviewTitle = name(
    "plugin.decision-journal.commands.review-decision.title",
  );
  const journal = page.getByRole("region", {
    name: name("plugin.decision-journal.name"),
    exact: true,
  });
  await journal.getByRole("button", { name: reviewTitle, exact: true }).click();
  dialog = page.getByRole("dialog", { name: reviewTitle, exact: true });
  await dialog
    .getByLabel(name("decisions.form.outcome"), { exact: true })
    .fill("The test draft was retained verbatim.");
  await dialog
    .getByRole("button", { name: name("decisions.status.closed"), exact: true })
    .click();
  const reviewed = await uiCommand(
    page,
    "decision-journal",
    "review-decision",
    () =>
      dialog
        .getByRole("button", {
          name: name("decisions.record_outcome"),
          exact: true,
        })
        .click(),
  );
  const outcome = noteIds(reviewed)[0];
  await dialog.locator(`a[href="/n/${outcome}"]`).click();
  await expect(
    journal.getByText(name("decisions.status.closed"), { exact: true }),
  ).toBeVisible();
  await journal.getByRole("button", { name: reviewTitle, exact: true }).click();
  dialog = page.getByRole("dialog", { name: reviewTitle, exact: true });
  await dialog
    .getByLabel(name("decisions.form.outcome"), { exact: true })
    .fill("Follow up next Tuesday.");
  await dialog
    .getByRole("button", { name: name("decisions.status.open"), exact: true })
    .click();
  await dialog
    .getByLabel(name("decisions.form.next_review_on"), { exact: true })
    .fill("2030-03-01");
  await dialog
    .getByRole("button", {
      name: name("decisions.record_outcome"),
      exact: true,
    })
    .click();
  await dialog.getByRole("link", { name: decision, exact: true }).click();
  const history = name("decisions.review_history");
  await journal.getByRole("button", { name: history, exact: true }).click();
  dialog = page.getByRole("dialog", { name: history, exact: true });
  await expect(dialog.locator(`a[href="/n/${outcome}"]`)).toBeVisible();
  await expect(dialog.getByText(/1 mar 2030/)).toBeVisible();
  await expect(dialog.getByText(/Open.*Review on/)).toHaveCount(0);
  await page.screenshot({ path: info.outputPath("decision-history-it.png") });
  await dialog
    .getByRole("button", { name: name("app.close"), exact: true })
    .click();
  expect(commandHeaders.length).toBeGreaterThan(3);
  expect(new Set(commandHeaders)).toEqual(new Set(["it"]));
  const mutationCount = mutations.length;
  await page.evaluate(() => {
    localStorage.setItem("spunto:language", "en");
    window.dispatchEvent(
      new StorageEvent("storage", { key: "spunto:language" }),
    );
  });
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(
    page
      .getByRole("region", { name: "Decision journal", exact: true })
      .getByText(/Open for follow-up.*Review on/),
  ).toBeVisible();
  expect(commandHeaders.at(-1)).toBe("en");
  expect(mutations).toHaveLength(mutationCount);
});

test("context packs curate sources, expose staleness and keep a private note copy", async ({
  page,
}, info) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await login(page, "context-packs");
  const suffix = `${Date.now().toString(36)}-${info.project.name}`;
  const sourceBody = `SOURCE-ONLY ${suffix}\nA synthetic implementation constraint.`;
  const seeded = await page.request.post("/api/v1/notes", {
    headers: { Origin: origin(), "Idempotency-Key": crypto.randomUUID() },
    data: { body: sourceBody, private: true, source: { kind: "web" } },
  });
  expect(seeded.status(), await seeded.text()).toBe(201);
  const source: Note = await seeded.json();
  let dialog = await palette(page, "Context packs");
  await uiCommand(page, "context-packs", "packs", () =>
    dialog.getByRole("option", { name: /^Context packs/ }).click(),
  );
  await uiCommand(page, "context-packs", "edit", () =>
    dialog
      .getByRole("button", { name: "New context pack", exact: true })
      .click(),
  );
  await dialog
    .getByLabel("Brief title", { exact: true })
    .fill(`Pack ${suffix}`);
  await dialog
    .getByLabel("Goals", { exact: true })
    .fill("Make one useful brief.\nKeep it manually curated.");
  await uiCommand(page, "context-packs", "edit", () =>
    dialog.getByRole("button", { name: "Create draft", exact: true }).click(),
  );
  await expect(dialog.getByText(/Draft · Finish your brief/)).toBeVisible();

  await uiCommand(page, "context-packs", "packs", () =>
    dialog
      .getByRole("button", { name: "All context packs", exact: true })
      .click(),
  );
  await uiCommand(page, "context-packs", "packs", () =>
    dialog
      .getByRole("button", { name: `Draft · Pack ${suffix}`, exact: true })
      .click(),
  );
  await uiCommand(page, "context-packs", "sources", () =>
    dialog.getByRole("button", { name: "Choose sources", exact: true }).click(),
  );
  await pickSource(page, dialog, "context-packs", suffix);
  await uiCommand(page, "context-packs", "edit", () =>
    dialog.getByRole("button", { name: "Write brief", exact: true }).click(),
  );
  await expect(dialog.getByLabel("Goals", { exact: true })).toHaveValue(
    "Make one useful brief.\nKeep it manually curated.",
  );
  await dialog
    .getByLabel("Project", { exact: true })
    .fill(`Synthetic ${suffix}`);
  await dialog
    .getByLabel("Constraints", { exact: true })
    .fill("Keep all work local.");
  await dialog
    .getByLabel("Decisions", { exact: true })
    .fill("Use explicit source revisions.");
  await dialog
    .getByLabel("Open questions", { exact: true })
    .fill("Which constraint changes next?");
  await expect(
    dialog.getByLabel("Private pack (only private-enabled agents can read)", {
      exact: true,
    }),
  ).toBeChecked();
  await uiCommand(page, "context-packs", "edit", () =>
    dialog.getByRole("button", { name: "Save brief", exact: true }).click(),
  );
  await expect(
    dialog.getByRole("heading", { name: `Pack ${suffix}`, exact: true }),
  ).toBeVisible();
  await expect(
    dialog.getByText(/baseline 1 · current 1 · current/),
  ).toBeVisible();

  await uiCommand(page, "context-packs", "edit", () =>
    dialog.getByRole("button", { name: "Edit brief", exact: true }).click(),
  );
  const revisedGoals =
    "A revised goal, kept by the owner.\nSources must never rewrite this brief.";
  await dialog.getByLabel("Goals", { exact: true }).fill(revisedGoals);
  await uiCommand(page, "context-packs", "edit", () =>
    dialog.getByRole("button", { name: "Save brief", exact: true }).click(),
  );
  const changed = await editNote(
    page,
    source,
    sourceBody + "\nA later source revision.",
  );
  await uiCommand(page, "context-packs", "sources", () =>
    dialog.getByRole("button", { name: "Review sources", exact: true }).click(),
  );
  await expect(dialog.getByText("1 · changed", { exact: true })).toBeVisible();
  await uiCommand(page, "context-packs", "packs", () =>
    dialog.getByRole("button", { name: "Back to brief", exact: true }).click(),
  );
  await expect(dialog.getByText(/Source revisions need review/)).toBeVisible();
  await expect(
    dialog.getByText(/A revised goal, kept by the owner/),
  ).toBeVisible();
  await uiCommand(page, "context-packs", "sources", () =>
    dialog.getByRole("button", { name: "Review sources", exact: true }).click(),
  );
  await dialog
    .getByLabel("Acknowledge all current source revisions", { exact: true })
    .check();
  await uiCommand(page, "context-packs", "sources", () =>
    dialog.getByRole("button", { name: "Update sources", exact: true }).click(),
  );
  await uiCommand(page, "context-packs", "packs", () =>
    dialog.getByRole("button", { name: "Back to brief", exact: true }).click(),
  );
  await expect(
    dialog.getByText(/baseline 2 · current 2 · current/),
  ).toBeVisible();
  await expect(dialog.getByText(/Source revisions need review/)).toHaveCount(0);
  await expect(
    dialog.getByText(/A revised goal, kept by the owner/),
  ).toBeVisible();
  await expect(
    dialog.getByRole("button", { name: "Copy text", exact: true }),
  ).toHaveCount(0);
  await uiCommand(page, "context-packs", "packs", () =>
    dialog.getByRole("button", { name: "Use brief", exact: true }).click(),
  );
  await expect(
    dialog.getByText(/Agents need permission to read private content/),
  ).toBeVisible();
  await dialog.getByRole("button", { name: "Copy text", exact: true }).click();
  await expect(
    dialog.getByRole("button", { name: "Copied", exact: true }),
  ).toBeVisible();
  const saved = await uiCommand(page, "context-packs", "edit", () =>
    dialog
      .getByRole("button", { name: "Keep private note copy", exact: true })
      .click(),
  );
  const kept = await note(page, noteIds(saved)[0]);
  expect(kept.private).toBe(true);
  expect(kept.origin).toBe("owner");
  expect(kept.machine).toBe(false);
  expect(kept.body).toContain(revisedGoals);
  expect(kept.body).toContain(`/n/${source.id}`);
  expect(kept.body).toContain("baseline 2");
  expect(kept.body).not.toContain("SOURCE-ONLY");
  expect((await note(page, source.id)).body).toBe(changed.body);
  await page.screenshot({
    path: info.outputPath("context-pack.png"),
    fullPage: true,
  });
  expect(errors).toEqual([]);
});

async function deskModeChoices(page: Page) {
  const main = page.getByRole("main");
  const start = main.getByRole("link", { name: "New desk", exact: true });
  await expect(
    start.or(main.getByRole("button", { name: "Combine notes", exact: true })),
  ).toBeVisible();
  if (await start.isVisible()) await start.click();
  await expect(
    main.getByRole("heading", { name: "Follow one idea" }),
  ).toBeVisible();
  await expect(
    main.getByRole("heading", { name: "Make a new connection" }),
  ).toBeVisible();
  return main;
}

test("thinking desk collision combines selected subjects and saves an authored private snapshot", async ({
  page,
}, info) => {
  await login(page, "thinking-desk");
  const suffix = `${Date.now().toString(36)}-${info.project.name}`;
  const sources: Note[] = [];
  for (const subject of ["Gardens", "Networks"]) {
    const response = await page.request.post("/api/v1/notes", {
      headers: { Origin: origin(), "Idempotency-Key": crypto.randomUUID() },
      data: {
        body: `${subject} ${suffix}`,
        private: true,
        source: { kind: "web" },
      },
    });
    expect(response.status()).toBe(201);
    sources.push(await response.json());
  }
  await page
    .getByRole("complementary", { name: "Navigation" })
    .getByRole("link", { name: "Thinking desk", exact: true })
    .click();
  await expect(page).toHaveURL(/\/thinking-desk$/);
  const desk = await deskModeChoices(page);
  const chooseAction = (title: string) =>
    desk.getByRole("button", { name: title, exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(
    desk.getByRole("button", { name: "Copy text", exact: true }),
  ).toHaveCount(0);
  await page.screenshot({
    path: info.outputPath("thinking-desk-choices.png"),
    fullPage: true,
  });
  await uiCommand(page, "thinking-desk", "edit", () =>
    chooseAction("Combine notes"),
  );
  const name = `Gardens and resilient neighborhood networks ${suffix}`;
  await desk.getByLabel("A name for this desk", { exact: true }).fill(name);
  await expect(desk.getByLabel("Your idea", { exact: true })).toHaveCount(0);
  await expect(desk.getByText("Creation request ID")).toHaveCount(0);
  await uiCommand(page, "thinking-desk", "edit", () =>
    chooseAction("Create desk"),
  );
  await expect(page).toHaveURL(/\/thinking-desk\/[a-f0-9-]+$/);
  const location = page.url();
  await expect(desk.getByRole("heading", { name, exact: true })).toHaveCount(1);
  await expect(desk.getByText(/Choose at least two notes/)).toBeVisible();
  await expect(
    desk.getByRole("button", { name: "Save as private note" }),
  ).toHaveCount(0);
  await uiCommand(page, "thinking-desk", "sources", () =>
    desk.getByRole("button", { name: "Choose notes", exact: true }).click(),
  );
  const [garden, network] = sources;
  await desk.getByRole("radio", { name: new RegExp(network.body) }).check();
  await uiCommand(page, "thinking-desk", "sources", () =>
    chooseAction("Add source"),
  );
  await expect(
    desk.getByText("Note added. 1 selected.", { exact: true }),
  ).toBeVisible();
  await expect(
    desk.getByRole("button", { name: "Update notes", exact: true }),
  ).toBeDisabled();
  await expect(
    desk.getByRole("searchbox", { name: "Search notes", exact: true }),
  ).toBeFocused();
  await pickSource(page, desk, "thinking-desk", garden.body);
  await expect(
    desk.getByText("Note added. 2 selected.", { exact: true }),
  ).toBeVisible();
  await expect(
    desk.getByRole("button", { name: "Back to desk", exact: true }),
  ).toBeFocused();
  await uiCommand(page, "thinking-desk", "desk", () =>
    desk.getByRole("button", { name: "Back to desk", exact: true }).click(),
  );
  await uiCommand(page, "thinking-desk", "edit", () =>
    chooseAction("Write your idea"),
  );
  await expect(desk.getByText(/surprising analogy/)).toBeVisible();
  await expect(
    desk.getByRole("link", { name: garden.body, exact: true }),
  ).toBeVisible();
  await expect(
    desk.getByRole("link", { name: network.body, exact: true }),
  ).toBeVisible();
  const idea = "Prune weak network connections like a garden.";
  await desk.getByLabel("Your idea", { exact: true }).fill(idea);
  await uiCommand(page, "thinking-desk", "edit", () =>
    chooseAction("Save idea"),
  );
  await expect(desk.getByText(idea, { exact: true })).toBeVisible();
  const saved = await uiCommand(page, "thinking-desk", "edit", () =>
    chooseAction("Save as private note"),
  );
  const snapshot = await note(page, noteIds(saved)[0]);
  expect(snapshot.private).toBe(true);
  expect(snapshot.machine).toBe(false);
  expect(snapshot.body).toContain(idea);
  expect(snapshot.body).toContain(`/n/${garden.id}`);
  expect(snapshot.body).toContain(`/n/${network.id}`);
  expect(snapshot.body).not.toContain("surprising analogy");
  expect((await note(page, garden.id)).body).toBe(garden.body);
  await expect(desk.getByText(/Private note saved/)).toBeVisible();
  await expect(desk.getByRole("link", { name, exact: true })).toBeVisible();
  await uiCommand(page, "thinking-desk", "desk", () =>
    chooseAction("Back to desk"),
  );
  await expect(desk.getByText(idea, { exact: true })).toBeVisible();
  await page.reload();
  await expect(page).toHaveURL(location);
  await expect(desk.getByText(idea, { exact: true })).toBeVisible();
  await desk.getByRole("link", { name: "Thinking desk", exact: true }).click();
  const savedDesk = desk.getByRole("link", { name, exact: true });
  await expect(savedDesk).toBeVisible();
  await savedDesk.click();
  await expect(page).toHaveURL(location);
  await expect(desk.getByText(idea, { exact: true })).toBeVisible();

  await page.goto(`/n/${garden.id}`);
  await expect(
    page.locator(".note-tool-shortcut").filter({ hasText: "Thinking desk" }),
  ).toHaveCount(0);
  const commands = await palette(page, "Thinking desk");
  await commands.getByRole("option", { name: /^Thinking desk/ }).click();
  await expect(page).toHaveURL(/\/thinking-desk$/);
  await expect(commands).toHaveCount(0);
});

test("thinking history exposes both desk modes and connects and removes selected sources", async ({
  page,
}, info) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page, "thinking-desk");
  const suffix = `${Date.now().toString(36)}-${info.project.name}`;
  const sources: Note[] = [];
  for (const order of ["Earlier", "Later"]) {
    const response = await page.request.post("/api/v1/notes", {
      headers: { Origin: origin(), "Idempotency-Key": crypto.randomUUID() },
      data: {
        body: `${order} history source ${suffix}`,
        private: true,
        source: { kind: "web" },
      },
    });
    expect(response.status()).toBe(201);
    sources.push(await response.json());
  }
  await page
    .getByRole("button", { name: "Open navigation", exact: true })
    .click();
  await page
    .getByRole("dialog", { name: "Navigation", exact: true })
    .getByRole("link", { name: "Thinking desk", exact: true })
    .click();
  await expect(
    page.getByRole("dialog", { name: "Navigation", exact: true }),
  ).toHaveCount(0);
  const desk = await deskModeChoices(page);
  await uiCommand(page, "thinking-desk", "edit", () =>
    desk.getByRole("button", { name: "Trace an idea", exact: true }).click(),
  );
  await desk
    .getByLabel("A name for this desk", { exact: true })
    .fill(`History ${suffix}`);
  await uiCommand(page, "thinking-desk", "edit", () =>
    desk.getByRole("button", { name: "Create desk", exact: true }).click(),
  );
  await uiCommand(page, "thinking-desk", "sources", () =>
    desk.getByRole("button", { name: "Choose notes", exact: true }).click(),
  );
  for (const source of sources)
    await pickSource(page, desk, "thinking-desk", source.body);
  await uiCommand(page, "thinking-desk", "desk", () =>
    desk.getByRole("button", { name: "Back to desk", exact: true }).click(),
  );
  await expect(
    desk.getByRole("heading", {
      name: "Notes in creation order",
      exact: true,
    }),
  ).toBeVisible();
  await expect(desk.getByText(/\d{4}-\d{2}-\d{2}/).first()).toBeVisible();
  for (const remove of [false, true]) {
    await uiCommand(page, "thinking-desk", "sources", () =>
      desk.getByRole("button", { name: "Connect notes", exact: true }).click(),
    );
    for (const [label, source] of [
      ["First note", sources[1]],
      ["Second note", sources[0]],
    ] as const) {
      const group = desk.getByRole("group", { name: label, exact: true });
      await expect(group).toBeVisible();
      await group
        .getByRole("button", { name: new RegExp(source.body) })
        .click();
    }
    await desk
      .getByRole("group", { name: "Connection", exact: true })
      .getByRole("button", { name: "Challenges", exact: true })
      .click();
    if (remove)
      await desk.getByLabel("Remove this connection", { exact: true }).check();
    await uiCommand(page, "thinking-desk", "sources", () =>
      desk
        .getByRole("button", { name: "Save connection", exact: true })
        .click(),
    );
    if (remove)
      await expect(
        desk.getByText("Note 2 challenges note 1", { exact: true }),
      ).toHaveCount(0);
    else
      await expect(
        desk.getByText("Note 2 challenges note 1", { exact: true }),
      ).toBeVisible();
  }
  // Server tests cover the earlier target. Keep the browser's check on the
  // later note selected as the first side of the connection.
  const firstSide = sources[1];
  expect((await note(page, firstSide.id)).body).toBe(firstSide.body);
  await page.screenshot({
    path: info.outputPath("thinking-history-narrow.png"),
    fullPage: true,
  });
});

test("accept an agent note as written and review a later agent change", async ({
  page,
  request,
}) => {
  await login(page, "agent-inbox");
  const ownerHeaders = { Origin: origin() };
  const token = await page.request.post("/api/v1/tokens", {
    headers: ownerHeaders,
    data: { name: "Review fixture", client: "script", scope: "write" },
  });
  expect(token.status()).toBe(201);
  const credential = await token.json();
  const agentHeaders = { Authorization: `Bearer ${credential.token}` };
  try {
    const body = "Keep the **original** wording.";
    const response = await request.post("/api/v1/notes", {
      headers: agentHeaders,
      data: { body },
    });
    expect(response.status()).toBe(201);
    const note = await response.json();
    const noteUrl = `/api/v1/notes/${note.id}`;
    async function inInbox(id = note.id) {
      const response = await page.request.get("/api/v1/notes", {
        params: { view: "agent-inbox/reviewed" },
      });
      expect(response.status()).toBe(200);
      const { notes } = await response.json();
      return notes.some((item: Note) => item.id === id);
    }
    expect(await inInbox()).toBe(true);
    await page.goto(`/n/${note.id}`);
    const comparison = page.getByRole("region", {
      name: "Agent changes",
      exact: true,
    });
    await expect(comparison).toHaveCount(0);
    await page
      .getByRole("button", { name: "Accept note", exact: true })
      .click();
    await expect.poll(inInbox).toBe(false);
    const accepted = await (await page.request.get(noteUrl)).json();
    expect(accepted).toMatchObject({
      body,
      version: note.version,
      source: note.source,
      origin: "agent",
    });
    const append = await request.post(`${noteUrl}/append`, {
      headers: agentHeaders,
      data: { text: "One more observation.", expected_version: note.version },
    });
    expect(append.status()).toBe(200);
    await expect.poll(inInbox).toBe(true);
    await page.reload();
    await expect(comparison.locator("ins")).toContainText(
      "One more observation.",
    );
    await page
      .getByRole("button", { name: "Accept changes", exact: true })
      .click();
    await expect.poll(inInbox).toBe(false);
    expect((await (await page.request.get(noteUrl)).json()).body).toBe(
      (await append.json()).body,
    );
    const owner = await page.request.post("/api/v1/notes", {
      headers: ownerHeaders,
      data: { body: "My own wording." },
    });
    expect(owner.status()).toBe(201);
    const original = await owner.json();
    const update = await request.post(`/api/v1/notes/${original.id}/append`, {
      headers: agentHeaders,
      data: { text: "Agent suggestion.", expected_version: original.version },
    });
    expect(update.status()).toBe(200);
    const edited = await update.json();
    await page.goto(`/n/${original.id}`);
    await page
      .getByRole("button", { name: "Accept changes", exact: true })
      .click();
    await expect.poll(() => inInbox(original.id)).toBe(false);
    const kept = await page.request.get(`/api/v1/notes/${original.id}`);
    expect(await kept.json()).toMatchObject({
      body: edited.body,
      version: edited.version,
      origin: original.origin,
    });
  } finally {
    const revoked = await page.request.delete(
      `/api/v1/credentials/${credential.id}`,
      {
        headers: ownerHeaders,
      },
    );
    expect(revoked.status()).toBe(204);
  }
});
