import { afterEach, beforeEach, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { api, request } from "../src/lib/api";
import {
  currentOwner,
  currentSession,
  queryClient,
  startSSO,
  switchWorkspace,
  useAuth,
  useOwner,
} from "../src/lib/query";
import {
  sessionIdentity,
  setRequestIdentity,
  workspaceHeaders,
} from "../src/lib/identity";
import { useDraftEditor } from "../src/lib/editor";
import {
  carryRotatedDrafts,
  draftStorageKey,
  eraseOwnerDrafts,
} from "../src/lib/drafts";
import { ShareNote, SpacesPanel } from "../src/features/Collaboration";
import SharedNotesPage from "../src/pages/SharedNotes";
import NotePage from "../src/pages/Note";
import { Palette } from "../src/components/core/Palette";
import { NoteCard } from "../src/components/core/NoteCard";
import type { Note, Session, SharedNote } from "../src/lib/types";
const ownerSession: Session = {
  owner_id: "alice",
  user_id: "alice",
  workspace_id: "personal-alice",
  workspace_role: "owner",
  credential_id: "credential-a",
  auth_method: "oidc",
};
const note = {
  id: "00000000-0000-4000-8000-000000000001",
  title: "Shared fixture",
  body: "original",
  version: 1,
  created_at: "2026-10-18T12:00:00Z",
  source: {},
  suggestions: [],
  annotations: [],
} as unknown as Note;
function Wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}
function IdentityHarness({ children }: { children?: ReactNode }) {
  useAuth();
  const owner = useOwner();
  return owner ? <div key={owner}>{children}</div> : null;
}
function seed(session = ownerSession) {
  queryClient.setQueryData(["meta"], { setup_pending: false });
  queryClient.setQueryData(["session"], session);
  vi.spyOn(api, "meta").mockResolvedValue({ setup_pending: false } as Awaited<
    ReturnType<typeof api.meta>
  >);
  vi.spyOn(api, "session").mockImplementation(async () => {
    const workspace =
      workspaceHeaders().get("X-Spunto-Workspace") ?? session.workspace_id;
    return {
      ...session,
      workspace_id: workspace,
      workspace_role: workspace === "team" ? "viewer" : session.workspace_role,
    };
  });
  vi.spyOn(api, "workspaceSession").mockImplementation(async (id) => ({
    ...session,
    workspace_id: id,
    workspace_role: "viewer",
  }));
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
});
afterEach(() => {
  cleanup();
  queryClient.clear();
  setRequestIdentity(null);
  vi.restoreAllMocks();
});
it("adds the current workspace to both generated and handwritten requests", async () => {
  setRequestIdentity(ownerSession);
  const calls: Headers[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    calls.push(
      input instanceof Request ? input.headers : new Headers(init?.headers),
    );
    return new Response(JSON.stringify(note));
  });
  await api.note(note.id);
  await request("/api/v1/shared-notes");
  expect(calls.map((headers) => headers.get("X-Spunto-Workspace"))).toEqual([
    "personal-alice",
    "personal-alice",
  ]);
  expect(
    calls.map((headers) => [
      headers.get("X-Spunto-Account"),
      headers.get("X-Spunto-Credential"),
    ]),
  ).toEqual([
    ["alice", "credential-a"],
    ["alice", "credential-a"],
  ]);
});
it("asserts the account on workspace-free private APIs and active session checks, and omits it for public auth or unbound hydration", async () => {
  setRequestIdentity(ownerSession);
  const calls: Headers[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) => {
    calls.push(new Headers(init?.headers));
    return new Response(JSON.stringify({}));
  });
  await api.accountSecurity();
  await api.session();
  await api.authOptions();
  await api.login("synthetic password", "alice");
  await api.register("alice", "synthetic password");
  setRequestIdentity(null);
  await api.workspaceSession("shared-space");
  expect(
    calls.map((headers) => [
      headers.get("X-Spunto-Account"),
      headers.get("X-Spunto-Credential"),
    ]),
  ).toEqual([
    ["alice", "credential-a"],
    ["alice", "credential-a"],
    [null, null],
    [null, null],
    [null, null],
    [null, null],
  ]);
  expect(calls[0].get("X-Spunto-Workspace")).toBeNull();
  expect(calls[5].get("X-Spunto-Workspace")).toBe("shared-space");
});
it("locks an unknown cookie actor in a shared space, clears private queries and preserves both accounts' drafts", async () => {
  startSSO();
  const bob: Session = {
    ...ownerSession,
    owner_id: "bob",
    user_id: "bob",
    workspace_id: "team",
    credential_id: "bob-cookie",
  };
  seed(bob);
  const { result } = renderHook(
    () => {
      useOwner();
      return useAuth();
    },
    { wrapper: Wrapper },
  );
  await waitFor(() => expect(currentSession()).toEqual(bob));
  const aliceDraft = draftStorageKey(
    sessionIdentity({ ...ownerSession, workspace_id: "team" }),
    "compose",
  );
  const bobDraft = draftStorageKey(sessionIdentity(bob), "compose");
  localStorage.setItem(aliceDraft, "alice pending work");
  localStorage.setItem(bobDraft, "bob pending work");
  queryClient.setQueryData(["notes"], { private: "bob data" });
  let asserted: Headers | undefined;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) => {
    asserted = new Headers(init?.headers);
    return new Response(JSON.stringify({ code: "identity_changed" }), {
      status: 409,
    });
  });
  await act(async () => {
    await api
      .saveAccountPassword({
        new_password: "synthetic passphrase",
        current_password: "synthetic previous",
      })
      .catch((error) =>
        expect(error).toMatchObject({ code: "identity_changed" }),
      );
  });
  expect(asserted?.get("X-Spunto-Account")).toBe("bob");
  expect(asserted?.get("X-Spunto-Credential")).toBe("bob-cookie");
  expect(currentSession()).toBeNull();
  expect(queryClient.getQueryData(["notes"])).toBeUndefined();
  expect(localStorage.getItem(aliceDraft)).toBe("alice pending work");
  expect(localStorage.getItem(bobDraft)).toBe("bob pending work");
  expect(localStorage.getItem("spunto:signed-out")).toBe("identity-changed");
  await waitFor(() => expect(result.current.status).toBe("anonymous"));
  startSSO();
});
it("preserves a mounted editor's pending draft when another tab publishes an identity fence", async () => {
  seed();
  const { result } = renderHook(
    () => {
      const auth = useAuth();
      const editor = useDraftEditor({ draftKey: "compose" });
      return { auth, editor };
    },
    { wrapper: Wrapper },
  );
  await waitFor(() => expect(currentSession()).toEqual(ownerSession));
  act(() => result.current.editor.setBody("pending private capture"));
  const key = draftStorageKey(sessionIdentity(ownerSession), "compose");
  await act(async () => {
    localStorage.setItem("spunto:signed-out", "identity-changed");
    window.dispatchEvent(
      new StorageEvent("storage", {
        key: "spunto:signed-out",
        newValue: "identity-changed",
      }),
    );
  });
  await waitFor(() => expect(result.current.auth.status).toBe("anonymous"));
  expect(currentSession()).toBeNull();
  expect(localStorage.getItem(key)).toContain("pending private capture");
  expect(localStorage.getItem("spunto:signed-out")).toBe("identity-changed");
  startSSO();
});
it.each(["raw", "generated"])(
  "rejects a late %s response after workspace switch, including a stale 401",
  async (kind) => {
    setRequestIdentity(ownerSession);
    let complete!: (response: Response) => void;
    vi.spyOn(globalThis, "fetch").mockImplementation(
      () =>
        new Promise((resolve) => {
          complete = resolve;
        }),
    );
    const pending = (
      kind === "raw" ? request("/api/v1/shared-notes") : api.note(note.id)
    ).catch((error: unknown) => error);
    setRequestIdentity({ ...ownerSession, workspace_id: "shared-team" });
    queryClient.setQueryData(["session"], ownerSession);
    complete(
      new Response(JSON.stringify({ code: "unauthenticated" }), {
        status: 401,
      }),
    );
    expect(await pending).toMatchObject({ code: "identity_changed" });
    expect(queryClient.getQueryData(["session"])).toEqual(ownerSession);
  },
);
it("fences drafts by account, workspace and credential, clears cached data, and settles an old save acknowledgement only in storage", async () => {
  seed();
  let complete!: (value: Note) => void;
  vi.spyOn(api, "updateNote").mockImplementation(
    () =>
      new Promise((resolve) => {
        complete = resolve;
      }),
  );
  const { result } = renderHook(
    () => {
      useAuth();
      return useDraftEditor({ note, draftKey: "note:" + note.id });
    },
    { wrapper: Wrapper },
  );
  await waitFor(() =>
    expect(currentOwner()).toBe(sessionIdentity(ownerSession)),
  );
  act(() => result.current.setBody("private draft"));
  let saving!: Promise<Note | undefined>;
  act(() => {
    saving = result.current.save();
  });
  queryClient.setQueryData(["notes"], { secret: true });
  await act(async () => {
    await switchWorkspace({
      id: "team",
      name: "Team",
      personal: false,
      role: "viewer",
    });
  });
  expect(queryClient.getQueryData(["notes"])).toBeUndefined();
  expect(currentOwner()).toBe(
    sessionIdentity({ ...ownerSession, workspace_id: "team" }),
  );
  expect(result.current.body).toBe("original");
  await act(async () => {
    complete({ ...note, body: "private draft", version: 2 });
    await saving;
  });
  expect(result.current.body).toBe("original");
  // The server holds exactly the text sent, so the old space keeps no stale draft to replay.
  expect(
    localStorage.getItem(
      draftStorageKey(sessionIdentity(ownerSession), "note:" + note.id),
    ),
  ).toBeNull();
  expect(
    sessionIdentity({ ...ownerSession, credential_id: "credential-b" }),
  ).not.toBe(sessionIdentity(ownerSession));
  expect(sessionIdentity({ ...ownerSession, user_id: "bob" })).not.toBe(
    sessionIdentity(ownerSession),
  );
});
it("keeps automatic session loading paused while a rotated cookie refresh validates the selected space", async () => {
  startSSO();
  const selected = { ...ownerSession, workspace_id: "selected-space" };
  seed(selected);
  const { result } = renderHook(
    () => {
      useOwner();
      return useAuth();
    },
    { wrapper: Wrapper },
  );
  await waitFor(() => expect(currentSession()).toEqual(selected));
  const rotated: Session = {
    ...selected,
    credential_id: "rotated-cookie",
    auth_method: "password",
  };
  vi.mocked(api.session).mockImplementation(async () => ({
    ...rotated,
    workspace_id:
      workspaceHeaders().get("X-Spunto-Workspace") ?? "personal-alice",
  }));
  vi.mocked(api.session).mockClear();
  let complete!: (value: Session) => void;
  vi.mocked(api.workspaceSession).mockImplementation(
    () =>
      new Promise((resolve) => {
        complete = resolve;
      }),
  );
  let pending!: Promise<void>;
  await act(async () => {
    pending = result.current.refresh();
  });
  await waitFor(() =>
    expect(api.workspaceSession).toHaveBeenCalledWith("selected-space"),
  );
  await act(async () => {
    await queryClient.refetchQueries({ queryKey: ["meta"] });
  });
  expect(result.current.status).toBe("loading");
  expect(currentSession()).toBeNull();
  expect(api.session).not.toHaveBeenCalled();
  await act(async () => {
    complete(rotated);
    await pending;
  });
  await waitFor(() => expect(currentSession()).toEqual(rotated));
  expect(result.current.status).toBe("authenticated");
});
it("restores mounted pending capture text and the exact idempotency payload after verified cookie rotation", async () => {
  seed();
  let acknowledge!: (value: Note) => void;
  vi.spyOn(api, "createNote").mockImplementation(
    () =>
      new Promise((resolve) => {
        acknowledge = resolve;
      }),
  );
  const { result } = renderHook(
    () => ({
      auth: useAuth(),
      editor: useDraftEditor({ draftKey: "compose" }),
    }),
    { wrapper: Wrapper },
  );
  await waitFor(() => expect(currentSession()).toEqual(ownerSession));
  act(() => result.current.editor.setBody("submitted capture"));
  let saving!: Promise<Note | undefined>;
  act(() => {
    saving = result.current.editor.save();
  });
  act(() => result.current.editor.setBody("typing after submission"));
  const beforeKey = draftStorageKey(sessionIdentity(ownerSession), "compose");
  const before = localStorage.getItem(beforeKey);
  expect(JSON.parse(before!).outbox[0].body).toBe("submitted capture");
  const rotated: Session = {
    ...ownerSession,
    credential_id: "rotated-cookie",
    auth_method: "password",
  };
  vi.mocked(api.workspaceSession).mockResolvedValue(rotated);
  vi.mocked(api.session).mockResolvedValue(rotated);
  await act(async () => {
    await result.current.auth.refresh();
  });
  const afterKey = draftStorageKey(sessionIdentity(rotated), "compose");
  expect(localStorage.getItem(afterKey)).toBe(before);
  expect(
    sessionStorage.getItem(
      "spunto:workspace:" +
        JSON.stringify([rotated.user_id, rotated.credential_id]),
    ),
  ).toBe(rotated.workspace_id);
  expect(result.current.editor.body).toBe("typing after submission");
  expect(result.current.editor.submitted).toBe(true);
  await act(async () => {
    acknowledge({ ...note, body: "submitted capture" });
    await saving;
  });
  expect(localStorage.getItem(afterKey)).toBe(before);
  expect(result.current.editor.body).toBe("typing after submission");
});
it("never overwrites a distinct rotation draft or carries drafts into another account or space", () => {
  const before = sessionIdentity(ownerSession);
  const rotated = sessionIdentity({
    ...ownerSession,
    credential_id: "rotated",
  });
  const oldKey = draftStorageKey(before, "compose");
  const newKey = draftStorageKey(rotated, "compose");
  localStorage.setItem(oldKey, "original pending work");
  localStorage.setItem(newKey, "different pending work");
  expect(() => carryRotatedDrafts(localStorage, before, rotated)).toThrow();
  expect(localStorage.getItem(oldKey)).toBe("original pending work");
  expect(localStorage.getItem(newKey)).toBe("different pending work");
  for (const target of [
    sessionIdentity({ ...ownerSession, user_id: "bob" }),
    sessionIdentity({ ...ownerSession, workspace_id: "other-space" }),
  ]) {
    expect(() => carryRotatedDrafts(localStorage, before, target)).toThrow();
    expect(localStorage.getItem(draftStorageKey(target, "compose"))).toBeNull();
  }
});
it("keeps a failed draft handoff fenced even if the rotated session is forcibly refetched", async () => {
  seed();
  const { result } = renderHook(
    () => ({
      auth: useAuth(),
      editor: useDraftEditor({ draftKey: "compose" }),
    }),
    { wrapper: Wrapper },
  );
  await waitFor(() => expect(currentSession()).toEqual(ownerSession));
  act(() => result.current.editor.setBody("earlier pending capture"));
  const oldKey = draftStorageKey(sessionIdentity(ownerSession), "compose");
  const original = localStorage.getItem(oldKey);
  const rotated: Session = {
    ...ownerSession,
    credential_id: "rotated-cookie",
    auth_method: "password",
  };
  const newKey = draftStorageKey(sessionIdentity(rotated), "compose");
  const distinct = JSON.stringify({
    body: "different existing draft",
    revision: 1,
  });
  localStorage.setItem(newKey, distinct);
  vi.mocked(api.workspaceSession).mockResolvedValue(rotated);
  vi.mocked(api.session).mockResolvedValue(rotated);
  await act(async () => {
    await expect(result.current.auth.refresh()).rejects.toMatchObject({
      code: "draft_recovery_required",
    });
  });
  expect(localStorage.getItem("spunto:signed-out")).toBe("identity-changed");
  await act(async () => {
    await queryClient.fetchQuery({
      queryKey: ["session"],
      queryFn: api.session,
      staleTime: 0,
    });
    window.dispatchEvent(new Event("focus"));
  });
  await waitFor(() => expect(result.current.auth.status).toBe("anonymous"));
  expect(result.current.auth.error).toMatchObject({
    code: "draft_recovery_required",
  });
  expect(currentSession()).toBeNull();
  expect(localStorage.getItem(oldKey)).toBe(original);
  expect(localStorage.getItem(newKey)).toBe(distinct);
  startSSO();
});
it.each(["storage", "logout"])(
  "does not undo %s sign-out while a cookie refresh is delayed",
  async (kind) => {
    startSSO();
    seed();
    const { result } = renderHook(
      () => {
        useOwner();
        return useAuth();
      },
      { wrapper: Wrapper },
    );
    await waitFor(() => expect(currentSession()).toEqual(ownerSession));
    let complete!: (value: Session) => void;
    vi.mocked(api.workspaceSession).mockImplementation(
      () =>
        new Promise((resolve) => {
          complete = resolve;
        }),
    );
    vi.spyOn(api, "logout").mockResolvedValue(undefined);
    let pending!: Promise<unknown>;
    await act(async () => {
      pending = result.current.refresh().catch((error) => error);
    });
    await waitFor(() => expect(api.workspaceSession).toHaveBeenCalledOnce());
    expect(currentOwner()).toBeNull();
    await act(async () => {
      if (kind === "storage")
        window.dispatchEvent(
          new StorageEvent("storage", {
            key: "spunto:signed-out",
            newValue: "1",
          }),
        );
      else await result.current.logout();
    });
    let outcome: unknown;
    await act(async () => {
      complete({ ...ownerSession, credential_id: "late-cookie" });
      outcome = await pending;
    });
    expect(outcome).toMatchObject({ code: "identity_changed" });
    expect(currentOwner()).toBeNull();
    expect(currentSession()).toBeNull();
    expect(queryClient.getQueryData(["session"])).toBeNull();
    await waitFor(() => expect(result.current.status).toBe("anonymous"));
    startSSO();
  },
);
it("adds and revokes a note grant by exact email, without an account directory", async () => {
  seed();
  vi.spyOn(api, "noteShares").mockResolvedValue({
    shares: [
      {
        user_id: "bob",
        email: "bob@example.test",
        display_name: "Bob",
        role: "viewer",
      },
    ],
  });
  const add = vi.spyOn(api, "shareNote").mockResolvedValue(undefined);
  const remove = vi.spyOn(api, "unshareNote").mockResolvedValue(undefined);
  render(
    <IdentityHarness>
      <ShareNote id={note.id} />
    </IdentityHarness>,
    { wrapper: Wrapper },
  );
  fireEvent.click(await screen.findByRole("button", { name: "Share" }));
  fireEvent.change(screen.getByLabelText("Username or verified email"), {
    target: { value: "bob@example.test" },
  });
  fireEvent.change(screen.getByLabelText("Access"), {
    target: { value: "editor" },
  });
  await waitFor(() =>
    expect(
      screen
        .getByRole("button", { name: "Give access" })
        .hasAttribute("disabled"),
    ).toBe(false),
  );
  fireEvent.click(screen.getByRole("button", { name: "Give access" }));
  await waitFor(() =>
    expect(add).toHaveBeenCalledWith(note.id, "bob@example.test", "editor"),
  );
  fireEvent.click(
    screen.getByRole("button", { name: "Remove access for bob@example.test" }),
  );
  await waitFor(() => expect(remove).toHaveBeenCalledWith(note.id, "bob"));
});
it("gives viewers read-only note surfaces and no member-management controls", async () => {
  seed({ ...ownerSession, workspace_id: "team", workspace_role: "viewer" });
  vi.spyOn(api, "note").mockResolvedValue(note);
  vi.spyOn(api, "workspaces").mockResolvedValue({
    workspaces: [{ id: "team", name: "Team", personal: false, role: "viewer" }],
    active_workspace_id: "team",
  });
  vi.spyOn(api, "workspaceMembers").mockResolvedValue({
    members: [
      {
        user_id: "alice",
        email: "alice@example.test",
        display_name: "Alice",
        role: "owner",
      },
    ],
  });
  render(
    <IdentityHarness>
      <NotePage id={note.id} />
      <NoteCard note={note} />
      <ShareNote id={note.id} />
      <SpacesPanel />
    </IdentityHarness>,
    { wrapper: Wrapper },
  );
  await screen.findByText("Can view");
  expect(screen.queryByRole("button", { name: "Edit" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Share" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Pin" })).toBeNull();
  expect(screen.queryByLabelText("Username or verified email")).toBeNull();
});
it.each(["note", "workspace"])(
  "grants %s access to an existing local username without requiring an email",
  async (kind) => {
    seed({ ...ownerSession, workspace_id: "team" });
    vi.spyOn(api, "workspaces").mockResolvedValue({
      workspaces: [
        { id: "team", name: "Team", personal: false, role: "owner" },
      ],
      active_workspace_id: "team",
    });
    vi.spyOn(api, "workspaceMembers").mockResolvedValue({ members: [] });
    vi.spyOn(api, "noteShares").mockResolvedValue({
      shares: [
        {
          user_id: "local-account",
          username: "bob.local",
          email: null,
          display_name: "",
          role: "viewer",
        },
      ],
    });
    const add = vi
      .spyOn(api, kind === "note" ? "shareNote" : "addWorkspaceMember")
      .mockResolvedValue(undefined);
    render(
      <IdentityHarness>
        {kind === "note" ? <ShareNote id={note.id} /> : <SpacesPanel />}
      </IdentityHarness>,
      { wrapper: Wrapper },
    );
    if (kind === "note") {
      fireEvent.click(await screen.findByRole("button", { name: "Share" }));
      expect(
        await screen.findByRole("button", {
          name: "Remove access for bob.local",
        }),
      ).toBeTruthy();
    }
    fireEvent.change(
      await screen.findByLabelText("Username or verified email"),
      {
        target: { value: "bob.local" },
      },
    );
    fireEvent.click(screen.getByRole("button", { name: "Give access" }));
    await waitFor(() =>
      expect(add).toHaveBeenCalledWith(
        kind === "note" ? note.id : "team",
        "bob.local",
        "viewer",
      ),
    );
  },
);
it.each(["note", "workspace"])(
  "sends a single exact username or verified email selector for %s grants",
  async (kind) => {
    const bodies: unknown[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) => {
      bodies.push(JSON.parse(String(init?.body)));
      return new Response(null, { status: 204 });
    });
    const grant = kind === "note" ? api.shareNote : api.addWorkspaceMember;
    await grant("source", "  Bob.Local  ", "editor");
    await grant("source", "bob@example.test", "viewer");
    await grant("source", "Kelvin", "viewer");
    expect(bodies).toEqual([
      { username: "Bob.Local", role: "editor" },
      { email: "bob@example.test", role: "viewer" },
      { username: "Kelvin", role: "viewer" },
    ]);
  },
);
it("uses only the dedicated shared-note API and shows conflict review for editors", async () => {
  seed();
  const shared = {
    ...note,
    share_role: "editor",
    workspace_id: "someone-elses-space",
    shared_by: "Bob",
  } as SharedNote;
  vi.spyOn(api, "sharedNote").mockResolvedValue(shared);
  const { ApiError } = await import("../src/lib/errors");
  const update = vi
    .spyOn(api, "updateSharedNote")
    .mockRejectedValue(
      new ApiError(
        409,
        "version_conflict",
        "00000000-0000-4000-8000-000000000002",
      ),
    );
  const regular = vi.spyOn(api, "updateNote");
  render(
    <IdentityHarness>
      <SharedNotesPage id={note.id} />
    </IdentityHarness>,
    { wrapper: Wrapper },
  );
  const textbox = await screen.findByRole("textbox", { name: "Note text" });
  fireEvent.change(textbox, { target: { value: "my edit" } });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  await screen.findByRole("heading", { name: "Review conflicting edits" });
  expect(update).toHaveBeenCalledWith(
    note.id,
    "my edit",
    1,
    "someone-elses-space",
  );
  expect(regular).not.toHaveBeenCalled();
  expect((textbox as HTMLTextAreaElement).value).toBe("my edit");
});
it("never constructs an editor for a viewer note grant", async () => {
  seed();
  vi.spyOn(api, "sharedNote").mockResolvedValue({
    ...note,
    share_role: "viewer",
    workspace_id: "other",
    shared_by: "Bob",
  } as SharedNote);
  const update = vi.spyOn(api, "updateSharedNote");
  render(
    <IdentityHarness>
      <SharedNotesPage id={note.id} />
    </IdentityHarness>,
    { wrapper: Wrapper },
  );
  await screen.findByText("Shared by Bob · Can view");
  expect(screen.queryByRole("textbox")).toBeNull();
  expect(update).not.toHaveBeenCalled();
});

it("stops a regular editor when the current membership becomes viewer", async () => {
  seed();
  const update = vi.spyOn(api, "updateNote").mockResolvedValue(note);
  const { result } = renderHook(
    () => {
      useAuth();
      return useDraftEditor({ note, draftKey: "downgrade" });
    },
    { wrapper: Wrapper },
  );
  await waitFor(() =>
    expect(currentOwner()).toBe(sessionIdentity(ownerSession)),
  );
  act(() => result.current.setBody("unsaved before downgrade"));
  await act(async () => {
    queryClient.setQueryData(["session"], {
      ...ownerSession,
      workspace_role: "viewer",
    });
  });
  await waitFor(() => expect(currentSession()?.workspace_role).toBe("viewer"));
  expect(update).not.toHaveBeenCalled();
  await act(async () => {
    await result.current.save();
  });
  expect(update).not.toHaveBeenCalled();
});

it("rejects a workspace switch when server membership was revoked", async () => {
  seed();
  const { ApiError } = await import("../src/lib/errors");
  vi.mocked(api.workspaceSession).mockRejectedValue(
    new ApiError(403, "forbidden"),
  );
  render(<IdentityHarness />, { wrapper: Wrapper });
  await waitFor(() =>
    expect(currentOwner()).toBe(sessionIdentity(ownerSession)),
  );
  await act(async () => {
    await expect(
      switchWorkspace({
        id: "revoked",
        name: "Revoked",
        personal: false,
        role: "owner",
      }),
    ).rejects.toMatchObject({ status: 403 });
  });
  expect(currentSession()?.workspace_id).toBe(ownerSession.workspace_id);
  expect(currentSession()?.workspace_role).toBe("owner");
});
it("explicit sign-out erases every credential and workspace draft for this account only", () => {
  const scopes = [
    ownerSession,
    { ...ownerSession, workspace_id: "team" },
    { ...ownerSession, credential_id: "old-credential" },
  ];
  for (const session of scopes)
    localStorage.setItem(
      draftStorageKey(sessionIdentity(session), "capture"),
      "private",
    );
  const other = draftStorageKey(
    sessionIdentity({ ...ownerSession, user_id: "bob" }),
    "capture",
  );
  localStorage.setItem(other, "bob draft");
  eraseOwnerDrafts(localStorage, sessionIdentity(ownerSession));
  for (const session of scopes)
    expect(
      localStorage.getItem(
        draftStorageKey(sessionIdentity(session), "capture"),
      ),
    ).toBeNull();
  expect(localStorage.getItem(other)).toBe("bob draft");
});

it("palette Edit opens the note editor when Share is also available", async () => {
  seed();
  vi.spyOn(api, "note").mockResolvedValue(note);
  vi.spyOn(api, "labels").mockResolvedValue({ labels: [], total: 0 });
  vi.spyOn(api, "manifest").mockResolvedValue({
    ui: 1,
    plugins_rev: 0,
    plugins: [],
  } as Awaited<ReturnType<typeof api.manifest>>);
  vi.spyOn(api, "history").mockResolvedValue([]);
  vi.spyOn(api, "search").mockResolvedValue({
    hits: [],
    next_cursor: null,
    semantic: "off",
  });
  render(
    <IdentityHarness>
      <NotePage id={note.id} />
      <Palette noteId={note.id} onClose={() => {}} />
    </IdentityHarness>,
    { wrapper: Wrapper },
  );
  await screen.findByRole("button", { name: "Share" });
  fireEvent.click(screen.getByRole("option", { name: /^Edit note/ }));
  await screen.findByLabelText("Edit note");
  expect(screen.queryByRole("dialog", { name: "Share this note" })).toBeNull();
});

it("keeps duplicate shared note IDs separate in links, cache, drafts and writes", async () => {
  seed();
  const alpha = {
    ...note,
    title: "Alpha grant",
    body: "Alpha body",
    share_role: "editor",
    workspace_id: "alpha",
    shared_by: "Alice",
  } as SharedNote;
  const beta = {
    ...alpha,
    title: "Beta grant",
    body: "Beta body",
    workspace_id: "beta",
    shared_by: "Bob",
  };
  vi.spyOn(api, "sharedNotes").mockResolvedValue({ notes: [alpha, beta] });
  const get = vi
    .spyOn(api, "sharedNote")
    .mockImplementation(async (_id, workspace) =>
      workspace === "beta" ? beta : alpha,
    );
  const update = vi
    .spyOn(api, "updateSharedNote")
    .mockImplementation(async (_id, body, version, workspace) => ({
      ...(workspace === "beta" ? beta : alpha),
      body,
      version: version + 1,
    }));
  for (const grant of [alpha, beta]) {
    localStorage.setItem(
      draftStorageKey(
        sessionIdentity(ownerSession),
        `shared-note:${grant.workspace_id}:${note.id}`,
      ),
      JSON.stringify({
        body: `${grant.workspace_id} retained draft`,
        revision: 1,
        noteId: note.id,
        version: 1,
      }),
    );
  }
  const view = render(
    <IdentityHarness>
      <SharedNotesPage />
    </IdentityHarness>,
    { wrapper: Wrapper },
  );
  expect(
    (await screen.findByRole("link", { name: "Alpha grant" })).getAttribute(
      "href",
    ),
  ).toBe(`/shared/n/${note.id}?workspace_id=alpha`);
  expect(
    screen.getByRole("link", { name: "Beta grant" }).getAttribute("href"),
  ).toBe(`/shared/n/${note.id}?workspace_id=beta`);
  act(() =>
    window.history.replaceState(
      null,
      "",
      `/shared/n/${note.id}?workspace_id=alpha`,
    ),
  );
  view.rerender(
    <IdentityHarness>
      <SharedNotesPage id={note.id} />
    </IdentityHarness>,
  );
  await waitFor(() =>
    expect(
      (screen.getByLabelText("Note text") as HTMLTextAreaElement).value,
    ).toBe("alpha retained draft"),
  );
  fireEvent.change(screen.getByLabelText("Note text"), {
    target: { value: "Alpha edit" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  await waitFor(() =>
    expect(update).toHaveBeenCalledWith(note.id, "Alpha edit", 1, "alpha"),
  );
  act(() =>
    window.history.replaceState(
      null,
      "",
      `/shared/n/${note.id}?workspace_id=beta`,
    ),
  );
  await waitFor(() =>
    expect(
      (screen.getByLabelText("Note text") as HTMLTextAreaElement).value,
    ).toBe("beta retained draft"),
  );
  fireEvent.change(screen.getByLabelText("Note text"), {
    target: { value: "Beta edit" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  await waitFor(() =>
    expect(update).toHaveBeenCalledWith(note.id, "Beta edit", 1, "beta"),
  );
  expect(get).toHaveBeenCalledWith(note.id, "alpha");
  expect(get).toHaveBeenCalledWith(note.id, "beta");
  expect(
    queryClient.getQueryData(["shared-note", "alpha", note.id]),
  ).toBeTruthy();
  expect(
    queryClient.getQueryData(["shared-note", "beta", note.id]),
  ).toBeTruthy();
});
