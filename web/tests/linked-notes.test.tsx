import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { api } from "../src/lib/api";
import { noteLinkIds, noteSummary } from "../src/lib/text";
import { NoteBody } from "../src/components/core/NoteCard";
import { LinkedNotes } from "../src/features/LinkedNotes";
import type { Note } from "../src/lib/types";

const plugins = vi.hoisted(() => ({
  list: [{ id: "links", state: "enabled" }],
}));
vi.mock("../src/lib/query", async (original) => ({
  ...(await original<typeof import("../src/lib/query")>()),
  useManifest: () => ({ data: { plugins: plugins.list } }),
}));
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  plugins.list = [{ id: "links", state: "enabled" }];
});

const id = (n: number) =>
  `019a0000-0000-7000-8000-${String(n).padStart(12, "0")}`;
const note = (n: number, overrides: Partial<Note> = {}) =>
  ({
    id: id(n),
    title: `Note ${n}`,
    body: `Note ${n}\n\nBody of note ${n}`,
    html: "",
    updated_at: "2026-11-01T12:00:00Z",
    annotations: [],
    ...overrides,
  }) as unknown as Note;
function serve(...available: Note[]) {
  vi.spyOn(api, "note").mockImplementation(async (wanted) => {
    const found = available.find((candidate) => candidate.id === wanted);
    if (!found) throw new Error("missing");
    return found;
  });
}
const show = (subject: Note) =>
  render(
    <QueryClientProvider client={new QueryClient()}>
      <LinkedNotes note={subject} />
    </QueryClientProvider>,
  );
const backlinks = (...ids: string[]) => [
  { id: "a", plugin: "links", kind: "panel", payload: { type: "notes", ids } },
];

it("finds each linked note once and ignores other links", () => {
  expect(
    noteLinkIds(
      `<p><a href="/n/${id(1)}">One</a> <a href="/l/ideas">#ideas</a> ` +
        `<a href="/n/${id(2)}">Two</a> <a href="/n/${id(1)}">again</a> <a href="/n/nope">x</a></p>`,
    ),
  ).toEqual([id(1), id(2)]);
});
it("summarises a note by its title, or by its opening words when it has none", () => {
  expect(
    noteSummary({ title: "Plan", body: "# Plan\n\nSee [the map](/n/x) first" }),
  ).toEqual({ name: "Plan", excerpt: "See the map first" });
  expect(noteSummary({ title: "", body: "- [ ] buy milk" })).toEqual({
    name: "buy milk",
    excerpt: "",
  });
});
it("draws a note link as a chip with the target's current name", () => {
  const html = `<p>See <a href="/n/${id(1)}">Old name</a> and <a href="https://example.org">site</a></p>`;
  const view = render(
    <NoteBody
      note={note(9, { html })}
      links={{ [id(1)]: { name: "New name", excerpt: "What it says" } }}
    />,
  );
  const chip = view.container.querySelector("a.note-link")!;
  expect(chip.textContent).toBe("New name");
  expect(chip.getAttribute("title")).toBe("What it says");
  expect(view.container.querySelectorAll("a.note-link").length).toBe(1);
  view.rerender(<NoteBody preview note={note(9, { html })} />);
  expect(view.container.querySelector("a.note-link")!.textContent).toBe(
    "Old name",
  );
});
it("lists the notes this one links to and the notes that link here", async () => {
  serve(note(1), note(2));
  show(
    note(9, {
      html: `<p><a href="/n/${id(1)}">One</a> <a href="/n/${id(3)}">Gone</a></p>`,
      annotations: backlinks(id(2)),
    } as Partial<Note>),
  );
  const block = await screen.findByRole("region", { name: "Linked notes" });
  const to = await within(block).findByRole("link", { name: /Note 1/ });
  expect(to.getAttribute("href")).toBe("/n/" + id(1));
  expect(to.textContent).toContain("Body of note 1");
  expect(
    (await within(block).findByRole("link", { name: /Note 2/ })).getAttribute(
      "href",
    ),
  ).toBe("/n/" + id(2));
  expect(within(block).getAllByRole("heading", { level: 3 }).length).toBe(2);
  expect(within(block).getAllByRole("link").length).toBe(2);
});
it("shows nothing without links, and no backlinks while the plugin is off", () => {
  plugins.list = [];
  serve(note(2));
  const view = show(
    note(9, { annotations: backlinks(id(2)) } as Partial<Note>),
  );
  expect(view.container.innerHTML).toBe("");
});
it("shows the first five and the rest on request", async () => {
  const targets = [1, 2, 3, 4, 5, 6, 7].map((n) => note(n));
  serve(...targets);
  show(
    note(9, {
      html: targets.map((t) => `<a href="/n/${t.id}">x</a>`).join(" "),
    }),
  );
  await screen.findByRole("link", { name: /Note 5/ });
  expect(screen.queryByRole("link", { name: /Note 6/ })).toBeNull();
  await userEvent.click(screen.getByRole("button", { name: "Show all 7" }));
  expect(await screen.findByRole("link", { name: /Note 7/ })).toBeTruthy();
});
