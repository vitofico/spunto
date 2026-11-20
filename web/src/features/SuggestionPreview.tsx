import { t, useLanguage } from "../lib/i18n";
import { useQuery } from "@tanstack/react-query";
import { api } from "../lib/api";
import { NoteDiff } from "../components/core/NoteDiff";
import type { Note, Suggestion } from "../lib/types";
import { NoteReference } from "./Vocabulary";
import styles from "./Feature.module.css";
import { labelChangeImpact } from "./labelChange";
export { suggestionTitle } from "./suggestionTitle";
export function SuggestionPreview({
  suggestion: s,
  baseNote,
}: {
  suggestion: Suggestion;
  baseNote?: Note;
}) {
  useLanguage();
  const note = useQuery({
    queryKey: ["note", s.note_id],
    queryFn: () => api.note(s.note_id!),
    enabled: !!s.note_id && !baseNote,
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
            {t("suggestionpreview.cluster", {
              label: String(s.payload.new),
              count: s.payload.note_ids.length,
            })}
          </p>
        ) : s.payload.new === null ? (
          <p>
            {t("suggestionpreview.remove_label", {
              label: String(s.payload.old),
            })}
          </p>
        ) : (
          <p>
            {t("suggestionpreview.rename_label", {
              old: String(s.payload.old),
              new: String(s.payload.new),
            })}
          </p>
        )}
        {s.reason && <p>{s.reason}</p>}
        {Array.isArray(s.payload.note_ids) && (
          <ul>
            {s.payload.note_ids.map((id) => (
              <li key={String(id)}>
                <NoteReference id={String(id)} />
              </li>
            ))}
          </ul>
        )}
        {impact.isPending ? (
          <p>{t("suggestionpreview.counting_affected_notes")}</p>
        ) : impact.data ? (
          <p>
            {t(
              Array.isArray(s.payload.note_ids)
                ? "suggestionpreview.changed_count"
                : "suggestionpreview.changed_nested",
              { count: impact.data.notes },
            )}
          </p>
        ) : (
          <p className="error" role="alert">
            {t("suggestionpreview.the_affected_notes_could_not_be_counted_try")}
          </p>
        )}
        <p className="muted">
          {Array.isArray(s.payload.note_ids)
            ? t(
                "suggestionpreview.each_selected_note_must_still_be_unchanged_and",
              )
            : t("suggestionpreview.the_change_is_applied_to_your_latest_notes")}
        </p>
      </div>
    );
  if (s.action === "replace_body") {
    const base = baseNote ?? note.data;
    const proposed = String(s.payload.body ?? "");
    const labels = {
      before: t("suggestionpreview.before"),
      after: t("suggestionpreview.after"),
    };
    return (
      <div>
        <p className={styles.facts}>
          {t("suggestionpreview.version", { version: s.note_version ?? "" })}
        </p>
        {base && base.id === s.note_id && base.version === s.note_version ? (
          <NoteDiff before={base.body} after={proposed} labels={labels} />
        ) : (
          <div className={styles.diff}>
            <div>
              <h3>{labels.before}</h3>
              <p>
                {t(
                  base ? "note.accept_stale" : "suggestionpreview.loading_note",
                )}
              </p>
            </div>
            <div>
              <h3>{labels.after}</h3>
              <pre>{proposed}</pre>
            </div>
          </div>
        )}
      </div>
    );
  }
  if (s.action === "merge_notes")
    return (
      <div>
        <p>
          {t(
            "suggestionpreview.the_source_will_be_appended_to_the_destination",
          )}
        </p>
        <div className={styles.diff}>
          <div>
            <h3>{t("suggestionpreview.destination")}</h3>
            <pre>
              {(baseNote ?? note.data)?.body ??
                t("suggestionpreview.loading_note")}
            </pre>
          </div>
          <div>
            <h3>{t("suggestionpreview.source")}</h3>
            <pre>
              {source.data?.body ?? t("suggestionpreview.loading_note")}
            </pre>
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
