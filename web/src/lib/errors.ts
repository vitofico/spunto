import { t } from "./i18n";
// Codes whose wording depends on what this client does next (drafts, sign-in), so
// the client's sentence wins. Every other failure shows the server's own detail.
const messages: () => Record<string, string> = () => ({
  identity_changed: t("errors.your_account_or_space_changed_reopen_this_note"),
  draft_recovery_required: t(
    "errors.sign_in_changed_but_an_earlier_draft_needs",
  ),
  unauthenticated: t("errors.sign_in_again_to_save_your_draft_is"),
  wrong_password: t("errors.that_password_didn_t_work"),
  wrong_setup_code: t("errors.that_setup_code_didn_t_work"),
  setup_required: t("errors.finish_setup_first"),
  setup_done: t("errors.setup_is_complete_sign_in"),
  version_conflict: t("errors.your_edit_was_preserved_as_a_conflict_copy"),
  permissions_changed: t("errors.the_permission_list_changed_review_it_again"),
  pinned_by_environment: t(
    "errors.this_setting_is_managed_by_the_server_environment",
  ),
  password_from_environment: t(
    "errors.the_password_is_managed_by_the_server_environment",
  ),
});
function fallback(status: number) {
  return status === 0
    ? t("app.can_t_reach_the_server_what_you_type")
    : status === 403
      ? t("errors.this_action_is_not_permitted")
      : status === 404
        ? t("errors.this_item_is_unavailable")
        : status === 422
          ? t("errors.check_the_information_and_try_again")
          : status === 429
            ? t("errors.wait_a_little_before_trying_again")
            : t("errors.the_server_could_not_complete_this_action_try");
}
/** Keep client validation messages reactive when the UI language changes. */
export class LocalizedError extends Error {
  constructor(readonly key: string) {
    super();
  }
  get message() {
    return t(this.key);
  }
}
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly conflictId?: string,
    readonly detail?: string,
    /** Seconds the server asked to wait (Retry-After). */
    readonly retryAfter?: number,
  ) {
    super();
    this.name = "ApiError";
  }
  get message() {
    return messages()[this.code] ?? this.detail ?? fallback(this.status);
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
