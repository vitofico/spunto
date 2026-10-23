import { t, useLanguage } from "../lib/i18n";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { currentOwner, startSSO, useAuth } from "../lib/query";
import styles from "./Feature.module.css";

export function AccountPanel() {
  useLanguage();
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
        throw new Error(
          t("accountpanel.the_sign_in_verification_link_is_unavailable"),
        );
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
      <h1>{t("accountpanel.account")}</h1>
      <p className="muted">
        {t("accountpanel.manage_how_you_sign_in_password_and_sso")}
      </p>
      {auth.session && (
        <details className="muted">
          <summary>{t("accountpanel.account_details")}</summary>
          <p>
            {t("accountpanel.account_id")}{" "}
            <code>{auth.session.user_id ?? auth.session.owner_id}</code>
          </p>
        </details>
      )}
      {security.isPending && (
        <p role="status">{t("accountpanel.loading_sign_in_settings")}</p>
      )}
      {value && (
        <>
          <h2>{t("accountpanel.username_and_password")}</h2>
          {value.local_password_set && (
            <p>
              {t("accountpanel.username")}
              <strong>{value.local_username}</strong>
            </p>
          )}
          {!value.local_password_set && (
            <p className="muted">
              {t("accountpanel.add_a_username_and_password_to_this_existing")}
            </p>
          )}
          {canSetPassword ? (
            <form
              onSubmit={(event) => {
                event.preventDefault();
                setSaved(false);
                setValidation(null);
                if (password !== confirmation) {
                  setValidation(t("accountpanel.the_passwords_do_not_match"));
                  return;
                }
                if (new TextEncoder().encode(password).length > 1024) {
                  setValidation(
                    t("accountpanel.that_password_is_too_long_use_a_shorter"),
                  );
                  return;
                }
                change.mutate();
              }}
            >
              <label className={styles.formField}>
                {t("accountpanel.username_2")}
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
                {t(
                  "accountpanel.use_3_64_letters_numbers_periods_underscores_or",
                )}
              </p>
              {value.local_password_set && (
                <label className={styles.formField}>
                  {t("accountpanel.current_password")}
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
                {t("accountpanel.new_password")}
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
                {t("accountpanel.confirm_new_password")}
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
                {t("accountpanel.at_least_8_characters_saving_signs_out_other")}
              </p>
              <button className="primary" disabled={change.isPending}>
                {change.isPending
                  ? t("app.saving")
                  : value.local_password_set
                    ? t("accountpanel.save_sign_in_settings")
                    : t("accountpanel.add_password_sign_in")}
              </button>
            </form>
          ) : value.reauth_url ===
            "/api/v1/auth/oidc/start?reauth=local-password" ? (
            <>
              <p className="muted">
                {t(
                  "accountpanel.verify_your_sso_identity_before_adding_password_sign",
                )}
              </p>
              <a
                className="button primary"
                href={value.reauth_url}
                onClick={startSSO}
              >
                {t("accountpanel.verify_with_sso")}
              </a>
            </>
          ) : (
            <p className="muted">
              {t(
                "accountpanel.sign_in_verification_is_unavailable_ask_the_server",
              )}
            </p>
          )}
          <h2>{t("accountpanel.single_sign_on")}</h2>
          {value.oidc_linked ? (
            <p className="muted">
              {t("accountpanel.sso_is_connected_to_this_account")}
            </p>
          ) : options.data?.oidc_enabled && value.local_password_set ? (
            <form
              onSubmit={(event) => {
                event.preventDefault();
                link.mutate();
              }}
            >
              <p className="muted">
                {t("accountpanel.connect_provider", {
                  provider: options.data.label ?? "SSO",
                })}
              </p>
              <label className={styles.formField}>
                {t("accountpanel.password_to_connect_sso")}
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
                {link.isPending
                  ? t("accountpanel.verifying")
                  : t("accountpanel.connect_sso")}
              </button>
            </form>
          ) : (
            <p className="muted">
              {t("accountpanel.sso_is_optional_and_is_not_configured_for")}
            </p>
          )}
        </>
      )}
      {saved && <p role="status">{t("accountpanel.sign_in_settings_saved")}</p>}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </>
  );
}
