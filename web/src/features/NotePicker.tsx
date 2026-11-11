import { useEffect, useId, useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { api } from "../lib/api";
import { getLanguage, t, useLanguage } from "../lib/i18n";
import { searchExcerpt } from "../lib/searchExcerpt";
import type { Note } from "../lib/types";
import styles from "./Feature.module.css";

export function NotePicker({
  exclude,
  action,
  busy,
  onSelect,
  onCancel,
}: {
  exclude: readonly string[];
  action: string;
  busy: boolean;
  onSelect: (note: Note) => void;
  onCancel?: () => void;
}) {
  useLanguage();
  const [input, setInput] = useState("");
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<{ id: string; version: number }>();
  const id = useId();
  const current = input.trim() === query;
  useEffect(() => {
    const timer = setTimeout(() => setQuery(input.trim()), 250);
    return () => clearTimeout(timer);
  }, [input]);
  const notes = useInfiniteQuery({
    queryKey: ["note-picker", query],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ signal, pageParam }) =>
      api.search(
        query,
        {
          mode: "keyword",
          sort: query ? "relevance" : "recent",
          include_archived: true,
          limit: 12,
          cursor: pageParam,
        },
        signal,
      ),
    getNextPageParam: (page) => page.next_cursor ?? undefined,
    enabled: current,
    retry: false,
  });
  const choices = current
    ? (notes.data?.pages ?? []).flatMap((page) =>
        page.hits.filter((note) => !exclude.includes(note.id)),
      )
    : [];
  const target = choices.find(
    (note) => note.id === selected?.id && note.version === selected.version,
  );
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        if (target && !busy && !notes.isError) onSelect(target);
      }}
    >
      <label htmlFor={id}>{t("notepicker.search")}</label>
      <input
        id={id}
        type="search"
        className="field"
        maxLength={200}
        autoComplete="off"
        autoFocus
        disabled={busy}
        value={input}
        onChange={(event) => {
          setInput(event.target.value);
          setSelected(undefined);
        }}
      />
      {(!current || notes.isPending) && (
        <p role="status">{t("timeline.loading_notes")}</p>
      )}
      {current && notes.isError && (
        <p role="alert">
          {notes.error.message}{" "}
          <button
            type="button"
            onClick={() =>
              void (notes.isFetchNextPageError
                ? notes.fetchNextPage()
                : notes.refetch())
            }
          >
            {t("app.try_again")}
          </button>
        </p>
      )}
      {current && notes.isSuccess && !choices.length && !notes.hasNextPage && (
        <p role="status">
          {query ? t("search.no_results", { query }) : t("notepicker.empty")}
        </p>
      )}
      {!!choices.length && (
        <fieldset className={styles.noteChoices} disabled={busy}>
          <legend>
            {query ? t("notepicker.matches") : t("notepicker.recent")}
          </legend>
          {choices.map((note) => (
            <label key={note.id}>
              <input
                type="radio"
                name={id}
                checked={note.id === target?.id}
                onChange={() =>
                  setSelected({ id: note.id, version: note.version })
                }
              />
              <span>
                <strong>{note.title || t("vocabulary.untitled_note")}</strong>
                <small>{searchExcerpt(note.body, [], 150)}</small>
                <small>
                  {new Date(note.created_at).toLocaleString(getLanguage(), {
                    dateStyle: "medium",
                    timeStyle: "short",
                  })}
                </small>
              </span>
            </label>
          ))}
        </fieldset>
      )}
      {current && notes.hasNextPage && !notes.isError && (
        <button
          type="button"
          disabled={busy || notes.isFetching}
          onClick={() => void notes.fetchNextPage()}
        >
          {t("search.load_more_results")}
        </button>
      )}
      <div className={styles.row}>
        <button className="primary" disabled={!target || busy || notes.isError}>
          {busy ? t("app.saving") : action}
        </button>
        {onCancel && (
          <button type="button" disabled={busy} onClick={onCancel}>
            {t("profilepanel.cancel")}
          </button>
        )}
      </div>
    </form>
  );
}
