import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { currentOwner, useHosted } from "../lib/query";
import type { Session } from "../lib/types";
import styles from "./Feature.module.css";

export function ProfilePanel() {
  const hosted = useHosted();
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
      <h1>Profile</h1>
      <p className={styles.muted}>
        {hosted
          ? "Optional display name for this space. Manage your username, password and optional SSO in Account settings."
          : "Your display name is optional and private to owner settings. It is not a username: you still unlock Spunto with your existing owner password."}
      </p>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          save.mutate();
        }}
      >
        <div className={styles.field}>
          <label htmlFor="owner-display-name">Display name (optional)</label>
          <input
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
        <p className={styles.error} role="alert">
          {error.message}
        </p>
      )}
    </>
  );
}
