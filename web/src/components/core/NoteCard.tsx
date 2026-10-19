import DOMPurify from "dompurify";
import { Link, useLocation } from "wouter";
import {
  useFlags,
  useUpdateNote,
  useWorkspaceRole,
  type Note,
} from "../../lib";
import { EntryMeta, SuggestionLine } from "../../features/Slots";
export function toggleRenderedCheckbox(body: string, offset: number) {
  if (
    !Number.isInteger(offset) ||
    offset < 1 ||
    body[offset - 1] !== "[" ||
    body[offset + 1] !== "]" ||
    !/[ xX]/.test(body[offset] ?? "")
  )
    return body;
  return (
    body.slice(0, offset) +
    (body[offset] === " " ? "x" : " ") +
    body.slice(offset + 1)
  );
}
export function NoteBody({
  note,
  onCheckbox,
}: {
  note: Note;
  onCheckbox?: (index: number) => void;
}) {
  if (!note.html)
    return (
      <div
        className={"note-body" + (note.machine ? " machine" : "")}
        style={{ whiteSpace: "pre-wrap" }}
      >
        {note.body}
      </div>
    );
  return (
    <div
      className={"note-body" + (note.machine ? " machine" : "")}
      onClick={(e) => {
        const input = e.target as HTMLInputElement;
        if (input.matches("input[type=checkbox]")) {
          e.preventDefault();
          e.stopPropagation();
          const offset = Number(input.dataset.checkboxOffset);
          if (
            onCheckbox &&
            input.dataset.checkboxOffset !== undefined &&
            Number.isInteger(offset)
          )
            onCheckbox(offset);
        }
      }}
      dangerouslySetInnerHTML={{
        __html: DOMPurify.sanitize(note.html ?? "", {
          ADD_ATTR: ["data-checkbox-index", "data-checkbox-offset"],
        }),
      }}
    />
  );
}
export function NoteCard({ note }: { note: Note }) {
  const canEdit = useWorkspaceRole() !== "viewer";
  const flags = useFlags();
  const update = useUpdateNote();
  const [, navigate] = useLocation();
  return (
    <article className="entry" data-note-id={note.id}>
      <time className="time" dateTime={note.created_at}>
        {new Date(note.created_at).toLocaleTimeString(undefined, {
          hour: "2-digit",
          minute: "2-digit",
        })}
      </time>
      <div
        className="entry-main"
        role="link"
        tabIndex={0}
        aria-label={"Open note: " + note.title}
        onClick={(e) => {
          if (!(e.target as HTMLElement).closest("a,input,button"))
            navigate("/n/" + note.id);
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter" && e.target === e.currentTarget)
            navigate("/n/" + note.id);
        }}
      >
        <NoteBody
          note={note}
          onCheckbox={
            canEdit
              ? (index) =>
                  update.mutate({
                    id: note.id,
                    body: toggleRenderedCheckbox(note.body, index),
                    version: note.version,
                  })
              : undefined
          }
        />
      </div>
      <div className="entry-meta">
        {note.pinned && <span>Pinned</span>}
        {note.private && <span>Private</span>}
        {note.origin === "agent" && <span>via agent</span>}
        {note.conflict_of && <span>Conflict copy</span>}
        <EntryMeta note={note} />
      </div>
      {canEdit && <SuggestionLine note={note} />}
      {canEdit && (
        <div className="entry-actions">
          <Link href={"/n/" + note.id} className="button">
            Edit
          </Link>
          <button
            className="button"
            onClick={() =>
              flags.mutate({ id: note.id, flags: { pinned: !note.pinned } })
            }
          >
            {note.pinned ? "Unpin" : "Pin"}
          </button>
          {note.trashed ? (
            <button
              className="button"
              onClick={() =>
                flags.mutate({ id: note.id, flags: { trashed: false } })
              }
            >
              Restore
            </button>
          ) : (
            <>
              <button
                className="button"
                onClick={() =>
                  flags.mutate({
                    id: note.id,
                    flags: { archived: !note.archived },
                  })
                }
              >
                {note.archived ? "Unarchive" : "Archive"}
              </button>
              <button
                className="button"
                onClick={() =>
                  flags.mutate({ id: note.id, flags: { trashed: true } })
                }
              >
                Move to trash
              </button>
            </>
          )}
          {update.error && (
            <span role="alert" className="error">
              {update.error.message}
            </span>
          )}
          {flags.error && (
            <span role="alert" className="error">
              {flags.error.message}
            </span>
          )}
        </div>
      )}
    </article>
  );
}
export function dayLabel(value: string) {
  const date = new Date(value),
    today = new Date();
  const start = (d: Date) =>
    new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const days = Math.round((start(today) - start(date)) / 86400000);
  return days === 0
    ? "Today"
    : days === 1
      ? "Yesterday"
      : date.toLocaleDateString(undefined, {
          weekday: "long",
          month: "long",
          day: "numeric",
          year:
            date.getFullYear() !== today.getFullYear() ? "numeric" : undefined,
        });
}
export function groupedNotes(notes: Note[]) {
  const groups: { title: string; notes: Note[] }[] = [];
  for (const note of notes) {
    const title = note.pinned ? "Pinned" : dayLabel(note.created_at);
    const existing = groups.find((group) => group.title === title);
    if (existing) existing.notes.push(note);
    else groups.push({ title, notes: [note] });
  }
  return groups;
}
