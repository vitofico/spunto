import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  NoteBody,
  NoteCard,
  toggleRenderedCheckbox,
} from "../src/components/core/NoteCard";
import type { Note } from "../src/lib/types";
vi.mock("../src/lib/query", async (original) => ({
  ...(await original<typeof import("../src/lib/query")>()),
  useManifest: () => ({ data: { plugins: [] } }),
}));
afterEach(() => {
  cleanup();
  window.history.replaceState(null, "", "/");
});
const preview = (
  title: string,
  html: string,
  body: string,
  labels: string[] = [],
) => render(<NoteBody preview note={{ title, html, body, labels } as Note} />);
it("keeps the original text when its display title is abbreviated", () => {
  preview(
    "A longer thought",
    "<p>A longer thought that must remain complete</p>",
    "A longer thought that must remain complete",
  );
  expect(
    screen.getByText("A longer thought that must remain complete"),
  ).toBeTruthy();
});
it("retains code that resembles a label while moving real terminal labels to pills", () => {
  const view = preview(
    "Code",
    '<p>Code</p><p><code>#ideas</code></p><p><a class="label" href="/l/ideas">#ideas</a></p>',
    "Code\n\n`#ideas`\n\n#ideas",
    ["ideas"],
  );
  expect(view.container.querySelector("code")?.textContent).toBe("#ideas");
  expect(view.container.querySelectorAll("p").length).toBe(1);
});
it("preserves original checkbox source offsets after the preview title is removed", () => {
  const view = preview(
    "Weekend plans",
    '<h2>Weekend plans</h2><ul><li><input type="checkbox" data-checkbox-offset="20"> Book a train</li></ul>',
    "Weekend plans\n\n- [ ] Book a train",
  );
  expect(
    view.container.querySelector("input")?.getAttribute("data-checkbox-offset"),
  ).toBe("20");
  expect(screen.getByText("Book a train")).toBeTruthy();
});

function card(
  title: string,
  html: string,
  body: string,
  labels: string[] = [],
) {
  const note = Object.freeze({
    id: "synthetic-preview",
    title,
    html,
    body,
    labels,
    created_at: "2026-01-01T12:00:00Z",
    updated_at: "2026-01-01T12:00:00Z",
    version: 1,
    change_seq: 1,
    archived: false,
    trashed: false,
    pinned: false,
    private: false,
    machine: false,
    origin: "owner",
    open_tasks: 0,
    conflict_of: null,
    updated_via_agent: false,
    source: { kind: "web", ref: null, url: null },
    suggestions: [],
    annotations: [],
  } satisfies Note);
  return {
    note,
    ...render(
      <QueryClientProvider client={new QueryClient()}>
        <NoteCard note={note} />
      </QueryClientProvider>,
    ),
  };
}

it("shows a complete safe first block once as the heading", () => {
  const view = card(
    "Morning memo",
    "<h2>Morning memo</h2><p>Body words</p>",
    "## Morning memo\n\nBody words",
  );
  expect(screen.getByRole("heading").textContent).toBe("Morning memo");
  expect(view.container.querySelector(".note-body")?.textContent).toBe(
    "Body words",
  );
});

it("shows a tagged long first paragraph only in the body and retains its open link", async () => {
  const words =
    "A synthetic long paragraph with enough detail to exceed a stored display title. "
      .repeat(3)
      .trim();
  const title = "#work/ideas A synthetic long paragraph";
  const body = `#work/ideas ${words}`;
  const view = card(
    title,
    `<p><a class="label" href="/l/work/ideas">#work/ideas</a> ${words}</p>`,
    body,
    ["work/ideas"],
  );
  expect(screen.queryByRole("heading")).toBeNull();
  expect(view.container.querySelector(".note-body")?.textContent?.trim()).toBe(
    words,
  );
  expect(view.container.querySelectorAll(".note-body a.label")).toHaveLength(0);
  expect(screen.getAllByRole("link", { name: "work/ideas" })).toHaveLength(1);
  const open = view.container.querySelector<HTMLAnchorElement>("a.time")!;
  expect(open.getAttribute("href")).toBe("/n/synthetic-preview");
  expect(open.getAttribute("aria-label")).toContain(title);
  await userEvent.tab();
  expect(document.activeElement).toBe(open);
  await userEvent.keyboard("{Enter}");
  expect(window.location.pathname).toBe("/n/synthetic-preview");
  expect(view.note.body).toBe(body);
});

it.each([
  [
    "Read the guide",
    '<p><a href="https://example.org/guide">Read the guide</a></p>',
  ],
  ["#ideas", "<p><code>#ideas</code></p>"],
  ["Code", "<pre><code>Code\n#ideas</code></pre>"],
  ["A first line", "<p>A first line\ncontinues in the same paragraph</p>"],
])(
  "keeps interactive, code and incomplete blocks in the body: %s",
  (title, html) => {
    const view = card(title, html, title);
    expect(screen.queryByRole("heading")).toBeNull();
    expect(
      view.container.querySelector(".note-body")?.textContent,
    ).toBeTruthy();
  },
);

it("moves canonical inline labels once while preserving code, numeric references and ordinary links", () => {
  const html =
    '<p>Memo <a class="label" href="/l/work/ideas">#work/ideas</a></p><p>Keep <code>#work/ideas</code> and issue #123, #123/child, <a href="/l/work/ideas">named link</a>.</p>';
  const body =
    "Memo #work/ideas\n\nKeep `#work/ideas` and issue #123, #123/child, [named link](/l/work/ideas).";
  const view = card("Memo #work/ideas", html, body, ["work/ideas"]);
  expect(screen.getByRole("heading").textContent).toBe("Memo");
  expect(view.container.querySelector("code")?.textContent).toBe("#work/ideas");
  expect(view.container.querySelector(".note-body")?.textContent).toContain(
    "#123, #123/child",
  );
  expect(
    screen.getByRole("link", { name: "named link" }).getAttribute("href"),
  ).toBe("/l/work/ideas");
  expect(screen.getAllByRole("link", { name: "work/ideas" })).toHaveLength(1);
  expect(view.note.body).toBe(body);
});

it("keeps a labels-only card keyboard reachable beyond its filter chips", async () => {
  const view = card(
    "#work/ideas",
    '<p><a class="label" href="/l/work/ideas">#work/ideas</a></p>',
    "#work/ideas",
    ["work/ideas"],
  );
  expect(screen.queryByRole("heading")).toBeNull();
  expect(view.container.querySelector(".note-body")?.textContent).toBe("");
  const open = view.container.querySelector<HTMLAnchorElement>("a.time")!;
  await userEvent.tab();
  expect(document.activeElement).toBe(open);
  expect(open.getAttribute("href")).toBe("/n/synthetic-preview");
  expect(open.getAttribute("aria-label")).toContain("#work/ideas");
  expect(screen.getByRole("link", { name: "work/ideas" })).not.toBe(open);
});

it("keeps task-first content and original source offsets after removing label anchors", () => {
  const body = "#ideas\n\n- [ ] Book a synthetic train";
  const offset = body.indexOf("[") + 1;
  const toggle = vi.fn();
  const note = Object.freeze({
    title: "#ideas",
    body,
    labels: ["ideas"],
    html: `<p><a class="label" href="/l/ideas">#ideas</a></p><ul><li><input type="checkbox" data-checkbox-offset="${offset}"> Book a synthetic train</li></ul>`,
  } as Note);
  const view = render(
    <NoteBody
      note={note}
      preview
      onCheckbox={toggle}
      renderTitle={(title) => title && <h3>{title}</h3>}
    />,
  );
  const box = view.container.querySelector<HTMLInputElement>("input")!;
  expect(screen.queryByRole("heading")).toBeNull();
  expect(box.dataset.checkboxOffset).toBe(String(offset));
  fireEvent.click(box);
  expect(toggle).toHaveBeenCalledExactlyOnceWith(offset);
  expect(toggleRenderedCheckbox(body, offset)).toBe(body.replace("[ ]", "[x]"));
  expect(note.body).toBe(body);
});

it("preserves full-note labels and sanitization outside card previews", () => {
  const view = render(
    <NoteBody
      note={
        {
          title: "Memo",
          body: "Memo #ideas",
          labels: ["ideas"],
          html: '<p>Memo <a class="label" href="/l/ideas">#ideas</a></p><script>throw Error("synthetic")</script>',
        } as Note
      }
    />,
  );
  expect(screen.getByRole("link", { name: "#ideas" })).toBeTruthy();
  expect(view.container.querySelector("script")).toBeNull();
});

it("removes tag-only wrappers while preserving other parentheses, code, numbers and links", () => {
  const view = card(
    "(#work/ideas)",
    '<p>(<a class="label" href="/l/work/ideas">#work/ideas</a>)</p><p>()</p><p>(ordinary words)</p><p><code>(#work/ideas)</code> (#123) <a href="/l/work/ideas">(#work/ideas)</a></p>',
    "(#work/ideas)\n\n()\n\n(ordinary words)\n\n`(#work/ideas)` (#123) [(#work/ideas)](/l/work/ideas)",
    ["work/ideas"],
  );
  expect(screen.queryByRole("heading")).toBeNull();
  const paragraphs = [...view.container.querySelectorAll(".note-body p")];
  expect(paragraphs.map((paragraph) => paragraph.textContent)).toEqual([
    "()",
    "(ordinary words)",
    "(#work/ideas) (#123) (#work/ideas)",
  ]);
  expect(view.container.querySelectorAll(".note-body a")).toHaveLength(1);
});

it.each(["()", "[]", "{}"])(
  "preserves standalone %s before a canonical tag",
  (punctuation) => {
    const view = card(
      `${punctuation} #ideas`,
      `<p>${punctuation} <a class="label" href="/l/ideas">#ideas</a></p>`,
      `${punctuation} #ideas`,
      ["ideas"],
    );
    expect(screen.getByRole("heading").textContent).toBe(punctuation);
    expect(view.container.querySelector(".note-body a.label")).toBeNull();
    expect(screen.getAllByRole("link", { name: "ideas" })).toHaveLength(1);
  },
);
