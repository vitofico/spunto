import { t, useLanguage } from "../lib/i18n";
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
  useLanguage();
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
        {t("sharednotes.note_text")}
        <textarea
          className="field"
          rows={16}
          value={draft.body}
          onChange={(event) => draft.setBody(event.target.value)}
        />
      </label>
      {draft.conflict && (
        <section
          className="card stack"
          aria-label={t("sharednotes.review_conflicting_edits")}
        >
          <h2>{t("sharednotes.review_conflicting_edits")}</h2>
          <p>
            {t("sharednotes.your_edit_was_preserved_review_the_latest_saved")}
          </p>
          <Link href={sharedPath(draft.conflict, note.workspace_id)}>
            {t("sharednotes.open_preserved_edit")}
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
                        t(
                          "sharednotes.the_saved_note_changed_again_review_its_latest",
                        ),
                      );
                      return;
                    }
                    if (draft.resumeReviewed(value)) await draft.save();
                  } catch (error) {
                    setError(
                      error instanceof Error
                        ? error.message
                        : t("sharednotes.could_not_save_your_review"),
                    );
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                {t("sharednotes.save_reviewed_text")}
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
        {t("sharednotes.save")}
      </button>
      <p role="status">
        {draft.state === "saving"
          ? t("app.saving")
          : draft.dirty
            ? t("note.unsaved_changes_on_this_device")
            : t("note.saved")}
      </p>
      {(draft.error || error) && (
        <p className="error" role="alert">
          {error ?? draft.error?.message}
        </p>
      )}
      {draft.storageFailed && (
        <p className="error" role="alert">
          {t("sharednotes.device_storage_is_unavailable_keep_this_tab_open")}
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
  useLanguage();
  const owner = useOwner();
  const query = useQuery({
    queryKey: ["shared-note", workspaceId, id],
    queryFn: () => api.sharedNote(id, workspaceId),
  });
  useDocumentTitle(query.data?.title || t("sharednotes.shared_note"));
  return (
    <>
      <Link href="/shared">{t("sharednotes.shared_with_me")}</Link>
      {query.isPending ? (
        <p>{t("note.opening_note")}</p>
      ) : query.error ? (
        <p className="error" role="alert">
          {query.error.message}
        </p>
      ) : (
        query.data && (
          <>
            <h1 className="page-title">
              {query.data.title || t("sharednotes.shared_note")}
            </h1>
            <p className="muted">
              {t("sharednotes.shared_by")}
              {query.data.shared_by} ·{" "}
              {query.data.share_role === "editor"
                ? t("collaboration.can_edit")
                : t("collaboration.can_view")}
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
  useLanguage();
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
  useLanguage();
  const hosted = useHosted();
  const query = useQuery({
    queryKey: ["shared-notes"],
    queryFn: api.sharedNotes,
    enabled: hosted,
  });
  useDocumentTitle(t("app.shared_with_me"));
  return (
    <>
      <h1 className="page-title">{t("app.shared_with_me")}</h1>
      {!hosted ? (
        <p className="muted">
          {t("sharednotes.this_spunto_has_one_owner_so_there_is")}
        </p>
      ) : query.isPending ? (
        <p>{t("sharednotes.opening_shared_notes")}</p>
      ) : query.error ? (
        <p className="error" role="alert">
          {query.error.message}
        </p>
      ) : !query.data?.notes.length ? (
        <p className="muted">
          {t("sharednotes.notes_shared_with_your_account_will_appear_here")}
        </p>
      ) : (
        query.data.notes.map((note) => (
          <article className="entry" key={note.workspace_id + ":" + note.id}>
            <Link href={sharedPath(note.id, note.workspace_id)}>
              {note.title || t("sharednotes.shared_note")}
            </Link>
            <p className="muted">
              {t("sharednotes.shared_by")}
              {note.shared_by} ·{" "}
              {note.share_role === "editor"
                ? t("collaboration.can_edit")
                : t("collaboration.can_view")}
            </p>
            <NoteBody note={note} />
          </article>
        ))
      )}
    </>
  );
}
