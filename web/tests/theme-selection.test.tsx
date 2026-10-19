import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { SettingsPage } from "../src/features/Settings";
import { readDeviceAppearance } from "../src/features/Appearance";
import { api } from "../src/lib/api";
import * as auth from "../src/lib/query";
import type { Preferences } from "../src/lib/types";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  document.cookie = "theme=; Max-Age=0; Path=/";
});
it("persists vivid instance selection and an independent device override across remount", async () => {
  document.cookie = "theme=; Max-Age=0; Path=/";
  vi.stubGlobal(
    "matchMedia",
    vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
  let settings: Preferences = {
    theme: "carta",
    plugin_order: [],
    hidden_views: [],
    hidden_mcp_tools: [],
  };
  vi.spyOn(api, "settings").mockImplementation(async () => settings);
  const save = vi
    .spyOn(api, "saveSettings")
    .mockImplementation(async (patch) => {
      settings = { ...settings, ...patch };
      return settings;
    });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  client.setQueryData(["settings"], settings);
  client.setQueryData(["session"], {
    owner_id: "fixture-owner",
    credential_id: "fixture-session",
  });
  vi.spyOn(auth, "currentOwner").mockReturnValue("fixture-owner");
  const view = () => (
    <QueryClientProvider client={client}>
      <SettingsPage section="appearance" />
    </QueryClientProvider>
  );
  const first = render(view());
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: /Corallo/ }));
  await waitFor(() => expect(save).toHaveBeenCalledWith({ theme: "corallo" }));
  await waitFor(() =>
    expect(document.documentElement.dataset.theme).toBe("corallo"),
  );
  expect(readDeviceAppearance().theme).toBeUndefined();
  await user.click(
    screen.getByRole("switch", {
      name: "Use a different theme on this device",
    }),
  );
  await user.click(screen.getByRole("button", { name: /Elettrica/ }));
  await user.click(screen.getByRole("button", { name: "Dark" }));
  expect(readDeviceAppearance()).toMatchObject({
    theme: "elettrica",
    mode: "dark",
  });
  expect(settings.theme).toBe("corallo");
  expect(save).toHaveBeenCalledTimes(1);
  first.unmount();
  render(view());
  expect(document.documentElement.dataset.theme).toBe("elettrica");
  expect(document.documentElement.dataset.mode).toBe("dark");
  await user.click(
    screen.getByRole("switch", {
      name: "Use a different theme on this device",
    }),
  );
  expect(readDeviceAppearance().theme).toBeUndefined();
  expect(document.documentElement.dataset.theme).toBe("corallo");
  expect(document.documentElement.dataset.mode).toBe("dark");
  client.clear();
});
