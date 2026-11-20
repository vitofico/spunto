import { useRef, type RefObject } from "react";
import { t, useLanguage } from "../../lib/i18n";
import { formatShortcut } from "../../lib/platform";
import { useModalFocus } from "./useModalFocus";

const shortcuts: () => [string[], string][] = () => [
  [["n"], t("app.new_note")],
  [[formatShortcut("mod+enter")], t("app.save_the_note")],
  [[formatShortcut("mod+s")], t("app.save_while_editing")],
  [[formatShortcut("mod+k"), "/"], t("app.search_and_commands")],
  [["j", "k"], t("app.next_or_previous_note")],
  [["↵", "o"], t("app.open_the_note")],
  [["e"], t("app.edit_the_note")],
  [["p"], t("app.pin_or_unpin")],
  [["#"], t("app.add_a_label_opens_the_note_to_edit")],
  [["y"], t("app.archive")],
  [[formatShortcut("shift+y")], t("app.move_to_trash")],
  [["Esc"], t("app.close_or_go_back_from_a_note")],
  [["["], t("app.collapse_the_navigation")],
  [["g a"], t("app.go_to_all_notes")],
  [["g l"], t("app.go_to_the_labels")],
  [["g t"], t("app.go_to_trash")],
  [["g s"], t("app.go_to_settings")],
  [["?"], t("app.show_these_keys")],
];
export default function KeyboardShortcuts({
  onClose,
  returnFocus,
}: {
  onClose: () => void;
  returnFocus: RefObject<HTMLElement | null>;
}) {
  useLanguage();
  const dialog = useRef<HTMLElement>(null);
  useModalFocus(dialog, true, onClose, returnFocus);
  return (
    <div
      className="overlay"
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        ref={dialog}
        className="palette card"
        role="dialog"
        aria-modal="true"
        aria-label={t("app.keyboard_shortcuts")}
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            event.preventDefault();
            onClose();
          }
        }}
      >
        <h2 className="page-title">{t("app.a_few_keys_a_little_faster")}</h2>
        <dl className="keys">
          {shortcuts().map(([keys, action]) => (
            <div key={action}>
              <dt>
                {keys.map((key, index) => (
                  <span key={key}>
                    {index > 0 && t("format.or")}
                    <kbd>{key}</kbd>
                  </span>
                ))}
              </dt>
              <dd>{action}</dd>
            </div>
          ))}
        </dl>
        <button className="button primary" autoFocus onClick={() => onClose()}>
          {t("app.close")}
        </button>
      </section>
    </div>
  );
}
