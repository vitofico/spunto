import { getLanguage, t } from "../lib/i18n";
import { isBundledPlugin } from "../lib/localizePlugins";
import type { Args, Note, Plugin, Schema } from "../lib/types";

export const decisionsPath = "/v/decision-journal/journal";

export function supportsDecisionCapture(plugin?: Plugin) {
  if (!plugin || plugin.id !== "decision-journal" || !isBundledPlugin(plugin))
    return false;
  const [major, minor] = plugin.version.split(".").map(Number);
  return (
    (major > 1 || (major === 1 && minor >= 2)) &&
    !!plugin.contributes?.commands?.some(
      (command) => command.id === "record-decision",
    )
  );
}

export function isOriginalDecision(note: Note) {
  return note.annotations?.some(
    (entry) =>
      entry.plugin === "decision-journal" && entry.kind === "member:journal",
  );
}

export function isDecisionNote(note: Note) {
  return (
    isOriginalDecision(note) ||
    (note.source?.kind === "plugin" && note.source.ref === "decision-journal")
  );
}

export function localizedDecisionStatus(text: string) {
  const live =
    /^(Open|Reviewed|Closed)(?: · Review (?:on|due) (\d{4}-\d{2}-\d{2}))?$/.exec(
      text,
    );
  const match =
    live ??
    /^(Open|Reviewed|Closed) · revision \d+(?: · review (\d{4}-\d{2}-\d{2}))?(?: · due)?$/.exec(
      text,
    );
  if (!match) return null;
  const status = match[1].toLowerCase();
  const date = match[2];
  const state = t(`decisions.status.${status}`);
  if (!date) return state;
  const scheduled = new Date(`${date}T00:00:00Z`);
  if (Number.isNaN(scheduled.getTime())) return state;
  const due = live
    ? text.includes(" · Review due ")
    : status !== "closed" && date <= new Date().toISOString().slice(0, 10);
  return `${state} · ${t(due ? "decisions.review_due" : "decisions.review_on", {
    date: new Intl.DateTimeFormat(getLanguage(), {
      dateStyle: "medium",
      timeZone: "UTC",
    }).format(scheduled),
  })}`;
}

export function decisionStatus(text: string) {
  const status = localizedDecisionStatus(text);
  if (status) return status;
  const outcome = /^(Closed|Reviewed|Reopened) · (.+)$/.exec(text);
  if (!outcome) {
    // Localized servers preserve ISO review dates. Keep any other response text as sent.
    return text.replace(
      / · ([^·]+?)(\d{4}-\d{2}-\d{2})$/,
      (part, label, value: string) => {
        const date = new Date(`${value}T00:00:00Z`);
        return Number.isNaN(date.getTime())
          ? part
          : ` · ${label}${date.toLocaleDateString(getLanguage(), { dateStyle: "medium", timeZone: "UTC" })}`;
      },
    );
  }
  const date = new Date(outcome[2]);
  const when = Number.isNaN(date.getTime())
    ? outcome[2]
    : date.toLocaleString(getLanguage(), {
        dateStyle: "medium",
        timeStyle: "short",
        timeZone: "UTC",
      });
  return `${t(`decisions.status.${outcome[1].toLowerCase()}`)} · ${when}`;
}

export function decisionCommandLabel(id: string, title: string, args: Args) {
  switch (id) {
    case "journal":
      if (args.operation === "schedule") return title;
      if (typeof args.cursor === "string") return t("decisions.more_decisions");
      if (typeof args.outcome_cursor === "string")
        return t("decisions.more_outcomes");
      return t("decisions.review_history");
    case "review-decision":
      return t(
        typeof args.request_id === "string"
          ? "decisions.resume_review"
          : "plugin.decision-journal.commands.review-decision.title",
      );
    default:
      return title;
  }
}

export function decisionForm(command: string, schema: Schema): Schema {
  const fields =
    command === "record-decision"
      ? [
          "title",
          "decision",
          "alternatives",
          "rationale",
          "expected",
          "questions",
          "review_on",
          "private",
        ]
      : command === "review-decision"
        ? ["outcome", "reflection", "status", "review_on"]
        : command === "journal"
          ? ["status", "review_on"]
          : [];
  if (!schema.properties || !fields.length) return schema;
  return {
    ...schema,
    properties: Object.fromEntries(
      Object.entries(schema.properties).map(([key, prop]) => [
        key,
        fields.includes(key)
          ? {
              ...prop,
              title: t(
                `decisions.form.${key === "review_on" && command === "review-decision" ? "next_review_on" : key}`,
              ),
              ...(key === "review_on" ? { format: "date" } : {}),
              ...(key === "private"
                ? { description: t("decisions.private_hint") }
                : {}),
            }
          : prop,
      ]),
    ),
  };
}
