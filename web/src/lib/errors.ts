// Codes whose wording depends on what this client does next (drafts, sign-in), so
// the client's sentence wins. Every other failure shows the server's own detail.
const messages: Record<string, string> = {
  identity_changed:
    "Your account or space changed. Reopen this note to continue.",
  draft_recovery_required:
    "Sign-in changed, but an earlier draft needs recovery. Both copies are kept on this browser. Do not clear browser storage.",
  unauthenticated: "Sign in again to save. Your draft is kept on this device.",
  wrong_password: "That password didn’t work.",
  wrong_setup_code: "That setup code didn’t work.",
  setup_required: "Finish setup first.",
  setup_done: "Setup is complete. Sign in.",
  version_conflict:
    "Your edit was preserved as a conflict copy. Review it before continuing.",
  permissions_changed: "The permission list changed. Review it again.",
  pinned_by_environment: "This setting is managed by the server environment.",
  password_from_environment:
    "The password is managed by the server environment.",
};
function fallback(status: number) {
  return status === 0
    ? "Can’t reach the server. What you type is kept on this device."
    : status === 403
      ? "This action is not permitted."
      : status === 404
        ? "This item is unavailable."
        : status === 422
          ? "Check the information and try again."
          : status === 429
            ? "Wait a little before trying again."
            : "The server could not complete this action. Try again.";
}
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly conflictId?: string,
    detail?: string,
    /** Seconds the server asked to wait (Retry-After). */
    readonly retryAfter?: number,
  ) {
    super(messages[code] ?? detail ?? fallback(status));
    this.name = "ApiError";
  }
  get retryable() {
    return this.status === 0 || this.status >= 500 || this.status === 429;
  }
}
/**
 * Only a problem document's `detail` is shown: the server writes it for people and
 * keeps note text, queries and secrets out of it (02 §5.1). Other bodies are dropped.
 */
export function problem(
  status: number,
  value: unknown,
  response?: Response,
): ApiError {
  const data =
    value && typeof value === "object"
      ? (value as Record<string, unknown>)
      : {};
  const code =
    typeof data.code === "string" && /^[a-z_]{1,80}$/.test(data.code)
      ? data.code
      : "request_failed";
  const conflictId =
    typeof data.conflict_id === "string" &&
    /^[a-f0-9-]{36}$/i.test(data.conflict_id)
      ? data.conflict_id
      : undefined;
  const detail =
    response?.headers
      .get("Content-Type")
      ?.startsWith("application/problem+json") &&
    typeof data.detail === "string" &&
    data.detail.trim() &&
    data.detail.length <= 300
      ? data.detail.trim()
      : undefined;
  const header = response?.headers.get("Retry-After");
  const wait = header
    ? /^\d+$/.test(header)
      ? Number(header)
      : (Date.parse(header) - Date.now()) / 1000
    : NaN;
  return new ApiError(
    status,
    code,
    conflictId,
    detail,
    wait > 0 ? Math.ceil(wait) : undefined,
  );
}
export function safeError(error: unknown) {
  return error instanceof ApiError ? error : new ApiError(0, "unreachable");
}
