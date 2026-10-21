import {
  useEffect,
  useLayoutEffect,
  useRef,
  useSyncExternalStore,
} from "react";
import {
  QueryClient,
  useInfiniteQuery,
  useMutation,
  useQuery,
} from "@tanstack/react-query";
import { api, onRequestSettled, onUnauthorized } from "./api";
import { ApiError } from "./errors";
import { carryRotatedDrafts, eraseOwnerDrafts, keptDrafts } from "./drafts";
import type {
  Flags,
  NoteFilters,
  SearchFilters,
  Session,
  Workspace,
} from "./types";
import {
  invalidateRequests,
  sessionIdentity,
  setRequestIdentity,
} from "./identity";
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30000,
      refetchOnWindowFocus: true,
      retry: (attempt, error) =>
        error instanceof ApiError && error.retryable && attempt < 2,
    },
    mutations: { retry: false },
  },
});
let activeOwner: string | null = null;
let activeSession: Session | null = null;
let switchingWorkspace = false;
let refreshingAuthentication = false;
let authenticationAction = false;
let authenticationRevision = 0;
let authenticationIssue: Error | null = null;
let workspaceIssue: string | null = null;
function workspaceStorageKey(session: Session) {
  return (
    "spunto:workspace:" +
    JSON.stringify([
      session.user_id ?? session.owner_id,
      session.credential_id ?? "session",
    ])
  );
}
let signedOut = false;
try {
  signedOut = ["1", "identity-changed"].includes(
    localStorage.getItem("spunto:signed-out") ?? "",
  );
} catch {}
function setSignedOut(value: boolean, marker: "1" | "identity-changed" = "1") {
  signedOut = value;
  try {
    if (value) localStorage.setItem("spunto:signed-out", marker);
    else localStorage.removeItem("spunto:signed-out");
  } catch {}
}
function cancelAuthentication() {
  authenticationRevision++;
  refreshingAuthentication = false;
  authenticationAction = false;
  invalidateRequests();
}
const authListeners = new Set<() => void>();
function ownerChanged(owner: string | null) {
  if (!owner) {
    activeSession = null;
    setRequestIdentity(null);
  }
  activeOwner = owner;
  for (const listener of authListeners) listener();
}
export function subscribeOwner(listener: () => void) {
  authListeners.add(listener);
  return () => {
    authListeners.delete(listener);
  };
}
function activateSession(session: Session) {
  const next = sessionIdentity(session);
  if (activeOwner && activeOwner !== next) {
    ownerChanged(null);
    void queryClient.cancelQueries({
      predicate: (query) =>
        !["meta", "session"].includes(String(query.queryKey[0])),
    });
    queryClient.removeQueries({
      predicate: (query) =>
        !["meta", "session"].includes(String(query.queryKey[0])),
    });
  }
  activeSession = session;
  setRequestIdentity(session);
  ownerChanged(next);
}
export function currentSession() {
  return activeSession;
}
export function useWorkspaceRole() {
  return useSyncExternalStore(
    subscribeOwner,
    () => activeSession?.workspace_role ?? "owner",
    () => "owner",
  );
}
export function useHosted() {
  return useSyncExternalStore(
    subscribeOwner,
    () => !!activeSession?.workspace_id,
    () => false,
  );
}
export function useWorkspaceIssue() {
  return useSyncExternalStore(
    subscribeOwner,
    () => workspaceIssue,
    () => null,
  );
}
export async function switchWorkspace(workspace: Workspace) {
  const session = activeSession;
  if (
    !session ||
    switchingWorkspace ||
    refreshingAuthentication ||
    session.workspace_id === workspace.id
  )
    return;
  const revision = authenticationRevision;
  switchingWorkspace = true;
  workspaceIssue = null;
  // Synchronous deactivation stops autosave before the request namespace changes.
  ownerChanged(null);
  await queryClient.cancelQueries();
  queryClient.clear();
  let next: Session | null = session;
  try {
    const validated = await api.workspaceSession(workspace.id);
    if (signedOut || revision !== authenticationRevision)
      throw new ApiError(401, "unauthenticated");
    if (
      (validated.user_id ?? validated.owner_id) !==
        (session.user_id ?? session.owner_id) ||
      validated.credential_id !== session.credential_id ||
      validated.workspace_id !== workspace.id
    )
      throw new ApiError(409, "identity_changed");
    next = validated;
    try {
      sessionStorage.setItem(workspaceStorageKey(session), workspace.id);
    } catch {}
  } catch (error) {
    workspaceIssue =
      error instanceof Error ? error.message : "Could not open this space.";
    if (error instanceof ApiError && [401, 409].includes(error.status))
      next = null;
    try {
      sessionStorage.removeItem(workspaceStorageKey(session));
    } catch {}
    throw error;
  } finally {
    switchingWorkspace = false;
    if (signedOut || revision !== authenticationRevision) next = null;
    if (next) activateSession(next);
    else ownerChanged(null);
    queryClient.setQueryData(["session"], next);
    void queryClient.invalidateQueries();
  }
}
export function startSSO() {
  cancelAuthentication();
  setSignedOut(false);
}
export async function refreshAuthentication(preserveContext = true) {
  if (switchingWorkspace || (preserveContext && (!activeSession || signedOut)))
    throw new ApiError(409, "identity_changed");
  const previous = preserveContext ? activeSession : null;
  await authenticationTransition(previous);
}
async function authenticationTransition(
  previous: Session | null,
  action?: () => Promise<void>,
) {
  const revision = ++authenticationRevision;
  authenticationIssue = null;
  refreshingAuthentication = true;
  authenticationAction = !!action;
  invalidateRequests();
  ownerChanged(null);
  try {
    await queryClient.cancelQueries();
    if (revision !== authenticationRevision)
      throw new ApiError(409, "identity_changed");
    if (action) await action();
    if (revision !== authenticationRevision)
      throw new ApiError(409, "identity_changed");
    authenticationAction = false;
    queryClient.clear();
    const value = previous?.workspace_id
      ? await api.workspaceSession(previous.workspace_id)
      : await api.session();
    if (
      revision !== authenticationRevision ||
      (previous &&
        ((value.user_id ?? value.owner_id) !==
          (previous.user_id ?? previous.owner_id) ||
          value.workspace_id !== previous.workspace_id))
    )
      throw new ApiError(409, "identity_changed");
    if (
      previous?.workspace_id &&
      previous.credential_id !== value.credential_id
    )
      carryRotatedDrafts(
        localStorage,
        sessionIdentity(previous),
        sessionIdentity(value),
      );
    if (previous && value.workspace_id) {
      try {
        sessionStorage.setItem(workspaceStorageKey(value), value.workspace_id);
      } catch {}
    }
    setSignedOut(false);
    queryClient.setQueryData(["session"], value);
    refreshingAuthentication = false;
    activateSession(value);
    void queryClient.invalidateQueries();
  } catch (error) {
    if (revision === authenticationRevision && error instanceof Error) {
      authenticationIssue = error;
      if (
        error instanceof ApiError &&
        ["draft_recovery_required", "identity_changed"].includes(error.code)
      )
        setSignedOut(true, "identity-changed");
    }
    throw error;
  } finally {
    if (revision === authenticationRevision && refreshingAuthentication) {
      refreshingAuthentication = false;
      authenticationAction = false;
      ownerChanged(null);
      queryClient.setQueryData(["session"], null);
    }
  }
}
async function authenticate(action: () => Promise<void>) {
  if (switchingWorkspace) throw new ApiError(409, "identity_changed");
  await authenticationTransition(null, action);
}
export function currentOwner() {
  return activeOwner;
}
export function useOwner() {
  return useSyncExternalStore(
    (listener) => {
      authListeners.add(listener);
      return () => {
        authListeners.delete(listener);
      };
    },
    currentOwner,
    () => null,
  );
}
onUnauthorized((reason) => {
  if (reason === "identity_changed") setSignedOut(true, "identity-changed");
  cancelAuthentication();
  ownerChanged(null);
  void queryClient.cancelQueries({
    predicate: (query) =>
      !["meta", "session"].includes(String(query.queryKey[0])),
  });
  queryClient.removeQueries({
    predicate: (query) =>
      !["meta", "session"].includes(String(query.queryKey[0])),
  });
  queryClient.setQueryData(["session"], null);
});
if (typeof window !== "undefined")
  window.addEventListener("storage", (event) => {
    if (
      event.key === "spunto:signed-out" &&
      ["1", "identity-changed"].includes(event.newValue ?? "")
    ) {
      const owner = activeOwner;
      cancelAuthentication();
      signedOut = true;
      ownerChanged(null);
      void queryClient.cancelQueries();
      // Deactivation persists pending work. Only explicit logout erases it;
      // an unexpected cookie in another tab must keep recoverable drafts.
      if (owner && event.newValue === "1")
        try {
          eraseOwnerDrafts(localStorage, owner);
        } catch {}
      queryClient.clear();
      queryClient.setQueryData(["session"], null);
    }
  });
export function useAuth() {
  useSyncExternalStore(
    subscribeOwner,
    () =>
      `${authenticationRevision}:${refreshingAuthentication}:${authenticationAction}:${switchingWorkspace}:${signedOut}`,
    () => "",
  );
  const meta = useQuery({
    queryKey: ["meta"],
    queryFn: api.meta,
    retry: false,
  });
  const session = useQuery({
    queryKey: ["session"],
    queryFn: async () => {
      const revision = authenticationRevision;
      try {
        const value = await api.session();
        if (revision !== authenticationRevision)
          throw new ApiError(409, "identity_changed");
        return value;
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) return null;
        if (
          error instanceof ApiError &&
          (error.status === 403 ||
            (error.status === 404 && error.code === "workspace_unavailable")) &&
          activeSession?.workspace_id
        ) {
          ownerChanged(null);
          await queryClient.cancelQueries({
            predicate: (query) =>
              !["meta", "session"].includes(String(query.queryKey[0])),
          });
          queryClient.removeQueries({
            predicate: (query) =>
              !["meta", "session"].includes(String(query.queryKey[0])),
          });
          try {
            return await api.personalSession();
          } catch {
            return null;
          }
        }
        throw error;
      }
    },
    retry: false,
    enabled:
      !signedOut &&
      !refreshingAuthentication &&
      !switchingWorkspace &&
      meta.data?.setup_pending === false,
  });
  useLayoutEffect(() => {
    if (switchingWorkspace || refreshingAuthentication) return;
    if (!signedOut && session.data) {
      // A remembered space is selected only after the server validates current membership.
      const starting = !activeOwner;
      const value = session.data;
      activateSession(value);
      if (starting && value.workspace_id) {
        let saved: string | null = null;
        try {
          saved = sessionStorage.getItem(workspaceStorageKey(value));
        } catch {}
        if (saved && saved !== value.workspace_id)
          void switchWorkspace({
            id: saved,
            name: "",
            role: "viewer",
            personal: false,
          }).catch(() => {});
      }
    } else ownerChanged(null);
  }, [session.data]);
  const login = useMutation({
    mutationFn: ({
      password,
      username,
    }: {
      password: string;
      username?: string;
    }) =>
      authenticate(() =>
        username === undefined
          ? api.login(password)
          : api.login(password, username),
      ),
  });
  const register = useMutation({
    mutationFn: ({
      username,
      password,
    }: {
      username: string;
      password: string;
    }) => authenticate(() => api.register(username, password)),
  });
  const setup = useMutation({
    mutationFn: ({ code, password }: { code: string; password: string }) =>
      api.setup(code, password),
    onSuccess: async () => {
      setSignedOut(false);
      queryClient.clear();
      const value = await api.session();
      activateSession(value);
      queryClient.setQueryData(["session"], value);
      void queryClient.invalidateQueries();
    },
  });
  const logout = useMutation({
    mutationFn: async () => {
      const owner = activeOwner;
      // Stop editors and requests before clearing drafts, so a delayed ACK cannot recreate private data.
      cancelAuthentication();
      setSignedOut(true);
      ownerChanged(null);
      await queryClient.cancelQueries();
      try {
        await api.logout();
      } finally {
        if (owner)
          try {
            eraseOwnerDrafts(localStorage, owner);
          } catch {}
        queryClient.clear();
        queryClient.setQueryData(["session"], null);
      }
    },
  });
  const status:
    "loading" | "authenticated" | "anonymous" | "setup" | "unreachable" =
    (refreshingAuthentication && !authenticationAction) || switchingWorkspace
      ? "loading"
      : meta.data?.setup_pending
        ? "setup"
        : meta.isError && !meta.data && !session.data
          ? "unreachable"
          : signedOut
            ? "anonymous"
            : meta.isPending || session.isPending
              ? "loading"
              : session.data
                ? "authenticated"
                : "anonymous";
  return {
    status,
    session: session.data,
    login: (password: string, username?: string) =>
      login.mutateAsync({ password, username }),
    register: (username: string, password: string) =>
      register.mutateAsync({ username, password }),
    refresh: () => refreshAuthentication(),
    reconnect: () => void meta.refetch(),
    reconnecting: meta.isFetching,
    setup: (code: string, password: string) =>
      setup.mutateAsync({ code, password }),
    logout: () => logout.mutateAsync(),
    error:
      authenticationIssue ??
      login.error ??
      register.error ??
      setup.error ??
      logout.error ??
      session.error ??
      meta.error,
  };
}
export function useNotes(filters: NoteFilters = {}) {
  return useInfiniteQuery({
    queryKey: ["notes", filters],
    initialPageParam: filters.cursor,
    queryFn: ({ pageParam, signal }) =>
      api.notes({ ...filters, cursor: pageParam }, signal),
    getNextPageParam: (page) => page.next_cursor ?? undefined,
  });
}
export function useSearch(
  q: string,
  filters: SearchFilters = {},
  enabled = true,
) {
  return useInfiniteQuery({
    queryKey: ["search", q, filters],
    enabled,
    initialPageParam: filters.cursor,
    queryFn: ({ pageParam, signal }) =>
      api.search(q, { ...filters, cursor: pageParam }, signal),
    getNextPageParam: (page) => page.next_cursor ?? undefined,
  });
}
export function useNote(id: string) {
  return useQuery({
    queryKey: ["note", id],
    queryFn: ({ signal }) => api.note(id, signal),
    enabled: !!id,
  });
}
export function useLabels() {
  return useQuery({ queryKey: ["labels"], queryFn: api.labels });
}
export function useManifest() {
  return useQuery({ queryKey: ["manifest"], queryFn: api.manifest });
}
export function useHistory(id: string) {
  return useQuery({
    queryKey: ["history", id],
    queryFn: () => api.history(id),
    enabled: !!id,
  });
}
export function useApiQuery<T>(
  key: readonly unknown[],
  loader: () => Promise<T>,
) {
  return useQuery({ queryKey: key, queryFn: loader });
}
export async function invalidateContent() {
  await Promise.all(
    [
      "notes",
      "note",
      "search",
      "labels",
      "history",
      "suggestions",
      "status",
      "slip",
      "plugin-panel",
      "label-impact",
      "shared-notes",
      "shared-note",
    ].map((key) => queryClient.invalidateQueries({ queryKey: [key] })),
  );
}
export function useApiMutation<T, R>(
  action: (input: T) => Promise<R>,
  keys: readonly string[] = [
    "notes",
    "note",
    "search",
    "labels",
    "history",
    "suggestions",
    "manifest",
    "catalog",
    "settings",
  ],
) {
  return useMutation({
    mutationFn: action,
    onSuccess: () =>
      Promise.all(
        keys.map((key) => queryClient.invalidateQueries({ queryKey: [key] })),
      ),
  });
}
export const useCreateNote = () =>
  useApiMutation(({ body, key }: { body: string; key: string }) =>
    api.createNote(body, key),
  );
export const useUpdateNote = () =>
  useApiMutation(
    ({ id, body, version }: { id: string; body: string; version: number }) =>
      api.updateNote(id, body, version),
  );
export const useFlags = () =>
  useApiMutation(({ id, flags }: { id: string; flags: Flags }) =>
    api.flags(id, flags),
  );
export const useRenameLabel = () =>
  useApiMutation(({ from, to }: { from: string; to: string }) =>
    api.renameLabel(from, to),
  );
export const useDeleteLabel = () =>
  useApiMutation((path: string) => api.deleteLabel(path));
export const useRestoreRevision = () =>
  useApiMutation(({ id, version }: { id: string; version: number }) =>
    api.restoreRevision(id, version),
  );
let feedFailing = false;
const syncListeners = new Set<() => void>();
function setFeedFailing(value: boolean) {
  if (feedFailing === value) return;
  feedFailing = value;
  for (const listener of syncListeners) listener();
}
function subscribeSync(listener: () => void) {
  syncListeners.add(listener);
  const stop = onRequestSettled(listener);
  for (const event of ["online", "offline", "storage"])
    window.addEventListener(event, listener);
  return () => {
    syncListeners.delete(listener);
    stop();
    for (const event of ["online", "offline", "storage"])
      window.removeEventListener(event, listener);
  };
}
/**
 * One sync state for the rail and the banner: offline when the browser says so or
 * the change feed keeps failing, plus the saves still waiting on this device.
 */
export function useSyncState() {
  const owner = useOwner();
  const online = useSyncExternalStore(
    subscribeSync,
    () => navigator.onLine && !feedFailing,
    () => true,
  );
  const waiting = useSyncExternalStore(
    subscribeSync,
    () => {
      try {
        return owner ? keptDrafts(localStorage, owner).waiting : 0;
      } catch {
        return 0;
      }
    },
    () => 0,
  );
  return { online, waiting };
}
/** A single app-level watcher; hidden tabs abort the poll and focus starts a fresh one. */
export function useLiveChanges(enabled: boolean) {
  const owner = useOwner();
  const last = useRef<{ cursor?: string; rev: number }>({ rev: 0 });
  useEffect(() => {
    last.current = { rev: 0 };
    if (!enabled || !owner) return;
    let stopped = false;
    let controller: AbortController | undefined;
    let retry: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      if (stopped || document.visibilityState !== "visible") return;
      const requestController = new AbortController();
      controller = requestController;
      try {
        const result = await api.changes(
          last.current.cursor,
          last.current.rev,
          requestController.signal,
        );
        if (stopped) return;
        setFeedFailing(false);
        const pluginsChanged = result.plugins_rev !== last.current.rev;
        last.current = { cursor: result.cursor, rev: result.plugins_rev };
        if (result.changed) {
          void invalidateContent();
          if (pluginsChanged)
            for (const key of [
              "manifest",
              "catalog",
              "settings",
              "plugin-settings",
            ])
              void queryClient.invalidateQueries({ queryKey: [key] });
        }
        if (!stopped) void poll();
      } catch (error) {
        if (stopped || requestController.signal.aborted) return;
        if (error instanceof ApiError && !error.retryable) return;
        setFeedFailing(true);
        retry = setTimeout(() => {
          void poll();
        }, 5000);
      }
    };
    const visibility = () => {
      controller?.abort();
      clearTimeout(retry);
      if (document.visibilityState === "visible") void poll();
    };
    document.addEventListener("visibilitychange", visibility);
    // Back online: ask again now instead of waiting for the retry timer.
    window.addEventListener("online", visibility);
    void poll();
    return () => {
      stopped = true;
      controller?.abort();
      clearTimeout(retry);
      setFeedFailing(false);
      document.removeEventListener("visibilitychange", visibility);
      window.removeEventListener("online", visibility);
    };
  }, [enabled, owner]);
}
