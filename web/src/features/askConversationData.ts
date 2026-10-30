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

export type AskWriteStage = "preview" | "undo" | "done";
export type AskWriteAction = {
  title: string;
  args: { action_id: string; stage: AskWriteStage };
};
/** Receipts carry the action; clients only echo the closed, server-issued arguments. */
export function askWriteAction(value: unknown): AskWriteAction | undefined {
  for (const item of askItems(value)) {
    if (
      item.type !== "command" ||
      item.id !== "save-answer" ||
      !record(item.args)
    )
      continue;
    const args = item.args;
    if (
      Object.keys(args).length !== 2 ||
      typeof args.action_id !== "string" ||
      !args.action_id.trim() ||
      !["preview", "undo", "done"].includes(String(args.stage))
    )
      continue;
    if (
      args.stage === "preview" &&
      !askItems(value).some(
        (part) =>
          part.type === "markdown" &&
          String(part.text ?? part.markdown ?? "").trim(),
      )
    )
      continue;
    return {
      title: String(item.title),
      args: { action_id: args.action_id, stage: args.stage as AskWriteStage },
    };
  }
}
export function askWriteAcknowledgement(
  requested: AskWriteAction,
  value: unknown,
): AskWriteAction | undefined {
  const next = askWriteAction(value);
  if (!next || next.args.action_id !== requested.args.action_id) return;
  if (
    next.args.stage === "done" ||
    (requested.args.stage === "preview" && next.args.stage === "undo")
  )
    return next;
}
export function askAnswerSave(value: unknown): Args | undefined {
  const args = askCommand(value, "save-answer");
  return args &&
    Object.keys(args).length === 1 &&
    typeof args.answer_id === "string" &&
    args.answer_id.trim()
    ? args
    : undefined;
}
export function askNoteActionOptions(
  plugins: { id: string; version?: string; state?: string }[] | undefined,
): Args {
  const plugin = plugins?.find(
    (item) =>
      item.id === "ask" &&
      (item.state === undefined || item.state === "enabled"),
  );
  const version = plugin?.version?.match(/^(\d+)\.(\d+)\.(\d+)(?:\+[\w.-]+)?$/);
  return version &&
    (Number(version[1]) > 1 ||
      (Number(version[1]) === 1 && Number(version[2]) >= 1))
    ? { note_actions: true }
    : {};
}
