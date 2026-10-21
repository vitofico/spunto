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
import type { Note, Revision, Session } from "../src/lib/types";

const session: Session = {
  owner_id: "alice",
  user_id: "alice",
  workspace_id: "personal-alice",
  workspace_role: "owner",
  credential_id: "credential-a",
  auth_method: "oidc",
};
let serial = 0;
function fixture(overrides: Partial<Note> = {}) {
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
  } as unknown as Note;
}
function tasks() {
  return fixture({
    body: "- [ ] one\n- [ ] two",
    html:
      '<ul><li><input type="checkbox" data-checkbox-index="0" data-checkbox-offset="3" aria-label="Toggle task"> one</li>' +
      '<li><input type="checkbox" data-checkbox-index="1" data-checkbox-offset="13" aria-label="Toggle task"> two</li></ul>',
  } as Partial<Note>);
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
  vi.spyOn(api, "history").mockResolvedValue([]);
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
    fireEvent.click(
      await screen.findByText("Revision history", { selector: "summary" }),
    );
    await screen.findByText(/Current version/);
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
