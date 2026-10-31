import { t, useLanguage } from "../lib/i18n";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useLocation, useSearch as useLocationSearch } from "wouter";
import { fold, useSearch } from "../lib";
import { useDocumentTitle } from "../lib/useDocumentTitle";
import { searchExcerpt } from "../lib/searchExcerpt";
import { NoteCard } from "../components/core/NoteCard";

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
/**
 * Wraps word-start matches in <mark>, accent- and case-insensitively. Only inside
 * rendered note bodies: their HTML is set as a whole, so React tracks none of it.
 */
function markMatches(body: HTMLElement, words: string[]) {
  for (const mark of body.querySelectorAll("mark[data-match]"))
    mark.replaceWith(...mark.childNodes);
  body.normalize();
  if (!words.length) return;
  const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
  const nodes: Text[] = [];
  while (walker.nextNode()) nodes.push(walker.currentNode as Text);
  for (const node of nodes) {
    if (node.parentElement?.closest("code,pre")) continue;
    // Fold character by character, remembering where each folded unit came from.
    let folded = "";
    const origin: number[] = [];
    for (let at = 0; at < node.data.length;) {
      const char = String.fromCodePoint(node.data.codePointAt(at)!);
      for (const unit of fold(char)) {
        folded += unit;
        for (let index = 0; index < unit.length; index++) origin.push(at);
      }
      at += char.length;
    }
    const spans: [number, number][] = [];
    for (const word of words) {
      let from = 0;
      for (;;) {
        const hit = folded.indexOf(word, from);
        if (hit < 0) break;
        from = hit + word.length;
        if (hit > 0 && /[\p{L}\p{N}]/u.test(folded[hit - 1])) continue;
        const last = origin[hit + word.length - 1];
        const end =
          last + String.fromCodePoint(node.data.codePointAt(last)!).length;
        spans.push([origin[hit], end]);
      }
    }
    spans.sort((a, b) => b[0] - a[0]);
    let previous = Infinity;
    for (const [start, end] of spans) {
      if (end > previous) continue;
      const range = document.createRange();
      range.setStart(node, start);
      range.setEnd(node, end);
      const mark = document.createElement("mark");
      mark.dataset.match = "";
      range.surroundContents(mark);
      previous = start;
    }
  }
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
  const results = useRef<HTMLDivElement>(null);
  useDocumentTitle(
    submitted.trim() ? `“${submitted.trim()}” · Search` : t("app.search"),
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
  const badDates = !!from && !!to && from > to;
  const tooLong = q.length > LIMIT;
  const enabled = !badDates && !!submitted.trim();
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
    !badDates,
  );
  const hybrid = useSearch(submitted, { ...filters, mode: "hybrid" }, enabled);
  const semantic = hybrid.data?.pages[0]?.semantic;
  const shown = semantic === "on" ? hybrid : keyword;
  const all = shown.data?.pages.flatMap((page) => page.hits) ?? [];
  useLayoutEffect(() => {
    const words = terms(submitted);
    for (const body of results.current?.querySelectorAll<HTMLElement>(
      ".note-body",
    ) ?? [])
      markMatches(body, words);
  });
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
        </div>
        {badDates && (
          <p id="search-dates" className="error" role="alert">
            {t("search.the_start_date_is_after_the_end_date")}
          </p>
        )}
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
      <p className="sr-only" role="status">
        {enabled && !shown.isPending && !shown.error
          ? t(shown.hasNextPage ? "search.found_more" : "search.found", {
              count: all.length,
            })
          : ""}
      </p>
      <div ref={results}>
        {all.map((note) => (
          <div key={note.id}>
            <p className="muted">
              {t(
                note.match === "meaning"
                  ? "search.similar_meaning"
                  : "search.terms_match",
              )}
            </p>
            <NoteCard
              note={note}
              excerpt={searchExcerpt(note.body, terms(submitted))}
            />
          </div>
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
