import { t } from "../lib/i18n";
import type { Suggestion } from "../lib/types";
export function requiresSuggestionPreview(s: Suggestion) {
  return (
    ["replace_body", "merge_notes", "label_change"].includes(s.action) ||
    (s.plugin === "links" && s.action === "append_text")
  );
}
export function suggestionTitle(s: Suggestion) {
  const p = s.payload;
  switch (s.action) {
    case "add_label":
      return `#${String(p.path)}`;
    case "set_title":
      return t("suggestionpreview.title_value1", { value1: String(p.title) });
    case "append_text":
      return t(
        s.plugin === "links"
          ? "plugin.links.commands.link.title"
          : "suggestionpreview.append_text",
      );
    case "replace_body":
      return t("suggestionpreview.review_edit");
    case "merge_notes":
      return t("suggestionpreview.compare_notes");
    case "label_change":
      return Array.isArray(p.note_ids)
        ? t("suggestionpreview.add_value1_to_value2_notes", {
            value1: String(p.new),
            value2: p.note_ids.length,
          })
        : p.new === null
          ? t("suggestionpreview.remove_value1", { value1: String(p.old) })
          : `#${String(p.old)} → #${String(p.new)}`;
    case "open":
      return t("slots.open");
    default:
      return t("suggestionpreview.review_suggestion");
  }
}
