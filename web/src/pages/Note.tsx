import { useEffect, useState } from "react";
import { Link, useLocation } from "wouter";
import {
  useNote,
  useDraftEditor,
  useFlags,
  useHistory,
  useRestoreRevision,
  api,
  queryClient,
  type Note,
  useWorkspaceRole,
} from "../lib";
import { ShareNote } from "../features/Collaboration";
import { Editor } from "../components/core/Editor";
import { NoteBody, toggleRenderedCheckbox } from "../components/core/NoteCard";
import { SuggestionLine, NotePanel } from "../features/Slots";
function NoteEditor({ note }: { note: Note }) {
  const [editing, setEditing] = useState(false);
  const [, navigate] = useLocation();
  const draft = useDraftEditor({ note, draftKey: "note:" + note.id });
  const [reviewBase, setReviewBase] = useState<Note | null>(null);
  const [reviewError, setReviewError] = useState<string | null>(null);
  const [reviewBusy, setReviewBusy] = useState(false);
  useEffect(() => {
    if (!draft.conflict) {
      setReviewBase(null);
      return;
    }
    let cancelled = false;
    setReviewBase(null);
    void api
      .note(note.id)
      .then((latest) => {
        if (!cancelled) setReviewBase(latest);
      })
      .catch((error) => {
        if (!cancelled) setReviewError(error.message);
      });
    return () => {
      cancelled = true;
    };
  }, [draft.conflict, note.id]);
  const flags = useFlags();
  const history = useHistory(note.id);
  const restore = useRestoreRevision();
  return (
    <div className="note-page">
      <Link href="/">← All notes</Link>
      <header className="note-header">
        <time dateTime={note.created_at}>
          {new Date(note.created_at).toLocaleString(undefined, {
            dateStyle: "long",
            timeStyle: "short",
          })}
        </time>
        <div className="row">
          {note.private && <span>Private</span>}
          {note.origin === "agent" && <span>via agent</span>}
          {note.machine && (
            <span>✧ Written by {note.source.ref ?? "a plugin"}</span>
          )}
          <ShareNote id={note.id} />
          <button
            className="button"
            data-edit-note
            onClick={() => setEditing(!editing)}
          >
            {editing ? "Read" : "Edit"}
          </button>
        </div>
      </header>
      {note.conflict_of && (
        <p className="notice">
          Two edits couldn’t be combined. Your text is safe in this copy.{" "}
          <Link href={"/n/" + note.conflict_of}>Open the original</Link>
        </p>
      )}
      {draft.conflict && (
        <p className="notice">
          Your earlier save is preserved on the server. The draft below may
          contain later text kept only on this device.{" "}
          <Link href={"/n/" + draft.conflict}>Open the preserved note</Link>
        </p>
      )}
      {draft.conflict && (
        <section className="card stack" aria-label="Review conflicting edits">
          <h2 className="page-title">Latest saved text</h2>
          {reviewBase ? (
            <>
              <NoteBody note={reviewBase} />
              <p className="muted">
                Review the saved text above and your local draft below. Saving
                reviewed text replaces the original note and keeps its history.
              </p>
              <button
                className="button primary"
                disabled={reviewBusy}
                onClick={async () => {
                  setReviewBusy(true);
                  setReviewError(null);
                  try {
                    const latest = await api.note(note.id);
                    if (latest.version !== reviewBase.version) {
                      setReviewBase(latest);
                      setReviewError(
                        "The saved note changed again. Review its latest text before saving.",
                      );
                      return;
                    }
                    if (!draft.resumeReviewed(latest)) {
                      setReviewError(
                        "Review could not resume yet. Keep your draft open and try again.",
                      );
                      return;
                    }
                    await draft.save();
                  } catch (error) {
                    setReviewError(
                      error instanceof Error
                        ? error.message
                        : "Could not save reviewed text.",
                    );
                  } finally {
                    setReviewBusy(false);
                  }
                }}
              >
                Save reviewed text
              </button>
            </>
          ) : (
            <div>
              <p className="muted">Opening latest saved text…</p>
              {reviewError && (
                <button
                  className="button"
                  onClick={() => {
                    setReviewError(null);
                    void api
                      .note(note.id)
                      .then(setReviewBase)
                      .catch((error) => setReviewError(error.message));
                  }}
                >
                  Try again
                </button>
              )}
            </div>
          )}
          {reviewError && (
            <p className="error" role="alert">
              {reviewError}
            </p>
          )}
        </section>
      )}
      {editing || draft.conflict ? (
        <Editor
          note
          body={draft.body}
          setBody={draft.setBody}
          onSave={() =>
            void draft.save().then((saved) => {
              if (saved) setEditing(false);
            })
          }
          upload={draft.uploadAttachment}
          busy={draft.state === "saving"}
          saveDisabled={!!draft.conflict}
          error={draft.error?.message}
          autoFocus
        />
      ) : (
        <div
          onClick={(e) => {
            if (!(e.target as HTMLElement).closest("a,input,button"))
              setEditing(true);
          }}
        >
          <NoteBody
            note={note}
            onCheckbox={(offset) => {
              draft.setBody(toggleRenderedCheckbox(draft.body, offset));
              void draft.save();
            }}
          />
        </div>
      )}
      <p className="saved-line" role="status">
        {draft.state === "saving"
          ? "Saving…"
          : draft.state === "error"
            ? "Can’t reach the server. Your changes are kept on this device."
            : draft.dirty
              ? "Unsaved changes on this device"
              : "Saved"}
      </p>
      {draft.storageFailed && (
        <p className="error" role="alert">
          Device storage is full or unavailable; keep this tab open until saved.
        </p>
      )}
      <SuggestionLine note={note} />
      <div className="note-actions">
        <button
          className="button"
          onClick={() =>
            flags.mutate({ id: note.id, flags: { pinned: !note.pinned } })
          }
        >
          {note.pinned ? "Unpin" : "Pin"}
        </button>
        <button
          className="button"
          onClick={() =>
            flags.mutate({ id: note.id, flags: { private: !note.private } })
          }
        >
          {note.private ? "Make public to configured tools" : "Make private"}
        </button>
        <button
          className="button"
          onClick={() =>
            flags.mutate({ id: note.id, flags: { archived: !note.archived } })
          }
        >
          {note.archived ? "Unarchive" : "Archive"}
        </button>
        <button
          className="button"
          onClick={() =>
            flags.mutate({ id: note.id, flags: { trashed: !note.trashed } })
          }
        >
          {note.trashed ? "Restore" : "Move to trash"}
        </button>
        {note.trashed && (
          <button
            className="button danger"
            onClick={async () => {
              if (confirm("Delete this note forever? This cannot be undone.")) {
                await api.deleteNote(note.id);
                await queryClient.invalidateQueries({ queryKey: ["notes"] });
                navigate("/trash");
              }
            }}
          >
            Delete forever
          </button>
        )}
      </div>
      {note.private && (
        <p className="muted">
          Private notes stay out of AI and agent tools unless their token
          explicitly includes them.
        </p>
      )}
      {flags.error && (
        <p className="error" role="alert">
          {flags.error.message}
        </p>
      )}
      <details className="history">
        <summary>Revision history</summary>
        {history.isPending ? (
          <p className="muted">Loading history…</p>
        ) : history.error ? (
          <p className="error">{history.error.message}</p>
        ) : (
          history.data?.map((revision) => (
            <div className="history-item" key={revision.version}>
              <div className="row">
                <span>
                  Version {revision.version} ·{" "}
                  {new Date(revision.changed_at).toLocaleString()}
                </span>
                <button
                  className="button"
                  disabled={restore.isPending}
                  onClick={() => {
                    if (
                      confirm(
                        "Restore this revision? The current text stays in history.",
                      )
                    )
                      restore.mutate({
                        id: note.id,
                        version: revision.version,
                      });
                  }}
                >
                  Restore this version
                </button>
              </div>
              <pre>{revision.body}</pre>
            </div>
          ))
        )}
        {restore.error && <p className="error">{restore.error.message}</p>}
      </details>
      <aside className="note-panel">
        <NotePanel note={note} />
      </aside>
    </div>
  );
}
export default function NotePage({ id }: { id: string }) {
  const role = useWorkspaceRole();
  const note = useNote(id);
  return note.isPending ? (
    <p className="page-status" role="status">
      Opening note…
    </p>
  ) : note.isError ? (
    <p className="error" role="alert">
      {note.error.message} <Link href="/">All notes</Link>
    </p>
  ) : role === "viewer" ? (
    <div className="note-page">
      <Link href="/">← All notes</Link>
      <p className="muted">Can view</p>
      <NoteBody note={note.data} />
    </div>
  ) : (
    <NoteEditor key={id} note={note.data} />
  );
}
