/** Small token highlighter only; rendered Markdown always comes sanitized from the server. */
export function caseFold(text: string) {
  return text
    .toLowerCase()
    .replaceAll("ß", "ss")
    .replaceAll("ς", "σ")
    .replaceAll("ſ", "s")
    .replace(
      /[ﬀ-ﬆ]/g,
      (char) =>
        ({ ﬀ: "ff", ﬁ: "fi", ﬂ: "fl", ﬃ: "ffi", ﬄ: "ffl", ﬅ: "st", ﬆ: "st" })[
          char
        ]!,
    );
}
export function fold(text: string) {
  return caseFold(text.normalize("NFKD").replace(/\p{M}/gu, ""));
}
export interface LabelToken {
  path: string;
  key: string;
  start: number;
  end: number;
}
function escaped(body: string, at: number) {
  let count = 0;
  while (at > 0 && body[--at] === "\\") count++;
  return count % 2 === 1;
}
export function codeMask(body: string) {
  const mask = new Uint8Array(body.length);
  let fence = "";
  let length = 0;
  let offset = 0;
  for (const line of body.match(/[^\n]*\n|[^\n]+$/g) ?? []) {
    const marker = /^ {0,3}(`{3,}|~{3,})([^\r\n]*)/.exec(line);
    if (fence) {
      mask.fill(1, offset, offset + line.length);
      if (
        marker &&
        marker[1]![0] === fence &&
        marker[1]!.length >= length &&
        !marker[2]!.trim()
      )
        fence = "";
    } else if (marker && (marker[1]![0] === "~" || !marker[2]!.includes("`"))) {
      fence = marker[1]![0]!;
      length = marker[1]!.length;
      mask.fill(1, offset, offset + line.length);
    }
    offset += line.length;
  }
  const runs = [...body.matchAll(/`+/g)];
  for (let i = 0; i < runs.length; i++) {
    const open = runs[i]!;
    if (mask[open.index] || escaped(body, open.index)) continue;
    for (let j = i + 1; j < runs.length; j++) {
      const close = runs[j]!;
      if (mask[close.index]) break;
      if (open[0].length === close[0].length) {
        mask.fill(1, open.index, close.index + close[0].length);
        i = j;
        break;
      }
    }
  }
  return mask;
}
export function labelTokens(body: string): LabelToken[] {
  const mask = codeMask(body);
  const result: LabelToken[] = [];
  for (let at = 0; at < body.length; at++) {
    if (
      body[at] !== "#" ||
      mask[at] ||
      (at > 0 && !/[\s([]/.test(body[at - 1]!))
    )
      continue;
    let end = at + 1;
    while (end < body.length && !mask[end]) {
      const char = String.fromCodePoint(body.codePointAt(end)!);
      if (!/[\p{L}\p{Nd}\p{M}_\-/]/u.test(char)) break;
      end += char.length;
    }
    const path = body.slice(at + 1, end).normalize("NFC");
    const segments = path.split("/");
    if (
      [...path].length <= 128 &&
      segments.length <= 5 &&
      !/^\p{Nd}+$/u.test(segments[0]!) &&
      segments.every((part) => !!part && /^[\p{L}\p{Nd}_-]+$/u.test(part))
    )
      result.push({ path, key: caseFold(path), start: at, end });
    at = end - 1;
  }
  return result;
}
export function toggleCheckbox(body: string, index: number) {
  if (!Number.isInteger(index) || index < 0) return body;
  const mask = codeMask(body);
  const matches = [
    ...body.matchAll(/^\s*(?:[-+*]|\d+[.)])\s+\[([ xX])\](?=\s)/gm),
  ].filter((match) => !mask[match.index]);
  const token = matches[index];
  if (!token) return body;
  const position = token.index + token[0].lastIndexOf("[") + 1;
  return (
    body.slice(0, position) +
    (token[1] === " " ? "x" : " ") +
    body.slice(position + 1)
  );
}
export type DiffLine = { kind: "same" | "add" | "del"; text: string };
/** Line diff (longest common subsequence) between two texts; very large changes fall back to remove-then-add. */
export function diffLines(before: string, after: string): DiffLine[] {
  const a = before.split("\n"),
    b = after.split("\n");
  let start = 0;
  while (start < a.length && start < b.length && a[start] === b[start]) start++;
  let end = 0;
  while (
    end < a.length - start &&
    end < b.length - start &&
    a[a.length - 1 - end] === b[b.length - 1 - end]
  )
    end++;
  const x = a.slice(start, a.length - end),
    y = b.slice(start, b.length - end);
  const same = (text: string): DiffLine => ({ kind: "same", text });
  const del = (text: string): DiffLine => ({ kind: "del", text });
  const add = (text: string): DiffLine => ({ kind: "add", text });
  const middle: DiffLine[] = [];
  if (x.length * y.length > 1_000_000)
    middle.push(...x.map(del), ...y.map(add));
  else {
    const table = Array.from(
      { length: x.length + 1 },
      () => new Uint32Array(y.length + 1),
    );
    for (let i = x.length - 1; i >= 0; i--)
      for (let j = y.length - 1; j >= 0; j--)
        table[i]![j] =
          x[i] === y[j]
            ? table[i + 1]![j + 1]! + 1
            : Math.max(table[i + 1]![j]!, table[i]![j + 1]!);
    let i = 0,
      j = 0;
    while (i < x.length && j < y.length)
      if (x[i] === y[j]) (middle.push(same(x[i++]!)), j++);
      else if (table[i + 1]![j]! >= table[i]![j + 1]!)
        middle.push(del(x[i++]!));
      else middle.push(add(y[j++]!));
    middle.push(...x.slice(i).map(del), ...y.slice(j).map(add));
  }
  return [
    ...a.slice(0, start).map(same),
    ...middle,
    ...a.slice(a.length - end).map(same),
  ];
}
/** Keeps `context` unchanged lines around each change; longer unchanged runs become one count. */
export function compactDiff(lines: DiffLine[], context = 2) {
  const near = lines.map((_, index) =>
    lines
      .slice(Math.max(0, index - context), index + context + 1)
      .some((line) => line.kind !== "same"),
  );
  const result: (DiffLine | { kind: "skip"; count: number })[] = [];
  lines.forEach((line, index) => {
    if (line.kind !== "same" || near[index]) result.push(line);
    else if (result.at(-1)?.kind === "skip")
      (result.at(-1) as { count: number }).count++;
    else result.push({ kind: "skip", count: 1 });
  });
  return result;
}
