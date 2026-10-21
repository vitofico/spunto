import { useEffect, useLayoutEffect, useRef, useState } from "react";
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
  onHelp,
  noteId,
  initialResult,
  initialOrigin,
  restoreFocus,
  instant = false,
}: {
  onClose: () => void;
  onHelp?: () => void;
  noteId?: string;
  initialResult?: unknown;
  initialOrigin?: CommandOrigin;
  restoreFocus?: HTMLElement | null;
  /** Opened from the keyboard: no entrance motion (03 §9.1). */
  instant?: boolean;
}) {
  const canEdit = useWorkspaceRole() !== "viewer";
  const [exportError, setExportError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [result, setResult] = useState<unknown>(initialResult ?? null);
  const [origin, setOrigin] = useState(initialOrigin);
  // Index into the rows below; the first row is "Search for …" whenever there is a query.
  const [active, setActive] = useState(-1);
  const [location, navigate] = useLocation();
  // Any navigation closes the palette, including links inside plugin results.
  const openedAt = useRef(location);
  useEffect(() => {
    if (location !== openedAt.current) onClose();
  }, [location]);
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const labels = useLabels();
  const results = useSearch(query, { mode: "keyword", limit: 6 });
  const notes =
    results.data?.pages.flatMap((page) => page.hits).slice(0, 6) ?? [];
  const matchingLabels = (labels.data?.labels ?? [])
    .filter((label) => label.path.includes(query.replace(/^#/, "")))
    .slice(0, 4);
  const matches = (label: string) =>
    label.toLowerCase().includes(query.trim().toLowerCase());
  // Rows come from this file and from plugin commands, so selection works on the DOM.
  const rows = () => [
    ...(list.current?.querySelectorAll<HTMLElement>("button:not(:disabled)") ??
      []),
  ];
  function paint(index: number) {
    const all = rows();
    all.forEach((row, at) => {
      row.id = "palette-option-" + at;
      row.toggleAttribute("data-active", at === index);
      row.tabIndex = -1;
    });
    const current = all[index];
    current?.scrollIntoView?.({ block: "nearest" });
    if (current)
      input.current?.setAttribute("aria-activedescendant", current.id);
    else input.current?.removeAttribute("aria-activedescendant");
  }
  useLayoutEffect(() => paint(active));
  useEffect(() => setActive(query.trim() ? 0 : -1), [query]);
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
            'input:not([type="hidden"]):enabled,button:enabled:not([tabindex="-1"]),a[href],select:enabled,textarea:enabled',
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
  async function exportNotes() {
    try {
      const url = URL.createObjectURL(await api.exportNotes());
      const link = document.createElement("a");
      link.href = url;
      link.download = "spunto-export.zip";
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      onClose();
    } catch {
      setExportError("Could not export this space. Try again.");
    }
  }
  const actions: { label: string; keys?: string; run: () => void }[] = [
    ...(canEdit ? [{ label: "New note", keys: "n", run: () => go("/") }] : []),
    { label: "Go to Archive", run: () => go("/archive") },
    { label: "Go to Trash", keys: "g t", run: () => go("/trash") },
    { label: "Settings", keys: "g s", run: () => go("/settings/appearance") },
    { label: "Export notes", run: () => void exportNotes() },
    ...(onHelp
      ? [
          {
            label: "Keyboard shortcuts",
            keys: "?",
            run: () => {
              onClose();
              onHelp();
            },
          },
        ]
      : []),
  ].filter((action) => matches(action.label));
  return (
    <div
      className="overlay"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <section
        className={"palette" + (instant ? "" : " enter")}
        role="dialog"
        aria-modal="true"
        aria-label="Search and commands"
      >
        <div className="palette-head">
          <input
            ref={input}
            aria-label="Search and commands"
            aria-controls="palette-options"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search notes, labels and actions…"
            onKeyDown={(e) => {
              if (result) return;
              const count = rows().length;
              if (e.key === "ArrowDown" || e.key === "ArrowUp") {
                e.preventDefault();
                if (!count) return;
                const step = e.key === "ArrowDown" ? 1 : -1;
                setActive((index) =>
                  index < 0 && step < 0
                    ? count - 1
                    : (index + step + count) % count,
                );
              } else if (e.key === "Enter") {
                e.preventDefault();
                rows()[active]?.click();
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
          <div
            ref={list}
            id="palette-options"
            onMouseMove={(e) => {
              const row = (e.target as HTMLElement).closest("button");
              const index = row ? rows().indexOf(row) : -1;
              if (index >= 0 && index !== active) setActive(index);
            }}
          >
            {query.trim() && (
              <div className="palette-group">
                <button
                  className="palette-row"
                  onClick={() => go("/search?q=" + encodeURIComponent(query))}
                >
                  <span>
                    Search for <q>{query.trim()}</q>
                  </span>
                  <kbd>↵</kbd>
                </button>
              </div>
            )}
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
            {canEdit && noteId && matches("Edit note") && (
              <div
                className="palette-group"
                role="group"
                aria-label="This note"
              >
                <div className="overline" aria-hidden="true">
                  This note
                </div>
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
            {query.trim() && (
              <div className="palette-group" role="group" aria-label="Notes">
                <div className="overline" aria-hidden="true">
                  Notes
                </div>
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
              </div>
            )}
            {matchingLabels.length > 0 && (
              <div className="palette-group" role="group" aria-label="Labels">
                <div className="overline" aria-hidden="true">
                  Labels
                </div>
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
            {actions.length > 0 && (
              <div className="palette-group" role="group" aria-label="Actions">
                <div className="overline" aria-hidden="true">
                  Actions
                </div>
                {actions.map((action) => (
                  <button
                    className="palette-row"
                    key={action.label}
                    onClick={action.run}
                  >
                    {action.label}
                    {action.keys && <kbd>{action.keys}</kbd>}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
        <footer className="palette-footer">
          {result
            ? "Esc to go back"
            : "↑ ↓ to choose · ↵ to open · Esc to close"}
        </footer>
      </section>
    </div>
  );
}
