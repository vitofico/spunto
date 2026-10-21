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
        // Every Tab moves here: WebKit skips links on its own and would leave the dialog.
        event.preventDefault();
        const items = controls();
        const at = items.indexOf(document.activeElement as HTMLElement);
        const step = event.shiftKey ? -1 : 1;
        const next =
          at < 0
            ? items.at(event.shiftKey ? -1 : 0)
            : items[(at + step + items.length) % items.length];
        (next ?? dialog).focus();
      }
    };
    document.addEventListener("keydown", key);
    return () => {
      document.removeEventListener("keydown", key);
      if (previous?.isConnected) previous.focus();
    };
  }, [container, open, returnFocus]);
}
