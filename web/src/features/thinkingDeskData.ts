import type { Args, Plugin } from "../lib/types";
import { askItems } from "./askConversationData";
import { record } from "./vocabularyData";

export function dedicatedDesk(plugin: Plugin | undefined) {
  const [major, minor] = plugin?.version.split(".").map(Number) ?? [];
  return major > 1 || (major === 1 && minor >= 2);
}

export function deskChoices(value: unknown) {
  const items = askItems(value);
  const lastChoice = items.findLastIndex(
    (item) =>
      item.type === "command" &&
      item.id === "edit" &&
      record(item.args) &&
      item.args.action === "start",
  );
  return lastChoice < 0 ? items : items.slice(0, lastChoice + 1);
}

export function deskListing(value: unknown) {
  const items = askItems(value);
  const desks = [];
  let after: string | undefined;
  for (const [index, item] of items.entries()) {
    if (item.type !== "command" || item.id !== "desk" || !record(item.args))
      continue;
    if (typeof item.args.cursor === "string") after = item.args.cursor;
    if (typeof item.args.desk_id === "string") {
      const title = items[index - 1];
      const label = title?.type === "text" ? title.text : item.title;
      if (typeof label === "string")
        desks.push({ id: item.args.desk_id, title: label });
    }
  }
  return { desks, after };
}

export function deskWorkspace(value: unknown) {
  const items = askItems(value);
  const first = items[0];
  if (
    first?.type !== "markdown" ||
    typeof first.text !== "string" ||
    !first.text.startsWith("# ")
  )
    return undefined;
  const details = items.find(
    (item) =>
      item.type === "command" &&
      item.id === "edit" &&
      record(item.args) &&
      item.args.action === "details",
  );
  const id = (details?.args as Args | undefined)?.desk_id;
  if (typeof id !== "string") return undefined;
  return { id, title: first.text.slice(2), items: items.slice(1) };
}
