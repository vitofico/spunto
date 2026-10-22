import DOMPurify from "dompurify";
import {
  memo,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Link, useLocation } from "wouter";
import {
  api,
  invalidateContent,
  safeError,
  useManifest,
  useWorkspaceRole,
  type Note,
} from "../../lib";
import { EntryMeta, SuggestionLine } from "../../features/Slots";
import { Icon } from "./Icon";
import { MoreMenu } from "./Menu";
import { useToast } from "./Toast";
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
/** A failed write says what happened; offline it never suggests a draft was involved. */
export function failure(error: unknown) {
  const safe = safeError(error);
  return safe.status === 0
    ? "Can’t reach the server. Nothing changed; try again when you’re back online."
    : safe.message;
}
/**
 * Rendered Markdown. `body` (default: the note's own) decides which task boxes show ticked, so a
 * tick appears before the server answers; a toggle never shifts the offsets the server rendered.
 */
export function NoteBody({
  note,
  body,
  onCheckbox,
  clamp = false,
}: {
  note: Note;
  body?: string;
  onCheckbox?: (offset: number) => void;
  clamp?: boolean;
}) {
  const content = useRef<HTMLDivElement>(null);
  const [expanded, setExpanded] = useState(false);
  const [overflows, setOverflows] = useState(false);
  const ticks = body ?? note.body;
  const html = useMemo(
    () =>
      note.html
        ? DOMPurify.sanitize(note.html, {
            ADD_ATTR: ["data-checkbox-index", "data-checkbox-offset"],
          })
        : "",
    [note.html],
  );
  useLayoutEffect(() => {
    for (const box of content.current?.querySelectorAll<HTMLInputElement>(
      "input[data-checkbox-offset]",
    ) ?? []) {
      box.checked = /[xX]/.test(
        ticks[Number(box.dataset.checkboxOffset)] ?? "",
      );
      box.disabled = !onCheckbox;
    }
  });
  useLayoutEffect(() => {
    const el = content.current;
    const frame = el?.parentElement;
    if (!clamp || !el || !frame) return;
    const check = () =>
      setOverflows(el.offsetHeight > frame.clientHeight + 1 || expanded);
    check();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(check);
    observer.observe(el);
    return () => observer.disconnect();
  }, [clamp, expanded, note.html, note.body]);
  const className = "note-body" + (note.machine ? " machine" : "");
  const text = !note.html ? (
    <div ref={content} className={className} style={{ whiteSpace: "pre-wrap" }}>
      {note.body}
    </div>
  ) : (
    <div
      ref={content}
      className={className}
      onClick={(e) => {
        const target = e.target as HTMLElement;
        // On a phone the whole task row is the target, not only the box.
        const box =
          target.closest<HTMLInputElement>("input[type=checkbox]") ??
          (window.matchMedia?.("(max-width: 599px)").matches &&
          !target.closest("a")
            ? target
                .closest("li")
                ?.querySelector<HTMLInputElement>(
                  ":scope > input[type=checkbox], :scope > p > input[type=checkbox]",
                )
            : null);
        if (!box) return;
        // The box keeps its native toggle: it already shows the new state, which the next render confirms.
        e.stopPropagation();
        const offset = Number(box.dataset.checkboxOffset);
        if (
          onCheckbox &&
          box.dataset.checkboxOffset !== undefined &&
          Number.isInteger(offset)
        )
          onCheckbox(offset);
      }}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
  if (!clamp) return text;
  return (
    <>
      <div
        className="clamp"
        data-clamped={!expanded || undefined}
        data-overflow={(overflows && !expanded) || undefined}
      >
        {text}
      </div>
      {overflows && (
        <button
          className="show-more"
          aria-expanded={expanded}
          onClick={() => setExpanded(!expanded)}
        >
          {expanded ? "Show less" : "Show more"}
        </button>
      )}
    </>
  );
}
/** Whose words these are: "via Claude Code" for agents, "✧ via Weekly digest" for plugin-written notes. */
export function Provenance({ note }: { note: Note }) {
  const manifest = useManifest();
  if (note.machine) {
    const ref = note.source.ref;
    const plugin = manifest.data?.plugins.find((p) => p.id === ref);
    return (
      <span className="chip">
        <span aria-hidden="true">✧ </span>via{" "}
        {plugin?.name ?? ref ?? "a plugin"}
      </span>
    );
  }
  if (note.origin !== "agent") return null;
  return (
    <span className="chip">
      <span className="agent-glyph" aria-hidden="true">
        &gt;_
      </span>{" "}
      via {note.source.ref ?? "an agent"}
    </span>
  );
}
type FlagName = "pinned" | "archived" | "trashed";
const flagDone: Record<FlagName, [on: string, off: string]> = {
  pinned: ["Pinned", "Unpinned"],
  archived: ["Archived", "Unarchived"],
  trashed: ["Moved to trash", "Restored"],
};
/** Pin, archive and trash with an Undo toast; resolves true once the change is saved and shown. */
export function useNoteFlag() {
  const toast = useToast();
  return useCallback(
    async (
      note: Note,
      flag: FlagName,
      value: boolean,
      afterUndo?: () => void,
    ) => {
      try {
        await api.flags(note.id, { [flag]: value });
      } catch (error) {
        toast.show({ message: failure(error) });
        return false;
      }
      toast.show({
        message: flagDone[flag][value ? 0 : 1],
        actionLabel: "Undo",
        onAction: () =>
          void api
            .flags(note.id, { [flag]: !value })
            .then(invalidateContent)
            .then(afterUndo, (error) =>
              toast.show({ message: failure(error) }),
            ),
      });
      await invalidateContent();
      return true;
    },
    [toast],
  );
}
/** Moves focus to a card's link once it is on screen (after a pin or an undo moved it). */
export function focusEntry(id: string) {
  requestAnimationFrame(() =>
    document
      .querySelector<HTMLElement>(
        `article.entry[data-note-id="${CSS.escape(id)}"] a.time`,
      )
      ?.focus(),
  );
}
function neighbour(article: HTMLElement | null) {
  const all = [
    ...document.querySelectorAll<HTMLElement>("article.entry[data-note-id]"),
  ];
  const at = article ? all.indexOf(article) : -1;
  return at < 0 ? undefined : (all[at + 1] ?? all[at - 1])?.dataset.noteId;
}
const tickQueues = new Map<
  string,
  { body: string; version: number; tail: Promise<void> }
>();
/** Ticks on one note go out one at a time, each on the text the server last returned, so quick taps never race. */
export function queueTick(
  note: Pick<Note, "id" | "body" | "version">,
  offset: number,
) {
  let queue = tickQueues.get(note.id);
  if (!queue)
    tickQueues.set(
      note.id,
      (queue = {
        body: note.body,
        version: note.version,
        tail: Promise.resolve(),
      }),
    );
  const q = queue;
  const run = q.tail.then(async () => {
    const saved = await api.updateNote(
      note.id,
      toggleRenderedCheckbox(q.body, offset),
      q.version,
    );
    q.body = saved.body;
    q.version = saved.version;
  });
  const tail = run.catch(() => {});
  q.tail = tail;
  return run.finally(() => {
    if (q.tail !== tail) return;
    tickQueues.delete(note.id);
    void invalidateContent();
  });
}
export const NoteCard = memo(function NoteCard({ note }: { note: Note }) {
  const canEdit = useWorkspaceRole() !== "viewer";
  const [, navigate] = useLocation();
  const toast = useToast();
  const flag = useNoteFlag();
  const article = useRef<HTMLElement>(null);
  const [ticked, setTicked] = useState<string>();
  // A capture the server has not acknowledged yet (see useDraftEditor).
  const queued = note.id.startsWith("queued:");
  const name = note.title || "Untitled note";
  useEffect(() => {
    if (!tickQueues.has(note.id)) setTicked(undefined);
  }, [note.id, note.version]);
  const leave = async (change: "archived" | "trashed", value: boolean) => {
    const next = neighbour(article.current);
    if ((await flag(note, change, value, () => focusEntry(note.id))) && next)
      focusEntry(next);
  };
  const edit = () => navigate("/n/" + note.id, { state: { edit: true } });
  const actions: Record<string, () => void> = {
    edit,
    label: edit,
    pin: () =>
      void flag(note, "pinned", !note.pinned, () => focusEntry(note.id)).then(
        (done) => done && focusEntry(note.id),
      ),
    archive: () => void leave("archived", !note.archived),
    trash: () => void leave("trashed", !note.trashed),
  };
  const latest = useRef(actions);
  latest.current = actions;
  useEffect(() => {
    const el = article.current;
    if (!el || queued || !canEdit) return;
    // The timeline's single-key shortcuts (App) ask the focused card to act like its own controls.
    const act = (event: Event) =>
      latest.current[String((event as CustomEvent).detail)]?.();
    el.addEventListener("spunto:entry-action", act);
    return () => el.removeEventListener("spunto:entry-action", act);
  }, [queued, canEdit]);
  const tick = (offset: number) => {
    setTicked((current) =>
      toggleRenderedCheckbox(current ?? note.body, offset),
    );
    queueTick(note, offset).catch((error) => {
      setTicked(undefined);
      toast.show({ message: failure(error) });
    });
  };
  const time = new Date(note.created_at).toLocaleTimeString(undefined, {
    hour: "numeric",
    minute: "2-digit",
  });
  return (
    <article
      ref={article}
      className={"entry" + (queued ? " queued" : "")}
      data-note-id={queued ? undefined : note.id}
      aria-busy={queued || undefined}
    >
      {queued ? (
        <span className="time">
          <time dateTime={note.created_at}>{time}</time>
        </span>
      ) : (
        <Link
          href={"/n/" + note.id}
          className="time"
          aria-label={`${note.pinned ? "Pinned" : time}, ${name}`}
        >
          {note.pinned ? (
            <Icon name="pin" />
          ) : (
            <time dateTime={note.created_at}>{time}</time>
          )}
        </Link>
      )}
      <div
        className="entry-main"
        onClick={(e) => {
          if (
            queued ||
            (e.target as HTMLElement).closest("a,input,button,summary") ||
            window.getSelection()?.isCollapsed === false
          )
            return;
          navigate("/n/" + note.id);
        }}
      >
        <NoteBody
          note={note}
          body={ticked}
          clamp
          onCheckbox={canEdit && !queued && !note.trashed ? tick : undefined}
        />
      </div>
      <div className="entry-meta">
        {note.private && (
          <span className="chip">
            <Icon name="lock" /> private
          </span>
        )}
        <Provenance note={note} />
        {note.conflict_of && (
          <span className="conflict">
            Conflicted copy · <Link href={"/n/" + note.id}>Compare</Link>
          </span>
        )}
        <EntryMeta note={note} />
        {queued && <span className="waiting">Waiting to sync</span>}
      </div>
      {canEdit && !queued && <SuggestionLine note={note} />}
      {canEdit && !queued && (
        <MoreMenu
          label={`Actions for “${name}”`}
          items={
            note.trashed
              ? [{ label: "Restore", onSelect: actions.trash! }]
              : [
                  { label: "Edit", onSelect: edit },
                  {
                    label: note.pinned ? "Unpin" : "Pin",
                    onSelect: actions.pin!,
                  },
                  {
                    label: note.archived ? "Unarchive" : "Archive",
                    onSelect: actions.archive!,
                  },
                  { label: "Move to trash", onSelect: actions.trash! },
                ]
          }
        />
      )}
    </article>
  );
});
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
  const groups = new Map<string, { title: string; notes: Note[] }>();
  for (const note of notes) {
    const day = note.pinned
      ? "Pinned"
      : new Date(note.created_at).toDateString();
    const existing = groups.get(day);
    if (existing) existing.notes.push(note);
    else
      groups.set(day, {
        title: note.pinned ? "Pinned" : dayLabel(note.created_at),
        notes: [note],
      });
  }
  return [...groups.values()];
}
