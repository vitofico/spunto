import { t, useLanguage } from "../lib/i18n";
import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useLocation } from "wouter";
import {
  api,
  currentSession,
  switchWorkspace,
  useHosted,
  useOwner,
  useWorkspaceRole,
  type AccessGrant,
} from "../lib";
import { choiceLabel } from "./format";
import { Dialog } from "./Dialog";
import { ErrorLine } from "./SettingsParts";
import styles from "./Feature.module.css";

function GrantForm({
  add,
  pending,
  note = false,
}: {
  note?: boolean;
  add: (account: string, role: "editor" | "viewer") => void;
  pending: boolean;
}) {
  useLanguage();
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<"editor" | "viewer">("viewer");
  return (
    <form
      className={`${styles.formField} ${styles.grantForm}`}
      onSubmit={(event) => {
        event.preventDefault();
        add(email.trim(), role);
      }}
    >
      <label>
        {t("collaboration.username_or_verified_email")}
        <input
          className="field"
          type="text"
          required
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
        />
      </label>
      <label>
        {t("collaboration.access")}
        <select
          className="field"
          value={role}
          onChange={(event) => setRole(event.target.value as typeof role)}
        >
          <option value="viewer">{t("collaboration.can_view")}</option>
          <option value="editor">{t("collaboration.can_edit")}</option>
        </select>
      </label>
      {note && email.trim() && (
        <div role="status">
          <p>
            {t(
              role === "editor"
                ? "collaboration.preview_editor"
                : "collaboration.preview_viewer",
              { recipient: email.trim() },
            )}
          </p>
          <p>{t("collaboration.preview_scope")}</p>
        </div>
      )}
      <button disabled={pending || !email.trim()}>
        {pending ? t("collaboration.adding") : t("collaboration.give_access")}
      </button>
    </form>
  );
}
function Grants({
  grants,
  remove,
  pending,
}: {
  grants: AccessGrant[];
  remove?: (userId: string) => void;
  pending: boolean;
}) {
  useLanguage();
  return (
    <ul className={styles.grantList}>
      {grants.map((grant) => (
        <li key={grant.user_id}>
          <div className={styles.grantIdentity}>
            {grant.display_name ||
              grant.username ||
              grant.email ||
              grant.user_id}
            {grant.display_name && (grant.username || grant.email) && (
              <small>{grant.username || grant.email}</small>
            )}
            <small>{choiceLabel(grant.role)}</small>
          </div>
          {remove && grant.role !== "owner" && (
            <button
              disabled={pending}
              onClick={() => remove(grant.user_id)}
              aria-label={t("collaboration.remove_access_for_value1", {
                value1: grant.username || grant.email || grant.user_id,
              })}
            >
              {t("collaboration.remove_access")}
            </button>
          )}
        </li>
      ))}
    </ul>
  );
}
export function SpacesPanel() {
  useLanguage();
  const hosted = useHosted();
  return hosted ? (
    <HostedSpaces />
  ) : (
    <>
      <h1>{t("collaboration.spaces")}</h1>
      <p>{t("collaboration.your_notes_are_in_your_personal_space_shared")}</p>
    </>
  );
}
function HostedSpaces() {
  useLanguage();
  useOwner();
  const session = currentSession();
  const role = useWorkspaceRole();
  const client = useQueryClient();
  const [, navigate] = useLocation();
  const [name, setName] = useState("");
  const spaces = useQuery({
    queryKey: ["workspaces"],
    queryFn: api.workspaces,
  });
  const workspace = spaces.data?.workspaces.find(
    (space) => space.id === session?.workspace_id,
  );
  const members = useQuery({
    queryKey: ["workspace-members", workspace?.id],
    queryFn: () => api.workspaceMembers(workspace!.id),
    enabled: !!workspace && !workspace.personal,
  });
  const create = useMutation({
    mutationFn: () => api.createWorkspace(name.trim()),
    onSuccess: async (value) => {
      setName("");
      await switchWorkspace(value);
      navigate("/");
    },
  });
  const change = useMutation({
    mutationFn: async (id: string) => {
      const value = spaces.data?.workspaces.find((space) => space.id === id);
      if (value) {
        await switchWorkspace(value);
        navigate("/");
      }
    },
  });
  const add = useMutation({
    mutationFn: ({
      email,
      role,
    }: {
      email: string;
      role: "editor" | "viewer";
    }) => api.addWorkspaceMember(workspace!.id, email, role),
    onSuccess: () =>
      client.invalidateQueries({ queryKey: ["workspace-members"] }),
  });
  const remove = useMutation({
    mutationFn: (userId: string) =>
      api.removeWorkspaceMember(workspace!.id, userId),
    onSuccess: () =>
      client.invalidateQueries({ queryKey: ["workspace-members"] }),
  });
  return (
    <>
      <h1>{t("collaboration.spaces")}</h1>
      <p className="muted">
        {t("collaboration.keep_your_own_notes_in_your_personal_space")}
      </p>
      <label className={styles.formField}>
        {t("collaboration.current_space")}
        <select
          className="field"
          value={workspace?.id ?? session?.workspace_id ?? ""}
          disabled={change.isPending || !spaces.data}
          onChange={(event) => change.mutate(event.target.value)}
        >
          {spaces.data?.workspaces.map((space) => (
            <option value={space.id} key={space.id}>
              {space.personal ? t("collaboration.personal") : space.name} ·{" "}
              {choiceLabel(space.role)}
            </option>
          ))}
        </select>
      </label>
      {workspace && !workspace.personal && (
        <>
          <h2>{t("collaboration.members")}</h2>
          <Grants
            grants={members.data?.members ?? []}
            remove={role === "owner" ? (id) => remove.mutate(id) : undefined}
            pending={remove.isPending}
          />
          {role === "owner" && (
            <GrantForm
              add={(email, role) => add.mutate({ email, role })}
              pending={add.isPending}
            />
          )}
        </>
      )}
      <h2>{t("collaboration.create_a_shared_space")}</h2>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate();
        }}
      >
        <label className={styles.formField}>
          {t("collaboration.space_name")}
          <input
            className="field"
            required
            maxLength={120}
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </label>
        <button disabled={create.isPending || !name.trim()}>
          {create.isPending
            ? t("collaboration.creating")
            : t("collaboration.create_shared_space")}
        </button>
      </form>
      <ErrorLine
        error={
          spaces.error ??
          members.error ??
          create.error ??
          change.error ??
          add.error ??
          remove.error
        }
      />
    </>
  );
}
export function ShareNote({ id }: { id: string }) {
  useLanguage();
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const hosted = useHosted();
  const role = useWorkspaceRole();
  return hosted && role === "owner" ? (
    <>
      <button ref={trigger} className="button" onClick={() => setOpen(true)}>
        {t("collaboration.share")}
      </button>
      {open && (
        <NoteShareDialog
          id={id}
          opener={trigger.current}
          close={() => setOpen(false)}
        />
      )}
    </>
  ) : null;
}
function NoteShareDialog({
  id,
  opener,
  close,
}: {
  id: string;
  opener: HTMLElement | null;
  close: () => void;
}) {
  useLanguage();
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ["note-shares", id],
    queryFn: () => api.noteShares(id),
  });
  const add = useMutation({
    mutationFn: ({
      email,
      role,
    }: {
      email: string;
      role: "editor" | "viewer";
    }) => api.shareNote(id, email, role),
    onSuccess: () =>
      client.invalidateQueries({ queryKey: ["note-shares", id] }),
  });
  const remove = useMutation({
    mutationFn: (userId: string) => api.unshareNote(id, userId),
    onSuccess: () =>
      client.invalidateQueries({ queryKey: ["note-shares", id] }),
  });
  return (
    <Dialog
      title={t("collaboration.share_this_note")}
      opener={opener}
      onClose={close}
      footer={<button onClick={close}>{t("collaboration.done")}</button>}
    >
      <p>{t("collaboration.give_an_existing_account_access_to_this_note")}</p>
      <Grants
        grants={query.data?.shares ?? []}
        remove={(userId) => remove.mutate(userId)}
        pending={remove.isPending}
      />
      <GrantForm
        note
        add={(email, role) => add.mutate({ email, role })}
        pending={add.isPending || query.isPending || query.isError}
      />
      <ErrorLine error={query.error ?? add.error ?? remove.error} />
    </Dialog>
  );
}

export function ActiveSharedSpace() {
  useLanguage();
  const spaces = useQuery({
    queryKey: ["workspaces"],
    queryFn: api.workspaces,
  });
  useOwner();
  const workspace = spaces.data?.workspaces.find(
    (space) => space.id === currentSession()?.workspace_id,
  );
  return workspace && !workspace.personal ? (
    <Link href="/settings/spaces" className="muted">
      {workspace.name} · {choiceLabel(workspace.role)}
    </Link>
  ) : null;
}
