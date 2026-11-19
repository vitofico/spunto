import CommandResult from "./CommandResult";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { Link } from "wouter";
import { api } from "../lib/api";
import { t, useLanguage } from "../lib/i18n";
import { useBrowserOnline, useWorkspaceRole } from "../lib/query";
import type { Args, Note, Plugin } from "../lib/types";
import {
  decisionsPath,
  isDecisionNote,
  isOriginalDecision,
  decisionStatus,
} from "./decisionJournalData";
import { activePlugins, type CommandOrigin, usePlugins } from "./Slots";
import { Dialog } from "./Dialog";
import { askItems } from "./askConversationData";
import styles from "./Feature.module.css";

const journal = "decision-journal";

function DecisionCommand({
  id,
  args,
  label,
  disabled,
  load,
}: {
  id: string;
  args?: Args;
  label: string;
  disabled?: boolean;
  load?: () => Promise<unknown>;
}) {
  const [origin, setOrigin] = useState<CommandOrigin>();
  const opener = useRef<HTMLButtonElement>(null);
  const result = useMutation({
    networkMode: "always",
    mutationFn: (choice: CommandOrigin) =>
      load
        ? load()
        : api.command(choice.plugin, choice.command, choice.args ?? {}),
  });
  const close = () => {
    setOrigin(undefined);
    result.reset();
  };
  return (
    <>
      <button
        className="button"
        ref={opener}
        type="button"
        disabled={disabled}
        onClick={() => {
          const choice = { plugin: journal, command: id, args };
          setOrigin(choice);
          result.mutate(choice);
        }}
      >
        {label}
      </button>
      {origin && (
        <Dialog title={label} onClose={close} opener={opener.current}>
          {result.isPending && <p role="status">{t("plugins.running")}</p>}
          {result.isError && (
            <>
              <p role="alert" className="error">
                {result.error.message}
              </p>
              <button onClick={() => result.mutate(origin)}>
                {t("app.try_again")}
              </button>
            </>
          )}
          {result.isSuccess && (
            <CommandResult
              value={result.data}
              origin={origin}
              onOpenNote={close}
            />
          )}
        </Dialog>
      )}
    </>
  );
}

export function DecisionCapture({ plugin }: { plugin: Plugin | undefined }) {
  useLanguage();
  const online = useBrowserOnline();
  const role = useWorkspaceRole();
  const command = plugin?.contributes?.commands?.find(
    ({ id }) => id === "record-decision",
  );
  return role === "owner" && command ? (
    <DecisionCommand
      id={command.id}
      label={t("plugin.decision-journal.commands.record-decision.title")}
      disabled={!online}
    />
  ) : null;
}

export function DecisionNoteActions({
  note,
  editing,
}: {
  note: Note;
  editing: boolean;
}) {
  const language = useLanguage();
  const role = useWorkspaceRole();
  const online = useBrowserOnline();
  const manifest = usePlugins();
  const plugin = activePlugins(manifest.data?.plugins).find(
    (item) => item.id === journal,
  );
  const show = !!plugin && !note.trashed && isDecisionNote(note);
  const detail = useQuery({
    queryKey: ["plugin-panel", journal, note.id, language],
    queryFn: () => api.command(journal, "journal", { note_id: note.id }),
    enabled: show && !editing && online && role === "owner",
    staleTime: 0,
    networkMode: "always",
    refetchOnWindowFocus: false,
    retry: false,
  });
  if (!show || editing) return null;
  const items = askItems(detail.data);
  const [summary, root] = items;
  const originalId =
    root?.type === "notes" && Array.isArray(root.ids) && root.ids[0];
  const status =
    typeof originalId === "string" &&
    summary?.type === "text" &&
    typeof summary.text === "string"
      ? decisionStatus(summary.text)
      : null;
  const original = isOriginalDecision(note);
  if (!original && !status) return null;
  return (
    <section
      className={styles.decisionTools}
      aria-label={t("plugin.decision-journal.name")}
    >
      {status && <p className={styles.facts}>{status}</p>}
      <div className={styles.row}>
        {status && role === "owner" && (
          <>
            <DecisionCommand
              id="review-decision"
              disabled={!online}
              args={{ note_id: note.id }}
              label={t(
                "plugin.decision-journal.commands.review-decision.title",
              )}
            />
            {original && (
              <DecisionCommand
                id="journal"
                load={() =>
                  online
                    ? detail
                        .refetch({ cancelRefetch: false, throwOnError: true })
                        .then((value) => value.data)
                    : Promise.resolve(detail.data)
                }
                args={{ note_id: note.id }}
                label={t("decisions.review_history")}
              />
            )}
          </>
        )}
        {typeof originalId === "string" && originalId !== note.id && (
          <Link href={`/n/${encodeURIComponent(originalId)}`}>
            {t("decisions.open_original")}
          </Link>
        )}
        <Link href={decisionsPath}>
          {t("plugin.decision-journal.views.journal.title")}
        </Link>
      </div>
    </section>
  );
}
