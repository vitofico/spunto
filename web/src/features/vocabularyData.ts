import { validSchema } from "./validSchema";
import type { Vocabulary } from "./types";
const fields: Record<string, string[]> = {
  text: ["text"],
  markdown: ["text", "markdown", "html"],
  notes: ["ids", "items"],
  pill: ["suggestion_id", "label", "action"],
  command: ["id", "args", "title"],
  form: ["schema"],
  diff: ["hunks", "base_version", "text"],
};
export const record = (value: unknown): value is Record<string, unknown> =>
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
