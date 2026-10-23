import { useLanguage } from "../../lib/i18n";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import styles from "./Toast.module.css";

export type ToastOptions = {
  message: string;
  actionLabel?: string;
  onAction?: () => void;
  timeoutMs?: number;
};
type Shown = ToastOptions & { id: number };

const ToastContext = createContext<{ show: (toast: ToastOptions) => void }>({
  show: () => {},
});

/** One line at the bottom ("Archived · Undo"); a new toast replaces the current one. */
export const useToast = () => useContext(ToastContext);

export function ToastProvider({ children }: { children: ReactNode }) {
  useLanguage();
  const [toast, setToast] = useState<Shown>();
  const [open, setOpen] = useState(false);
  const [held, setHeld] = useState(false);
  const next = useRef(0);
  const show = useCallback((options: ToastOptions) => {
    setToast({ ...options, id: ++next.current });
    setHeld(false);
    setOpen(true);
  }, []);
  const value = useMemo(() => ({ show }), [show]);
  useEffect(() => {
    if (!open || held || !toast) return;
    const timer = setTimeout(() => setOpen(false), toast.timeoutMs ?? 6000);
    return () => clearTimeout(timer);
  }, [open, held, toast]);
  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className={styles.layer} role="status">
        <div
          className={styles.toast}
          data-open={open || undefined}
          onPointerEnter={() => setHeld(true)}
          onPointerLeave={() => setHeld(false)}
          onFocus={() => setHeld(true)}
          onBlur={() => setHeld(false)}
          onKeyDown={(event) => {
            if (event.key === "Escape") setOpen(false);
          }}
        >
          {toast && <span key={toast.id}>{toast.message}</span>}
          {toast?.actionLabel && (
            <button
              className="button"
              onClick={() => {
                setOpen(false);
                toast.onAction?.();
              }}
            >
              {toast.actionLabel}
            </button>
          )}
        </div>
      </div>
    </ToastContext.Provider>
  );
}
