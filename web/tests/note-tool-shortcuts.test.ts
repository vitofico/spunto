import { expect, it } from "vitest";
import { noteShortcuts } from "../src/features/NoteTools";
import { noteToolKey, type NoteToolChoice } from "../src/features/Slots";
import type { NoteDetail } from "../src/lib/types";

function tool(key: string, trustedBundled = true): NoteToolChoice {
  const [plugin, id] = key.split("/");
  return {
    plugin,
    id,
    name: plugin,
    title: id,
    bundled: trustedBundled,
    trustedBundled,
  };
}

const plain = {
  note_tool_inputs: {
    image: false,
    audio: false,
    link: false,
    title_summary: true,
    labels: true,
    short_text: true,
  },
} as NoteDetail;
const image = {
  ...plain,
  note_tool_inputs: { ...plain.note_tool_inputs!, image: true },
};

it("ranks bundled inputs before writing tools, ignoring manifest order and duplicates", () => {
  const commands = [
    "links/link",
    "tidy/tidy",
    "label-suggestions/suggest-now",
    "title-summary/summarize",
    "link-enrichment/fetch",
    "voice-memos/transcribe",
    "image-text/read",
    "image-text/read",
  ].map((key) => tool(key));
  expect(noteShortcuts(commands, image).map(noteToolKey)).toEqual([
    "image-text/read",
    "voice-memos/transcribe",
  ]);
  expect(noteShortcuts(commands.slice(0, 4), plain).map(noteToolKey)).toEqual([
    "title-summary/summarize",
    "label-suggestions/suggest-now",
  ]);
});

it("keeps only genuine context shortcuts and falls back to Link to note when there's no stronger tool", () => {
  const link = tool("links/link");
  const tidy = tool("tidy/tidy");
  const read = tool("image-text/read");
  expect(noteShortcuts([link, tidy, read], image)).toEqual([read]);
  expect(noteShortcuts([link, tidy], plain)).toEqual([tidy]);
  expect(noteShortcuts([tool("image-text/read", false), link], image)).toEqual([
    link,
  ]);
  expect(
    noteShortcuts(
      [tool("image-text/read", false), tool("links/link", false)],
      image,
    ),
  ).toEqual([]);
});
