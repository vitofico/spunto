import { useSyncExternalStore } from "react";
export type NotesLayout = "column" | "grid";
export const notesLayoutKey = "spunto:notes-layout";
export function readNotesLayout(): NotesLayout {
  try {
    return localStorage.getItem(notesLayoutKey) === "grid" ? "grid" : "column";
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
  document.documentElement.dataset.notesLayout === "grid" ? "grid" : "column";
export const useNotesLayout = () => useSyncExternalStore(subscribe, current);
