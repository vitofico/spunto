import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { DecisionRationale } from "../src/features/DecisionJournal";
import { supportsDecisionCapture } from "../src/features/decisionJournalData";
import type { Note, Plugin } from "../src/lib/types";

afterEach(cleanup);

it("offers composer decisions only for the supporting bundled journal", () => {
  const plugin = {
    id: "decision-journal",
    version: "1.2.0",
    origin: "bundled",
    contributes: { commands: [{ id: "record-decision" }] },
  } as Plugin;
  expect(supportsDecisionCapture(plugin)).toBe(true);
  expect(supportsDecisionCapture(undefined)).toBe(false);
  expect(supportsDecisionCapture({ ...plugin, version: "1.1.3" })).toBe(false);
  expect(supportsDecisionCapture({ ...plugin, origin: "operator" })).toBe(
    false,
  );
  expect(supportsDecisionCapture({ ...plugin, contributes: {} })).toBe(false);
});

it("displays all rationale fragments for a journal member as plain text", () => {
  const reason = "<em>Keep this literal.</em>";
  const saved = {
    annotations: [
      { plugin: "decision-journal", kind: "member:journal", payload: {} },
      {
        plugin: "decision-journal",
        kind: "panel",
        payload: {
          ui: 1,
          items: [
            { type: "command", id: "journal", title: "Open decision journal" },
            { type: "text", text: "x".repeat(280) },
            { type: "text", text: reason },
          ],
        },
      },
    ],
  } as Note;
  const { container, rerender } = render(<DecisionRationale note={saved} />);
  expect(screen.getByText("x".repeat(280) + reason)).toBeTruthy();
  expect(container.querySelector("em")).toBeNull();
  rerender(
    <DecisionRationale
      note={{ ...saved, annotations: saved.annotations.slice(1) }}
    />,
  );
  expect(container.textContent).toBe("");
});
