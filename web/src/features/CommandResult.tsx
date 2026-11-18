import { useMutation, useQueryClient } from "@tanstack/react-query";
import { lazy, Suspense, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "wouter";
import { api } from "../lib/api";
import { createIdempotencyKey } from "../lib/drafts";
import { safeError } from "../lib/errors";
import { getLanguage, t, useLanguage } from "../lib/i18n";
import { requestGeneration } from "../lib/identity";
import { isBundledPlugin } from "../lib/localizePlugins";
import {
  invalidateContentFor,
  useBrowserOnline,
  useWorkspaceRole,
} from "../lib/query";
import type { Args, Component, Json, Plugin, Schema } from "../lib/types";
import {
  askContinuation,
  askItems,
  askNoteActionOptions,
} from "./askConversationData";
import { copyText } from "./copy";
import {
  decisionForm,
  decisionStatus,
  decisionCommandLabel,
} from "./decisionJournalData";
import {
  supportsNotePicker,
  usePlugins,
  type CommandChoice,
  type CommandOrigin,
} from "./Slots";
import { useCommandProposals } from "./suggestionFeedback";
import { VocabularyOutput } from "./Vocabulary";
import { outputText, validComponent } from "./vocabularyData";
import styles from "./Feature.module.css";

const LinkPicker = lazy(() =>
  import("./LinkPicker").then((m) => ({ default: m.LinkPicker })),
);
const CommandProposal = lazy(() => import("./CommandProposal"));
const NotePicker = lazy(() =>
  import("./NotePicker").then((m) => ({ default: m.NotePicker })),
);
const AskConversation = lazy(() => import("./AskConversation"));

function outputComponents(payload: unknown): unknown[] {
  return Array.isArray(payload)
    ? payload
    : payload && typeof payload === "object" && "type" in payload
      ? [payload]
      : payload && typeof payload === "object"
        ? ((payload as { items?: unknown[]; components?: unknown[] }).items ??
          (payload as { components?: unknown[] }).components ??
          [])
        : [];
}
function pluginFormChoice(
  plugin: Plugin | undefined,
  field: string,
  value: Json,
) {
  if (typeof value !== "string") return undefined;
  if (
    plugin?.id === "decision-journal" &&
    plugin.origin !== "operator" &&
    field === "status" &&
    ["open", "reviewed", "closed"].includes(value)
  )
    return t(`decisions.status.${value}`);
  if (!plugin || !isBundledPlugin(plugin))
    return getLanguage() === "en" && plugin?.origin !== "operator"
      ? undefined
      : value;
  if (["context-packs", "thinking-desk"].includes(plugin.id)) {
    if (field === "action" && value === "save") return t("sharednotes.save");
    if (field === "action" && value === "delete")
      return t("settingsplugins.delete").trim();
    if ((field === "add" || field === "remove") && value === "None")
      return t("pluginui.choice.none");
    if (plugin.id === "thinking-desk") {
      if (field === "mode" && ["history", "collision"].includes(value))
        return t(`pluginui.choice.${value}`);
      if (
        field === "kind" &&
        ["builds-on", "challenges", "replaces"].includes(value)
      )
        return t(`pluginui.choice.${value}`);
    }
    return value;
  }
  if (plugin.id === "links" && field === "target")
    return value === "Choose a note" ? t("pluginui.choice.choose_note") : value;
  return undefined;
}

/** A plugin's own save: a command it offers only inside results, titled "Save as note". */
function saveCommandIds(plugin: Plugin | undefined) {
  return new Set(
    (plugin?.contributes?.commands ?? [])
      .filter(
        (c) =>
          !c.where?.length &&
          (c.id === "save-answer" ||
            c.id === "save-translation" ||
            c.id === "save-development"),
      )
      .map((c) => c.id),
  );
}
function saveCommand(
  value: unknown,
  ids: Set<string>,
): { id: string; args: Args } | undefined {
  if (Array.isArray(value)) {
    for (const item of value) {
      const command = saveCommand(item, ids);
      if (command) return command;
    }
  } else if (value && typeof value === "object") {
    if (
      validComponent(value) &&
      value.type === "command" &&
      ids.has(String(value.id))
    )
      return { id: String(value.id), args: (value.args ?? {}) as Args };
    if (!("type" in value)) {
      const group = value as { items?: unknown; components?: unknown };
      return saveCommand(group.items ?? group.components, ids);
    }
  }
}
export default function CommandResult(props: {
  value: unknown;
  onDismiss?: () => void;
  origin?: CommandOrigin;
}) {
  const sourceId = props.origin?.args?.note_id;
  if (
    props.value === true &&
    props.origin?.plugin === "links" &&
    props.origin.command === "link" &&
    typeof sourceId === "string"
  )
    return (
      <Suspense fallback={<p role="status">{t("timeline.loading_notes")}</p>}>
        <LinkPicker sourceId={sourceId} onClose={props.onDismiss} />
      </Suspense>
    );
  if (props.origin?.plugin === "tidy" && typeof sourceId === "string") {
    const items = outputComponents(props.value);
    const proposal = items.find(
      (item) =>
        validComponent(item) &&
        item.type === "pill" &&
        item.action === "accept",
    );
    const diff = items.find(
      (item) => validComponent(item) && item.type === "diff",
    ) as Extract<Component, { type: "diff" }> | undefined;
    if (validComponent(proposal) && typeof proposal.suggestion_id === "string")
      return (
        <TidyResult
          key={proposal.suggestion_id}
          id={proposal.suggestion_id}
          noteId={sourceId}
          diff={diff}
          onClose={props.onDismiss}
        />
      );
  }
  return <PluginResult {...props} />;
}

function TidyResult({
  id,
  noteId,
  diff,
  onClose,
}: {
  id: string;
  noteId: string;
  diff?: Extract<Component, { type: "diff" }>;
  onClose?: () => void;
}) {
  const [done, setDone] = useState(false);
  const ids = useMemo(() => (done ? [] : [id]), [done, id]);
  useCommandProposals(noteId, ids);
  return done ? (
    <div className={styles.result}>
      <p>{t("commandproposal.applied")}</p>
      <footer>
        {onClose && <button onClick={onClose}>{t("app.close")}</button>}
      </footer>
    </div>
  ) : (
    <Suspense fallback={<p role="status">{t("timeline.loading_notes")}</p>}>
      <CommandProposal
        id={id}
        plugin="tidy"
        noteId={noteId}
        inline
        diff={diff}
        onClose={onClose}
        onApplied={() => (onClose ? onClose() : setDone(true))}
      />
    </Suspense>
  );
}

function PluginResult({
  value,
  onDismiss,
  origin,
}: {
  value: unknown;
  onDismiss?: () => void;
  origin?: CommandOrigin;
}) {
  useLanguage();
  const [copied, setCopied] = useState(false);
  const [saved, setSaved] = useState<{ id?: string } | null>(null);
  const client = useQueryClient();
  const manifest = usePlugins();
  const workspaceRole = useWorkspaceRole();
  const online = useBrowserOnline();
  const [display, setDisplay] = useState(value);
  const [currentOrigin, setCurrentOrigin] = useState(origin);
  const [responseId, setResponseId] = useState(0);
  const [review, setReview] = useState<{ id: string; response: number }>();
  const [applied, setApplied] = useState<string[]>([]);
  const noteId = currentOrigin?.args?.note_id;
  const session = useMemo(() => ({}), [value, origin]);
  const activeSession = useRef(session);
  const appliedSession = useRef(session);
  activeSession.current = session;
  const visibleResult = useMemo(() => {
    if (!applied.length) return display;
    return outputComponents(display).filter(
      (item) =>
        !validComponent(item) ||
        item.type !== "pill" ||
        !applied.includes(String(item.suggestion_id)),
    );
  }, [display, applied]);
  const proposalIds = useMemo(
    () =>
      outputComponents(visibleResult).flatMap((item) =>
        validComponent(item) && item.type === "pill" && item.action === "accept"
          ? [String(item.suggestion_id)]
          : [],
      ),
    [visibleResult],
  );
  useCommandProposals(
    typeof noteId === "string" ? noteId : undefined,
    proposalIds,
  );
  const resultItems = askItems(display);
  const interactive = resultItems.some(
    (item) => item.type === "form" || item.type === "pill",
  );
  const text = interactive ? "" : outputText(display);
  const plugin = manifest.data?.plugins.find(
    (p) => p.id === currentOrigin?.plugin,
  );
  const sourceForm =
    currentOrigin?.command === "sources" &&
    ["context-packs", "thinking-desk"].includes(currentOrigin.plugin)
      ? resultItems.find(
          (item) =>
            validComponent(item) &&
            item.type === "form" &&
            (item.schema as Schema).properties?.selected_ids !== undefined,
        )
      : undefined;
  const sourceFields =
    sourceForm && validComponent(sourceForm) && sourceForm.type === "form"
      ? (sourceForm.schema as Schema).properties
      : undefined;
  const selectedIds = sourceFields?.selected_ids?.default;
  const sourceRevision = sourceFields?.expected_revision?.default;
  const sourceLimit = sourceFields?.max_sources?.default;
  const sourcePickerArgs =
    currentOrigin &&
    Array.isArray(selectedIds) &&
    typeof sourceRevision === "number"
      ? {
          ...(currentOrigin.plugin === "context-packs"
            ? { pack_id: currentOrigin.args?.pack_id ?? null }
            : { desk_id: currentOrigin.args?.desk_id ?? null }),
          expected_revision: sourceRevision,
          picker: true,
        }
      : undefined;
  const providedSave = saveCommand(display, saveCommandIds(plugin));
  const askPendingHandle = useRef<Args | undefined>(undefined);
  const askLifetime = useRef({ active: true, generation: requestGeneration() });
  useEffect(() => {
    askLifetime.current.active = true;
    return () => {
      askLifetime.current.active = false;
      if (
        askPendingHandle.current &&
        askLifetime.current.generation === requestGeneration()
      )
        void api
          .command("ask", "clear-conversation", {
            conversation_id: askPendingHandle.current.conversation_id,
          })
          .catch(() => {});
    };
  }, []);
  const saveKey = useMemo(createIdempotencyKey, [display]);
  // Plugins that create notes save their own results; an unknown origin gets no save.
  const genericSave = !!plugin && !plugin.creates;
  const id = currentOrigin?.command;
  const decisionCommand =
    plugin?.id === "decision-journal" &&
    id &&
    ["record-decision", "review-decision", "journal"].includes(id)
      ? id
      : undefined;
  const decisionOffline = !!decisionCommand && !online;
  const formLabel =
    decisionCommand === "record-decision"
      ? t("decisions.save_decision")
      : decisionCommand === "review-decision"
        ? t("decisions.record_outcome")
        : (plugin?.contributes?.commands?.find(
            (c) => c.id === currentOrigin?.command,
          )?.title ?? t("settingsplugins.run"));
  const decisionSaved =
    !interactive &&
    resultItems.some((item) => item.type === "notes") &&
    (decisionCommand === "record-decision" ||
      decisionCommand === "review-decision");
  useEffect(() => {
    if (appliedSession.current === session) return;
    appliedSession.current = session;
    setDisplay(value);
    setCurrentOrigin(origin);
    setResponseId((prior) => prior + 1);
    setCopied(false);
    setSaved(null);
    setApplied([]);
  }, [value, origin, session]);
  const followup = useMutation({
    networkMode: decisionCommand ? "always" : undefined,
    mutationFn: async ({
      plugin,
      id,
      args,
      session: requestedSession,
    }: Pick<CommandChoice, "plugin" | "id" | "args"> & { session: object }) => {
      if (
        plugin === "ask" &&
        id === "ask" &&
        typeof args?.question === "string" &&
        !args.conversation_id
      ) {
        const started = askPendingHandle.current
          ? undefined
          : await api.command("ask", "ask", {
              start: true,
              ...askNoteActionOptions(manifest.data?.plugins, workspaceRole),
            });
        const handle = askPendingHandle.current ?? askContinuation(started);
        if (!handle) throw new Error(outputText(started) || t("ask.failed"));
        askPendingHandle.current = handle;
        if (
          !askLifetime.current.active ||
          requestedSession !== activeSession.current ||
          askLifetime.current.generation !== requestGeneration()
        ) {
          if (askLifetime.current.generation === requestGeneration())
            void api
              .command("ask", "clear-conversation", {
                conversation_id: handle.conversation_id,
              })
              .catch(() => {});
          throw new DOMException("Conversation closed", "AbortError");
        }
        return api.command(plugin, id, { ...handle, ...args });
      }
      return api.command(plugin, id, args ?? {});
    },
    onSuccess: (next, choice) => {
      if (
        choice.session !== activeSession.current ||
        !askLifetime.current.active
      )
        return;
      if (choice.plugin === "ask") askPendingHandle.current = undefined;
      setDisplay(next);
      setCurrentOrigin({
        plugin: choice.plugin,
        command: choice.id,
        args: choice.args,
      });
      setResponseId((prior) => prior + 1);
      setCopied(false);
      setSaved(null);
      setApplied([]);
      if (
        choice.plugin === "decision-journal" &&
        ["record-decision", "review-decision"].includes(choice.id)
      )
        void invalidateContentFor(client);
      else void client.invalidateQueries({ queryKey: ["notes"] });
    },
  });
  const conversationView =
    currentOrigin?.plugin === "ask" &&
    !!(
      askContinuation(display) ||
      (currentOrigin.command === "ask" &&
        askNoteActionOptions(manifest.data?.plugins, workspaceRole)
          .note_actions === true &&
        askItems(display).some((item) => item.type === "form"))
    );
  const save = useMutation({
    mutationFn: () => api.createNote(text, saveKey),
    onSuccess: (note) => {
      setSaved({ id: note?.id });
      void client.invalidateQueries({ queryKey: ["notes"] });
    },
  });
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !document.querySelector("dialog[open]"))
        onDismiss?.();
      if ((e.metaKey || e.ctrlKey) && e.key === "s") {
        e.preventDefault();
        if (conversationView) return;
        if (currentOrigin && providedSave && !followup.isPending)
          followup.mutate({
            ...providedSave,
            plugin: currentOrigin.plugin,
            session,
          });
        else if (genericSave && text && !save.isPending && !saved)
          save.mutate();
      }
    };
    document.addEventListener("keydown", key);
    return () => document.removeEventListener("keydown", key);
  }, [
    onDismiss,
    text,
    save,
    saved,
    currentOrigin,
    providedSave,
    followup,
    genericSave,
    session,
    conversationView,
  ]);
  if (conversationView)
    return (
      <Suspense fallback={<p role="status">{t("timeline.loading_notes")}</p>}>
        <AskConversation
          key={responseId}
          value={askContinuation(display) ? display : undefined}
          question={
            typeof currentOrigin?.args?.question === "string"
              ? currentOrigin.args.question
              : ""
          }
          onDismiss={onDismiss}
        />
      </Suspense>
    );
  return (
    <div className={styles.result}>
      {currentOrigin?.plugin === "ask" &&
        typeof currentOrigin?.args?.question === "string" && (
          <p>{currentOrigin.args.question}</p>
        )}
      <p role="status" className={decisionSaved ? undefined : "sr-only"}>
        {decisionSaved &&
          t(
            decisionCommand === "record-decision"
              ? "decisions.decision_saved"
              : "decisions.outcome_saved",
          )}
      </p>
      {decisionOffline && <p role="status">{t("decisions.reconnect")}</p>}
      <VocabularyOutput
        key={responseId}
        formSubmitDisabled={decisionOffline}
        formSchema={
          decisionCommand
            ? (schema) => decisionForm(decisionCommand, schema)
            : undefined
        }
        formDetails={
          decisionCommand === "record-decision"
            ? {
                label: t("decisions.add_details"),
                fields: ["title", "alternatives", "rationale", "expected"],
              }
            : undefined
        }
        formChoices={(field, value) => pluginFormChoice(plugin, field, value)}
        commandLabel={decisionCommand ? decisionCommandLabel : undefined}
        displayText={decisionCommand === "journal" ? decisionStatus : undefined}
        value={visibleResult}
        formLabel={
          sourcePickerArgs ? t("notepicker.update_sources") : formLabel
        }
        onCommand={
          currentOrigin && !followup.isPending && !decisionOffline
            ? (id, args) =>
                followup.mutate({
                  plugin: currentOrigin.plugin,
                  id,
                  args:
                    id === "sources" &&
                    !args.action &&
                    plugin &&
                    supportsNotePicker(plugin) &&
                    ["context-packs", "thinking-desk"].includes(
                      currentOrigin.plugin,
                    )
                      ? { ...args, picker: true }
                      : args,
                  session,
                })
            : undefined
        }
        onSuggestion={
          typeof noteId === "string" &&
          plugin &&
          (plugin.id !== "links" || supportsNotePicker(plugin)) &&
          !followup.isPending
            ? (id) => setReview({ id, response: responseId })
            : undefined
        }
        onForm={
          currentOrigin?.command && !followup.isPending
            ? (args) => {
                if (decisionOffline) return;
                followup.mutate({
                  plugin: currentOrigin.plugin,
                  id: currentOrigin.command,
                  args: sourcePickerArgs
                    ? {
                        ...sourcePickerArgs,
                        remove: args.remove ?? null,
                        refresh: args.refresh === true,
                      }
                    : { ...currentOrigin.args, ...args },
                  session,
                });
              }
            : undefined
        }
      />
      {sourcePickerArgs &&
        currentOrigin &&
        Array.isArray(selectedIds) &&
        (typeof sourceLimit === "number" &&
        selectedIds.length >= sourceLimit ? (
          <p role="status">
            {t("notepicker.source_limit", { count: sourceLimit })}
          </p>
        ) : (
          <Suspense
            fallback={<p role="status">{t("timeline.loading_notes")}</p>}
          >
            <NotePicker
              exclude={selectedIds.filter(
                (id): id is string => typeof id === "string",
              )}
              action={t("notepicker.add_source")}
              busy={followup.isPending}
              onSelect={(note) =>
                followup.mutate({
                  plugin: currentOrigin.plugin,
                  id: currentOrigin.command,
                  args: {
                    ...sourcePickerArgs,
                    add_id: note.id,
                    add_version: note.version,
                  },
                  session,
                })
              }
            />
          </Suspense>
        ))}
      {followup.isPending && <p role="status">{t("timeline.loading_notes")}</p>}
      {review?.response === responseId &&
        typeof noteId === "string" &&
        plugin && (
          <Suspense
            fallback={<p role="status">{t("timeline.loading_notes")}</p>}
          >
            <CommandProposal
              key={review.id}
              id={review.id}
              plugin={plugin.id}
              noteId={noteId}
              onClose={() => setReview(undefined)}
              onApplied={() => {
                setReview(undefined);
                setApplied((ids) => [...ids, review.id]);
              }}
            />
          </Suspense>
        )}
      {applied.length > 0 && <p>{t("commandproposal.applied")}</p>}
      {(!decisionCommand || onDismiss) && (
        <footer>
          {!decisionCommand && !interactive && (
            <button
              disabled={!text}
              onClick={() => void copyText(text).then(setCopied)}
            >
              {copied ? t("settingsaccess.copied") : t("slots.copy_text")}
            </button>
          )}
          {genericSave && !interactive && (
            <button
              className="primary"
              disabled={!text || save.isPending || !!saved}
              onClick={() => save.mutate()}
            >
              {saved
                ? t("slots.saved_as_note")
                : save.isPending
                  ? t("app.saving")
                  : t("slots.save_as_note")}
            </button>
          )}
          {saved?.id && (
            <Link href={`/n/${encodeURIComponent(saved.id)}`}>
              {t("slots.open_note")}
            </Link>
          )}
          {onDismiss && <button onClick={onDismiss}>{t("slots.back")}</button>}
        </footer>
      )}
      {save.isError && (
        <p role="alert" className="error">
          {save.error.message}
        </p>
      )}
      {followup.error && (
        <p role="alert" className="error">
          {followup.error.message}
          {safeError(followup.error).code === "version_conflict" && (
            <> {t("slots.stale_form_recovery")}</>
          )}
        </p>
      )}
    </div>
  );
}
