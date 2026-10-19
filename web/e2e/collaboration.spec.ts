import { expect, test, type Page } from "@playwright/test";
const personal = {
  id: "personal-alice",
  name: "Personal",
  personal: true,
  role: "owner",
};
const team = { id: "team", name: "Studio", personal: false, role: "viewer" };
const note = {
  id: "00000000-0000-4000-8000-000000000001",
  title: "A shared thought",
  body: "A shared thought",
  html: "<p>A shared thought</p>",
  version: 1,
  created_at: "2026-10-18T12:00:00Z",
  source: {},
  suggestions: [],
  annotations: [],
};
async function fixture(page: Page) {
  const writes: string[] = [];
  const saves: { body: string; version: number }[] = [];
  let pauseSave: Promise<void> | undefined;
  let sharedRole = "viewer";
  let shared = { ...note };
  let conflict = false;
  const noteSpaces: string[] = [];
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const workspace = request.headers()["x-spunto-workspace"] ?? personal.id;
    const json = (data: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify(data),
      });
    if (path.endsWith("/changes/wait")) return route.abort();
    if (path.endsWith("/meta"))
      return json({
        setup_pending: false,
        unencrypted: false,
        plugins_rev: 0,
        ai_capabilities: [],
      });
    if (path.endsWith("/auth/session"))
      return json({
        owner_id: "alice",
        user_id: "alice",
        credential_id: "fixture-session",
        workspace_id: workspace,
        workspace_role: workspace === team.id ? "viewer" : "owner",
        auth_method: "oidc",
      });
    if (path.endsWith("/workspaces"))
      return json({
        workspaces: [personal, team],
        active_workspace_id: workspace,
      });
    if (path.endsWith("/members"))
      return json({
        members: [
          {
            user_id: "owner",
            email: "owner@example.test",
            display_name: "Owner",
            role: "owner",
          },
        ],
      });
    if (path.endsWith("/settings"))
      return json({
        theme: "carta",
        hidden_views: [],
        hidden_mcp_tools: [],
        plugin_order: [],
      });
    if (path.endsWith("/plugins/manifest"))
      return json({ ui: 1, plugins_rev: 0, plugins: [] });
    if (path.endsWith("/labels"))
      return json({
        labels: [
          { path: workspace === team.id ? "studio" : "personal", count: 1 },
        ],
        total: 1,
      });
    if (path.endsWith("/shared-notes"))
      return json({
        notes: [
          {
            ...shared,
            share_role: sharedRole,
            workspace_id: "other-space",
            shared_by: "Bob",
          },
        ],
      });
    if (path.includes("/shared-notes/")) {
      expect(new URL(request.url()).searchParams.get("workspace_id")).toBe(
        "other-space",
      );
      if (request.method() === "PATCH") {
        writes.push(path);
        saves.push(request.postDataJSON());
        const paused = pauseSave;
        pauseSave = undefined;
        if (paused) await paused;
        if (conflict) {
          shared = {
            ...shared,
            body: "Latest saved text",
            html: "<p>Latest saved text</p>",
            version: 2,
          };
          return json(
            {
              code: "version_conflict",
              conflict_id: "00000000-0000-4000-8000-000000000002",
            },
            409,
          );
        }
        shared = {
          ...shared,
          body: request.postDataJSON().body,
          version: shared.version + 1,
        };
      }
      return json({
        ...shared,
        share_role: sharedRole,
        workspace_id: "other-space",
        shared_by: "Bob",
      });
    }
    if (path.endsWith("/notes")) {
      noteSpaces.push(workspace);
      return json({
        notes: [
          {
            ...note,
            body: workspace === team.id ? "Studio note" : "Personal note",
            html: `<p>${workspace === team.id ? "Studio note" : "Personal note"}</p>`,
          },
        ],
        next_cursor: null,
      });
    }
    if (path.endsWith("/shares")) return json({ shares: [] });
    if (path.endsWith("/revisions")) return json([]);
    if (path.includes("/notes/")) return json(note);
    return json({ code: "not_found" }, 404);
  });
  return {
    writes,
    saves,
    noteSpaces,
    pauseNextSave: () => {
      let resume!: () => void;
      pauseSave = new Promise<void>((resolve) => {
        resume = resolve;
      });
      return () => resume();
    },
    editor: () => {
      sharedRole = "editor";
    },
    conflict: () => {
      conflict = true;
    },
  };
}
test("workspace selection survives reload while labels, drafts and viewer controls stay separate", async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto("/");
  await page.getByLabel("New note").fill("Private personal capture");
  await page.goto("/settings/spaces");
  await page.getByLabel("Current space").selectOption(team.id);
  await expect(page.getByText("Studio note", { exact: true })).toBeVisible();
  await expect(page.getByLabel("New note")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Pin", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("link", { name: "studio 1", exact: false }),
  ).toBeVisible();
  await page.keyboard.press("Control+k");
  await expect(
    page.getByRole("button", { name: "New note", exact: true }),
  ).toHaveCount(0);
  await page.keyboard.press("Escape");
  state.noteSpaces.length = 0;
  await page.reload();
  await expect(page.getByText("Studio note", { exact: true })).toBeVisible();
  expect(state.noteSpaces.every((workspace) => workspace === team.id)).toBe(
    true,
  );
  await page.goto("/settings/spaces");
  await expect(page.getByLabel("Username or verified email")).toHaveCount(0);
  await page.getByLabel("Current space").selectOption(personal.id);
  await expect(page.getByLabel("New note")).toHaveValue(
    "Private personal capture",
  );
  await expect(page.getByText("Personal note", { exact: true })).toBeVisible();
});
test("shared editors autosave without Save, retain typing during an ACK and preserve automatic conflicts", async ({
  page,
}) => {
  const state = await fixture(page);
  state.editor();
  await page.goto(`/shared/n/${note.id}?workspace_id=other-space`);
  const editor = page.getByLabel("Note text");
  const resume = state.pauseNextSave();
  try {
    await editor.fill("First automatic edit");
    await expect
      .poll(() => state.saves, { timeout: 12_000 })
      .toEqual([{ body: "First automatic edit", version: 1 }]);
    await editor.fill("Newer typing while the first save is in flight");
  } finally {
    resume();
  }
  await expect(editor).toHaveValue(
    "Newer typing while the first save is in flight",
  );
  await expect
    .poll(() => state.saves, { timeout: 12_000 })
    .toEqual([
      { body: "First automatic edit", version: 1 },
      { body: "Newer typing while the first save is in flight", version: 2 },
    ]);
  await expect(page.getByRole("main").getByRole("status")).toHaveText("Saved");
  state.conflict();
  await editor.fill("Automatic conflict stays on this device");
  await expect(
    page.getByRole("heading", { name: "Review conflicting edits" }),
  ).toBeVisible({ timeout: 12_000 });
  await expect(editor).toHaveValue("Automatic conflict stays on this device");
  await expect(
    page.getByRole("link", { name: "Open preserved edit" }),
  ).toHaveAttribute("href", /workspace_id=other-space/);
  expect(
    state.writes.every((path) => path === "/api/v1/shared-notes/" + note.id),
  ).toBe(true);
});
test("shared-note viewers cannot edit; editors save through the shared API and review conflicts", async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto("/shared");
  await page
    .getByRole("link", { name: "A shared thought", exact: true })
    .click();
  await expect(page).toHaveURL(/workspace_id=other-space/);
  await expect(page.getByText("Shared by Bob · Can view")).toBeVisible();
  await expect(page.getByRole("textbox")).toHaveCount(0);
  state.editor();
  await page.reload();
  await page.getByLabel("Note text").fill("My collaboration edit");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("main").getByRole("status")).toHaveText("Saved");
  expect(state.writes).toEqual(["/api/v1/shared-notes/" + note.id]);
  state.conflict();
  await page.getByLabel("Note text").fill("Conflicting collaboration edit");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Review conflicting edits" }),
  ).toBeVisible();
  await expect(
    page.getByText("Latest saved text", { exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Note text")).toHaveValue(
    "Conflicting collaboration edit",
  );
  await expect(
    page.getByRole("link", { name: "Open preserved edit" }),
  ).toHaveAttribute("href", /workspace_id=other-space/);
  await expect(
    page.getByRole("button", { name: "Save", exact: true }),
  ).toBeDisabled();
});
