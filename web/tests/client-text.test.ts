import { describe, expect, it } from "vitest";
import labels from "../../spec/fixtures/labels.json";
import folds from "../../spec/fixtures/fold.json";
import { fold, labelTokens, toggleCheckbox } from "../src/lib/text";
import { problem, safeError } from "../src/lib/errors";
describe("shared text fixtures", () => {
  for (const fixture of labels)
    it(`labels: ${fixture.body}`, () => {
      const tokens = labelTokens(fixture.body);
      expect(tokens.map((t) => t.path)).toEqual(fixture.paths);
      expect(tokens.map((t) => t.key)).toEqual(fixture.keys);
    });
  for (const fixture of folds)
    it(`fold: ${fixture.text}`, () =>
      expect(fold(fixture.text)).toBe(fixture.folded));
  it("toggles actual task lines and leaves fenced examples alone", () => {
    const body = "```\n- [ ] example\n```\n- [ ] one\n1. [X] two";
    expect(toggleCheckbox(body, 0)).toBe(
      "```\n- [ ] example\n```\n- [x] one\n1. [X] two",
    );
    expect(toggleCheckbox(body, 1)).toBe(
      "```\n- [ ] example\n```\n- [ ] one\n1. [ ] two",
    );
  });
});
it("sanitizes all untrusted server details and browser errors", () => {
  const secret = "note body password provider secret";
  expect(problem(500, { detail: secret, code: secret }).message).not.toContain(
    secret,
  );
  expect(safeError(new Error(secret)).message).not.toContain(secret);
});
