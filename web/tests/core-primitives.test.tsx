import { afterEach, beforeEach, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from "@testing-library/react";
import { ToastProvider, useToast } from "../src/components/core/Toast";
import { useConfirm } from "../src/components/core/ConfirmDialog";
import { formatShortcut, modKey } from "../src/lib/platform";
import { useDocumentTitle } from "../src/lib/useDocumentTitle";
import { followThemeColor } from "../src/lib/theme-color";

beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function () {
    this.open = false;
  };
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

function Archive({ onUndo }: { onUndo?: () => void }) {
  const toast = useToast();
  return (
    <button
      onClick={() =>
        toast.show({
          message: "Archived",
          actionLabel: "Undo",
          onAction: onUndo,
          timeoutMs: 1000,
        })
      }
    >
      Archive
    </button>
  );
}

it("shows one toast in a status region, keeps focus and offers its action", () => {
  vi.useFakeTimers();
  const undo = vi.fn();
  render(
    <ToastProvider>
      <Archive onUndo={undo} />
    </ToastProvider>,
  );
  const status = screen.getByRole("status");
  const trigger = screen.getByRole("button", { name: "Archive" });
  trigger.focus();
  fireEvent.click(trigger);
  fireEvent.click(trigger);
  expect(status.textContent).toBe("ArchivedUndo");
  expect(screen.getAllByRole("button", { name: "Undo" })).toHaveLength(1);
  expect(document.activeElement).toBe(trigger);
  fireEvent.click(screen.getByRole("button", { name: "Undo" }));
  expect(undo).toHaveBeenCalledOnce();
  expect(status.firstElementChild?.hasAttribute("data-open")).toBe(false);
});

it("closes after its timeout unless the pointer or focus holds it", () => {
  vi.useFakeTimers();
  render(
    <ToastProvider>
      <Archive />
    </ToastProvider>,
  );
  const toast = () => screen.getByRole("status").firstElementChild!;
  fireEvent.click(screen.getByRole("button", { name: "Archive" }));
  fireEvent.focus(screen.getByRole("button", { name: "Undo" }));
  act(() => vi.advanceTimersByTime(5000));
  expect(toast().hasAttribute("data-open")).toBe(true);
  fireEvent.blur(screen.getByRole("button", { name: "Undo" }));
  act(() => vi.advanceTimersByTime(1000));
  expect(toast().hasAttribute("data-open")).toBe(false);
});

it("is a no-op outside the provider", () => {
  render(<Archive />);
  fireEvent.click(screen.getByRole("button", { name: "Archive" }));
  expect(screen.queryByRole("status")).toBeNull();
});

function Remove({ onResult }: { onResult: (confirmed: boolean) => void }) {
  const [confirm, dialog] = useConfirm();
  return (
    <>
      <button
        onClick={async (event) =>
          onResult(
            await confirm({
              opener: event.currentTarget,
              title: "Delete this note forever?",
              body: "This cannot be undone.",
              confirmLabel: "Delete forever",
              danger: true,
            }),
          )
        }
      >
        Delete
      </button>
      {dialog}
    </>
  );
}

it("confirms in a themed dialog and resolves false on Cancel or Escape", async () => {
  const result = vi.fn();
  render(<Remove onResult={result} />);
  // fireEvent.click leaves focus on <body>, as a click in WebKit does.
  const opener = screen.getByRole("button", { name: "Delete" });
  fireEvent.click(screen.getByRole("button", { name: "Delete" }));
  const dialog = screen.getByRole("dialog", {
    name: "Delete this note forever?",
  });
  expect(dialog.textContent).toContain("This cannot be undone.");
  expect(screen.getByRole("button", { name: "Delete forever" }).className).toBe(
    "danger",
  );
  await act(async () =>
    fireEvent.click(screen.getByRole("button", { name: "Delete forever" })),
  );
  expect(result).toHaveBeenLastCalledWith(true);
  expect(screen.queryByRole("dialog")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Delete" }));
  await act(async () =>
    fireEvent.click(screen.getByRole("button", { name: "Cancel" })),
  );
  expect(result).toHaveBeenLastCalledWith(false);
  expect(document.activeElement).toBe(opener);
  opener.blur();
  fireEvent.click(opener);
  await act(async () =>
    fireEvent(screen.getByRole("dialog"), new Event("cancel")),
  );
  expect(result).toHaveBeenLastCalledWith(false);
  expect(document.activeElement).toBe(opener);
  expect(result).toHaveBeenCalledTimes(3);
});

it("names the modifier and formats shortcuts per platform", () => {
  const platform = vi.spyOn(navigator, "platform", "get");
  platform.mockReturnValue("MacIntel");
  expect(modKey()).toBe("⌘");
  expect(formatShortcut("mod+k")).toBe("⌘ K");
  expect(formatShortcut("mod+enter")).toBe("⌘ ↵");
  platform.mockReturnValue("Win32");
  expect(modKey()).toBe("Ctrl");
  expect(formatShortcut("mod+k")).toBe("Ctrl+K");
  expect(formatShortcut("mod+shift+enter")).toBe("Ctrl+Shift+Enter");
});

function Page({ title }: { title?: string }) {
  useDocumentTitle(title);
  return null;
}

it("titles the tab per page and restores the previous title", () => {
  document.title = "Before";
  const { rerender, unmount } = render(<Page title="Settings" />);
  expect(document.title).toBe("Settings · Spunto");
  rerender(<Page />);
  expect(document.title).toBe("Spunto");
  unmount();
  expect(document.title).toBe("Before");
});

it("keeps the theme-color meta on the chrome token", () => {
  const listeners: (() => void)[] = [];
  window.matchMedia = vi.fn().mockReturnValue({
    addEventListener: (_: string, listener: () => void) =>
      listeners.push(listener),
  });
  document.documentElement.style.setProperty("--chrome", "#E4DFD7");
  followThemeColor();
  const meta = document.querySelector<HTMLMetaElement>(
    'meta[name="theme-color"]',
  );
  expect(meta?.content).toBe("#E4DFD7");
  document.documentElement.style.setProperty("--chrome", "#0C0B0A");
  window.dispatchEvent(new Event("spunto:appearance"));
  expect(meta?.content).toBe("#0C0B0A");
  document.documentElement.style.setProperty("--chrome", "#E4DFD7");
  listeners.forEach((listener) => listener());
  expect(meta?.content).toBe("#E4DFD7");
  document.documentElement.style.removeProperty("--chrome");
});
