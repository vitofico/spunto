import { useRef, useState } from "react";
import { Icon } from "../components/core/Icon";
import { t, useLanguage } from "../lib/i18n";
import { hasNoteToolInput } from "../lib/noteToolInputs";
import type { NoteDetail } from "../lib/types";
import { Dialog } from "./Dialog";
import { noteToolKey, PluginCommands, type NoteToolChoice } from "./Slots";

const shortcutPriority = [
  "image-text/read",
  "voice-memos/transcribe",
  "link-enrichment/fetch",
  "title-summary/summarize",
  "label-suggestions/suggest-now",
  "tidy/tidy",
];

export function noteShortcuts(commands: NoteToolChoice[], note: NoteDetail) {
  const bundled = new Map(
    commands
      .filter((tool) => tool.trustedBundled)
      .map((tool) => [noteToolKey(tool), tool]),
  );
  const hasMediaOrLink =
    hasNoteToolInput(note, "image") ||
    hasNoteToolInput(note, "audio") ||
    hasNoteToolInput(note, "link");
  if (hasMediaOrLink) bundled.delete("tidy/tidy");
  const contextual = shortcutPriority
    .flatMap((key) => bundled.get(key) ?? [])
    .slice(0, 2);
  const link = bundled.get("links/link");
  return contextual.length || !link ? contextual : [link];
}

export function NoteTools({
  note,
  enabled,
}: {
  note: NoteDetail;
  enabled: boolean;
}) {
  useLanguage();
  const [picker, setPicker] = useState(false);
  const [direct, setDirect] = useState(false);
  const opener = useRef<HTMLButtonElement>(null);
  const close = () => {
    setPicker(false);
    setDirect(false);
  };
  return (
    <PluginCommands
      where="note-actions"
      noteId={note.id}
      note={note}
      noteToolsEnabled={enabled}
      onOpenNote={close}
      renderNoteTools={({
        commands,
        choose,
        list,
        result,
        busy,
        failed,
        reset,
      }) => (
        <>
          {noteShortcuts(commands, note).map((tool) => (
            <button
              key={noteToolKey(tool)}
              type="button"
              className="button note-tool-shortcut"
              aria-disabled={busy || undefined}
              onClick={(event) => {
                if (busy) return;
                reset();
                opener.current = event.currentTarget;
                setDirect(true);
                choose(tool);
              }}
            >
              <Icon name={tool.icon ?? "tools"} /> {tool.title}
            </button>
          ))}
          {enabled && list && (
            <button
              type="button"
              className="button note-command-trigger"
              aria-haspopup="dialog"
              onClick={(event) => {
                reset();
                opener.current = event.currentTarget;
                setDirect(false);
                setPicker(true);
              }}
            >
              <Icon name="tools" /> {t("plugins.note_tools")}
            </button>
          )}
          {direct && busy && (
            <span className="sr-only" role="status">
              {t("plugins.running")}
            </span>
          )}
          {(picker || (direct && (result || failed))) && (
            <Dialog
              title={t("plugins.note_tools")}
              opener={opener.current}
              onClose={() => {
                reset();
                close();
              }}
            >
              {result ?? list}
            </Dialog>
          )}
        </>
      )}
    />
  );
}
