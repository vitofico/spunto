import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { SettingsPage } from "../src/features/Settings";
import { api } from "../src/lib/api";
import * as auth from "../src/lib/query";
import type { Preferences } from "../src/lib/types";

const initial: Preferences = {
  theme: "salvia",
  plugin_order: ["tasks"],
  hidden_views: ["tasks/tasks"],
  hidden_mcp_tools: [],
  owner_display_name: "",
};
const clients: QueryClient[] = [];
afterEach(() => {
  cleanup();
  for (const client of clients.splice(0)) client.clear();
  vi.restoreAllMocks();
});
function fixture(settings = initial) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  clients.push(client);
  client.setQueryData(["session"], {
    owner_id: "fixture-owner",
    credential_id: "fixture-session",
  });
  vi.spyOn(auth, "currentOwner").mockReturnValue("fixture-owner");
  vi.spyOn(api, "settings").mockResolvedValue(settings);
  const view = () => (
    <QueryClientProvider client={client}>
      <SettingsPage section="profile" />
    </QueryClientProvider>
  );
  return { client, view };
}

it("saves and clears a private optional display name without replacing other preferences", async () => {
  const { client, view } = fixture();
  let stored = initial;
  const save = vi
    .spyOn(api, "saveSettings")
    .mockImplementation(async (patch) => {
      stored = { ...stored, ...patch };
      return stored;
    });
  const first = render(view());
  const user = userEvent.setup();
  const input = await screen.findByRole("textbox", {
    name: "Display name (optional)",
  });
  await waitFor(() => expect((input as HTMLInputElement).disabled).toBe(false));
  expect(
    screen.getByRole("link", { name: "Profile" }).getAttribute("href"),
  ).toBe("/settings/profile");
  expect(screen.getByText(/It is not a username/)).toBeTruthy();
  const name = 'Élodie 李 <img src=x onerror="alert(1)">';
  await user.type(input, `  ${name}  `);
  await user.click(screen.getByRole("button", { name: "Save profile" }));
  await screen.findByText("Profile saved.");
  expect(save).toHaveBeenCalledWith({ owner_display_name: name });
  expect(client.getQueryData(["settings"])).toEqual({
    ...initial,
    owner_display_name: name,
  });
  expect((input as HTMLInputElement).value).toBe(name);
  expect(document.querySelector("img")).toBeNull();
  first.unmount();
  render(view());
  const restored = screen.getByRole("textbox", {
    name: "Display name (optional)",
  });
  expect((restored as HTMLInputElement).value).toBe(name);
  await user.clear(restored);
  await user.click(screen.getByRole("button", { name: "Save profile" }));
  await waitFor(() =>
    expect(save).toHaveBeenLastCalledWith({ owner_display_name: "" }),
  );
  await waitFor(() => expect(stored).toEqual(initial));
});

it("keeps unsaved typing across a settings refresh and reports a failed save", async () => {
  const { client, view } = fixture();
  vi.spyOn(api, "saveSettings").mockRejectedValue(
    new Error("The profile could not be saved."),
  );
  render(view());
  const input = await screen.findByRole("textbox", {
    name: "Display name (optional)",
  });
  await waitFor(() => expect((input as HTMLInputElement).disabled).toBe(false));
  const user = userEvent.setup();
  await user.type(input, "New name");
  act(() => {
    client.setQueryData(["settings"], {
      ...initial,
      owner_display_name: "Remote name",
    });
  });
  expect((input as HTMLInputElement).value).toBe("New name");
  await user.click(screen.getByRole("button", { name: "Save profile" }));
  expect((await screen.findByRole("alert")).textContent).toBe(
    "The profile could not be saved.",
  );
  expect(screen.queryByText("Profile saved.")).toBeNull();
  expect((input as HTMLInputElement).value).toBe("New name");
  expect(client.getQueryData(["settings"])).toEqual({
    ...initial,
    owner_display_name: "Remote name",
  });
  expect(input.getAttribute("maxlength")).toBe("80");
});

it.each([null, "different-owner", "fixture-owner"])(
  "does not restore private profile cache after authority changes to %s during save",
  async (nextOwner) => {
    const { client, view } = fixture();
    let resolve!: (value: Preferences) => void;
    vi.spyOn(api, "saveSettings").mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    render(view());
    const input = await screen.findByRole("textbox", {
      name: "Display name (optional)",
    });
    await waitFor(() =>
      expect((input as HTMLInputElement).disabled).toBe(false),
    );
    const user = userEvent.setup();
    await user.type(input, "Private fixture");
    await user.click(screen.getByRole("button", { name: "Save profile" }));
    await waitFor(() => expect(api.saveSettings).toHaveBeenCalledTimes(1));
    vi.mocked(auth.currentOwner).mockReturnValue(nextOwner);
    client.clear();
    if (nextOwner)
      client.setQueryData(["session"], {
        owner_id: nextOwner,
        credential_id: "different-session",
      });
    await act(async () => {
      resolve({ ...initial, owner_display_name: "Private fixture" });
    });
    expect(client.getQueryData(["settings"])).toBeUndefined();
  },
);

it("shows a load failure without offering to overwrite an unknown profile", async () => {
  const { view } = fixture();
  vi.mocked(api.settings).mockRejectedValue(
    new Error("Could not load settings."),
  );
  render(view());
  expect((await screen.findByRole("alert")).textContent).toBe(
    "Could not load settings.",
  );
  expect(
    (screen.getByRole("button", { name: "Save profile" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
});
