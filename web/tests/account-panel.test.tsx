import { afterEach, expect, it, vi } from "vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AccountPanel } from "../src/features/AccountPanel";
import { api } from "../src/lib/api";
import * as auth from "../src/lib/query";
import type { AccountSecurity } from "../src/lib/types";

const clients: QueryClient[] = [];
const initial: AccountSecurity = {
  local_username: null,
  local_password_set: false,
  oidc_linked: true,
  reauthenticated: false,
  reauth_url: "/api/v1/auth/oidc/start?reauth=local-password",
};
afterEach(() => {
  cleanup();
  clients.splice(0).forEach((client) => client.clear());
  vi.restoreAllMocks();
});
function fixture(security = initial, oidc = true) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  clients.push(client);
  client.setQueryData(["session"], {
    owner_id: "existing-account",
    user_id: "existing-account",
    workspace_id: "same-library",
    credential_id: "old-cookie",
  });
  let stored = security;
  vi.spyOn(api, "accountSecurity").mockImplementation(async () => stored);
  vi.spyOn(api, "authOptions").mockResolvedValue({
    mode: "hosted",
    password_enabled: true,
    oidc_enabled: oidc,
    label: "Authentik",
    registration_enabled: false,
  });
  vi.spyOn(auth, "currentOwner").mockReturnValue("existing-account-scope");
  const refresh = vi.fn().mockResolvedValue(undefined);
  vi.spyOn(auth, "useAuth").mockReturnValue({
    refresh,
  } as unknown as ReturnType<typeof auth.useAuth>);
  const register = vi.spyOn(api, "register").mockResolvedValue(undefined);
  const save = vi
    .spyOn(api, "saveAccountPassword")
    .mockImplementation(async (body) => {
      stored = {
        ...stored,
        local_username: body.username ?? stored.local_username,
        local_password_set: true,
        reauthenticated: false,
      };
    });
  const view = render(
    <QueryClientProvider client={client}>
      <AccountPanel />
    </QueryClientProvider>,
  );
  return { client, refresh, register, save, unmount: view.unmount };
}

it("requires fresh provider verification before adding the first password to an SSO account", async () => {
  const { save } = fixture();
  const verify = await screen.findByRole("link", { name: "Verify with SSO" });
  expect(verify.getAttribute("href")).toBe(initial.reauth_url);
  expect(screen.queryByLabelText("New password")).toBeNull();
  expect(save).not.toHaveBeenCalled();
});

it("adds local credentials to the existing account and rehydrates its session instead of registering", async () => {
  const { client, refresh, register, save } = fixture({
    ...initial,
    reauthenticated: true,
  });
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText("Username"), "Vito-local");
  expect(screen.queryByLabelText("Current password")).toBeNull();
  await user.type(screen.getByLabelText("New password"), "new safe passphrase");
  await user.type(
    screen.getByLabelText("Confirm new password"),
    "new safe passphrase",
  );
  await user.click(
    screen.getByRole("button", { name: "Add password sign-in" }),
  );
  await screen.findByText("Sign-in settings saved.");
  expect(save).toHaveBeenCalledExactlyOnceWith({
    username: "Vito-local",
    new_password: "new safe passphrase",
  });
  expect(refresh).toHaveBeenCalledOnce();
  expect(register).not.toHaveBeenCalled();
  expect(client.getQueryData(["session"])).toMatchObject({
    owner_id: "existing-account",
    workspace_id: "same-library",
  });
  expect(
    (screen.getByLabelText("New password") as HTMLInputElement).value,
  ).toBe("");
});

it("requires the current password for changes even after recent authentication", async () => {
  const { save, refresh } = fixture(
    {
      ...initial,
      local_username: "existing-user",
      local_password_set: true,
      reauthenticated: true,
    },
    false,
  );
  const user = userEvent.setup();
  await user.type(
    await screen.findByLabelText("New password"),
    "changed passphrase",
  );
  await user.type(
    screen.getByLabelText("Confirm new password"),
    "changed passphrase",
  );
  await user.click(
    screen.getByRole("button", { name: "Save sign-in settings" }),
  );
  expect(save).not.toHaveBeenCalled();
  await user.type(
    screen.getByLabelText("Current password"),
    "existing passphrase",
  );
  await user.click(
    screen.getByRole("button", { name: "Save sign-in settings" }),
  );
  await waitFor(() => expect(refresh).toHaveBeenCalledOnce());
  expect(save).toHaveBeenCalledExactlyOnceWith({
    username: "existing-user",
    new_password: "changed passphrase",
    current_password: "existing passphrase",
  });
  expect(screen.queryByRole("button", { name: "Connect SSO" })).toBeNull();
});

it("keeps a failed credential change visible without claiming a fresh session", async () => {
  const { save, refresh } = fixture({
    ...initial,
    local_username: "person",
    local_password_set: true,
  });
  save.mockRejectedValue(new Error("The current password is incorrect."));
  const user = userEvent.setup();
  await user.type(
    await screen.findByLabelText("Current password"),
    "wrong password",
  );
  await user.type(screen.getByLabelText("New password"), "changed passphrase");
  await user.type(
    screen.getByLabelText("Confirm new password"),
    "changed passphrase",
  );
  await user.click(
    screen.getByRole("button", { name: "Save sign-in settings" }),
  );
  expect((await screen.findByRole("alert")).textContent).toBe(
    "The current password is incorrect.",
  );
  expect(refresh).not.toHaveBeenCalled();
});

it("does not hydrate or repopulate private settings after account authority changes during a save", async () => {
  const { client, save, refresh, unmount } = fixture({
    ...initial,
    reauthenticated: true,
  });
  let resolve!: () => void;
  save.mockImplementation(
    () =>
      new Promise<void>((done) => {
        resolve = done;
      }),
  );
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText("Username"), "person");
  await user.type(screen.getByLabelText("New password"), "new safe passphrase");
  await user.type(
    screen.getByLabelText("Confirm new password"),
    "new safe passphrase",
  );
  await user.click(
    screen.getByRole("button", { name: "Add password sign-in" }),
  );
  await waitFor(() => expect(save).toHaveBeenCalledOnce());
  vi.mocked(auth.currentOwner).mockReturnValue("another-account");
  unmount();
  client.clear();
  await act(async () => {
    resolve();
  });
  expect(refresh).not.toHaveBeenCalled();
  expect(screen.queryByText("Sign-in settings saved.")).toBeNull();
  expect(client.getQueryData(["account-security"])).toBeUndefined();
});

it("requires password verification before explicitly linking optional SSO", async () => {
  fixture({
    ...initial,
    local_username: "person",
    local_password_set: true,
    oidc_linked: false,
  });
  const link = vi
    .spyOn(api, "linkAccountSSO")
    .mockRejectedValue(new Error("The current password is incorrect."));
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "Connect SSO" }));
  expect(link).not.toHaveBeenCalled();
  await user.type(
    screen.getByLabelText("Password to connect SSO"),
    "typed current password",
  );
  await user.click(screen.getByRole("button", { name: "Connect SSO" }));
  expect(link).toHaveBeenCalledExactlyOnceWith("typed current password");
  expect((await screen.findByRole("alert")).textContent).toBe(
    "The current password is incorrect.",
  );
});
