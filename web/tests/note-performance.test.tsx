import DOMPurify from "dompurify";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import {
  dayLabel,
  groupedNotes,
  NoteBody,
} from "../src/components/core/NoteCard";
import { api } from "../src/lib/api";
import { queryClient, startSSO, useAuth, useOwner } from "../src/lib/query";
import type { Note } from "../src/lib/types";
import Timeline from "../src/pages/Timeline";

function note(overrides: Partial<Note> = {}): Note {
  return {
    id: "note-1",
    title: "A saved note",
    body: "- [ ] saved",
    html: '<ul><li><input type="checkbox" data-checkbox-offset="3"> saved</li></ul>',
    version: 1,
    created_at: "2026-01-01T12:00:00Z",
    source: {},
    suggestions: [],
    annotations: [],
    ...overrides,
  } as Note;
}

beforeEach(() => {
  startSSO();
  queryClient.clear();
  localStorage.clear();
  window.history.replaceState(null, "", "/");
});
afterEach(() => {
  cleanup();
  queryClient.clear();
  vi.useRealTimers();
});

it("groups nonadjacent notes by local day and keeps first-seen ordering", () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date(2026, 0, 2, 12));
  const date = (day: number, hour: number) =>
    new Date(2026, 0, day, hour).toISOString();
  const notes = [
    note({ id: "yesterday-1", created_at: date(1, 23) }),
    note({ id: "pinned-1", pinned: true, created_at: date(1, 12) }),
    note({ id: "today", created_at: date(2, 0) }),
    note({ id: "yesterday-2", created_at: date(1, 0) }),
    note({ id: "pinned-2", pinned: true, created_at: date(2, 12) }),
    note({
      id: "last-year",
      created_at: new Date(2025, 11, 31, 12).toISOString(),
    }),
  ];
  const groups = groupedNotes(notes);
  expect(groups.map((group) => group.title)).toEqual([
    "Yesterday",
    "Pinned",
    "Today",
    dayLabel(notes[5].created_at),
  ]);
  expect(groups.map((group) => group.notes.map((item) => item.id))).toEqual([
    ["yesterday-1", "yesterday-2"],
    ["pinned-1", "pinned-2"],
    ["today"],
    ["last-year"],
  ]);
  expect(notes.map((item) => item.id)).toEqual([
    "yesterday-1",
    "pinned-1",
    "today",
    "yesterday-2",
    "pinned-2",
    "last-year",
  ]);
});

it("formats a day once for a synthetic 5,000-note timeline", () => {
  const notes = Array.from({ length: 5000 }, (_, index) =>
    note({
      id: String(index),
      created_at: new Date(2025, 0, (index % 30) + 1, 12).toISOString(),
    }),
  );
  const format = vi.spyOn(Date.prototype, "toLocaleDateString");
  const groups = groupedNotes(notes);
  expect(groups).toHaveLength(30);
  expect(groups.flatMap((group) => group.notes)).toHaveLength(5000);
  expect(format).toHaveBeenCalledTimes(30);
});

it("reuses sanitized HTML while task state and edit permissions change", () => {
  const saved = note();
  const sanitize = vi.spyOn(DOMPurify, "sanitize");
  const toggle = vi.fn();
  const { rerender, container } = render(
    <NoteBody note={saved} onCheckbox={toggle} />,
  );
  const checkbox = () => screen.getByRole("checkbox") as HTMLInputElement;
  fireEvent.click(checkbox());
  expect(toggle).toHaveBeenCalledWith(3);
  rerender(<NoteBody note={saved} body="- [x] saved" onCheckbox={toggle} />);
  expect(checkbox().checked).toBe(true);
  rerender(<NoteBody note={saved} body="- [x] saved" />);
  expect(checkbox().disabled).toBe(true);
  expect(sanitize).toHaveBeenCalledOnce();
  rerender(
    <NoteBody
      note={{
        ...saved,
        html: '<p onclick="bad()">Updated<script>bad()</script></p>',
      }}
    />,
  );
  expect(screen.getByText("Updated")).toBeTruthy();
  expect(container.querySelector("script,[onclick]")).toBeNull();
  expect(sanitize).toHaveBeenCalledTimes(2);
});

function Signed({ children }: { children: ReactNode }) {
  useAuth();
  return useOwner() ? children : null;
}
function Wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      <Signed>{children}</Signed>
    </QueryClientProvider>
  );
}

it("keeps saved cards and day groups unchanged while composing, then updates on new data", async () => {
  const session = { owner_id: "synthetic-owner" };
  queryClient.setQueryData(["meta"], { setup_pending: false });
  queryClient.setQueryData(["session"], session);
  queryClient.setQueryData(["manifest"], { plugins: [] });
  queryClient.setQueryData(["labels"], { labels: [] });
  vi.spyOn(api, "meta").mockResolvedValue({ setup_pending: false } as never);
  vi.spyOn(api, "session").mockResolvedValue(session);
  const saved = note();
  const today = note({
    id: "today-note",
    created_at: new Date().toISOString(),
    body: "Today note",
    html: "<p>Today note</p>",
  });
  const key = ["notes", { view: "timeline", limit: 50 }];
  queryClient.setQueryData(key, {
    pages: [{ notes: [saved, today], next_cursor: null }],
    pageParams: [undefined],
  });
  const formatDay = vi.spyOn(Date.prototype, "toLocaleDateString");
  const formatTime = vi.spyOn(Date.prototype, "toLocaleTimeString");
  const sanitize = vi.spyOn(DOMPurify, "sanitize");
  const { rerender } = render(<Timeline focusComposer={false} />, {
    wrapper: Wrapper,
  });
  const editor = await screen.findByRole("textbox", { name: "New note" });
  const before = [
    formatDay.mock.calls.length,
    formatTime.mock.calls.length,
    sanitize.mock.calls.length,
  ];
  expect(before.every((count) => count > 0)).toBe(true);
  for (let index = 1; index <= 20; index++)
    fireEvent.change(editor, { target: { value: "draft ".repeat(index) } });
  expect((editor as HTMLTextAreaElement).value).toBe("draft ".repeat(20));
  expect([
    formatDay.mock.calls.length,
    formatTime.mock.calls.length,
    sanitize.mock.calls.length,
  ]).toEqual(before);
  await act(async () => {
    queryClient.setQueryData(key, {
      pages: [
        {
          notes: [
            { ...saved, version: 2, html: "<p>Changed on the server</p>" },
            today,
          ],
          next_cursor: null,
        },
      ],
      pageParams: [undefined],
    });
  });
  await waitFor(() =>
    expect(screen.getByText("Changed on the server")).toBeTruthy(),
  );
  expect(sanitize.mock.calls.length).toBeGreaterThan(before[2]);
  expect((editor as HTMLTextAreaElement).value).toBe("draft ".repeat(20));
  const nextDay = new Date();
  nextDay.setDate(nextDay.getDate() + 1);
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(nextDay);
  rerender(<Timeline focusComposer={false} />);
  expect(screen.getByRole("heading", { name: "Yesterday" })).toBeTruthy();
});
