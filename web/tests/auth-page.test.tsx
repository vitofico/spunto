import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import AuthPage from "../src/pages/Auth";
import { api } from "../src/lib/api";

const auth = vi.hoisted(() => ({
  login: vi.fn().mockResolvedValue(undefined),
  register: vi.fn().mockResolvedValue(undefined),
  setup: vi.fn().mockResolvedValue(undefined),
  error: null,
}));
vi.mock("../src/lib", () => ({ useAuth: () => auth }));
beforeEach(() => {
  window.history.replaceState({}, "", "/login");
  vi.spyOn(api, "authOptions").mockResolvedValue({ mode: "local" });
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.restoreAllMocks();
});
it("explains the existing password-only owner login and submits only that password", async () => {
  render(<AuthPage />);
  expect(
    await screen.findByRole("heading", { name: "Unlock your Spunto" }),
  ).toBeTruthy();
  expect(screen.getByText(/existing owner password/)).toBeTruthy();
  expect(screen.getByText(/There is no username to enter/)).toBeTruthy();
  expect(screen.queryByRole("textbox")).toBeNull();
  const password = screen.getByLabelText("Password");
  expect(password.getAttribute("autocomplete")).toBe("current-password");
  const user = userEvent.setup();
  await user.type(password, "correct horse battery");
  await user.click(screen.getByRole("button", { name: "Sign in" }));
  expect(auth.login).toHaveBeenCalledExactlyOnceWith("correct horse battery");
  expect(auth.setup).not.toHaveBeenCalled();
});
it("keeps first-time setup distinct from unlocking an existing home", async () => {
  render(<AuthPage setup />);
  expect(
    screen.queryByRole("heading", { name: "Unlock your Spunto" }),
  ).toBeNull();
  expect(screen.getByText(/setup code printed in the server log/)).toBeTruthy();
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("Setup code"), "fixture-code");
  await user.type(
    screen.getByLabelText("Choose your password"),
    "correct horse battery",
  );
  await user.click(screen.getByRole("button", { name: "Create your home" }));
  expect(auth.setup).toHaveBeenCalledExactlyOnceWith(
    "fixture-code",
    "correct horse battery",
  );
  expect(auth.login).not.toHaveBeenCalled();
});

it("uses the browser SSO flow without a password or provider secret", async () => {
  vi.mocked(api.authOptions).mockResolvedValue({
    mode: "oidc",
    login_url: "/api/v1/auth/oidc/start",
  });
  render(<AuthPage />);
  const link = await screen.findByRole("link", { name: "Continue with SSO" });
  expect(link.getAttribute("href")).toBe("/api/v1/auth/oidc/start");
  await waitFor(() => expect(screen.queryByLabelText("Password")).toBeNull());
  expect(auth.login).not.toHaveBeenCalled();
});

it("supports standalone hosted username/password with no provider or registration", async () => {
  vi.mocked(api.authOptions).mockResolvedValue({
    mode: "hosted",
    password_enabled: true,
    registration_enabled: false,
    oidc_enabled: false,
  });
  render(<AuthPage />);
  const username = await screen.findByLabelText("Username");
  expect(screen.queryByRole("link", { name: /Continue with/ })).toBeNull();
  expect(
    screen.queryByRole("button", { name: "Create an account" }),
  ).toBeNull();
  const user = userEvent.setup();
  await user.type(username, "Alice.1");
  await user.type(screen.getByLabelText("Password"), "safe passphrase");
  await user.click(screen.getByRole("button", { name: "Sign in" }));
  expect(auth.login).toHaveBeenCalledExactlyOnceWith(
    "safe passphrase",
    "Alice.1",
  );
  expect(auth.register).not.toHaveBeenCalled();
});

it("offers optional configured SSO alongside hosted password sign-in", async () => {
  vi.mocked(api.authOptions).mockResolvedValue({
    mode: "hosted",
    password_enabled: true,
    registration_enabled: false,
    oidc_enabled: true,
    label: "Authentik",
    login_url: "/api/v1/auth/oidc/start",
  });
  render(<AuthPage />);
  expect(await screen.findByLabelText("Username")).toBeTruthy();
  expect(screen.getByLabelText("Password")).toBeTruthy();
  expect(
    screen
      .getByRole("link", { name: "Continue with Authentik" })
      .getAttribute("href"),
  ).toBe("/api/v1/auth/oidc/start");
});

it("registers only when enabled, confirms the password and never asks for email", async () => {
  vi.mocked(api.authOptions).mockResolvedValue({
    mode: "hosted",
    password_enabled: true,
    registration_enabled: true,
    oidc_enabled: false,
  });
  render(<AuthPage />);
  const user = userEvent.setup();
  await user.click(
    await screen.findByRole("button", { name: "Create an account" }),
  );
  await user.type(screen.getByLabelText("Username"), "New_person");
  await user.type(screen.getByLabelText("Password"), "new passphrase");
  await user.type(
    screen.getByLabelText("Confirm password"),
    "wrong passphrase",
  );
  await user.click(screen.getByRole("button", { name: "Create account" }));
  expect(screen.getByRole("alert").textContent).toBe(
    "The passwords do not match.",
  );
  expect(auth.register).not.toHaveBeenCalled();
  await user.clear(screen.getByLabelText("Confirm password"));
  await user.type(screen.getByLabelText("Confirm password"), "new passphrase");
  expect(screen.queryByLabelText(/email/i)).toBeNull();
  await user.click(screen.getByRole("button", { name: "Create account" }));
  expect(auth.register).toHaveBeenCalledExactlyOnceWith(
    "New_person",
    "new passphrase",
  );
});

it("does not turn a signup URL into permission to register", async () => {
  window.history.replaceState({}, "", "/login?register=1");
  vi.mocked(api.authOptions).mockResolvedValue({
    mode: "hosted",
    password_enabled: true,
    registration_enabled: false,
    oidc_enabled: false,
  });
  render(<AuthPage />);
  await screen.findByText(/Username\/password signup is disabled/);
  expect(screen.queryByLabelText("Confirm password")).toBeNull();
  expect(screen.getByRole("button", { name: "Sign in" })).toBeTruthy();
});

it("waits for trustworthy options and can retry instead of guessing password-only mode", async () => {
  vi.mocked(api.authOptions).mockRejectedValueOnce(new Error("offline"));
  render(<AuthPage />);
  await screen.findByRole("alert");
  expect(screen.queryByLabelText("Password")).toBeNull();
  vi.mocked(api.authOptions).mockResolvedValue({
    mode: "hosted",
    password_enabled: true,
    oidc_enabled: false,
  });
  await userEvent
    .setup()
    .click(screen.getByRole("button", { name: "Try again" }));
  expect(await screen.findByLabelText("Username")).toBeTruthy();
});
