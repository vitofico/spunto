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
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useLocation } from "wouter";
import { api } from "../lib/api";
import { isBundledPlugin, localizeCatalog } from "../lib/localizePlugins";
import { hasNoteToolInput } from "../lib/noteToolInputs";
import { safeError } from "../lib/errors";
import { NoteBody } from "../components/core/NoteCard";
import { useToast } from "../components/core/Toast";
import { Icon, type IconName } from "../components/core/Icon";
import type {
  Args,
  Command,
  Note,
  NoteDetail,
  Plugin,
  Suggestion,
} from "../lib/types";
import { SuggestionPreview } from "./LazyPluginViews";
import { outputText, commandSuccessMessage } from "./vocabularyData";
import { Dialog } from "./Dialog";
import {
  appliedSuggestionTitle,
  requiresSuggestionPreview,
  suggestionTitle,
  suggestionReason,
} from "./suggestionTitle";
import { useSuggestionFeedback } from "./suggestionFeedback";
import { labelChangeImpact } from "./labelChange";
import { plural } from "./format";
import styles from "./Feature.module.css";
import { requestGeneration } from "../lib/identity";
import { decisionsPath, isDecisionNote } from "./decisionJournalData";
import { askContinuation, askNoteActionOptions } from "./askConversationData";
const CommandResult = lazy(() => import("./CommandResult"));
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
        if (result?.suggestion?.receipt?.undoable && s.action !== "add_label")
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
    (s) =>
      pending.has(s.id) || (s.receipt?.undoable && s.action !== "add_label"),
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
          const source = manifest.data?.plugins.find((p) => p.id === s.plugin);
          const reason = suggestionReason(s, source);
          const provenance = t("slots.suggested_by_value1_value2", {
            value1: source?.name ?? s.plugin,
            value2: reason ? `: ${reason}` : "",
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
export function EntryMeta({ note }: { note: Note }) {
  useLanguage();
  const manifest = usePlugins();
  for (const plugin of activePlugins(manifest.data?.plugins)) {
    const annotation = note.annotations?.find(
      (a) => a.plugin === plugin.id && a.kind === "meta",
    );
    if (annotation) {
      let text = outputText(annotation.payload);
      if (isBundledPlugin(plugin)) {
        const progress =
          plugin.id === "tasks" && /^(\d+) of (\d+) done$/.exec(text);
        const duration =
          plugin.id === "link-enrichment" && /^(\d+) min read$/.exec(text);
        if (progress)
          text = t("pluginui.task_progress", {
            done: Number(progress[1]),
            total: Number(progress[2]),
          });
        if (duration)
          text = t("pluginui.reading_time", { count: Number(duration[1]) });
      }
      text = [...text].slice(0, 24).join("");
      if (text) return <span className={styles.meta}>{text}</span>;
    }
  }
  return null;
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
export type CommandOrigin = { plugin: string; command: string; args?: Args };
export type CommandChoice = {
  plugin: string;
  id: string;
  title: string;
  args?: Args;
};
export type NoteToolChoice = CommandChoice & {
  name: string;
  icon?: IconName;
  bundled: boolean;
  trustedBundled: boolean;
};
export type NoteToolView = {
  commands: NoteToolChoice[];
  choose: (tool: NoteToolChoice) => void;
  list: ReactNode;
  result: ReactNode;
  busy: boolean;
  failed: boolean;
  reset: () => void;
};

export const noteToolKey = (tool: CommandChoice) => `${tool.plugin}/${tool.id}`;
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
  onNavigate,
}: {
  query?: string;
  noteId?: string;
  note?: NoteDetail;
  onResult?: (value: unknown, origin: CommandOrigin) => void;
  onNavigate?: () => void;
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
      onNavigate={onNavigate}
      inPalette
    />
  );
}
const noteToolInputs: Record<
  string,
  keyof NonNullable<NoteDetail["note_tool_inputs"]>
> = {
  "image-text/read": "image",
  "voice-memos/transcribe": "audio",
  "link-enrichment/fetch": "link",
  "title-summary/summarize": "title_summary",
  "label-suggestions/suggest-now": "labels",
  "tidy/tidy": "short_text",
  "translate/translate": "short_text",
  "develop/develop": "short_text",
};

const noteToolIcons: Record<string, IconName> = {
  "image-text/read": "image",
  "voice-memos/transcribe": "audio",
  "link-enrichment/fetch": "link",
  "links/link": "link",
  "title-summary/summarize": "notes",
  "label-suggestions/suggest-now": "hash",
  "tidy/tidy": "edit",
  "translate/translate": "translate",
  "develop/develop": "branch",
  "context-packs/packs": "archive",
};

function operatorPlugin(plugin: Plugin) {
  return (
    plugin.origin === "operator" ||
    plugin.source_kind === "folder" ||
    plugin.source_kind === "entrypoint"
  );
}

function commandSurfaces(plugin: Plugin, command: Command) {
  const surfaces = command.where ?? command.surfaces ?? [];
  if (plugin.id === "thinking-desk" && !operatorPlugin(plugin))
    return surfaces.filter((surface) => surface === "palette");
  if (
    plugin.id === "decision-journal" &&
    command.id === "record-decision" &&
    !operatorPlugin(plugin)
  )
    return surfaces.filter((surface) => surface !== "note-actions");
  return surfaces;
}

/** Source actions use the same scope in the palette and the note's tools list. */
export function canRunOnNote(
  plugin: Plugin,
  command: string,
  note?: NoteDetail,
) {
  if (!note || note.trashed) return false;
  const bundled = !operatorPlugin(plugin);
  if (
    bundled &&
    plugin.id === "decision-journal" &&
    command === "review-decision"
  )
    return isDecisionNote(note);
  if (bundled && plugin.id === "agent-inbox" && command === "mark-reviewed")
    return (
      (note.source?.kind === "agent" || note.updated_via_agent) &&
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
  if (bundled && plugin.id === "links" && command === "link")
    return supportsNotePicker(plugin);
  const input = bundled ? noteToolInputs[`${plugin.id}/${command}`] : undefined;
  return !input || hasNoteToolInput(note, input);
}

export function noteCommandAvailable(
  plugin: Plugin,
  command: Command,
  note: NoteDetail,
  versionedAgentReview?: boolean,
) {
  return (
    commandSurfaces(plugin, command).includes("note-actions") &&
    (operatorPlugin(plugin) ||
      (!(
        plugin.id === "decision-journal" && command.id === "review-decision"
      ) &&
        !(
          versionedAgentReview &&
          plugin.id === "agent-inbox" &&
          command.id === "mark-reviewed"
        ))) &&
    canRunOnNote(plugin, command.id, note)
  );
}

function useCommandCatalog(enabled: boolean) {
  useLanguage();
  const online = useBrowserOnline();
  const role = useWorkspaceRole();
  return useQuery({
    queryKey: ["catalog"],
    queryFn: api.catalog,
    enabled: enabled && online && role === "owner",
    select: (catalog) => ({
      bundled: catalog.plugins
        .filter((plugin) => isBundledPlugin(plugin))
        .map((plugin) => plugin.id),
      waiting: localizeCatalog(catalog).plugins.filter(
        (plugin) =>
          plugin.state === "waiting" &&
          plugin.permissions.some(
            (permission) =>
              permission.startsWith("ai.") &&
              !Object.hasOwn(plugin.ai_destinations ?? {}, permission.slice(3)),
          ),
      ),
    }),
  });
}

export function PluginCommands({
  query = "",
  where = "palette",
  noteId,
  note,
  pluginId,
  onChoose,
  onResult,
  onOpenNote,
  onNavigate,
  inPalette,
  noteToolsEnabled = true,
  renderNoteTools,
}: {
  query?: string;
  where?: string | string[];
  noteId?: string;
  note?: NoteDetail;
  pluginId?: string;
  onChoose?: (choice: CommandChoice) => void;
  onResult?: (value: unknown, origin: CommandOrigin) => void;
  onOpenNote?: () => void;
  onNavigate?: () => void;
  /** Rows look and line up like the palette's own rows. */
  inPalette?: boolean;
  noteToolsEnabled?: boolean;
  renderNoteTools?: (view: NoteToolView) => ReactNode;
}) {
  useLanguage();
  const manifest = usePlugins();
  const online = useBrowserOnline();
  const toast = useToast();
  const workspaceRole = useWorkspaceRole();
  const [, navigate] = useLocation();
  const client = useQueryClient();
  const places = Array.isArray(where) ? where : [where];
  const slash = places.includes("slash");
  const noteTools = where === "note-actions";
  const versionedReview = manifest.data?.features?.includes(
    "agent_review_versioned",
  );
  const enabledPlugins = activePlugins(manifest.data?.plugins);
  const matchesSurface = (plugin: Plugin, command: Command, at: string[]) => {
    if (!places.some((place) => at.includes(place))) return false;
    if (!at.includes("note-actions")) return true;
    if (noteTools)
      return (
        !!note && noteCommandAvailable(plugin, command, note, versionedReview)
      );
    if (noteId) return canRunOnNote(plugin, command.id, note);
    return at.some(
      (surface) => surface !== "note-actions" && places.includes(surface),
    );
  };
  const legacyNoteTools =
    noteTools &&
    note &&
    enabledPlugins.some(
      (plugin) =>
        plugin.origin == null &&
        plugin.source_kind == null &&
        plugin.contributes?.commands?.some((command) =>
          noteCommandAvailable(plugin, command, note, versionedReview),
        ),
    );
  const catalog = useCommandCatalog(
    !slash &&
      (!noteTools ||
        (noteToolsEnabled &&
          ((!note?.private && !note?.machine) || !!legacyNoteTools))),
  );
  const [active, setActive] = useState(0);
  const [result, setResult] = useState<unknown>(null);
  const [origin, setOrigin] = useState<CommandOrigin>();
  const list = useRef<HTMLDivElement>(null);
  const returnTo = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (result || !returnTo.current) return;
    const key = returnTo.current;
    returnTo.current = undefined;
    const rows =
      list.current?.querySelectorAll<HTMLButtonElement>("[data-command-key]");
    if (rows)
      Array.from(rows)
        .find((row) => row.dataset.commandKey === key)
        ?.focus();
  }, [result]);
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
      const plugin = currentPlugins.current?.find(
        (item) => item.id === choice.plugin,
      );
      const success =
        plugin && !operatorPlugin(plugin) && commandSuccessMessage(value);
      if (success) {
        if (
          !commandLifetime.current.active ||
          commandLifetime.current.generation !== requestGeneration()
        )
          return;
        if (noteTools) onOpenNote?.();
        toast.show({ message: success });
        return;
      }
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
    online && workspaceRole === "owner" && (!noteTools || noteToolsEnabled)
      ? enabledPlugins.filter((p) => !pluginId || p.id === pluginId)
      : []
  )
    .flatMap((p) =>
      (p.contributes?.commands ?? [])
        .slice(0, 3)
        .map((c) => ({
          command: c,
          at: commandSurfaces(p, c),
        }))
        .filter(
          ({ command, at }) =>
            matchesSurface(p, command, at) &&
            !(
              p.id === "agent-inbox" &&
              !operatorPlugin(p) &&
              command.id === "mark-reviewed" &&
              versionedReview
            ) &&
            (!("online" in command) ||
              !(command as unknown as { online?: boolean }).online ||
              online),
        )
        .map(({ command, at }): NoteToolChoice => ({
          plugin: p.id,
          id: command.id,
          title: command.title,
          name: p.name,
          bundled: !operatorPlugin(p),
          trustedBundled:
            noteTools &&
            (isBundledPlugin(p) ||
              (p.origin == null &&
                p.source_kind == null &&
                catalog.data?.bundled.includes(p.id) === true)),
          icon: noteTools
            ? (!operatorPlugin(p) && noteToolIcons[`${p.id}/${command.id}`]) ||
              "tools"
            : undefined,
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
    slash ||
    !online ||
    workspaceRole !== "owner" ||
    (noteTools && !noteToolsEnabled)
      ? []
      : (catalog.data?.waiting ?? []).flatMap((plugin) =>
          !pluginId || pluginId === plugin.id
            ? (plugin.contributes?.commands ?? [])
                .slice(0, 3)
                .filter((command) => {
                  const at = commandSurfaces(plugin, command);
                  return (
                    matchesSurface(plugin, command, at) &&
                    command.title.toLowerCase().includes(query.toLowerCase())
                  );
                })
                .map((command) => ({ plugin, command }))
            : [],
        );
  const highlighted = Math.min(active, commands.length - 1);
  const choose = (c: (typeof commands)[number]) => {
    if (c.plugin === "thinking-desk" && c.id === "desk" && c.bundled) {
      onNavigate?.();
      navigate("/thinking-desk");
    } else if (
      !slash &&
      inPalette &&
      c.bundled &&
      c.plugin === "decision-journal" &&
      c.id === "journal"
    )
      navigate(decisionsPath);
    else if (onChoose) onChoose(c);
    else if (
      c.bundled &&
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
  const openNote = () => {
    setResult(null);
    onOpenNote?.();
  };
  const resultContent = result ? (
    <Suspense fallback={<p role="status">{t("timeline.loading_notes")}</p>}>
      <CommandResult
        value={result}
        origin={origin}
        onOpenNote={onOpenNote ? openNote : undefined}
        onDismiss={() => {
          if (result === true && origin?.plugin === "links" && onOpenNote) {
            openNote();
            return;
          }
          if (origin) returnTo.current = `${origin.plugin}/${origin.command}`;
          setResult(null);
        }}
      />
    </Suspense>
  ) : null;
  const commandList =
    commands.length ||
    waiting.length ||
    command.isPending ||
    command.isError ? (
      <div
        ref={list}
        className={
          slash
            ? styles.slash
            : inPalette
              ? "palette-group"
              : noteTools
                ? styles.noteCommandGrid
                : undefined
        }
        role={slash ? "listbox" : undefined}
        aria-label={slash ? t("slots.commands") : undefined}
      >
        {commands.map((c, index) => (
          <button
            className={
              inPalette
                ? "palette-row"
                : noteTools
                  ? styles.noteCommand
                  : styles.command
            }
            key={noteToolKey(c)}
            data-command-key={noteToolKey(c)}
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
            {c.icon && (
              <span className={styles.noteCommandIcon}>
                <Icon name={c.icon} />
              </span>
            )}
            <span>{c.title}</span>
            {c.name !== c.title && (!noteTools || !c.bundled) && (
              <small className="muted">{c.name}</small>
            )}
          </button>
        ))}
        {waiting.map(({ plugin, command }) => (
          <p className={styles.facts} key={`${plugin.id}/${command.id}`}>
            {t("quiet.command_waiting", { command: command.title })}{" "}
            <Link href="/settings/ai">{t("settingsplugins.ai_settings")}</Link>
          </p>
        ))}
        {command.isPending && <p role="status">{t("plugins.running")}</p>}
        {command.isError && (
          <p role="alert" className="error">
            {command.error.message}
          </p>
        )}
      </div>
    ) : null;
  if (renderNoteTools)
    return renderNoteTools({
      commands,
      choose,
      list: commandList,
      result: resultContent,
      busy: command.isPending,
      failed: command.isError,
      reset: () => {
        setResult(null);
        if (!command.isPending) command.reset();
      },
    });
  return resultContent ?? commandList;
}
