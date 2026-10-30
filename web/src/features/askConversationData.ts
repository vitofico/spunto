import { record, validComponent } from "./vocabularyData";
import type { Args } from "../lib/types";
import type { Vocabulary } from "./types";

export function askItems(value: unknown): Vocabulary[] {
  if (record(value) && value.ui !== undefined && value.ui !== 1) return [];
  const items = Array.isArray(value)
    ? value
    : record(value) && "type" in value
      ? [value]
      : record(value)
        ? (value.items ?? value.components)
        : [];
  return Array.isArray(items) && items.length <= 10
    ? items.filter(validComponent)
    : [];
}
export function askCommand(value: unknown, id: string): Args | undefined {
  const item = askItems(value).find(
    (item) => item.type === "command" && item.id === id,
  );
  return item && record(item.args) ? (item.args as Args) : undefined;
}
export function askContinuation(value: unknown): Args | undefined {
  const args = askCommand(value, "ask");
  return args &&
    typeof args.conversation_id === "string" &&
    args.conversation_id.length > 0 &&
    Number.isInteger(args.after_turn) &&
    Number(args.after_turn) >= 0
    ? args
    : undefined;
}
export function askSources(value: unknown): { id: string; reason?: string }[] {
  return askItems(value)
    .filter((item) => item.type === "notes")
    .flatMap((item) =>
      ((item.ids ?? item.items) as unknown[]).map((source) =>
        typeof source === "string"
          ? { id: source }
          : {
              id: String((source as Record<string, unknown>).id),
              reason: (source as { reason?: string }).reason,
            },
      ),
    )
    .filter((source) => source.id.trim().length > 0);
}
