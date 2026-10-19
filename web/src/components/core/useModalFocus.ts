import { useEffect, useRef, type RefObject } from "react";

export function useModalFocus(
  container: RefObject<HTMLElement | null>,
  open: boolean,
  onClose: () => void,
  returnFocus: RefObject<HTMLElement | null>,
) {
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    if (!open || !container.current) return;
    const dialog = container.current;
    const previous =
      returnFocus.current ?? (document.activeElement as HTMLElement);
    const controls = () =>
      [
        ...dialog.querySelectorAll<HTMLElement>(
          'a[href],button:enabled,input:not([type="hidden"]):enabled,textarea:enabled,select:enabled,[tabindex="0"]',
        ),
      ].filter(
        (control) =>
          control.getClientRects().length &&
          getComputedStyle(control).visibility !== "hidden",
      );
    const initial =
      dialog.querySelector<HTMLElement>("[data-modal-initial]") ??
      controls()[0];
    (initial ?? dialog).focus();
    const key = (event: KeyboardEvent) => {
      if (event.defaultPrevented || document.querySelector("dialog[open]"))
        return;
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        close.current();
      } else if (event.key === "Tab") {
        const items = controls();
        const first = items[0],
          last = items.at(-1);
        if (!first) {
          event.preventDefault();
          dialog.focus();
        } else if (
          event.shiftKey &&
          (document.activeElement === first ||
            !dialog.contains(document.activeElement))
        ) {
          event.preventDefault();
          last?.focus();
        } else if (
          !event.shiftKey &&
          (document.activeElement === last ||
            !dialog.contains(document.activeElement))
        ) {
          event.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener("keydown", key);
    return () => {
      document.removeEventListener("keydown", key);
      if (previous?.isConnected) previous.focus();
    };
  }, [container, open, returnFocus]);
}
