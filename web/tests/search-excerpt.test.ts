import { expect, it } from "vitest";
import { searchExcerpt } from "../src/lib/searchExcerpt";

it("centres a bounded excerpt on a buried folded word-start match", () => {
  const body =
    "Opening text ".repeat(80) +
    "Perché la Straße? " +
    "Closing text ".repeat(80);
  const result = searchExcerpt(body, ["perche", "strasse"], 160);
  expect(result).toContain("Perché la Straße");
  expect(result.startsWith("…") && result.endsWith("…")).toBe(true);
  expect(result.length).toBeLessThanOrEqual(162);
});

it("does not centre inside another word and displays Markdown as plain text", () => {
  const result = searchExcerpt(
    "unneedle ".repeat(40) + "needle [label](https://example.org) end",
    ["needle"],
    80,
  );
  expect(result).toContain("needle label");
  expect(result).toContain("https://example.org");
  expect(
    searchExcerpt("# Title\n- [ ] Task\n" + "body ".repeat(100), [], 60),
  ).toHaveLength(61);
});

it("never clips a surrogate pair at either excerpt edge", () => {
  const result = searchExcerpt(
    "😀".repeat(40) + " needle " + "😀".repeat(40),
    ["needle"],
    21,
  );
  expect(result).toContain("needle");
  expect(result).not.toMatch(
    /(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]|[\uD800-\uDBFF](?![\uDC00-\uDFFF])/u,
  );
});

it("retains link destinations when they explain a search match", () => {
  const body =
    "Opening ".repeat(80) +
    "[Reference](https://example.org/needle) " +
    "Closing ".repeat(80);
  expect(searchExcerpt(body, ["needle"], 90)).toContain("example.org/needle");
});
