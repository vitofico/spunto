import { t } from "../lib/i18n";
import type { Suggestion } from "../lib/types";
export function requiresSuggestionPreview(s: Suggestion) {
  if (s.one_tap === true) return false;
  return (
    ["replace_body", "merge_notes", "label_change"].includes(s.action) ||
    (s.plugin === "links" && s.action === "append_text")
  );
}
export function suggestionTitle(s: Suggestion) {
  const p = s.payload;
  switch (s.action) {
    case "add_label":
      return t("quiet.add_label", { label: String(p.path) });
    case "set_title":
      return t("suggestionpreview.title_value1", { value1: String(p.title) });
    case "append_text":
      return t(
        s.plugin === "links"
          ? "plugin.links.commands.link.title"
          : "suggestionpreview.append_text",
      );
    case "replace_body":
      return t(
        s.one_tap && s.plugin === "voice-memos"
          ? "quiet.add_transcript"
          : "suggestionpreview.review_edit",
      );
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

export function appliedSuggestionTitle(s: Suggestion) {
  switch (s.action) {
    case "add_label":
      return t("quiet.added_label", { label: String(s.payload.path) });
    case "set_title":
      return t("quiet.set_title", { title: String(s.payload.title) });
    case "replace_body":
      return t(
        s.plugin === "voice-memos"
          ? "quiet.added_transcript"
          : "quiet.edited_note",
      );
    case "merge_notes":
      return t("quiet.merged_notes");
    case "label_change":
      return t("quiet.changed_labels");
    case "append_text":
      return t(
        s.plugin === "links" ? "quiet.linked_note" : "quiet.appended_text",
      );
    default:
      return t("commandproposal.applied");
  }
}
