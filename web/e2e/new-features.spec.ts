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
    .getByRole("textbox", { name: "Search and commands" })
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
  "thinking-desk:edit": "Edit thinking desk",
  "thinking-desk:sources": "Choose thinking sources",
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
  await page.goto(`/n/${source.id}`);
  await page.getByRole("button", { name: "More actions", exact: true }).click();
  await page
    .getByRole("menuitem", { name: "Link to note", exact: true })
    .click();
  const picker = page.getByRole("dialog", {
    name: "Link to note",
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

test("decision journal creates, reviews and reopens without rewriting the original", async ({
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
  const original = await note(page, rootId);
  expect(original.origin).toBe("owner");
  expect(original.machine).toBe(false);
  expect(original.body).toContain("## Alternatives");
  expect(original.body).toContain("Keep backups easy to restore.");
  // A direct owner edit must survive both subsequent plugin commands.
  const edited = await editNote(
    page,
    original,
    original.body + "\n\nOwner clarification " + suffix,
  );

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
  const outcome = await note(page, outcomeId);
  expect(outcome.body).toContain(`/n/${rootId}`);
  expect(outcome.body).toContain("three minutes");
  expect((await note(page, rootId)).body).toBe(edited.body);

  // Review can be reopened from an outcome note, retaining the earlier outcome.
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
  const reopened = await run(
    page,
    dialog,
    "decision-journal",
    "review-decision",
  );
  expect(noteIds(reopened)[0]).not.toBe(outcomeId);
  expect((await note(page, outcomeId)).body).toBe(outcome.body);
  expect((await note(page, rootId)).body).toBe(edited.body);
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

test("a source note offers decision capture with inherited privacy", async ({
  page,
}) => {
  await login(page, "decision-journal");
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
  await page.getByRole("button", { name: "More actions", exact: true }).click();
  await page.getByRole("menuitem", { name: "Note tools", exact: true }).click();
  const tools = page.getByRole("dialog", { name: "Note tools", exact: true });
  await uiCommand(page, "decision-journal", "record-decision", () =>
    tools.getByRole("button", { name: /^Record decision/ }).click(),
  );
  await expect(
    tools.getByRole("switch", { name: "Private", exact: true }),
  ).toBeChecked();
  await tools
    .getByLabel("What did you decide?", { exact: true })
    .fill("Store the backup in a separate directory.");
  await page.context().setOffline(true);
  await expect(
    tools.getByLabel("What did you decide?", { exact: true }),
  ).toHaveValue("Store the backup in a separate directory.");
  await expect(
    tools.getByRole("button", { name: "Save decision", exact: true }),
  ).toBeDisabled();
  await expect(
    tools.getByLabel("What did you decide?", { exact: true }),
  ).toBeEditable();
  await page.context().setOffline(false);
  const saved = await run(page, tools, "decision-journal", "record-decision");
  const decision = await note(page, noteIds(saved)[0]);
  expect(decision.private).toBe(true);
  expect(decision.body).toContain(`/n/${source.id}`);
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
  await page.goto("/settings/plugins/library-stats");
  const stats = page.getByRole("button", {
    name: new RegExp(name("plugin.library-stats.commands.summary.title")),
  });
  await uiCommand(page, "library-stats", "summary", () => stats.click());
  await expect(page.getByText(/Note salvate: /)).toBeVisible();
  await expect(page.getByText(/Saved notes: /)).toHaveCount(0);
  await page.screenshot({ path: info.outputPath("library-stats-it.png") });

  await enablePlugin(page, "thinking-desk");
  await page.goto("/settings/plugins/thinking-desk");
  await page
    .getByRole("button", {
      name: name("plugin.thinking-desk.commands.desk.title"),
      exact: true,
    })
    .click();
  await page.getByRole("button", { name: "Nuovo tavolo", exact: true }).click();
  await expect(
    page.getByRole("button", {
      name: name("pluginui.choice.history"),
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", {
      name: name("pluginui.choice.collision"),
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Collision", exact: true }),
  ).toHaveCount(0);

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
    .getByLabel("Project", { exact: true })
    .fill(`Synthetic ${suffix}`);
  await dialog
    .getByLabel("Goals", { exact: true })
    .fill("Make one useful brief.\nKeep it manually curated.");
  await dialog
    .getByLabel("Constraints", { exact: true })
    .fill("Keep all work local.");
  await dialog
    .getByLabel("Decisions", { exact: true })
    .fill("Use explicit source revisions.");
  await dialog
    .getByLabel("Open questions", { exact: true })
    .fill("Which constraint changes next?");
  await dialog
    .getByLabel("Private pack (only private-enabled agents can read)", {
      exact: true,
    })
    .check();
  await run(page, dialog, "context-packs", "edit");
  await expect(
    dialog.getByRole("heading", { name: `Pack ${suffix}`, exact: true }),
  ).toBeVisible();

  await uiCommand(page, "context-packs", "sources", () =>
    dialog.getByRole("button", { name: "Review sources", exact: true }).click(),
  );
  await pickSource(page, dialog, "context-packs", suffix);
  await uiCommand(page, "context-packs", "packs", () =>
    dialog.getByRole("button", { name: "Back to brief", exact: true }).click(),
  );
  await expect(
    dialog.getByText(/baseline 1 · current 1 · current/),
  ).toBeVisible();

  await uiCommand(page, "context-packs", "edit", () =>
    dialog.getByRole("button", { name: "Edit brief", exact: true }).click(),
  );
  const revisedGoals =
    "A revised goal, kept by the owner.\nSources must never rewrite this brief.";
  await dialog.getByLabel("Goals", { exact: true }).fill(revisedGoals);
  await run(page, dialog, "context-packs", "edit");
  const changed = await editNote(
    page,
    source,
    sourceBody + "\nA later source revision.",
  );
  expect(changed.version).toBe(2);
  await uiCommand(page, "context-packs", "sources", () =>
    dialog.getByRole("button", { name: "Review sources", exact: true }).click(),
  );
  await expect(dialog.getByText("1: changed", { exact: true })).toBeVisible();
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
  const dialog = await palette(page, "Thinking desk");
  await uiCommand(page, "thinking-desk", "desk", () =>
    dialog.getByRole("option", { name: /^Thinking desk/ }).click(),
  );
  await uiCommand(page, "thinking-desk", "edit", () =>
    dialog
      .getByRole("button", { name: "New thinking desk", exact: true })
      .click(),
  );
  await dialog
    .getByLabel("Topic or desk title", { exact: true })
    .fill(`Collision ${suffix}`);
  await dialog
    .getByRole("group", { name: "Desk type", exact: true })
    .getByRole("button", { name: "Collision", exact: true })
    .click();
  await run(page, dialog, "thinking-desk", "edit");
  await uiCommand(page, "thinking-desk", "sources", () =>
    dialog.getByRole("button", { name: "Review sources", exact: true }).click(),
  );
  for (const source of sources)
    await pickSource(page, dialog, "thinking-desk", source.body);
  await uiCommand(page, "thinking-desk", "desk", () =>
    dialog.getByRole("button", { name: "Back to desk", exact: true }).click(),
  );
  await expect(dialog.getByText(/surprising analogy/)).toBeVisible();
  await uiCommand(page, "thinking-desk", "edit", () =>
    dialog
      .getByRole("button", { name: "Write reflection", exact: true })
      .click(),
  );
  const idea = "Prune weak network connections like a garden.";
  await dialog
    .getByLabel("Your reflection or new idea", { exact: true })
    .fill(idea);
  await run(page, dialog, "thinking-desk", "edit");
  await expect(dialog.getByText(idea, { exact: true })).toBeVisible();
  const saved = await uiCommand(page, "thinking-desk", "edit", () =>
    dialog
      .getByRole("button", { name: "Keep private note copy", exact: true })
      .click(),
  );
  const snapshot = await note(page, noteIds(saved)[0]);
  expect(snapshot.private).toBe(true);
  expect(snapshot.machine).toBe(false);
  expect(snapshot.body).toContain(idea);
  expect((await note(page, sources[0].id)).body).toBe(sources[0].body);
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
  // Open the normal command palette on the narrow viewport without relying on a desktop sidebar.
  await page.keyboard.press("Control+k");
  const dialog = page.getByRole("dialog", {
    name: "Search and commands",
    exact: true,
  });
  await dialog
    .getByRole("textbox", { name: "Search and commands" })
    .fill("Thinking desk");
  await uiCommand(page, "thinking-desk", "desk", () =>
    dialog.getByRole("option", { name: /^Thinking desk/ }).click(),
  );
  await uiCommand(page, "thinking-desk", "edit", () =>
    dialog
      .getByRole("button", { name: "New thinking desk", exact: true })
      .click(),
  );
  const types = dialog.getByRole("group", { name: "Desk type", exact: true });
  await expect(
    types.getByRole("button", { name: "History", exact: true }),
  ).toBeVisible();
  await expect(
    types.getByRole("button", { name: "Collision", exact: true }),
  ).toBeVisible();
  await types.getByRole("button", { name: "History", exact: true }).click();
  await dialog
    .getByLabel("Topic or desk title", { exact: true })
    .fill(`History ${suffix}`);
  await run(page, dialog, "thinking-desk", "edit");
  await uiCommand(page, "thinking-desk", "sources", () =>
    dialog.getByRole("button", { name: "Review sources", exact: true }).click(),
  );
  for (const source of sources)
    await pickSource(page, dialog, "thinking-desk", source.body);
  await uiCommand(page, "thinking-desk", "desk", () =>
    dialog.getByRole("button", { name: "Back to desk", exact: true }).click(),
  );
  await expect(
    dialog.getByRole("heading", {
      name: "Notes in creation order",
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    dialog.getByText(/\d{4}-\d{2}-\d{2}:.*revision 1/).first(),
  ).toBeVisible();
  for (const remove of [false, true]) {
    await uiCommand(page, "thinking-desk", "sources", () =>
      dialog
        .getByRole("button", { name: "Connect notes", exact: true })
        .click(),
    );
    for (const [label, source] of [
      ["First note", sources[1]],
      ["Second note", sources[0]],
    ] as const) {
      const group = dialog.getByRole("group", { name: label, exact: true });
      if (await group.count()) {
        await group
          .getByRole("button", { name: new RegExp(source.body) })
          .click();
      } else {
        const select = dialog.getByLabel(label, { exact: true });
        const caption = (
          await select.getByRole("option").allTextContents()
        ).find((value) => value.includes(source.body));
        expect(caption).toBeDefined();
        await select.selectOption({ label: caption! });
      }
    }
    await dialog
      .getByRole("group", { name: "Connection", exact: true })
      .getByRole("button", { name: "Challenges", exact: true })
      .click();
    if (remove)
      await dialog
        .getByLabel("Remove this connection", { exact: true })
        .check();
    await run(page, dialog, "thinking-desk", "sources");
    if (remove)
      await expect(
        dialog.getByText("Note 2 challenges note 1", { exact: true }),
      ).toHaveCount(0);
    else
      await expect(
        dialog.getByText("Note 2 challenges note 1", { exact: true }),
      ).toBeVisible();
  }
  for (const source of sources)
    expect((await note(page, source.id)).body).toBe(source.body);
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
    await page
      .getByRole("button", { name: "Accept note", exact: true })
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
