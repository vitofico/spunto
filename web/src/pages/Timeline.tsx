import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useLocation } from "wouter";
import {
  api,
  queryClient,
  useNotes,
  useDraftEditor,
  useDeleteLabel,
  useRenameLabel,
  useLabels,
  labelTokens,
  useWorkspaceRole,
} from "../lib";
import { ReviewLine, DailySlip } from "../features/Slots";
import { Editor } from "../components/core/Editor";
import { groupedNotes, NoteCard } from "../components/core/NoteCard";
export default function Timeline({
  view = "timeline",
  label,
}: {
  view?: string;
  label?: string;
}) {
  const canEdit = useWorkspaceRole() !== "viewer";
  const [, navigate] = useLocation();
  const notes = useNotes({ view, label, limit: 50 });
  const draft = useDraftEditor({
    draftKey: label ? "composer:" + label : "composer",
    enabled: canEdit,
  });
  const labels = useLabels();
  const rename = useRenameLabel(),
    remove = useDeleteLabel();
  const [action, setAction] = useState("");
  const [newLabel, setNewLabel] = useState(label ?? "");
  const [confirmed, setConfirmed] = useState(false);
  const impact = useQuery({
    queryKey: ["label-impact", label, action, newLabel],
    queryFn: () =>
      api.request<{ notes: number }>("/api/v1/labels/impact", {
        method: "POST",
        body: { old: label, new: action === "remove" ? null : newLabel },
      }),
    enabled: !!label && !!action && (action === "remove" || !!newLabel),
    retry: false,
  });
  const [emptyBusy, setEmptyBusy] = useState(false);
  const [emptyError, setEmptyError] = useState<string | null>(null);
  const sentinel = useRef<HTMLDivElement>(null);
  const all = notes.data?.pages.flatMap((p) => p.notes) ?? [];
  const count =
    labels.data?.labels.find((l) => l.path === label)?.count ?? all.length;
  const EmptyHeading = label || view !== "timeline" ? "h2" : "h1";
  useEffect(() => {
    if (!sentinel.current || !notes.hasNextPage) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && !notes.isFetchingNextPage)
          void notes.fetchNextPage();
      },
      { rootMargin: "400px" },
    );
    observer.observe(sentinel.current);
    return () => observer.disconnect();
  }, [notes.hasNextPage, notes.isFetchingNextPage, notes.fetchNextPage]);
  async function save() {
    if (
      label &&
      draft.body.trim() &&
      !labelTokens(draft.body).some((token) => token.path === label)
    )
      draft.setBody(draft.body + " #" + label);
    await draft.save();
    document.getElementById("composer")?.focus();
  }
  return (
    <>
      {label ? (
        <div className="label-title">
          <h1 className="page-title">
            #{label} <span className="muted">{count}</span>
          </h1>
          <select
            disabled={!canEdit}
            aria-label="Label actions"
            value={action}
            onChange={(e) => {
              setAction(e.target.value);
              setConfirmed(false);
            }}
          >
            <option value="">Label actions</option>
            <option value="rename">Rename</option>
            <option value="merge">Merge into…</option>
            <option value="remove">Remove label</option>
          </select>
        </div>
      ) : (
        view !== "timeline" && (
          <h1 className="page-title">
            {view === "archive"
              ? "Archive"
              : view === "trash"
                ? "Trash"
                : view.split("/").at(-1)}
          </h1>
        )
      )}
      {action && label && (
        <section className="card stack">
          <h2>
            {action === "remove"
              ? "Remove label"
              : action === "merge"
                ? "Merge label"
                : "Rename label"}
          </h2>
          {action !== "remove" && (
            <label>
              New label path
              <input
                className="field"
                value={newLabel}
                onChange={(e) => setNewLabel(e.target.value)}
              />
            </label>
          )}
          <p className="muted">
            Rewrites {impact.data?.notes ?? count} notes. Child labels move too.
            Whole labels only; note text is kept.
          </p>
          <div className="row">
            <button
              className="button primary"
              disabled={
                impact.isPending ||
                impact.isError ||
                rename.isPending ||
                remove.isPending
              }
              onClick={async () => {
                if (action === "remove") {
                  if (!confirmed) {
                    setConfirmed(true);
                    return;
                  }
                  await remove.mutateAsync(label);
                  navigate("/");
                } else {
                  await rename.mutateAsync({ from: label, to: newLabel });
                  navigate("/l/" + newLabel);
                }
                setAction("");
              }}
            >
              {action === "remove"
                ? confirmed
                  ? "Confirm removal"
                  : "Remove label"
                : action === "merge"
                  ? "Merge labels"
                  : "Rename"}
            </button>
            <button className="button" onClick={() => setAction("")}>
              Cancel
            </button>
          </div>
          {(rename.error || remove.error || impact.error) && (
            <p className="error">
              {rename.error?.message ??
                remove.error?.message ??
                impact.error?.message}
            </p>
          )}
        </section>
      )}
      {view === "timeline" && canEdit && (
        <>
          <Editor
            body={draft.body}
            setBody={(body) =>
              draft.setBody(
                label &&
                  body.trim() &&
                  !labelTokens(body).some((token) => token.path === label)
                  ? body + " #" + label
                  : body,
              )
            }
            onSave={() => void save()}
            upload={draft.uploadAttachment}
            autoFocus
            error={draft.error?.message}
            busy={draft.state === "saving"}
          />
          {draft.storageFailed && (
            <p className="error" role="alert">
              Device storage is full or unavailable; keep this tab open until
              saved.
            </p>
          )}
          <ReviewLine />
          <DailySlip />
        </>
      )}
      {view === "trash" && (
        <p className="muted">Notes here are deleted for good after 30 days.</p>
      )}
      {notes.isPending && (
        <p className="page-status" role="status">
          Loading notes…
        </p>
      )}
      {notes.isError && (
        <p className="error" role="alert">
          {notes.error.message}{" "}
          <button className="button" onClick={() => void notes.refetch()}>
            Try again
          </button>
        </p>
      )}
      {!notes.isPending && !all.length && !notes.isError && (
        <div className="empty">
          <EmptyHeading>
            {label
              ? "No notes with this label yet."
              : view === "timeline" && canEdit
                ? "A place for your next thought."
                : view === "trash"
                  ? "Nothing in the trash."
                  : view === "archive"
                    ? "A quiet archive."
                    : "No notes here yet."}
          </EmptyHeading>
          {label && <p>Notes with #{label} will appear here.</p>}
          {view === "timeline" && !label && canEdit && (
            <>
              <p>Write a sentence above. That’s a note.</p>
              <ol>
                <li>Save with ⌘ Enter.</li>
                <li>Add #labels anywhere in the text.</li>
                <li>Find it again with search or ⌘ K.</li>
              </ol>
            </>
          )}
        </div>
      )}
      <div role="feed" aria-label="Notes" aria-busy={notes.isPending}>
        {groupedNotes(all).map((group) => (
          <section key={group.title}>
            <h2 className="overline day">{group.title}</h2>
            {group.notes.map((note) => (
              <NoteCard key={note.id} note={note} />
            ))}
          </section>
        ))}
      </div>
      <div ref={sentinel} />
      {notes.hasNextPage && (
        <button
          className="button load-more"
          disabled={notes.isFetchingNextPage}
          onClick={() => void notes.fetchNextPage()}
        >
          {notes.isFetchingNextPage ? "Loading more notes…" : "Load more notes"}
        </button>
      )}
      {view === "trash" && canEdit && all.length > 0 && (
        <button
          className="button danger"
          disabled={emptyBusy}
          onClick={async () => {
            setEmptyBusy(true);
            setEmptyError(null);
            try {
              let total = 0;
              let cursor: string | undefined;
              do {
                const page = await api.notes({
                  view: "trash",
                  limit: 500,
                  cursor,
                });
                total += page.notes.length;
                cursor = page.next_cursor ?? undefined;
              } while (cursor);
              if (
                window.confirm(
                  `Delete ${total} notes forever? This cannot be undone.`,
                )
              ) {
                await api.request("/api/v1/trash/empty", { method: "POST" });
                await queryClient.invalidateQueries({ queryKey: ["notes"] });
              }
            } catch (error) {
              setEmptyError(
                error instanceof Error
                  ? error.message
                  : "Could not empty trash. Try again.",
              );
            } finally {
              setEmptyBusy(false);
            }
          }}
        >
          {emptyBusy ? "Counting notes…" : "Empty trash"}
        </button>
      )}
      {emptyError && (
        <p className="error" role="alert">
          {emptyError}
        </p>
      )}
    </>
  );
}
