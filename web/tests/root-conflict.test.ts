import { expect, it, vi } from "vitest";
import { DraftController } from "../src/lib/drafts";
import { ApiError } from "../src/lib/errors";
import type { Note } from "../src/lib/types";

function storage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value);
    },
    removeItem: (key: string) => {
      values.delete(key);
    },
  };
}
function note(body: string, version = 1) {
  return { id: "original", body, version } as Note;
}

it("reviewing a conflict preserves later typing and resumes saves with the reviewed version", async () => {
  const update = vi
    .fn()
    .mockRejectedValueOnce(new ApiError(409, "version_conflict", "saved-copy"))
    .mockImplementation(async (_id: string, body: string, version: number) =>
      note(body, version + 1),
    );
  const store = storage();
  const controller = new DraftController(
    store,
    "draft",
    { create: vi.fn(), update },
    note("base"),
  );
  controller.setBody("incoming");
  await controller.save();
  controller.setBody("later local typing");
  const recovered = new DraftController(
    store,
    "draft",
    { create: vi.fn(), update },
    note("remote", 4),
  );
  await recovered.save();
  expect(update).toHaveBeenCalledTimes(1);
  expect(recovered.resumeReviewed(note("remote", 4))).toBe(true);
  expect(recovered.getSnapshot().body).toBe("later local typing");
  await recovered.save();
  expect(update).toHaveBeenLastCalledWith("original", "later local typing", 4);
  expect(recovered.getSnapshot().conflict).toBeNull();
  expect(store.getItem("draft")).toBeNull();
  recovered.setBody("another edit");
  await recovered.save();
  expect(update).toHaveBeenLastCalledWith("original", "another edit", 5);
});

it("a merged acknowledgement preserves newer text until it has been explicitly reviewed", async () => {
  let acknowledge!: (note: Note) => void;
  const update = vi.fn().mockImplementation(
    () =>
      new Promise<Note>((resolve) => {
        acknowledge = resolve;
      }),
  );
  const controller = new DraftController(
    storage(),
    "draft",
    { create: vi.fn(), update },
    note("base"),
  );
  controller.setBody("sent");
  const save = controller.save();
  controller.setBody("newer than sent");
  acknowledge(note("server merged text", 3));
  await save;
  expect(controller.getSnapshot().conflict).toBe("original");
  expect(controller.getSnapshot().body).toBe("newer than sent");
  expect(
    controller.resumeReviewed({ id: "other", body: "other", version: 1 }),
  ).toBe(false);
  expect(controller.resumeReviewed(note("server merged text", 3))).toBe(true);
  expect(controller.getSnapshot().body).toBe("newer than sent");
});
