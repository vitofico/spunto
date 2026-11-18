import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../lib/api";
import { t, useLanguage } from "../lib/i18n";
import { isBundledPlugin } from "../lib/localizePlugins";
import type { Annotation, Args, Note, Plugin } from "../lib/types";
import { askItems } from "./askConversationData";
import { plural } from "./format";
import { activePlugins, usePlugins } from "./Slots";
import { VocabularyOutput } from "./Vocabulary";
import styles from "./Feature.module.css";

function storedPanel(plugin: Plugin, annotation?: Annotation) {
  if (
    !annotation ||
    !isBundledPlugin(plugin) ||
    plugin.id !== "link-enrichment"
  )
    return annotation?.payload;
  // The second paragraph is the host-generated estimate, following the source link.
  return askItems(annotation.payload).map((item) =>
    item.type === "markdown" && typeof item.html === "string"
      ? {
          ...item,
          html: item.html.replace(
            /^(<p><a [^\n]+<\/a><\/p>\s*<p>)(\d+) min read(<\/p>)/,
            (_, before, count: string, after) =>
              `${before}${t("pluginui.reading_time", { count: Number(count) })}${after}`,
          ),
        }
      : item,
  );
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
  const language = useLanguage();
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
    queryKey: ["plugin-panel", plugin.id, note.id, note.version, language],
    queryFn: ({ signal }) => api.pluginPanel(plugin.id, note.id, signal),
    enabled: panel?.mode === "live" && navigator.onLine && !excludedInput,
    retry: false,
  });
  const output =
    panel?.mode === "live" && excludedInput
      ? undefined
      : (storedPanel(plugin, annotation) ?? query.data);
  if (!output) return null;
  const count = askItems(output).reduce((total, item) => {
    const notes = item.ids ?? item.items;
    return (
      total + (item.type === "notes" && Array.isArray(notes) ? notes.length : 1)
    );
  }, 0);
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
        displayText={
          annotation && plugin.id === "voice-memos" && isBundledPlugin(plugin)
            ? (text) => {
                const match = /^Recording (\d+:\d{2})$/.exec(text);
                return match
                  ? t("pluginui.recording_duration", { duration: match[1] })
                  : text;
              }
            : undefined
        }
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
