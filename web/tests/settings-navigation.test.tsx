import { afterEach, expect, it, vi } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import { SettingsPage } from "../src/features/Settings";

vi.mock("../src/features/AISettings", () => ({
  AISettings: () => <h1>AI settings</h1>,
  AIRouting: () => null,
  AIHealthResults: () => null,
}));
vi.mock("../src/features/ProfilePanel", () => ({
  ProfilePanel: () => <h1>Profile settings</h1>,
}));
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("reveals the active settings tab on section changes and resize without moving page or focus", () => {
  let width = 300;
  vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockImplementation(
    function (this: HTMLElement) {
      return this.tagName === "NAV" ? width : 0;
    },
  );
  vi.spyOn(HTMLElement.prototype, "scrollWidth", "get").mockReturnValue(900);
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(
    function (this: HTMLElement) {
      if (this.tagName === "NAV") return new DOMRect(0, 80, width, 44);
      const offset = this.parentElement?.scrollLeft ?? 0;
      const left = this.textContent === "AI" ? 600 : 100;
      return new DOMRect(left - offset, 80, 50, 44);
    },
  );
  const scrollPage = vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  const focusTarget = document.createElement("button");
  document.body.append(focusTarget);
  focusTarget.focus();
  const { rerender } = render(<SettingsPage section="ai" />);
  const nav = screen.getByRole("navigation", { name: "Settings" });
  expect(nav.scrollLeft).toBeGreaterThan(0);
  expect(
    screen.getByRole("link", { name: "AI" }).getBoundingClientRect().right,
  ).toBeLessThanOrEqual(width);
  const previousOffset = nav.scrollLeft;
  width = 200;
  act(() => window.dispatchEvent(new Event("resize")));
  expect(nav.scrollLeft).toBeGreaterThan(previousOffset);
  expect(
    screen.getByRole("link", { name: "AI" }).getBoundingClientRect().right,
  ).toBeLessThanOrEqual(width);
  rerender(<SettingsPage section="profile" />);
  const profile = screen.getByRole("link", { name: "Profile" });
  expect(profile.getBoundingClientRect().left).toBeGreaterThanOrEqual(0);
  expect(profile.getBoundingClientRect().right).toBeLessThanOrEqual(width);
  expect(document.activeElement).toBe(focusTarget);
  expect(nav.scrollTop).toBe(0);
  expect(scrollPage).not.toHaveBeenCalled();
  focusTarget.remove();
});
