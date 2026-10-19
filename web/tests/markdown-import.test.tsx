import { afterEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MarkdownImport } from "../src/features/MarkdownImport";
import { api } from "../src/lib/api";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
function mount() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MarkdownImport />
    </QueryClientProvider>,
  );
}
it("requires an uploaded preview before queueing and shows the finished vocabulary report", async () => {
  const fetch = vi.fn().mockResolvedValue(
    new Response(
      JSON.stringify({
        archive_id: "synthetic-preview",
        report: {
          notes: 2,
          attachments: 1,
          attachment_bytes: 24,
          missing_links: 1,
        },
      }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    ),
  );
  vi.stubGlobal("fetch", fetch);
  const route = vi
    .spyOn(api, "pluginRoute")
    .mockResolvedValueOnce([{ type: "text", text: "Import queued." }])
    .mockResolvedValueOnce([
      { type: "text", text: "Created 2 notes; skipped 0 owner edits." },
    ]);
  mount();
  expect(
    screen.queryByRole("button", { name: "Import these notes" }),
  ).toBeNull();
  fireEvent.change(screen.getByLabelText("Markdown ZIP"), {
    target: {
      files: [
        new File(["synthetic zip"], "vault.zip", { type: "application/zip" }),
      ],
    },
  });
  const submit = await screen.findByRole("button", {
    name: "Import these notes",
  });
  expect(fetch.mock.calls[0][0]).toBe("/api/v1/plugins/markdown-import/upload");
  expect(fetch.mock.calls[0][1].body).toBeInstanceOf(FormData);
  fireEvent.click(submit);
  await screen.findByText("Import queued.");
  expect(route).toHaveBeenCalledWith("markdown-import", "import", "POST", {
    archive_id: "synthetic-preview",
  });
  expect((submit as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(
    screen.getByRole("button", { name: "Refresh import report" }),
  );
  await screen.findByText("Created 2 notes; skipped 0 owner edits.");
});
it("surfaces upload errors without enabling an import", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          code: "validation_failed",
          detail: "Choose a valid ZIP.",
        }),
        {
          status: 422,
          headers: { "Content-Type": "application/problem+json" },
        },
      ),
    ),
  );
  mount();
  fireEvent.change(screen.getByLabelText("Markdown ZIP"), {
    target: { files: [new File(["invalid"], "bad.zip")] },
  });
  await waitFor(() =>
    expect(screen.getByRole("alert").textContent).toBeTruthy(),
  );
  expect(
    screen.queryByRole("button", { name: "Import these notes" }),
  ).toBeNull();
});
