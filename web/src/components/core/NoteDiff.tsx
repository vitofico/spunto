import { Fragment, useMemo } from "react";
import { t, useLanguage } from "../../lib/i18n";
import { compactDiff, diffLines, type DiffLine } from "../../lib/text";

type Change = {
  kind: "change";
  before: string[];
  after: string[];
  inline?: { before: Map<number, DiffLine[]>; after: Map<number, DiffLine[]> };
};

const word = /[\p{L}\p{N}]/u;
const words = (parts: string[]) =>
  parts.filter((part) => word.test(part)).length;
const tokens = (line: string) =>
  line.length <= 1_200
    ? (line.match(/\s+|[\p{L}\p{N}\p{M}_]+|\S/gu) ?? [])
    : [];

function comparison(before: string, after: string) {
  const rows: (
    { kind: "same"; text: string } | { kind: "skip"; count: number } | Change
  )[] = [];
  let change: Change | undefined;
  for (const line of compactDiff(diffLines(before, after))) {
    if (line.kind === "skip" || line.kind === "same") {
      rows.push(
        line.kind === "skip" ? line : { kind: "same", text: line.text },
      );
      change = undefined;
    } else {
      if (!change) {
        change = { kind: "change", before: [], after: [] };
        rows.push(change);
      }
      change[line.kind === "del" ? "before" : "after"].push(line.text);
    }
  }

  let remaining = 150_000;
  for (const row of rows) {
    if (
      row.kind !== "change" ||
      row.before.length > 12 ||
      row.after.length > 12
    )
      continue;
    const left = row.before.map(tokens);
    const right = row.after.map(tokens);
    const single = left.length === 1 && right.length === 1;
    const choices: {
      left: number;
      right: number;
      score: number;
      diff: DiffLine[];
    }[] = [];
    for (const [i, a] of left.entries()) {
      for (const [j, b] of right.entries()) {
        const cost = a.length * b.length;
        if (cost === 0 || cost > 4_096 || cost > remaining) continue;
        remaining -= cost;
        const diff = diffLines(a.join("\n"), b.join("\n"));
        const shared = diff.filter(
          (part) => part.kind === "same" && word.test(part.text),
        ).length;
        if (shared === 0) continue;
        const score = (2 * shared) / (words(a) + words(b));
        if (single || (shared >= 2 && score >= 0.55))
          choices.push({ left: i, right: j, score, diff });
      }
    }

    const paired = {
      before: new Map<number, DiffLine[]>(),
      after: new Map<number, DiffLine[]>(),
    };
    let rightmost = -1;
    for (const choice of choices) {
      // Both lines must prefer this match, and pairs follow the excerpt order.
      if (
        choice.right <= rightmost ||
        choices.some(
          (other) =>
            other !== choice &&
            (other.left === choice.left || other.right === choice.right) &&
            other.score >= choice.score,
        )
      )
        continue;
      rightmost = choice.right;
      paired.before.set(
        choice.left,
        choice.diff.filter((part) => part.kind !== "add"),
      );
      paired.after.set(
        choice.right,
        choice.diff.filter((part) => part.kind !== "del"),
      );
    }
    if (paired.before.size) row.inline = paired;
  }
  return rows;
}

function InlineDiff({ parts }: { parts: DiffLine[] }) {
  const runs: { changed: boolean; text: string }[] = [];
  for (const part of parts) {
    const changed = part.kind !== "same";
    const previous = runs.at(-1);
    if (
      previous?.changed === changed &&
      /^\s+$/u.test(previous.text) === /^\s+$/u.test(part.text)
    ) {
      previous.text += part.text;
    } else {
      runs.push({ changed, text: part.text });
    }
  }
  return runs.map((part, index) => {
    if (!part.changed) return part.text;
    const spacing = /^\s+$/u.test(part.text)
      ? part.text
          .replace(/\t/g, "→")
          .replace(/\r/g, "␍")
          .replace(/[^→␍]/gu, "·")
      : undefined;
    return (
      <strong key={index} className="note-diff-phrase" data-spacing={spacing}>
        {part.text}
      </strong>
    );
  });
}

function ChangeText({
  lines,
  emphasized,
}: {
  lines: string[];
  emphasized?: Map<number, DiffLine[]>;
}) {
  const text = emphasized
    ? lines.map((line, index) => {
        const parts = emphasized.get(index);
        return (
          <Fragment key={index}>
            {index > 0 && "\n"}
            {parts ? <InlineDiff parts={parts} /> : line}
          </Fragment>
        );
      })
    : lines.join("\n");
  return (
    <>
      {text}
      {lines.at(-1) === "" && <span className="note-diff-blank" />}
    </>
  );
}

export function NoteDiff({
  before,
  after,
  labelledBy,
  labels,
}: {
  before: string;
  after: string;
  labelledBy?: string;
  labels?: { before: string; after: string };
}) {
  useLanguage();
  const rows = useMemo(() => comparison(before, after), [before, after]);
  return (
    <div
      className="diff note-diff"
      role={labelledBy ? "region" : undefined}
      aria-labelledby={labelledBy}
      tabIndex={labelledBy ? 0 : undefined}
    >
      {rows.map((row, index) => {
        if (row.kind === "skip")
          return (
            <div key={index} className="diff-skip">
              {t("note.unchanged_lines", { count: row.count })}
            </div>
          );
        if (row.kind === "same")
          return <div key={index}>{row.text || " "}</div>;
        return (
          <div className="note-diff-group" key={index}>
            {row.before.length > 0 && (
              <del>
                <span className="note-diff-label">
                  <span aria-hidden="true">−</span>{" "}
                  {labels?.before ?? t("note.removed")}
                </span>
                <ChangeText
                  lines={row.before}
                  emphasized={row.inline?.before}
                />
              </del>
            )}
            {row.after.length > 0 && (
              <ins>
                <span className="note-diff-label">
                  <span aria-hidden="true">+</span>{" "}
                  {labels?.after ?? t("note.added")}
                </span>
                <ChangeText lines={row.after} emphasized={row.inline?.after} />
              </ins>
            )}
          </div>
        );
      })}
    </div>
  );
}
