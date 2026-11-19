import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
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
import type { ReactNode } from "react";
import { api } from "../src/lib/api";
import { queryClient, startSSO, useAuth, useOwner } from "../src/lib/query";
import { setRequestIdentity } from "../src/lib/identity";
import { ApiError } from "../src/lib/errors";
import { NoteCard } from "../src/components/core/NoteCard";
import { ToastProvider } from "../src/components/core/Toast";
import NotePage from "../src/pages/Note";
import type {
  Manifest,
  Note,
  NoteDetail,
  Revision,
  Session,
  Suggestion,
} from "../src/lib/types";

const session: Session = {
  owner_id: "alice",
  user_id: "alice",
  workspace_id: "personal-alice",
  workspace_role: "owner",
  credential_id: "credential-a",
  auth_method: "oidc",
};
let serial = 0;
function fixture(overrides: Partial<NoteDetail> = {}) {
  serial += 1;
  return {
    id: `00000000-0000-4000-8000-${String(serial).padStart(12, "0")}`,
    title: "Weekly review",
    body: "original",
    version: 1,
    created_at: "2026-10-18T12:00:00Z",
    source: {},
    suggestions: [],
    annotations: [],
    ...overrides,
  } as unknown as NoteDetail;
}
function tasks() {
  return fixture({
    body: "- [ ] one\n- [ ] two",
    html:
      '<ul><li><input type="checkbox" data-checkbox-index="0" data-checkbox-offset="3" aria-label="Toggle task"> one</li>' +
      '<li><input type="checkbox" data-checkbox-index="1" data-checkbox-offset="13" aria-label="Toggle task"> two</li></ul>',
  } as Partial<NoteDetail>);
}
function Signed({ children }: { children: ReactNode }) {
  useAuth();
  return useOwner() ? <>{children}</> : null;
}
function Wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <Signed>{children}</Signed>
      </ToastProvider>
    </QueryClientProvider>
  );
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
beforeEach(() => {
  startSSO();
  localStorage.clear();
  sessionStorage.clear();
  window.history.replaceState(null, "", "/");
  queryClient.clear();
  setRequestIdentity(null);
  HTMLDialogElement.prototype.showModal = function () {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function () {
    this.open = false;
  };
  queryClient.setQueryData(["meta"], { setup_pending: false });
  queryClient.setQueryData(["session"], session);
  vi.spyOn(api, "meta").mockResolvedValue({ setup_pending: false } as Awaited<
    ReturnType<typeof api.meta>
  >);
  vi.spyOn(api, "session").mockResolvedValue(session);
  vi.spyOn(api, "manifest").mockResolvedValue({ plugins: [] } as never);
  vi.spyOn(api, "catalog").mockResolvedValue({ plugins: [] } as never);
  vi.spyOn(api, "history").mockResolvedValue([]);
});

it("offers a saved label Undo in note history after returning to the note", async () => {
  const noteId = fixture().id;
  const appliedAt = "2026-11-19T09:00:00Z";
  const change: Suggestion = {
    id: "recent-label",
    plugin: "label-suggestions",
    note_id: noteId,
    note_version: 2,
    status: "accepted",
    action: "add_label",
    receipt: { note_version: 2, undoable: true },
    payload: { path: "garden" },
    confidence: 1,
    reason: "Matches an existing label.",
    change_seq: 2,
    created_at: appliedAt,
    decided_at: appliedAt,
  };
  let note = fixture({ id: noteId, version: 2, suggestions: [change] });
  vi.spyOn(api, "note").mockImplementation(async () => note);
  const decide = vi
    .spyOn(api, "decideSuggestion")
    .mockImplementation(async () => {
      note = { ...note, version: 3, suggestions: [] };
      return { applied: true, suggestion: change };
    });
  render(<NotePage id={note.id} />, { wrapper: Wrapper });
  expect(await screen.findByText("Label Undo available")).toBeTruthy();
  expect(screen.queryByText("Added #garden")).toBeNull();
  expect(api.history).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText("Label Undo available"));
  const receipts = await screen.findByRole("region", {
    name: "Recent label changes",
  });
  expect(within(receipts).getByText("Added #garden")).toBeTruthy();
  fireEvent.click(within(receipts).getByRole("button", { name: "Undo" }));
  expect(await screen.findByText("Change undone")).toBeTruthy();
  await waitFor(() =>
    expect(screen.queryByText("Label Undo available")).toBeNull(),
  );
  expect(decide).toHaveBeenCalledExactlyOnceWith("recent-label", "undo");
});
afterEach(() => {
  cleanup();
  queryClient.clear();
  setRequestIdentity(null);
  vi.restoreAllMocks();
});

describe("ticking tasks on a card (WW-04)", () => {
  it("shows both ticks at once and sends the second only on the text the first returned", async () => {
    const note = tasks();
    const replies: Array<ReturnType<typeof deferred<Note>>> = [];
    const update = vi.spyOn(api, "updateNote").mockImplementation(() => {
      const reply = deferred<Note>();
      replies.push(reply);
      return reply.promise;
    });
    render(<NoteCard note={note} />, { wrapper: Wrapper });
    const [first, second] = (await screen.findAllByRole("checkbox", {
      name: "Toggle task",
    })) as HTMLInputElement[];
    fireEvent.click(first!);
    fireEvent.click(second!);
    expect(first!.checked).toBe(true);
    expect(second!.checked).toBe(true);
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(update).toHaveBeenLastCalledWith(note.id, "- [x] one\n- [ ] two", 1);
    await act(async () =>
      replies[0]!.resolve({
        ...note,
        body: "- [x] one\n- [ ] two",
        version: 2,
      }),
    );
    await waitFor(() => expect(update).toHaveBeenCalledTimes(2));
    expect(update).toHaveBeenLastCalledWith(note.id, "- [x] one\n- [x] two", 2);
    await act(async () =>
      replies[1]!.resolve({
        ...note,
        body: "- [x] one\n- [x] two",
        version: 3,
      }),
    );
  });
  it("puts a tick back and says why when the server cannot be reached", async () => {
    const note = tasks();
    vi.spyOn(api, "updateNote").mockRejectedValue(new ApiError(0, "network"));
    render(<NoteCard note={note} />, { wrapper: Wrapper });
    const [first] = (await screen.findAllByRole("checkbox", {
      name: "Toggle task",
    })) as HTMLInputElement[];
    fireEvent.click(first!);
    expect(first!.checked).toBe(true);
    expect(
      await screen.findByText(/Can’t reach the server. Nothing changed/),
    ).toBeTruthy();
    expect(first!.checked).toBe(false);
  });
});

const field = () =>
  screen.getByRole("textbox", { name: "Edit note" }) as HTMLTextAreaElement;

describe("the note page", () => {
  async function openEditor(note: Note) {
    vi.spyOn(api, "note").mockResolvedValue(note);
    render(<NotePage id={note.id} />, { wrapper: Wrapper });
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    return field();
  }
  it("claims nothing before an edit and saves in place on Cmd+S (WV-41, WW-12)", async () => {
    const note = fixture();
    vi.spyOn(api, "note").mockResolvedValue(note);
    const update = vi
      .spyOn(api, "updateNote")
      .mockImplementation(
        async (id, body, version) =>
          ({ ...note, id, body, version: version + 1 }) as Note,
      );
    render(<NotePage id={note.id} />, { wrapper: Wrapper });
    await screen.findByRole("button", { name: "Edit" });
    expect(screen.getByRole("link", { name: "All notes" })).toBeTruthy();
    expect(document.querySelector(".saved-line")?.textContent).toBe("");
    fireEvent.click(screen.getByRole("button", { name: "Edit" }));
    fireEvent.change(field(), { target: { value: "original, more" } });
    fireEvent.keyDown(field(), { key: "s", metaKey: true });
    await waitFor(() =>
      expect(update).toHaveBeenCalledWith(note.id, "original, more", 1),
    );
    expect(await screen.findByText("Saved")).toBeTruthy();
    expect(field()).toBeTruthy();
  });
  it.each([
    [0, "Can’t reach the server. Your changes are kept on this device."],
    [403, new ApiError(403, "forbidden").message],
  ])(
    "names the real reason a save failed (status %s)",
    async (status, message) => {
      const note = fixture();
      vi.spyOn(api, "updateNote").mockRejectedValue(
        new ApiError(status, status ? "forbidden" : "network"),
      );
      await openEditor(note);
      fireEvent.change(field(), { target: { value: "changed" } });
      fireEvent.click(screen.getByRole("button", { name: "Done" }));
      expect(await screen.findByText(message)).toBeTruthy();
      expect(field()).toBeTruthy();
    },
  );
  describe("when the note was edited on two devices (WW-08)", () => {
    async function conflicted() {
      const note = fixture();
      const latest = { ...note, body: "theirs", version: 2 };
      const update = vi
        .spyOn(api, "updateNote")
        .mockRejectedValueOnce(
          new ApiError(409, "version_conflict", "conflict-copy"),
        )
        .mockImplementation(
          async (id, body, version) =>
            ({ ...latest, id, body, version: version + 1 }) as Note,
        );
      const flags = vi
        .spyOn(api, "flags")
        .mockResolvedValue({ ...note, trashed: true } as Note);
      await openEditor(note);
      vi.mocked(api.note).mockResolvedValue(latest);
      fireEvent.change(field(), { target: { value: "mine" } });
      fireEvent.click(screen.getByRole("button", { name: "Done" }));
      const notice = await screen.findByRole("region", {
        name: "Edited on two devices",
      });
      fireEvent.click(within(notice).getByRole("button", { name: "Compare" }));
      await within(notice).findByText("theirs", { selector: "del" });
      expect(
        within(notice).getByText("mine", { selector: "ins" }),
      ).toBeTruthy();
      return { note, notice, update, flags };
    }
    it("keeps mine by saving it over the version just compared", async () => {
      const { note, notice, update, flags } = await conflicted();
      fireEvent.click(
        within(notice).getByRole("button", { name: "Keep mine" }),
      );
      await waitFor(() =>
        expect(update).toHaveBeenLastCalledWith(note.id, "mine", 2),
      );
      await waitFor(() =>
        expect(flags).toHaveBeenCalledWith("conflict-copy", { trashed: true }),
      );
    });
    it("keeps the saved text by dropping mine and its copy", async () => {
      const { notice, update, flags } = await conflicted();
      fireEvent.click(
        within(notice).getByRole("button", { name: "Keep saved" }),
      );
      await waitFor(() =>
        expect(flags).toHaveBeenCalledWith("conflict-copy", { trashed: true }),
      );
      expect(update).toHaveBeenCalledTimes(1);
      await waitFor(() =>
        expect(screen.queryByRole("textbox", { name: "Edit note" })).toBeNull(),
      );
      expect(
        screen.queryByRole("region", { name: "Edited on two devices" }),
      ).toBeNull();
    });
  });
  it("keeps a trashed note read-only, with Restore and a confirmed Delete forever (WW-31)", async () => {
    const note = fixture({ trashed: true });
    vi.spyOn(api, "note").mockResolvedValue(note);
    const remove = vi.spyOn(api, "deleteNote").mockResolvedValue(undefined);
    render(<NotePage id={note.id} />, { wrapper: Wrapper });
    expect(await screen.findByText(/In trash/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Edit" })).toBeNull();
    fireEvent.click(screen.getByText("original"));
    expect(screen.queryByRole("textbox")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "More actions" }));
    const menu = screen.getByRole("menu");
    expect(
      within(menu)
        .getAllByRole("menuitem")
        .map((item) => item.textContent),
    ).toEqual(["Restore", "Delete forever"]);
    fireEvent.click(
      within(menu).getByRole("menuitem", { name: "Delete forever" }),
    );
    expect(remove).not.toHaveBeenCalled();
    const dialog = await screen.findByRole("dialog");
    fireEvent.click(
      within(dialog).getByRole("button", { name: "Delete forever" }),
    );
    await waitFor(() => expect(remove).toHaveBeenCalledWith(note.id));
  });
  it("offers Restore only on older versions and shows what each changed (WW-30)", async () => {
    const note = fixture({ body: "new", version: 2 });
    vi.spyOn(api, "note").mockResolvedValue(note);
    vi.mocked(api.history).mockResolvedValue([
      { version: 2, body: "new", changed_at: "2026-10-19T12:00:00Z" },
      { version: 1, body: "old", changed_at: "2026-10-18T12:00:00Z" },
    ] as Revision[]);
    render(<NotePage id={note.id} />, { wrapper: Wrapper });
    await screen.findByText("new");
    expect(api.history).not.toHaveBeenCalled();
    fireEvent.click(
      await screen.findByText("Revision history", { selector: "summary" }),
    );
    await screen.findByText(/Current version/);
    expect(api.history).toHaveBeenCalledExactlyOnceWith(note.id);
    expect(screen.getAllByRole("button", { name: "Restore" })).toHaveLength(1);
    const older = screen
      .getByText(/Version 1/)
      .closest(".history-item") as HTMLElement;
    expect(within(older).getByRole("button", { name: "Restore" })).toBeTruthy();
    const current = screen
      .getByText(/Current version/)
      .closest(".history-item") as HTMLElement;
    expect(within(current).getByText("old", { selector: "del" })).toBeTruthy();
    expect(within(current).getByText("new", { selector: "ins" })).toBeTruthy();
  });
});

it("reaches the missing-model explanation from Note tools when the only tool is waiting", async () => {
  const note = fixture();
  vi.spyOn(api, "note").mockResolvedValue(note);
  vi.mocked(api.catalog).mockResolvedValue({
    plugins_rev: 1,
    plugins: [
      {
        id: "tidy",
        name: "Tidy text",
        version: "1.0.0",
        description: "",
        category: "organize",
        kind: "server",
        permissions: ["ai.chat"],
        state: "waiting",
        position: 0,
        contributes: {
          commands: [
            { id: "tidy", title: "Tidy text", where: ["note-actions"] },
          ],
        },
      },
    ],
  });
  render(<NotePage id={note.id} />, { wrapper: Wrapper });
  await waitFor(() =>
    expect(queryClient.getQueryData(["catalog"])).toBeDefined(),
  );
  fireEvent.click(await screen.findByRole("button", { name: "Note tools" }));
  const tools = screen.getByRole("dialog", { name: "Note tools" });
  expect(within(tools).getByText(/Tidy text/)).toBeTruthy();
  expect(
    within(tools)
      .getByRole("link", { name: "AI settings" })
      .getAttribute("href"),
  ).toBe("/settings/ai");
  expect(within(tools).queryByRole("button", { name: "Tidy text" })).toBeNull();
});

const adoptionManifest: Manifest = {
  ui: 1,
  plugins_rev: 1,
  features: ["note_adoption", "agent_review_versioned"],
  plugins: [],
};

it("accepts a private machine-written note without editing and focuses the receipt", async () => {
  let note = fixture({
    machine: true,
    private: true,
    origin: "plugin",
    source: { kind: "plugin", ref: "ask" },
    version: 7,
  });
  vi.mocked(api.manifest).mockResolvedValue(adoptionManifest);
  vi.spyOn(api, "note").mockImplementation(async () => note);
  const adoption = vi.spyOn(api, "adoptNote").mockImplementation(async () => {
    note = { ...note, machine: false, version: 8 };
    return note;
  });
  const edit = vi.spyOn(api, "updateNote");
  render(<NotePage id={note.id} />, { wrapper: Wrapper });
  const button = await screen.findByRole("button", { name: "Accept note" });
  expect(
    screen.getByText("Keep the text as written. The note stays private."),
  ).toBeTruthy();
  button.focus();
  fireEvent.click(button);
  const receipt = await screen.findByText("Note accepted");
  expect(document.activeElement).toBe(receipt);
  expect(adoption).toHaveBeenCalledWith(note.id, 7);
  expect(edit).not.toHaveBeenCalled();
  expect(screen.getByText("original")).toBeTruthy();
  expect(screen.queryByRole("button", { name: "Accept note" })).toBeNull();
});

it("lets acceptance and adoption run independently and removes older receipts on a new edit", async () => {
  let note = fixture({
    machine: true,
    origin: "owner",
    source: { kind: "web" },
    updated_via_agent: true,
    version: 4,
    agent_review: {
      kind: "edited",
      before: { version: 3, body: "prior premise" },
    },
  });
  vi.mocked(api.manifest).mockResolvedValue({
    ...adoptionManifest,
    plugins: [
      {
        id: "agent-inbox",
        name: "From agents",
        version: "1.0.0",
        description: "",
        category: "agents",
        kind: "server",
        permissions: ["notes.read", "annotate"],
        state: "enabled",
        position: 0,
        contributes: {
          commands: [
            {
              id: "mark-reviewed",
              title: "Accept as is",
              where: ["note-actions"],
            },
          ],
        },
      },
    ],
  });
  vi.spyOn(api, "note").mockImplementation(async () => note);
  const adopt = vi.spyOn(api, "adoptNote").mockImplementation(async () => {
    note = { ...note, machine: false, version: 5 };
    return note;
  });
  const review = vi.spyOn(api, "command").mockImplementation(async () => {
    note = {
      ...note,
      annotations: [
        {
          id: "reviewed",
          plugin: "agent-inbox",
          kind: "member:reviewed",
          payload: [],
        },
      ],
    };
    return { type: "text", text: "Note accepted" };
  });
  render(<NotePage id={note.id} />, { wrapper: Wrapper });
  expect(
    await screen.findByRole("button", { name: "Accept agent changes" }),
  ).toBeTruthy();
  const comparison = screen.getByRole("region", { name: "Agent changes" });
  expect(
    within(comparison).getByText("prior premise", { selector: "del" }),
  ).toBeTruthy();
  expect(
    within(comparison).getByText("original", { selector: "ins" }),
  ).toBeTruthy();
  expect(api.history).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "Use as my note" }));
  await screen.findByText("Note accepted");
  expect(adopt).toHaveBeenCalledWith(note.id, 4);
  expect(review).not.toHaveBeenCalled();
  const changes = screen.getByRole<HTMLButtonElement>("button", {
    name: "Accept changes",
  });
  await waitFor(() => expect(changes.disabled).toBe(false));
  fireEvent.click(changes);
  await screen.findByText("Changes accepted");
  await waitFor(() =>
    expect(screen.queryByRole("button", { name: "Accept changes" })).toBeNull(),
  );
  expect(review).toHaveBeenCalledWith("agent-inbox", "mark-reviewed", {
    note_id: note.id,
    base_version: 5,
  });
  expect(screen.getByText("Edited by an agent")).toBeTruthy();

  note = {
    ...note,
    annotations: [],
    version: 6,
    body: "New agent edit",
    agent_review: { kind: "edited", before: null },
  };
  await act(
    async () =>
      void (await queryClient.invalidateQueries({
        queryKey: ["note", note.id],
      })),
  );
  expect(
    await screen.findByRole("button", { name: "Accept changes" }),
  ).toBeTruthy();
  expect(screen.queryByText("Changes accepted")).toBeNull();
  expect(
    screen.getByText(/version before the agent’s edit is unavailable/),
  ).toBeTruthy();
  expect(screen.queryByRole("region", { name: "Agent changes" })).toBeNull();
  expect(screen.getByText("New agent edit")).toBeTruthy();
});

it("does not offer adoption in a read-only workspace", async () => {
  const viewer = { ...session, workspace_role: "viewer" as const };
  queryClient.setQueryData(["session"], viewer);
  vi.mocked(api.session).mockResolvedValue(viewer);
  vi.mocked(api.manifest).mockResolvedValue(adoptionManifest);
  const note = fixture({ machine: true });
  vi.spyOn(api, "note").mockResolvedValue(note);
  render(<NotePage id={note.id} />, { wrapper: Wrapper });
  expect(await screen.findByText("original")).toBeTruthy();
  expect(screen.queryByRole("button", { name: "Accept note" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Edit" })).toBeNull();
});

it("asks for review again if the displayed revision became stale", async () => {
  const note = fixture({ machine: true });
  const latest = deferred<Note>();
  vi.mocked(api.manifest).mockResolvedValue(adoptionManifest);
  vi.spyOn(api, "note")
    .mockResolvedValueOnce(note)
    .mockReturnValue(latest.promise);
  const adopt = vi
    .spyOn(api, "adoptNote")
    .mockRejectedValue(new ApiError(409, "version_conflict"));
  render(<NotePage id={note.id} />, { wrapper: Wrapper });
  fireEvent.click(await screen.findByRole("button", { name: "Accept note" }));
  expect(
    await screen.findByText(
      "The note changed. Read the latest version before accepting.",
    ),
  ).toBeTruthy();
  expect(screen.queryByText("Note accepted")).toBeNull();
  await act(async () =>
    latest.resolve({ ...note, version: 2, body: "Updated text" }),
  );
  expect(await screen.findByText("Updated text")).toBeTruthy();
  expect(screen.queryByRole("alert")).toBeNull();
  expect(adopt).toHaveBeenCalledTimes(1);
});
