import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  lazy,
  Suspense,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";
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
import { choiceLabel } from "./format";
import {
  supportsNotePicker,
  usePlugins,
  type CommandChoice,
  type CommandOrigin,
} from "./Slots";
import { useCommandProposals } from "./suggestionFeedback";
import { ResultNoteLink, safePluginHtml, VocabularyOutput } from "./Vocabulary";
import { deskWorkspace } from "./thinkingDeskData";
import { outputText, record, validComponent } from "./vocabularyData";
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
  if (
    plugin.id === "translate" &&
    field === "source_language" &&
    value === "auto"
  )
    return t("plugin.translate.detect_source");
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
type DeskPageActions = {
  route: (args: Args, origin: CommandOrigin | undefined) => boolean;
  changed: (value: unknown, command: string, args?: Args) => void;
};

export default function CommandResult(props: {
  value: unknown;
  onDismiss?: () => void;
  onOpenNote?: () => void;
  surroundingTitle?: string;
  origin?: CommandOrigin;
  deskPage?: DeskPageActions;
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
  onOpenNote,
  surroundingTitle,
  origin,
  deskPage,
}: {
  value: unknown;
  onDismiss?: () => void;
  onOpenNote?: () => void;
  surroundingTitle?: string;
  origin?: CommandOrigin;
  deskPage?: DeskPageActions;
}) {
  useLanguage();
  const heading = useRef<HTMLHeadingElement>(null);
  const headingId = useId();
  const panel = useRef<HTMLElement>(null);
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
  const workspace = deskPage ? deskWorkspace(display) : undefined;
  const resultItems = workspace?.items ?? askItems(display);
  const pageItems = deskPage
    ? resultItems.filter(
        (item) =>
          item.type !== "command" ||
          item.id !== "desk" ||
          (record(item.args) && Object.keys(item.args).length > 0) ||
          (currentOrigin?.command === "edit" &&
            currentOrigin.args?.action === "start" &&
            typeof currentOrigin.args.title !== "string"),
      )
    : resultItems;
  const interactive = resultItems.some(
    (item) => item.type === "form" || item.type === "pill",
  );
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
  const thinkingDesk =
    plugin?.id === "thinking-desk" && isBundledPlugin(plugin);
  const deskForm = thinkingDesk
    ? resultItems.find((item) => item.type === "form")
    : undefined;
  const deskFormLabel = (deskForm?.schema as Schema | undefined)?.title;
  const deskStartActions = thinkingDesk
    ? resultItems.filter(
        (item) =>
          item.type === "command" &&
          item.id === "edit" &&
          (item.args as Args | undefined)?.action === "start",
      )
    : [];
  const deskWorkAction = thinkingDesk
    ? resultItems.find((item) => {
        const args = item.args as Args | undefined;
        return (
          item.type === "command" &&
          typeof args?.expected_revision === "number" &&
          (item.id === "sources" ||
            (item.id === "edit" &&
              ["write", "snapshot"].includes(String(args.action))))
        );
      })
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
  const deskPicker = thinkingDesk && !!sourcePickerArgs;
  const deskPickerContinue = deskPicker
    ? resultItems
        .slice(0, resultItems.indexOf(sourceForm!))
        .find((item) => item.type === "command" && item.id === "desk")
    : undefined;
  const deskPrimary = deskPickerContinue ?? deskWorkAction;
  const deskPrimaryActions = deskPrimary ? [deskPrimary] : deskStartActions;
  const deskPickerChanged =
    deskPicker &&
    (typeof currentOrigin?.args?.add_id === "string" ||
      typeof currentOrigin?.args?.remove === "string" ||
      currentOrigin?.args?.refresh === true);
  const deskPickerStatus =
    deskPickerChanged && resultItems[0]?.type === "text"
      ? String(resultItems[0].text)
      : "";
  const providedSave = saveCommand(display, saveCommandIds(plugin));
  const translationLanguage = currentOrigin?.args?.language;
  const translationPreview =
    plugin?.id === "translate" &&
    isBundledPlugin(plugin) &&
    providedSave?.id === "save-translation" &&
    typeof translationLanguage === "string";
  const translationHtml =
    translationPreview &&
    record(display) &&
    typeof display.preview_html === "string" &&
    display.preview_html.length <= 1_000_000
      ? display.preview_html
      : undefined;
  const text = translationPreview
    ? resultItems
        .filter((item) => item.type === "markdown")
        .map((item) => String(item.text ?? item.markdown ?? ""))
        .join("")
    : interactive
      ? ""
      : outputText(display);
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
  const commandName = plugin?.contributes?.commands?.find(
    (command) => command.id === currentOrigin?.command,
  )?.title;
  const title = onDismiss
    ? ((thinkingDesk ? plugin?.name : commandName) ??
      plugin?.name ??
      t("commandresult.title"))
    : undefined;
  const showHeading = title && title !== surroundingTitle;
  const formLabel =
    decisionCommand === "record-decision"
      ? t("decisions.save_decision")
      : decisionCommand === "review-decision"
        ? t("decisions.record_outcome")
        : (commandName ?? t("settingsplugins.run"));
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
    networkMode: "always",
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
      deskPage?.changed(next, choice.id, choice.args);
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
    networkMode: "always",
    mutationFn: () => api.createNote(text, saveKey),
    onSuccess: (note) => {
      setSaved({ id: note?.id });
      void client.invalidateQueries({ queryKey: ["notes"] });
    },
  });
  useEffect(() => {
    if (conversationView) return;
    if (thinkingDesk) {
      const dialog = panel.current?.closest<HTMLElement>(
        ".palette, dialog, [role='dialog']",
      );
      if (dialog) dialog.scrollTop = 0;
    }
    const pickerAction = deskPickerChanged
      ? panel.current?.querySelector<HTMLElement>(
          deskPickerContinue ? "button.primary" : 'input[type="search"]',
        )
      : undefined;
    (pickerAction ?? heading.current ?? panel.current)?.focus({
      preventScroll: thinkingDesk && !pickerAction,
    });
  }, [
    responseId,
    conversationView,
    thinkingDesk,
    deskPickerChanged,
    deskPickerContinue,
  ]);
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.defaultPrevented) return;
      if (
        e.key === "Escape" &&
        !document.querySelector('dialog[open], [role="dialog"]')
      )
        onDismiss?.();
      if ((e.metaKey || e.ctrlKey) && e.key === "s") {
        e.preventDefault();
        if (conversationView || !online) return;
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
    online,
  ]);
  const sourcePicker =
    sourcePickerArgs &&
    currentOrigin &&
    Array.isArray(selectedIds) &&
    (typeof sourceLimit === "number" && selectedIds.length >= sourceLimit ? (
      <p role="status">
        {t("notepicker.source_limit", { count: sourceLimit })}
      </p>
    ) : (
      <Suspense fallback={<p role="status">{t("timeline.loading_notes")}</p>}>
        <NotePicker
          exclude={selectedIds.filter(
            (id): id is string => typeof id === "string",
          )}
          action={t("notepicker.add_source")}
          busy={followup.isPending || !online}
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
    ));
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
    <section
      ref={panel}
      className={styles.resultPanel}
      tabIndex={showHeading ? undefined : -1}
      aria-labelledby={showHeading ? headingId : undefined}
      aria-label={showHeading ? undefined : (title ?? t("commandresult.title"))}
    >
      {showHeading && (
        <h2 id={headingId} ref={heading} tabIndex={-1}>
          {title}
        </h2>
      )}
      {currentOrigin?.plugin === "ask" &&
        typeof currentOrigin?.args?.question === "string" && (
          <p>{currentOrigin.args.question}</p>
        )}
      <p
        role="status"
        className={
          decisionSaved || deskPickerStatus ? styles.resultStatus : "sr-only"
        }
      >
        {deskPickerStatus}
        {decisionSaved &&
          t(
            decisionCommand === "record-decision"
              ? "decisions.decision_saved"
              : "decisions.outcome_saved",
          )}
      </p>
      {decisionOffline && (
        <p role="status" className={styles.resultStatus}>
          {t("decisions.reconnect")}
        </p>
      )}
      {translationPreview && (
        <div className={styles.resultStatus}>
          <strong>
            {t("plugin.translate.preview", {
              language: choiceLabel(translationLanguage),
            })}
          </strong>
          <p>{t("plugin.translate.linked_note")}</p>
        </div>
      )}
      <div
        className={
          deskStartActions.length
            ? `${styles.resultBody} ${styles.deskChoices}${deskPage ? " " + styles.deskPageChoices : ""}`
            : styles.resultBody
        }
        aria-busy={followup.isPending}
      >
        {translationHtml && (
          <div
            dangerouslySetInnerHTML={{
              __html: safePluginHtml(translationHtml),
            }}
          />
        )}
        <VocabularyOutput
          key={responseId}
          result
          onOpenNote={onOpenNote}
          primaryCommand={providedSave?.id}
          primaryActions={deskPrimaryActions}
          formSubmitDisabled={!online}
          formSubmitOnChange={deskPicker}
          beforeForm={thinkingDesk ? sourcePicker : undefined}
          hideForm={
            thinkingDesk &&
            !!sourcePickerArgs &&
            Array.isArray(selectedIds) &&
            selectedIds.length === 0
          }
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
          displayText={
            decisionCommand === "journal" ? decisionStatus : undefined
          }
          value={
            deskPickerStatus
              ? pageItems.slice(1)
              : translationHtml
                ? resultItems.filter((item) => item.type !== "markdown")
                : deskPage
                  ? pageItems
                  : visibleResult
          }
          formLabel={
            deskFormLabel ??
            (sourcePickerArgs ? t("notepicker.update_sources") : formLabel)
          }
          onCommand={
            currentOrigin && online && !followup.isPending
              ? (id, args) => {
                  if (id === "desk" && deskPage?.route(args, currentOrigin))
                    return;
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
                  });
                }
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
                  if (!online) return;
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
      </div>
      {!thinkingDesk && sourcePicker}
      {(followup.isPending || save.isPending) && (
        <p role="status" className={styles.resultStatus}>
          {t("plugins.running")}
        </p>
      )}
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
      {applied.length > 0 && (
        <p role="status" className={styles.resultStatus}>
          {t("commandproposal.applied")}
        </p>
      )}
      {!deskPage &&
        ((!decisionCommand &&
          !interactive &&
          text &&
          !deskStartActions.length) ||
          saved?.id ||
          onDismiss) && (
          <footer>
            {!decisionCommand &&
              !interactive &&
              text &&
              !deskStartActions.length && (
                <button
                  className="button"
                  onClick={() => void copyText(text).then(setCopied)}
                >
                  {copied ? t("settingsaccess.copied") : t("slots.copy_text")}
                </button>
              )}
            {genericSave && !interactive && text && (
              <button
                className="primary"
                disabled={!online || save.isPending || !!saved}
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
              <ResultNoteLink id={saved.id} onOpen={onOpenNote}>
                {t("slots.open_note")}
              </ResultNoteLink>
            )}
            {onDismiss && (
              <button className="button" onClick={onDismiss}>
                {t("slots.back")}
              </button>
            )}
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
    </section>
  );
}
