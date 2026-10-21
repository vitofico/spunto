/** "1 record", "2 records"; pass the plural when it is not just "s". */
export function plural(count: number, one: string, many = `${one}s`) {
  return `${count.toLocaleString()} ${count === 1 ? one : many}`;
}

/** Sizes in the unit people read: "24 bytes", "3.4 KB", "12 MB". */
export function formatBytes(bytes: number) {
  if (bytes < 1000) return plural(bytes, "byte");
  const [value, unit] =
    bytes < 1_000_000
      ? [bytes / 1000, "KB"]
      : bytes < 1_000_000_000
        ? [bytes / 1_000_000, "MB"]
        : [bytes / 1_000_000_000, "GB"];
  return `${value.toLocaleString(undefined, { maximumFractionDigits: value < 10 ? 1 : 0 })} ${unit}`;
}

/** A date and time in words: "21 Oct 2026, 14:32". */
export function formatWhen(value: string | number) {
  return new Date(value).toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  });
}

const words: Record<string, string> = {
  min: "minimum",
  max: "maximum",
  url: "URL",
  urls: "URLs",
  id: "ID",
  ai: "AI",
};

/** A setting key as a label when the schema gives no title: "min_notes" → "Minimum notes". */
export function humanize(key: string) {
  const text = key
    .split(/[_\-\s]+/)
    .filter(Boolean)
    .map((word) => words[word.toLowerCase()] ?? word.toLowerCase())
    .join(" ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * An enum value as a choice: "sunday" → "Sunday"; language codes by name ("it" → "Italian").
 * Only identifier-like values are reworded; others, such as note titles, stay as written.
 */
export function choiceLabel(choice: unknown) {
  if (typeof choice !== "string") return String(choice);
  if (/^[a-z]{2}$/.test(choice)) {
    try {
      return (
        new Intl.DisplayNames(undefined, { type: "language" }).of(choice) ??
        choice.toUpperCase()
      );
    } catch {
      return choice.toUpperCase();
    }
  }
  return /^[a-z0-9]+([_-][a-z0-9]+)*$/.test(choice) ? humanize(choice) : choice;
}

/** "Chrome on macOS" from a browser's user agent; anything unrecognised stays as it is. */
export function browserName(agent: string) {
  const browser = /Edg(e|A|iOS)?\//.test(agent)
    ? "Edge"
    : /OPR\//.test(agent)
      ? "Opera"
      : /Firefox\/|FxiOS\//.test(agent)
        ? "Firefox"
        : /Chrome\/|CriOS\//.test(agent)
          ? "Chrome"
          : /Safari\//.test(agent)
            ? "Safari"
            : null;
  const system = /Android/.test(agent)
    ? "Android"
    : /iPhone|iPad|iPod/.test(agent)
      ? "iOS"
      : /Macintosh|Mac OS X/.test(agent)
        ? "macOS"
        : /Windows/.test(agent)
          ? "Windows"
          : /CrOS/.test(agent)
            ? "ChromeOS"
            : /Linux/.test(agent)
              ? "Linux"
              : null;
  if (browser && system) return `${browser} on ${system}`;
  return browser ?? system ?? agent;
}
