import { t, useLanguage } from "../lib/i18n";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useLocation } from "wouter";
import { api } from "../lib/api";
import { currentOwner, useAuth, useHosted } from "../lib/query";
import { keptDrafts } from "../lib/drafts";
import type { Session } from "../lib/types";
import { Dialog } from "./Dialog";
import { useConfirm } from "../components/core/ConfirmDialog";
import { plural } from "./format";
import styles from "./Feature.module.css";

export function ProfilePanel({
  onShowGuide,
}: {
  onShowGuide?: (trigger: HTMLElement) => void;
}) {
  useLanguage();
  const hosted = useHosted();
  return (
    <>
      <h1>{t("profilepanel.profile")}</h1>
      {hosted ? <DisplayName /> : <Password />}
      {onShowGuide && (
        <>
          <h2>{t("profilepanel.guide")}</h2>
          <p className="muted">
            {t("profilepanel.the_four_first_run_steps_writing_labels_search")}
          </p>
          <button onClick={(event) => onShowGuide(event.currentTarget)}>
            {t("profilepanel.show_guide")}
          </button>
        </>
      )}
      <SignOut />
    </>
  );
}

/** Hosted spaces keep the optional display name; a single-owner install has no use for it. */
function DisplayName() {
  useLanguage();
  const query = useQuery({ queryKey: ["settings"], queryFn: api.settings });
  const client = useQueryClient();
  const [draft, setDraft] = useState<string | null>(null);
  const saved = query.data?.owner_display_name ?? "";
  const name = draft ?? saved;
  const save = useMutation({
    mutationFn: async () => {
      const owner = currentOwner();
      const session = client.getQueryData<Session>(["session"])?.credential_id;
      const data = await api.saveSettings({ owner_display_name: name.trim() });
      return { data, owner, session };
    },
    onSuccess: ({ data, owner, session }) => {
      if (
        !owner ||
        owner !== currentOwner() ||
        session !== client.getQueryData<Session>(["session"])?.credential_id
      )
        return;
      client.setQueryData(["settings"], data);
      setDraft(null);
    },
  });
  const error = save.error ?? query.error;
  return (
    <>
      <p className="muted">
        {t("profilepanel.account_explanation")}{" "}
        <Link href="/settings/account">
          {t("profilepanel.account_settings")}
        </Link>
        .
      </p>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          save.mutate();
        }}
      >
        <div className={styles.formField}>
          <label htmlFor="owner-display-name">
            {t("profilepanel.display_name_optional")}
          </label>
          <input
            className="field"
            id="owner-display-name"
            value={name}
            maxLength={80}
            autoComplete="off"
            disabled={!query.data || save.isPending}
            onChange={(event) => {
              setDraft(event.target.value);
              save.reset();
            }}
            aria-describedby="display-name-help"
          />
          <small id="display-name-help">
            {t("profilepanel.up_to_80_characters_leave_blank_to_clear")}
          </small>
        </div>
        <button
          className="primary"
          disabled={!query.data || save.isPending || name.trim() === saved}
        >
          {save.isPending ? t("app.saving") : t("profilepanel.save_profile")}
        </button>
      </form>
      {query.isPending && (
        <p role="status">{t("profilepanel.loading_profile")}</p>
      )}
      {save.isSuccess && <p role="status">{t("profilepanel.profile_saved")}</p>}
      {error && (
        <p className="error" role="alert">
          {error.message}
        </p>
      )}
    </>
  );
}

function Password() {
  useLanguage();
  const query = useQuery({
    queryKey: ["session"],
    queryFn: () => api.session(),
  });
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState(false);
  const change = useMutation({
    mutationFn: () => api.changePassword(password),
    onSuccess: () => {
      setPassword("");
      location.assign("/");
    },
  });
  return (
    <>
      <h2>{t("profilepanel.password")}</h2>
      {query.data?.password_managed ? (
        <p className={styles.secret}>{t("profilepanel.managed_password")}</p>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            setConfirm(true);
          }}
        >
          <label className={styles.formField}>
            {t("accountpanel.new_password")}
            <input
              className="field"
              type="password"
              value={password}
              autoComplete="new-password"
              minLength={12}
              maxLength={1024}
              required
              onChange={(e) => setPassword(e.target.value)}
            />
            <small>
              {t("profilepanel.at_least_12_characters_changing_it_signs_out")}
            </small>
          </label>
          <button disabled={!password}>
            {t("profilepanel.change_password")}
          </button>
        </form>
      )}
      {!confirm && change.error && (
        <p className="error" role="alert">
          {change.error.message}
        </p>
      )}
      {confirm && (
        <Dialog
          title={t("profilepanel.change_your_password")}
          onClose={() => setConfirm(false)}
          footer={
            <>
              <button onClick={() => setConfirm(false)}>
                {t("profilepanel.cancel")}
              </button>
              <button
                className="primary"
                disabled={change.isPending}
                onClick={() => change.mutate()}
              >
                {t("profilepanel.change_password_2")}
              </button>
            </>
          }
        >
          <p>{t("profilepanel.your_web_sessions_will_be_signed_out_device")}</p>
          {change.error && (
            <p className="error" role="alert">
              {change.error.message}
            </p>
          )}
        </Dialog>
      )}
    </>
  );
}

/** Notes kept only on this browser, drafts and captures waiting to sync; signing out erases them. */
function unsentNotes() {
  const owner = currentOwner();
  try {
    return owner ? keptDrafts(localStorage, owner).unsent : 0;
  } catch {
    /* Unreadable storage holds nothing to warn about. */
    return 0;
  }
}

function SignOut() {
  useLanguage();
  const auth = useAuth();
  const [, navigate] = useLocation();
  const [confirm, dialog] = useConfirm();
  const [pending, setPending] = useState(false);
  const signOut = async (opener: HTMLElement) => {
    const unsent = unsentNotes();
    if (
      unsent &&
      !(await confirm({
        opener,
        title: t("profilepanel.sign_out"),
        body: t("profilepanel.value1_on_this_browser_will_be_erased", {
          value1: plural(unsent, "unsent_note"),
        }),
        confirmLabel: t("profilepanel.sign_out_and_erase"),
        danger: true,
      }))
    )
      return;
    setPending(true);
    try {
      await auth.logout();
      navigate("/", { replace: true });
    } catch {
      setPending(false);
    }
  };
  return (
    <>
      <h2>{t("profilepanel.sign_out_2")}</h2>
      <p className="muted">
        {t("profilepanel.signs_this_browser_out_other_devices_stay_signed")}
      </p>
      <button disabled={pending} onClick={(e) => void signOut(e.currentTarget)}>
        {pending ? t("profilepanel.signing_out") : t("profilepanel.sign_out_2")}
      </button>
      {dialog}
    </>
  );
}
