import { useState } from "react";
import { useLocation } from "wouter";
import { useSearch } from "../lib";
import { NoteCard } from "../components/core/NoteCard";
export default function SearchPage() {
  const [, navigate] = useLocation();
  const params = new URLSearchParams(window.location.search);
  const [q, setQ] = useState(params.get("q") ?? "");
  const [submitted, setSubmitted] = useState(q);
  const [from, setFrom] = useState(params.get("from") ?? "");
  const [to, setTo] = useState(params.get("to") ?? "");
  const [archived, setArchived] = useState(false);
  const [recent, setRecent] = useState(false);
  const filters = {
    from: from || undefined,
    to: to || undefined,
    include_archived: archived,
    sort: recent ? ("recent" as const) : ("relevance" as const),
    limit: 50,
  };
  const keyword = useSearch(submitted, { ...filters, mode: "keyword" });
  const hybrid = useSearch(
    submitted,
    { ...filters, mode: "hybrid" },
    !!submitted.trim(),
  );
  const semantic = hybrid.data?.pages[0]?.semantic;
  const results = semantic === "on" ? hybrid : keyword;
  const all = results.data?.pages.flatMap((page) => page.hits) ?? [];
  return (
    <>
      <h1 className="page-title">Search your notes</h1>
      <form
        className="search-form"
        onSubmit={(e) => {
          e.preventDefault();
          setSubmitted(q);
          navigate("/search?q=" + encodeURIComponent(q));
        }}
      >
        <div className="row">
          <input
            className="field"
            aria-label="Search notes"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            autoFocus
            placeholder="A word, a phrase, a #label…"
          />
          <button className="button primary">Search</button>
        </div>
        <div className="search-filters">
          <label>
            From{" "}
            <input
              aria-label="From date"
              type="date"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
            />
          </label>
          <label>
            To{" "}
            <input
              aria-label="To date"
              type="date"
              value={to}
              onChange={(e) => setTo(e.target.value)}
            />
          </label>
          <label>
            <input
              type="checkbox"
              checked={archived}
              onChange={(e) => setArchived(e.target.checked)}
            />{" "}
            Include archive
          </label>
          <label>
            <input
              type="checkbox"
              checked={recent}
              onChange={(e) => setRecent(e.target.checked)}
            />{" "}
            Newest first
          </label>
        </div>
      </form>
      {!submitted.trim() && (
        <p className="page-status">
          Search for a word, a phrase, or a #label to find a note.
        </p>
      )}
      {submitted.trim() && results.isPending && (
        <p className="page-status" role="status">
          Searching your notes…
        </p>
      )}
      {results.error && (
        <p className="error" role="alert">
          {results.error.message}{" "}
          <button className="button" onClick={() => void results.refetch()}>
            Try again
          </button>
        </p>
      )}
      {(semantic === "unavailable" || hybrid.error) && (
        <p className="muted" role="status">
          Similar meaning is unavailable. Showing keyword matches.
        </p>
      )}
      {submitted.trim() &&
        !results.isPending &&
        !all.length &&
        !results.error && (
          <p className="muted">
            No notes match “{submitted}”. Try another word or a wider date
            range.
          </p>
        )}
      {all.map((note) => (
        <div key={note.id}>
          {note.match === "meaning" && (
            <p className="muted">≈ similar meaning</p>
          )}
          <NoteCard note={note} />
        </div>
      ))}
      {results.hasNextPage && (
        <button
          className="button load-more"
          onClick={() => void results.fetchNextPage()}
          disabled={results.isFetchingNextPage}
        >
          {results.isFetchingNextPage
            ? "Loading more results…"
            : "Load more results"}
        </button>
      )}
    </>
  );
}
