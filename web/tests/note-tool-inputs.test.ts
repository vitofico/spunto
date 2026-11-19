import { expect, it } from "vitest";
import { invalidateRequests } from "../src/lib/identity";
import {
  hasNoteToolInput,
  rememberAttachmentMime,
} from "../src/lib/noteToolInputs";
import type { NoteDetail } from "../src/lib/types";

const note = (body: string, overrides: Partial<NoteDetail> = {}) =>
  ({ body, source: { kind: "web" }, ...overrides }) as NoteDetail;

it("accepts legacy text and source URLs conservatively, with detail eligibility authoritative", () => {
  expect(hasNoteToolInput(note("one two"), "labels")).toBe(false);
  expect(hasNoteToolInput(note("one three two"), "labels")).toBe(true);
  expect(hasNoteToolInput(note("word ".repeat(299)), "title_summary")).toBe(
    false,
  );
  expect(hasNoteToolInput(note("word ".repeat(300)), "title_summary")).toBe(
    true,
  );
  expect(
    hasNoteToolInput(note("Read [source](https://example.org/page)"), "link"),
  ).toBe(true);
  expect(
    hasNoteToolInput(note("https://example.org/", { private: true }), "link"),
  ).toBe(false);
  expect(hasNoteToolInput(note("https://@example.org/"), "link")).toBe(false);
  expect(hasNoteToolInput(note("http:///example.org/"), "link")).toBe(false);
  expect(
    hasNoteToolInput(
      note("https://example.org/", {
        note_tool_inputs: {
          image: false,
          audio: false,
          link: false,
          title_summary: false,
          labels: false,
          short_text: true,
        },
      }),
      "link",
    ),
  ).toBe(false);
});

it("requires the same-identity server upload receipt and MIME for attached media", () => {
  const id = "0192f3c4-1111-7000-8000-000000000001";
  const url = `/a/${id}`;
  const body = `![diagram.gif](${url})`;
  expect(hasNoteToolInput(note(body), "image")).toBe(false);
  rememberAttachmentMime({
    id,
    url,
    snippet: body,
    mime: "audio/wav",
    size: 20,
  });
  expect(hasNoteToolInput(note(body), "image")).toBe(false);
  expect(hasNoteToolInput(note(body), "audio")).toBe(true);
  invalidateRequests();
  expect(hasNoteToolInput(note(body), "audio")).toBe(false);
});
