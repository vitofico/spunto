import { ApiError } from "./errors";
import type { Session } from "./types";

let generation = 0;
let workspace: string | undefined;
let identity: string | null = null;
let account: string | undefined;
let credential: string | undefined;
export function sessionIdentity(session: Session): string {
  return session.user_id || session.workspace_id
    ? JSON.stringify([
        session.user_id ?? session.owner_id,
        session.workspace_id ?? "personal",
        session.credential_id ?? "session",
      ])
    : session.owner_id;
}
export function requestGeneration() {
  return generation;
}
export function invalidateRequests() {
  generation++;
}
export function assertCurrentRequest(started: number) {
  if (started !== generation) throw new ApiError(409, "identity_changed");
}
export function setRequestIdentity(session: Session | null) {
  const next = session
    ? JSON.stringify([
        sessionIdentity(session),
        session.credential_id ?? "session",
      ])
    : null;
  if (next !== identity) generation++;
  identity = next;
  workspace = session?.workspace_id;
  account = session?.user_id ?? session?.owner_id;
  credential = session?.credential_id;
}
export function actorHeaders(headers?: HeadersInit, enabled = true) {
  const value = new Headers(headers);
  value.delete("X-Spunto-Account");
  value.delete("X-Spunto-Credential");
  if (enabled && account && credential) {
    value.set("X-Spunto-Account", account);
    value.set("X-Spunto-Credential", credential);
  }
  return value;
}
export function workspaceHeaders(headers?: HeadersInit) {
  const value = actorHeaders(headers);
  if (workspace) value.set("X-Spunto-Workspace", workspace);
  else value.delete("X-Spunto-Workspace");
  return value;
}
