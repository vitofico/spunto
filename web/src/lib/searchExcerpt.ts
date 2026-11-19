import { fold } from "./text";

export function searchMatchRanges(
  text: string,
  terms: string[],
  firstOnly = false,
) {
  if (!terms.length || !text) return [];
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
  const matches: [number, number][] = [];
  for (const term of terms) {
    if (!term) continue;
    let from = 0;
    for (;;) {
      const index = folded.indexOf(term, from);
      if (index < 0) break;
      if (!index || !/[\p{L}\p{N}]/u.test(folded[index - 1])) {
        const end = offsets[index + term.length - 1];
        matches.push([
          offsets[index],
          end + String.fromCodePoint(text.codePointAt(end)!).length,
        ]);
        if (firstOnly) break;
      }
      from = index + term.length;
    }
  }
  return matches.sort(([a, endA], [b, endB]) => a - b || endB - endA);
}

function plainText(body: string) {
  return body
    .replace(/!?\[([^\]]+)\]\(([^)]*)\)/g, "$1 ($2)")
    .replace(
      /^\s*(?:#{1,6}\s+|>\s*|(?:[-+*]|\d+[.)])\s+(?:\[[ xX]\]\s*)?)/gm,
      "",
    )
    .replace(/\s+/g, " ")
    .trim();
}

/** Plain text only: keep a bounded window around the first word-start match. */
export function searchExcerpt(
  body: string,
  terms: string[],
  width = 240,
  title = "",
) {
  const heading = title.replace(/\s+/g, " ").trim();
  const source = body.trimStart();
  const lineEnd = source.indexOf("\n");
  const firstLine = lineEnd < 0 ? source : source.slice(0, lineEnd);
  // The displayed title strips inline formatting from the first line. Keep
  // link destinations in the excerpt even when their link text is the title.
  const titleLine =
    heading &&
    plainText(firstLine)
      .replace(/(?<!\\)\*{1,3}([^*]+)\*{1,3}/g, "$1")
      .replace(/(?<![\w\\])_{1,3}([^_]+)_{1,3}(?!\w)/g, "$1")
      .replace(/(?<!\\)`+([^`]+)`+/g, "$1")
      .replace(/(?<!\\)~~(.+?)~~/g, "$1");
  const strippedHeading = !!heading && titleLine === heading;
  const plain = plainText(
    strippedHeading ? (lineEnd < 0 ? "" : source.slice(lineEnd + 1)) : body,
  );
  const text =
    !strippedHeading &&
    heading &&
    (plain === heading || plain.startsWith(heading + " "))
      ? plain.slice(heading.length).trimStart()
      : plain;
  const [[match] = []] = searchMatchRanges(text, terms, true);
  let start =
    match !== undefined
      ? Math.max(
          0,
          Math.min(match - Math.floor(width / 3), text.length - width),
        )
      : 0;
  // Keep both ends on complete UTF-16 code points.
  if (start && /[\uDC00-\uDFFF]/.test(text[start])) start--;
  let end = Math.min(text.length, start + width);
  if (end < text.length && /[\uDC00-\uDFFF]/.test(text[end])) end--;
  return (
    (start ? "…" : "") + text.slice(start, end) + (end < text.length ? "…" : "")
  );
}
