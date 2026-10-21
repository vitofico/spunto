import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import {
  FirstRunGuide,
  GUIDE_COMPLETED_KEY,
  hasCompletedGuide,
  markGuideCompleted,
} from "../src/components/core/FirstRunGuide";

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.restoreAllMocks();
});

it("teaches four steps with static marked examples and focuses each heading", () => {
  const finish = vi.fn();
  render(<FirstRunGuide onSkip={vi.fn()} onFinish={finish} />);
  expect(document.activeElement).toBe(
    screen.getByRole("heading", { level: 1 }),
  );
  expect(
    screen.getByRole("button", { name: "Back" }).hasAttribute("disabled"),
  ).toBe(true);
  expect(screen.getByText("Example")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Next" }));
  expect(screen.getByRole("heading").textContent).toBe(
    "Labels live in your words.",
  );
  fireEvent.click(screen.getByRole("button", { name: "Back" }));
  expect(screen.getByRole("heading").textContent).toBe(
    "A quiet place for loose ideas.",
  );
  for (let step = 0; step < 3; step++) {
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(document.activeElement).toBe(screen.getByRole("heading"));
  }
  expect(screen.getByText(/Android keeps a local copy/)).toBeTruthy();
  expect(screen.queryByRole("textbox")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Start writing" }));
  expect(finish).toHaveBeenCalledOnce();
});

it("leaves persistence to the host and exposes an explicit Skip action", () => {
  const skip = vi.fn();
  render(<FirstRunGuide onSkip={skip} onFinish={vi.fn()} />);
  fireEvent.click(screen.getByRole("button", { name: "Skip" }));
  expect(skip).toHaveBeenCalledOnce();
  expect(hasCompletedGuide()).toBe(false);
});

it("skips on Escape", () => {
  const skip = vi.fn();
  render(<FirstRunGuide onSkip={skip} onFinish={vi.fn()} />);
  fireEvent.keyDown(document, { key: "Escape" });
  expect(skip).toHaveBeenCalledOnce();
});

it("stores only a browser-wide completion marker", () => {
  expect(hasCompletedGuide()).toBe(false);
  markGuideCompleted();
  expect(hasCompletedGuide()).toBe(true);
  expect(localStorage.getItem(GUIDE_COMPLETED_KEY)).toBe("complete");
  expect(localStorage.length).toBe(1);
});

it("handles unavailable browser storage without throwing", () => {
  vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
    throw new DOMException("Unavailable", "SecurityError");
  });
  vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
    throw new DOMException("Unavailable", "SecurityError");
  });
  expect(hasCompletedGuide()).toBe(false);
  expect(() => markGuideCompleted()).not.toThrow();
});
