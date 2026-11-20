import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import ThinkingDesk from "../src/pages/ThinkingDesk";
import { api } from "../src/lib/api";
import type { NoteDetail, Plugin, WorkspaceRole } from "../src/lib/types";
import { noteCommandAvailable } from "../src/features/Slots";

const authority = vi.hoisted(() => ({
  role: "owner" as WorkspaceRole,
  enabled: true,
  version: "1.2.0",
}));
vi.mock("../src/lib/query", async (original) => ({
  ...(await original<typeof import("../src/lib/query")>()),
  useManifest: () => ({
    isPending: false,
    data: {
      plugins: authority.enabled
        ? [
            {
              id: "thinking-desk",
              origin: "bundled",
              version: authority.version,
            },
          ]
        : [],
    },
  }),
  useOwner: () => "synthetic-owner",
  useWorkspaceRole: () => authority.role,
}));

const open = (desk?: string) => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <ThinkingDesk desk={desk} />
    </QueryClientProvider>,
  );
};
const command = (id: string, title: string, args = {}) => ({
  type: "command",
  id,
  title,
  args,
});
const choices = [
  { type: "text", text: "Start with notes you already have." },
  { type: "markdown", text: "### Follow one idea" },
  command("edit", "Trace an idea", { action: "start" }),
  { type: "markdown", text: "### Make a new connection" },
  command("edit", "Combine notes", { action: "start" }),
];

beforeEach(() => {
  authority.role = "owner";
  authority.enabled = true;
  authority.version = "1.2.0";
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it.each(["viewer", "editor", "disabled"])(
  "does not load a desk for %s access",
  async (reason) => {
    if (reason === "disabled") authority.enabled = false;
    else authority.role = reason as WorkspaceRole;
    const invoke = vi.spyOn(api, "command");
    open("018f7414-ef80-7000-8000-000000000001");
    await screen.findByRole("heading", { name: /page isn.t here/i });
    expect(invoke).not.toHaveBeenCalled();
  },
);

it("renders full authored titles in the server's recent order and a New desk link", async () => {
  const newest = "A complete authored title for the most recently updated desk";
  const earlier = "Another saved desk";
  vi.spyOn(api, "command").mockResolvedValue([
    command("desk", "New desk", { action: "new" }),
    { type: "text", text: newest },
    command("desk", "Open desk", {
      desk_id: "018f7414-ef80-7000-8000-000000000001",
    }),
    { type: "text", text: earlier },
    command("desk", "Open desk", {
      desk_id: "018f7414-ef80-7000-8000-000000000002",
    }),
  ]);

  open();
  await screen.findByRole("heading", { name: "Resume a desk" });
  expect(
    screen.getAllByRole("listitem").map((item) => item.textContent),
  ).toEqual([newest, earlier]);
  expect(
    screen.getByRole("link", { name: "New desk" }).getAttribute("href"),
  ).toBe("/thinking-desk/new");
  expect(screen.queryByRole("button", { name: "Open desk" })).toBeNull();
});

it("opens new choices with the older server's parameter and response contract", async () => {
  authority.version = "1.1.0";
  const invoke = vi.spyOn(api, "command").mockResolvedValue([
    ...choices,
    { type: "markdown", text: "### Your saved desks" },
    command("desk", "Old title…", {
      desk_id: "018f7414-ef80-7000-8000-000000000001",
    }),
  ]);
  open("new");
  await screen.findByRole("button", { name: "Combine notes" });
  expect(invoke).toHaveBeenCalledWith("thinking-desk", "desk", {});
  expect(screen.queryByText("Old title…")).toBeNull();
});

it("hides old bundled desk commands from note tools", () => {
  const plugin = { id: "thinking-desk", origin: "bundled" } as Plugin;
  expect(
    noteCommandAvailable(
      plugin,
      {
        id: "desk",
        title: "Thinking desk",
        where: ["note-actions", "palette", "slash"],
      },
      { id: "synthetic-note" } as NoteDetail,
    ),
  ).toBe(false);
});
