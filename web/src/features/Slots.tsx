import { useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "wouter";
import { api } from "../lib/api";
import { useToast } from "../components/core/Toast";
import { createIdempotencyKey } from "../lib/drafts";
import { copyText } from "./copy";
import type { Args, Annotation, Note, Plugin, Suggestion } from "../lib/types";
import { VocabularyOutput, outputText, validComponent } from "./Vocabulary";
import { Dialog } from "./Dialog";
import { SuggestionPreview, suggestionTitle } from "./SuggestionPreview";
import { labelChangeImpact } from "./labelChange";
import { plural } from "./format";
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
      {plural(count, "suggestion")} · <Link href="/review">Review</Link>
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
      {(expanded ? suggestions : suggestions.slice(0, 3)).map((s) => {
        const by = manifest.data?.plugins.find((p) => p.id === s.plugin)?.name;
        return (
          <span className={styles.suggestionGroup} key={s.id}>
            <button
              className={styles.pill}
              disabled={decision.isPending}
              title={`Suggested by ${by}: ${s.reason}`}
              onClick={() => apply(s)}
            >
              {suggestionTitle(s)}
              {s.payload.is_new === true && <small>new</small>}
              <span className="sr-only">
                , suggested by {by}
                {s.reason ? `: ${s.reason}` : ""}
              </span>
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
        );
      })}
      {!expanded && suggestions.length > 3 && (
        <button className={styles.overflow} onClick={() => setExpanded(true)}>
          +{suggestions.length - 3}
        </button>
      )}
      <button
        className={styles.dismiss}
        aria-label={`Dismiss ${plural(suggestions.length, "suggestion")}`}
        disabled={decision.isPending}
        onClick={() => {
          for (const s of suggestions)
            decision.mutate({ id: s.id, action: "dismiss" });
        }}
      >
        Dismiss
      </button>
      {decision.isError && (
        <p className="error" role="alert">
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
        <p role="alert" className="error">
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
          message: "Archived",
          actionLabel: "Undo",
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
        From your notes ·{" "}
        {new Date(slip.note.created_at).toLocaleDateString(undefined, {
          dateStyle: "long",
        })}
      </p>
      <blockquote>{slip.note.body.slice(0, 400)}</blockquote>
      <div className={styles.row}>
        <Link href={`/n/${encodeURIComponent(slip.note_id)}`}>Open</Link>
        <button
          disabled={archive.isPending}
          onClick={() => archive.mutate({ id: slip.note_id, archived: true })}
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
      {dismiss.error && (
        <p role="alert" className="error">
          {dismiss.error.message}
        </p>
      )}
    </section>
  );
}
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
            Show {more ? "fewer" : "more"} views
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
/** A plugin's own save: a command it offers only inside results, titled "Save as note". */
function saveCommandIds(plugin: Plugin | undefined) {
  return new Set(
    (plugin?.contributes?.commands ?? [])
      .filter((c) => !c.where?.length && c.title === "Save as note")
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
  const saveKey = useMemo(createIdempotencyKey, [display]);
  // Plugins that create notes save their own results; an unknown origin gets no save.
  const genericSave = !!plugin && !plugin.creates;
  const formLabel =
    plugin?.contributes?.commands?.find((c) => c.id === currentOrigin?.command)
      ?.title ?? "Run";
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
      <footer>
        <button
          disabled={!text}
          onClick={() => void copyText(text).then(setCopied)}
        >
          {copied ? "Copied" : "Copy text"}
        </button>
        {genericSave && (
          <button
            className="primary"
            disabled={!text || save.isPending || !!saved}
            onClick={() => save.mutate()}
          >
            {saved
              ? "Saved as note"
              : save.isPending
                ? "Saving…"
                : "Save as note"}
          </button>
        )}
        {saved?.id && (
          <Link href={`/n/${encodeURIComponent(saved.id)}`}>Open note</Link>
        )}
        {onDismiss && <button onClick={onDismiss}>Back</button>}
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
  // One row per command: a note action that is also a palette command runs on the open note.
  return (
    <PluginCommands
      query={query}
      where={noteId ? ["note-actions", "palette"] : ["palette"]}
      noteId={noteId}
      onResult={onResult}
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
}: {
  query?: string;
  where?: string | string[];
  noteId?: string;
  pluginId?: string;
  onChoose?: (choice: CommandChoice) => void;
  onResult?: (value: unknown, origin: CommandOrigin) => void;
}) {
  const manifest = usePlugins();
  const places = Array.isArray(where) ? where : [where];
  const slash = places.includes("slash");
  const [active, setActive] = useState(0);
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
        .map(({ command, at }) => ({
          plugin: p.id,
          id: command.id,
          title: [...command.title].slice(0, 24).join(""),
          name: p.name,
          args:
            noteId && at.includes("note-actions")
              ? { note_id: noteId }
              : undefined,
        })),
    )
    .filter((c) =>
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
      role={slash ? "listbox" : undefined}
      aria-label={slash ? "Commands" : undefined}
    >
      {commands.map((c, index) => (
        <button
          className={styles.command}
          key={`${c.plugin}/${c.id}`}
          role={slash ? "option" : undefined}
          aria-selected={slash ? index === highlighted : undefined}
          disabled={command.isPending}
          onMouseDown={slash ? (e) => e.preventDefault() : undefined}
          onClick={() => choose(c)}
        >
          <span>{c.title}</span>
          <small>{c.name}</small>
        </button>
      ))}
      {command.isError && (
        <p role="alert" className="error">
          {command.error.message}
        </p>
      )}
    </div>
  );
}
