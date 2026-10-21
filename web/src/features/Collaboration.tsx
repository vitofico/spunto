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
import { Dialog } from "./Dialog";
import styles from "./Feature.module.css";

function ErrorLine({ error }: { error: Error | null }) {
  return error ? (
    <p className="error" role="alert">
      {error.message}
    </p>
  ) : null;
}
function GrantForm({
  add,
  pending,
}: {
  add: (account: string, role: "editor" | "viewer") => void;
  pending: boolean;
}) {
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
        Username or verified email
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
        Access
        <select
          className="field"
          value={role}
          onChange={(event) => setRole(event.target.value as typeof role)}
        >
          <option value="viewer">Can view</option>
          <option value="editor">Can edit</option>
        </select>
      </label>
      <button disabled={pending || !email.trim()}>
        {pending ? "Adding…" : "Give access"}
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
            <small>{grant.role}</small>
          </div>
          {remove && grant.role !== "owner" && (
            <button
              disabled={pending}
              onClick={() => remove(grant.user_id)}
              aria-label={`Remove access for ${grant.username || grant.email || grant.user_id}`}
            >
              Remove access
            </button>
          )}
        </li>
      ))}
    </ul>
  );
}
export function SpacesPanel() {
  const hosted = useHosted();
  return hosted ? (
    <HostedSpaces />
  ) : (
    <>
      <h1>Spaces</h1>
      <p>
        Your notes are in your personal space. Shared spaces are available on a
        hosted server.
      </p>
    </>
  );
}
function HostedSpaces() {
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
      <h1>Spaces</h1>
      <p className="muted">
        Keep your own notes in your personal space. Create a shared space when a
        group needs the same notes and labels.
      </p>
      <label className={styles.formField}>
        Current space
        <select
          className="field"
          value={workspace?.id ?? session?.workspace_id ?? ""}
          disabled={change.isPending || !spaces.data}
          onChange={(event) => change.mutate(event.target.value)}
        >
          {spaces.data?.workspaces.map((space) => (
            <option value={space.id} key={space.id}>
              {space.personal ? "Personal" : space.name} · {space.role}
            </option>
          ))}
        </select>
      </label>
      {workspace && !workspace.personal && (
        <>
          <h2>Members</h2>
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
      <h2>Create a shared space</h2>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate();
        }}
      >
        <label className={styles.formField}>
          Space name
          <input
            className="field"
            required
            maxLength={120}
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </label>
        <button disabled={create.isPending || !name.trim()}>
          {create.isPending ? "Creating…" : "Create shared space"}
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
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const hosted = useHosted();
  const role = useWorkspaceRole();
  return hosted && role === "owner" ? (
    <>
      <button ref={trigger} className="button" onClick={() => setOpen(true)}>
        Share
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
      title="Share this note"
      opener={opener}
      onClose={close}
      footer={<button onClick={close}>Done</button>}
    >
      <p>
        Give an existing account access to this note. Other notes and labels
        stay in their space.
      </p>
      <Grants
        grants={query.data?.shares ?? []}
        remove={(userId) => remove.mutate(userId)}
        pending={remove.isPending}
      />
      <GrantForm
        add={(email, role) => add.mutate({ email, role })}
        pending={add.isPending || query.isPending || query.isError}
      />
      <ErrorLine error={query.error ?? add.error ?? remove.error} />
    </Dialog>
  );
}

export function ActiveSharedSpace() {
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
      {workspace.name} · {workspace.role}
    </Link>
  ) : null;
}
