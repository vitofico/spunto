import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { NoteDiff } from "../components/core/NoteDiff";
import { api } from "../lib/api";
import { safeError } from "../lib/errors";
import { t } from "../lib/i18n";
import { invalidateContentFor } from "../lib/query";
import type { NoteDetail } from "../lib/types";
import styles from "./Feature.module.css";

type Acceptance = "machine" | "agent-note" | "agent-changes";

export function NoteAcceptance({
  note,
  machine,
  agent,
  blocked,
  online,
}: {
  note: NoteDetail;
  machine: boolean;
  agent: boolean;
  blocked: boolean;
  online: boolean;
}) {
  const client = useQueryClient();
  const comparisonHeading = useId();
  const [accepted, setAccepted] = useState<{
    mode: Acceptance;
    version: number;
  }>();
  const receipt =
    accepted?.version === note.version ? accepted.mode : undefined;
  const feedback = useRef<HTMLParagraphElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const accept = useMutation({
    networkMode: "always",
    mutationFn: async ({
      mode,
      version,
    }: {
      mode: Acceptance;
      version: number;
    }) => {
      if (mode === "machine")
        return (await api.adoptNote(note.id, version)).version;
      await api.command("agent-inbox", "mark-reviewed", {
        note_id: note.id,
        base_version: version,
      });
      return version;
    },
    onSuccess: (version, { mode }) => {
      setAccepted({ mode, version });
      return invalidateContentFor(client);
    },
    onError: (error) => {
      if (
        [
          "version_conflict",
          "note_not_machine_written",
          "note_not_from_agent",
        ].includes(safeError(error).code)
      )
        void client.invalidateQueries({ queryKey: ["note", note.id] });
    },
  });
  useEffect(() => {
    if (
      receipt &&
      (document.activeElement === trigger.current ||
        document.activeElement === document.body)
    )
      feedback.current?.focus({ preventScroll: true });
  }, [receipt]);

  const error =
    accept.variables?.version === note.version && accept.error
      ? safeError(accept.error)
      : undefined;
  if (blocked || (!machine && !agent && !receipt && !error)) return null;
  const agentChanges = note.agent_review
    ? note.agent_review.kind === "edited"
    : note.source?.kind !== "agent" || machine;
  const before = note.agent_review?.before;
  const run = (mode: Acceptance, button: HTMLButtonElement) => {
    if (!online || accept.isPending) return;
    trigger.current = button;
    setAccepted(undefined);
    accept.mutate({ mode, version: note.version });
  };
  return (
    <div className={styles.noteAcceptance}>
      {machine && (
        <div className={styles.acceptanceAction}>
          <p>
            {t(
              note.private
                ? "note.accept_private_help"
                : "note.accept_machine_help",
            )}
          </p>
          <button
            type="button"
            className="button"
            disabled={!online || accept.isPending}
            onClick={(event) => run("machine", event.currentTarget)}
          >
            {t(agent ? "note.use_as_my_note" : "note.accept_note")}
          </button>
        </div>
      )}
      {agent && (
        <div
          className={styles.agentAcceptance}
          data-comparing={agentChanges || undefined}
        >
          {agentChanges && (
            <div className={styles.agentComparison}>
              <h2 id={comparisonHeading}>{t("note.agent_changes")}</h2>
              {!before ? (
                <p>{t("note.agent_comparison_unavailable")}</p>
              ) : before.body === note.body ? (
                <p>{t("note.agent_comparison_unchanged")}</p>
              ) : (
                <NoteDiff
                  before={before.body}
                  after={note.body}
                  labelledBy={comparisonHeading}
                  labels={{
                    before: t("note.before_agent_edit", {
                      version: before.version,
                    }),
                    after: t("note.after_agent_edit", {
                      version: note.version,
                    }),
                  }}
                />
              )}
            </div>
          )}
          <div className={styles.acceptanceAction}>
            <p>{t("note.accept_agent_help")}</p>
            <button
              type="button"
              className={agentChanges ? "primary" : "button"}
              disabled={!online || accept.isPending}
              onClick={(event) =>
                run(
                  agentChanges ? "agent-changes" : "agent-note",
                  event.currentTarget,
                )
              }
            >
              {t(
                agentChanges
                  ? machine
                    ? "note.accept_agent_changes"
                    : "note.accept_changes"
                  : "note.accept_note",
              )}
            </button>
          </div>
        </div>
      )}
      {!online && (machine || agent) && (
        <p className={styles.resultStatus}>{t("note.accept_reconnect")}</p>
      )}
      <p
        className={receipt ? styles.resultStatus : "sr-only"}
        role="status"
        tabIndex={-1}
        ref={feedback}
      >
        {receipt &&
          t(
            receipt === "agent-changes"
              ? "note.changes_accepted"
              : "note.note_accepted",
          )}
      </p>
      {error && (
        <p className="error" role="alert">
          {error.code === "version_conflict"
            ? t("note.accept_stale")
            : error.message}
        </p>
      )}
    </div>
  );
}
