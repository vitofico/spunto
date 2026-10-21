const apple = () =>
  /mac|iphone|ipad|ipod/i.test(
    (navigator as Navigator & { userAgentData?: { platform?: string } })
      .userAgentData?.platform ?? navigator.platform,
  );

/** The modifier the shortcuts use: "⌘" on Apple platforms, "Ctrl" elsewhere. */
export const modKey = () => (apple() ? "⌘" : "Ctrl");

const keys: Record<string, [apple: string, other: string]> = {
  mod: ["⌘", "Ctrl"],
  shift: ["⇧", "Shift"],
  alt: ["⌥", "Alt"],
  enter: ["↵", "Enter"],
};

/** "mod+k" reads "⌘ K" on Apple platforms and "Ctrl+K" elsewhere. */
export function formatShortcut(shortcut: string) {
  const mac = apple();
  return shortcut
    .split("+")
    .map(
      (key) =>
        keys[key.toLowerCase()]?.[mac ? 0 : 1] ??
        (key.length === 1 ? key.toUpperCase() : key),
    )
    .join(mac ? " " : "+");
}
