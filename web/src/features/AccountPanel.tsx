import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { currentOwner, startSSO, useAuth } from "../lib/query";
import styles from "./Feature.module.css";

export function AccountPanel() {
  const auth = useAuth();
  const client = useQueryClient();
  const security = useQuery({
    queryKey: ["account-security"],
    queryFn: api.accountSecurity,
  });
  const options = useQuery({
    queryKey: ["auth-options"],
    queryFn: api.authOptions,
  });
  const [username, setUsername] = useState<string | null>(null);
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [current, setCurrent] = useState("");
  const [linkPassword, setLinkPassword] = useState("");
  const [validation, setValidation] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const value = security.data;
  const canSetPassword =
    value && (value.local_password_set || value.reauthenticated);
  const change = useMutation({
    mutationFn: async () => {
      const owner = currentOwner();
      await api.saveAccountPassword({
        username: (username ?? value?.local_username ?? "").trim(),
        new_password: password,
        ...(value?.local_password_set ? { current_password: current } : {}),
      });
      setPassword("");
      setConfirmation("");
      setCurrent("");
      if (!owner || owner !== currentOwner()) return false;
      await auth.refresh();
      return true;
    },
    onSuccess: (sameAccount) => {
      if (!sameAccount) return;
      setSaved(true);
      setUsername(null);
      void client.invalidateQueries({ queryKey: ["account-security"] });
    },
    onError: () => {
      void client.invalidateQueries({ queryKey: ["account-security"] });
    },
  });
  const link = useMutation({
    mutationFn: async () => {
      const owner = currentOwner();
      const result = await api.linkAccountSSO(linkPassword);
      setLinkPassword("");
      if (!owner || owner !== currentOwner()) return;
      if (result.login_url !== "/api/v1/auth/oidc/start?reauth=oidc-link")
        throw new Error("The sign-in verification link is unavailable.");
      startSSO();
      window.location.assign(result.login_url);
    },
  });
  const error =
    validation ??
    change.error?.message ??
    link.error?.message ??
    security.error?.message ??
    options.error?.message;
  return (
    <>
      <h1>Account</h1>
      <p className="muted">
        Manage how you sign in. Password and SSO sign-in open the same account
        and notes.
      </p>
      {auth.session && (
        <details className="muted">
          <summary>Account details</summary>
          <p>
            Account ID:{" "}
            <code>{auth.session.user_id ?? auth.session.owner_id}</code>
          </p>
        </details>
      )}
      {security.isPending && <p role="status">Loading sign-in settings…</p>}
      {value && (
        <>
          <h2>Username and password</h2>
          {value.local_password_set && (
            <p>
              Username: <strong>{value.local_username}</strong>
            </p>
          )}
          {!value.local_password_set && (
            <p className="muted">
              Add a username and password to this existing account. Your library
              stays here.
            </p>
          )}
          {canSetPassword ? (
            <form
              onSubmit={(event) => {
                event.preventDefault();
                setSaved(false);
                setValidation(null);
                if (password !== confirmation) {
                  setValidation("The passwords do not match.");
                  return;
                }
                if (new TextEncoder().encode(password).length > 1024) {
                  setValidation(
                    "That password is too long. Use a shorter passphrase.",
                  );
                  return;
                }
                change.mutate();
              }}
            >
              <label className={styles.formField}>
                Username
                <input
                  className="field"
                  value={username ?? value.local_username ?? ""}
                  onChange={(event) => setUsername(event.target.value)}
                  autoComplete="username"
                  autoCapitalize="none"
                  spellCheck={false}
                  minLength={3}
                  maxLength={64}
                  pattern={"[A-Za-z0-9][A-Za-z0-9._\\-]{2,63}"}
                  disabled={change.isPending}
                  required
                />
              </label>
              <p className="muted">
                Use 3–64 letters, numbers, periods, underscores or hyphens. No
                email address is needed. Start with a letter or number.
              </p>
              {value.local_password_set && (
                <label className={styles.formField}>
                  Current password
                  <input
                    className="field"
                    type="password"
                    value={current}
                    onChange={(event) => setCurrent(event.target.value)}
                    autoComplete="current-password"
                    maxLength={1024}
                    disabled={change.isPending}
                    required
                  />
                </label>
              )}
              <label className={styles.formField}>
                New password
                <input
                  className="field"
                  type="password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  autoComplete="new-password"
                  minLength={8}
                  maxLength={1024}
                  disabled={change.isPending}
                  required
                />
              </label>
              <label className={styles.formField}>
                Confirm new password
                <input
                  className="field"
                  type="password"
                  value={confirmation}
                  onChange={(event) => setConfirmation(event.target.value)}
                  autoComplete="new-password"
                  minLength={8}
                  maxLength={1024}
                  disabled={change.isPending}
                  required
                />
              </label>
              <p className="muted">
                At least 8 characters. Saving signs out other browser sessions;
                this browser stays signed in.
              </p>
              <button className="primary" disabled={change.isPending}>
                {change.isPending
                  ? "Saving…"
                  : value.local_password_set
                    ? "Save sign-in settings"
                    : "Add password sign-in"}
              </button>
            </form>
          ) : value.reauth_url ===
            "/api/v1/auth/oidc/start?reauth=local-password" ? (
            <>
              <p className="muted">
                Verify your SSO identity before adding password sign-in to this
                account.
              </p>
              <a
                className="button primary"
                href={value.reauth_url}
                onClick={startSSO}
              >
                Verify with SSO
              </a>
            </>
          ) : (
            <p className="muted">
              Sign-in verification is unavailable. Ask the server operator to
              restore your sign-in provider.
            </p>
          )}
          <h2>Single sign-on</h2>
          {value.oidc_linked ? (
            <p className="muted">SSO is connected to this account.</p>
          ) : options.data?.oidc_enabled && value.local_password_set ? (
            <form
              onSubmit={(event) => {
                event.preventDefault();
                link.mutate();
              }}
            >
              <p className="muted">
                Connect {options.data.label ?? "SSO"} to this account. Verify
                your current password, then sign in with the provider. Accounts
                are never linked by email.
              </p>
              <label className={styles.formField}>
                Password to connect SSO
                <input
                  className="field"
                  type="password"
                  value={linkPassword}
                  onChange={(event) => setLinkPassword(event.target.value)}
                  autoComplete="current-password"
                  maxLength={1024}
                  disabled={link.isPending}
                  required
                />
              </label>
              <button disabled={link.isPending}>
                {link.isPending ? "Verifying…" : "Connect SSO"}
              </button>
            </form>
          ) : (
            <p className="muted">
              SSO is optional and is not configured for this account.
            </p>
          )}
        </>
      )}
      {saved && <p role="status">Sign-in settings saved.</p>}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </>
  );
}
