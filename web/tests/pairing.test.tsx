import { afterEach, beforeEach, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from "@testing-library/react";
import { api, type PairingCode } from "../src/lib/api";
import { PairDevice, pairingPayload } from "../src/features/PairDevice";
import * as clipboard from "../src/features/copy";

const pairing: PairingCode = {
  code: "synthetic-pairing-code-for-ui-only",
  public_url: "https://notes.example.test/caffè",
  expires_at: "2026-10-14T13:15:00Z",
};
beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-10-14T13:10:00Z"));
  HTMLDialogElement.prototype.showModal = function () {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function () {
    this.open = false;
  };
  vi.spyOn(api, "createPairing").mockResolvedValue(pairing);
  vi.spyOn(clipboard, "copyText").mockResolvedValue(true);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});
async function open() {
  await act(async () => {
    fireEvent.click(
      screen.getByRole("button", { name: "Pair an Android device" }),
    );
  });
}
it("creates only on request, renders a local QR and copies the interoperable JSON payload", async () => {
  render(<PairDevice />);
  expect(api.createPairing).not.toHaveBeenCalled();
  await open();
  expect(
    screen.getByRole("img", { name: "Android pairing code" }),
  ).toBeTruthy();
  const encoded = pairingPayload(pairing);
  expect(JSON.parse(encoded)).toEqual({
    server: pairing.public_url,
    code: pairing.code,
  });
  expect(encoded).toMatch(/^[\x20-\x7e]*$/);
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Copy pairing code" }));
  });
  expect(clipboard.copyText).toHaveBeenCalledWith(encoded);
  expect(localStorage.getItem("spunto-pairing")).toBeNull();
});
it("removes expired QR and copy controls and permits a fresh code", async () => {
  render(<PairDevice />);
  await open();
  await act(async () => {
    vi.advanceTimersByTime(300000);
  });
  expect(
    screen.queryByRole("img", { name: "Android pairing code" }),
  ).toBeNull();
  expect(
    screen.queryByRole("button", { name: "Copy pairing code" }),
  ).toBeNull();
  vi.mocked(api.createPairing).mockResolvedValue({
    ...pairing,
    expires_at: "2026-10-14T13:20:00Z",
  });
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Create a new code" }));
  });
  expect(
    screen.getByRole("img", { name: "Android pairing code" }),
  ).toBeTruthy();
});
it("closing an expired code also cancels a pending replacement dialog", async () => {
  render(<PairDevice />);
  await open();
  await act(async () => {
    vi.advanceTimersByTime(300000);
  });
  let finish!: (value: PairingCode) => void;
  vi.mocked(api.createPairing).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve;
    }),
  );
  fireEvent.click(screen.getByRole("button", { name: "Create a new code" }));
  fireEvent.click(screen.getByRole("button", { name: "Close" }));
  await act(async () => {
    finish({ ...pairing, expires_at: "2026-10-14T13:20:00Z" });
  });
  expect(screen.queryByRole("dialog")).toBeNull();
});
it("shows a recoverable API failure without storing or displaying a code", async () => {
  vi.mocked(api.createPairing).mockRejectedValue(
    new Error("Please sign in again."),
  );
  render(<PairDevice />);
  await open();
  expect(screen.getByRole("alert").textContent).toBe("Please sign in again.");
  expect(screen.queryByRole("img")).toBeNull();
});
