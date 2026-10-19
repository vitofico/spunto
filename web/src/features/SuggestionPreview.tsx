import { useQuery } from "@tanstack/react-query";
import { api } from "../lib/api";
import type { Suggestion } from "../lib/types";
import { NoteReference } from "./Vocabulary";
import styles from "./Feature.module.css";
import { labelChangeImpact } from "./labelChange";
export function suggestionTitle(s: Suggestion) {
  const p = s.payload;
  switch (s.action) {
    case "add_label":
      return `#${String(p.path)}`;
    case "set_title":
      return `Title: ${String(p.title)}`;
    case "append_text":
      return "Append text";
    case "replace_body":
      return "Review edit";
    case "merge_notes":
      return "Compare notes";
    case "label_change":
      return Array.isArray(p.note_ids)
        ? `Add #${String(p.new)} to ${p.note_ids.length} notes`
        : p.new === null
          ? `Remove #${String(p.old)}`
          : `#${String(p.old)} → #${String(p.new)}`;
    case "open":
      return "Open";
    default:
      return "Review suggestion";
  }
}
export function SuggestionPreview({
  suggestion: s,
}: {
  suggestion: Suggestion;
}) {
  const note = useQuery({
    queryKey: ["note", s.note_id],
    queryFn: () => api.note(s.note_id!),
    enabled: !!s.note_id,
  });
  const source = useQuery({
    queryKey: ["note", s.payload.source_id],
    queryFn: () => api.note(String(s.payload.source_id)),
    enabled: s.action === "merge_notes",
  });
  const impact = useQuery({
    queryKey: [
      "label-impact",
      s.payload.old,
      s.payload.new,
      s.payload.note_ids,
    ],
    queryFn: () =>
      api.request<{ notes: number }>("/api/v1/labels/impact", {
        method: "POST",
        body: labelChangeImpact(s.payload),
      }),
    enabled: s.action === "label_change",
    retry: false,
  });
  if (s.action === "label_change")
    return (
      <div>
        {Array.isArray(s.payload.note_ids) ? (
          <p>
            Add <strong>#{String(s.payload.new)}</strong> to{" "}
            {s.payload.note_ids.length} selected unlabeled notes.
          </p>
        ) : s.payload.new === null ? (
          <p>
            Remove <strong>#{String(s.payload.old)}</strong> from affected
            notes, including nested labels.
          </p>
        ) : (
          <p>
            Rename or merge <strong>#{String(s.payload.old)}</strong> into{" "}
            <strong>#{String(s.payload.new)}</strong>.
          </p>
        )}
        {impact.isPending ? (
          <p>Counting affected notes…</p>
        ) : impact.data ? (
          <p>
            {impact.data.notes} {impact.data.notes === 1 ? "note" : "notes"}{" "}
            will change
            {Array.isArray(s.payload.note_ids)
              ? "."
              : ", including nested labels."}
          </p>
        ) : (
          <p className={styles.error} role="alert">
            The affected notes could not be counted. Try again.
          </p>
        )}
        <p className={styles.muted}>
          {Array.isArray(s.payload.note_ids)
            ? "Each selected note must still be unchanged and unlabeled when you apply."
            : "The change is applied to your latest notes. Their words stay."}
        </p>
      </div>
    );
  if (s.action === "replace_body")
    return (
      <div>
        <p className={styles.facts}>
          Based on version {s.note_version}. A changed note will require a fresh
          suggestion.
        </p>
        <div className={styles.diff}>
          <div>
            <h3>Before</h3>
            <pre>{note.data?.body ?? "Loading note…"}</pre>
          </div>
          <div>
            <h3>After</h3>
            <pre>{String(s.payload.body ?? "")}</pre>
          </div>
        </div>
      </div>
    );
  if (s.action === "merge_notes")
    return (
      <div>
        <p>
          The source will be appended to the destination and moved to Trash.
        </p>
        <div className={styles.diff}>
          <div>
            <h3>Destination</h3>
            <pre>{note.data?.body ?? "Loading note…"}</pre>
          </div>
          <div>
            <h3>Source</h3>
            <pre>{source.data?.body ?? "Loading note…"}</pre>
          </div>
        </div>
      </div>
    );
  if (s.action === "append_text")
    return (
      <blockquote className={styles.quote}>{String(s.payload.text)}</blockquote>
    );
  if (s.note_id) return <NoteReference id={s.note_id} />;
  return <p>{s.reason}</p>;
}
