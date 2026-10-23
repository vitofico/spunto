import { LanguagePicker } from "../components/core/LanguagePicker";
import { t, useLanguage } from "../lib/i18n";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { useSearch } from "wouter";
import { useAuth } from "../lib";
import { ApiError, LocalizedError } from "../lib/errors";
import { api } from "../lib/api";
import type { AuthOptions } from "../lib/types";
import { startSSO } from "../lib/query";
import { useDocumentTitle } from "../lib/useDocumentTitle";

const seconds = (count: number) => t("count.second", { count });
export default function AuthPage({
  setup = false,
  band,
}: {
  setup?: boolean;
  /** The plain-HTTP band: shown here too, where the password is typed. */
  band?: ReactNode;
}) {
  useLanguage();
  const auth = useAuth();
  const search = useSearch();
  const [options, setOptions] = useState<AuthOptions | null>(null);
  const [optionsError, setOptionsError] = useState<Error | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [register, setRegister] = useState(
    new URLSearchParams(search).get("register") === "1",
  );
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  // While the server's back-off lasts, the button waits and the line counts down.
  const [waitUntil, setWaitUntil] = useState(0);
  const [now, setNow] = useState(Date.now);
  const passwordField = useRef<HTMLInputElement>(null);
  const codeField = useRef<HTMLInputElement>(null);
  // The field the last error is about, marked invalid and focused to retype.
  const [invalid, setInvalid] = useState<"code" | "password" | null>(null);
  const waiting = Math.ceil((waitUntil - now) / 1000);
  useEffect(() => {
    if (waitUntil <= Date.now()) return;
    const timer = setInterval(() => {
      setNow(Date.now());
      if (Date.now() >= waitUntil) {
        clearInterval(timer);
        setError(null);
      }
    }, 1000);
    return () => clearInterval(timer);
  }, [waitUntil]);
  useEffect(() => {
    let cancelled = false;
    if (!setup) {
      setOptionsError(null);
      void api
        .authOptions()
        .then((value) => {
          if (!cancelled) setOptions(value);
        })
        .catch(() => {
          if (!cancelled)
            setOptionsError(
              new LocalizedError(
                "auth.could_not_load_sign_in_options_try_again",
              ),
            );
        });
    }
    return () => {
      cancelled = true;
    };
  }, [setup, attempt]);
  const hosted = options?.mode === "hosted";
  const passwordEnabled =
    setup ||
    options?.mode === "local" ||
    (hosted && options.password_enabled === true);
  const registering =
    hosted && options.registration_enabled === true && register;
  const oidc =
    !setup && (options?.mode === "oidc" || options?.oidc_enabled === true);
  useDocumentTitle(
    setup
      ? t("auth.set_up")
      : registering
        ? t("auth.create_your_account")
        : t("auth.sign_in"),
  );
  const message =
    waiting > 0
      ? t("auth.too_many_tries_try_again_in_value1", {
          value1: seconds(waiting),
        })
      : (error?.message ?? auth.error?.message ?? null);
  return (
    <main className="auth">
      <form
        onSubmit={async (event) => {
          event.preventDefault();
          if (!passwordEnabled || waiting > 0) return;
          setError(null);
          setInvalid(null);
          if (registering && password !== confirmation) {
            setError(
              new LocalizedError("accountpanel.the_passwords_do_not_match"),
            );
            setInvalid("password");
            return;
          }
          if (new TextEncoder().encode(password).length > 1024) {
            setError(
              new LocalizedError(
                "accountpanel.that_password_is_too_long_use_a_shorter",
              ),
            );
            setInvalid("password");
            return;
          }
          setBusy(true);
          try {
            if (setup) await auth.setup(code, password);
            else if (registering)
              await auth.register(username.trim(), password);
            else if (hosted) await auth.login(password, username.trim());
            else await auth.login(password);
            setPassword("");
            setConfirmation("");
          } catch (failure) {
            if (
              failure instanceof ApiError &&
              failure.code === "rate_limited" &&
              failure.retryAfter
            ) {
              setNow(Date.now());
              setWaitUntil(Date.now() + failure.retryAfter * 1000);
            }
            setError(
              failure instanceof Error
                ? failure
                : new LocalizedError("auth.could_not_sign_in"),
            );
            const code = failure instanceof ApiError ? failure.code : "";
            const field =
              code === "wrong_setup_code"
                ? codeField
                : code === "wrong_password" || code === "unauthenticated"
                  ? passwordField
                  : null;
            setInvalid(
              field === codeField
                ? "code"
                : field === passwordField
                  ? "password"
                  : null,
            );
            // Keep the owner in the field with the old attempt selected to retype.
            const target = (field ?? passwordField).current;
            requestAnimationFrame(() => {
              target?.focus();
              target?.select();
            });
          } finally {
            setBusy(false);
          }
        }}
      >
        <div className="wordmark">
          spunto<span>.</span>
        </div>
        <LanguagePicker />
        <h1>
          {setup
            ? t("auth.create_your_home")
            : registering
              ? t("auth.create_your_account")
              : hosted || oidc || !options
                ? t("auth.sign_in_to_spunto")
                : t("auth.unlock_your_spunto")}
        </h1>
        {!setup && !options && !optionsError && (
          <p role="status">{t("auth.loading_sign_in_options")}</p>
        )}
        {optionsError && (
          <>
            <p className="error" role="alert">
              {optionsError.message}
            </p>
            <button type="button" onClick={() => setAttempt(attempt + 1)}>
              {t("app.try_again")}
            </button>
          </>
        )}
        {setup ? (
          <>
            <p className="muted">
              {t("auth.a_quiet_home_for_your_notes_use_the")}
            </p>
            <label>
              {t("auth.setup_code")}
              <input
                ref={codeField}
                className="field"
                aria-invalid={invalid === "code" || undefined}
                aria-describedby="auth-message"
                value={code}
                onChange={(event) => setCode(event.target.value)}
                autoComplete="off"
                required
                autoFocus
              />
            </label>
          </>
        ) : options?.mode === "local" ? (
          <p className="muted">
            {t("auth.enter_the_existing_owner_password_for_this_spunto")}
          </p>
        ) : hosted ? (
          <p className="muted">
            {registering
              ? t("auth.choose_a_username_for_your_personal_notes_no")
              : t("auth.use_your_username_and_password_to_open_your")}
          </p>
        ) : null}
        {band}
        {hosted && register && !options.registration_enabled && (
          <p className="muted">
            {t(oidc ? "auth.signup_disabled_sso" : "auth.signup_disabled")}
          </p>
        )}
        {passwordEnabled && (
          <>
            {hosted && (
              <label>
                {t("accountpanel.username_2")}
                <input
                  className="field"
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  autoComplete="username"
                  autoCapitalize="none"
                  spellCheck={false}
                  minLength={3}
                  maxLength={64}
                  pattern={"[A-Za-z0-9][A-Za-z0-9._\\-]{2,63}"}
                  required
                  autoFocus
                />
              </label>
            )}
            {registering && (
              <p className="muted">
                {t("auth.use_3_64_letters_numbers_periods_underscores_or")}
              </p>
            )}
            <label>
              {setup
                ? t("auth.choose_your_password")
                : t("profilepanel.password")}
              <input
                ref={passwordField}
                className="field"
                type="password"
                aria-invalid={invalid === "password" || undefined}
                aria-describedby="auth-message"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete={
                  setup || registering ? "new-password" : "current-password"
                }
                minLength={setup ? 12 : registering ? 8 : undefined}
                maxLength={1024}
                required
                autoFocus={!setup && !hosted}
              />
            </label>
            {registering && (
              <label>
                {t("auth.confirm_password")}
                <input
                  className="field"
                  type="password"
                  value={confirmation}
                  onChange={(event) => setConfirmation(event.target.value)}
                  autoComplete="new-password"
                  minLength={8}
                  maxLength={1024}
                  required
                />
              </label>
            )}
            {/* Always rendered, so a message never pushes the button down. */}
            <p id="auth-message" className="error auth-message" role="alert">
              {message}
            </p>
            <button className="button primary" disabled={busy || waiting > 0}>
              {busy
                ? t("auth.signing_in")
                : setup
                  ? t("auth.create_your_home")
                  : registering
                    ? t("auth.create_account")
                    : t("auth.sign_in")}
            </button>
          </>
        )}
        {oidc && (
          <a
            className={passwordEnabled ? "button" : "button primary"}
            href="/api/v1/auth/oidc/start"
            onClick={startSSO}
          >
            {options?.label
              ? t("auth.continue_with_value1", { value1: options.label })
              : t("auth.continue_with_sso")}
          </a>
        )}
        {hosted && options.registration_enabled && (
          <button
            type="button"
            className="button"
            onClick={() => {
              setRegister(!registering);
              setPassword("");
              setConfirmation("");
              setError(null);
            }}
          >
            {registering
              ? t("auth.use_an_existing_account")
              : t("auth.create_an_account")}
          </button>
        )}
      </form>
    </main>
  );
}
