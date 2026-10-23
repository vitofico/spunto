import { t, useLanguage } from "../lib/i18n";
import DOMPurify from "dompurify";
import { Link } from "wouter";
import { useQuery } from "@tanstack/react-query";
import { api } from "../lib/api";
import { lazy, Suspense } from "react";
const SchemaForm = lazy(() =>
  import("./SchemaForm").then((module) => ({ default: module.SchemaForm })),
);
import type { Args, Schema } from "../lib/types";
import type { Vocabulary } from "./types";
import styles from "./Feature.module.css";
import { record, validComponent } from "./vocabularyData";
export { validComponent, outputText } from "./vocabularyData";
export function safePluginHtml(html: string) {
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: [
      "p",
      "br",
      "strong",
      "em",
      "del",
      "blockquote",
      "pre",
      "code",
      "ul",
      "ol",
      "li",
      "h1",
      "h2",
      "h3",
      "h4",
      "h5",
      "h6",
      "a",
      "img",
      "hr",
      "table",
      "thead",
      "tbody",
      "tr",
      "th",
      "td",
    ],
    ALLOWED_ATTR: [
      "href",
      "src",
      "alt",
      "title",
      "type",
      "checked",
      "disabled",
    ],
    ALLOW_DATA_ATTR: false,
    FORBID_ATTR: ["style", "id", "name"],
    FORBID_TAGS: ["svg", "math", "script", "iframe", "form", "object", "embed"],
    ADD_URI_SAFE_ATTR: [],
  });
}
export function NoteReference({ id, reason }: { id: string; reason?: string }) {
  useLanguage();
  const query = useQuery({
    queryKey: ["note", id],
    queryFn: () => api.note(id),
    retry: false,
  });
  if (query.isError)
    return <span className="muted">{t("vocabulary.note_unavailable")}</span>;
  return (
    <Link className={styles.noteRef} href={`/n/${encodeURIComponent(id)}`}>
      {query.data
        ? query.data.title || t("vocabulary.untitled_note")
        : t("slots.open_note")}
      {reason && <small className="muted"> · {reason}</small>}
    </Link>
  );
}
export type Callbacks = {
  formLabel?: string;
  onSuggestion?: (id: string) => void;
  onCommand?: (id: string, args: Args) => void;
  onForm?: (args: Args) => void;
  allow?: string[];
};
export function VocabularyItem({
  value,
  depth = 0,
  ...actions
}: { value: unknown; depth?: number } & Callbacks) {
  useLanguage();
  if (depth > 3) return null;
  if (!validComponent(value)) {
    if (record(value) && value.fallback === "Open on the web")
      return <p>{t("vocabulary.open_on_the_web")}</p>;
    if (record(value) && record(value.fallback))
      return (
        <VocabularyItem value={value.fallback} depth={depth + 1} {...actions} />
      );
    return null;
  }
  if (actions.allow && !actions.allow.includes(value.type)) return null;
  switch (value.type) {
    case "text":
      return <p>{String(value.text)}</p>;
    case "markdown":
      return typeof value.html === "string" ? (
        <div dangerouslySetInnerHTML={{ __html: safePluginHtml(value.html) }} />
      ) : (
        <p>{String(value.text ?? value.markdown)}</p>
      );
    case "notes":
      return (
        <div>
          {((value.ids ?? value.items) as unknown[]).map((item, index) =>
            typeof item === "string" ? (
              <NoteReference key={`${item}-${index}`} id={item} />
            ) : (
              <NoteReference
                key={index}
                id={String((item as Record<string, unknown>).id)}
                reason={(item as { reason?: string }).reason}
              />
            ),
          )}
        </div>
      );
    case "pill":
      return (
        <button
          className={styles.pill}
          disabled={!actions.onSuggestion}
          onClick={() => actions.onSuggestion?.(String(value.suggestion_id))}
        >
          {String(value.label)}
        </button>
      );
    case "command":
      return (
        <button
          disabled={!actions.onCommand}
          onClick={() =>
            actions.onCommand?.(String(value.id), (value.args ?? {}) as Args)
          }
        >
          {String(value.title)}
        </button>
      );
    case "form":
      return (
        <Suspense fallback={null}>
          <SchemaForm
            schema={value.schema as Schema}
            hiddenFields={Object.entries(
              (value.schema as Schema).properties ?? {},
            )
              .filter(
                ([key, field]) =>
                  [
                    "request_id",
                    "note_id",
                    "pack_id",
                    "decision_id",
                    "expected_revision",
                    "base_version",
                    "revision",
                    "source_choices",
                    "removal_choices",
                  ].includes(key) && field.default !== undefined,
              )
              .map(([key]) => key)}
            onSave={(values) => actions.onForm?.(values)}
            disabled={!actions.onForm}
            submitLabel={actions.formLabel ?? t("settingsplugins.run")}
          />
        </Suspense>
      );
    case "diff":
      return (
        <div>
          <p className={styles.facts}>
            {t("suggestionpreview.based_on_version")}
            {Number(value.base_version)}
          </p>
          {(
            value.hunks as {
              old_lines?: string[];
              new_lines?: string[];
              lines?: string[];
            }[]
          ).map((hunk, index) => (
            <div className={styles.diff} key={index}>
              <pre aria-label={t("suggestionpreview.before")}>
                {(hunk.old_lines ?? hunk.lines ?? []).join("\n")}
              </pre>
              <pre aria-label={t("suggestionpreview.after")}>
                {(hunk.new_lines ?? hunk.lines ?? []).join("\n")}
              </pre>
            </div>
          ))}
        </div>
      );
    default:
      return null;
  }
}
export function VocabularyOutput({
  value,
  ...actions
}: { value: unknown } & Callbacks) {
  useLanguage();
  if (Array.isArray(value))
    return (
      <>
        {value.slice(0, 10).map((item, index) => (
          <VocabularyItem value={item} key={index} {...actions} />
        ))}
      </>
    );
  if (record(value) && !("type" in value)) {
    if (
      Object.keys(value).some(
        (key) => !["ui", "items", "components"].includes(key),
      ) ||
      (value.ui !== undefined && value.ui !== 1)
    )
      return null;
    return (
      <VocabularyOutput
        value={value.items ?? value.components ?? []}
        {...actions}
      />
    );
  }
  return <VocabularyItem value={value} {...actions} />;
}
