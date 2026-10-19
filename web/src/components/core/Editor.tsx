import { useEffect, useRef, useState } from "react";
import { api, useLabels } from "../../lib";
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
interface Props {
  body: string;
  setBody: (body: string) => void;
  onSave: () => void;
  upload?: (file: File) => Promise<unknown>;
  placeholder?: string;
  autoFocus?: boolean;
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
  note = false,
  busy = false,
  saveDisabled = false,
  error,
  id = "composer",
}: Props) {
  const input = useRef<HTMLTextAreaElement>(null);
  const file = useRef<HTMLInputElement>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [caret, setCaret] = useState(body.length);
  const [selected, setSelected] = useState(0);
  const [dismissed, setDismissed] = useState(false);
  const labels = useLabels();
  const prefix = dismissed ? null : activeLabel(body, caret);
  const matches = (labels.data?.labels ?? [])
    .filter(
      (l) =>
        prefix !== null &&
        l.path.toLowerCase().startsWith(prefix.toLowerCase()),
    )
    .slice(0, 6);
  const slash = /^\/([^\n]*)$/.exec(
    body.slice(0, caret).split("\n").at(-1) ?? "",
  )?.[1];
  useEffect(() => {
    const el = input.current;
    if (el) {
      el.style.height = "auto";
      el.style.height = Math.max(note ? 280 : 114, el.scrollHeight) + "px";
    }
  }, [body, note]);
  function insert(text: string) {
    const el = input.current;
    if (!el) return;
    const start = el.selectionStart,
      end = el.selectionEnd;
    setBody(body.slice(0, start) + text + body.slice(end));
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(start + text.length, start + text.length);
      setCaret(start + text.length);
    });
  }
  function choose(path: string) {
    const start = caret - (prefix?.length ?? 0) - 1;
    const next = body.slice(0, start) + "#" + path + " " + body.slice(caret);
    setBody(next);
    setDismissed(true);
    requestAnimationFrame(() => {
      input.current?.focus();
      const pos = start + path.length + 2;
      input.current?.setSelectionRange(pos, pos);
      setCaret(pos);
    });
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
          value={body}
          autoFocus={autoFocus}
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
            if (
              (e.metaKey || e.ctrlKey) &&
              (e.key === "Enter" || e.key.toLowerCase() === "s")
            ) {
              e.preventDefault();
              onSave();
            } else if (prefix !== null && matches.length) {
              if (e.key === "ArrowDown" || e.key === "ArrowUp") {
                e.preventDefault();
                setSelected(
                  (selected +
                    (e.key === "ArrowDown" ? 1 : matches.length - 1)) %
                    matches.length,
                );
              } else if (e.key === "Enter" || e.key === "Tab") {
                e.preventDefault();
                choose(matches[selected].path);
              } else if (e.key === "Escape") {
                e.preventDefault();
                setDismissed(true);
              }
            } else if (e.key === "Escape") {
              e.currentTarget.blur();
            }
          }}
        />
      </div>
      <div className="composer-tools">
        <div className="row">
          <button
            className="button"
            aria-label="Add label"
            onClick={() => insert("#")}
          >
            <Icon name="hash" />
          </button>
          <button
            className="button"
            aria-label="Add checklist"
            onClick={() =>
              insert((body && !body.endsWith("\n") ? "\n" : "") + "- [ ] ")
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
                className="sr-only"
                type="file"
                accept="image/png,image/jpeg,image/gif,image/webp"
                onChange={(e) => {
                  const value = e.target.files?.[0];
                  if (value) {
                    setUploadError(null);
                    void upload(value).catch((error) =>
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
          <span className="composer-hint">⌘ ↵</span>
          <button
            className="button primary"
            disabled={busy || saveDisabled || !body.trim()}
            onClick={onSave}
          >
            {busy ? "Saving…" : note ? "Done" : "Save"}
          </button>
        </div>
      </div>
      {(error || uploadError) && (
        <p className="error" role="alert">
          {error || uploadError}
        </p>
      )}
      {prefix !== null && matches.length > 0 && (
        <div className="autocomplete" role="listbox" aria-label="Labels">
          {matches.map((label, index) => (
            <button
              key={label.path}
              className={index === selected ? "active" : ""}
              role="option"
              aria-selected={index === selected}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => choose(label.path)}
            >
              #{label.path}
            </button>
          ))}
        </div>
      )}
      {slash !== undefined && (
        <SlashCommands
          query={slash}
          onChoose={(choice) => {
            setDismissed(true);
            void api
              .command(choice.plugin, choice.id, choice.args ?? {})
              .then((value) =>
                window.dispatchEvent(
                  new CustomEvent("spunto:command-result", { detail: value }),
                ),
              )
              .catch((error) => setUploadError(error.message));
          }}
        />
      )}
    </div>
  );
}
