import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { api, useLabels } from "../../lib";
import { formatShortcut } from "../../lib/platform";
import { Icon } from "./Icon";
import { SlashCommands } from "../../features/Slots";
export function activeLabel(body: string, caret: number) {
  return (
    /(?:^|\s)#([\p{L}\p{N}_/-]*)$/u.exec(body.slice(0, caret))?.[1] ?? null
  );
}
export function highlightedParts(body: string) {
  return body
    .split(
      /(#[\p{L}\p{N}_][\p{L}\p{N}_/-]*|`[^`\n]*`|^#{1,6}(?=\s)|\*\*|__)/gmu,
    )
    .map((text, index) => ({
      text,
      index,
      kind:
        text.startsWith("#") && !/^#+$/.test(text)
          ? "label"
          : /^(?:`|#+$|\*\*|__)/.test(text)
            ? "markup"
            : "",
    }));
}
/** Enter inside a list: the next item, or the end of the list when the item is empty. */
export function continueList(body: string, caret: number) {
  const start = body.lastIndexOf("\n", caret - 1) + 1;
  const end = body.indexOf("\n", caret);
  const line = body.slice(start, end < 0 ? body.length : end);
  const item = /^(\s*)([-*+]|(\d+)([.)]))\s+(\[[ xX]\]\s+)?/.exec(line);
  if (!item) return null;
  if (item[0].length === line.length && caret === start + line.length)
    return { body: body.slice(0, start) + body.slice(caret), caret: start };
  const marker = item[3] ? Number(item[3]) + 1 + item[4]! : item[2];
  const next = "\n" + item[1] + marker + " " + (item[5] ? "[ ] " : "");
  return {
    body: body.slice(0, caret) + next + body.slice(caret),
    caret: caret + next.length,
  };
}
/** Where a click on rendered Markdown lands in its source text, when the browser can tell. */
export function sourceOffset(
  container: HTMLElement,
  x: number,
  y: number,
  body: string,
) {
  const point = document.caretPositionFromPoint?.(x, y);
  const range = point
    ? { node: point.offsetNode, offset: point.offset }
    : (() => {
        const legacy = document.caretRangeFromPoint?.(x, y);
        return (
          legacy && {
            node: legacy.startContainer,
            offset: legacy.startOffset,
          }
        );
      })();
  if (!range || !container.contains(range.node)) return undefined;
  const before = document.createRange();
  before.setStart(container, 0);
  before.setEnd(range.node, range.offset);
  // Rendered text is the source minus its markup, so walk the source in order.
  let at = 0;
  for (const char of before.toString()) {
    const found = body.indexOf(char, at);
    if (found < 0) return undefined;
    at = found + char.length;
  }
  return at;
}
interface Props {
  body: string;
  setBody: (body: string) => void;
  onSave: () => void;
  upload?: (file: File) => Promise<{ snippet: string }>;
  placeholder?: string;
  autoFocus?: boolean;
  /** Where the caret starts when the editor opens focused; the end of the text by default. */
  caret?: number;
  note?: boolean;
  busy?: boolean;
  saveDisabled?: boolean;
  error?: string | null;
  id?: string;
}
export function Editor({
  body,
  setBody,
  onSave,
  upload,
  placeholder = "What’s on your mind?",
  autoFocus = false,
  caret: initialCaret,
  note = false,
  busy = false,
  saveDisabled = false,
  error,
  id = "composer",
}: Props) {
  const input = useRef<HTMLTextAreaElement>(null);
  const file = useRef<HTMLInputElement>(null);
  const listId = useId();
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [caret, setCaret] = useState(body.length);
  const [selected, setSelected] = useState(0);
  // Autocomplete waits for typing, so opening a note that ends in a label shows no popup.
  const [dismissed, setDismissed] = useState(true);
  const labels = useLabels();
  const prefix = dismissed ? null : activeLabel(body, caret);
  const known = (labels.data?.labels ?? []).filter(
    (l) =>
      prefix !== null && l.path.toLowerCase().startsWith(prefix.toLowerCase()),
  );
  const options = [
    ...known.slice(0, 6).map((l) => ({ path: l.path, fresh: false })),
    ...(prefix &&
    !/\/$/.test(prefix) &&
    !known.some((l) => l.path.toLowerCase() === prefix.toLowerCase())
      ? [{ path: prefix, fresh: true }]
      : []),
  ];
  const open = prefix !== null && options.length > 0;
  const active = Math.min(selected, options.length - 1);
  const slash = /^\/([^\n]*)$/.exec(
    body.slice(0, caret).split("\n").at(-1) ?? "",
  )?.[1];
  // The composer gives each capture away at once, so only a note editor ever shows "Saving…".
  const saving = note && busy;
  useLayoutEffect(() => {
    const el = input.current;
    if (el) {
      el.style.height = "auto";
      el.style.height = Math.max(note ? 280 : 114, el.scrollHeight) + "px";
    }
  }, [body, note]);
  useLayoutEffect(() => {
    const el = input.current;
    if (!autoFocus || !el) return;
    const at = Math.min(initialCaret ?? el.value.length, el.value.length);
    el.focus();
    el.setSelectionRange(at, at);
    setCaret(at);
    // Only the first focus places the caret; later renders keep the owner's selection.
  }, []);
  function place(text: string, at: number) {
    setBody(text);
    input.current?.focus();
    requestAnimationFrame(() => {
      input.current?.setSelectionRange(at, at);
      setCaret(at);
    });
  }
  /** Toolbar inserts act at the selection the field remembers, even after it lost focus. */
  function insert(text: (before: string, after: string) => string) {
    const el = input.current;
    if (!el) return;
    const value = el.value,
      start = el.selectionStart,
      end = el.selectionEnd;
    const added = text(value.slice(0, start), value.slice(end));
    place(
      value.slice(0, start) + added + value.slice(end),
      start + added.length,
    );
  }
  function choose(path: string) {
    const start = caret - (prefix?.length ?? 0) - 1;
    setDismissed(true);
    place(
      body.slice(0, start) + "#" + path + " " + body.slice(caret),
      start + path.length + 2,
    );
  }
  return (
    <div className={note ? "note-editor" : "composer"}>
      <div className="editor-wrap">
        <pre className="highlight" aria-hidden="true">
          {highlightedParts(body).map((p) => (
            <span key={p.index} className={p.kind}>
              {p.text}
            </span>
          ))}
          {"\n"}
        </pre>
        <textarea
          ref={input}
          id={id}
          aria-label={note ? "Edit note" : "New note"}
          aria-autocomplete="list"
          aria-controls={open ? listId : undefined}
          aria-activedescendant={open ? `${listId}-${active}` : undefined}
          value={body}
          placeholder={placeholder}
          onSelect={(e) => setCaret(e.currentTarget.selectionStart)}
          onChange={(e) => {
            const nextCaret = e.target.selectionStart;
            setBody(e.target.value);
            requestAnimationFrame(() =>
              input.current?.setSelectionRange(nextCaret, nextCaret),
            );
            setCaret(nextCaret);
            setDismissed(false);
            setSelected(0);
          }}
          onKeyDown={(e) => {
            const mod = e.metaKey || e.ctrlKey;
            if (mod && e.key === "Enter") {
              e.preventDefault();
              onSave();
            } else if (mod && e.key.toLowerCase() === "s") {
              // In a note, ⌘S is useDraftEditor's save-in-place shortcut; editing continues.
              if (!note) {
                e.preventDefault();
                onSave();
              }
            } else if (open) {
              if (e.key === "ArrowDown" || e.key === "ArrowUp") {
                e.preventDefault();
                setSelected(
                  (active + (e.key === "ArrowDown" ? 1 : options.length - 1)) %
                    options.length,
                );
              } else if (e.key === "Enter" || e.key === "Tab") {
                e.preventDefault();
                choose(options[active]!.path);
              } else if (e.key === "Escape") {
                e.preventDefault();
                setDismissed(true);
              }
            } else if (e.key === "Escape") {
              e.currentTarget.blur();
            } else if (
              e.key === "Enter" &&
              !e.shiftKey &&
              !e.altKey &&
              !e.nativeEvent.isComposing &&
              e.currentTarget.selectionStart === e.currentTarget.selectionEnd
            ) {
              const next = continueList(
                e.currentTarget.value,
                e.currentTarget.selectionStart,
              );
              if (next) {
                e.preventDefault();
                place(next.body, next.caret);
              }
            }
          }}
        />
      </div>
      <div className="composer-tools">
        {/* Tools act on the text without taking focus from it, so the caret and the phone keyboard stay. */}
        <div className="row" onMouseDown={(e) => e.preventDefault()}>
          <button
            className="button"
            aria-label="Add label"
            onClick={() => {
              setDismissed(false);
              setSelected(0);
              insert((before) => (/(^|\s)$/.test(before) ? "#" : " #"));
            }}
          >
            <Icon name="hash" />
          </button>
          <button
            className="button"
            aria-label="Add checklist"
            onClick={() =>
              insert((before) =>
                before.slice(before.lastIndexOf("\n") + 1).trim()
                  ? "\n- [ ] "
                  : "- [ ] ",
              )
            }
          >
            <Icon name="cbox" />
          </button>
          {upload && (
            <>
              <button
                className="button"
                aria-label="Attach image"
                onClick={() => file.current?.click()}
              >
                <Icon name="image" />
              </button>
              <input
                ref={file}
                hidden
                type="file"
                accept="image/png,image/jpeg,image/gif,image/webp"
                onChange={(e) => {
                  const value = e.target.files?.[0];
                  if (value) {
                    setUploadError(null);
                    void upload(value)
                      .then((attachment) =>
                        insert((before, after) => {
                          const lead = before && !before.endsWith("\n");
                          const tail = after && !after.startsWith("\n");
                          return (
                            (lead ? "\n" : "") +
                            attachment.snippet +
                            (tail ? "\n" : "")
                          );
                        }),
                      )
                      .catch((error) =>
                        setUploadError(
                          error instanceof Error
                            ? error.message
                            : "Could not attach the image. Try again.",
                        ),
                      );
                  }
                  e.currentTarget.value = "";
                }}
              />
            </>
          )}
        </div>
        <div className="row">
          <span className="composer-hint">{formatShortcut("mod+enter")}</span>
          <button
            className="button primary"
            disabled={saving || saveDisabled || !body.trim()}
            onClick={onSave}
          >
            {saving ? "Saving…" : note ? "Done" : "Save"}
          </button>
        </div>
      </div>
      {(error || uploadError) && (
        <p className="error" role="alert">
          {error || uploadError}
        </p>
      )}
      {open && (
        <div
          className="autocomplete"
          role="listbox"
          id={listId}
          aria-label="Labels"
        >
          {options.map((option, index) => (
            <div
              key={option.path + option.fresh}
              id={`${listId}-${index}`}
              className={index === active ? "active" : ""}
              role="option"
              aria-selected={index === active}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => choose(option.path)}
            >
              {option.fresh ? (
                <>
                  <span className="muted">New label</span> #{option.path}
                </>
              ) : (
                <>#{option.path}</>
              )}
            </div>
          ))}
        </div>
      )}
      {slash !== undefined && (
        <SlashCommands
          query={slash}
          onChoose={(choice) => {
            setDismissed(true);
            // The typed "/query" was only the way to the menu; it leaves with it.
            const start = body.lastIndexOf("\n", caret - 1) + 1;
            place(body.slice(0, start) + body.slice(caret), start);
            void api
              .command(choice.plugin, choice.id, choice.args ?? {})
              .then((value) =>
                window.dispatchEvent(
                  new CustomEvent("spunto:command-result", {
                    detail: {
                      value,
                      origin: {
                        plugin: choice.plugin,
                        command: choice.id,
                        args: choice.args,
                      },
                    },
                  }),
                ),
              )
              .catch((error) => setUploadError(error.message));
          }}
        />
      )}
    </div>
  );
}
