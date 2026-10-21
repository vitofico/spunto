import { useEffect, useId, useRef, type ReactNode } from "react";
import styles from "./Feature.module.css";
export function Dialog({
  title,
  onClose,
  children,
  footer,
  opener,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  /** The control that opened it, for a click: WebKit doesn't focus a clicked button. */
  opener?: HTMLElement | null;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const previous = opener ?? document.activeElement;
    const dialog = ref.current;
    if (dialog && !dialog.open) dialog.showModal();
    return () => {
      dialog?.close();
      if (previous instanceof HTMLElement && previous.isConnected)
        previous.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className={styles.modal}
      aria-labelledby={titleId}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onClick={(e) => {
        if (e.target === ref.current) {
          const box = ref.current.getBoundingClientRect();
          if (
            e.clientX < box.left ||
            e.clientX > box.right ||
            e.clientY < box.top ||
            e.clientY > box.bottom
          )
            onClose();
        }
      }}
    >
      <h2 id={titleId}>{title}</h2>
      {children}
      <footer>{footer ?? <button onClick={onClose}>Close</button>}</footer>
    </dialog>
  );
}
