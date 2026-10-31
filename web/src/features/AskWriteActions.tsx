import { useEffect, useRef, useState } from "react";
import { Link } from "wouter";
import { useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { invalidateContentFor } from "../lib/query";
import { t, useLanguage } from "../lib/i18n";
import { VocabularyOutput, NoteReference } from "./Vocabulary";
import { outputText } from "./vocabularyData";
import styles from "./AskConversation.module.css";
import {
  askItems,
  askSources,
  askWriteAcknowledgement,
  type AskWriteAction,
} from "./askConversationData";

/** Confirmation is a click on a visible server-owned preview, never a model callback. */
export function AskWriteActions({
  initial,
  preview,
  onRevise,
  current,
}: {
  initial: AskWriteAction;
  preview: unknown;
  onRevise: () => void;
  current: () => boolean;
}) {
  useLanguage();
  const client = useQueryClient();
  const [action, setAction] = useState(initial);
  const [result, setResult] = useState<unknown>();
  const [pending, setPending] = useState(false);
  const [cancelled, setCancelled] = useState(false);
  const [error, setError] = useState("");
  const alive = useRef(true);
  const running = useRef(false);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  async function confirm() {
    if (
      !alive.current ||
      !current() ||
      running.current ||
      cancelled ||
      action.args.stage === "done"
    )
      return;
    running.current = true;
    setPending(true);
    setError("");
    try {
      const response = await api.command("ask", "save-answer", action.args);
      if (!alive.current || !current()) return;
      const accepted = askWriteAcknowledgement(action, response);
      if (accepted) {
        setAction(accepted);
        setResult(response);
        void invalidateContentFor(client);
      } else
        setError(
          outputText(
            askItems(response).filter(
              (item) => item.type === "text" || item.type === "markdown",
            ),
          ) || t("ask.action_failed"),
        );
    } catch (error) {
      if (alive.current && current())
        setError(
          error instanceof Error ? error.message : t("ask.action_failed"),
        );
    } finally {
      running.current = false;
      if (alive.current && current()) setPending(false);
    }
  }
  const creation = initial.title === "Create note";
  const previewing = action.args.stage === "preview" && !cancelled;
  const parts = askItems(preview).filter(
    (item) =>
      item.type !== "text" ||
      item.text !== "Preview only. Nothing changes until you confirm.",
  );
  const content = (
    <>
      <div className={styles.preview}>
        <VocabularyOutput value={parts} allow={["text", "markdown"]} />
      </div>
      {askSources(preview).length > 0 && (
        <div className={styles.related}>
          <h4>{t("ask.related_notes")}</h4>
          {askSources(preview).map((source) => (
            <NoteReference key={source.id} {...source} />
          ))}
        </div>
      )}
    </>
  );
  return (
    <section
      className={styles.write}
      aria-label={t(creation ? "ask.review_new" : "ask.review_edit")}
      aria-busy={pending}
    >
      <header className={styles.writeHeader}>
        <h3>{t(creation ? "ask.review_new" : "ask.review_edit")}</h3>
        {previewing && (
          <span className={styles.badge}>{t("ask.not_saved")}</span>
        )}
      </header>
      {previewing ? (
        <>
          <p className="muted">{t("ask.preview_hint")}</p>
          {content}
          <p className={styles.help}>{t("ask.revise_hint")}</p>
        </>
      ) : (
        <details className={styles.previous}>
          <summary>{t("ask.view_proposal")}</summary>
          {content}
        </details>
      )}
      {cancelled ? (
        <p role="status">{t("ask.action_cancelled")}</p>
      ) : action.args.stage === "done" ? (
        <p role="status">
          {t(creation ? "ask.creation_undone" : "ask.edit_undone")}
        </p>
      ) : (
        <>
          {action.args.stage === "undo" && (
            <div className={styles.saved}>
              <p role="status">
                {t(creation ? "ask.note_created" : "ask.change_saved")}
              </p>
              <p className={styles.help}>
                {t(creation ? "ask.undo_create_hint" : "ask.undo_edit_hint")}
              </p>
            </div>
          )}
          <div className={styles.actions}>
            <button
              className={
                action.args.stage === "preview" ? "primary" : undefined
              }
              disabled={pending}
              onClick={() => void confirm()}
            >
              {pending
                ? t("ask.action_pending")
                : action.args.stage === "undo"
                  ? t("ask.undo")
                  : creation
                    ? t("ask.create_note")
                    : t("ask.apply_change")}
            </button>
            {action.args.stage === "preview" && (
              <button disabled={pending} onClick={onRevise}>
                {t("ask.revise_request")}
              </button>
            )}
            {action.args.stage === "preview" && (
              <button
                disabled={pending}
                onClick={() => {
                  if (current() && !running.current) setCancelled(true);
                }}
              >
                {t("profilepanel.cancel")}
              </button>
            )}
            {action.args.stage === "undo" &&
              askSources(result).map((source) => (
                <Link
                  key={source.id}
                  href={`/n/${encodeURIComponent(source.id)}`}
                >
                  {t("slots.open_note")}
                </Link>
              ))}
          </div>
        </>
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </section>
  );
}
