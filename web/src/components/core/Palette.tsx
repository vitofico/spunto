import { useEffect, useRef, useState } from "react";
import { useLocation } from "wouter";
import { api, useLabels, useSearch, useWorkspaceRole } from "../../lib";
import { Icon } from "./Icon";
import {
  PaletteCommands,
  CommandResult,
  type CommandOrigin,
} from "../../features/Slots";
export function Palette({
  onClose,
  noteId,
  initialResult,
  initialOrigin,
  restoreFocus,
}: {
  onClose: () => void;
  noteId?: string;
  initialResult?: unknown;
  initialOrigin?: CommandOrigin;
  restoreFocus?: HTMLElement | null;
}) {
  const canEdit = useWorkspaceRole() !== "viewer";
  const [exportError, setExportError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [result, setResult] = useState<unknown>(initialResult ?? null);
  const [origin, setOrigin] = useState(initialOrigin);
  const [, navigate] = useLocation();
  const input = useRef<HTMLInputElement>(null);
  const labels = useLabels();
  const results = useSearch(query, { mode: "keyword", limit: 6 });
  const notes =
    results.data?.pages.flatMap((page) => page.hits).slice(0, 6) ?? [];
  const matchingLabels = (labels.data?.labels ?? [])
    .filter((label) => label.path.includes(query.replace(/^#/, "")))
    .slice(0, 4);
  useEffect(() => {
    const previous = restoreFocus ?? (document.activeElement as HTMLElement);
    input.current?.focus();
    return () => previous?.focus();
  }, []);
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.defaultPrevented || document.querySelector("dialog[open]")) return;
      if (e.key === "Escape") {
        e.preventDefault();
        if (result) setResult(null);
        else onClose();
      }
      if (e.key === "Tab") {
        const dialog = input.current?.closest("[role=dialog]");
        const controls = [
          ...(dialog?.querySelectorAll<HTMLElement>(
            'input:not([type="hidden"]):enabled,button:enabled,a[href],select:enabled,textarea:enabled',
          ) ?? []),
        ];
        const first = controls[0],
          last = controls.at(-1);
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last?.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first?.focus();
        }
      }
    };
    document.addEventListener("keydown", key);
    return () => {
      document.removeEventListener("keydown", key);
    };
  }, [onClose, result]);
  function go(path: string) {
    onClose();
    navigate(path);
  }
  const actions = [
    ["New note", "/"],
    ["Go to Archive", "/archive"],
    ["Go to Trash", "/trash"],
    ["Settings", "/settings/appearance"],
    ["Export notes", "/api/v1/export"],
  ].filter(
    ([label]) =>
      (canEdit || label !== "New note") &&
      label.toLowerCase().includes(query.toLowerCase()),
  );
  return (
    <div
      className="overlay"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <section
        className="palette"
        role="dialog"
        aria-modal="true"
        aria-label="Search and commands"
      >
        <div className="palette-head">
          <input
            ref={input}
            aria-label="Search and commands"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search notes, labels and actions…"
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                go("/search?q=" + encodeURIComponent(query));
              }
            }}
          />
          <button
            className="button"
            aria-label="Close search and commands"
            onClick={onClose}
          >
            <Icon name="close" />
          </button>
        </div>
        {result ? (
          <div className="palette-group">
            <CommandResult
              value={result}
              origin={origin}
              onDismiss={() => setResult(null)}
            />
          </div>
        ) : (
          <>
            {canEdit && (
              <PaletteCommands
                query={query}
                noteId={noteId}
                onResult={(value, context) => {
                  setResult(value);
                  setOrigin(context);
                }}
              />
            )}
            {canEdit && noteId && (
              <div className="palette-group">
                <div className="overline">This note</div>
                <button
                  className="palette-row"
                  onClick={() => {
                    onClose();
                    document
                      .querySelector<HTMLButtonElement>(
                        ".note-header button[data-edit-note]",
                      )
                      ?.click();
                  }}
                >
                  Edit note<kbd>e</kbd>
                </button>
              </div>
            )}
            {query && (
              <div className="palette-group">
                <div className="overline">Notes</div>
                {results.isPending && (
                  <p className="palette-status" role="status">
                    Searching your notes…
                  </p>
                )}
                {results.error && (
                  <p className="palette-status error" role="alert">
                    {results.error.message}
                  </p>
                )}
                {!results.isPending && !results.error && !notes.length && (
                  <p className="palette-status">No notes match this search.</p>
                )}
                {notes.map((note) => (
                  <button
                    className="palette-row"
                    key={note.id}
                    onClick={() => go("/n/" + note.id)}
                  >
                    {note.title || note.body.slice(0, 80)}
                  </button>
                ))}
                <button
                  className="palette-row"
                  onClick={() => go("/search?q=" + encodeURIComponent(query))}
                >
                  See all results →
                </button>
              </div>
            )}
            {matchingLabels.length > 0 && (
              <div className="palette-group">
                <div className="overline">Labels</div>
                {matchingLabels.map((label) => (
                  <button
                    className="palette-row"
                    key={label.path}
                    onClick={() => go("/l/" + label.path)}
                  >
                    #{label.path}
                    <span className="muted">{label.count}</span>
                  </button>
                ))}
              </div>
            )}
            {exportError && (
              <p className="error" role="alert">
                {exportError}
              </p>
            )}
            <div className="palette-group">
              <div className="overline">Actions</div>
              {actions.map(([label, path]) => (
                <button
                  className="palette-row"
                  key={label}
                  onClick={async () => {
                    if (path.startsWith("/api")) {
                      try {
                        const url = URL.createObjectURL(
                          await api.exportNotes(),
                        );
                        const link = document.createElement("a");
                        link.href = url;
                        link.download = "spunto-export.zip";
                        link.click();
                        setTimeout(() => URL.revokeObjectURL(url), 1000);
                        onClose();
                      } catch {
                        setExportError(
                          "Could not export this space. Try again.",
                        );
                      }
                    } else go(path);
                  }}
                >
                  {label}
                </button>
              ))}
            </div>
          </>
        )}
        <footer className="palette-footer">
          {result
            ? "Esc to go back"
            : "Enter to search · Tab to move · Esc to close"}
        </footer>
      </section>
    </div>
  );
}
