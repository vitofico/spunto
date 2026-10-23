import { getLanguage, t, useLanguage } from "../lib/i18n";
import { useEffect, useRef, useState } from "react";
import { Link, useLocation } from "wouter";
import {
  useNote,
  useDraftEditor,
  useHistory,
  api,
  createIdempotencyKey,
  invalidateContent,
  type Note,
  type Revision,
  useWorkspaceRole,
} from "../lib";
import { compactDiff, diffLines } from "../lib/text";
import { useDocumentTitle } from "../lib/useDocumentTitle";
import { ShareNote } from "../features/Collaboration";
import { Editor, sourceOffset } from "../components/core/Editor";
import {
  NoteBody,
  Provenance,
  failure,
  toggleRenderedCheckbox,
  useNoteFlag,
} from "../components/core/NoteCard";
import { Icon } from "../components/core/Icon";
import { NotFound } from "../components/core/NotFound";
import { ApiError } from "../lib/errors";
import { MoreMenu } from "../components/core/Menu";
import { useConfirm } from "../components/core/ConfirmDialog";
import { useToast } from "../components/core/Toast";
import { SuggestionLine, NotePanel } from "../features/Slots";
/** App marks whether the history entry before this one is inside Spunto. */
function cameFromApp() {
  try {
    return sessionStorage.getItem("spunto:in-app-nav") === "1";
  } catch {
    return false;
  }
}
/** The way back, named by where it goes: the previous in-app page, else All notes. */
function Back() {
  useLanguage();
  const inApp = cameFromApp();
  return (
    <Link
      href="/"
      className="button icon-button back"
      aria-label={inApp ? t("slots.back") : t("app.all_notes")}
      onClick={(event) => {
        if (!inApp) return;
        event.preventDefault();
        history.back();
      }}
    >
      <Icon name="back" />
    </Link>
  );
}
function Diff({ before, after }: { before: string; after: string }) {
  useLanguage();
  return (
    <div className="diff">
      {compactDiff(diffLines(before, after)).map((line, index) =>
        line.kind === "skip" ? (
          <div key={index} className="diff-skip">
            {t("note.unchanged_lines", { count: line.count })}
          </div>
        ) : line.kind === "del" ? (
          <del key={index}>
            <span className="sr-only">{t("note.removed")}</span>
            {line.text || " "}
          </del>
        ) : line.kind === "add" ? (
          <ins key={index}>
            <span className="sr-only">{t("note.added")}</span>
            {line.text || " "}
          </ins>
        ) : (
          <div key={index}>{line.text || " "}</div>
        ),
      )}
    </div>
  );
}
type Choice = { label: string; run: () => Promise<void>; primary?: boolean };
/** "Edited on two devices": the saved text against the other version, and three ways out. */
function Conflict({
  saved,
  mine,
  mineName,
  choices,
}: {
  saved: string | null;
  mine: string;
  mineName: "yours" | "this copy";
  choices: Choice[];
}) {
  useLanguage();
  const [comparing, setComparing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <section
      className="notice conflict-notice"
      aria-label={t("note.edited_on_two_devices")}
    >
      <p>
        {t("note.edited_on_two_devices_both_versions_are_kept")}{" "}
        <button
          className="link-button"
          aria-expanded={comparing}
          onClick={() => setComparing(!comparing)}
        >
          {t("review.compare")}
        </button>
      </p>
      {comparing &&
        (saved === null ? (
          <p className="muted">{t("note.opening_the_saved_version")}</p>
        ) : (
          <>
            <p className="diff-legend">
              <del>{t("note.only_in_the_saved_version")}</del>
              <ins>
                {t(
                  mineName === "yours"
                    ? "note.only_in_yours"
                    : "note.only_in_copy",
                )}
              </ins>
            </p>
            <Diff before={saved} after={mine} />
            <div className="row">
              {choices.map((choice) => (
                <button
                  key={choice.label}
                  className={"button" + (choice.primary ? " primary" : "")}
                  disabled={busy}
                  onClick={async () => {
                    setBusy(true);
                    setError(null);
                    try {
                      await choice.run();
                    } catch (reason) {
                      setError(failure(reason));
                    } finally {
                      setBusy(false);
                    }
                  }}
                >
                  {choice.label}
                </button>
              ))}
            </div>
          </>
        ))}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
function History({ note, revisions }: { note: Note; revisions: Revision[] }) {
  useLanguage();
  const [confirm, dialog] = useConfirm();
  const toast = useToast();
  return (
    <>
      {revisions.map((revision, index) => (
        <div className="history-item" key={revision.version}>
          <div className="row">
            <span>
              {revision.version === note.version
                ? t("note.current_version")
                : t("note.version_value1", { value1: revision.version })}{" "}
              · {new Date(revision.changed_at).toLocaleString(getLanguage())}
            </span>
            {revision.version !== note.version && (
              <button
                className="button"
                onClick={async (e) => {
                  if (
                    !(await confirm({
                      opener: e.currentTarget,
                      title: t("note.restore_this_version"),
                      body: t("note.the_current_text_stays_in_history"),
                      confirmLabel: t("note.restore"),
                    }))
                  )
                    return;
                  try {
                    await api.restoreRevision(note.id, revision.version);
                    await invalidateContent();
                    toast.show({ message: t("note.version_restored") });
                  } catch (error) {
                    toast.show({ message: failure(error) });
                  }
                }}
              >
                {t("note.restore")}
              </button>
            )}
          </div>
          <Diff
            before={revisions[index + 1]?.body ?? ""}
            after={revision.body}
          />
        </div>
      ))}
      {dialog}
    </>
  );
}
function NoteEditor({ note }: { note: Note }) {
  useLanguage();
  const trashed = note.trashed;
  const [editing, setEditing] = useState(
    () =>
      !trashed && (history.state as { edit?: boolean } | null)?.edit === true,
  );
  const [caret, setCaret] = useState<number>();
  const [everSaved, setEverSaved] = useState(false);
  const [, navigate] = useLocation();
  const draft = useDraftEditor({
    note,
    draftKey: "note:" + note.id,
    enabled: !trashed,
  });
  const [reviewBase, setReviewBase] = useState<Note | null>(null);
  const flag = useNoteFlag();
  const toast = useToast();
  const [confirm, dialog] = useConfirm();
  const revisions = useHistory(note.id);
  const historyRef = useRef<HTMLDetailsElement>(null);
  const name = note.title || t("vocabulary.untitled_note");
  useDocumentTitle(name);
  useEffect(() => {
    if (draft.state === "saving") setEverSaved(true);
  }, [draft.state]);
  useEffect(() => {
    if (!draft.conflict) {
      setReviewBase(null);
      return;
    }
    let cancelled = false;
    void api.note(note.id).then(
      (latest) => {
        if (!cancelled) setReviewBase(latest);
      },
      () => {},
    );
    return () => {
      cancelled = true;
    };
  }, [draft.conflict, note.id]);
  const copyId =
    draft.conflict && draft.conflict !== note.id ? draft.conflict : null;
  const trashCopy = async (id: string) => {
    await api.flags(id, { trashed: true });
    await invalidateContent();
  };
  // Replacing keeps App's history depth marker (history.state) on the entry.
  const replace = (to: string) =>
    navigate(to, { replace: true, state: history.state });
  const leaveNote = () => (cameFromApp() ? history.back() : replace("/"));
  const status =
    draft.state === "saving"
      ? t("app.saving")
      : draft.state === "error" && draft.error
        ? draft.error.status === 0
          ? t("note.can_t_reach_the_server_your_changes_are")
          : draft.error.message
        : draft.conflict
          ? ""
          : draft.dirty
            ? t("note.unsaved_changes_on_this_device")
            : everSaved
              ? t("note.saved")
              : "";
  const menu = trashed
    ? [
        {
          label: t("note.restore"),
          onSelect: () => void flag(note, "trashed", false),
        },
        {
          label: t("note.delete_forever"),
          danger: true,
          onSelect: async () => {
            if (
              !(await confirm({
                title: t("note.delete_this_note_forever"),
                body: t("note.it_can_t_be_restored_afterwards"),
                confirmLabel: t("note.delete_forever"),
                danger: true,
              }))
            )
              return;
            try {
              await api.deleteNote(note.id);
              await invalidateContent();
              replace("/trash");
            } catch (error) {
              toast.show({ message: failure(error) });
            }
          },
        },
      ]
    : [
        {
          label: note.pinned ? t("note.unpin") : t("slots.pin"),
          onSelect: () => void flag(note, "pinned", !note.pinned),
        },
        {
          label: note.private
            ? t("note.let_ai_and_agents_read_it")
            : t("note.make_private"),
          onSelect: () =>
            void api
              .flags(note.id, { private: !note.private })
              .then(invalidateContent, (error) =>
                toast.show({ message: failure(error) }),
              ),
        },
        {
          label: note.archived ? t("note.unarchive") : t("app.archive"),
          onSelect: () =>
            void flag(note, "archived", !note.archived).then(
              (done) => done && !note.archived && leaveNote(),
            ),
        },
        {
          label: t("note.revision_history"),
          onSelect: () => {
            const details = historyRef.current;
            if (!details) return;
            details.open = true;
            details.scrollIntoView({ block: "start" });
            details.querySelector("summary")?.focus();
          },
        },
        {
          label: t("app.move_to_trash"),
          onSelect: () =>
            void flag(note, "trashed", true).then(
              (done) => done && leaveNote(),
            ),
        },
      ];
  return (
    <div className="note-page">
      <Back />
      <header className="note-header">
        <div className="note-meta">
          <time dateTime={note.created_at}>
            {new Date(note.created_at).toLocaleString(getLanguage(), {
              dateStyle: "long",
              timeStyle: "short",
            })}
          </time>
          {note.private && (
            <span className="chip">
              <Icon name="lock" /> {t("note.private")}
            </span>
          )}
          <Provenance note={note} />
        </div>
        <div className="row note-tools">
          <ShareNote id={note.id} />
          {!trashed && !editing && !draft.conflict && (
            <button
              className="button"
              data-edit-note
              onClick={() => {
                setCaret(undefined);
                setEditing(true);
              }}
            >
              <Icon name="edit" /> {t("note.edit")}
            </button>
          )}
          <MoreMenu label={t("note.more_actions")} items={menu} />
        </div>
      </header>
      {trashed && (
        <p className="notice">
          {t("note.in_trash")}{" "}
          <button
            className="link-button"
            onClick={() => void flag(note, "trashed", false)}
          >
            {t("note.restore")}
          </button>
        </p>
      )}
      {note.conflict_of && !trashed && (
        <CopyConflict note={note} original={note.conflict_of} />
      )}
      {draft.conflict && (
        <Conflict
          saved={reviewBase?.body ?? null}
          mine={draft.body}
          mineName="yours"
          choices={[
            {
              label: t("note.keep_mine"),
              primary: true,
              run: async () => {
                const latest = await api.note(note.id);
                if (!draft.resumeReviewed(latest))
                  throw new Error(t("note.keep_this_page_open_and_try_again"));
                if (!(await draft.save())) return;
                if (copyId) await trashCopy(copyId);
              },
            },
            {
              label: t("note.keep_saved"),
              run: async () => {
                const latest = await api.note(note.id);
                if (!draft.discard(latest))
                  throw new Error(t("note.keep_this_page_open_and_try_again"));
                setEditing(false);
                if (copyId) await trashCopy(copyId);
              },
            },
            {
              label: t("review.keep_both"),
              run: async () => {
                let kept: string;
                if (copyId) {
                  const copy = await api.note(copyId);
                  if (copy.body !== draft.body)
                    await api.updateNote(copyId, draft.body, copy.version);
                  kept = copyId;
                } else
                  kept = (
                    await api.createNote(draft.body, createIdempotencyKey())
                  ).id;
                draft.discard(await api.note(note.id));
                setEditing(false);
                await invalidateContent();
                toast.show({
                  message: t("note.your_version_is_a_separate_note"),
                  actionLabel: t("slots.open"),
                  onAction: () => navigate("/n/" + kept),
                });
              },
            },
          ]}
        />
      )}
      {editing || draft.conflict ? (
        <Editor
          note
          body={draft.body}
          setBody={draft.setBody}
          onSave={() => {
            if (!draft.dirty) return setEditing(false);
            void draft.save().then((saved) => {
              if (saved) setEditing(false);
            });
          }}
          upload={draft.uploadAttachment}
          busy={draft.state === "saving"}
          saveDisabled={!!draft.conflict}
          caret={caret}
          autoFocus
        />
      ) : (
        <div
          className={trashed ? undefined : "note-read"}
          onClick={(e) => {
            const target = e.target as HTMLElement;
            if (
              trashed ||
              target.closest("a,input,button,summary") ||
              window.getSelection()?.isCollapsed === false
            )
              return;
            setCaret(
              sourceOffset(e.currentTarget, e.clientX, e.clientY, draft.body),
            );
            setEditing(true);
          }}
        >
          <NoteBody
            note={note}
            body={
              draft.body.length === note.body.length ? draft.body : undefined
            }
            onCheckbox={
              trashed
                ? undefined
                : (offset) => {
                    draft.setBody(toggleRenderedCheckbox(draft.body, offset));
                    // A second tick during the first save goes out right after it.
                    void draft.save().then(() => draft.save());
                  }
            }
          />
        </div>
      )}
      <p
        className={"saved-line" + (draft.state === "error" ? " error" : "")}
        role="status"
      >
        {status}
      </p>
      {draft.storageFailed && (
        <p className="error" role="alert">
          {t("note.device_storage_is_full_or_unavailable_keep_this")}
        </p>
      )}
      {!trashed && <SuggestionLine note={note} />}
      {note.private && (
        <p className="muted">
          {t("note.private_notes_stay_out_of_ai_and_agent")}
        </p>
      )}
      <details className="history" ref={historyRef}>
        <summary>{t("note.revision_history")}</summary>
        {revisions.isPending ? (
          <p className="muted">{t("note.loading_history")}</p>
        ) : revisions.error ? (
          <p className="error">{revisions.error.message}</p>
        ) : (
          <History note={note} revisions={revisions.data ?? []} />
        )}
      </details>
      <div className="note-panel">
        <NotePanel note={note} />
      </div>
      {dialog}
    </div>
  );
}
/** A conflicted copy holds an edit that could not join its original; compare and settle it here. */
function CopyConflict({ note, original }: { note: Note; original: string }) {
  useLanguage();
  const [, navigate] = useLocation();
  const toast = useToast();
  const [saved, setSaved] = useState<Note | null>(null);
  useEffect(() => {
    let cancelled = false;
    void api.note(original).then(
      (value) => {
        if (!cancelled) setSaved(value);
      },
      () => {},
    );
    return () => {
      cancelled = true;
    };
  }, [original]);
  const settle = async (message: string) => {
    await api.flags(note.id, { trashed: true });
    await invalidateContent();
    navigate("/n/" + original, { replace: true, state: history.state });
    toast.show({
      message,
      actionLabel: t("settingsplugins.undo"),
      onAction: () =>
        void api
          .flags(note.id, { trashed: false })
          .then(invalidateContent)
          .catch((error) => toast.show({ message: failure(error) })),
    });
  };
  return (
    <Conflict
      saved={saved?.body ?? null}
      mine={note.body}
      mineName="this copy"
      choices={[
        {
          label: t("note.keep_this_copy"),
          primary: true,
          run: async () => {
            const latest = await api.note(original);
            await api.updateNote(original, note.body, latest.version);
            await settle(t("note.kept_this_copy_it_moved_into_the_original"));
          },
        },
        {
          label: t("note.keep_the_original"),
          run: () => settle(t("note.copy_moved_to_trash")),
        },
        {
          label: t("review.keep_both"),
          run: async () =>
            toast.show({
              message: t("note.both_notes_stay"),
              actionLabel: t("note.open_the_original"),
              onAction: () => navigate("/n/" + original),
            }),
        },
      ]}
    />
  );
}
export default function NotePage({ id }: { id: string }) {
  useLanguage();
  const role = useWorkspaceRole();
  const note = useNote(id);
  return note.isPending ? (
    <p className="page-status" role="status">
      {t("note.opening_note")}
    </p>
  ) : note.error instanceof ApiError && note.error.status === 404 ? (
    <NotFound title={t("note.this_note_isn_t_here")} />
  ) : note.isError ? (
    <p className="error" role="alert">
      {note.error.message} <Link href="/">{t("app.all_notes")}</Link>
    </p>
  ) : role === "viewer" ? (
    <div className="note-page">
      <Back />
      <p className="muted">{t("collaboration.can_view")}</p>
      <NoteBody note={note.data} />
    </div>
  ) : (
    <NoteEditor key={id} note={note.data} />
  );
}
