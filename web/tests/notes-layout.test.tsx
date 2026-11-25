import { afterEach, expect, it, vi } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { NotesLayoutButton } from "../src/App";
import {
  followNotesLayout,
  notesLayoutKey,
  readNotesLayout,
  saveNotesLayout,
} from "../src/lib/notes-layout";

afterEach(() => {
  cleanup();
  localStorage.removeItem(notesLayoutKey);
  delete document.documentElement.dataset.notesLayout;
  vi.restoreAllMocks();
});

const button = () => screen.getByRole("button", { name: "Full width" });

it("switches the notes list between the column and full width and remembers it on this device", async () => {
  const user = userEvent.setup();
  followNotesLayout();
  expect(document.documentElement.dataset.notesLayout).toBe("column");
  render(<NotesLayoutButton />);
  expect(button().getAttribute("aria-pressed")).toBe("false");
  await user.click(button());
  expect(button().getAttribute("aria-pressed")).toBe("true");
  expect(document.documentElement.dataset.notesLayout).toBe("wide");
  expect(localStorage.getItem(notesLayoutKey)).toBe("wide");
  await user.click(button());
  expect(button().getAttribute("aria-pressed")).toBe("false");
  expect(readNotesLayout()).toBe("column");
});

it("restores the saved layout and follows a change made in another tab", async () => {
  localStorage.setItem(notesLayoutKey, "wide");
  followNotesLayout();
  render(<NotesLayoutButton />);
  expect(button().getAttribute("aria-pressed")).toBe("true");
  localStorage.setItem(notesLayoutKey, "column");
  await act(async () => {
    window.dispatchEvent(
      new StorageEvent("storage", { key: notesLayoutKey, newValue: "column" }),
    );
  });
  expect(document.documentElement.dataset.notesLayout).toBe("column");
  expect(button().getAttribute("aria-pressed")).toBe("false");
});

it("keeps the column by default and still switches when storage is unavailable", () => {
  vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
    throw new Error("blocked");
  });
  vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
    throw new Error("blocked");
  });
  expect(readNotesLayout()).toBe("column");
  saveNotesLayout("wide");
  expect(document.documentElement.dataset.notesLayout).toBe("wide");
});
