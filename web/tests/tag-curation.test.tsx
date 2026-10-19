import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { api } from "../src/lib/api";
import type { Suggestion } from "../src/lib/types";
import {
  SuggestionPreview,
  suggestionTitle,
} from "../src/features/SuggestionPreview";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("previews removal and counts its current impact with a null destination", async () => {
  const request = vi.spyOn(api, "request").mockResolvedValue({ notes: 2 });
  const suggestion: Suggestion = {
    id: "remove-old",
    plugin: "label-gardener",
    action: "label_change",
    note_id: null,
    note_version: null,
    confidence: null,
    reason: "Unused duplicate tag",
    status: "pending",
    created_at: "2026-10-15T00:00:00Z",
    decided_at: null,
    change_seq: 1,
    payload: { old: "old", new: null },
  };
  expect(suggestionTitle(suggestion)).toBe("Remove #old");
  const client = new QueryClient();
  render(
    <QueryClientProvider client={client}>
      <SuggestionPreview suggestion={suggestion} />
    </QueryClientProvider>,
  );
  expect(await screen.findByText(/2 notes will change/)).toBeTruthy();
  expect(
    screen.getByText(/from affected notes, including nested labels/),
  ).toBeTruthy();
  expect(screen.queryByText(/#null/)).toBeNull();
  expect(request).toHaveBeenCalledWith("/api/v1/labels/impact", {
    method: "POST",
    body: { old: "old", new: null },
  });
  client.clear();
});
