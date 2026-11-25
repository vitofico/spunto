import { useSyncExternalStore } from "react";
export type NotesLayout = "column" | "wide";
export const notesLayoutKey = "spunto:notes-layout";
export function readNotesLayout(): NotesLayout {
  try {
    const saved = localStorage.getItem(notesLayoutKey);
    // "grid" is what the earlier tile layout saved; it now means the full-width column.
    return saved === "wide" || saved === "grid" ? "wide" : "column";
  } catch {
    return "column";
  }
}
function applyNotesLayout(layout: NotesLayout) {
  document.documentElement.dataset.notesLayout = layout;
}
export function saveNotesLayout(layout: NotesLayout) {
  try {
    localStorage.setItem(notesLayoutKey, layout);
  } catch {
    // The current choice still works when browser storage is unavailable.
  }
  applyNotesLayout(layout);
}
export function followNotesLayout() {
  applyNotesLayout(readNotesLayout());
  window.addEventListener("storage", (event) => {
    if (event.key === notesLayoutKey || event.key === null)
      applyNotesLayout(readNotesLayout());
  });
}
// The root attribute is the one source of truth, so other tabs' changes reach the button too.
function subscribe(listener: () => void) {
  const observer = new MutationObserver(listener);
  observer.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ["data-notes-layout"],
  });
  return () => observer.disconnect();
}
const current = (): NotesLayout =>
  document.documentElement.dataset.notesLayout === "wide" ? "wide" : "column";
export const useNotesLayout = () => useSyncExternalStore(subscribe, current);
