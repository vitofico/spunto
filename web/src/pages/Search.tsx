import { t, useLanguage } from "../lib/i18n";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { useLocation, useSearch as useLocationSearch } from "wouter";
import { fold, useSearch } from "../lib";
import { useDocumentTitle } from "../lib/useDocumentTitle";
import { searchExcerpt, searchMatchRanges } from "../lib/searchExcerpt";
import { NoteCard } from "../components/core/NoteCard";
import { Icon } from "../components/core/Icon";

const LIMIT = 500;
/** Words to mark in results: what the server matches, minus #labels. */
function terms(query: string) {
  return (
    query
      .replace(/(^|\s)#\S+/g, " ")
      .match(/[\p{L}\p{N}]+/gu)
      ?.map(fold) ?? []
  );
}
function highlight(text: string, words: string[]): ReactNode {
  const ranges = searchMatchRanges(text, words);
  if (!ranges.length) return text;
  let before = 0;
  const pieces: ReactNode[] = [];
  for (const [start, end] of ranges) {
    if (start < before) continue;
    pieces.push(
      text.slice(before, start),
      <mark key={start} data-match="">
        {text.slice(start, end)}
      </mark>,
    );
    before = end;
  }
  return [...pieces, text.slice(before)];
}
export default function SearchPage() {
  useLanguage();
  const [, navigate] = useLocation();
  const params = new URLSearchParams(useLocationSearch());
  const [q, setQ] = useState(params.get("q") ?? "");
  const submitted = params.get("q") ?? "";
  const from = params.get("from") ?? "";
  const to = params.get("to") ?? "";
  const archived = params.get("archived") === "1";
  const recent = params.get("sort") === "recent";
  const badDates = !!from && !!to && from > to;
  const filtersRef = useRef<HTMLDetailsElement>(null);
  const count = Number(!!(from || to)) + Number(archived) + Number(recent);
  useDocumentTitle(
    submitted.trim()
      ? `“${submitted.trim()}” · ${t("app.search")}`
      : t("app.search"),
  );
  /** Filters live in the address, so Back, reload and shared links keep them. */
  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(window.location.search);
    for (const [key, value] of Object.entries(next))
      if (value) merged.set(key, value);
      else merged.delete(key);
    navigate("/search?" + merged, { replace: true, state: history.state });
  }
  // A search started elsewhere (the palette) fills the field unless it is in use.
  const field = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (document.activeElement !== field.current) setQ(submitted);
  }, [submitted]);
  // Results follow typing after a short pause.
  useEffect(() => {
    if (q === submitted || q.length > LIMIT) return;
    const timer = setTimeout(() => update({ q }), 300);
    return () => clearTimeout(timer);
  }, [q]);
  useEffect(() => {
    if (badDates && filtersRef.current) filtersRef.current.open = true;
  }, [badDates]);
  const tooLong = q.length > LIMIT;
  const validSearch = !badDates && submitted.length <= LIMIT;
  const enabled = validSearch && !!submitted.trim();
  const filters = {
    from: from || undefined,
    to: to || undefined,
    include_archived: archived,
    sort: recent ? ("recent" as const) : ("relevance" as const),
    limit: 50,
  };
  const keyword = useSearch(
    submitted,
    { ...filters, mode: "keyword" },
    validSearch,
  );
  const hybrid = useSearch(submitted, { ...filters, mode: "hybrid" }, enabled);
  const semantic = hybrid.data?.pages[0]?.semantic;
  const shown = semantic === "on" ? hybrid : keyword;
  const all = shown.data?.pages.flatMap((page) => page.hits) ?? [];
  const words = terms(submitted);
  const resultCount =
    enabled && !shown.isPending && !shown.error
      ? t(shown.hasNextPage ? "search.found_more" : "search.found", {
          count: all.length,
        })
      : "";
  return (
    <>
      <h1 className="page-title">{t("search.search_your_notes")}</h1>
      <form
        className="search-form"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          if (!tooLong) update({ q });
        }}
      >
        <div className="row">
          <input
            ref={field}
            className="field"
            type="search"
            aria-label={t("search.search_notes")}
            aria-invalid={tooLong || undefined}
            aria-describedby={tooLong ? "search-too-long" : undefined}
            value={q}
            onChange={(e) => setQ(e.target.value)}
            autoFocus
            placeholder={t("search.a_word_a_phrase_a_label")}
          />
          <button className="button primary">{t("app.search")}</button>
        </div>
        {tooLong && (
          <p id="search-too-long" className="error" role="alert">
            {t("search.too_long", { limit: LIMIT, count: q.length })}
          </p>
        )}
        <details className="search-filter-disclosure" ref={filtersRef}>
          <summary className="button">
            <Icon name="tune" />
            {t(count ? "search.filters_active" : "search.filters", { count })}
            <Icon name="chevron" />
          </summary>
          <div className="search-filters">
            <label className="date-filter">
              {t("search.from")}
              <input
                className="field"
                aria-label={t("search.from_date")}
                type="date"
                value={from}
                max={to || undefined}
                aria-invalid={badDates || undefined}
                aria-describedby={badDates ? "search-dates" : undefined}
                onChange={(e) => update({ from: e.target.value })}
              />
            </label>
            <label className="date-filter">
              {t("search.to")}
              <input
                className="field"
                aria-label={t("search.to_date")}
                type="date"
                value={to}
                min={from || undefined}
                aria-invalid={badDates || undefined}
                aria-describedby={badDates ? "search-dates" : undefined}
                onChange={(e) => update({ to: e.target.value })}
              />
            </label>
            <label>
              <input
                type="checkbox"
                checked={archived}
                onChange={(e) =>
                  update({ archived: e.target.checked ? "1" : "" })
                }
              />
              {t("search.include_archive")}
            </label>
            <label>
              <input
                type="checkbox"
                checked={recent}
                onChange={(e) =>
                  update({ sort: e.target.checked ? "recent" : "" })
                }
              />
              {t("search.newest_first")}
            </label>
            {badDates && (
              <p id="search-dates" className="error" role="alert">
                {t("search.the_start_date_is_after_the_end_date")}
              </p>
            )}
            {count > 0 && (
              <button
                type="button"
                className="button search-clear-filters"
                onClick={() => {
                  filtersRef.current?.querySelector("summary")?.focus();
                  update({ from: "", to: "", archived: "", sort: "" });
                }}
              >
                {t("search.clear_filters")}
              </button>
            )}
          </div>
        </details>
      </form>
      {!submitted.trim() && (
        <p className="page-status">
          {t("search.search_for_a_word_a_phrase_or_a")}
        </p>
      )}
      {enabled && shown.isPending && (
        <p className="page-status" role="status">
          {t("search.searching_your_notes")}
        </p>
      )}
      {shown.error && (
        <p className="error" role="alert">
          {shown.error.message}{" "}
          <button className="button" onClick={() => void shown.refetch()}>
            {t("app.try_again")}
          </button>
        </p>
      )}
      {semantic === "unavailable" && !shown.error && (
        <p className="muted" role="status">
          {t("search.similar_meaning_is_unavailable_showing_keyword_matches")}
        </p>
      )}
      {enabled && !shown.isPending && !all.length && !shown.error && (
        <div className="empty">
          <h2>{t("search.no_results", { query: submitted.trim() })}</h2>
          <p>{t("search.try_fewer_words_search_ignores_accents_so_perche")}</p>
        </div>
      )}
      <p
        className={all.length && resultCount ? "search-count" : "sr-only"}
        role="status"
      >
        {resultCount}
      </p>
      <div className="search-results">
        {all.map((note) => (
          <NoteCard
            key={note.id}
            note={note}
            searchPreview={{
              title: highlight(note.title, words),
              excerpt: highlight(
                searchExcerpt(note.body, words, 240, note.title),
                words,
              ),
              meaning: note.match === "meaning",
            }}
          />
        ))}
      </div>
      {shown.hasNextPage && (
        <button
          className="button load-more"
          onClick={() => void shown.fetchNextPage()}
          disabled={shown.isFetchingNextPage}
        >
          {shown.isFetchingNextPage
            ? t("search.loading_more_results")
            : t("search.load_more_results")}
        </button>
      )}
    </>
  );
}
