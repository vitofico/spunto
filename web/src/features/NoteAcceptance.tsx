import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { api } from "../lib/api";
import { safeError } from "../lib/errors";
import { t } from "../lib/i18n";
import { invalidateContentFor } from "../lib/query";
import type { Note } from "../lib/types";
import styles from "./Feature.module.css";

type Acceptance = "machine" | "agent-note" | "agent-changes";

export function NoteAcceptance({
  note,
  machine,
  agent,
  blocked,
  online,
}: {
  note: Note;
  machine: boolean;
  agent: boolean;
  blocked: boolean;
  online: boolean;
}) {
  const client = useQueryClient();
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
        ["version_conflict", "note_not_machine_written"].includes(
          safeError(error).code,
        )
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
  const agentChanges = note.source?.kind !== "agent" || machine;
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
        <div className={styles.acceptanceAction}>
          <p>{t("note.accept_agent_help")}</p>
          <button
            type="button"
            className="button"
            disabled={!online || accept.isPending}
            onClick={(event) =>
              run(
                agentChanges ? "agent-changes" : "agent-note",
                event.currentTarget,
              )
            }
          >
            {t(
              machine
                ? "note.accept_agent_changes"
                : agentChanges
                  ? "note.accept_changes"
                  : "note.accept_note",
            )}
          </button>
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
