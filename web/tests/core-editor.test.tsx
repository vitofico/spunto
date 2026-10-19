import { describe, it, expect } from "vitest";
import { activeLabel, highlightedParts } from "../src/components/core/Editor";
import {
  groupedNotes,
  toggleRenderedCheckbox,
} from "../src/components/core/NoteCard";
import type { Note } from "../src/lib";
describe("label autocomplete caret handling", () => {
  it("completes the active nested label and leaves later text untouched", () => {
    expect(activeLabel("Thought #project/spu later", 20)).toBe("project/spu");
    expect(activeLabel("Thought #project/spu later", 26)).toBeNull();
  });
  it("does not mistake words or headings for active labels", () => {
    expect(activeLabel("thought#idea", 12)).toBeNull();
    expect(activeLabel("# Heading", 9)).toBeNull();
    expect(activeLabel("A #", 3)).toBe("");
  });
  it("preserves owner text while providing highlights", () => {
    const body = "## Heading\nA #project/spunto and `code`";
    const parts = highlightedParts(body);
    expect(parts.map((p) => p.text).join("")).toBe(body);
    expect(parts.find((p) => p.text === "#project/spunto")?.kind).toBe("label");
  });
});
describe("timeline groups", () => {
  it("keeps pinned notes together before their ordinary day group", () => {
    const notes = [
      { id: "1", pinned: true, created_at: "2026-10-10T12:00:00Z" },
      { id: "2", pinned: false, created_at: "2026-10-10T13:00:00Z" },
      { id: "3", pinned: true, created_at: "2026-10-11T13:00:00Z" },
    ] as Note[];
    const groups = groupedNotes(notes);
    expect(groups[0].title).toBe("Pinned");
    expect(groups[0].notes.map((n) => n.id)).toEqual(["1", "3"]);
    expect(groups).toHaveLength(2);
  });
});

describe("rendered checkbox source offsets", () => {
  it("updates only the source token selected by the server", () => {
    const body = "😀 a `- [ ] code`\n- [ ] real";
    const offset = body.lastIndexOf("[ ") + 1;
    expect(toggleRenderedCheckbox(body, offset)).toBe(
      "😀 a `- [ ] code`\n- [x] real",
    );
    expect(toggleRenderedCheckbox(body, offset - 1)).toBe(body);
  });
});
