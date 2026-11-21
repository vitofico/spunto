import { useEffect, useRef, useState, type ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Icon } from "../components/core/Icon";
import { Link } from "wouter";
import { api } from "../lib/api";
import { ApiError } from "../lib/errors";
import { requestGeneration } from "../lib/identity";
import {
  subscribeOwner,
  useBrowserOnline,
  useManifest,
  useWorkspaceRole,
} from "../lib/query";
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
  supportsConversationalAsk,
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
  page = false,
  backLink,
  collectionId,
}: {
  value: unknown;
  question?: string;
  onDismiss?: () => void;
  page?: boolean;
  backLink?: ReactNode;
  collectionId?: string;
}) {
  useLanguage();
  const manifest = useManifest();
  const workspaceRole = useWorkspaceRole();
  const catalog = useQuery({
    queryKey: ["catalog"],
    queryFn: api.catalog,
    enabled: page && workspaceRole === "owner",
  });
  const client = useQueryClient();
  const [turns, setTurns] = useState<Turn[]>(
    value === undefined ? [] : [{ id: 0, question, value }],
  );
  const input = useRef<HTMLTextAreaElement>(null);
  const latest = useRef<HTMLElement>(null);
  const online = useBrowserOnline();
  const plugin = manifest.data?.plugins.find((p) => p.id === "ask");
  const canContinue = !!plugin?.contributes?.commands?.some(
    (command) => command.id === "clear-conversation",
  );
  const modern =
    page && canContinue && !!supportsConversationalAsk(plugin?.version);
  const canWrite =
    canContinue &&
    !collectionId &&
    askNoteActionOptions(manifest.data?.plugins, workspaceRole).note_actions ===
      true;
  const limit = modern ? 2000 : 500;
  const [draft, setDraft] = useState("");
  const [selection, setSelection] = useState<{
    model: string;
    localOnly: boolean;
  }>();
  const chatModel = selection?.model ?? "";
  const [notesOnly, setNotesOnly] = useState(false);
  const scroll = useRef<HTMLDivElement>(null);
  const [pending, setPending] = useState<string | null>(null);
  const [failure, setFailure] = useState<{
    question: string;
    error: string;
    stale?: boolean;
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
  const models = useQuery({
    queryKey: ["ask-models", generation.current],
    queryFn: api.askModels,
    enabled: modern && enabled && workspaceRole === "owner",
    retry: false,
    staleTime: 60_000,
  });
  const clearHandle = (args: Args | undefined) => {
    if (args && generation.current === requestGeneration())
      void api
        .command("ask", "clear-conversation", {
          conversation_id: args.conversation_id,
        })
        .catch(() => {});
  };
  const discard = (keepDraft = false) => {
    revision.current++;
    controller.current?.abort();
    clearHandle(continuation.current);
    continuation.current = undefined;
    setTurns([]);
    setSelection(undefined);
    if (!keepDraft) setDraft("");
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
  useEffect(() => {
    if (!page || !scroll.current) return;
    if (pending) scroll.current.scrollTop = scroll.current.scrollHeight;
    else latest.current?.scrollIntoView({ block: "start" });
  }, [page, pending, turns]);
  if (collectionId && !modern)
    return (
      <div>
        {backLink}
        <p role="status">{t("ask.collection_unavailable")}</p>
      </div>
    );
  async function submit(question: string) {
    if (
      !enabled ||
      models.isLoading ||
      pending ||
      failure?.stale ||
      !online ||
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
      if (canContinue && !continuation.current) {
        const started = await api.command("ask", "ask", {
          start: true,
          ...(!collectionId &&
            askNoteActionOptions(manifest.data?.plugins, workspaceRole)),
          ...(modern && { conversational: true }),
          ...(collectionId && { collection_id: collectionId }),
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
      const args = {
        ...continuation.current,
        question,
        ...(selection && {
          chat_model: selection.model,
          ...(selection.localOnly && { chat_model_local_only: true }),
        }),
        ...(modern && notesOnly && { notes_only: true }),
      };
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
        const stale =
          error instanceof ApiError && error.code === "ask_collection_changed";
        setDraft(question);
        setFailure({
          question,
          error: stale
            ? t("ask.collection_changed")
            : error instanceof Error
              ? error.message
              : t("ask.failed"),
          stale,
        });
      }
    } finally {
      if (alive.current && attempt === revision.current) setPending(null);
    }
  }
  const empty = turns.length === 0 && !pending;
  const starters = empty && (
    <div className={chatStyles.starters} aria-label={t("ask.try_message")}>
      <p className="muted">{t("ask.try_message")}</p>
      {(["ask", ...(canWrite ? ["create", "edit"] : [])] as const).map(
        (kind) => (
          <button
            key={kind}
            type="button"
            aria-label={`${t(`ask.starter_${kind}`)}: ${t(`ask.example_${kind}`)}`}
            disabled={!enabled || !!draft.trim()}
            onClick={() => {
              if (draft.trim()) return;
              setDraft(t(`ask.example_${kind}`));
              input.current?.focus();
            }}
          >
            <Icon
              name={
                kind === "ask" ? "search" : kind === "create" ? "add" : "edit"
              }
            />
            <strong>{t(`ask.starter_${kind}`)}</strong>
            <span>{t(`ask.example_${kind}`)}</span>
          </button>
        ),
      )}
    </div>
  );
  const Heading = page ? "h1" : "h2";
  const introduction = (
    <>
      <Heading>{t("plugin.ask.name")}</Heading>
      {(!page || empty) && (
        <p>
          {t(
            collectionId
              ? "ask.collection_intro"
              : modern
                ? "ask.discussion_intro"
                : canWrite
                  ? "ask.intro"
                  : "ask.intro_readonly",
          )}
        </p>
      )}
    </>
  );
  const destination = catalog.data?.plugins.find((item) => item.id === "ask")
    ?.ai_destinations?.chat;
  const externalHost =
    models.data && Array.isArray(models.data.external_models)
      ? (chatModel
          ? models.data.external_models.includes(chatModel)
          : models.data.external) && models.data.host
      : destination?.leaves_network &&
        (destination.host ?? destination.profile);
  return (
    <section
      className={page ? chatStyles.page : styles.result}
      aria-label={t("ask.conversation")}
    >
      <header className={chatStyles.header}>
        {page ? backLink : <div>{introduction}</div>}
        <button
          onClick={() => {
            discard(failure?.stale === true);
            requestAnimationFrame(() => input.current?.focus());
          }}
        >
          <Icon name="add" /> {t("ask.new_conversation")}
        </button>
      </header>
      <div className={page ? chatStyles.transcript : undefined} ref={scroll}>
        {page && (
          <div className={empty ? chatStyles.welcome : chatStyles.threadTitle}>
            <span className={chatStyles.mark}>
              <Icon name="ask" />
            </span>
            <div>{introduction}</div>
          </div>
        )}
        {page && starters}
        {page ? (
          <div className={chatStyles.conversationInfo}>
            <details className={chatStyles.temporary}>
              <summary>{t("ask.temporary_title")}</summary>
              <p>{t("ask.temporary")}</p>
            </details>
            {modern &&
              workspaceRole === "owner" &&
              empty &&
              !draft &&
              !failure && (
                <Link href="/settings/ai">
                  <Icon name="tune" />
                  {t("ask.model_settings")}
                </Link>
              )}
          </div>
        ) : (
          <p className={chatStyles.temporary}>{t("ask.temporary")}</p>
        )}
        <div role="log" aria-live="polite" aria-busy={pending !== null}>
          {turns.map((turn) => (
            <article
              key={turn.id}
              tabIndex={-1}
              ref={turn.id === turns.at(-1)?.id ? latest : undefined}
            >
              {turn.question && (
                <h3 className={chatStyles.question}>{turn.question}</h3>
              )}
              {!askWriteAction(turn.value) && (
                <VocabularyOutput
                  value={turn.value}
                  allow={["text", "markdown"]}
                />
              )}
              {!askWriteAction(turn.value) &&
                askSources(turn.value).length > 0 &&
                (page ? (
                  <details className={chatStyles.sources}>
                    <summary>
                      {t("ask.sources")} · {askSources(turn.value).length}
                    </summary>
                    <ol>
                      {askSources(turn.value).map((source, i) => (
                        <li key={source.id}>
                          <NoteReference {...source} keepConversation={true} />
                        </li>
                      ))}
                    </ol>
                  </details>
                ) : (
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
                ))}
              <TurnActions
                value={turn.value}
                onRevise={() => {
                  if (!draft.trim()) setDraft(turn.question);
                  input.current?.focus();
                }}
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
              <h3 className={chatStyles.question}>{pending}</h3>
              <p role="status">{t("timeline.loading_notes")}</p>
            </>
          )}
        </div>
        {failure && (
          <div role="alert">
            <p className="error">{failure.error}</p>
            {!failure.stale && <p>{t("ask.retry_hint")}</p>}
            <button
              onClick={() =>
                failure.stale ? discard(true) : void submit(failure.question)
              }
            >
              {t(failure.stale ? "ask.new_conversation" : "ask.retry")}
            </button>
          </div>
        )}
        {turns.length > 0 && !pending && (
          <button
            type="button"
            className={chatStyles.latest}
            onClick={() => {
              latest.current?.scrollIntoView({ block: "start" });
              latest.current?.focus({ preventScroll: true });
            }}
          >
            {t("ask.view_latest")}
          </button>
        )}
      </div>
      <form
        className={chatStyles.composer}
        onSubmit={(event) => {
          event.preventDefault();
          if (draft.trim() && [...draft.trim()].length <= limit)
            void submit(draft.trim());
        }}
      >
        {page && externalHost && (
          <p className={chatStyles.provider}>
            {t("ask.external_provider", {
              host: externalHost,
            })}
          </p>
        )}
        <label>
          <span className={page ? "sr-only" : undefined}>
            {t("ask.message")}
          </span>
          <textarea
            ref={input}
            data-modal-initial
            placeholder={t(
              canWrite ? "ask.message_placeholder" : "ask.question_placeholder",
            )}
            autoFocus
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            disabled={!enabled || !!pending}
            rows={page ? 2 : 3}
          />
        </label>
        {!page && starters}
        {[...draft.trim()].length > limit && (
          <p role="alert">
            {t(modern ? "ask.message_limit" : "ask.question_limit")}
          </p>
        )}
        <div className={chatStyles.composerActions}>
          {modern && !!models.data?.models?.length && (
            <label className={chatStyles.model}>
              <Icon name="tune" />
              <span className="sr-only">{t("ask.chat_model")}</span>
              <select
                className="field"
                value={chatModel}
                disabled={!!pending || !!failure || turns.length > 0}
                onChange={(event) => {
                  const model = event.target.value;
                  setSelection(
                    model
                      ? {
                          model,
                          localOnly:
                            !models.data.external_models.includes(model),
                        }
                      : undefined,
                  );
                }}
              >
                <option value="">
                  {t("ask.default_model", {
                    model: models.data.default_model,
                  })}
                </option>
                {models.data.models
                  .filter((model) => model !== models.data.default_model)
                  .map((model) => (
                    <option key={model} value={model}>
                      {model}
                    </option>
                  ))}
              </select>
            </label>
          )}
          {modern && (
            <label className={chatStyles.onlyNotes}>
              <input
                type="checkbox"
                checked={notesOnly}
                disabled={!!pending}
                onChange={(e) => setNotesOnly(e.target.checked)}
              />
              {t("ask.only_notes")}
            </label>
          )}
          {!online && <p role="status">{t("ask.offline")}</p>}
          <button
            className="primary"
            disabled={
              !enabled ||
              models.isLoading ||
              !!pending ||
              !!failure?.stale ||
              !draft.trim() ||
              [...draft.trim()].length > limit ||
              !online
            }
          >
            <Icon name="send" /> <span>{t("ask.send")}</span>
          </button>
        </div>
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
  onRevise,
  current,
}: {
  value: unknown;
  onRevise: () => void;
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
      {!action && (
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
      )}
      {action && (
        <AskWriteActions
          initial={action}
          preview={value}
          onRevise={onRevise}
          current={() => alive.current && current()}
        />
      )}
    </>
  );
}
