import { afterEach, expect, it, vi } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  accountName,
  AccountIdentityDetails,
  AccountIdentityLink,
} from "../src/features/AccountIdentity";
import { api } from "../src/lib/api";
import * as auth from "../src/lib/query";
import type { Session, SignedInAccount } from "../src/lib/types";

const clients: QueryClient[] = [];
afterEach(() => {
  cleanup();
  clients.splice(0).forEach((client) => client.clear());
  vi.restoreAllMocks();
});
const alice: SignedInAccount = {
  id: "alice",
  display_name: "  Élodie 李  ",
  username: "alice-login",
  email: null,
};
function fixture(
  initial: Session = {
    owner_id: "alice",
    user_id: "alice",
    credential_id: "one",
  },
) {
  let session = initial;
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  clients.push(client);
  vi.spyOn(auth, "useAuth").mockImplementation(
    () => ({ session }) as ReturnType<typeof auth.useAuth>,
  );
  const load = vi
    .spyOn(api, "accountIdentity")
    .mockResolvedValue({ account: alice });
  const close = vi.fn();
  const content = () => (
    <QueryClientProvider client={client}>
      <AccountIdentityLink close={close} />
      <AccountIdentityDetails />
    </QueryClientProvider>
  );
  const view = render(content());
  return {
    load,
    client,
    select(next: Session) {
      session = next;
      view.rerender(content());
    },
  };
}

it("shows the actual account name and login separately from its server", async () => {
  fixture();
  const link = await screen.findByRole("link", { name: "Account: Élodie 李" });
  expect(link.getAttribute("href")).toBe("/settings/account");
  expect(link.getAttribute("title")).toBe("Account: Élodie 李");
  expect(screen.getByText("Signed in as Élodie 李")).toBeTruthy();
  expect(screen.getByText("Username: alice-login")).toBeTruthy();
  expect(screen.getByText(`Server: ${window.location.origin}`)).toBeTruthy();
});

it("trims fallback names and supports SSO-only accounts without exposing identifiers", () => {
  expect(
    accountName({ ...alice, display_name: " \t", username: " login " }),
  ).toBe("login");
  expect(
    accountName({
      ...alice,
      display_name: "",
      username: null,
      email: " mail@example.test ",
    }),
  ).toBe("mail@example.test");
  expect(
    accountName({ ...alice, display_name: " ", username: " ", email: null }),
  ).toBeUndefined();
});

it("keeps single-owner mode meaningful and never fetches hosted account data", () => {
  const { load } = fixture({ owner_id: "opaque-owner-id" });
  expect(
    screen.getByRole("link", { name: "Account: Owner" }).getAttribute("href"),
  ).toBe("/settings/profile");
  expect(screen.getByText("Signed in as Owner")).toBeTruthy();
  expect(screen.queryByText("opaque-owner-id")).toBeNull();
  expect(load).not.toHaveBeenCalled();
});

it("does not publish an old credential's late identity into another account", async () => {
  let resolve!: (value: { account: SignedInAccount }) => void;
  const { load, select } = fixture();
  // Replace the initial request before its microtask completes, then rotate the account.
  load.mockImplementation(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  select({ owner_id: "old", user_id: "old", credential_id: "slow" });
  await act(async () => {});
  load.mockResolvedValue({
    account: { ...alice, id: "bob", display_name: "Bob", username: "bob" },
  });
  select({ owner_id: "bob", user_id: "bob", credential_id: "new" });
  await screen.findByRole("link", { name: "Account: Bob" });
  await act(async () => {
    resolve({
      account: { ...alice, id: "old", display_name: "Private old name" },
    });
  });
  expect(screen.queryByText("Private old name")).toBeNull();
  expect(screen.getByText("Signed in as Bob")).toBeTruthy();
});

it("rejects an identity response for a different account", async () => {
  const { load, select } = fixture();
  load.mockResolvedValue({ account: alice });
  select({ owner_id: "bob", user_id: "bob", credential_id: "bob-cookie" });
  await act(async () => {});
  expect(screen.queryByText("Élodie 李")).toBeNull();
  expect(screen.getByRole("link", { name: "Account" })).toBeTruthy();
});
