import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { api } from "../src/lib/api";
import type { Suggestion } from "../src/lib/types";
import {
  SuggestionPreview,
  suggestionTitle,
} from "../src/features/SuggestionPreview";
import { labelChangeImpact } from "../src/features/labelChange";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
it("previews cluster labels without inventing an old label or counting unrelated notes", async () => {
  const proposal = {
    id: "cluster-test",
    plugin: "label-gardener",
    action: "label_change",
    payload: { new: "Ideas", note_ids: ["note-one", "note-two"] },
    note_id: null,
    status: "pending",
    note_version: null,
    confidence: null,
    reason: "Shared theme",
    change_seq: 1,
    created_at: "2026-10-14T00:00:00Z",
    decided_at: null,
  } as Suggestion;
  const request = vi.spyOn(api, "request").mockResolvedValue({ notes: 2 });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <SuggestionPreview suggestion={proposal} />
    </QueryClientProvider>,
  );
  expect(suggestionTitle(proposal)).toBe("Add #Ideas to 2 notes");
  expect(
    (await screen.findByText(/selected unlabeled notes/)).textContent,
  ).toBe("Add #Ideas to 2 selected unlabeled notes.");
  await screen.findByText(/Each selected note must still be unchanged/);
  await screen.findByText(/2 notes will change/);
  expect(request).toHaveBeenCalledWith("/api/v1/labels/impact", {
    method: "POST",
    body: { new: "Ideas", note_ids: ["note-one", "note-two"] },
  });
  expect(screen.queryByText(/undefined/)).toBeNull();
  expect(labelChangeImpact({ old: "Ideas", new: "ideas/active" })).toEqual({
    old: "Ideas",
    new: "ideas/active",
  });
  client.clear();
});
