import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useLocation } from "wouter";
import { api } from "../lib/api";
import { currentOwner, useAuth, useHosted } from "../lib/query";
import { draftStorageKey } from "../lib/drafts";
import type { Session } from "../lib/types";
import { Dialog } from "./Dialog";
import { useConfirm } from "../components/core/ConfirmDialog";
import { plural } from "./format";
import styles from "./Feature.module.css";

export function ProfilePanel({ onShowGuide }: { onShowGuide?: () => void }) {
  const hosted = useHosted();
  return (
    <>
      <h1>Profile</h1>
      {hosted ? <DisplayName /> : <Password />}
      {onShowGuide && (
        <>
          <h2>Guide</h2>
          <p className="muted">
            The four first-run steps: writing, labels, search and settings.
          </p>
          <button onClick={onShowGuide}>Show guide</button>
        </>
      )}
      <SignOut />
    </>
  );
}

/** Hosted spaces keep the optional display name; a single-owner install has no use for it. */
function DisplayName() {
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
        Optional display name for this space. Manage your username, password and
        optional SSO in <Link href="/settings/account">Account settings</Link>.
      </p>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          save.mutate();
        }}
      >
        <div className={styles.formField}>
          <label htmlFor="owner-display-name">Display name (optional)</label>
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
            Up to 80 characters. Leave blank to clear your name.
          </small>
        </div>
        <button
          className="primary"
          disabled={!query.data || save.isPending || name.trim() === saved}
        >
          {save.isPending ? "Saving…" : "Save profile"}
        </button>
      </form>
      {query.isPending && <p role="status">Loading profile…</p>}
      {save.isSuccess && <p role="status">Profile saved.</p>}
      {error && (
        <p className="error" role="alert">
          {error.message}
        </p>
      )}
    </>
  );
}

function Password() {
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
      <h2>Password</h2>
      {query.data?.password_managed ? (
        <p className={styles.secret}>
          Set in the environment. Change <code>SPUNTO_OWNER_PASSWORD</code> or{" "}
          <code>SPUNTO_OWNER_PASSWORD_HASH</code> and restart the server.
        </p>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            setConfirm(true);
          }}
        >
          <label className={styles.formField}>
            New password
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
              At least 12 characters. Changing it signs out your web sessions;
              paired devices and access tokens keep working.
            </small>
          </label>
          <button disabled={!password}>Change password…</button>
        </form>
      )}
      {!confirm && change.error && (
        <p className="error" role="alert">
          {change.error.message}
        </p>
      )}
      {confirm && (
        <Dialog
          title="Change your password?"
          onClose={() => setConfirm(false)}
          footer={
            <>
              <button onClick={() => setConfirm(false)}>Cancel</button>
              <button
                className="primary"
                disabled={change.isPending}
                onClick={() => change.mutate()}
              >
                Change password
              </button>
            </>
          }
        >
          <p>
            Your web sessions will be signed out. Device and access tokens keep
            their current access.
          </p>
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

/** Drafts kept on this browser that were never saved; signing out erases them. */
function unsentDrafts() {
  const owner = currentOwner();
  if (!owner) return 0;
  const prefix = draftStorageKey(owner, "");
  let count = 0;
  try {
    for (let index = 0; index < localStorage.length; index++) {
      const key = localStorage.key(index);
      if (!key?.startsWith(prefix)) continue;
      const draft = JSON.parse(localStorage.getItem(key) ?? "null") as {
        body?: unknown;
      } | null;
      if (typeof draft?.body === "string" && draft.body.trim()) count++;
    }
  } catch {
    /* Unreadable storage holds nothing to warn about. */
  }
  return count;
}

function SignOut() {
  const auth = useAuth();
  const [, navigate] = useLocation();
  const [confirm, dialog] = useConfirm();
  const [pending, setPending] = useState(false);
  const signOut = async () => {
    const drafts = unsentDrafts();
    if (
      drafts &&
      !(await confirm({
        title: "Sign out?",
        body: `${plural(drafts, "unsent draft")} on this browser will be erased.`,
        confirmLabel: "Sign out and erase",
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
      <h2>Sign out</h2>
      <p className="muted">
        Signs this browser out. Other devices stay signed in.
      </p>
      <button disabled={pending} onClick={() => void signOut()}>
        {pending ? "Signing out…" : "Sign out"}
      </button>
      {dialog}
    </>
  );
}
