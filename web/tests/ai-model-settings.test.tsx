import { afterEach, beforeEach, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { api } from "../src/lib/api";
import { ApiError } from "../src/lib/errors";
import { AISettings } from "../src/features/AISettings";
import type {
  AIModelInventory,
  AIModelSelections,
  AIStatus,
} from "../src/lib/types";

const inventory: AIModelInventory = {
  profiles: [
    {
      profile: "local",
      error: null,
      models: [
        {
          id: "fixture-embedding-v2",
          capabilities: ["embed"],
          leaves_network: false,
        },
        {
          id: "fixture-vision-cloud",
          capabilities: ["chat", "vision"],
          leaves_network: true,
        },
        {
          id: "fixture-audio",
          capabilities: ["transcribe"],
          leaves_network: false,
        },
        {
          id: "fixture-metadata-only",
          capabilities: [],
          leaves_network: false,
        },
      ],
    },
    {
      profile: "cloud",
      error: null,
      models: [
        { id: "fixture-chat", capabilities: ["chat"], leaves_network: true },
        { id: "fixture-unknown", capabilities: null, leaves_network: true },
      ],
    },
  ],
};
function status(): AIStatus {
  return {
    can_manage: true,
    profiles: [
      {
        name: "local",
        provider: "openai",
        base_url: "http://localhost:11434/v1",
        embed_model: "fixture-embedding-old",
        leaves_network: { embed: false },
      },
      {
        name: "cloud",
        provider: "anthropic",
        base_url: "https://provider.synthetic.test",
        chat_model: "fixture-chat",
        leaves_network: { chat: true },
      },
    ],
    bindings: { chat: "cloud", embed: "local" },
    capabilities: ["ai.chat", "ai.embed"],
    health: [],
    model_selection: {
      revision: "revision-1",
      selections: { chat: { profile: "cloud", model: "fixture-chat" } },
      defaults: { embed: { profile: "local", model: "fixture-embedding-old" } },
    },
    indexing: {
      available: true,
      enabled: true,
      chunks: 4,
      routes: [
        {
          profile: "local",
          active_key: "old-key",
          target_key: "old-key",
          indexed: 5,
          total: 5,
        },
      ],
    },
  };
}
const value = (profile: string, model: string) =>
  JSON.stringify([profile, model]);
let client: QueryClient;
let current: AIStatus;
function wrap() {
  return render(
    <QueryClientProvider client={client}>
      <AISettings />
    </QueryClientProvider>,
  );
}
function selectModel(label: string, profile: string, model: string) {
  fireEvent.change(screen.getByRole("combobox", { name: label }), {
    target: { value: value(profile, model) },
  });
}
beforeEach(() => {
  client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  current = status();
  vi.spyOn(api, "ai").mockImplementation(async () => current);
  vi.spyOn(api, "aiModels").mockResolvedValue(inventory);
  vi.spyOn(api, "saveAIModels").mockImplementation(async ({ selections }) => {
    current = {
      ...current,
      model_selection: {
        ...current.model_selection!,
        revision: "revision-2",
        selections,
      },
    };
    return current;
  });
});
afterEach(() => {
  cleanup();
  client.clear();
  vi.restoreAllMocks();
});

it("selects inventory models explicitly, preserves other overrides and enables an unbound capability", async () => {
  wrap();
  const embed = await screen.findByRole("combobox", {
    name: "Embeddings model",
  });
  await within(embed).findByRole("option", { name: "fixture-embedding-v2" });
  expect(
    within(embed).queryByRole("option", { name: "fixture-chat" }),
  ).toBeNull();
  expect(
    within(embed).queryByRole("option", { name: "fixture-metadata-only" }),
  ).toBeNull();
  selectModel("Embeddings model", "local", "fixture-embedding-v2");
  selectModel("Vision model", "local", "fixture-vision-cloud");
  expect(api.saveAIModels).not.toHaveBeenCalled();
  expect(screen.getByText(/Embedding changes rebuild the index/)).toBeTruthy();
  const vision = screen.getByRole("combobox", { name: "Vision model" });
  expect(
    within(vision.parentElement!).getByText(/Outside your network/),
  ).toBeTruthy();
  const selections: AIModelSelections = {
    chat: { profile: "cloud", model: "fixture-chat" },
    embed: { profile: "local", model: "fixture-embedding-v2" },
    vision: { profile: "local", model: "fixture-vision-cloud" },
  };
  vi.mocked(api.saveAIModels).mockImplementation(async () => {
    current = {
      ...current,
      bindings: { chat: "cloud", embed: "local", vision: "local" },
      model_selection: {
        ...current.model_selection!,
        revision: "revision-2",
        selections,
      },
      indexing: {
        ...current.indexing!,
        routes: [
          {
            profile: "local",
            active_key: "old-key",
            target_key: "new-key",
            indexed: 1,
            total: 5,
          },
        ],
      },
    };
    return current;
  });
  fireEvent.click(screen.getByRole("button", { name: "Save models" }));
  await waitFor(() =>
    expect(api.saveAIModels).toHaveBeenCalledWith({
      revision: "revision-1",
      selections,
    }),
  );
  expect(await screen.findByText("Model choices saved.")).toBeTruthy();
  expect(
    screen.getByText(
      /The previous index is retained until the new model is ready/,
    ),
  ).toBeTruthy();
  expect(screen.getByText(/local: 1 of 5 notes indexed/)).toBeTruthy();
});

it("recovers from discovery failure and marks unknown capability support without inventing it", async () => {
  vi.mocked(api.aiModels).mockRejectedValueOnce(
    new ApiError(503, "ai_inventory_unavailable"),
  );
  wrap();
  expect(
    await screen.findByText(/Could not load available models/),
  ).toBeTruthy();
  expect(screen.queryByText(/no model is configured/)).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Refresh models" }));
  const embed = screen.getByRole("combobox", { name: "Embeddings model" });
  await within(embed).findByRole("option", {
    name: "fixture-unknown (capability unverified)",
  });
  selectModel("Embeddings model", "cloud", "fixture-unknown");
  expect(
    within(embed.parentElement!).getByText(
      /Capability not reported by provider/,
    ),
  ).toBeTruthy();
  expect(
    within(embed.parentElement!).getByText(/Outside your network/),
  ).toBeTruthy();
  expect(
    (screen.getByRole("button", { name: "Save models" }) as HTMLButtonElement)
      .disabled,
  ).toBe(false);
});

it("retains a disappeared current model while saving an unrelated available model", async () => {
  current.model_selection!.selections.chat = {
    profile: "cloud",
    model: "fixture-chat-removed",
  };
  current.profiles[1].chat_model = "fixture-chat-removed";
  wrap();
  const chat = await screen.findByRole("combobox", { name: "Chat model" });
  const missing = await within(chat).findByRole("option", {
    name: /fixture-chat-removed.*not listed by provider/,
  });
  expect((chat as HTMLSelectElement).value).toBe(
    value("cloud", "fixture-chat-removed"),
  );
  expect((missing as HTMLOptionElement).disabled).toBe(true);
  const embed = screen.getByRole("combobox", { name: "Embeddings model" });
  await within(embed).findByRole("option", { name: "fixture-embedding-v2" });
  selectModel("Embeddings model", "local", "fixture-embedding-v2");
  fireEvent.click(screen.getByRole("button", { name: "Save models" }));
  await waitFor(() =>
    expect(api.saveAIModels).toHaveBeenCalledWith({
      revision: "revision-1",
      selections: {
        chat: { profile: "cloud", model: "fixture-chat-removed" },
        embed: { profile: "local", model: "fixture-embedding-v2" },
      },
    }),
  );
});

it("keeps saved choices and other providers usable when one inventory fails", async () => {
  vi.mocked(api.aiModels).mockResolvedValue({
    profiles: [
      inventory.profiles[0],
      { profile: "cloud", models: [], error: "ai_inventory_unavailable" },
    ],
  });
  wrap();
  expect(
    await screen.findByText(
      "Could not load models from cloud. Saved choices are kept.",
    ),
  ).toBeTruthy();
  const chat = screen.getByRole("combobox", { name: "Chat model" });
  expect((chat as HTMLSelectElement).value).toBe(
    value("cloud", "fixture-chat"),
  );
  expect(
    within(chat).getByRole("option", {
      name: "fixture-chat · cloud (current selection)",
    }),
  ).toBeTruthy();
  const embed = screen.getByRole("combobox", { name: "Embeddings model" });
  expect(
    within(embed).getByRole("option", { name: "fixture-embedding-v2" }),
  ).toBeTruthy();
});

it("preserves dirty choices and the original revision across polling and a CAS conflict", async () => {
  vi.mocked(api.saveAIModels).mockRejectedValue(
    new ApiError(409, "ai_selection_changed"),
  );
  wrap();
  const embed = await screen.findByRole("combobox", {
    name: "Embeddings model",
  });
  await within(embed).findByRole("option", { name: "fixture-embedding-v2" });
  selectModel("Embeddings model", "local", "fixture-embedding-v2");
  current = {
    ...status(),
    model_selection: {
      ...status().model_selection!,
      revision: "other-revision",
      selections: {},
    },
  };
  act(() => client.setQueryData(["ai"], current));
  expect((embed as HTMLSelectElement).value).toBe(
    value("local", "fixture-embedding-v2"),
  );
  fireEvent.click(screen.getByRole("button", { name: "Save models" }));
  expect(
    await screen.findByText(
      /Model settings changed elsewhere. Your choices are kept/,
    ),
  ).toBeTruthy();
  expect((embed as HTMLSelectElement).value).toBe(
    value("local", "fixture-embedding-v2"),
  );
  expect(api.saveAIModels).toHaveBeenCalledWith({
    revision: "revision-1",
    selections: {
      chat: { profile: "cloud", model: "fixture-chat" },
      embed: { profile: "local", model: "fixture-embedding-v2" },
    },
  });
  fireEvent.click(
    screen.getByRole("button", { name: "Discard changes and reload" }),
  );
  await waitFor(() => expect((embed as HTMLSelectElement).value).toBe(""));
  expect(screen.queryByText(/Model settings changed elsewhere/)).toBeNull();
});

it.each([
  [
    422,
    "ai_model_invalid",
    "That model is no longer available for this capability",
  ],
  [503, "ai_inventory_unavailable", "Could not load the provider’s model list"],
] as const)(
  "keeps choices after a %s save failure",
  async (code, reason, message) => {
    vi.mocked(api.saveAIModels).mockRejectedValue(new ApiError(code, reason));
    wrap();
    const embed = await screen.findByRole("combobox", {
      name: "Embeddings model",
    });
    await within(embed).findByRole("option", { name: "fixture-embedding-v2" });
    selectModel("Embeddings model", "local", "fixture-embedding-v2");
    fireEvent.click(screen.getByRole("button", { name: "Save models" }));
    expect(await screen.findByText(new RegExp(message))).toBeTruthy();
    expect((embed as HTMLSelectElement).value).toBe(
      value("local", "fixture-embedding-v2"),
    );
    expect(screen.queryByText("Model choices saved.")).toBeNull();
  },
);

it("stages an environment reset and permits explicit save when discovery is unavailable", async () => {
  vi.mocked(api.aiModels).mockRejectedValue(
    new ApiError(503, "ai_inventory_unavailable"),
  );
  wrap();
  expect(
    await screen.findByText(/Could not load available models/),
  ).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Use server defaults" }));
  expect(api.saveAIModels).not.toHaveBeenCalled();
  expect(
    (screen.getByRole("combobox", { name: "Chat model" }) as HTMLSelectElement)
      .value,
  ).toBe("");
  fireEvent.click(screen.getByRole("button", { name: "Save models" }));
  await waitFor(() =>
    expect(api.saveAIModels).toHaveBeenCalledWith({
      revision: "revision-1",
      selections: {},
    }),
  );
});

it("keeps model discovery and mutations unavailable to a space member", async () => {
  current.can_manage = false;
  const check = vi.spyOn(api, "checkAI");
  const reindex = vi.spyOn(api, "reindexAI");
  wrap();
  const embed = await screen.findByRole("combobox", {
    name: "Embeddings model",
  });
  expect((embed as HTMLSelectElement).disabled).toBe(true);
  expect(api.aiModels).not.toHaveBeenCalled();
  expect(screen.queryByRole("button", { name: "Refresh models" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Save models" })).toBeNull();
  expect(
    (
      screen.getByRole("button", {
        name: "Check providers",
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
  expect(
    (screen.getByRole("button", { name: "Reindex notes" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  fireEvent.click(screen.getByRole("button", { name: "Check providers" }));
  fireEvent.click(screen.getByRole("button", { name: "Reindex notes" }));
  expect(check).not.toHaveBeenCalled();
  expect(reindex).not.toHaveBeenCalled();
  expect(api.saveAIModels).not.toHaveBeenCalled();
  expect(
    screen.getByText("Only a space owner can change models."),
  ).toBeTruthy();
});

it("reports a status load failure without constructing empty settings", async () => {
  vi.mocked(api.ai).mockRejectedValue(new ApiError(503, "unavailable"));
  wrap();
  expect(await screen.findByRole("alert")).toBeTruthy();
  expect(screen.queryByRole("combobox")).toBeNull();
  expect(api.aiModels).not.toHaveBeenCalled();
  expect(api.saveAIModels).not.toHaveBeenCalled();
});
