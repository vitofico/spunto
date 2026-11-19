import { t, useLanguage } from "../lib/i18n";
import DOMPurify from "dompurify";
import { Link } from "wouter";
import { useQuery } from "@tanstack/react-query";
import { api } from "../lib/api";
import { NoteDiff } from "../components/core/NoteDiff";
import { lazy, Suspense, type ReactNode } from "react";
const SchemaForm = lazy(() =>
  import("./SchemaForm").then((module) => ({ default: module.SchemaForm })),
);
import type { Args, Json, Schema } from "../lib/types";
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
export function ResultNoteLink({
  id,
  onOpen,
  className,
  children,
}: {
  id: string;
  onOpen?: () => void;
  className?: string;
  children: ReactNode;
}) {
  return (
    <Link
      className={className}
      href={`/n/${encodeURIComponent(id)}`}
      tabIndex={0}
      onClick={(event) => {
        if (
          !event.defaultPrevented &&
          event.button === 0 &&
          !event.metaKey &&
          !event.ctrlKey &&
          !event.shiftKey &&
          !event.altKey
        )
          onOpen?.();
      }}
    >
      {children}
    </Link>
  );
}
export function NoteReference({
  id,
  reason,
  onOpen,
  result,
}: {
  id: string;
  reason?: string;
  onOpen?: () => void;
  result?: boolean;
}) {
  useLanguage();
  const query = useQuery({
    queryKey: ["note", id],
    queryFn: () => api.note(id),
    retry: false,
  });
  if (query.isError)
    return <span className="muted">{t("vocabulary.note_unavailable")}</span>;
  return (
    <ResultNoteLink id={id} className={styles.noteRef} onOpen={onOpen}>
      <span>
        {query.data
          ? query.data.title || t("vocabulary.untitled_note")
          : t("slots.open_note")}
      </span>
      {reason && (
        <small className="muted">{result ? reason : ` · ${reason}`}</small>
      )}
    </ResultNoteLink>
  );
}
export type Callbacks = {
  formLabel?: string;
  formSchema?: (schema: Schema) => Schema;
  formChoices?: (key: string, value: Json) => string | undefined;
  formDetails?: { label: string; fields: readonly string[] };
  formSubmitDisabled?: boolean;
  commandLabel?: (id: string, title: string, args: Args) => string;
  displayText?: (text: string) => string;
  result?: boolean;
  onOpenNote?: () => void;
  primaryCommand?: string;
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
      return (
        <p>{actions.displayText?.(String(value.text)) ?? String(value.text)}</p>
      );
    case "markdown":
      return typeof value.html === "string" ? (
        <div dangerouslySetInnerHTML={{ __html: safePluginHtml(value.html) }} />
      ) : (
        <p>{String(value.text ?? value.markdown)}</p>
      );
    case "notes": {
      const notes = (value.ids ?? value.items) as (
        string | { id: string; reason?: string }
      )[];
      if (!notes.length) return null;
      const List = actions.result ? "ul" : "div";
      const Item = actions.result ? "li" : "div";
      return (
        <List
          className={actions.result ? styles.resultNotes : undefined}
          role={actions.result ? "list" : undefined}
          aria-label={actions.result ? t("review.notes") : undefined}
        >
          {notes.map((item, index) => {
            const { id, reason } =
              typeof item === "string" ? { id: item } : item;
            return (
              <Item key={`${id}-${index}`}>
                <NoteReference
                  id={id}
                  reason={reason && (actions.displayText?.(reason) ?? reason)}
                  result={actions.result}
                  onOpen={actions.onOpenNote}
                />
              </Item>
            );
          })}
        </List>
      );
    }
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
          className={
            actions.result
              ? actions.primaryCommand === value.id
                ? "primary"
                : "button"
              : undefined
          }
          disabled={!actions.onCommand}
          onClick={() =>
            actions.onCommand?.(String(value.id), (value.args ?? {}) as Args)
          }
        >
          {actions.commandLabel?.(
            String(value.id),
            String(value.title),
            (value.args ?? {}) as Args,
          ) ?? String(value.title)}
        </button>
      );
    case "form":
      return (
        <Suspense fallback={null}>
          <SchemaForm
            schema={
              actions.formSchema?.(value.schema as Schema) ??
              (value.schema as Schema)
            }
            optionLabel={actions.formChoices}
            optionalDetails={actions.formDetails}
            submitDisabled={actions.formSubmitDisabled}
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
                    "selected_ids",
                    "max_sources",
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
          ).map((hunk, index) => {
            const before = hunk.lines
              ?.filter((line) => !line.startsWith("+"))
              .map((line) => line.replace(/^[- ]/, ""));
            const after = hunk.lines
              ?.filter((line) => !line.startsWith("-"))
              .map((line) => line.replace(/^[+ ]/, ""));
            return (
              <NoteDiff
                key={index}
                before={(before ?? hunk.old_lines ?? []).join("\n")}
                after={(after ?? hunk.new_lines ?? []).join("\n")}
                labels={{
                  before: t("suggestionpreview.before"),
                  after: t("suggestionpreview.after"),
                }}
              />
            );
          })}
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
