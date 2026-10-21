import createClient from "openapi-fetch";
import type { paths } from "./api-types";
import { ApiError, problem, safeError } from "./errors";
import {
  assertCurrentRequest,
  actorHeaders,
  requestGeneration,
  workspaceHeaders,
} from "./identity";
import type {
  Args,
  Attachment,
  Credential,
  Flags,
  Manifest,
  Meta,
  Note,
  NoteFilters,
  NotePage,
  Plugin,
  Preferences,
  Revision,
  Schema,
  SearchFilters,
  SearchPage,
  Session,
  Suggestion,
  TokenInput,
  Label,
  AIStatus,
  AIModelInventory,
  AIModelSelections,
  AIHealth,
  AICapability,
  VectorStatus,
  Workspace,
  AccessGrant,
  SharedNote,
  AuthOptions,
  AccountSecurity,
} from "./types";

let unauthorized: (reason?: string) => void = () => {};
export function onUnauthorized(callback: (reason?: string) => void) {
  unauthorized = callback;
}
const settledListeners = new Set<() => void>();
/** Runs after any request settles, once the caller's own handlers have run (sync state). */
export function onRequestSettled(listener: () => void) {
  settledListeners.add(listener);
  return () => {
    settledListeners.delete(listener);
  };
}
function settled() {
  setTimeout(() => {
    for (const listener of settledListeners) listener();
  });
}
const responseGenerations = new WeakMap<Response, number>();
async function check(
  response: Response,
  generation = responseGenerations.get(response) ?? requestGeneration(),
) {
  if (!response.ok) {
    let body: unknown;
    try {
      body = await response.clone().json();
    } catch {
      body = undefined;
    }
    assertCurrentRequest(generation);
    if (
      response.status === 409 &&
      body &&
      typeof body === "object" &&
      "code" in body &&
      body.code === "identity_changed"
    )
      unauthorized("identity_changed");
    if (
      response.status === 401 &&
      !(
        body &&
        typeof body === "object" &&
        "code" in body &&
        ["wrong_password", "wrong_setup_code"].includes(String(body.code))
      )
    )
      unauthorized();
    throw problem(response.status, body);
  }
}
export const generatedClient = createClient<paths>({
  baseUrl:
    typeof location === "undefined" ? "http://localhost" : location.origin,
  credentials: "same-origin",
  fetch: async (input) => {
    const generation = requestGeneration();
    const request = new Request(input);
    const response = await globalThis.fetch(
      new Request(request, { headers: workspaceHeaders(request.headers) }),
    );
    assertCurrentRequest(generation);
    responseGenerations.set(response, generation);
    return response;
  },
});
generatedClient.use({
  onResponse: async ({ response }) => {
    await check(response);
    return response;
  },
});
export function normalizeNote(note: Note): Note {
  return {
    ...note,
    suggestions: (note.suggestions ?? []).map((value) => {
      const raw = value as typeof value & { plugin_id?: string };
      return { ...raw, plugin: raw.plugin ?? raw.plugin_id ?? "" };
    }),
    annotations: (note.annotations ?? []).map((value) => {
      const raw = value as typeof value & { plugin_id?: string };
      const plugin = raw.plugin ?? raw.plugin_id ?? "";
      return {
        ...raw,
        plugin,
        id: raw.id ?? `${plugin}/${note.id}/${raw.kind}`,
      };
    }),
  };
}
function normalizeWire(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(normalizeWire);
  if (!value || typeof value !== "object") return value;
  const raw = value as Record<string, unknown>;
  if (
    typeof raw.id === "string" &&
    typeof raw.body === "string" &&
    typeof raw.version === "number"
  )
    return normalizeNote(raw as unknown as Note);
  return Object.fromEntries(
    Object.entries(raw).map(([key, item]) => [key, normalizeWire(item)]),
  );
}
function recipient(value: string) {
  const identifier = value.trim();
  return identifier.includes("@")
    ? { email: identifier }
    : { username: identifier };
}
async function result<T>(call: Promise<{ data?: unknown }>) {
  try {
    const generation = requestGeneration();
    const value = await call;
    assertCurrentRequest(generation);
    return normalizeWire(value.data) as T;
  } catch (error) {
    throw safeError(error);
  } finally {
    settled();
  }
}
export interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  headers?: HeadersInit;
  signal?: AbortSignal;
  workspace?: boolean;
  identity?: boolean;
}
export interface PairingCode {
  code: string;
  public_url: string;
  expires_at: string;
}
export async function request<T = unknown>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  // Only same-origin relative endpoints; caller-controlled plugin URLs never receive cookies.
  if (
    !path.startsWith("/api/v1/") ||
    path.includes("..") ||
    path.includes("\\")
  )
    throw new ApiError(422, "validation_failed");
  const generation = requestGeneration();
  try {
    const headers =
      options.workspace === false
        ? actorHeaders(options.headers)
        : workspaceHeaders(options.headers);
    if (options.identity === false) {
      headers.delete("X-Spunto-Account");
      headers.delete("X-Spunto-Credential");
    }
    if (
      options.body !== undefined &&
      !(options.body instanceof Blob) &&
      !(options.body instanceof FormData)
    )
      headers.set("Content-Type", "application/json");
    const response = await fetch(path, {
      method: options.method ?? "GET",
      credentials: "same-origin",
      cache: "no-store",
      headers,
      signal: options.signal,
      body:
        options.body === undefined
          ? undefined
          : options.body instanceof Blob || options.body instanceof FormData
            ? options.body
            : JSON.stringify(options.body),
    });
    assertCurrentRequest(generation);
    await check(response, generation);
    assertCurrentRequest(generation);
    const value =
      response.status === 204
        ? (undefined as T)
        : (normalizeWire(await response.json()) as T);
    assertCurrentRequest(generation);
    return value;
  } catch (error) {
    if (options.signal?.aborted) throw error;
    throw safeError(error);
  } finally {
    settled();
  }
}
const segment = encodeURIComponent;
export const api = {
  request,
  meta: () => request<Meta>("/api/v1/meta"),
  session: () => request<Session>("/api/v1/auth/session"),
  personalSession: () =>
    request<Session>("/api/v1/auth/session", { workspace: false }),
  workspaceSession: (id: string) =>
    request<Session>("/api/v1/auth/session", {
      workspace: false,
      headers: { "X-Spunto-Workspace": id },
    }),
  authOptions: () =>
    request<AuthOptions>("/api/v1/auth/options", {
      workspace: false,
      identity: false,
    }),
  accountSecurity: () =>
    request<AccountSecurity>("/api/v1/auth/account/security", {
      workspace: false,
    }),
  saveAccountPassword: (body: {
    username?: string;
    new_password: string;
    current_password?: string;
  }) =>
    request<void>("/api/v1/auth/account/password", {
      method: "PUT",
      body,
      workspace: false,
    }),
  linkAccountSSO: (current_password: string) =>
    request<{ login_url: string }>("/api/v1/auth/account/oidc-link", {
      method: "POST",
      body: { current_password },
      workspace: false,
    }),
  workspaces: () =>
    request<{ workspaces: Workspace[]; active_workspace_id: string }>(
      "/api/v1/workspaces",
    ),
  createWorkspace: (name: string) =>
    request<Workspace>("/api/v1/workspaces", {
      method: "POST",
      body: { name },
    }),
  workspaceMembers: (id: string) =>
    request<{ members: AccessGrant[] }>(
      `/api/v1/workspaces/${segment(id)}/members`,
    ),
  addWorkspaceMember: (
    id: string,
    account: string,
    role: "editor" | "viewer",
  ) =>
    request(`/api/v1/workspaces/${segment(id)}/members`, {
      method: "POST",
      body: { ...recipient(account), role },
    }),
  removeWorkspaceMember: (id: string, userId: string) =>
    request(`/api/v1/workspaces/${segment(id)}/members/${segment(userId)}`, {
      method: "DELETE",
    }),
  noteShares: (id: string) =>
    request<{ shares: AccessGrant[] }>(`/api/v1/notes/${segment(id)}/shares`),
  shareNote: (id: string, account: string, role: "editor" | "viewer") =>
    request(`/api/v1/notes/${segment(id)}/shares`, {
      method: "POST",
      body: { ...recipient(account), role },
    }),
  unshareNote: (id: string, userId: string) =>
    request(`/api/v1/notes/${segment(id)}/shares/${segment(userId)}`, {
      method: "DELETE",
    }),
  sharedNotes: () => request<{ notes: SharedNote[] }>("/api/v1/shared-notes"),
  sharedNote: (id: string, workspaceId?: string) =>
    request<SharedNote>(
      `/api/v1/shared-notes/${segment(id)}${workspaceId ? `?${new URLSearchParams({ workspace_id: workspaceId })}` : ""}`,
    ),
  updateSharedNote: (
    id: string,
    body: string,
    version: number,
    workspaceId?: string,
  ) =>
    request<SharedNote>(
      `/api/v1/shared-notes/${segment(id)}${workspaceId ? `?${new URLSearchParams({ workspace_id: workspaceId })}` : ""}`,
      {
        method: "PATCH",
        body: { body, version },
      },
    ),
  login: (password: string, username?: string) =>
    request<void>("/api/v1/auth/login", {
      method: "POST",
      workspace: false,
      body: username === undefined ? { password } : { username, password },
      identity: false,
    }),
  register: (username: string, password: string) =>
    request<void>("/api/v1/auth/register", {
      method: "POST",
      body: { username, password },
      workspace: false,
      identity: false,
    }),
  setup: (code: string, password: string) =>
    result<void>(
      generatedClient.POST("/api/v1/setup", { body: { code, password } }),
    ),
  logout: () => result<void>(generatedClient.POST("/api/v1/auth/logout")),
  notes: (filters: NoteFilters = {}, signal?: AbortSignal) => {
    const { archived, trashed, ...query } = filters;
    return result<NotePage>(
      generatedClient.GET("/api/v1/notes", {
        params: {
          query: {
            ...query,
            view: trashed
              ? "trash"
              : archived
                ? "archive"
                : (query.view ?? "timeline"),
            render: "html",
          },
        },
        signal,
      }),
    );
  },
  note: (id: string, signal?: AbortSignal) =>
    result<Note>(
      generatedClient.GET("/api/v1/notes/{id}", {
        params: { path: { id }, query: { render: "html" } },
        signal,
      }),
    ),
  createNote: (body: string, key: string) =>
    result<Note>(
      generatedClient.POST("/api/v1/notes", {
        params: { header: { "idempotency-key": key } },
        body: { body, private: false, source: { kind: "web" } },
      }),
    ),
  updateNote: (id: string, body: string, version: number) =>
    result<Note>(
      generatedClient.PATCH("/api/v1/notes/{id}", {
        params: { path: { id }, header: { "if-match": `"${version}"` } },
        body: { body },
      }),
    ),
  flags: (id: string, body: Flags) =>
    result<Note>(
      generatedClient.POST("/api/v1/notes/{id}/flags", {
        params: { path: { id } },
        body,
      }),
    ),
  listNotes: (filters: NoteFilters = {}) => api.notes(filters),
  emptyTrash: () =>
    request<{ deleted: number }>("/api/v1/trash/empty", { method: "POST" }),
  deleteNote: (id: string) =>
    result<void>(
      generatedClient.DELETE("/api/v1/notes/{id}", {
        params: { path: { id } },
      }),
    ),
  search: (q: string, filters: SearchFilters = {}, signal?: AbortSignal) =>
    result<SearchPage>(
      generatedClient.GET("/api/v1/search", {
        params: { query: { q, ...filters, render: "html" } },
        signal,
      }),
    ),
  labels: () => request<{ labels: Label[]; total: number }>("/api/v1/labels"),
  history: (id: string) =>
    request<Revision[]>(`/api/v1/notes/${segment(id)}/revisions`),
  restoreRevision: (id: string, version: number) =>
    result<Note>(
      generatedClient.POST("/api/v1/notes/{id}/revisions/{version}/restore", {
        params: { path: { id, version } },
      }),
    ),
  renameLabel: (from: string, to: string) =>
    result<{ rewritten: number }>(
      generatedClient.POST("/api/v1/labels/rename", { body: { from, to } }),
    ),
  deleteLabel: (path: string) =>
    result<{ rewritten: number }>(
      generatedClient.POST("/api/v1/labels/remove", { body: { path } }),
    ),
  manifest: () => request<Manifest>("/api/v1/plugins/manifest"),
  ai: () => request<AIStatus>("/api/v1/ai"),
  aiModels: () => request<AIModelInventory>("/api/v1/ai/models"),
  saveAIModels: (body: { revision: string; selections: AIModelSelections }) =>
    request<AIStatus>("/api/v1/ai/models", { method: "PUT", body }),
  checkAI: () =>
    request<{ health: AIHealth[] }>("/api/v1/ai/check", { method: "POST" }),
  reindexAI: () =>
    request<{ indexing: VectorStatus }>("/api/v1/ai/reindex", {
      method: "POST",
      body: { drop: false },
    }),
  saveAIRoute: (id: string, body: Partial<Record<AICapability, string>>) =>
    request(`/api/v1/plugins/${segment(id)}/ai-route`, { method: "PUT", body }),
  pluginPanel: (id: string, noteId: string, signal?: AbortSignal) =>
    request(
      `/api/v1/plugins/${segment(id)}/panel?${new URLSearchParams({ note_id: noteId })}`,
      { signal },
    ),
  catalog: () =>
    request<{ plugins: Plugin[]; plugins_rev: number }>("/api/v1/plugins"),
  pluginSettings: (id: string) =>
    request<{
      schema: Schema;
      values: Args;
      secrets: Record<string, { set: boolean; source: string }>;
    }>(`/api/v1/plugins/${segment(id)}/settings`),
  savePluginSettings: (id: string, body: Args) =>
    request(`/api/v1/plugins/${segment(id)}/settings`, { method: "PUT", body }),
  enablePlugin: (id: string, permissions: string[]) =>
    request<Plugin>(`/api/v1/plugins/${segment(id)}/enable`, {
      method: "POST",
      body: { permissions },
    }),
  disablePlugin: (id: string) =>
    request<Plugin>(`/api/v1/plugins/${segment(id)}/disable`, {
      method: "POST",
    }),
  deletePluginData: (id: string) =>
    request<void>(`/api/v1/plugins/${segment(id)}/data`, { method: "DELETE" }),
  command: (id: string, command: string, body: Args = {}) =>
    request(`/api/v1/plugins/${segment(id)}/commands/${segment(command)}`, {
      method: "POST",
      body,
    }),
  pluginRoute: (
    id: string,
    path: string,
    method: RequestOptions["method"] = "GET",
    args: Args = {},
  ) => {
    const endpoint = `/api/v1/plugins/${segment(id)}/${path.split("/").map(segment).join("/")}`;
    return request(
      method === "GET"
        ? `${endpoint}?${new URLSearchParams(Object.entries(args).map(([key, value]) => [key, String(value)]))}`
        : endpoint,
      { method, body: method === "GET" ? undefined : args },
    );
  },
  settings: () => request<Preferences>("/api/v1/settings"),
  saveSettings: (body: Partial<Preferences>) =>
    request<Preferences>("/api/v1/settings", { method: "PUT", body }),
  suggestions: (scope: "note" | "review" = "review", noteId?: string) =>
    request<{ suggestions: Suggestion[] }>(
      `/api/v1/suggestions?${new URLSearchParams({ scope, ...(noteId ? { note_id: noteId } : {}) })}`,
    ),
  decideSuggestion: (id: string, decision: "accept" | "dismiss") =>
    request(`/api/v1/suggestions/${segment(id)}/${decision}`, {
      method: "POST",
    }),
  credentials: () => request<Credential[]>("/api/v1/credentials"),
  createPairing: () =>
    request<PairingCode>("/api/v1/auth/pairing", { method: "POST" }),
  createToken: (body: TokenInput) =>
    request<{ id: string; token: string }>("/api/v1/tokens", {
      method: "POST",
      body,
    }),
  revokeCredential: (id: string) =>
    request<void>(`/api/v1/credentials/${segment(id)}`, { method: "DELETE" }),
  changePassword: (password: string) =>
    request<void>("/api/v1/auth/password", {
      method: "PUT",
      body: { password },
    }),
  status: () => request<Record<string, unknown>>("/api/v1/status"),
  exportNotes: async () => {
    const generation = requestGeneration();
    const response = await fetch("/api/v1/export", {
      credentials: "same-origin",
      cache: "no-store",
      headers: workspaceHeaders(),
    });
    assertCurrentRequest(generation);
    await check(response, generation);
    const value = await response.blob();
    assertCurrentRequest(generation);
    return value;
  },
  changes: (
    cursor: string | undefined,
    plugins_rev: number,
    signal: AbortSignal,
  ) =>
    request<{ cursor: string; plugins_rev: number; changed: boolean }>(
      `/api/v1/changes/wait?${new URLSearchParams({ plugins_rev: String(plugins_rev), ...(cursor ? { cursor } : {}) })}`,
      { signal },
    ),
  uploadAttachment: async (file: File) => {
    const meta = await request<Meta>("/api/v1/meta");
    const limit = meta.max_upload_bytes;
    const maximum =
      typeof limit === "number" &&
      Number.isSafeInteger(limit) &&
      limit > 0 &&
      limit <= 1_000_000_000
        ? limit
        : 5_000_000;
    if (file.size > maximum) throw new ApiError(422, "validation_failed");
    const form = new FormData();
    form.append("file", file);
    return request<Attachment>("/api/v1/attachments", {
      method: "POST",
      body: form,
    });
  },
};
