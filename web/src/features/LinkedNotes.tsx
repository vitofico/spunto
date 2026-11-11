import { useId, useMemo, useState } from "react";
import { useQueries } from "@tanstack/react-query";
import { Link } from "wouter";
import { api } from "../lib/api";
import { getLanguage, t, useLanguage } from "../lib/i18n";
import { noteLinkIds, noteSummary } from "../lib/text";
import type { Note } from "../lib/types";
import { activePlugins, usePlugins } from "./Slots";
import { validComponent } from "./vocabularyData";

const FIRST = 5;

/** The notes behind these IDs that the reader can open; a removed or unshared one is left out. */
function useNotes(ids: string[]) {
  return useQueries({
    queries: ids.map((id) => ({
      queryKey: ["note", id],
      queryFn: () => api.note(id),
      retry: false,
    })),
  }).flatMap((query) => (query.data ? [query.data] : []));
}
/** What the notes this one links to are called now, for the chips in its text. */
export function useNoteLinks(note: Note) {
  const ids = useMemo(() => noteLinkIds(note.html), [note.html]);
  return Object.fromEntries(
    useNotes(ids).map((target) => [target.id, noteSummary(target)]),
  );
}
function Group({ title, ids }: { title: string; ids: string[] }) {
  const [all, setAll] = useState(false);
  const shown = all ? ids : ids.slice(0, FIRST);
  const notes = useNotes(shown);
  if (!notes.length) return null;
  return (
    <>
      <h3 className="overline">{title}</h3>
      <ul>
        {notes.map((note) => {
          const { name, excerpt } = noteSummary(note);
          return (
            <li key={note.id}>
              <Link href={"/n/" + note.id}>
                <span className="linked-note-name">
                  {name || t("vocabulary.untitled_note")}
                </span>
                <time dateTime={note.updated_at}>
                  {new Date(note.updated_at).toLocaleDateString(getLanguage(), {
                    dateStyle: "medium",
                  })}
                </time>
                {excerpt && (
                  <span className="linked-note-excerpt">{excerpt}</span>
                )}
              </Link>
            </li>
          );
        })}
      </ul>
      {ids.length > shown.length && (
        <button className="show-more" onClick={() => setAll(true)}>
          {t("linkednotes.show_all", { count: ids.length })}
        </button>
      )}
    </>
  );
}

/** Both directions in one place: the notes this one points at, and the notes that point here. */
export function LinkedNotes({ note }: { note: Note }) {
  useLanguage();
  const heading = useId();
  const manifest = usePlugins();
  const to = useMemo(
    () => noteLinkIds(note.html).filter((id) => id !== note.id),
    [note.html, note.id],
  );
  const stored = activePlugins(manifest.data?.plugins).some(
    (plugin) => plugin.id === "links",
  )
    ? note.annotations?.find((a) => a.plugin === "links" && a.kind === "panel")
        ?.payload
    : undefined;
  const from =
    validComponent(stored) && stored.type === "notes"
      ? (stored.ids as unknown[]).filter((id) => typeof id === "string")
      : [];
  if (!to.length && !from.length) return null;
  return (
    <section className="linked-notes" aria-labelledby={heading}>
      <h2 id={heading}>{t("linkednotes.linked_notes")}</h2>
      <Group title={t("linkednotes.links_to")} ids={to} />
      <Group title={t("linkednotes.linked_from")} ids={from} />
    </section>
  );
}
