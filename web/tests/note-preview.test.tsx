import { afterEach, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { NoteBody } from "../src/components/core/NoteCard";
import type { Note } from "../src/lib/types";
afterEach(cleanup);
const preview = (
  title: string,
  html: string,
  body: string,
  labels: string[] = [],
) => render(<NoteBody preview note={{ title, html, body, labels } as Note} />);
it("keeps the original text when its display title is abbreviated", () => {
  preview(
    "A longer thought",
    "<p>A longer thought that must remain complete</p>",
    "A longer thought that must remain complete",
  );
  expect(
    screen.getByText("A longer thought that must remain complete"),
  ).toBeTruthy();
});
it("retains code that resembles a label while moving real terminal labels to pills", () => {
  const view = preview(
    "Code",
    "<p>Code</p><p><code>#ideas</code></p><p>#ideas</p>",
    "Code\n\n`#ideas`\n\n#ideas",
    ["ideas"],
  );
  expect(view.container.querySelector("code")?.textContent).toBe("#ideas");
  expect(view.container.querySelectorAll("p").length).toBe(1);
});
it("preserves original checkbox source offsets after the preview title is removed", () => {
  const view = preview(
    "Weekend plans",
    '<h2>Weekend plans</h2><ul><li><input type="checkbox" data-checkbox-offset="20"> Book a train</li></ul>',
    "Weekend plans\n\n- [ ] Book a train",
  );
  expect(
    view.container.querySelector("input")?.getAttribute("data-checkbox-offset"),
  ).toBe("20");
  expect(screen.getByText("Book a train")).toBeTruthy();
});
