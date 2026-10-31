import { fold } from "./text";

/** Plain text only: keep a bounded window around the first word-start match. */
export function searchExcerpt(body: string, terms: string[], width = 240) {
  const text = body
    .replace(/\[([^\]]+)\]\(([^)]*)\)/g, "$1 ($2)")
    .replace(/^\s*(?:#{1,6}\s+|>\s*|[-+*]\s+(?:\[[ xX]\]\s*)?)/gm, "")
    .replace(/\s+/g, " ")
    .trim();
  let folded = "";
  const offsets: number[] = [];
  for (let at = 0; at < text.length;) {
    const char = String.fromCodePoint(text.codePointAt(at)!);
    for (const unit of fold(char)) {
      folded += unit;
      for (let index = 0; index < unit.length; index++) offsets.push(at);
    }
    at += char.length;
  }
  let match = Infinity;
  for (const term of terms) {
    if (!term) continue;
    let from = 0;
    for (;;) {
      const index = folded.indexOf(term, from);
      if (index < 0) break;
      if (!index || !/[\p{L}\p{N}]/u.test(folded[index - 1])) {
        match = Math.min(match, offsets[index]);
        break;
      }
      from = index + term.length;
    }
  }
  let start = Number.isFinite(match)
    ? Math.max(0, Math.min(match - Math.floor(width / 3), text.length - width))
    : 0;
  // Keep both ends on complete UTF-16 code points.
  if (start && /[\uDC00-\uDFFF]/.test(text[start])) start--;
  let end = Math.min(text.length, start + width);
  if (end < text.length && /[\uDC00-\uDFFF]/.test(text[end])) end--;
  return (
    (start ? "…" : "") + text.slice(start, end) + (end < text.length ? "…" : "")
  );
}
