export type BackgroundStyle = "plain" | "artwork";
export const backgroundStyleKey = "spunto:background-style";
export function readBackgroundStyle(): BackgroundStyle {
  try {
    return localStorage.getItem(backgroundStyleKey) === "plain"
      ? "plain"
      : "artwork";
  } catch {
    return "artwork";
  }
}
export function applyBackgroundStyle(style: BackgroundStyle) {
  document.documentElement.dataset.background = style;
}
export function saveBackgroundStyle(style: BackgroundStyle) {
  try {
    localStorage.setItem(backgroundStyleKey, style);
  } catch {
    // The current choice still works when browser storage is unavailable.
  }
  applyBackgroundStyle(style);
}
export function followBackgroundStyle() {
  applyBackgroundStyle(readBackgroundStyle());
  window.addEventListener("storage", (event) => {
    if (event.key === backgroundStyleKey || event.key === null)
      applyBackgroundStyle(readBackgroundStyle());
  });
}
