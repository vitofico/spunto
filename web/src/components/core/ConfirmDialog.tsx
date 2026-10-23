import { t, useLanguage } from "../../lib/i18n";
import { useCallback, useEffect, useRef, useState } from "react";
import { Dialog } from "../../features/Dialog";

export type ConfirmOptions = {
  title: string;
  body?: string;
  confirmLabel: string;
  danger?: boolean;
  /** The clicked control that asked; focus returns there. */
  opener?: HTMLElement | null;
};

/** A themed replacement for window.confirm; Cancel comes first and takes focus. */
export function ConfirmDialog({
  title,
  body,
  confirmLabel,
  danger,
  opener,
  onClose,
}: ConfirmOptions & { onClose: (confirmed: boolean) => void }) {
  useLanguage();
  return (
    <Dialog
      title={title}
      opener={opener}
      onClose={() => onClose(false)}
      footer={
        <>
          <button onClick={() => onClose(false)}>
            {t("profilepanel.cancel")}
          </button>
          <button
            className={danger ? "danger" : "primary"}
            onClick={() => onClose(true)}
          >
            {confirmLabel}
          </button>
        </>
      }
    >
      {body && <p>{body}</p>}
    </Dialog>
  );
}

/**
 * `const [confirm, dialog] = useConfirm()`, render `{dialog}`, then
 * `if (await confirm({ title, confirmLabel, danger: true })) …`.
 */
export function useConfirm() {
  const [options, setOptions] = useState<ConfirmOptions>();
  const resolve = useRef<(confirmed: boolean) => void>(undefined);
  const settle = useCallback((confirmed: boolean) => {
    resolve.current?.(confirmed);
    resolve.current = undefined;
    setOptions(undefined);
  }, []);
  useEffect(() => () => resolve.current?.(false), []);
  const confirm = useCallback(
    (next: ConfirmOptions) =>
      new Promise<boolean>((done) => {
        resolve.current?.(false);
        resolve.current = done;
        setOptions(next);
      }),
    [],
  );
  const dialog = options && <ConfirmDialog {...options} onClose={settle} />;
  return [confirm, dialog] as const;
}
