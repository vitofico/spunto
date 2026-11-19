import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { NoteDiff } from "../src/components/core/NoteDiff";

it("labels the two excerpts beside their changed words", () => {
  render(
    <>
      <h2 id="changes">Agent changes</h2>
      <NoteDiff
        before="Ship the beta on Monday before noon."
        after="Ship the final on Friday before noon."
        labelledBy="changes"
        labels={{ before: "Before v2", after: "After v3" }}
      />
    </>,
  );
  const comparison = screen.getByRole("region", { name: "Agent changes" });
  const before = comparison.querySelector("del");
  const after = comparison.querySelector("ins");
  expect(before?.textContent).toContain("Before v2");
  expect(after?.textContent).toContain("After v3");
  expect(
    [...before!.querySelectorAll("strong")].map((part) => part.textContent),
  ).toEqual(["beta", "Monday"]);
  expect(
    [...after!.querySelectorAll("strong")].map((part) => part.textContent),
  ).toEqual(["final", "Friday"]);
});

it("makes whitespace visible and leaves multiline replacements intact", () => {
  const { container, rerender } = render(
    <NoteDiff before="Keep    this line" after="Keep this line" />,
  );
  expect(
    container.querySelector("del [data-spacing]")?.getAttribute("data-spacing"),
  ).toBe("····");
  expect(container.querySelector("del [data-spacing]")?.textContent).toBe(
    "    ",
  );
  rerender(
    <NoteDiff
      before={"First paragraph\nSecond paragraph"}
      after={"Replacement\nNew section"}
    />,
  );
  expect(container.querySelector("del")?.textContent).toContain(
    "First paragraph\nSecond paragraph",
  );
  expect(container.querySelector("ins")?.textContent).toContain(
    "Replacement\nNew section",
  );
  expect(container.querySelectorAll("strong")).toHaveLength(0);
});

it.each(["Bring ID.\nToday at four.\nGate", "Today at four.\nBring ID.\nGate"])(
  "pairs related changed lines alongside a separate insertion in %j",
  (after) => {
    const { container } = render(
      <NoteDiff before={"Today at three.\nGate"} after={after} />,
    );
    expect(container.querySelector("del strong")?.textContent).toBe("three");
    expect(container.querySelector("ins strong")?.textContent).toBe("four");
    expect(container.querySelector("ins")?.textContent).toContain(
      after.split("\n").slice(0, 2).join("\n"),
    );
  },
);

it("renders a maximum-length newline-heavy rewrite in two bounded excerpts", () => {
  const breaks = "\n".repeat(99_998);
  const before = "a" + breaks + "b";
  const after = "c" + breaks + "d";
  const { container } = render(<NoteDiff before={before} after={after} />);
  expect(container.querySelectorAll(".note-diff-group")).toHaveLength(1);
  expect(container.querySelector("del")?.textContent?.endsWith(before)).toBe(
    true,
  );
  expect(container.querySelector("ins")?.textContent?.endsWith(after)).toBe(
    true,
  );
  expect(container.querySelectorAll(".note-diff *").length).toBeLessThan(10);
});
