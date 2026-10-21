import { readFileSync } from "node:fs";
import { test, expect } from "@playwright/test";

// Intentional DeleteData acceptance needs its own freshly seeded volume per
// engine. Never run this against the ordinary 54-case fixture or an owner root.
test.skip(
  process.env.SPUNTO_REMOVED_BROWSER_FIXTURE !== "removed-browser-v1" ||
    process.env.SPUNTO_E2E_URL !== "http://127.0.0.1:18223",
  "Requires the explicit isolated Removed-plugin browser fixture",
);

type Card = {
  id: string;
  name: string;
  state: string;
  switch: "on" | "off";
  origin: string;
  source_kind: string;
  pinned_by_env: boolean | 0 | 1;
  can_enable: boolean;
  can_delete_data: boolean;
  permissions: string[];
  permission_sentences: string[];
  granted: string[];
  data_count: number;
  data_counts: Record<string, number>;
  data_size_bytes: number;
  plugins_rev?: number;
};
type Catalog = { plugins: Card[]; plugins_rev: number };
const ids = ["browser-retained", "browser-locked", "browser-installed"];

test("packaged Removed cards retain metadata and notes while owner cleanup stays scoped", async ({
  page,
}, info) => {
  const metadataPath = process.env.SPUNTO_REMOVED_FIXTURE_METADATA;
  if (!metadataPath)
    throw new Error("Supply this engine's fresh seed metadata path");
  const seed = JSON.parse(readFileSync(metadataPath, "utf8")) as {
    synthetic_fixture: string;
    note_ids: Record<string, string>;
    owner_note_id: string;
  };
  expect(seed.synthetic_fixture).toBe("removed-browser-v1");
  await page.goto("/");
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.SPUNTO_E2E_PASSWORD ?? "browser acceptance password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByLabel("New note")).toBeVisible();
  const origin = "http://127.0.0.1:18223";
  async function catalog(): Promise<Catalog> {
    const response = await page.request.get("/api/v1/plugins");
    expect(response.status()).toBe(200);
    return response.json();
  }
  function card(value: Catalog, id: string): Card {
    const result = value.plugins.find((item) => item.id === id);
    if (!result) throw new Error(`Fresh fixture lacks ${id}`);
    return result;
  }
  async function note(id: string) {
    expect(id).toMatch(/^[0-9a-f-]{36}$/);
    const response = await page.request.get(`/api/v1/notes/${id}`);
    expect(response.status()).toBe(200);
    const value = await response.json();
    // Cleanup intentionally removes annotations/suggestions, not note content,
    // provenance, flags or versions. Avoid comparing removed derived surfaces.
    return {
      id: value.id,
      body: value.body,
      version: value.version,
      origin: value.origin,
      source: value.source,
      machine: value.machine,
      private: value.private,
      pinned: value.pinned,
      archived: value.archived,
      trashed: value.trashed,
    };
  }
  const original = await catalog();
  const installed = card(original, ids[2]);
  expect(installed).toMatchObject({
    origin: "operator",
    source_kind: "folder",
    switch: "off",
    state: "disabled",
    can_enable: true,
  });
  expect([false, 0]).toContain(installed.pinned_by_env);
  expect(installed.granted).toEqual(installed.permissions);
  const retainedNotes = await Promise.all(
    [...ids.map((id) => seed.note_ids[id]), seed.owner_note_id].map(note),
  );
  for (const id of ids.slice(0, 2)) {
    const removed = card(original, id);
    expect(removed).toMatchObject({
      origin: "operator",
      source_kind: "folder",
      state: "removed",
      switch: "off",
      can_enable: false,
      can_delete_data: true,
    });
    expect(removed.granted).toEqual(removed.permissions);
    expect(removed.data_count).toBeGreaterThanOrEqual(5);
    expect(removed.data_size_bytes).toBeGreaterThan(0);
    expect(Object.values(removed.data_counts).every((count) => count > 0)).toBe(
      true,
    );
    expect(id === ids[1] ? [true, 1] : [false, 0]).toContain(
      removed.pinned_by_env,
    );
  }
  expect(card(original, "tasks").origin).toBe("bundled");
  const settingsRequests: string[] = [];
  page.on("request", (request) => {
    if (
      ids
        .slice(0, 2)
        .some(
          (id) =>
            new URL(request.url()).pathname ===
            `/api/v1/plugins/${id}/settings`,
        )
    ) {
      settingsRequests.push(request.method());
    }
  });
  try {
    await page.goto("/settings/plugins");
    for (const id of ids.slice(0, 2)) {
      const removed = card(original, id);
      const row = page
        .getByRole("listitem")
        .filter({ has: page.getByText(removed.name, { exact: true }) });
      await expect(row.getByText(/Operator-installed/)).toBeVisible();
      await expect(row.getByRole("switch")).toHaveCount(0);
      await expect(
        row.getByRole("link", { name: removed.name, exact: true }),
      ).toHaveAttribute("href", `/settings/plugins/${id}`);
      if (id === ids[1])
        await expect(
          row.getByText("Set in the environment · Off", { exact: true }),
        ).toBeVisible();
    }
    // Exercise real UI permission consent and record the actual returned state
    // and global revision, never assume revision+1 or state from the click.
    const toggle = page.getByRole("switch", {
      name: `Enable ${installed.name}`,
      exact: true,
    });
    const enabledReply = page.waitForResponse(
      (reply) =>
        reply.url().endsWith(`/plugins/${installed.id}/enable`) &&
        reply.request().method() === "POST",
    );
    await toggle.click();
    const permissionDialog = page.getByRole("dialog");
    for (const sentence of installed.permission_sentences)
      await expect(
        permissionDialog.getByText(sentence, { exact: true }),
      ).toBeVisible();
    await permissionDialog
      .getByRole("button", { name: "Turn on", exact: true })
      .click();
    const reply = await enabledReply;
    expect(reply.status()).toBe(200);
    expect(reply.request().postDataJSON()).toEqual({
      permissions: installed.permissions,
    });
    const enabled = (await reply.json()) as Card;
    expect(enabled).toMatchObject({
      id: installed.id,
      state: "enabled",
      switch: "on",
      origin: "operator",
    });
    expect(Number.isSafeInteger(enabled.plugins_rev)).toBe(true);
    const afterEnable = await catalog();
    expect(afterEnable.plugins_rev).toBeGreaterThanOrEqual(
      enabled.plugins_rev!,
    );
    expect(card(afterEnable, installed.id)).toMatchObject({
      switch: enabled.switch,
      state: enabled.state,
      granted: enabled.granted,
    });
    await expect(toggle).toBeChecked();
    const disabledReply = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/plugins/${installed.id}/disable`) &&
        response.request().method() === "POST",
    );
    await toggle.click();
    const offReply = await disabledReply;
    expect(offReply.status()).toBe(200);
    const off = (await offReply.json()) as Card;
    expect(off).toMatchObject({
      id: installed.id,
      switch: "off",
      state: "disabled",
    });
    expect(Number.isSafeInteger(off.plugins_rev)).toBe(true);
    await expect(toggle).not.toBeChecked();
    const pinnedDisable = await page.request.post(
      `/api/v1/plugins/${ids[1]}/disable`,
      { headers: { Origin: origin } },
    );
    expect(pinnedDisable.status()).toBe(409);
    expect((await pinnedDisable.json()).code).toBe("pinned_by_environment");
    for (const id of ids.slice(0, 2)) {
      const removed = card(original, id);
      await page.goto(`/settings/plugins/${id}`);
      await expect(
        page.getByRole("heading", { name: removed.name, exact: true }),
      ).toBeVisible();
      await expect(
        page.getByText(
          "Removed. Plugin code is no longer installed. Its stored data and notes remain.",
          { exact: true },
        ),
      ).toBeVisible();
      await expect(page.getByRole("switch")).toHaveCount(0);
      await expect(
        page.getByRole("heading", { name: "AI", exact: true }),
      ).toHaveCount(0);
      await expect(
        page.getByRole("heading", { name: "Run", exact: true }),
      ).toHaveCount(0);
      await expect(
        page.getByRole("button", { name: "Undo", exact: true }),
      ).toHaveCount(0);
      for (const sentence of removed.permission_sentences)
        await expect(page.getByText(sentence, { exact: true })).toBeVisible();
      if (id === ids[1])
        await expect(
          page.getByText("Set in the environment · Off", { exact: true }),
        ).toBeVisible();
      await page.reload();
      await expect(
        page.getByRole("heading", { name: removed.name, exact: true }),
      ).toBeVisible();
      await page.screenshot({
        path: info.outputPath(`${id}-before-cleanup.png`),
        fullPage: true,
      });
      await page
        .getByRole("button", {
          name: "Delete this plugin’s data…",
          exact: true,
        })
        .click();
      const confirmation = page.getByRole("dialog", {
        name: `Delete ${removed.name}’s data?`,
        exact: true,
      });
      await expect(
        confirmation.getByText(/Its notes stay: they are notes\./),
      ).toBeVisible();
      const deletionReply = page.waitForResponse(
        (response) =>
          response.url().endsWith(`/plugins/${id}/data`) &&
          response.request().method() === "DELETE",
      );
      await confirmation
        .getByRole("button", {
          name: `Delete ${removed.data_count} records`,
          exact: true,
        })
        .click();
      const deleted = await deletionReply;
      expect(deleted.status()).toBe(204);
      expect((await deleted.request().allHeaders()).origin).toBe(origin);
      await expect(confirmation).toHaveCount(0);
      const after = card(await catalog(), id);
      expect(after).toMatchObject({
        state: "removed",
        switch: "off",
        can_enable: false,
        data_count: 0,
        data_size_bytes: 0,
        pinned_by_env: removed.pinned_by_env,
        origin: removed.origin,
        source_kind: removed.source_kind,
        granted: removed.granted,
        permissions: removed.permissions,
      });
      expect(
        Object.values(after.data_counts).every((count) => count === 0),
      ).toBe(true);
      await page.reload();
      await expect(
        page.getByText("0 records · 0 bytes. Its notes stay: they are notes.", {
          exact: true,
        }),
      ).toBeVisible();
    }
    expect(
      settingsRequests,
      "Removed detail must never request unavailable settings code",
    ).toEqual([]);
    expect(
      await Promise.all(
        [...ids.map((id) => seed.note_ids[id]), seed.owner_note_id].map(note),
      ),
    ).toEqual(retainedNotes);
    expect(card(await catalog(), installed.id).data_counts).toEqual(
      installed.data_counts,
    );
  } finally {
    // Revert only the installed switch/grants; deleted synthetic data is
    // intentionally irreversible and requires the explicit volume reset.
    const restore = await page.request.post(
      `/api/v1/plugins/${installed.id}/disable`,
      { headers: { Origin: origin } },
    );
    expect(restore.status()).toBe(200);
    expect(card(await catalog(), installed.id)).toMatchObject({
      switch: installed.switch,
      state: installed.state,
      granted: installed.granted,
    });
  }
});
