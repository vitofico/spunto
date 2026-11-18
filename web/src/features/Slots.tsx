import {
  invalidateContentFor,
  useManifest,
  useBrowserOnline,
  useWorkspaceRole,
} from "../lib/query";
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
import { Link, useLocation } from "wouter";
import { api } from "../lib/api";
import { localizeCatalog } from "../lib/localizePlugins";
import { safeError } from "../lib/errors";
import { NoteBody } from "../components/core/NoteCard";
import { useToast } from "../components/core/Toast";
import { createIdempotencyKey } from "../lib/drafts";
import { copyText } from "./copy";
import type {
  Args,
  Component,
  Annotation,
  Note,
  Plugin,
  Schema,
  Suggestion,
} from "../lib/types";
import { SuggestionPreview } from "./LazyPluginViews";
import { VocabularyOutput } from "./Vocabulary";
import { outputText, validComponent } from "./vocabularyData";
import { Dialog } from "./Dialog";
import {
  appliedSuggestionTitle,
  requiresSuggestionPreview,
  suggestionTitle,
} from "./suggestionTitle";
import {
  useCommandProposals,
  useSuggestionFeedback,
} from "./suggestionFeedback";
import { labelChangeImpact } from "./labelChange";
import { plural } from "./format";
import styles from "./Feature.module.css";
import { requestGeneration } from "../lib/identity";
import {
  decisionsPath,
  isDecisionNote,
  decisionForm,
  decisionStatus,
  decisionCommandLabel,
} from "./decisionJournalData";
import {
  askContinuation,
  askItems,
  askNoteActionOptions,
} from "./askConversationData";
const LinkPicker = lazy(() =>
  import("./LinkPicker").then((m) => ({ default: m.LinkPicker })),
);
const CommandProposal = lazy(() => import("./CommandProposal"));
const NotePicker = lazy(() =>
  import("./NotePicker").then((m) => ({ default: m.NotePicker })),
);
const AskConversation = lazy(() => import("./AskConversation"));
export function usePlugins() {
  return useManifest();
}
export function supportsNotePicker(plugin: Plugin) {
  const [major, minor] = plugin.version.split(".").map(Number);
  return major > 1 || (major === 1 && minor >= 1);
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
  const ids = new Set(
    activePlugins(plugins)
      .filter((p) => p.id !== "links" || supportsNotePicker(p))
      .map((p) => p.id),
  );
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
    query.data?.suggestions.filter(
      (s) => s.status === "pending" && active.has(s.plugin),
    ).length ?? 0;
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
  const [order, setOrder] = useState<string[]>([]);
  const { data: hidden } = useQuery<string[]>({
    queryKey: ["command-proposals", note.id],
    queryFn: () => [],
    enabled: false,
  });
  const { acknowledge, undo } = useSuggestionFeedback();
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
      s,
      action,
    }: {
      s: Suggestion;
      action: "accept" | "dismiss";
    }) => api.decideSuggestion(s.id, action),
    onSuccess: (result, { s, action }) => {
      setPreview(null);
      void invalidateContentFor(client);
      if (action === "accept") {
        if (result?.suggestion?.receipt?.undoable)
          setOrder(items.map((item) => item.id));
        acknowledge(result, s);
      }
    },
  });
  useEffect(() => {
    setExpanded(false);
    setOrder([]);
    setPreview(null);
  }, [note.id]);
  const suggestions = visibleSuggestions(
    note,
    manifest.data?.plugins ?? [],
  ).filter((s) => !hidden?.includes(s.id));
  const pending = new Set(suggestions.map((s) => s.id));
  const items = (note.suggestions ?? []).filter(
    (s) => pending.has(s.id) || s.receipt?.undoable,
  );
  if (items.some((s) => s.receipt?.undoable) && order.length) {
    const positions = new Map(order.map((id, index) => [id, index]));
    items.sort(
      (a, b) =>
        (positions.get(a.id) ?? order.length) -
        (positions.get(b.id) ?? order.length),
    );
  }
  if (editing || !items.length) return null;
  const apply = (s: Suggestion, trigger: HTMLElement) => {
    opener.current = trigger;
    if (requiresSuggestionPreview(s)) setPreview(s);
    else decision.mutate({ s, action: "accept" });
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
        if (
          e.key === "x" &&
          e.target instanceof HTMLElement &&
          !e.target.closest("dialog")
        ) {
          e.preventDefault();
          for (const s of suggestions)
            decision.mutate({ s, action: "dismiss" });
        }
        if (
          e.key === "a" &&
          e.target instanceof HTMLElement &&
          !e.target.closest("dialog")
        ) {
          e.preventDefault();
          const review = suggestions.find(requiresSuggestionPreview);
          if (review) {
            opener.current = null;
            onReview ? onReview() : setPreview(review);
          } else
            for (const s of suggestions)
              decision.mutate({ s, action: "accept" });
        }
      }}
    >
      <div
        className={
          expanded ? styles.expandedSuggestions : styles.suggestionItems
        }
      >
        {(expanded ? items : items.slice(0, 3)).map((s) => {
          if (s.receipt?.undoable)
            return (
              <span className={styles.suggestionGroup} key={s.id}>
                <span
                  className={styles.receipt}
                  title={appliedSuggestionTitle(s)}
                >
                  {appliedSuggestionTitle(s)}
                </span>
                <button
                  className={styles.receiptUndo}
                  disabled={undo.isPending}
                  onClick={() => undo.mutate(s)}
                >
                  {t("settingsplugins.undo")}
                </button>
                <button
                  className={styles.dismiss}
                  disabled={decision.isPending}
                  aria-label={t("slots.dismiss_value1", {
                    value1: appliedSuggestionTitle(s),
                  })}
                  onClick={() => decision.mutate({ s, action: "dismiss" })}
                >
                  ×
                </button>
              </span>
            );
          const by = manifest.data?.plugins.find(
            (p) => p.id === s.plugin,
          )?.name;
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
                          timer: window.setTimeout(
                            () => reveal(provenance),
                            500,
                          ),
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
                  value1:
                    s.action === "add_label"
                      ? `#${String(s.payload.path)}`
                      : suggestionTitle(s),
                })}
                disabled={decision.isPending}
                onClick={() => decision.mutate({ s, action: "dismiss" })}
              >
                ×
              </button>
            </span>
          );
        })}
      </div>
      {!expanded && items.length > 3 && (
        <button className={styles.overflow} onClick={() => setExpanded(true)}>
          +{items.length - 3}
        </button>
      )}
      {suggestions.length > 1 && (
        <button
          className={styles.dismiss}
          aria-label={t("slots.dismiss_value1", {
            value1: plural(suggestions.length, "suggestion"),
          })}
          disabled={decision.isPending}
          onClick={() => {
            for (const s of suggestions)
              decision.mutate({ s, action: "dismiss" });
          }}
        >
          {t("review.dismiss")}
        </button>
      )}
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
                  decision.mutate({ s: preview, action: "accept" })
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
    onSuccess: (result, { id, args }) =>
      window.dispatchEvent(
        new CustomEvent("spunto:command-result", {
          detail: {
            value: result,
            origin: { plugin: plugin.id, command: id, args },
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
  // Backlinks live in the note's own Linked notes block, next to the links it makes.
  const plugins = activePlugins(manifest.data?.plugins).filter(
    (p) =>
      p.id !== "links" &&
      p.id !== "decision-journal" &&
      (p.contributes?.panel ||
        note.annotations?.some((a) => a.plugin === p.id && a.kind === "panel")),
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
export function PluginRailViews({ close }: { close?: () => void }) {
  useLanguage();
  const manifest = usePlugins();
  const settings = useQuery({ queryKey: ["settings"], queryFn: api.settings });
  const [location] = useLocation();
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
  const review = activePlugins(manifest.data?.plugins).some((plugin) =>
    ["duplicates", "label-gardener"].includes(plugin.id),
  );
  if (!views.length && !review) return null;
  const href = (v: (typeof views)[number]) =>
    `/v/${encodeURIComponent(v.plugin)}/${encodeURIComponent(v.id)}`;
  return (
    <nav className={styles.railGroup} aria-label={t("slots.views")}>
      <h3>{t("slots.views")}</h3>
      {review && (
        <Link
          href="/review"
          onClick={close}
          aria-current={location === "/review" ? "page" : undefined}
        >
          {t("review.review")}
        </Link>
      )}
      {views
        .filter((v) => !settings.data?.hidden_views.includes(v.key))
        .map((v) => (
          <Link
            key={v.key}
            href={href(v)}
            onClick={close}
            aria-current={location === href(v) ? "page" : undefined}
          >
            {v.title}
          </Link>
        ))}
      {views.some((v) => settings.data?.hidden_views.includes(v.key)) && (
        <>
          <button onClick={() => setMore(!more)} aria-expanded={more}>
            {t(more ? "plugins.show_fewer_views" : "plugins.show_more_views")}
          </button>
          {more &&
            views
              .filter((v) => settings.data?.hidden_views.includes(v.key))
              .map((v) => (
                <div key={v.key} className={styles.row}>
                  <Link
                    href={href(v)}
                    onClick={close}
                    aria-current={location === href(v) ? "page" : undefined}
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
    </nav>
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
export function CommandResult(props: {
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
        formChoices={
          decisionCommand === "review-decision"
            ? (key, value) =>
                key === "status" &&
                typeof value === "string" &&
                ["open", "reviewed", "closed"].includes(value)
                  ? t(`decisions.status.${value}`)
                  : undefined
            : undefined
        }
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
  note,
  onResult,
}: {
  query?: string;
  noteId?: string;
  note?: Note;
  onResult?: (value: unknown, origin: CommandOrigin) => void;
}) {
  useLanguage();
  // One row per command: a note action that is also a palette command runs on the open note.
  return (
    <PluginCommands
      query={query}
      where={noteId ? ["note-actions", "palette"] : ["palette"]}
      noteId={noteId}
      note={note}
      onResult={onResult}
      inPalette
    />
  );
}
/** Source actions use the same scope in the palette and the note's tools list. */
export function canRunOnNote(plugin: Plugin, command: string, note?: Note) {
  if (plugin.id === "decision-journal" && command === "review-decision")
    return !!note && !note.trashed && isDecisionNote(note);
  if (!note) return true;
  if (note.trashed) return false;
  if (plugin.id === "agent-inbox" && command === "mark-reviewed")
    return (
      (note.origin === "agent" || note.updated_via_agent) &&
      !note.annotations?.some(
        (item) => item.plugin === plugin.id && item.kind === "member:reviewed",
      )
    );
  if (
    (note.private || note.machine) &&
    plugin.permissions.some(
      (permission) =>
        permission.startsWith("ai.") || permission.startsWith("net."),
    )
  )
    return false;
  return true;
}

export function useWaitingCommandPlugins(enabled: boolean) {
  useLanguage();
  const online = useBrowserOnline();
  const role = useWorkspaceRole();
  return useQuery({
    queryKey: ["catalog"],
    queryFn: api.catalog,
    enabled: enabled && online && role === "owner",
    select: (catalog) =>
      localizeCatalog(catalog).plugins.filter(
        (plugin) =>
          plugin.state === "waiting" &&
          plugin.permissions.some(
            (permission) =>
              permission.startsWith("ai.") &&
              !Object.hasOwn(plugin.ai_destinations ?? {}, permission.slice(3)),
          ),
      ),
  });
}

export function PluginCommands({
  query = "",
  where = "palette",
  noteId,
  note,
  skipCommands = [],
  pluginId,
  onChoose,
  onResult,
  inPalette,
}: {
  query?: string;
  where?: string | string[];
  noteId?: string;
  note?: Note;
  skipCommands?: string[];
  pluginId?: string;
  onChoose?: (choice: CommandChoice) => void;
  onResult?: (value: unknown, origin: CommandOrigin) => void;
  /** Rows look and line up like the palette's own rows. */
  inPalette?: boolean;
}) {
  useLanguage();
  const manifest = usePlugins();
  const online = useBrowserOnline();
  const workspaceRole = useWorkspaceRole();
  const [, navigate] = useLocation();
  const client = useQueryClient();
  const places = Array.isArray(where) ? where : [where];
  const slash = places.includes("slash");
  const waitingPlugins = useWaitingCommandPlugins(!slash);
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
              ...askNoteActionOptions(currentPlugins.current, workspaceRole),
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
      if (choice.args?.note_id && choice.plugin !== "decision-journal")
        void invalidateContentFor(client);
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
    online && workspaceRole === "owner"
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
            !skipCommands.includes(`${p.id}/${command.id}`) &&
            (!(noteId && at.includes("note-actions")) ||
              canRunOnNote(p, command.id, note)) &&
            (p.id !== "links" ||
              command.id !== "link" ||
              supportsNotePicker(p)) &&
            (!("online" in command) ||
              !(command as unknown as { online?: boolean }).online ||
              online),
        )
        .map(({ command, at }): CommandChoice & { name: string } => ({
          plugin: p.id,
          id: command.id,
          title: command.title,
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
  const waiting =
    slash || !online || workspaceRole !== "owner"
      ? []
      : (waitingPlugins.data ?? []).flatMap((plugin) =>
          !pluginId || pluginId === plugin.id
            ? (plugin.contributes?.commands ?? [])
                .slice(0, 3)
                .filter(
                  (command) =>
                    places.some((place) =>
                      (command.where ?? command.surfaces ?? []).includes(place),
                    ) &&
                    (noteId ||
                      !(command.where ?? command.surfaces ?? []).includes(
                        "note-actions",
                      )) &&
                    !skipCommands.includes(`${plugin.id}/${command.id}`) &&
                    (!(
                      noteId &&
                      (command.where ?? command.surfaces ?? []).includes(
                        "note-actions",
                      )
                    ) ||
                      canRunOnNote(plugin, command.id, note)) &&
                    command.title.toLowerCase().includes(query.toLowerCase()),
                )
                .map((command) => ({ plugin, command }))
            : [],
        );
  const highlighted = Math.min(active, commands.length - 1);
  const choose = (c: CommandChoice) => {
    if (
      !slash &&
      inPalette &&
      c.plugin === "decision-journal" &&
      c.id === "journal"
    )
      navigate(decisionsPath);
    else if (onChoose) onChoose(c);
    else if (
      c.plugin === "links" &&
      c.id === "link" &&
      typeof c.args?.note_id === "string"
    ) {
      const nextOrigin = { plugin: c.plugin, command: c.id, args: c.args };
      if (onResult) onResult(true, nextOrigin);
      else {
        setResult(true);
        setOrigin(nextOrigin);
      }
    } else command.mutate(c);
  };
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
  if (!commands.length && !waiting.length) return null;
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
      {waiting.map(({ plugin, command }) => (
        <p className={styles.facts} key={`${plugin.id}/${command.id}`}>
          {t("quiet.command_waiting", { command: command.title })}{" "}
          <Link href="/settings/ai">{t("settingsplugins.ai_settings")}</Link>
        </p>
      ))}
      {command.isPending && <p role="status">{t("timeline.loading_notes")}</p>}
      {command.isError && (
        <p role="alert" className="error">
          {command.error.message}
        </p>
      )}
    </div>
  );
}
