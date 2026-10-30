import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { invalidateContentFor } from "../lib/query";
import { t, useLanguage } from "../lib/i18n";
import { VocabularyOutput } from "./Vocabulary";
import { outputText } from "./vocabularyData";
import styles from "./AskConversation.module.css";
import {
  askItems,
  askWriteAcknowledgement,
  type AskWriteAction,
} from "./askConversationData";

/** Confirmation is a click on a visible server-owned preview, never a model callback. */
export function AskWriteActions({
  initial,
  current,
}: {
  initial: AskWriteAction;
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
  return (
    <div className={styles.write} aria-busy={pending}>
      {action.args.stage === "preview" && !cancelled && (
        <p className="muted">{t("ask.preview_hint")}</p>
      )}
      {result !== undefined && (
        <VocabularyOutput
          value={result}
          allow={["text", "markdown", "notes"]}
        />
      )}
      {cancelled ? (
        <p role="status">{t("ask.action_cancelled")}</p>
      ) : action.args.stage === "done" ? (
        <p role="status">{t("ask.undo_complete")}</p>
      ) : (
        <div className={styles.actions}>
          <button
            className="primary"
            disabled={pending}
            onClick={() => void confirm()}
          >
            {pending
              ? t("ask.action_pending")
              : action.args.stage === "undo"
                ? t("ask.undo")
                : initial.title === "Create note"
                  ? t("ask.create_note")
                  : t("ask.apply_change")}
          </button>
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
        </div>
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </div>
  );
}
