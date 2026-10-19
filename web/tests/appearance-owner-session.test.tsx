import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AppearancePanel } from "../src/features/AppearancePanel";
import { api } from "../src/lib/api";
import * as auth from "../src/lib/query";
import type { Preferences } from "../src/lib/types";

const clients: QueryClient[] = [];
beforeEach(() => {
  vi.stubGlobal(
    "matchMedia",
    vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
});
afterEach(() => {
  cleanup();
  for (const client of clients.splice(0)) client.clear();
  document.cookie = "theme=; Max-Age=0; Path=/";
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it.each(["same-session", "signed-out", "different-owner", "new-session"])(
  "adopts an appearance response only for its original authority: %s",
  async (transition) => {
    document.cookie = "theme=; Max-Age=0; Path=/";
    const initial: Preferences = {
      theme: "carta",
      plugin_order: [],
      hidden_views: [],
      hidden_mcp_tools: [],
      owner_display_name: "Private fixture owner",
    };
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: Infinity } },
    });
    clients.push(client);
    client.setQueryData(["settings"], initial);
    client.setQueryData(["manifest"], { ui: 1, plugins_rev: 1, plugins: [] });
    client.setQueryData(["session"], {
      owner_id: "fixture-owner",
      credential_id: "fixture-session",
    });
    vi.spyOn(auth, "currentOwner").mockReturnValue("fixture-owner");
    vi.spyOn(api, "settings").mockResolvedValue(initial);
    let finish!: (value: Preferences) => void;
    vi.spyOn(api, "saveSettings").mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const view = render(
      <QueryClientProvider client={client}>
        <AppearancePanel />
      </QueryClientProvider>,
    );
    await userEvent
      .setup()
      .click(screen.getByRole("button", { name: /Salvia/ }));
    await waitFor(() =>
      expect(api.saveSettings).toHaveBeenCalledWith({ theme: "salvia" }),
    );
    if (transition !== "same-session") {
      view.unmount();
      client.clear();
      const nextOwner =
        transition === "signed-out"
          ? null
          : transition === "different-owner"
            ? "another-owner"
            : "fixture-owner";
      vi.mocked(auth.currentOwner).mockReturnValue(nextOwner);
      if (nextOwner)
        client.setQueryData(["session"], {
          owner_id: nextOwner,
          credential_id: "another-session",
        });
      document.documentElement.dataset.theme = "carta";
    }
    await act(async () => {
      finish({ ...initial, theme: "salvia" });
    });
    if (transition === "same-session") {
      await waitFor(() =>
        expect(client.getQueryData(["settings"])).toEqual({
          ...initial,
          theme: "salvia",
        }),
      );
      await waitFor(() =>
        expect(document.documentElement.dataset.theme).toBe("salvia"),
      );
    } else {
      expect(client.getQueryData(["settings"])).toBeUndefined();
      expect(document.documentElement.dataset.theme).toBe("carta");
    }
  },
);
