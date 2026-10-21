import DOMPurify from "dompurify";
import { Link } from "wouter";
import { useQuery } from "@tanstack/react-query";
import { api } from "../lib/api";
import { SchemaForm, validSchema } from "./SchemaForm";
import type { Args, Schema } from "../lib/types";
import type { Vocabulary } from "./types";
import styles from "./Feature.module.css";
const fields: Record<string, string[]> = {
  text: ["text"],
  markdown: ["text", "markdown", "html"],
  notes: ["ids", "items"],
  pill: ["suggestion_id", "label", "action"],
  command: ["id", "args", "title"],
  form: ["schema"],
  diff: ["hunks", "base_version", "text"],
};
const record = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === "object" && !Array.isArray(value);
const string = (value: unknown, max: number) =>
  typeof value === "string" && [...value].length <= max;
export function validComponent(value: unknown): value is Vocabulary {
  if (!record(value) || typeof value.type !== "string" || !fields[value.type])
    return false;
  if (
    Object.keys(value).some(
      (key) =>
        !["type", "fallback", ...fields[value.type as string]].includes(key),
    )
  )
    return false;
  switch (value.type) {
    case "text":
      return string(value.text, 280);
    case "markdown":
      return (
        string(value.text ?? value.markdown, 4000) &&
        (value.html === undefined || string(value.html, 64000))
      );
    case "notes": {
      const items = value.ids ?? value.items;
      return (
        Array.isArray(items) &&
        items.length <= 10 &&
        items.every(
          (item) =>
            string(item, 128) ||
            (record(item) &&
              Object.keys(item).every((k) => ["id", "reason"].includes(k)) &&
              string(item.id, 128) &&
              (item.reason === undefined || string(item.reason, 280))),
        )
      );
    }
    case "pill":
      return (
        string(value.suggestion_id, 128) &&
        string(value.label, 40) &&
        string(value.action, 40)
      );
    case "command":
      return (
        string(value.id, 128) &&
        string(value.title, 24) &&
        (value.args === undefined || record(value.args))
      );
    case "form":
      return validSchema(value.schema);
    case "diff":
      return (
        Number.isInteger(value.base_version) &&
        Number(value.base_version) > 0 &&
        Array.isArray(value.hunks) &&
        value.hunks.length <= 100 &&
        value.hunks.every(
          (h) =>
            record(h) &&
            Object.keys(h).every((k) =>
              [
                "old_start",
                "new_start",
                "old_lines",
                "new_lines",
                "lines",
              ].includes(k),
            ) &&
            Object.entries(h).every(([k, v]) =>
              k.endsWith("start")
                ? Number.isInteger(v)
                : Array.isArray(v) &&
                  v.length <= 4000 &&
                  v.every((line) => string(line, 4000)),
            ),
        )
      );
    default:
      return false;
  }
}
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
  const query = useQuery({
    queryKey: ["note", id],
    queryFn: () => api.note(id),
    retry: false,
  });
  if (query.isError) return <span className="muted">Note unavailable</span>;
  return (
    <Link className={styles.noteRef} href={`/n/${encodeURIComponent(id)}`}>
      {query.data?.body.slice(0, 100) ?? "Open note"}
      {reason && <small className="muted"> · {reason}</small>}
    </Link>
  );
}
type Callbacks = {
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
  if (depth > 3) return null;
  if (!validComponent(value)) {
    if (record(value) && value.fallback === "Open on the web")
      return <p>Open on the web</p>;
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
          submitLabel={actions.formLabel ?? "Run"}
        />
      );
    case "diff":
      return (
        <div>
          <p className={styles.facts}>
            Based on version {Number(value.base_version)}
          </p>
          {(
            value.hunks as {
              old_lines?: string[];
              new_lines?: string[];
              lines?: string[];
            }[]
          ).map((hunk, index) => (
            <div className={styles.diff} key={index}>
              <pre aria-label="Before">
                {(hunk.old_lines ?? hunk.lines ?? []).join("\n")}
              </pre>
              <pre aria-label="After">
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
export function outputText(value: unknown, depth = 0): string {
  if (depth > 3) return "";
  if (Array.isArray(value))
    return value
      .slice(0, 10)
      .map((item) => outputText(item, depth + 1))
      .filter(Boolean)
      .join("\n\n");
  if (!record(value)) return "";
  if (!("type" in value))
    return outputText(value.items ?? value.components, depth + 1);
  if (!validComponent(value)) return outputText(value.fallback, depth + 1);
  if (value.type === "text" || value.type === "markdown")
    return String(value.text ?? value.markdown ?? "");
  if (value.type === "diff") return String(value.text ?? "");
  if (value.type === "notes")
    return ((value.ids ?? value.items) as unknown[])
      .map((item) =>
        typeof item === "string"
          ? `[Note](/n/${encodeURIComponent(item)})`
          : `[Note](/n/${encodeURIComponent(String((item as Record<string, unknown>).id))})`,
      )
      .join("\n");
  return "";
}
