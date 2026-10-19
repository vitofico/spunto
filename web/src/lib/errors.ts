const messages: Record<string, string> = {
  identity_changed:
    "Your account or space changed. Reopen this note to continue.",
  draft_recovery_required:
    "Sign-in changed, but an earlier draft needs recovery. Both copies are kept on this browser. Do not clear browser storage.",
  unauthenticated: "Sign in again to save. Your draft is kept on this device.",
  wrong_password: "That password is not right.",
  wrong_setup_code: "That setup code is not right.",
  setup_required: "Finish setup first.",
  setup_done: "Setup is complete. Sign in.",
  version_conflict:
    "Your edit was preserved as a conflict copy. Review it before continuing.",
  validation_failed: "Check the information and try again.",
  rate_limited: "Wait a little before trying again.",
  permissions_changed: "The permission list changed. Review it again.",
  pinned_by_environment: "This setting is managed by the server environment.",
  password_from_environment:
    "The password is managed by the server environment.",
};
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly conflictId?: string,
  ) {
    super(
      messages[code] ??
        (status === 0
          ? "The server is unreachable. Your draft is kept on this device."
          : status === 403
            ? "This action is not permitted."
            : status === 404
              ? "This item is unavailable."
              : "The server could not complete this action. Try again."),
    );
    this.name = "ApiError";
  }
  get retryable() {
    return this.status === 0 || this.status >= 500 || this.status === 429;
  }
}
export function problem(status: number, value: unknown): ApiError {
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
  return new ApiError(status, code, conflictId);
}
export function safeError(error: unknown) {
  return error instanceof ApiError ? error : new ApiError(0, "unreachable");
}
