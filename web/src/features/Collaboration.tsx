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
  type Workspace,
} from "../lib";
import { choiceLabel } from "./format";
import { Dialog } from "./Dialog";
import { ErrorLine } from "./SettingsParts";
import styles from "./Feature.module.css";

type GrantRole = Exclude<AccessGrant["role"], "owner">;

function RoleOptions() {
  useLanguage();
  return (
    <>
      <option value="viewer">{t("collaboration.can_view")}</option>
      <option value="editor">{t("collaboration.can_edit")}</option>
    </>
  );
}

function GrantForm({
  add,
  disabled,
  pending,
  note = false,
}: {
  note?: boolean;
  add: (account: string, role: GrantRole) => void;
  disabled: boolean;
  pending: boolean;
}) {
  useLanguage();
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<GrantRole>("viewer");
  const account = email.trim();
  const ready = !disabled && !pending && !!account;
  return (
    <form
      className={`${styles.formField} ${styles.grantForm}`}
      onSubmit={(event) => {
        event.preventDefault();
        if (ready) add(account, role);
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
          <RoleOptions />
        </select>
      </label>
      {note && account && (
        <div role="status">
          <p>
            {t(
              role === "editor"
                ? "collaboration.preview_editor"
                : "collaboration.preview_viewer",
              { recipient: account },
            )}
          </p>
          <p>{t("collaboration.preview_scope")}</p>
        </div>
      )}
      <button disabled={!ready}>
        {pending ? t("collaboration.adding") : t("collaboration.give_access")}
      </button>
    </form>
  );
}
function Grants({
  grants,
  changeRole,
  remove,
  pending,
}: {
  grants: AccessGrant[];
  changeRole?: (userId: string, role: GrantRole) => void;
  remove?: (userId: string) => void;
  pending: boolean;
}) {
  useLanguage();
  return (
    <ul className={styles.grantList}>
      {grants.map((grant) => {
        const recipient = grant.username || grant.email || grant.user_id;
        return (
          <li key={grant.user_id}>
            <div className={styles.grantIdentity}>
              {grant.display_name || recipient}
              {grant.display_name && (grant.username || grant.email) && (
                <small>{recipient}</small>
              )}
              {(!changeRole || grant.role === "owner") && (
                <small>{choiceLabel(grant.role)}</small>
              )}
            </div>
            {changeRole && grant.role !== "owner" && (
              <select
                className="field"
                aria-label={t("collaboration.access_for_value1", {
                  value1: recipient,
                })}
                disabled={pending}
                value={grant.role}
                onChange={(event) =>
                  changeRole(grant.user_id, event.target.value as GrantRole)
                }
              >
                <RoleOptions />
              </select>
            )}
            {remove && grant.role !== "owner" && (
              <button
                disabled={pending}
                onClick={() => remove(grant.user_id)}
                aria-label={t("collaboration.remove_access_for_value1", {
                  value1: recipient,
                })}
              >
                {t("collaboration.remove_access")}
              </button>
            )}
          </li>
        );
      })}
    </ul>
  );
}

function WorkspaceSelect({
  label,
  workspaces,
  active,
  pending,
  select,
  rail = false,
}: {
  label: string;
  workspaces: Workspace[];
  active?: string;
  pending: boolean;
  select: (workspace: Workspace) => void;
  rail?: boolean;
}) {
  useLanguage();
  return (
    <label className={rail ? styles.railWorkspace : styles.formField}>
      <span className={rail ? "rail-label" : undefined}>{label}</span>
      <select
        className="field"
        aria-label={label}
        value={active ?? ""}
        disabled={pending || !workspaces.length}
        onChange={(event) => {
          const workspace = workspaces.find(
            (space) => space.id === event.target.value,
          );
          if (workspace) select(workspace);
        }}
      >
        {workspaces.map((space) => (
          <option value={space.id} key={space.id}>
            {space.personal ? t("collaboration.personal") : space.name} ·{" "}
            {choiceLabel(space.role)}
          </option>
        ))}
      </select>
    </label>
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
    },
  });
  const change = useMutation({
    mutationFn: switchWorkspace,
    onSuccess: () => navigate("/"),
  });
  const refreshMembers = () =>
    client.invalidateQueries({
      queryKey: ["workspace-members", workspace?.id],
    });
  const add = useMutation({
    mutationFn: ({ email, role }: { email: string; role: GrantRole }) =>
      api.addWorkspaceMember(workspace!.id, email, role),
    onSuccess: refreshMembers,
  });
  const edit = useMutation({
    mutationFn: ({ userId, role }: { userId: string; role: GrantRole }) =>
      api.changeWorkspaceMemberRole(workspace!.id, userId, role),
    onSettled: refreshMembers,
  });
  const remove = useMutation({
    mutationFn: (userId: string) =>
      api.removeWorkspaceMember(workspace!.id, userId),
    onSuccess: refreshMembers,
  });
  const accessPending = add.isPending || edit.isPending || remove.isPending;
  return (
    <>
      <h1>{t("collaboration.spaces")}</h1>
      <p className="muted">
        {t("collaboration.keep_your_own_notes_in_your_personal_space")}
      </p>
      <WorkspaceSelect
        label={t("collaboration.current_space")}
        workspaces={spaces.data?.workspaces ?? []}
        active={session?.workspace_id}
        pending={change.isPending || create.isPending}
        select={(space) => change.mutate(space)}
      />
      {workspace && !workspace.personal && (
        <>
          <div className={styles.between}>
            <h2>{t("collaboration.members")}</h2>
            <Link href="/" className="button">
              {t("app.all_notes")}
            </Link>
          </div>
          <Grants
            grants={members.data?.members ?? []}
            changeRole={
              role === "owner"
                ? (userId, role) => edit.mutate({ userId, role })
                : undefined
            }
            remove={role === "owner" ? (id) => remove.mutate(id) : undefined}
            pending={accessPending}
          />
          {role === "owner" && (
            <GrantForm
              add={(email, role) => add.mutate({ email, role })}
              disabled={edit.isPending || remove.isPending}
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
            maxLength={80}
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
          edit.error ??
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
  const refreshShares = () =>
    client.invalidateQueries({ queryKey: ["note-shares", id] });
  const add = useMutation({
    mutationFn: ({ email, role }: { email: string; role: GrantRole }) =>
      api.shareNote(id, email, role),
    onSuccess: refreshShares,
  });
  const edit = useMutation({
    mutationFn: ({ userId, role }: { userId: string; role: GrantRole }) =>
      api.changeNoteShareRole(id, userId, role),
    onSettled: refreshShares,
  });
  const remove = useMutation({
    mutationFn: (userId: string) => api.unshareNote(id, userId),
    onSuccess: refreshShares,
  });
  const accessPending = add.isPending || edit.isPending || remove.isPending;
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
        changeRole={(userId, role) => edit.mutate({ userId, role })}
        remove={(userId) => remove.mutate(userId)}
        pending={accessPending}
      />
      <GrantForm
        note
        add={(email, role) => add.mutate({ email, role })}
        disabled={
          edit.isPending || remove.isPending || query.isPending || query.isError
        }
        pending={add.isPending}
      />
      <ErrorLine
        error={query.error ?? add.error ?? edit.error ?? remove.error}
      />
    </Dialog>
  );
}

export function SpaceSwitcher({ close }: { close: () => void }) {
  useLanguage();
  const spaces = useQuery({
    queryKey: ["workspaces"],
    queryFn: api.workspaces,
  });
  useOwner();
  const [, navigate] = useLocation();
  const change = useMutation({
    mutationFn: switchWorkspace,
    onSuccess: () => {
      close();
      navigate("/");
    },
    onError: close,
  });
  return spaces.data && spaces.data.workspaces.length > 1 ? (
    <WorkspaceSelect
      rail
      label={t("collaboration.switch_space")}
      workspaces={spaces.data.workspaces}
      active={currentSession()?.workspace_id}
      pending={change.isPending}
      select={(space) => change.mutate(space)}
    />
  ) : null;
}
