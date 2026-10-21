import { useEffect, useId, useRef, useState } from "react";
import { Icon } from "./Icon";

export type MenuItem = {
  label: string;
  onSelect: () => void;
  danger?: boolean;
};

/** One `more` button opening a short action list: arrows move, Escape returns to the button. */
export function MoreMenu({
  label,
  items,
}: {
  label: string;
  items: MenuItem[];
}) {
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const id = useId();
  useEffect(() => {
    if (!open) return;
    menu.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    const away = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!menu.current?.contains(target) && !trigger.current?.contains(target))
        setOpen(false);
    };
    document.addEventListener("pointerdown", away);
    return () => document.removeEventListener("pointerdown", away);
  }, [open]);
  const close = () => {
    setOpen(false);
    trigger.current?.focus();
  };
  return (
    <div className="more" data-open={open || undefined}>
      <button
        ref={trigger}
        className="button icon-button"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        onClick={() => setOpen(!open)}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown" && !open) {
            event.preventDefault();
            setOpen(true);
          }
        }}
      >
        <Icon name="more" />
      </button>
      {open && (
        <div
          ref={menu}
          id={id}
          className="menu"
          role="menu"
          aria-label={label}
          onKeyDown={(event) => {
            const entries = [
              ...menu.current!.querySelectorAll<HTMLElement>(
                '[role="menuitem"]',
              ),
            ];
            const at = entries.indexOf(document.activeElement as HTMLElement);
            const next = (
              {
                ArrowDown: at + 1,
                ArrowUp: at - 1,
                Home: 0,
                End: entries.length - 1,
              } as Record<string, number>
            )[event.key];
            if (next !== undefined) {
              event.preventDefault();
              entries[(next + entries.length) % entries.length]?.focus();
            } else if (event.key === "Escape") {
              event.preventDefault();
              event.stopPropagation();
              close();
            } else if (event.key === "Tab") setOpen(false);
          }}
        >
          {items.map((item) => (
            <button
              key={item.label}
              role="menuitem"
              tabIndex={-1}
              className={item.danger ? "danger" : undefined}
              onClick={() => {
                close();
                item.onSelect();
              }}
            >
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
