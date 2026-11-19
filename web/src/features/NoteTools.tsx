import { useRef, useState } from "react";
import { Icon } from "../components/core/Icon";
import { t, useLanguage } from "../lib/i18n";
import { currentSession, useOwner } from "../lib/query";
import type { NoteDetail } from "../lib/types";
import { Dialog } from "./Dialog";
import { noteToolKey, PluginCommands } from "./Slots";
import styles from "./Feature.module.css";

function readPins(key: string) {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(key) ?? "[]");
    if (Array.isArray(value))
      return [
        ...new Set(
          value.filter((item): item is string => typeof item === "string"),
        ),
      ].slice(0, 2);
  } catch {}
  return [];
}

export function NoteTools({
  note,
  enabled,
}: {
  note: NoteDetail;
  enabled: boolean;
}) {
  useOwner();
  const session = currentSession();
  if (!session) return null;
  const scope = JSON.stringify([
    session.user_id ?? session.owner_id,
    session.workspace_id ?? null,
  ]);
  return (
    <ToolChooser
      key={scope}
      note={note}
      enabled={enabled}
      storageKey={`spunto:note-tool-pins:${scope}`}
    />
  );
}

function ToolChooser({
  note,
  enabled,
  storageKey,
}: {
  note: NoteDetail;
  enabled: boolean;
  storageKey: string;
}) {
  useLanguage();
  const [pins, setPins] = useState(() => readPins(storageKey));
  const [picker, setPicker] = useState(false);
  const [direct, setDirect] = useState(false);
  const [pinMode, setPinMode] = useState(false);
  const opener = useRef<HTMLButtonElement>(null);
  const save = (keys: string[]) => {
    setPins(keys);
    try {
      localStorage.setItem(storageKey, JSON.stringify(keys));
    } catch {}
  };
  const close = () => {
    setPicker(false);
    setDirect(false);
    setPinMode(false);
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
      }) => {
        const shortcuts = pins.flatMap((key) => {
          const tool = commands.find((item) => noteToolKey(item) === key);
          return tool ? [tool] : [];
        });
        return (
          <>
            {shortcuts.map((tool) => (
              <button
                key={noteToolKey(tool)}
                type="button"
                className="button pinned-note-tool"
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
                {result ?? (
                  <>
                    {picker && commands.length > 0 && (
                      <div className={styles.notePinsControl}>
                        <button
                          type="button"
                          className="button"
                          aria-pressed={pinMode}
                          disabled={busy}
                          onClick={() => setPinMode(!pinMode)}
                        >
                          <Icon name="pin" /> {t("plugins.pin_tools")}
                        </button>
                      </div>
                    )}
                    {pinMode ? (
                      <>
                        <p className={styles.resultStatus}>
                          {t("plugins.pin_up_to_two")}
                        </p>
                        <div className={styles.noteCommandGrid}>
                          {commands.map((tool) => {
                            const key = noteToolKey(tool);
                            const pinned = pins.includes(key);
                            return (
                              <button
                                key={key}
                                className={styles.noteCommand}
                                type="button"
                                aria-pressed={pinned}
                                disabled={!pinned && pins.length >= 2}
                                onClick={() =>
                                  save(
                                    pinned
                                      ? pins.filter((item) => item !== key)
                                      : [...pins, key],
                                  )
                                }
                              >
                                <span className={styles.noteCommandIcon}>
                                  <Icon
                                    name={
                                      pinned ? "pin" : (tool.icon ?? "tools")
                                    }
                                  />
                                </span>
                                <span>{tool.title}</span>
                              </button>
                            );
                          })}
                        </div>
                        {pins.length > shortcuts.length && (
                          <button
                            type="button"
                            className="button"
                            onClick={() => save(shortcuts.map(noteToolKey))}
                          >
                            {t("plugins.clear_unavailable_pins")}
                          </button>
                        )}
                      </>
                    ) : (
                      list
                    )}
                  </>
                )}
              </Dialog>
            )}
          </>
        );
      }}
    />
  );
}
