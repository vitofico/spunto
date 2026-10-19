import { describe, expect, it, vi } from "vitest";
import {
  DraftController,
  createIdempotencyKey,
  draftStorageKey,
  eraseOwnerDrafts,
  type DraftStorage,
} from "../src/lib/drafts";
import { ApiError } from "../src/lib/errors";
import type { Note } from "../src/lib/types";
const key = "00000000-0000-4000-8000-000000000001";
function memory(): DraftStorage {
  const map = new Map<string, string>();
  return {
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => {
      map.set(k, v);
    },
    removeItem: (k) => {
      map.delete(k);
    },
  };
}
const note = (body: string, version = 1) =>
  ({ id: "note-id", body, version }) as Note;
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
describe("durable saves", () => {
  it("keeps new keystrokes while a create ACK is in flight, then edits that same note", async () => {
    const pending = deferred<Note>();
    const store = memory();
    const create = vi.fn(() => pending.promise);
    const update = vi.fn(async (_id, body) => note(body, 2));
    const draft = new DraftController(
      store,
      "draft",
      { create, update },
      undefined,
      () => key,
    );
    draft.setBody("sent");
    const save = draft.save();
    draft.setBody("newer");
    pending.resolve(note("sent"));
    await save;
    expect(draft.getSnapshot().body).toBe("newer");
    expect(JSON.parse(store.getItem("draft")!).body).toBe("newer");
    await draft.save();
    expect(create).toHaveBeenCalledTimes(1);
    expect(update).toHaveBeenCalledWith("note-id", "newer", 1);
    expect(draft.getSnapshot().body).toBe("");
    expect(store.getItem("draft")).toBeNull();
  });
  it("retries an uncertain create with the exact original key and payload across reload", async () => {
    const store = memory();
    const create = vi
      .fn()
      .mockRejectedValueOnce(new ApiError(0, "unreachable"))
      .mockResolvedValue(note("first"));
    const transport = { create, update: vi.fn() };
    const original = new DraftController(
      store,
      "draft",
      transport,
      undefined,
      () => key,
    );
    original.setBody("first");
    await original.save();
    original.setBody("newer");
    const recovered = new DraftController(
      store,
      "draft",
      transport,
      undefined,
      () => crypto.randomUUID(),
    );
    await recovered.save();
    expect(create.mock.calls).toEqual([
      ["first", key],
      ["first", key],
    ]);
    expect(recovered.getSnapshot().body).toBe("newer");
    expect(recovered.getSnapshot().dirty).toBe(true);
  });
  it("preserves conflict copies and newer local text without another automatic mutation", async () => {
    const conflict = "00000000-0000-4000-8000-000000000002";
    const store = memory();
    const update = vi
      .fn()
      .mockRejectedValue(new ApiError(409, "version_conflict", conflict));
    const draft = new DraftController(
      store,
      "draft",
      { create: vi.fn(), update },
      note("base"),
    );
    draft.setBody("incoming");
    await draft.save();
    draft.setBody("later");
    await draft.save();
    expect(update).toHaveBeenCalledTimes(1);
    expect(draft.getSnapshot().conflict).toBe(conflict);
    expect(JSON.parse(store.getItem("draft")!).body).toBe("later");
  });
  it("does not remove a draft on validation, auth or transport errors", async () => {
    for (const status of [0, 401, 422]) {
      const store = memory();
      const draft = new DraftController(
        store,
        "draft",
        {
          create: vi
            .fn()
            .mockRejectedValue(new ApiError(status, "validation_failed")),
          update: vi.fn(),
        },
        undefined,
        () => key,
      );
      draft.setBody("keep");
      await draft.save();
      expect(store.getItem("draft")).not.toBeNull();
      expect(draft.getSnapshot().dirty).toBe(true);
    }
  });
  it("does not recreate cleared private drafts after deactivation and a delayed ACK", async () => {
    const pending = deferred<Note>();
    const store = memory();
    const draft = new DraftController(
      store,
      "draft",
      { create: () => pending.promise, update: vi.fn() },
      undefined,
      () => key,
    );
    draft.setBody("secret");
    const save = draft.save();
    draft.deactivate();
    store.removeItem("draft");
    pending.resolve(note("secret"));
    await save;
    expect(store.getItem("draft")).toBeNull();
  });
  it("keeps merged server text and newer keystrokes for review", async () => {
    const pending = deferred<Note>();
    const draft = new DraftController(
      memory(),
      "draft",
      { create: vi.fn(), update: () => pending.promise },
      note("base"),
    );
    draft.setBody("edit");
    const save = draft.save();
    draft.setBody("edit plus new typing");
    pending.resolve(note("server merge plus edit", 2));
    await save;
    expect(draft.getSnapshot().body).toBe("edit plus new typing");
    expect(draft.getSnapshot().state).toBe("conflict");
  });
  it("separates account namespaces and clears only the signed out owner", () => {
    localStorage.clear();
    const a = draftStorageKey("a", "compose");
    const b = draftStorageKey("b", "compose");
    localStorage.setItem(a, "private a");
    localStorage.setItem(b, "private b");
    eraseOwnerDrafts(localStorage, "a");
    expect(localStorage.getItem(a)).toBeNull();
    expect(localStorage.getItem(b)).toBe("private b");
  });
  it("updates clean editors on server refresh but preserves dirty edits and base version", async () => {
    const update = vi.fn(async () => note("local", 3));
    const draft = new DraftController(
      memory(),
      "draft",
      { create: vi.fn(), update },
      note("base"),
    );
    draft.syncNote(note("remote", 2));
    expect(draft.getSnapshot().body).toBe("remote");
    draft.setBody("local");
    draft.syncNote(note("new remote", 3));
    await draft.save();
    expect(update).toHaveBeenCalledWith("note-id", "local", 2);
  });
  it("survives unavailable storage and exposes the durability failure", () => {
    const draft = new DraftController(
      {
        getItem: () => null,
        setItem: () => {
          throw new Error("quota");
        },
        removeItem: () => {},
      },
      "draft",
      { create: vi.fn(), update: vi.fn() },
    );
    draft.setBody("kept in memory");
    expect(draft.getSnapshot().storageFailed).toBe(true);
    expect(draft.getSnapshot().body).toBe("kept in memory");
  });
});

it("creates RFC4122 UUIDv4 keys without requiring secure-context randomUUID", () => {
  const keys = new Set(Array.from({ length: 100 }, createIdempotencyKey));
  expect(keys.size).toBe(100);
  for (const key of keys)
    expect(key).toMatch(
      /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/,
    );
});

it("keeps legacy queued submission intent through newer ACKs and reloads until the exact revision is acknowledged", async () => {
  const store = memory();
  store.setItem(
    "draft",
    JSON.stringify({
      body: "newer",
      revision: 2,
      pending: { body: "submitted", revision: 1, key },
    }),
  );
  const create = vi.fn(async () => note("submitted"));
  const update = vi.fn(async (_id, body) => note(body, 2));
  const transport = { create, update };
  const restored = new DraftController(store, "draft", transport);
  expect(restored.canSaveAutomatically()).toBe(true);
  await restored.saveAutomatically();
  expect(create).toHaveBeenCalledWith("submitted", key);
  expect(restored.getSnapshot().body).toBe("newer");
  expect(restored.getSnapshot().submitted).toBe(true);
  expect(JSON.parse(store.getItem("draft")!).submitted).toBe(true);
  const newer = new DraftController(store, "draft", transport);
  await newer.saveAutomatically();
  expect(update).toHaveBeenCalledWith("note-id", "newer", 1);
  expect(newer.getSnapshot().submitted).toBe(false);
  expect(store.getItem("draft")).toBeNull();
  newer.setBody("next untouched capture");
  await newer.saveAutomatically();
  expect(newer.canSaveAutomatically()).toBe(false);
  expect(create).toHaveBeenCalledTimes(1);
  expect(update).toHaveBeenCalledTimes(1);
});

it("requires explicit retry after nonretryable failures even when the submitted draft is reloaded or corrected", async () => {
  for (const status of [401, 403, 422]) {
    const store = memory();
    const create = vi
      .fn()
      .mockRejectedValueOnce(new ApiError(status, "validation_failed"))
      .mockImplementation(async (body) => note(body));
    const transport = { create, update: vi.fn() };
    const first = new DraftController(
      store,
      "draft",
      transport,
      undefined,
      () => key,
    );
    first.setBody("submitted");
    await first.save();
    const restored = new DraftController(store, "draft", transport);
    expect(restored.getSnapshot().submitted).toBe(true);
    expect(restored.getSnapshot().retryBlocked).toBe(true);
    await restored.saveAutomatically();
    restored.setBody("corrected");
    await restored.saveAutomatically();
    expect(create).toHaveBeenCalledTimes(1);
    await restored.save();
    expect(create).toHaveBeenCalledTimes(2);
    expect(restored.getSnapshot().submitted).toBe(false);
    expect(store.getItem("draft")).toBeNull();
  }
});

it("recovers submission intent from legacy captures whose create ACK left newer unsaved typing", async () => {
  const store = memory();
  store.setItem(
    "draft",
    JSON.stringify({
      body: "newer typing",
      revision: 2,
      noteId: "created",
      version: 1,
    }),
  );
  const create = vi.fn();
  const update = vi.fn(
    async (id, body, version) => ({ id, body, version: version + 1 }) as Note,
  );
  const restored = new DraftController(store, "draft", { create, update });
  await restored.saveAutomatically();
  expect(update).toHaveBeenCalledWith("created", "newer typing", 1);
  expect(create).not.toHaveBeenCalled();
  expect(restored.getSnapshot().submitted).toBe(false);
  expect(store.getItem("draft")).toBeNull();
});
