import { useManifest } from "../lib/query";
import { getLanguage, t, useLanguage } from "../lib/i18n";
import {
  lazy,
  Suspense,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "wouter";
import { api } from "../lib/api";
import { NoteBody } from "../components/core/NoteCard";
import { useToast } from "../components/core/Toast";
import { createIdempotencyKey } from "../lib/drafts";
import { copyText } from "./copy";
import type { Args, Annotation, Note, Plugin, Suggestion } from "../lib/types";
import { SuggestionPreview } from "./LazyPluginViews";
import { VocabularyOutput } from "./Vocabulary";
import { outputText, validComponent } from "./vocabularyData";
import { Dialog } from "./Dialog";
import { suggestionTitle } from "./suggestionTitle";
import { labelChangeImpact } from "./labelChange";
import { plural } from "./format";
import styles from "./Feature.module.css";
import { requestGeneration } from "../lib/identity";
import { askContinuation, askNoteActionOptions } from "./askConversationData";
const AskConversation = lazy(() => import("./AskConversation"));
export function usePlugins() {
  return useManifest();
}
export function activePlugins(plugins: Plugin[] | undefined) {
  return (plugins ?? [])
    .filter((p) => p.state === undefined || p.state === "enabled")
    .sort((a, b) => a.position - b.position);
}
export function visibleSuggestions(
  note: Pick<Note, "suggestions">,
  plugins: Plugin[],
) {
  const ids = new Set(activePlugins(plugins).map((p) => p.id));
  const counts = new Map<string, number>();
  return (note.suggestions ?? []).filter(
    (s) =>
      s.status === "pending" &&
      ids.has(s.plugin) &&
      (counts.set(s.plugin, (counts.get(s.plugin) ?? 0) + 1).get(s.plugin) ??
        0) <= 3,
  );
}
export function ReviewLine() {
  useLanguage();
  const manifest = usePlugins();
  const active = new Set(
    activePlugins(manifest.data?.plugins).map((p) => p.id),
  );
  const query = useQuery({
    queryKey: ["suggestions", "review"],
    queryFn: () => api.suggestions("review"),
    enabled: active.size > 0,
  });
  const count =
    query.data?.suggestions.filter((s) => active.has(s.plugin)).length ?? 0;
  return count >= 3 ? (
    <p className={styles.reviewLine}>
      {plural(count, "suggestion")} ·{" "}
      <Link href="/review">{t("review.review")}</Link>
    </p>
  ) : null;
}
export function SuggestionLine({
  note,
  onReview,
  editing = false,
}: {
  note: Note;
  onReview?: () => void;
  editing?: boolean;
}) {
  useLanguage();
  const manifest = usePlugins();
  const client = useQueryClient();
  const [expanded, setExpanded] = useState(false);
  const [preview, setPreview] = useState<Suggestion | null>(null);
  const opener = useRef<HTMLElement>(null);
  const describe = useId();
  const toast = useToast();
  // A touch long-press shows the provenance a mouse gets from the tooltip.
  const press = useRef<{ timer?: number; shown?: boolean }>({});
  const release = () => {
    window.clearTimeout(press.current.timer);
    press.current.timer = undefined;
  };
  const reveal = (provenance: string) => {
    release();
    press.current.shown = true;
    toast.show({ message: provenance });
  };
  const impact = useQuery({
    queryKey: [
      "label-impact",
      preview?.payload.old,
      preview?.payload.new,
      preview?.payload.note_ids,
    ],
    queryFn: () =>
      api.request<{ notes: number }>("/api/v1/labels/impact", {
        method: "POST",
        body: labelChangeImpact(preview?.payload),
      }),
    enabled: preview?.action === "label_change",
    retry: false,
  });
  const decision = useMutation({
    mutationFn: ({
      id,
      action,
    }: {
      id: string;
      action: "accept" | "dismiss";
    }) => api.decideSuggestion(id, action),
    onSuccess: () => {
      setPreview(null);
      void client.invalidateQueries({ queryKey: ["notes"] });
      void client.invalidateQueries({ queryKey: ["note", note.id] });
      void client.invalidateQueries({ queryKey: ["suggestions"] });
    },
  });
  useEffect(() => {
    setExpanded(false);
    setPreview(null);
  }, [note.id]);
  const suggestions = visibleSuggestions(note, manifest.data?.plugins ?? []);
  if (editing || !suggestions.length) return null;
  const apply = (s: Suggestion, trigger: HTMLElement) => {
    opener.current = trigger;
    if (["replace_body", "merge_notes", "label_change"].includes(s.action))
      setPreview(s);
    else decision.mutate({ id: s.id, action: "accept" });
  };
  return (
    <div
      className={styles.suggestions}
      aria-label={t("slots.suggestions")}
      onKeyDown={(e) => {
        if (
          e.target instanceof HTMLInputElement ||
          e.target instanceof HTMLTextAreaElement
        )
          return;
        if (e.key === "x") {
          e.preventDefault();
          for (const s of suggestions)
            decision.mutate({ id: s.id, action: "dismiss" });
        }
        if (e.key === "a") {
          e.preventDefault();
          if (
            suggestions.some((s) =>
              ["replace_body", "merge_notes", "label_change"].includes(
                s.action,
              ),
            )
          ) {
            opener.current = null;
            onReview ? onReview() : setPreview(suggestions[0]);
          } else
            for (const s of suggestions)
              decision.mutate({ id: s.id, action: "accept" });
        }
      }}
    >
      {(expanded ? suggestions : suggestions.slice(0, 3)).map((s) => {
        const by = manifest.data?.plugins.find((p) => p.id === s.plugin)?.name;
        const provenance = t("slots.suggested_by_value1_value2", {
          value1: by ?? s.plugin,
          value2: s.reason ? `: ${s.reason}` : "",
        });
        return (
          <span className={styles.suggestionGroup} key={s.id}>
            <button
              className={styles.pill}
              disabled={decision.isPending}
              title={provenance}
              aria-describedby={`${describe}-${s.id}`}
              onPointerDown={(e) => {
                press.current =
                  e.pointerType === "mouse"
                    ? {}
                    : {
                        timer: window.setTimeout(() => reveal(provenance), 500),
                      };
              }}
              onPointerUp={release}
              onPointerCancel={release}
              onPointerLeave={release}
              onContextMenu={(e) => {
                if (press.current.timer === undefined) return;
                e.preventDefault();
                reveal(provenance);
              }}
              onClick={(e) => {
                const held = press.current.shown;
                press.current = {};
                if (!held) apply(s, e.currentTarget);
              }}
            >
              {suggestionTitle(s)}
              {s.payload.is_new === true && <small>{t("slots.new")}</small>}
            </button>
            <span id={`${describe}-${s.id}`} hidden>
              {provenance}
            </span>
            <button
              className={styles.dismiss}
              aria-label={t("slots.dismiss_value1", {
                value1: suggestionTitle(s),
              })}
              disabled={decision.isPending}
              onClick={() => decision.mutate({ id: s.id, action: "dismiss" })}
            >
              ×
            </button>
          </span>
        );
      })}
      {!expanded && suggestions.length > 3 && (
        <button className={styles.overflow} onClick={() => setExpanded(true)}>
          +{suggestions.length - 3}
        </button>
      )}
      <button
        className={styles.dismiss}
        aria-label={t("slots.dismiss_value1", {
          value1: plural(suggestions.length, "suggestion"),
        })}
        disabled={decision.isPending}
        onClick={() => {
          for (const s of suggestions)
            decision.mutate({ id: s.id, action: "dismiss" });
        }}
      >
        {t("review.dismiss")}
      </button>
      {decision.isError && (
        <p className="error" role="alert">
          {decision.error.message}
        </p>
      )}
      {preview && (
        <Dialog
          title={suggestionTitle(preview)}
          opener={opener.current}
          onClose={() => setPreview(null)}
          footer={
            <>
              <button onClick={() => setPreview(null)}>
                {t("profilepanel.cancel")}
              </button>
              <button
                className="primary"
                disabled={
                  decision.isPending ||
                  (preview.action === "label_change" && !impact.data)
                }
                onClick={() =>
                  decision.mutate({ id: preview.id, action: "accept" })
                }
              >
                {t("review.apply")}
              </button>
            </>
          }
        >
          <SuggestionPreview suggestion={preview} />
        </Dialog>
      )}
    </div>
  );
}
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
function annotationComponents(annotation: Annotation) {
  return outputComponents(annotation.payload);
}
function panelItems(items: unknown[]) {
  return items.reduce<number>((count, item) => {
    if (validComponent(item) && item.type === "notes")
      return count + ((item.ids ?? item.items) as unknown[]).length;
    return count + 1;
  }, 0);
}
export function EntryMeta({ note }: { note: Note }) {
  useLanguage();
  const manifest = usePlugins();
  for (const plugin of activePlugins(manifest.data?.plugins)) {
    const annotation = note.annotations?.find(
      (a) => a.plugin === plugin.id && a.kind === "meta",
    );
    if (annotation) {
      const text = [...outputText(annotation.payload)].slice(0, 24).join("");
      if (text) return <span className={styles.meta}>{text}</span>;
    }
  }
  return null;
}
function PanelSection({
  plugin,
  note,
  annotation,
}: {
  plugin: Plugin;
  note: Note;
  annotation?: Annotation;
}) {
  useLanguage();
  const panel = plugin.contributes?.panel as
    { title?: string; mode?: string; route?: string } | undefined;
  const excludedInput =
    (note.private || note.machine) &&
    plugin.permissions.some((permission) => permission.startsWith("ai."));
  const [opened, setOpened] = useState(() => {
    try {
      return localStorage.getItem(`spunto-panel-${plugin.id}`) === "open";
    } catch {
      return false;
    }
  });
  const command = useMutation({
    mutationFn: ({ id, args }: { id: string; args: Args }) =>
      api.command(plugin.id, id, args),
    onSuccess: (result) =>
      window.dispatchEvent(
        new CustomEvent("spunto:command-result", {
          detail: {
            value: result,
            origin: { plugin: plugin.id, command: "" },
          },
        }),
      ),
  });
  const query = useQuery({
    queryKey: ["plugin-panel", plugin.id, note.id, note.version],
    queryFn: ({ signal }) => api.pluginPanel(plugin.id, note.id, signal),
    enabled: panel?.mode === "live" && navigator.onLine && !excludedInput,
    retry: false,
  });
  const output =
    panel?.mode === "live" && excludedInput
      ? undefined
      : (annotation?.payload ?? query.data);
  if (!output) return null;
  const count = panelItems(outputComponents(output));
  if (!count) return null;
  return (
    <details
      open={opened}
      onToggle={(event) => {
        const open = event.currentTarget.open;
        setOpened(open);
        try {
          localStorage.setItem(
            `spunto-panel-${plugin.id}`,
            open ? "open" : "closed",
          );
        } catch {}
      }}
    >
      <summary>
        {panel?.title ?? plugin.name} · {plural(count, "item")}
      </summary>
      <VocabularyOutput
        value={output}
        allow={["text", "markdown", "notes", "command"]}
        onCommand={(id, args) => command.mutate({ id, args })}
      />
      {command.isError && (
        <p role="alert" className="error">
          {command.error.message}
        </p>
      )}
    </details>
  );
}
export function NotePanel({ note }: { note: Note }) {
  useLanguage();
  const manifest = usePlugins();
  const plugins = activePlugins(manifest.data?.plugins).filter(
    (p) =>
      p.contributes?.panel ||
      note.annotations?.some((a) => a.plugin === p.id && a.kind === "panel"),
  );
  if (!plugins.length) return null;
  return (
    <aside className={styles.panel} aria-label={t("slots.about_this_note")}>
      {plugins.map((p) => (
        <PanelSection
          key={p.id}
          plugin={p}
          note={note}
          annotation={note.annotations?.find(
            (a) => a.plugin === p.id && a.kind === "panel",
          )}
        />
      ))}
    </aside>
  );
}
export function PluginNoteSlots({
  note,
  onReview,
  editing = false,
}: {
  note: Note;
  onReview?: () => void;
  editing?: boolean;
}) {
  useLanguage();
  return (
    <>
      <SuggestionLine note={note} onReview={onReview} editing={editing} />
      <NotePanel note={note} />
    </>
  );
}
export function DailySlip() {
  useLanguage();
  const manifest = usePlugins();
  const client = useQueryClient();
  const toast = useToast();
  const query = useQuery({
    queryKey: ["slip"],
    queryFn: () =>
      api.request<{
        slip: {
          plugin_id: string;
          note_id: string;
          payload: unknown;
          note: Note;
          updated_at: string;
        } | null;
      }>("/api/v1/slip"),
    enabled: !!manifest.data?.plugins.some((p) => p.contributes?.slip),
  });
  const dismiss = useMutation({
    mutationFn: ({
      plugin_id,
      note_id,
    }: {
      plugin_id: string;
      note_id: string;
    }) =>
      api.request("/api/v1/slip/dismiss", {
        method: "POST",
        body: { plugin_id, note_id },
      }),
    onSuccess: () => void client.invalidateQueries({ queryKey: ["slip"] }),
  });
  const refresh = () => {
    void client.invalidateQueries({ queryKey: ["slip"] });
    void client.invalidateQueries({ queryKey: ["notes"] });
  };
  const archive = useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      api.flags(id, { archived }),
    onSuccess: (_, { id, archived }) => {
      refresh();
      if (archived)
        toast.show({
          message: t("slots.archived"),
          actionLabel: t("settingsplugins.undo"),
          onAction: () => archive.mutate({ id, archived: false }),
        });
    },
    onError: (error) => toast.show({ message: error.message }),
  });
  const slip = query.data?.slip;
  if (!slip || !manifest.data?.plugins.some((p) => p.id === slip.plugin_id))
    return null;
  return (
    <section className={styles.slip}>
      <p className="overline">
        {t("slots.from_your_notes")}{" "}
        {new Date(slip.note.created_at).toLocaleDateString(getLanguage(), {
          dateStyle: "long",
        })}
      </p>
      <blockquote>
        <NoteBody note={slip.note} clamp />
      </blockquote>
      <div className={styles.row}>
        <Link href={`/n/${encodeURIComponent(slip.note_id)}`}>
          {t("slots.open")}
        </Link>
        <button
          disabled={archive.isPending}
          onClick={() => archive.mutate({ id: slip.note_id, archived: true })}
        >
          {t("app.archive")}
        </button>
        <button
          aria-label={t("slots.not_today")}
          disabled={dismiss.isPending}
          onClick={() => dismiss.mutate(slip)}
        >
          ×
        </button>
      </div>
      {dismiss.error && (
        <p role="alert" className="error">
          {dismiss.error.message}
        </p>
      )}
    </section>
  );
}
export function PluginRailViews() {
  useLanguage();
  const manifest = usePlugins();
  const settings = useQuery({ queryKey: ["settings"], queryFn: api.settings });
  const client = useQueryClient();
  const [more, setMore] = useState(false);
  const pin = useMutation({
    mutationFn: (id: string) =>
      api.saveSettings({
        hidden_views: settings.data?.hidden_views.filter((v) => v !== id) ?? [],
      }),
    onSuccess: () => void client.invalidateQueries({ queryKey: ["settings"] }),
  });
  const views = activePlugins(manifest.data?.plugins).flatMap((p) =>
    (p.contributes?.views ?? [])
      .slice(0, 1)
      .map((v) => ({ ...v, plugin: p.id, key: `${p.id}/${v.id}` })),
  );
  if (!views.length) return null;
  return (
    <section className={styles.railGroup}>
      <h3>{t("slots.views")}</h3>
      {views
        .filter((v) => !settings.data?.hidden_views.includes(v.key))
        .map((v) => (
          <Link
            key={v.key}
            href={`/v/${encodeURIComponent(v.plugin)}/${encodeURIComponent(v.id)}`}
          >
            {v.title}
          </Link>
        ))}
      {views.some((v) => settings.data?.hidden_views.includes(v.key)) && (
        <>
          <button onClick={() => setMore(!more)} aria-expanded={more}>
            {t("settingsplugins.show")}
            {more ? "fewer" : "more"} {t("slots.views_2")}
          </button>
          {more &&
            views
              .filter((v) => settings.data?.hidden_views.includes(v.key))
              .map((v) => (
                <div key={v.key} className={styles.row}>
                  <Link
                    href={`/v/${encodeURIComponent(v.plugin)}/${encodeURIComponent(v.id)}`}
                  >
                    {v.title}
                  </Link>
                  <button
                    onClick={() => pin.mutate(v.key)}
                    disabled={pin.isPending}
                  >
                    {t("slots.pin")}
                  </button>
                </div>
              ))}
        </>
      )}
    </section>
  );
}
export type CommandOrigin = { plugin: string; command: string; args?: Args };
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
export function CommandResult({
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
  const [display, setDisplay] = useState(value);
  const [currentOrigin, setCurrentOrigin] = useState(origin);
  const [responseId, setResponseId] = useState(0);
  const session = useMemo(() => ({}), [value, origin]);
  const activeSession = useRef(session);
  const appliedSession = useRef(session);
  activeSession.current = session;
  const text = outputText(display);
  const plugin = manifest.data?.plugins.find(
    (p) => p.id === currentOrigin?.plugin,
  );
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
  const formLabel =
    plugin?.contributes?.commands?.find((c) => c.id === currentOrigin?.command)
      ?.title ?? t("settingsplugins.run");
  useEffect(() => {
    if (appliedSession.current === session) return;
    appliedSession.current = session;
    setDisplay(value);
    setCurrentOrigin(origin);
    setResponseId((prior) => prior + 1);
    setCopied(false);
    setSaved(null);
  }, [value, origin, session]);
  const followup = useMutation({
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
              ...askNoteActionOptions(manifest.data?.plugins),
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
      void client.invalidateQueries({ queryKey: ["notes"] });
    },
  });
  const save = useMutation({
    mutationFn: () => api.createNote(text, saveKey),
    onSuccess: (note) => {
      setSaved({ id: note?.id });
      void client.invalidateQueries({ queryKey: ["notes"] });
    },
  });
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape") onDismiss?.();
      if ((e.metaKey || e.ctrlKey) && e.key === "s") {
        e.preventDefault();
        if (currentOrigin?.plugin === "ask" && askContinuation(display)) return;
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
    display,
  ]);
  if (currentOrigin?.plugin === "ask" && askContinuation(display))
    return (
      <Suspense fallback={<p role="status">{t("timeline.loading_notes")}</p>}>
        <AskConversation
          key={responseId}
          value={display}
          question={
            typeof currentOrigin.args?.question === "string"
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
        typeof currentOrigin.args?.question === "string" && (
          <p>{currentOrigin.args.question}</p>
        )}
      <VocabularyOutput
        key={responseId}
        value={display}
        formLabel={formLabel}
        onCommand={
          currentOrigin && !followup.isPending
            ? (id, args) =>
                followup.mutate({
                  plugin: currentOrigin.plugin,
                  id,
                  args,
                  session,
                })
            : undefined
        }
        onForm={
          currentOrigin?.command && !followup.isPending
            ? (args) =>
                followup.mutate({
                  plugin: currentOrigin.plugin,
                  id: currentOrigin.command,
                  args: { ...currentOrigin.args, ...args },
                  session,
                })
            : undefined
        }
      />
      {followup.isPending && currentOrigin?.plugin === "ask" && (
        <p role="status">{t("timeline.loading_notes")}</p>
      )}
      <footer>
        <button
          disabled={!text}
          onClick={() => void copyText(text).then(setCopied)}
        >
          {copied ? t("settingsaccess.copied") : t("slots.copy_text")}
        </button>
        {genericSave && (
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
      {save.isError && (
        <p role="alert" className="error">
          {save.error.message}
        </p>
      )}
      {followup.error && (
        <p role="alert" className="error">
          {followup.error.message}
        </p>
      )}
    </div>
  );
}
export type CommandChoice = {
  plugin: string;
  id: string;
  title: string;
  args?: Args;
};
export function SlashCommands({
  query = "",
  onChoose,
}: {
  query: string;
  onChoose: (choice: CommandChoice) => void;
}) {
  useLanguage();
  return <PluginCommands query={query} where="slash" onChoose={onChoose} />;
}
export function PaletteCommands({
  query = "",
  noteId,
  onResult,
}: {
  query?: string;
  noteId?: string;
  onResult?: (value: unknown, origin: CommandOrigin) => void;
}) {
  useLanguage();
  // One row per command: a note action that is also a palette command runs on the open note.
  return (
    <PluginCommands
      query={query}
      where={noteId ? ["note-actions", "palette"] : ["palette"]}
      noteId={noteId}
      onResult={onResult}
      inPalette
    />
  );
}
export function PluginCommands({
  query = "",
  where = "palette",
  noteId,
  pluginId,
  onChoose,
  onResult,
  inPalette,
}: {
  query?: string;
  where?: string | string[];
  noteId?: string;
  pluginId?: string;
  onChoose?: (choice: CommandChoice) => void;
  onResult?: (value: unknown, origin: CommandOrigin) => void;
  /** Rows look and line up like the palette's own rows. */
  inPalette?: boolean;
}) {
  useLanguage();
  const manifest = usePlugins();
  const places = Array.isArray(where) ? where : [where];
  const slash = places.includes("slash");
  const [active, setActive] = useState(0);
  const [result, setResult] = useState<unknown>(null);
  const [origin, setOrigin] = useState<CommandOrigin>();
  const currentQuery = useRef(query);
  currentQuery.current = query;
  const commandLifetime = useRef({
    active: true,
    generation: requestGeneration(),
  });
  const pendingAskHandle = useRef<Args | undefined>(undefined);
  const currentPlugins = useRef(manifest.data?.plugins);
  currentPlugins.current = manifest.data?.plugins;
  useEffect(() => {
    commandLifetime.current.active = true;
    return () => {
      commandLifetime.current.active = false;
      if (
        pendingAskHandle.current &&
        commandLifetime.current.generation === requestGeneration()
      )
        void api
          .command("ask", "clear-conversation", {
            conversation_id: pendingAskHandle.current.conversation_id,
          })
          .catch(() => {});
    };
  }, []);
  useEffect(
    () => () => {
      if (
        pendingAskHandle.current &&
        commandLifetime.current.generation === requestGeneration()
      )
        void api
          .command("ask", "clear-conversation", {
            conversation_id: pendingAskHandle.current.conversation_id,
          })
          .catch(() => {});
      pendingAskHandle.current = undefined;
    },
    [query],
  );
  useEffect(() => {
    if (
      pendingAskHandle.current &&
      !activePlugins(manifest.data?.plugins).some(
        (plugin) => plugin.id === "ask",
      )
    ) {
      if (commandLifetime.current.generation === requestGeneration())
        void api
          .command("ask", "clear-conversation", {
            conversation_id: pendingAskHandle.current.conversation_id,
          })
          .catch(() => {});
      pendingAskHandle.current = undefined;
    }
  }, [manifest.data]);
  const command = useMutation({
    mutationFn: async ({ plugin, id, args }: CommandChoice) => {
      if (
        plugin === "ask" &&
        id === "ask" &&
        typeof args?.question === "string"
      ) {
        const started = pendingAskHandle.current
          ? undefined
          : await api.command("ask", "ask", {
              start: true,
              ...askNoteActionOptions(currentPlugins.current),
            });
        const handle = pendingAskHandle.current ?? askContinuation(started);
        if (!handle) throw new Error(outputText(started) || t("ask.failed"));
        pendingAskHandle.current = handle;
        if (
          !commandLifetime.current.active ||
          commandLifetime.current.generation !== requestGeneration() ||
          args.question !== currentQuery.current.trim() ||
          !activePlugins(currentPlugins.current).some((p) => p.id === "ask")
        ) {
          if (commandLifetime.current.generation === requestGeneration())
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
    onSuccess: (value, choice) => {
      if (
        choice.plugin === "ask" &&
        (!commandLifetime.current.active ||
          commandLifetime.current.generation !== requestGeneration() ||
          !activePlugins(currentPlugins.current).some(
            (plugin) => plugin.id === "ask",
          ) ||
          (typeof choice.args?.question === "string" &&
            choice.args.question !== currentQuery.current.trim()))
      ) {
        const continuation = askContinuation(value);
        if (
          continuation &&
          commandLifetime.current.generation === requestGeneration()
        )
          void api
            .command("ask", "clear-conversation", {
              conversation_id: continuation.conversation_id,
            })
            .catch(() => {});
        return;
      }
      if (choice.plugin === "ask") pendingAskHandle.current = undefined;
      const nextOrigin = {
        plugin: choice.plugin,
        command: choice.id,
        args: choice.args,
      };
      if (onResult) onResult(value, nextOrigin);
      else {
        setResult(value);
        setOrigin(nextOrigin);
      }
    },
  });
  const question = query.trim();
  const askQuestion =
    places.includes("palette") &&
    question.split(/\s+/).length >= 3 &&
    [...question].length <= 500;
  const commands = (
    navigator.onLine
      ? activePlugins(manifest.data?.plugins).filter(
          (p) => !pluginId || p.id === pluginId,
        )
      : []
  )
    .flatMap((p) =>
      (p.contributes?.commands ?? [])
        .slice(0, 3)
        .map((c) => ({
          command: c,
          at: (c as unknown as { where?: string[] }).where ?? c.surfaces ?? [],
        }))
        .filter(
          ({ command, at }) =>
            places.some((place) => at.includes(place)) &&
            (!("online" in command) ||
              !(command as unknown as { online?: boolean }).online ||
              navigator.onLine),
        )
        .map(({ command, at }): CommandChoice & { name: string } => ({
          plugin: p.id,
          id: command.id,
          title: [...command.title].slice(0, 24).join(""),
          name: p.name,
          args:
            p.id === "ask" &&
            command.id === "ask" &&
            askQuestion &&
            question.toLowerCase() !== command.title.toLowerCase()
              ? { question }
              : noteId && at.includes("note-actions")
                ? { note_id: noteId }
                : undefined,
        })),
    )
    .filter(
      (c) =>
        (c.plugin === "ask" && c.id === "ask" && askQuestion) ||
        c.title.toLowerCase().includes(query.toLowerCase().replace(/^\//, "")),
    );
  const highlighted = Math.min(active, commands.length - 1);
  const choose = (c: CommandChoice) =>
    onChoose ? onChoose(c) : command.mutate(c);
  useEffect(() => setActive(0), [query]);
  // The slash menu is driven from the editor: arrows move, Enter runs the highlighted command.
  useEffect(() => {
    if (!slash || !commands.length) return;
    const key = (e: KeyboardEvent) => {
      if (
        !(e.target instanceof HTMLTextAreaElement) ||
        e.isComposing ||
        e.metaKey ||
        e.ctrlKey ||
        e.altKey ||
        e.shiftKey ||
        !["ArrowDown", "ArrowUp", "Enter"].includes(e.key)
      )
        return;
      e.preventDefault();
      e.stopPropagation();
      if (e.key === "Enter") choose(commands[highlighted]);
      else
        setActive(
          (highlighted + (e.key === "ArrowDown" ? 1 : commands.length - 1)) %
            commands.length,
        );
    };
    document.addEventListener("keydown", key, true);
    return () => document.removeEventListener("keydown", key, true);
  });
  if (result)
    return (
      <CommandResult
        value={result}
        origin={origin}
        onDismiss={() => setResult(null)}
      />
    );
  if (!commands.length) return null;
  return (
    <div
      className={slash ? styles.slash : inPalette ? "palette-group" : undefined}
      role={slash ? "listbox" : undefined}
      aria-label={slash ? t("slots.commands") : undefined}
    >
      {commands.map((c, index) => (
        <button
          className={inPalette ? "palette-row" : styles.command}
          key={`${c.plugin}/${c.id}`}
          data-ask-question={
            (c.plugin === "ask" &&
              c.id === "ask" &&
              typeof c.args?.question === "string") ||
            undefined
          }
          role={slash ? "option" : undefined}
          aria-selected={slash ? index === highlighted : undefined}
          disabled={command.isPending}
          onMouseDown={slash ? (e) => e.preventDefault() : undefined}
          onClick={() => choose(c)}
        >
          <span>{c.title}</span>
          <small className="muted">{c.name}</small>
        </button>
      ))}
      {command.isPending && command.variables?.plugin === "ask" && (
        <p role="status">{t("timeline.loading_notes")}</p>
      )}
      {command.isError && (
        <p role="alert" className="error">
          {command.error.message}
        </p>
      )}
    </div>
  );
}
