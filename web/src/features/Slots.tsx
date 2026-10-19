import { useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { createIdempotencyKey } from "../lib/drafts";
import { copyText } from "./copy";
import type { Args, Annotation, Note, Plugin, Suggestion } from "../lib/types";
import { VocabularyOutput, outputText, validComponent } from "./Vocabulary";
import { Dialog } from "./Dialog";
import { SuggestionPreview, suggestionTitle } from "./SuggestionPreview";
import { labelChangeImpact } from "./labelChange";
import styles from "./Feature.module.css";
export function usePlugins() {
  return useQuery({ queryKey: ["manifest"], queryFn: api.manifest });
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
  const manifest = usePlugins();
  const query = useQuery({
    queryKey: ["suggestions", "review"],
    queryFn: () => api.suggestions("review"),
    enabled: !!manifest.data?.plugins.length,
  });
  const count =
    query.data?.suggestions.filter((s) =>
      manifest.data?.plugins.some((p) => p.id === s.plugin),
    ).length ?? 0;
  return count >= 3 ? (
    <p className={styles.reviewLine}>
      {count} suggestions · <a href="/review">Review</a>
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
  const manifest = usePlugins();
  const client = useQueryClient();
  const [expanded, setExpanded] = useState(false);
  const [preview, setPreview] = useState<Suggestion | null>(null);
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
  const apply = (s: Suggestion) => {
    if (["replace_body", "merge_notes", "label_change"].includes(s.action))
      setPreview(s);
    else decision.mutate({ id: s.id, action: "accept" });
  };
  return (
    <div
      className={styles.suggestions}
      aria-label="Suggestions"
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
          )
            onReview ? onReview() : setPreview(suggestions[0]);
          else
            for (const s of suggestions)
              decision.mutate({ id: s.id, action: "accept" });
        }
      }}
    >
      {(expanded ? suggestions : suggestions.slice(0, 3)).map((s) => (
        <span className={styles.suggestionGroup} key={s.id}>
          <button
            className={styles.pill}
            disabled={decision.isPending}
            title={`Suggested by ${manifest.data?.plugins.find((p) => p.id === s.plugin)?.name ?? s.plugin}: ${s.reason}`}
            onClick={() => apply(s)}
          >
            {suggestionTitle(s)}
            {s.payload.is_new === true && <small>new</small>}
          </button>
          <button
            className={styles.dismiss}
            aria-label={`Dismiss ${suggestionTitle(s)}`}
            disabled={decision.isPending}
            onClick={() => decision.mutate({ id: s.id, action: "dismiss" })}
          >
            ×
          </button>
        </span>
      ))}
      {!expanded && suggestions.length > 3 && (
        <button className={styles.overflow} onClick={() => setExpanded(true)}>
          +{suggestions.length - 3}
        </button>
      )}
      {decision.isError && (
        <p className={styles.error} role="alert">
          {decision.error.message}
        </p>
      )}
      {preview && (
        <Dialog
          title={suggestionTitle(preview)}
          onClose={() => setPreview(null)}
          footer={
            <>
              <button onClick={() => setPreview(null)}>Cancel</button>
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
                Apply
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
        {panel?.title ?? plugin.name} · {count} {count === 1 ? "item" : "items"}
      </summary>
      <VocabularyOutput
        value={output}
        allow={["text", "markdown", "notes", "command"]}
        onCommand={(id, args) => command.mutate({ id, args })}
      />
      {command.isError && (
        <p role="alert" className={styles.error}>
          {command.error.message}
        </p>
      )}
    </details>
  );
}
export function NotePanel({ note }: { note: Note }) {
  const manifest = usePlugins();
  const plugins = activePlugins(manifest.data?.plugins).filter(
    (p) =>
      p.contributes?.panel ||
      note.annotations?.some((a) => a.plugin === p.id && a.kind === "panel"),
  );
  if (!plugins.length) return null;
  return (
    <aside className={styles.panel} aria-label="About this note">
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
  return (
    <>
      <SuggestionLine note={note} onReview={onReview} editing={editing} />
      <NotePanel note={note} />
    </>
  );
}
export function DailySlip() {
  const manifest = usePlugins();
  const client = useQueryClient();
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
  const archive = useMutation({
    mutationFn: (id: string) => api.flags(id, { archived: true }),
    onSuccess: () => void client.invalidateQueries({ queryKey: ["notes"] }),
  });
  const slip = query.data?.slip;
  if (!slip || !manifest.data?.plugins.some((p) => p.id === slip.plugin_id))
    return null;
  return (
    <section className={styles.slip}>
      <p className={styles.overline}>
        From your notes · {new Date(slip.note.created_at).toLocaleDateString()}
      </p>
      <blockquote>{slip.note.body.slice(0, 400)}</blockquote>
      <div className={styles.row}>
        <a href={`/n/${encodeURIComponent(slip.note_id)}`}>Open</a>
        <button
          disabled={archive.isPending}
          onClick={() => archive.mutate(slip.note_id)}
        >
          Archive
        </button>
        <button
          aria-label="Not today"
          disabled={dismiss.isPending}
          onClick={() => dismiss.mutate(slip)}
        >
          ×
        </button>
      </div>
      {(dismiss.isError || archive.isError) && (
        <p role="alert" className={styles.error}>
          The change could not be saved. Try again.
        </p>
      )}
    </section>
  );
}
export const PluginDailySlip = DailySlip;
export function PluginRailViews() {
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
      <h3>Views</h3>
      {views
        .filter((v) => !settings.data?.hidden_views.includes(v.key))
        .map((v) => (
          <a
            key={v.key}
            href={`/v/${encodeURIComponent(v.plugin)}/${encodeURIComponent(v.id)}`}
          >
            {v.title}
          </a>
        ))}
      {views.some((v) => settings.data?.hidden_views.includes(v.key)) && (
        <>
          <button onClick={() => setMore(!more)} aria-expanded={more}>
            Show {more ? "fewer" : "more"} views
          </button>
          {more &&
            views
              .filter((v) => settings.data?.hidden_views.includes(v.key))
              .map((v) => (
                <div key={v.key} className={styles.row}>
                  <a
                    href={`/v/${encodeURIComponent(v.plugin)}/${encodeURIComponent(v.id)}`}
                  >
                    {v.title}
                  </a>
                  <button
                    onClick={() => pin.mutate(v.key)}
                    disabled={pin.isPending}
                  >
                    Pin
                  </button>
                </div>
              ))}
        </>
      )}
    </section>
  );
}
export type CommandOrigin = { plugin: string; command: string; args?: Args };
function saveCommand(value: unknown): { id: string; args: Args } | undefined {
  if (Array.isArray(value)) {
    for (const item of value) {
      const command = saveCommand(item);
      if (command) return command;
    }
  } else if (value && typeof value === "object") {
    if (
      validComponent(value) &&
      value.type === "command" &&
      value.id === "save-answer"
    )
      return { id: String(value.id), args: (value.args ?? {}) as Args };
    if (!("type" in value)) {
      const group = value as { items?: unknown; components?: unknown };
      return saveCommand(group.items ?? group.components);
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
  const [copied, setCopied] = useState(false);
  const [saved, setSaved] = useState(false);
  const client = useQueryClient();
  const [display, setDisplay] = useState(value);
  const [currentOrigin, setCurrentOrigin] = useState(origin);
  const [responseId, setResponseId] = useState(0);
  const session = useMemo(() => ({}), [value, origin]);
  const activeSession = useRef(session);
  const appliedSession = useRef(session);
  activeSession.current = session;
  const text = outputText(display);
  const providedSave = saveCommand(display);
  const saveKey = useMemo(createIdempotencyKey, [display]);
  const genericSave = !["ask", "context-packs", "decision-journal"].includes(
    currentOrigin?.plugin ?? "",
  );
  useEffect(() => {
    if (appliedSession.current === session) return;
    appliedSession.current = session;
    setDisplay(value);
    setCurrentOrigin(origin);
    setResponseId((prior) => prior + 1);
    setCopied(false);
    setSaved(false);
  }, [value, origin, session]);
  const followup = useMutation({
    mutationFn: ({
      plugin,
      id,
      args,
    }: Pick<CommandChoice, "plugin" | "id" | "args"> & { session: object }) =>
      api.command(plugin, id, args ?? {}),
    onSuccess: (next, choice) => {
      if (choice.session !== activeSession.current) return;
      setDisplay(next);
      setCurrentOrigin({
        plugin: choice.plugin,
        command: choice.id,
        args: choice.args,
      });
      setResponseId((prior) => prior + 1);
      setCopied(false);
      setSaved(false);
      void client.invalidateQueries({ queryKey: ["notes"] });
    },
  });
  const save = useMutation({
    mutationFn: () => api.createNote(text, saveKey),
    onSuccess: () => {
      setSaved(true);
      void client.invalidateQueries({ queryKey: ["notes"] });
    },
  });
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape") onDismiss?.();
      if ((e.metaKey || e.ctrlKey) && e.key === "s") {
        e.preventDefault();
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
  ]);
  return (
    <div className={styles.result}>
      <VocabularyOutput
        key={responseId}
        value={display}
        formLabel={currentOrigin?.plugin === "ask" ? "Ask" : "Run"}
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
      <footer>
        <button
          disabled={!text}
          onClick={() => void copyText(text).then(setCopied)}
        >
          {copied ? "Copied" : "Copy"}
        </button>
        {genericSave && (
          <button
            className="primary"
            disabled={!text || save.isPending || saved}
            onClick={() => save.mutate()}
          >
            {saved
              ? "Saved as note"
              : save.isPending
                ? "Saving…"
                : "Save as note"}
          </button>
        )}
        {onDismiss && <button onClick={onDismiss}>Back</button>}
      </footer>
      {save.isError && (
        <p role="alert" className={styles.error}>
          {save.error.message}
        </p>
      )}
      {followup.error && (
        <p role="alert" className={styles.error}>
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
  return (
    <>
      {noteId && (
        <PluginCommands
          query={query}
          where="note-actions"
          noteId={noteId}
          onResult={onResult}
        />
      )}
      <PluginCommands query={query} where="palette" onResult={onResult} />
    </>
  );
}
export function PluginCommands({
  query = "",
  where = "palette",
  noteId,
  pluginId,
  onChoose,
  onResult,
}: {
  query?: string;
  where?: string;
  noteId?: string;
  pluginId?: string;
  onChoose?: (choice: CommandChoice) => void;
  onResult?: (value: unknown, origin: CommandOrigin) => void;
}) {
  const manifest = usePlugins();
  const [result, setResult] = useState<unknown>(null);
  const [origin, setOrigin] = useState<CommandOrigin>();
  const command = useMutation({
    mutationFn: ({ plugin, id, args }: CommandChoice) =>
      api.command(plugin, id, args ?? {}),
    onSuccess: (value, choice) => {
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
        .filter(
          (c) =>
            (
              (c as unknown as { where?: string[] }).where ??
              c.surfaces ??
              []
            ).includes(where) &&
            (!("online" in c) ||
              !(c as unknown as { online?: boolean }).online ||
              navigator.onLine),
        )
        .map((c) => ({
          plugin: p.id,
          id: c.id,
          title: [...c.title].slice(0, 24).join(""),
          name: p.name,
          args: noteId ? { note_id: noteId } : undefined,
        })),
    )
    .filter((c) =>
      c.title.toLowerCase().includes(query.toLowerCase().replace(/^\//, "")),
    );
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
    <div>
      {commands.map((c) => (
        <button
          className={styles.command}
          key={`${c.plugin}/${c.id}`}
          disabled={command.isPending}
          onClick={() => (onChoose ? onChoose(c) : command.mutate(c))}
        >
          <span>{c.title}</span>
          <small>{c.name}</small>
        </button>
      ))}
      {command.isError && (
        <p role="alert" className={styles.error}>
          {command.error.message}
        </p>
      )}
    </div>
  );
}
