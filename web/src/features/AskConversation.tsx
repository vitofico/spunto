import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Link } from "wouter";
import { api } from "../lib/api";
import { requestGeneration } from "../lib/identity";
import { subscribeOwner, useManifest } from "../lib/query";
import { t, useLanguage } from "../lib/i18n";
import type { Args } from "../lib/types";
import { VocabularyOutput, NoteReference } from "./Vocabulary";
import { outputText } from "./vocabularyData";
import {
  askAnswerSave,
  askContinuation,
  askSources,
  askWriteAction,
  askNoteActionOptions,
} from "./askConversationData";
import { AskWriteActions } from "./AskWriteActions";
import { copyText } from "./copy";
import styles from "./Feature.module.css";
import chatStyles from "./AskConversation.module.css";

type Turn = { id: number; question: string; value: unknown };
export default function AskConversation({
  value,
  question = "",
  onDismiss,
}: {
  value: unknown;
  question?: string;
  onDismiss?: () => void;
}) {
  useLanguage();
  const manifest = useManifest();
  const client = useQueryClient();
  const [turns, setTurns] = useState<Turn[]>([{ id: 0, question, value }]);
  const [draft, setDraft] = useState("");
  const [pending, setPending] = useState<string | null>(null);
  const [failure, setFailure] = useState<{
    question: string;
    error: string;
  } | null>(null);
  const continuation = useRef(askContinuation(value));
  const generation = useRef(requestGeneration());
  const revision = useRef(0);
  const controller = useRef<AbortController | null>(null);
  const alive = useRef(true);
  const enabled =
    manifest.data?.plugins.some(
      (p) => p.id === "ask" && (p.state === undefined || p.state === "enabled"),
    ) ?? true;
  const clearHandle = (args: Args | undefined) => {
    if (args && generation.current === requestGeneration())
      void api
        .command("ask", "clear-conversation", {
          conversation_id: args.conversation_id,
        })
        .catch(() => {});
  };
  const discard = () => {
    revision.current++;
    controller.current?.abort();
    clearHandle(continuation.current);
    continuation.current = undefined;
    setTurns([]);
    setDraft("");
    setPending(null);
    setFailure(null);
  };
  useEffect(() => {
    alive.current = true;
    const unsubscribe = subscribeOwner(() => {
      if (generation.current !== requestGeneration()) discard();
    });
    return () => {
      alive.current = false;
      revision.current++;
      controller.current?.abort();
      // StrictMode rehearses effect cleanup before immediately setting it up again.
      // Delay server invalidation so only an actual departure destroys the handle.
      const handle = continuation.current;
      queueMicrotask(() => {
        if (!alive.current) clearHandle(handle);
      });
      unsubscribe();
    };
  }, []);
  useEffect(() => {
    if (!enabled) discard();
  }, [enabled]);
  async function submit(question: string) {
    if (
      !enabled ||
      pending ||
      !navigator.onLine ||
      generation.current !== requestGeneration()
    )
      return;
    const attempt = ++revision.current;

    const abort = new AbortController();
    controller.current = abort;
    setPending(question);
    setFailure(null);
    setDraft("");
    try {
      if (!continuation.current) {
        const started = await api.command("ask", "ask", {
          start: true,
          ...askNoteActionOptions(manifest.data?.plugins),
        });
        const handle = askContinuation(started);
        if (!handle) throw new Error(outputText(started) || t("ask.failed"));
        if (
          !alive.current ||
          attempt !== revision.current ||
          generation.current !== requestGeneration()
        ) {
          clearHandle(handle);
          return;
        }
        continuation.current = handle;
      }
      const args = { ...continuation.current, question };
      const result = await api.request("/api/v1/plugins/ask/commands/ask", {
        method: "POST",
        body: args,
        signal: abort.signal,
      });
      if (
        !alive.current ||
        attempt !== revision.current ||
        generation.current !== requestGeneration()
      ) {
        clearHandle(askContinuation(result));
        return;
      }
      continuation.current = askContinuation(result);
      setTurns((prior) =>
        [...prior, { id: attempt, question, value: result }].slice(-20),
      );
      void client.invalidateQueries({ queryKey: ["notes"] });
    } catch (error) {
      if (
        alive.current &&
        attempt === revision.current &&
        !abort.signal.aborted
      ) {
        setDraft(question);
        setFailure({
          question,
          error: error instanceof Error ? error.message : t("ask.failed"),
        });
      }
    } finally {
      if (alive.current && attempt === revision.current) setPending(null);
    }
  }
  return (
    <section className={styles.result} aria-label={t("ask.conversation")}>
      <p className="muted">{t("ask.temporary")}</p>
      <button onClick={discard}>{t("ask.new_conversation")}</button>
      <div aria-live="polite" aria-busy={pending !== null}>
        {turns.map((turn) => (
          <article key={turn.id}>
            {turn.question && <h3>{turn.question}</h3>}
            <VocabularyOutput value={turn.value} allow={["text", "markdown"]} />
            {askSources(turn.value).length > 0 && (
              <>
                <h4>{t("ask.sources")}</h4>
                <ol>
                  {askSources(turn.value).map((source, i) => (
                    <li key={`${source.id}-${i}`}>
                      <NoteReference {...source} />
                    </li>
                  ))}
                </ol>
              </>
            )}
            <TurnActions
              value={turn.value}
              current={() =>
                alive.current &&
                enabled &&
                generation.current === requestGeneration()
              }
            />
          </article>
        ))}
        {pending && (
          <>
            <h3>{pending}</h3>
            <p role="status">{t("timeline.loading_notes")}</p>
          </>
        )}
      </div>
      {failure && (
        <div role="alert">
          <p className="error">{failure.error}</p>
          <p>{t("ask.retry_hint")}</p>
          <button onClick={() => void submit(failure.question)}>
            {t("ask.retry")}
          </button>
        </div>
      )}
      <form
        className={chatStyles.composer}
        onSubmit={(event) => {
          event.preventDefault();
          if (draft.trim() && [...draft.trim()].length <= 500)
            void submit(draft.trim());
        }}
      >
        <label>
          {t("ask.followup")}
          <textarea
            autoFocus
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            disabled={!enabled || !!pending}
            rows={3}
          />
        </label>
        {[...draft.trim()].length > 500 && (
          <p role="alert">{t("ask.question_limit")}</p>
        )}
        <button
          className="primary"
          disabled={
            !enabled ||
            !!pending ||
            !draft.trim() ||
            [...draft.trim()].length > 500 ||
            !navigator.onLine
          }
        >
          {t("ask.send")}
        </button>
      </form>
      {onDismiss && (
        <button
          onClick={() => {
            discard();
            onDismiss();
          }}
        >
          {t("slots.back")}
        </button>
      )}
    </section>
  );
}
function TurnActions({
  value,
  current,
}: {
  value: unknown;
  current: () => boolean;
}) {
  const client = useQueryClient();
  const [copied, setCopied] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState<unknown>();
  const [error, setError] = useState("");
  const save = askAnswerSave(value);
  const action = askWriteAction(value);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  return (
    <>
      <footer>
        <button
          onClick={() =>
            void copyText(outputText(value)).then((ok) => {
              if (alive.current && current()) setCopied(ok);
            })
          }
        >
          {copied ? t("settingsaccess.copied") : t("slots.copy_text")}
        </button>
        {save && (
          <button
            disabled={saving || !!saved}
            onClick={async () => {
              if (!current()) return;
              setSaving(true);
              setError("");
              try {
                const result = await api.command("ask", "save-answer", save);
                if (alive.current && current()) {
                  if (askSources(result).length) {
                    setSaved(result);
                    void client.invalidateQueries({ queryKey: ["notes"] });
                  } else setError(outputText(result) || t("ask.failed"));
                }
              } catch (error) {
                if (alive.current && current())
                  setError(
                    error instanceof Error ? error.message : t("ask.failed"),
                  );
              } finally {
                if (alive.current && current()) setSaving(false);
              }
            }}
          >
            {saved
              ? t("slots.saved_as_note")
              : saving
                ? t("app.saving")
                : t("slots.save_as_note")}
          </button>
        )}
        {askSources(saved).map((source) => (
          <Link key={source.id} href={`/n/${encodeURIComponent(source.id)}`}>
            {t("slots.open_note")}
          </Link>
        ))}
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
      </footer>
      {action && (
        <AskWriteActions
          initial={action}
          current={() => alive.current && current()}
        />
      )}
    </>
  );
}
