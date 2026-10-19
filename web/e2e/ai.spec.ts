import { test, expect, type Page } from "@playwright/test";
import type { AIModelSelections } from "../src/lib/types";

const id = "0192f3c4-1111-7000-8000-000000000001";
const note = {
  id,
  body: "Coffee in Rome",
  title: "Coffee",
  html: "<p>Coffee in Rome</p>",
  version: 1,
  change_seq: 1,
  labels: [],
  created_at: "2026-10-14T08:30:00Z",
  updated_at: "2026-10-14T08:30:00Z",
  pinned: false,
  archived: false,
  trashed: false,
  private: false,
  open_tasks: 0,
  source: { kind: "web", ref: null },
  origin: "owner",
  machine: false,
  conflict_of: null,
  suggestions: [],
  annotations: [],
};
const ai = {
  profiles: [
    {
      name: "local",
      provider: "openai",
      base_url: "http://localhost:11434/v1",
      embed_model: "embeddinggemma",
      chat_model: "fixture-chat",
      leaves_network: { chat: false, embed: false },
    },
  ],
  capabilities: ["ai.chat", "ai.embed"],
  bindings: { chat: "local", embed: "local" },
  health: [],
  indexing: {
    available: true,
    enabled: true,
    chunks: 10,
    routes: [
      {
        profile: "local",
        active_key: "old",
        target_key: "new",
        indexed: 3,
        total: 5,
      },
    ],
  },
};
const ask = {
  id: "ask",
  name: "Ask your notes",
  version: "1",
  description: "Answer a question with citations to your notes.",
  category: "recall",
  kind: "server",
  position: 0,
  switch: "on",
  state: "enabled",
  permissions: ["notes.read", "notes.create", "ai.chat", "ai.embed"],
  ai_route: { chat: "local" },
  contributes: {
    commands: [
      { id: "ask", title: "Ask your notes", where: ["palette"] },
      { id: "save-answer", title: "Save as note", where: [] },
    ],
  },
};
async function fixture(page: Page, withModels = false) {
  await page.addInitScript(() => {
    // These are online feature fixtures; the Linux test container has no
    // external network interface, which otherwise marks Chromium offline.
    Object.defineProperty(navigator, "onLine", { get: () => true });
  });
  const routes: unknown[] = [],
    reindex: unknown[] = [],
    commands: { id: string; args: unknown }[] = [];
  const modelSaves: unknown[] = [];
  let selections: AIModelSelections = {};
  let revision = 1;
  const modelStatus = () => ({
    ...ai,
    can_manage: true,
    profiles: ai.profiles.map((profile) => ({
      ...profile,
      ...(selections.embed ? { embed_model: selections.embed.model } : {}),
      ...(selections.vision ? { vision_model: selections.vision.model } : {}),
      leaves_network: { ...profile.leaves_network, vision: true },
    })),
    bindings: {
      ...ai.bindings,
      ...(selections.vision ? { vision: "local" } : {}),
    },
    model_selection: {
      revision: `fixture-revision-${revision}`,
      selections,
      defaults: {
        chat: { profile: "local", model: "fixture-chat" },
        embed: { profile: "local", model: "embeddinggemma" },
      },
    },
  });
  let releaseHybrid: (() => void) | undefined;
  const hybridWait = new Promise<void>((resolve) => {
    releaseHybrid = resolve;
  });
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      path = url.pathname;
    const json = (value: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify(value),
      });
    if (path.endsWith("/changes/wait")) return route.abort("failed");
    if (path.endsWith("/meta"))
      return json({
        version: "test",
        setup_pending: false,
        unencrypted: false,
        plugins_rev: 1,
        ai_capabilities: ai.capabilities,
      });
    if (path.endsWith("/auth/session")) return json({ owner_id: "m4-fixture" });
    if (path.endsWith("/plugins/manifest"))
      return json({
        ui: 1,
        plugins_rev: 1,
        plugins: [
          ask,
          {
            ...ask,
            id: "related",
            name: "Related",
            permissions: ["ai.embed", "mcp"],
            contributes: {
              panel: { title: "Related", mode: "live", route: "notes/{id}" },
            },
          },
        ],
      });
    if (path.endsWith("/plugins"))
      return json({ plugins: [ask], plugins_rev: 1 });
    if (path.endsWith("/ask/settings"))
      return json({
        schema: { type: "object", properties: {} },
        values: {},
        secrets: {},
      });
    if (path.endsWith("/settings"))
      return json({
        theme: "carta",
        plugin_order: [],
        hidden_views: [],
        hidden_mcp_tools: [],
      });
    if (path.endsWith("/ai")) return json(withModels ? modelStatus() : ai);
    if (path.endsWith("/ai/models")) {
      if (request.method() === "PUT") {
        const body = request.postDataJSON();
        modelSaves.push(body);
        selections = body.selections;
        revision++;
        return json(modelStatus());
      }
      return json({
        profiles: [
          {
            profile: "local",
            error: null,
            models: [
              {
                id: "fixture-embedding-v2",
                capabilities: ["embed"],
                leaves_network: false,
              },
              {
                id: "fixture-vision-cloud",
                capabilities: ["vision"],
                leaves_network: true,
              },
            ],
          },
        ],
      });
    }
    if (path.endsWith("/ai/check"))
      return json({
        health: [
          { profile: "local", capability: "embed", ok: true, dimension: 768 },
        ],
      });
    if (path.endsWith("/ai/reindex")) {
      reindex.push(request.postDataJSON());
      return json({ indexing: ai.indexing });
    }
    if (path.endsWith("/ai-route")) {
      routes.push(request.postDataJSON());
      return json(ask);
    }
    if (path.endsWith("/labels")) return json({ labels: [], total: 0 });
    if (path.endsWith("/suggestions")) return json({ suggestions: [] });
    if (path.endsWith("/related/panel"))
      return json({ ui: 1, items: [{ type: "notes", ids: ["neighbor"] }] });
    if (path.includes("/commands/")) {
      const command = path.split("/").at(-1)!;
      const args = request.postDataJSON();
      commands.push({ id: command, args });
      if (command === "save-answer")
        return json({ type: "notes", ids: ["saved-answer"] });
      if (!args.question)
        return json({
          type: "form",
          schema: {
            type: "object",
            required: ["question"],
            properties: {
              question: {
                type: "string",
                title: "Question",
                minLength: 1,
                maxLength: 500,
              },
            },
          },
        });
      return json({
        ui: 1,
        items: [
          {
            type: "markdown",
            text: "You had coffee in Rome.",
            html: "<p>You had coffee in Rome.</p>",
          },
          { type: "notes", ids: [id] },
          {
            type: "command",
            id: "save-answer",
            title: "Save as note",
            args: { answer_id: "fixture-receipt" },
          },
        ],
      });
    }
    if (path.endsWith("/search")) {
      if (!url.searchParams.get("q"))
        return json({ hits: [], semantic: "off", next_cursor: null });
      if (url.searchParams.get("mode") === "hybrid") {
        await hybridWait;
        return json({
          hits: [
            {
              ...note,
              id: "meaning",
              body: "Caffè in Milan",
              html: "<p>Caffè in Milan</p>",
              match: "meaning",
            },
          ],
          semantic: "on",
          next_cursor: null,
        });
      }
      return json({ hits: [note], semantic: "off", next_cursor: null });
    }
    if (path.endsWith("/notes"))
      return json({ notes: [note], next_cursor: null });
    if (path.endsWith("/revisions")) return json([]);
    if (path.includes("/notes/"))
      return json(
        path.endsWith("neighbor")
          ? {
              ...note,
              id: "neighbor",
              body: "Coffee neighbor",
              html: "<p>Coffee neighbor</p>",
            }
          : note,
      );
    return json({ code: "not_found" }, 404);
  });
  return {
    routes,
    reindex,
    commands,
    modelSaves,
    releaseHybrid: () => releaseHybrid?.(),
  };
}

test("model inventory choices and environment reset require Save on mobile", async ({
  page,
}, info) => {
  const state = await fixture(page, true);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/settings/ai");
  const embedding = page.getByRole("combobox", { name: "Embeddings model" });
  await expect(
    embedding.locator("option").filter({ hasText: "fixture-embedding-v2" }),
  ).toHaveCount(1);
  await embedding.selectOption(
    JSON.stringify(["local", "fixture-embedding-v2"]),
  );
  await page
    .getByRole("combobox", { name: "Vision model" })
    .selectOption(JSON.stringify(["local", "fixture-vision-cloud"]));
  expect(state.modelSaves).toEqual([]);
  await expect(
    page.getByText(
      "fixture-vision-cloud at http://localhost:11434/v1 · Outside your network",
    ),
  ).toBeVisible();
  await page.getByRole("button", { name: "Save models" }).click();
  await expect(page.getByText("Model choices saved.")).toBeVisible();
  expect(state.modelSaves).toEqual([
    {
      revision: "fixture-revision-1",
      selections: {
        embed: { profile: "local", model: "fixture-embedding-v2" },
        vision: { profile: "local", model: "fixture-vision-cloud" },
      },
    },
  ]);
  await expect(embedding).toHaveValue(
    JSON.stringify(["local", "fixture-embedding-v2"]),
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  if (info.project.name === "chromium") {
    await page.getByRole("heading", { name: "AI", exact: true }).click();
    await page.screenshot({
      path: "/tmp/spunto-model-picker-mobile.png",
      fullPage: true,
    });
  }
  await page.getByRole("button", { name: "Use server defaults" }).click();
  expect(state.modelSaves).toHaveLength(1);
  await expect(embedding).toHaveValue("");
  await page.getByRole("button", { name: "Save models" }).click();
  await expect
    .poll(() => state.modelSaves.at(-1))
    .toEqual({ revision: "fixture-revision-2", selections: {} });
});

test("AI profiles, checks, indexing and route reset on mobile", async ({
  page,
}, info) => {
  const state = await fixture(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/settings/ai");
  await expect(
    page.getByText("embeddinggemma", { exact: false }),
  ).toBeVisible();
  await expect(page.getByRole("textbox")).toHaveCount(0);
  await page.getByRole("button", { name: "Check providers" }).click();
  await expect(
    page.getByText("768 dimensions", { exact: false }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Reindex notes" }).click();
  await expect(page.getByText("Index work queued.")).toBeVisible();
  expect(state.reindex).toEqual([{ drop: false }]);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  if (info.project.name === "chromium")
    await page.getByRole("heading", { name: "AI", exact: true }).click();
  if (info.project.name === "chromium")
    await page.screenshot({
      path: "/tmp/spunto-m4-ai-mobile.png",
      fullPage: true,
    });
  await page.goto("/settings/plugins/ask");
  await page.getByLabel("Chat profile", { exact: true }).selectOption("");
  await page.getByRole("button", { name: "Save AI routes" }).click();
  await expect(page.getByText("AI routes saved.")).toBeVisible();
  expect(state.routes).toEqual([{}]);
});

test("keyword results render before hybrid replaces the list", async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto("/search?q=coffee");
  await expect(page.getByText("Coffee in Rome", { exact: true })).toBeVisible();
  state.releaseHybrid();
  await expect(page.getByText("Caffè in Milan", { exact: true })).toBeVisible();
  await expect(page.getByText("Coffee in Rome", { exact: true })).toHaveCount(
    0,
  );
  await expect(page.getByText("≈ similar meaning")).toBeVisible();
});

test("live related panel and Ask form preserve the save-answer action", async ({
  page,
}, info) => {
  const state = await fixture(page);
  await page.goto(`/n/${id}`);
  await expect(page.getByText("Related · 1 item")).toBeVisible();
  await page.getByText("Related · 1 item").click();
  await expect(page.getByText("Coffee neighbor")).toBeVisible();
  await page.keyboard.press("Meta+k");
  await page.getByRole("button", { name: /Ask your notes/ }).click();
  await page
    .getByLabel("Question", { exact: true })
    .fill("Where did I have coffee?");
  await page.getByRole("button", { name: "Ask", exact: true }).click();
  await expect(page.getByText("You had coffee in Rome.")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Save as note", exact: true }),
  ).toHaveCount(1);
  if (info.project.name === "chromium")
    await page.screenshot({
      path: "/tmp/spunto-m4-ask-desktop.png",
      fullPage: true,
    });
  await page.getByRole("button", { name: "Save as note", exact: true }).click();
  await expect
    .poll(() => state.commands.at(-1))
    .toEqual({ id: "save-answer", args: { answer_id: "fixture-receipt" } });
  await expect(page.locator('a[href="/n/saved-answer"]')).toHaveText(
    "Coffee in Rome",
  );
});

test("private and machine notes do not request AI live panels", async ({
  page,
}) => {
  await fixture(page);
  let requests = 0;
  page.on("request", (request) => {
    if (new URL(request.url()).pathname === "/api/v1/plugins/related/panel")
      requests++;
  });
  for (const flag of ["private", "machine"] as const) {
    await page.route(`**/api/v1/notes/${id}?**`, (route) =>
      route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ ...note, [flag]: true }),
      }),
    );
    const manifest = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === "/api/v1/plugins/manifest",
    );
    await page.goto(`/n/${id}`);
    await manifest;
    await expect(page.locator(".note-body")).toBeVisible();
    await expect(
      page.locator("summary").filter({ hasText: /^Related/ }),
    ).toHaveCount(0);
    expect(requests).toBe(0);
    await page.unroute(`**/api/v1/notes/${id}?**`);
  }
});
