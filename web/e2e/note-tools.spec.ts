import { expect, test } from "@playwright/test";

test("compares an agent edit, selects contextual shortcuts and keeps label Undo after reload", async ({
  page,
}, info) => {
  const id = "0192f3c4-1111-7000-8000-000000000001";
  const created = "2026-11-19T09:00:00Z";
  const originalBody = "# Trip notes\n\nMeet at eight by the north entrance.";
  const body = "# Trip notes\n\nMeet at nine by the river entrance.";
  const receipt = {
    id: "label-receipt",
    plugin: "label-suggestions",
    note_id: id,
    action: "add_label",
    payload: { path: "travel" },
    status: "accepted",
    receipt: { note_version: 4, undoable: true },
  };
  const plugin = (
    id: string,
    name: string,
    command: string,
    title: string,
    permissions: string[],
    origin = "bundled",
  ) => ({
    id,
    name,
    origin,
    permissions,
    position: 0,
    state: "enabled",
    version: "1.1.0",
    contributes: {
      commands: [{ id: command, title, where: ["note-actions"] }],
    },
  });
  const plugins = [
    plugin("agent-inbox", "From agents", "mark-reviewed", "Accept as is", [
      "annotate",
    ]),
    plugin("image-text", "Text in images", "read", "Read image text", [
      "ai.vision",
    ]),
    plugin("voice-memos", "Voice memos", "transcribe", "Transcribe recording", [
      "ai.transcribe",
    ]),
    plugin(
      "link-enrichment",
      "Link enrichment",
      "fetch",
      "Fetch link details",
      ["net.fetch"],
    ),
    plugin("links", "Links", "link", "Link to note", ["notes.read"]),
    plugin(
      "label-suggestions",
      "Label suggestions",
      "suggest-now",
      "Suggest labels",
      ["ai.chat"],
    ),
    plugin("tidy", "Tidy", "tidy", "Tidy text", ["ai.chat"]),
    plugin(
      "decision-journal",
      "Decision journal",
      "record-decision",
      "Record decision",
      ["notes.write"],
    ),
    plugin(
      "custom-review",
      "Operator Review",
      "scan",
      "Scan note",
      ["notes.read"],
      "operator",
    ),
  ];
  let reviewed = false;
  let undone = false;
  let histories = 0;
  let image = true;
  let labels = true;
  let shortText = true;
  let legacy = false;
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const json = (value: unknown) => route.fulfill({ json: value });
    if (path.endsWith("/changes/wait")) return;
    if (path.endsWith("/auth/session"))
      return json({
        owner_id: "tools-fixture",
        credential_id: "fixture-session",
      });
    if (path.endsWith("/settings"))
      return json({
        theme: "carta",
        plugin_order: [],
        hidden_views: [],
        hidden_mcp_tools: [],
      });
    if (path.endsWith("/meta"))
      return json({
        setup_pending: false,
        plugins_rev: 1,
        ai_capabilities: [],
      });
    if (path.endsWith("/plugins/manifest"))
      return json({
        ui: 1,
        plugins_rev: 1,
        plugins: legacy
          ? plugins.map((plugin) => ({
              ...plugin,
              origin:
                plugin.id === "label-suggestions" ||
                plugin.origin === "operator"
                  ? "operator"
                  : undefined,
            }))
          : plugins,
        features: ["agent_review_versioned"],
      });
    if (path.endsWith("/plugins")) return json({ plugins_rev: 1, plugins });
    if (path.endsWith("/labels")) return json({ labels: [], total: 0 });
    if (path.endsWith("/revisions")) {
      histories++;
      return json([
        { version: 4, body, changed_at: created },
        { version: 2, body: originalBody, changed_at: created },
      ]);
    }
    if (path.endsWith("/commands/read"))
      return json({
        type: "text",
        text: "Image text is ready.",
        feedback: "success",
      });
    if (path.endsWith("/commands/scan"))
      return json({
        type: "markdown",
        text: "Inspection details",
        html: "<p>Inspection details</p>",
      });
    if (path.endsWith("/commands/suggest-now"))
      return json({
        type: "markdown",
        text: "Label matches",
        html: "<p>Label matches</p>",
      });
    if (path.endsWith("/commands/mark-reviewed")) {
      expect(route.request().postDataJSON()).toEqual({
        note_id: id,
        base_version: 4,
      });
      reviewed = true;
      return json({ type: "text", text: "Note accepted" });
    }
    if (path.endsWith("/label-receipt/undo")) {
      undone = true;
      return json({
        suggestion: { ...receipt, status: "dismissed" },
        applied: true,
      });
    }
    if (path.endsWith(`/notes/${id}`))
      return json({
        id,
        body: legacy ? `${body}\nhttps://example.org/trip` : body,
        title: "Trip notes",
        created_at: created,
        updated_at: created,
        version: undone ? 5 : 4,
        labels: undone ? [] : ["travel"],
        source: { kind: "web" },
        origin: "owner",
        updated_via_agent: true,
        annotations: reviewed
          ? [{ plugin: "agent-inbox", kind: "member:reviewed", payload: {} }]
          : [],
        suggestions: undone ? [] : [receipt],
        agent_review: reviewed
          ? null
          : { kind: "edited", before: { version: 2, body: originalBody } },
        note_tool_inputs: legacy
          ? undefined
          : {
              image,
              audio: false,
              link: false,
              title_summary: false,
              labels,
              short_text: shortText,
            },
        html: "<h1>Trip notes</h1><p>Meet at nine by the river entrance.</p>",
      });
    return json({});
  });

  await page.goto(`/n/${id}`);
  const comparison = page.getByRole("region", {
    name: "Agent changes",
    exact: true,
  });
  await expect(comparison.locator("del")).toContainText("Meet at eight");
  await expect(comparison.locator("ins")).toContainText("Meet at nine");
  await expect(page.getByText("Before · Version 2")).toBeVisible();
  await expect(page.getByText("After · Version 4")).toBeVisible();
  await expect(page.getByText("Added #travel")).toHaveCount(0);
  expect(histories).toBe(0);
  await page.screenshot({ path: info.outputPath("agent-comparison.png") });

  await page.setViewportSize({ width: 390, height: 844 });
  const shortcuts = page.locator(".note-tool-shortcut");
  await expect(shortcuts).toHaveText(["Read image text", "Suggest labels"]);
  const imageShortcut = page.getByRole("button", {
    name: "Read image text",
    exact: true,
  });
  expect((await imageShortcut.boundingBox())!.height).toBeGreaterThanOrEqual(
    44,
  );
  await page.screenshot({
    path: info.outputPath("automatic-shortcuts-mobile.png"),
  });
  const tools = page.getByRole("button", { name: "Note tools", exact: true });
  await tools.focus();
  await page.keyboard.press("Enter");
  const dialog = page.getByRole("dialog", { name: "Note tools", exact: true });
  await expect(
    dialog.getByRole("button", { name: "Read image text", exact: true }),
  ).toBeVisible();
  for (const name of [
    "Transcribe recording",
    "Fetch link details",
    "Record decision",
  ]) {
    await expect(dialog.getByRole("button", { name })).toHaveCount(0);
  }
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(390);
  await page.screenshot({
    path: info.outputPath("contextual-tools-mobile.png"),
  });
  await dialog.getByRole("button", { name: /Scan note/ }).click();
  await expect(dialog.getByText("Inspection details")).toBeVisible();
  await dialog.getByRole("button", { name: "Back", exact: true }).click();
  await expect(dialog.getByRole("button", { name: /Scan note/ })).toBeFocused();
  await dialog
    .getByRole("button", { name: "Read image text", exact: true })
    .click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText("Image text is ready.")).toBeVisible();
  await expect(tools).toBeFocused();

  await imageShortcut.click();
  await expect(page.getByText("Image text is ready.")).toBeVisible();
  await expect(dialog).toBeHidden();
  const labelShortcut = page.getByRole("button", {
    name: "Suggest labels",
    exact: true,
  });
  await labelShortcut.click();
  await expect(dialog.getByText("Label matches")).toBeVisible();
  await dialog.getByRole("button", { name: "Back", exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(labelShortcut).toBeFocused();

  await page
    .getByRole("button", { name: "Accept changes", exact: true })
    .click();
  await expect(
    page.getByText("Changes accepted", { exact: true }),
  ).toBeVisible();
  await expect(comparison).toHaveCount(0);
  await page.reload();
  await page.getByText("Label Undo available", { exact: true }).click();
  const history = page.getByRole("region", { name: "Recent label changes" });
  await expect(history.getByText("Added #travel")).toBeVisible();
  expect(histories).toBe(1);
  const undo = history.getByRole("button", { name: "Undo", exact: true });
  await undo.focus();
  await expect(undo).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByText("Change undone", { exact: true })).toBeVisible();
  await expect(history).toHaveCount(0);

  image = false;
  labels = false;
  await page.reload();
  await expect(shortcuts).toHaveText(["Tidy text"]);
  shortText = false;
  await page.reload();
  await expect(shortcuts).toHaveText(["Link to note"]);

  legacy = true;
  await page.reload();
  await expect(shortcuts).toHaveText(["Fetch link details"]);
  await tools.click();
  await expect(
    dialog.getByRole("button", { name: /Suggest labels/ }),
  ).toBeVisible();
});
