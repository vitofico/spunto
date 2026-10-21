/** Keeps the browser's bar colour on the theme's `chrome` token as theme or mode change. */
export function followThemeColor() {
  const meta =
    document.querySelector<HTMLMetaElement>('meta[name="theme-color"]') ??
    document.head.appendChild(
      Object.assign(document.createElement("meta"), { name: "theme-color" }),
    );
  const sync = () => {
    meta.content = getComputedStyle(document.documentElement)
      .getPropertyValue("--chrome")
      .trim();
  };
  sync();
  window.addEventListener("spunto:appearance", sync);
  window
    .matchMedia("(prefers-color-scheme: dark)")
    .addEventListener("change", sync);
}
