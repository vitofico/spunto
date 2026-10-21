import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { api } from "../src/lib/api";
import { queryClient } from "../src/lib/query";
import {
  Editor,
  activeLabel,
  continueList,
  highlightedParts,
} from "../src/components/core/Editor";
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

function Wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}
beforeEach(() => {
  queryClient.clear();
  vi.spyOn(api, "manifest").mockResolvedValue({ plugins: [] } as never);
  vi.spyOn(api, "labels").mockResolvedValue({
    labels: [{ path: "work", count: 3 }],
  } as never);
});
afterEach(() => {
  cleanup();
  queryClient.clear();
  vi.restoreAllMocks();
});

function Controlled({ initial, caret }: { initial: string; caret?: number }) {
  const [body, setBody] = useState(initial);
  return (
    <>
      <Editor
        note
        body={body}
        setBody={setBody}
        onSave={() => {}}
        caret={caret}
        autoFocus
      />
      <output data-testid="body">{body}</output>
    </>
  );
}
const field = () =>
  screen.getByRole("textbox", { name: "Edit note" }) as HTMLTextAreaElement;
const text = () => screen.getByTestId("body").textContent;

describe("editing at the caret (WW-02, WW-21, CA-34, WV-41)", () => {
  it("opens with the caret where the reader clicked, or at the end", () => {
    render(<Controlled initial={"Title\nmore"} caret={3} />, {
      wrapper: Wrapper,
    });
    expect(document.activeElement).toBe(field());
    expect(field().selectionStart).toBe(3);
    cleanup();
    render(<Controlled initial={"Title\nmore"} />, { wrapper: Wrapper });
    expect(field().selectionStart).toBe(10);
  });
  it("inserts a checklist on its own line at the caret, never before the title", async () => {
    render(<Controlled initial={"Title\nmore"} caret={5} />, {
      wrapper: Wrapper,
    });
    fireEvent.click(screen.getByRole("button", { name: "Add checklist" }));
    await waitFor(() => expect(text()).toBe("Title\n- [ ] \nmore"));
  });
  it("adds a label with a space before it and offers known and new labels", async () => {
    render(<Controlled initial="word" />, { wrapper: Wrapper });
    fireEvent.click(screen.getByRole("button", { name: "Add label" }));
    await waitFor(() => expect(text()).toBe("word #"));
    // The caret lands after the inserted "#" on the next frame.
    await act(() => new Promise((done) => requestAnimationFrame(done)));
    expect(
      within(await screen.findByRole("listbox", { name: "Labels" })).getByRole(
        "option",
        { name: "#work" },
      ),
    ).toBeTruthy();
    fireEvent.change(field(), { target: { value: "word #wor" } });
    const options = within(
      await screen.findByRole("listbox", { name: "Labels" }),
    ).getAllByRole("option");
    expect(options.map((option) => option.textContent)).toEqual([
      "#work",
      "New label #wor",
    ]);
  });
  it("waits for typing before suggesting labels, so a note ending in a label opens quietly", async () => {
    render(<Controlled initial="Plan #wo" />, { wrapper: Wrapper });
    await waitFor(() => expect(api.labels).toHaveBeenCalled());
    await act(async () => {});
    expect(screen.queryByRole("listbox")).toBeNull();
  });
  it("continues and ends lists on Enter", () => {
    expect(continueList("- [x] done", 10)).toEqual({
      body: "- [x] done\n- [ ] ",
      caret: 17,
    });
    expect(continueList("1. one", 6)).toEqual({
      body: "1. one\n2. ",
      caret: 10,
    });
    expect(continueList("a\n- ", 4)).toEqual({ body: "a\n", caret: 2 });
    expect(continueList("plain", 5)).toBeNull();
  });
});
