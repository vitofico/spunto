import { useCallback, useEffect, useState } from "react";
import { Link, useSearch as useLocationSearch } from "wouter";
import { useQuery } from "@tanstack/react-query";
import {
  api,
  useDraftEditor,
  useHosted,
  useOwner,
  type SharedNote,
} from "../lib";
import { useDocumentTitle } from "../lib/useDocumentTitle";
import { NoteBody } from "../components/core/NoteCard";

function sharedPath(id: string, workspaceId: string) {
  return `/shared/n/${encodeURIComponent(id)}?${new URLSearchParams({ workspace_id: workspaceId })}`;
}
function SharedEditor({ note }: { note: SharedNote }) {
  const update = useCallback(
    (id: string, body: string, version: number) =>
      api.updateSharedNote(id, body, version, note.workspace_id),
    [note.workspace_id],
  );
  const draft = useDraftEditor({
    note,
    draftKey: "shared-note:" + note.workspace_id + ":" + note.id,
    update,
  });
  const [latest, setLatest] = useState<SharedNote | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!draft.conflict) {
      setLatest(null);
      return;
    }
    let cancelled = false;
    void api
      .sharedNote(note.id, note.workspace_id)
      .then((value) => {
        if (!cancelled) setLatest(value);
      })
      .catch((error: Error) => {
        if (!cancelled) setError(error.message);
      });
    return () => {
      cancelled = true;
    };
  }, [draft.conflict, note.id, note.workspace_id]);
  return (
    <>
      <label className="stack">
        Note text
        <textarea
          className="field"
          rows={16}
          value={draft.body}
          onChange={(event) => draft.setBody(event.target.value)}
        />
      </label>
      {draft.conflict && (
        <section className="card stack" aria-label="Review conflicting edits">
          <h2>Review conflicting edits</h2>
          <p>
            Your edit was preserved. Review the latest saved note before saving
            your draft again.
          </p>
          <Link href={sharedPath(draft.conflict, note.workspace_id)}>
            Open preserved edit
          </Link>
          {latest && (
            <>
              <NoteBody note={latest} />
              <button
                className="button"
                disabled={busy}
                onClick={async () => {
                  setBusy(true);
                  setError(null);
                  try {
                    const value = await api.sharedNote(
                      note.id,
                      note.workspace_id,
                    );
                    if (value.version !== latest.version) {
                      setLatest(value);
                      setError(
                        "The saved note changed again. Review its latest text before saving.",
                      );
                      return;
                    }
                    if (draft.resumeReviewed(value)) await draft.save();
                  } catch (error) {
                    setError(
                      error instanceof Error
                        ? error.message
                        : "Could not save your review.",
                    );
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                Save reviewed text
              </button>
            </>
          )}
        </section>
      )}
      <button
        className="button primary"
        disabled={draft.state === "saving" || !!draft.conflict || !draft.dirty}
        onClick={() => void draft.save()}
      >
        Save
      </button>
      <p role="status">
        {draft.state === "saving"
          ? "Saving…"
          : draft.dirty
            ? "Unsaved changes on this device"
            : "Saved"}
      </p>
      {(draft.error || error) && (
        <p className="error" role="alert">
          {error ?? draft.error?.message}
        </p>
      )}
      {draft.storageFailed && (
        <p className="error" role="alert">
          Device storage is unavailable. Keep this tab open until saved.
        </p>
      )}
    </>
  );
}
function SharedDetail({
  id,
  workspaceId,
}: {
  id: string;
  workspaceId?: string;
}) {
  const owner = useOwner();
  const query = useQuery({
    queryKey: ["shared-note", workspaceId, id],
    queryFn: () => api.sharedNote(id, workspaceId),
  });
  useDocumentTitle(query.data?.title || "Shared note");
  return (
    <>
      <Link href="/shared">← Shared with me</Link>
      {query.isPending ? (
        <p>Opening note…</p>
      ) : query.error ? (
        <p className="error" role="alert">
          {query.error.message}
        </p>
      ) : (
        query.data && (
          <>
            <h1 className="page-title">{query.data.title || "Shared note"}</h1>
            <p className="muted">
              Shared by {query.data.shared_by} ·{" "}
              {query.data.share_role === "editor" ? "Can edit" : "Can view"}
            </p>
            {query.data.share_role === "viewer" ? (
              <NoteBody note={query.data} />
            ) : (
              <SharedEditor
                key={owner + ":" + query.data.workspace_id + ":" + id}
                note={query.data}
              />
            )}
          </>
        )
      )}
    </>
  );
}
export default function SharedNotesPage({ id }: { id?: string }) {
  const search = useLocationSearch();
  const workspaceId =
    new URLSearchParams(search).get("workspace_id") ?? undefined;
  return id ? (
    <SharedDetail id={id} workspaceId={workspaceId} />
  ) : (
    <SharedList />
  );
}
function SharedList() {
  const hosted = useHosted();
  const query = useQuery({
    queryKey: ["shared-notes"],
    queryFn: api.sharedNotes,
    enabled: hosted,
  });
  useDocumentTitle("Shared with me");
  return (
    <>
      <h1 className="page-title">Shared with me</h1>
      {!hosted ? (
        <p className="muted">
          This Spunto has one owner, so there is nothing shared here. Sharing
          between accounts is part of hosted Spunto.
        </p>
      ) : query.isPending ? (
        <p>Opening shared notes…</p>
      ) : query.error ? (
        <p className="error" role="alert">
          {query.error.message}
        </p>
      ) : !query.data?.notes.length ? (
        <p className="muted">
          Notes shared with your account will appear here.
        </p>
      ) : (
        query.data.notes.map((note) => (
          <article className="entry" key={note.workspace_id + ":" + note.id}>
            <Link href={sharedPath(note.id, note.workspace_id)}>
              {note.title || "Shared note"}
            </Link>
            <p className="muted">
              Shared by {note.shared_by} ·{" "}
              {note.share_role === "editor" ? "Can edit" : "Can view"}
            </p>
            <NoteBody note={note} />
          </article>
        ))
      )}
    </>
  );
}
