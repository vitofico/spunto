import { afterEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react";
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

it("lists real settings destinations, identifies the current one and resets the chooser across sections", () => {
  const { rerender } = render(<SettingsPage section="ai" />);
  const nav = screen.getByRole("navigation", { name: "Settings" });
  const chooser = within(nav).getByRole("button", { name: "Browse settings" });
  expect(chooser.getAttribute("aria-expanded")).toBe("false");
  fireEvent.click(chooser);
  expect(chooser.getAttribute("aria-expanded")).toBe("true");
  expect(
    within(nav).getByRole("link", { name: "AI", current: "page" }),
  ).toBeTruthy();
  expect(
    within(nav).getByRole("link", { name: "Data" }).getAttribute("href"),
  ).toBe("/settings/data");
  expect(within(nav).queryByRole("link", { name: "Spaces" })).toBeNull();
  fireEvent.keyDown(nav, { key: "Escape" });
  expect(chooser.getAttribute("aria-expanded")).toBe("false");
  fireEvent.click(chooser);
  rerender(<SettingsPage section="profile" />);
  expect(chooser.getAttribute("aria-expanded")).toBe("false");
  expect(
    within(nav).getByRole("link", { name: "Profile", current: "page" }),
  ).toBeTruthy();
});

it("keeps a newly released update unread until the page is opened and remembers it after remount", () => {
  const key = "spunto:whats-new-seen";
  localStorage.setItem(key, "2026-10-plain-background");
  try {
    const { rerender, unmount } = render(<SettingsPage section="profile" />);
    expect(
      screen
        .getByRole("link", {
          name: "What’s new",
          description: "Unread updates",
        })
        .getAttribute("href"),
    ).toBe("/settings/whats-new");
    expect(localStorage.getItem(key)).toBe("2026-10-plain-background");

    rerender(<SettingsPage section="whats-new" />);
    expect(screen.getByRole("heading", { name: "What’s new" })).toBeTruthy();
    expect(
      screen
        .getByRole("link", { name: "Open Appearance" })
        .getAttribute("href"),
    ).toBe("/settings/appearance");
    expect(
      screen.getByRole("heading", {
        name: "Review agent edits and find the right note tools",
      }),
    ).toBeTruthy();
    expect(localStorage.getItem(key)).toBe("2026-11-note-tools");
    unmount();

    render(<SettingsPage section="profile" />);
    expect(screen.getByRole("link", { name: "What’s new" })).toBeTruthy();
    expect(
      screen.queryByRole("link", {
        name: "What’s new",
        description: "Unread updates",
      }),
    ).toBeNull();
  } finally {
    localStorage.removeItem(key);
  }
});
