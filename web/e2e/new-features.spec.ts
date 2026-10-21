import { test, expect, type Locator, type Page } from "@playwright/test";

// Run against a disposable local server, with its real API and packaged client.
test.skip(
  !process.env.SPUNTO_E2E_URL,
  "Set SPUNTO_E2E_URL to a local test server",
);
test.setTimeout(60_000);

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
  await page.goto("/");
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.SPUNTO_E2E_PASSWORD ?? "browser acceptance password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByLabel("New note")).toBeVisible();
  const response = await page.request.get("/api/v1/plugins");
  expect(response.status()).toBe(200);
  const plugins: { id: string; permissions: string[] }[] = (
    await response.json()
  ).plugins;
  const plugin = plugins.find((item) => item.id === pluginId);
  expect(
    plugin,
    `Bundled ${pluginId} must be in the real catalog`,
  ).toBeDefined();
  const enabled = await page.request.post(
    `/api/v1/plugins/${pluginId}/enable`,
    {
      headers: { Origin: origin() },
      data: { permissions: plugin!.permissions },
    },
  );
  expect(enabled.status(), await enabled.text()).toBe(200);
  // Refetch the real manifest before looking for the newly enabled command.
  await page.goto("/");
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

// A returned form submits with its command's manifest title.
const formTitles: Record<string, string> = {
  "record-decision": "Record decision",
  "review-decision": "Review decision",
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
      .getByRole("button", { name: formTitles[command], exact: true })
      .last()
      .click(),
  );
}

function noteIds(output: Output): string[] {
  return [...(output.ids ?? []), ...(output.items ?? []).flatMap(noteIds)];
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

async function selectStatus(dialog: Locator, value: "open" | "closed") {
  await dialog
    .getByRole("group", { name: "Status (open reopens)", exact: true })
    .getByRole("button", { name: value, exact: true })
    .click();
}

test("decision journal creates, reviews and reopens without rewriting the original", async ({
  page,
}, info) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await login(page, "decision-journal");
  const suffix = `${Date.now().toString(36)}-${info.project.name}`;
  let dialog = await palette(page, "Record decision");
  await uiCommand(page, "decision-journal", "record-decision", () =>
    dialog.getByRole("button", { name: /^Record decision/ }).click(),
  );
  await dialog
    .getByLabel("Decision title", { exact: true })
    .fill(`Decision ${suffix}`);
  await dialog
    .getByLabel("What did you decide?", { exact: true })
    .fill("Use a single SQLite file.");
  await dialog
    .getByLabel("Alternatives considered", { exact: true })
    .fill("A separate database service.");
  await dialog
    .getByLabel("Why this choice?", { exact: true })
    .fill("One owner needs simple operations.\nKeep backups easy to restore.");
  await dialog
    .getByLabel("Expected outcome", { exact: true })
    .fill("Recover the synthetic library in under five minutes.");
  await dialog
    .getByLabel("Review date (YYYY-MM-DD, optional)", { exact: true })
    .fill("2030-02-01");
  const created = await run(
    page,
    dialog,
    "decision-journal",
    "record-decision",
  );
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

  await page.goto(`/n/${rootId}`);
  dialog = await palette(page, "Review decision");
  await uiCommand(page, "decision-journal", "review-decision", () =>
    dialog.getByRole("button", { name: /^Review decision/ }).click(),
  );
  await dialog
    .getByLabel("What happened, or why reopen?", { exact: true })
    .fill("The synthetic restore finished in three minutes.");
  await dialog
    .getByLabel("What did you learn? (optional)", { exact: true })
    .fill("Keep a separate backup copy.");
  await selectStatus(dialog, "closed");
  const reviewed = await run(
    page,
    dialog,
    "decision-journal",
    "review-decision",
  );
  const outcomeId = noteIds(reviewed)[0];
  const outcome = await note(page, outcomeId);
  expect(outcome.body).toContain(`/n/${rootId}`);
  expect(outcome.body).toContain("three minutes");
  expect((await note(page, rootId)).body).toBe(edited.body);

  // Review can be reopened from an outcome note, retaining the earlier outcome.
  await page.goto(`/n/${outcomeId}`);
  dialog = await palette(page, "Review decision");
  await uiCommand(page, "decision-journal", "review-decision", () =>
    dialog.getByRole("button", { name: /^Review decision/ }).click(),
  );
  await dialog
    .getByLabel("What happened, or why reopen?", { exact: true })
    .fill("A new storage constraint needs another review.");
  await dialog
    .getByLabel("Next review date (YYYY-MM-DD, optional)", { exact: true })
    .fill("2030-03-01");
  await selectStatus(dialog, "open");
  const reopened = await run(
    page,
    dialog,
    "decision-journal",
    "review-decision",
  );
  expect(noteIds(reopened)[0]).not.toBe(outcomeId);
  expect((await note(page, outcomeId)).body).toBe(outcome.body);
  expect((await note(page, rootId)).body).toBe(edited.body);
  await page.goto(`/n/${rootId}`);
  const panel = page.getByLabel("About this note");
  await panel
    .locator("summary")
    .filter({ hasText: "Decision journal" })
    .click();
  await uiCommand(page, "decision-journal", "journal", () =>
    panel.getByRole("button", { name: "Open decision", exact: true }).click(),
  );
  dialog = page.getByRole("dialog", {
    name: "Search and commands",
    exact: true,
  });
  await expect(
    dialog.getByText("Open · revision 3 · review 2030-03-01", { exact: true }),
  ).toBeVisible();
  await expect(dialog.locator(`a[href="/n/${outcomeId}"]`)).toBeVisible();
  await page.screenshot({
    path: info.outputPath("decision-journal.png"),
    fullPage: true,
  });
  expect(errors).toEqual([]);
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
    dialog.getByRole("button", { name: /^Context packs/ }).click(),
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
  await dialog
    .getByLabel("Search source notes (blank browses recent)", { exact: true })
    .fill(suffix);
  await run(page, dialog, "context-packs", "sources");
  const choices = dialog.getByRole("group", {
    name: "Add source",
    exact: true,
  });
  if (await choices.count()) {
    await choices.getByRole("button", { name: new RegExp(suffix) }).click();
  } else {
    const select = dialog.getByLabel("Add source", { exact: true });
    const labels = await select.getByRole("option").allTextContents();
    const label = labels.find((value) => value.includes(suffix));
    expect(label, "Search must return the real synthetic source").toBeDefined();
    await select.selectOption({ label: label! });
  }
  await run(page, dialog, "context-packs", "sources");
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
  await run(page, dialog, "context-packs", "sources");
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
