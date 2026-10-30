import { t, useLanguage } from "../../lib/i18n";
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
  useLanguage();
  const canEdit = useWorkspaceRole() !== "viewer";
  const [exportError, setExportError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [result, setResult] = useState<unknown>(initialResult ?? null);
  const [origin, setOrigin] = useState(initialOrigin);
  // The highlighted row itself, not its place, so it stays highlighted when rows arrive above it.
  const [active, setActive] = useState<HTMLElement | null>(null);
  const [location, navigate] = useLocation();
  // Any navigation closes the palette, including links inside plugin results.
  const openedAt = useRef(location);
  useEffect(() => {
    if (location !== openedAt.current) onClose();
  }, [location]);
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const defaultSelection = useRef(true);
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
  function paint(highlighted: HTMLElement | null) {
    const all = rows();
    all.forEach((row, at) => {
      row.id = "palette-option-" + at;
      row.setAttribute("role", "option");
      row.setAttribute("aria-selected", String(row === highlighted));
      row.tabIndex = -1;
    });
    const current = all.find((row) => row === highlighted);
    current?.scrollIntoView?.({ block: "nearest" });
    if (current)
      input.current?.setAttribute("aria-activedescendant", current.id);
    else input.current?.removeAttribute("aria-activedescendant");
  }
  useLayoutEffect(() => {
    paint(active);
    // Plugin rows can arrive after this render; they are options too.
    const observer = new MutationObserver(() => {
      const ask = list.current?.querySelector<HTMLElement>(
        "[data-ask-question]",
      );
      if (defaultSelection.current && query.trim().endsWith("?") && ask)
        setActive(ask);
      else paint(active);
    });
    if (list.current)
      observer.observe(list.current, { childList: true, subtree: true });
    return () => observer.disconnect();
  });
  useEffect(() => {
    defaultSelection.current = true;
    const ask = list.current?.querySelector<HTMLElement>("[data-ask-question]");
    const search = list.current?.querySelector<HTMLElement>(
      "[data-search-query]",
    );
    setActive(
      query.trim()
        ? query.trim().endsWith("?") && ask
          ? ask
          : (search ?? rows()[0] ?? null)
        : null,
    );
  }, [query]);
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
      setExportError(t("palette.could_not_export_this_space_try_again"));
    }
  }
  const actions: { label: string; keys?: string; run: () => void }[] = [
    ...(canEdit
      ? [{ label: t("app.new_note"), keys: "n", run: () => go("/") }]
      : []),
    { label: t("palette.go_to_archive"), run: () => go("/archive") },
    { label: t("app.go_to_trash"), keys: "g t", run: () => go("/trash") },
    {
      label: t("app.settings"),
      keys: "g s",
      run: () => go("/settings/appearance"),
    },
    { label: t("settingsdata.export_notes"), run: () => void exportNotes() },
    ...(onHelp
      ? [
          {
            label: t("app.keyboard_shortcuts"),
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
        aria-label={t("app.search_and_commands")}
      >
        <div className="palette-head">
          <input
            ref={input}
            aria-label={t("app.search_and_commands")}
            aria-controls="palette-options"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t("palette.search_notes_labels_and_actions")}
            onKeyDown={(e) => {
              if (result) return;
              const all = rows();
              const count = all.length;
              const index = active ? all.indexOf(active) : -1;
              if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
                const ask = list.current?.querySelector<HTMLButtonElement>(
                  "[data-ask-question]:not(:disabled)",
                );
                if (ask) {
                  e.preventDefault();
                  ask.click();
                  return;
                }
              }
              if (e.key === "ArrowDown" || e.key === "ArrowUp") {
                e.preventDefault();
                defaultSelection.current = false;
                if (!count) return;
                const step = e.key === "ArrowDown" ? 1 : -1;
                setActive(
                  all[
                    index < 0 && step < 0
                      ? count - 1
                      : (index + step + count) % count
                  ],
                );
              } else if (e.key === "Enter") {
                e.preventDefault();
                all[index]?.click();
              }
            }}
          />
          <button
            className="button"
            aria-label={t("palette.close_search_and_commands")}
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
            role="listbox"
            aria-label={t("palette.results")}
            onMouseMove={(e) => {
              const row = (e.target as HTMLElement).closest("button");
              if (row && row !== active && rows().includes(row)) {
                defaultSelection.current = false;
                setActive(row);
              }
            }}
          >
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
            {query.trim() && (
              <div className="palette-group">
                <button
                  className="palette-row"
                  data-search-query
                  onClick={() => go("/search?q=" + encodeURIComponent(query))}
                >
                  <span>
                    {t("palette.search_for")}
                    <q>{query.trim()}</q>
                  </span>
                </button>
              </div>
            )}
            {canEdit && noteId && matches(t("editor.edit_note")) && (
              <div
                className="palette-group"
                role="group"
                aria-label={t("palette.this_note")}
              >
                <div className="overline" aria-hidden="true">
                  {t("palette.this_note")}
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
                  {t("editor.edit_note")}
                  <kbd>{t("palette.e")}</kbd>
                </button>
              </div>
            )}
            {query.trim() && (
              <div
                className="palette-group"
                role="group"
                aria-label={t("review.notes")}
              >
                <div className="overline" aria-hidden="true">
                  {t("review.notes")}
                </div>
                {results.isPending && (
                  <p className="palette-status" role="status">
                    {t("search.searching_your_notes")}
                  </p>
                )}
                {results.error && (
                  <p className="palette-status error" role="alert">
                    {results.error.message}
                  </p>
                )}
                {!results.isPending && !results.error && !notes.length && (
                  <p className="palette-status">
                    {t("palette.no_notes_match_this_search")}
                  </p>
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
              <div
                className="palette-group"
                role="group"
                aria-label={t("app.labels")}
              >
                <div className="overline" aria-hidden="true">
                  {t("app.labels")}
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
              <div
                className="palette-group"
                role="group"
                aria-label={t("palette.actions")}
              >
                <div className="overline" aria-hidden="true">
                  {t("palette.actions")}
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
            ? t("palette.esc_to_go_back")
            : t("palette.to_choose_to_open_esc_to_close")}
        </footer>
      </section>
    </div>
  );
}
