import { useEffect, useRef, useState, type ReactNode } from "react";
import { useSearch } from "wouter";
import { useAuth } from "../lib";
import { ApiError } from "../lib/errors";
import { api } from "../lib/api";
import type { AuthOptions } from "../lib/types";
import { startSSO } from "../lib/query";
import { useDocumentTitle } from "../lib/useDocumentTitle";

const seconds = (count: number) =>
  `${count} ${count === 1 ? "second" : "seconds"}`;
export default function AuthPage({
  setup = false,
  band,
}: {
  setup?: boolean;
  /** The plain-HTTP band: shown here too, where the password is typed. */
  band?: ReactNode;
}) {
  const auth = useAuth();
  const search = useSearch();
  const [options, setOptions] = useState<AuthOptions | null>(null);
  const [optionsError, setOptionsError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [register, setRegister] = useState(
    new URLSearchParams(search).get("register") === "1",
  );
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
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
            setOptionsError("Could not load sign-in options. Try again.");
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
    setup ? "Set up" : registering ? "Create your account" : "Sign in",
  );
  const message =
    waiting > 0
      ? `Too many tries. Try again in ${seconds(waiting)}.`
      : (error ?? auth.error?.message ?? null);
  return (
    <main className="auth">
      <form
        onSubmit={async (event) => {
          event.preventDefault();
          if (!passwordEnabled || waiting > 0) return;
          setError(null);
          setInvalid(null);
          if (registering && password !== confirmation) {
            setError("The passwords do not match.");
            setInvalid("password");
            return;
          }
          if (new TextEncoder().encode(password).length > 1024) {
            setError("That password is too long. Use a shorter passphrase.");
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
              failure instanceof Error ? failure.message : "Could not sign in.",
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
        <h1>
          {setup
            ? "Create your home"
            : registering
              ? "Create your account"
              : hosted || oidc || !options
                ? "Sign in to Spunto"
                : "Unlock your Spunto"}
        </h1>
        {!setup && !options && !optionsError && (
          <p role="status">Loading sign-in options…</p>
        )}
        {optionsError && (
          <>
            <p className="error" role="alert">
              {optionsError}
            </p>
            <button type="button" onClick={() => setAttempt(attempt + 1)}>
              Try again
            </button>
          </>
        )}
        {setup ? (
          <>
            <p className="muted">
              A quiet home for your notes. Use the setup code printed in the
              server log.
            </p>
            <label>
              Setup code
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
            Enter the existing owner password for this Spunto. There is no
            username to enter.
          </p>
        ) : hosted ? (
          <p className="muted">
            {registering
              ? "Choose a username for your personal notes. No email address is required."
              : "Use your username and password to open your notes."}
          </p>
        ) : null}
        {band}
        {hosted && register && !options.registration_enabled && (
          <p className="muted">
            Username/password signup is disabled on this server. Sign in to an
            existing account{oidc ? " or use SSO" : ""}.
          </p>
        )}
        {passwordEnabled && (
          <>
            {hosted && (
              <label>
                Username
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
                Use 3–64 letters, numbers, periods, underscores or hyphens.
                Start with a letter or number.
              </p>
            )}
            <label>
              {setup ? "Choose your password" : "Password"}
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
                Confirm password
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
                ? "Signing in…"
                : setup
                  ? "Create your home"
                  : registering
                    ? "Create account"
                    : "Sign in"}
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
              ? `Continue with ${options.label}`
              : "Continue with SSO"}
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
            {registering ? "Use an existing account" : "Create an account"}
          </button>
        )}
      </form>
    </main>
  );
}
