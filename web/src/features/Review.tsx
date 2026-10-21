import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocation } from "wouter";
import { api } from "../lib/api";
import { useDocumentTitle } from "../lib/useDocumentTitle";
import type { Suggestion } from "../lib/types";
import { Dialog } from "./Dialog";
import { NoteReference } from "./Vocabulary";
import styles from "./Feature.module.css";
import { SuggestionPreview, suggestionTitle } from "./SuggestionPreview";
import { labelChangeImpact } from "./labelChange";
import { activePlugins, usePlugins } from "./Slots";
export function ReviewPage() {
  useDocumentTitle("Review");
  const [, navigate] = useLocation();
  const query = useQuery({
    queryKey: ["suggestions", "review"],
    queryFn: () => api.suggestions("review"),
  });
  const manifest = usePlugins();
  const plugins = activePlugins(manifest.data?.plugins);
  const client = useQueryClient();
  const [preview, setPreview] = useState<Suggestion | null>(null);
  const [one, setOne] = useState(false);
  const [skip, setSkip] = useState<string[]>([]);
  const [expanded, setExpanded] = useState<string[]>([]);
  const impact = useQuery({
    queryKey: [
      "label-impact",
      preview?.payload.old,
      preview?.payload.new,
      preview?.payload.note_ids,
    ],
    queryFn: () =>
      api.request<{ notes: number }>("/api/v1/labels/impact", {
        method: "POST",
        body: labelChangeImpact(preview?.payload),
      }),
    enabled: preview?.action === "label_change",
    retry: false,
  });
  const decide = useMutation({
    mutationFn: ({
      s,
      action,
    }: {
      s: Suggestion;
      action: "accept" | "dismiss";
    }) => api.decideSuggestion(s.id, action),
    onSuccess: (result) => {
      const open = (
        result as {
          result?: {
            open?: { note_id?: string; plugin_id?: string; view?: string };
          };
        }
      )?.result?.open;
      if (open?.note_id) navigate(`/n/${encodeURIComponent(open.note_id)}`);
      else if (open?.plugin_id && open.view)
        navigate(
          `/v/${encodeURIComponent(open.plugin_id)}/${encodeURIComponent(open.view)}`,
        );
      setPreview(null);
      void client.invalidateQueries({ queryKey: ["suggestions"] });
      void client.invalidateQueries({ queryKey: ["notes"] });
      void client.invalidateQueries({ queryKey: ["note"] });
      void client.invalidateQueries({ queryKey: ["labels"] });
    },
  });
  const suggestions =
    query.data?.suggestions.filter(
      (s) => plugins.some((p) => p.id === s.plugin) && !skip.includes(s.id),
    ) ?? [];
  const apply = (s: Suggestion) =>
    ["replace_body", "merge_notes", "label_change"].includes(s.action)
      ? setPreview(s)
      : decide.mutate({ s, action: "accept" });
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (
        !one ||
        preview ||
        e.target instanceof HTMLInputElement ||
        e.target instanceof HTMLTextAreaElement ||
        !suggestions[0]
      )
        return;
      if (e.key === "a") {
        e.preventDefault();
        apply(suggestions[0]);
      }
      if (e.key === "x") {
        e.preventDefault();
        decide.mutate({ s: suggestions[0], action: "dismiss" });
      }
      if (e.key === "s") {
        e.preventDefault();
        setSkip([...skip, suggestions[0].id]);
      }
    };
    document.addEventListener("keydown", key);
    return () => document.removeEventListener("keydown", key);
  });
  const groups = ["Notes", "Labels", "Duplicates"];
  const group = (s: Suggestion) =>
    s.action === "label_change"
      ? "Labels"
      : s.action === "merge_notes"
        ? "Duplicates"
        : "Notes";
  return (
    <div className={styles.review}>
      <div className={styles.reviewHeader}>
        <h1 className="page-title">Review</h1>
        {suggestions.length > 0 && (
          <span className={styles.facts}>{suggestions.length} waiting</span>
        )}
      </div>
      {query.isPending ? (
        <p>Loading suggestions…</p>
      ) : query.isError ? (
        <p className="error" role="alert">
          {query.error.message}
        </p>
      ) : !suggestions.length ? (
        <div className="empty">
          <h2>All caught up.</h2>
          {skip.length > 0 && (
            <button onClick={() => setSkip([])}>
              Return to skipped suggestions
            </button>
          )}
        </div>
      ) : (
        <>
          <div className={styles.row}>
            <button
              aria-pressed={one}
              onClick={() => {
                setOne(!one);
                setSkip([]);
              }}
            >
              {one ? "Grouped queue" : "One by one"}
            </button>
            {one && (
              <small className={styles.facts}>
                a Apply · s Skip · x Dismiss
              </small>
            )}
          </div>
          {(one ? ["Current"] : groups).map((name) => {
            const items = one
              ? suggestions.slice(0, 1)
              : suggestions.filter((s) => group(s) === name);
            if (!items.length) return null;
            const shown = expanded.includes(name) ? items : items.slice(0, 3);
            return (
              <section key={name} aria-label={name}>
                <h2 className="overline">{name}</h2>
                {shown.map((s) => (
                  <article className={styles.proposal} key={s.id}>
                    <div className={styles.between}>
                      <strong>{suggestionTitle(s)}</strong>
                      <span className={styles.facts}>
                        {plugins.find((p) => p.id === s.plugin)?.name}
                      </span>
                    </div>
                    {s.note_id && <NoteReference id={s.note_id} />}
                    <p>{s.reason}</p>
                    <div className={styles.row}>
                      <button
                        className="primary"
                        disabled={decide.isPending || !navigator.onLine}
                        onClick={() => apply(s)}
                      >
                        {!navigator.onLine
                          ? "Needs a connection"
                          : [
                                "replace_body",
                                "merge_notes",
                                "label_change",
                              ].includes(s.action)
                            ? "Compare"
                            : "Apply"}
                      </button>
                      {one && (
                        <button onClick={() => setSkip([...skip, s.id])}>
                          Skip
                        </button>
                      )}
                      <button
                        disabled={decide.isPending}
                        onClick={() => decide.mutate({ s, action: "dismiss" })}
                      >
                        {s.action === "merge_notes" ? "Keep both" : "Dismiss"}
                      </button>
                    </div>
                  </article>
                ))}
                {items.length > 3 && !expanded.includes(name) && (
                  <button onClick={() => setExpanded([...expanded, name])}>
                    {items.length - 3} more
                  </button>
                )}
              </section>
            );
          })}
        </>
      )}
      {decide.isError && (
        <p className="error" role="alert">
          {decide.error.message}
        </p>
      )}
      {preview && (
        <Dialog
          title={suggestionTitle(preview)}
          onClose={() => setPreview(null)}
          footer={
            <>
              <button onClick={() => setPreview(null)}>Cancel</button>
              <button
                className="primary"
                disabled={
                  decide.isPending ||
                  (preview.action === "label_change" && !impact.data)
                }
                onClick={() => decide.mutate({ s: preview, action: "accept" })}
              >
                {preview.action === "merge_notes" ? "Merge notes" : "Apply"}
              </button>
            </>
          }
        >
          <SuggestionPreview suggestion={preview} />
        </Dialog>
      )}
    </div>
  );
}
