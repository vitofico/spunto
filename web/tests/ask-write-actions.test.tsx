import { StrictMode } from "react";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { PermissionDialog } from "../src/features/SettingsPlugins";
import type { Plugin } from "../src/lib/types";
import AskConversation from "../src/features/AskConversation";
import { CommandResult, PaletteCommands } from "../src/features/Slots";
import { api } from "../src/lib/api";
import * as queryState from "../src/lib/query";
import { invalidateRequests } from "../src/lib/identity";
import {
  askAnswerSave,
  askNoteActionOptions,
  askWriteAction,
  askWriteAcknowledgement,
} from "../src/features/askConversationData";

const continuation = {
  type: "command",
  id: "ask",
  title: "Ask a follow-up",
  args: { conversation_id: "conversation", after_turn: 1 },
};
function action(
  stage: "preview" | "undo" | "done",
  title = "Apply change",
  id = "receipt",
) {
  return {
    type: "command",
    id: "save-answer",
    title,
    args: { action_id: id, stage },
  };
}
function proposal(title = "Apply change") {
  return {
    ui: 1,
    items: [
      { type: "text", text: "Confirm this proposed change." },
      {
        type: "markdown",
        text: "Target: Project plan\n\nBefore: Old deadline\n\nAfter: New deadline",
      },
      { type: "notes", ids: ["target"] },
      action("preview", title),
      continuation,
    ],
  };
}
const acknowledgement = (stage: "undo" | "done", id = "receipt") => ({
  ui: 1,
  items: [
    {
      type: "text",
      text: stage === "undo" ? "Change applied." : "Change undone.",
    },
    { type: "notes", ids: ["target"] },
    action(stage, stage === "undo" ? "Undo" : "Undo complete", id),
  ],
});
let client: QueryClient;
function manifest(version = "1.1.0", state = "enabled") {
  client.setQueryData(["manifest"], {
    ui: 1,
    plugins_rev: 1,
    plugins: [
      {
        id: "ask",
        name: "Ask",
        version,
        state,
        position: 1,
        creates: "machine",
        contributes: {
          commands: [
            { id: "ask", title: "Ask your notes", where: ["palette"] },
            {
              id: "clear-conversation",
              title: "Clear conversation",
              where: [],
            },
          ],
        },
      },
    ],
  });
}
beforeEach(() => {
  client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  manifest();
  vi.spyOn(api, "note").mockResolvedValue({
    id: "target",
    title: "Project plan",
  } as never);
  vi.spyOn(api, "command").mockResolvedValue({});
  vi.spyOn(api, "createNote");
});
afterEach(async () => {
  await act(async () => cleanup());
  client.clear();
  vi.restoreAllMocks();
});
const wrap = (node: React.ReactNode) => (
  <QueryClientProvider client={client}>{node}</QueryClientProvider>
);
const open = (title = "Apply change") =>
  render(
    wrap(
      <StrictMode>
        <AskConversation
          value={proposal(title)}
          question="Change the deadline"
        />
      </StrictMode>,
    ),
  );
it.each(["Create note", "Apply change"])(
  "shows the target and full preview without writes, then explicitly confirms %s and offers guarded Undo",
  async (title) => {
    vi.mocked(api.command)
      .mockResolvedValueOnce(acknowledgement("undo"))
      .mockResolvedValueOnce(acknowledgement("done"));
    open(title);
    expect(screen.getByText(/Target: Project plan/).textContent).toContain(
      "Before: Old deadline",
    );
    expect(screen.getByText(/Target: Project plan/).textContent).toContain(
      "After: New deadline",
    );
    expect(api.command).not.toHaveBeenCalled();
    expect(api.createNote).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Save as note" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: title }));
    expect(await screen.findByRole("button", { name: "Undo" })).toBeTruthy();
    expect(api.command).toHaveBeenNthCalledWith(1, "ask", "save-answer", {
      action_id: "receipt",
      stage: "preview",
    });
    expect(screen.getByText(/After: New deadline/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Undo" }));
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveProperty(
        "textContent",
        title === "Create note"
          ? "Created note moved to Trash."
          : "Previous text restored.",
      ),
    );
    expect(api.command).toHaveBeenNthCalledWith(2, "ask", "save-answer", {
      action_id: "receipt",
      stage: "undo",
    });
    expect(screen.queryByRole("button", { name: "Undo" })).toBeNull();
    expect(api.createNote).not.toHaveBeenCalled();
  },
);
it("cancels a preview locally without a write or Undo affordance", () => {
  open();
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  expect(screen.getByRole("status").textContent).toBe("Proposal cancelled.");
  expect(screen.queryByRole("button", { name: "Apply change" })).toBeNull();
  expect(api.command).not.toHaveBeenCalled();
  expect(api.createNote).not.toHaveBeenCalled();
});
it("serializes repeated confirmation and replays the same receipt after response loss", async () => {
  let finish!: (value: unknown) => void;
  vi.mocked(api.command)
    .mockImplementationOnce(
      () =>
        new Promise((_resolve, reject) => {
          finish = () => reject(new Error("Response lost"));
        }),
    )
    .mockResolvedValueOnce(acknowledgement("undo"));
  open();
  const confirm = screen.getByRole("button", { name: "Apply change" });
  fireEvent.click(confirm);
  fireEvent.click(confirm);
  expect(api.command).toHaveBeenCalledTimes(1);
  expect(
    (screen.getByRole("button", { name: "Working…" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  await act(async () => finish(null));
  await screen.findByRole("alert");
  fireEvent.click(screen.getByRole("button", { name: "Apply change" }));
  await screen.findByRole("button", { name: "Undo" });
  expect(vi.mocked(api.command).mock.calls[0]).toEqual(
    vi.mocked(api.command).mock.calls[1],
  );
});
it.each([false, true])(
  "shows a stale %s action refusal honestly even if the response contains a note link",
  async (undo) => {
    const refusal = {
      ui: 1,
      items: [
        { type: "text", text: "The note changed. Ask for a fresh preview." },
        { type: "notes", ids: ["target"] },
      ],
    };
    if (undo)
      vi.mocked(api.command)
        .mockResolvedValueOnce(acknowledgement("undo"))
        .mockResolvedValueOnce(refusal);
    else vi.mocked(api.command).mockResolvedValueOnce(refusal);
    open();
    fireEvent.click(screen.getByRole("button", { name: "Apply change" }));
    if (undo) {
      await screen.findByRole("button", { name: "Undo" });
      fireEvent.click(screen.getByRole("button", { name: "Undo" }));
    }
    expect(await screen.findByRole("alert")).toHaveProperty(
      "textContent",
      "The note changed. Ask for a fresh preview.",
    );
    expect(screen.queryByText("Undo complete")).toBeNull();
    if (!undo)
      expect(screen.queryByRole("button", { name: "Undo" })).toBeNull();
  },
);
it.each(["clear", "plugin", "identity", "close"])(
  "ignores an accepted action's late reply after %s",
  async (boundary) => {
    let finish!: (value: unknown) => void;
    vi.mocked(api.command).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const view = open();
    fireEvent.click(screen.getByRole("button", { name: "Apply change" }));
    if (boundary === "clear")
      fireEvent.click(screen.getByRole("button", { name: "New conversation" }));
    else if (boundary === "plugin")
      await act(async () => manifest("1.1.0", "disabled"));
    else if (boundary === "identity") invalidateRequests();
    else view.unmount();
    await act(async () => finish(acknowledgement("undo")));
    expect(screen.queryByRole("button", { name: "Undo" })).toBeNull();
    expect(screen.queryByText("Change applied.")).toBeNull();
  },
);
it("uses receipt stages, rejects client bodies/mixed legacy receipts, and never confirms a preview without Markdown", () => {
  const initial = askWriteAction(proposal())!;
  expect(
    askWriteAcknowledgement(initial, acknowledgement("undo")),
  ).toHaveProperty("args.stage", "undo");
  expect(
    askWriteAcknowledgement(initial, acknowledgement("done")),
  ).toHaveProperty("args.stage", "done");
  expect(
    askWriteAcknowledgement(initial, acknowledgement("undo", "wrong")),
  ).toBeUndefined();
  expect(
    askWriteAcknowledgement(initial, { type: "notes", ids: ["target"] }),
  ).toBeUndefined();
  expect(askWriteAction({ ui: 1, items: [action("preview")] })).toBeUndefined();
  expect(
    askWriteAction({
      ui: 1,
      items: [
        { type: "markdown", text: "Preview" },
        {
          ...action("preview"),
          args: { action_id: "receipt", stage: "preview", body: "Injected" },
        },
      ],
    }),
  ).toBeUndefined();
  expect(askAnswerSave(proposal())).toBeUndefined();
  expect(
    askAnswerSave({
      type: "command",
      id: "save-answer",
      title: "Save as note",
      args: { answer_id: "legacy" },
    }),
  ).toEqual({ answer_id: "legacy" });
});
it.each(["1.0.0", "1.1.0"])(
  "negotiates reviewed actions on every new palette conversation only for server %s",
  async (version) => {
    manifest(version);
    vi.mocked(api.command).mockImplementation(async (_plugin, _id, args) =>
      args?.start === true
        ? {
            ui: 1,
            items: [
              {
                ...continuation,
                args: { conversation_id: "conversation", after_turn: 0 },
              },
            ],
          }
        : proposal(),
    );
    render(wrap(<PaletteCommands query="Change the deadline?" />));
    fireEvent.click(screen.getByRole("button", { name: /Ask your notes/ }));
    await screen.findByRole("button", { name: "Apply change" });
    expect(api.command).toHaveBeenNthCalledWith(
      1,
      "ask",
      "ask",
      version === "1.1.0"
        ? { start: true, note_actions: true }
        : { start: true },
    );
    expect(api.command).toHaveBeenNthCalledWith(2, "ask", "ask", {
      conversation_id: "conversation",
      after_turn: 0,
      question: "Change the deadline?",
    });
  },
);
it("negotiates generic Ask forms and fresh chat allocation while continuations carry no opt-in flag", async () => {
  vi.mocked(api.command).mockImplementation(async (_plugin, _id, args) =>
    args?.start === true
      ? {
          ui: 1,
          items: [
            {
              ...continuation,
              args: { conversation_id: "conversation", after_turn: 0 },
            },
          ],
        }
      : proposal(),
  );
  const request = vi.spyOn(api, "request").mockResolvedValue(proposal());
  render(
    wrap(
      <CommandResult
        value={{
          type: "form",
          schema: {
            type: "object",
            required: ["question"],
            properties: { question: { type: "string", title: "Question" } },
          },
        }}
        origin={{ plugin: "ask", command: "ask" }}
      />,
    ),
  );
  fireEvent.change(await screen.findByLabelText("Your message"), {
    target: { value: "Change the deadline" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Send" }));
  await screen.findByRole("button", { name: "Apply change" });
  expect(api.command).toHaveBeenCalledWith("ask", "ask", {
    start: true,
    note_actions: true,
  });
  fireEvent.click(screen.getByRole("button", { name: "New conversation" }));
  fireEvent.change(screen.getByLabelText("Your message"), {
    target: { value: "Create a new note" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Send" }));
  await screen.findByRole("button", { name: "Apply change" });
  expect(
    vi
      .mocked(api.command)
      .mock.calls.filter((call) => call[2]?.note_actions === true),
  ).toHaveLength(2);
  expect(request).toHaveBeenCalledWith(
    "/api/v1/plugins/ask/commands/ask",
    expect.objectContaining({
      body: {
        conversation_id: "conversation",
        after_turn: 0,
        question: "Create a new note",
      },
    }),
  );
});
it("keeps waiting/legacy/prerelease manifests read-only", () => {
  for (const version of [undefined, "1", "1.0.0", "1.1.0-alpha"])
    expect(
      askNoteActionOptions([{ id: "ask", version, state: "enabled" }]),
    ).toEqual({});
  expect(
    askNoteActionOptions([{ id: "ask", version: "1.1.0", state: "waiting" }]),
  ).toEqual({});
  expect(
    askNoteActionOptions([{ id: "ask", version: "1.1.0", state: "enabled" }]),
  ).toEqual({ note_actions: true });
});

it("shows explicit-confirmation permission guidance for bundled Ask without auto-consenting", () => {
  HTMLDialogElement.prototype.showModal = function () {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function () {
    this.open = false;
  };
  const enable = vi.fn();
  render(
    wrap(
      <PermissionDialog
        plugin={
          {
            id: "ask",
            name: "Ask",
            description: "Reviewed note actions",
            origin: "bundled",
            permissions: ["notes.review"],
            permission_sentences: ["notes.review"],
            contributes: {},
          } as Plugin
        }
        onClose={() => {}}
        onEnable={enable}
        busy={false}
        error={null}
        opener={null}
      />,
    ),
  );
  expect(
    screen.getByText(/only after your explicit confirmation/),
  ).toBeTruthy();
  expect(screen.queryByText("notes.review")).toBeNull();
  expect(enable).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "Turn on" }));
  expect(enable).toHaveBeenCalledOnce();
});

it("refreshes cached search, labels, note details and history after both Apply and Undo", async () => {
  const cached = [
    "notes",
    "note",
    "search",
    "labels",
    "history",
    "suggestions",
    "status",
    "slip",
    "plugin-panel",
    "label-impact",
    "shared-notes",
    "shared-note",
  ];
  const populate = () => {
    for (const key of cached)
      client.setQueryData([key, "cached-result"], { body: "Old deadline" });
  };
  populate();
  vi.mocked(api.command)
    .mockResolvedValueOnce(acknowledgement("undo"))
    .mockResolvedValueOnce(acknowledgement("done"));
  open();
  fireEvent.click(screen.getByRole("button", { name: "Apply change" }));
  await screen.findByRole("button", { name: "Undo" });
  for (const key of cached)
    expect(
      client.getQueryState([key, "cached-result"])?.isInvalidated,
      key + " after Apply",
    ).toBe(true);
  populate();
  for (const key of cached)
    expect(client.getQueryState([key, "cached-result"])?.isInvalidated).toBe(
      false,
    );
  fireEvent.click(screen.getByRole("button", { name: "Undo" }));
  await screen.findByText("Previous text restored.");
  for (const key of cached)
    expect(
      client.getQueryState([key, "cached-result"])?.isInvalidated,
      key + " after Undo",
    ).toBe(true);
});

it.each([
  [
    "Create a note",
    "Create a note: Weekend shopping — buy pasta and tomatoes.",
  ],
  ["Edit a note", "Add olive oil to my note “Weekend shopping”."],
])(
  "prefills %s without sending and preserves a message already being written",
  async (label, message) => {
    const request = vi.spyOn(api, "request");
    render(
      wrap(
        <CommandResult
          value={{
            type: "form",
            schema: {
              type: "object",
              properties: { question: { type: "string" } },
            },
          }}
          origin={{ plugin: "ask", command: "ask" }}
        />,
      ),
    );
    const input = await screen.findByRole("textbox", { name: "Your message" });
    fireEvent.click(
      screen.getByRole("button", { name: new RegExp(`^${label}:`) }),
    );
    expect(input).toHaveProperty("value", message);
    expect(document.activeElement).toBe(input);
    expect(api.command).not.toHaveBeenCalled();
    expect(request).not.toHaveBeenCalled();
    expect(api.createNote).not.toHaveBeenCalled();
    fireEvent.change(input, {
      target: { value: "My own request in progress" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: /Edit a note: Add olive oil/ }),
    );
    expect(input).toHaveProperty("value", "My own request in progress");
  },
);

it("does not advertise note actions on a legacy server", () => {
  manifest("1.0.0");
  render(wrap(<AskConversation value={undefined} />));
  expect(
    screen.getByRole("button", {
      name: /Ask about your notes: What are my plans/,
    }),
  ).toBeTruthy();
  expect(screen.queryByRole("button", { name: /Create a note/ })).toBeNull();
  expect(screen.queryByRole("button", { name: /Edit a note/ })).toBeNull();
});

it("groups every authoritative preview part with its controls and offers revision without sending", async () => {
  const value = proposal();
  value.items.splice(
    0,
    1,
    { type: "text", text: "Preview only. Nothing changes until you confirm." },
    { type: "text", text: "Target note: Project plan" },
    {
      type: "markdown",
      text: "After:\n\nAdditional unchanged lines\nLast line remains visible",
    },
  );
  render(
    wrap(<AskConversation value={value} question="Change the deadline" />),
  );
  const card = screen.getByRole("region", { name: "Review note changes" });
  expect(card.textContent).toContain("Target note: Project plan");
  expect(card.textContent).toContain("Last line remains visible");
  expect(card.textContent).toContain("Before: Old deadline");
  expect(
    screen.getAllByText("Preview only. Nothing changes until you confirm."),
  ).toHaveLength(1);
  expect(screen.getByRole("heading", { name: "Related notes" })).toBeTruthy();
  expect(screen.queryByRole("heading", { name: "Sources" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Copy text" })).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Revise request" }));
  const input = screen.getByRole("textbox", { name: "Your message" });
  expect(input).toHaveProperty("value", "Change the deadline");
  expect(document.activeElement).toBe(input);
  fireEvent.change(input, { target: { value: "Keep this newer request" } });
  fireEvent.click(screen.getByRole("button", { name: "Revise request" }));
  expect(input).toHaveProperty("value", "Keep this newer request");
  expect(api.command).not.toHaveBeenCalled();
  expect(api.createNote).not.toHaveBeenCalled();
});

it("explains the creation undo and chat lifetime beside its confirmed note link", async () => {
  vi.mocked(api.command).mockResolvedValueOnce(acknowledgement("undo"));
  open("Create note");
  fireEvent.click(screen.getByRole("button", { name: "Create note" }));
  await screen.findByText("Note created.");
  expect(
    screen.getByText(/Undo moves this note to Trash if it is unchanged/),
  ).toBeTruthy();
  expect(screen.getByText(/opening a note leaves the chat/)).toBeTruthy();
  expect(
    screen.getByRole("link", { name: "Open note" }).getAttribute("href"),
  ).toBe("/n/target");
  expect(screen.queryByText("Not saved")).toBeNull();
  expect(screen.getByText("View original proposal")).toBeTruthy();
});

it.each(["owner", "editor", "viewer", "unknown", null, undefined])(
  "negotiates reviewed note actions according to workspace role %s",
  (role) => {
    const plugins = [{ id: "ask", version: "1.1.0", state: "enabled" }];
    expect(askNoteActionOptions(plugins, role)).toEqual(
      role === "viewer" || role === "unknown" ? {} : { note_actions: true },
    );
  },
);

it.each(["direct", "palette", "form"])(
  "does not advertise or negotiate authoring for workspace viewers through %s",
  async (entry) => {
    vi.spyOn(queryState, "useWorkspaceRole").mockReturnValue("viewer");
    const reply = {
      ui: 1,
      items: [{ type: "text", text: "Legacy response" }, continuation],
    };
    vi.mocked(api.command).mockImplementation(
      async (_plugin, _command, args) =>
        args?.start === true
          ? {
              ui: 1,
              items: [
                {
                  ...continuation,
                  args: { conversation_id: "conversation", after_turn: 0 },
                },
              ],
            }
          : reply,
    );
    vi.spyOn(api, "request").mockResolvedValue(reply);
    if (entry === "direct") {
      render(wrap(<AskConversation value={undefined} />));
      expect(
        screen.queryByText(/draft a note, or describe a change/),
      ).toBeNull();
      expect(
        screen.queryByRole("button", { name: /Create a note/ }),
      ).toBeNull();
      expect(screen.queryByRole("button", { name: /Edit a note/ })).toBeNull();
      fireEvent.change(screen.getByRole("textbox", { name: "Your message" }), {
        target: { value: "What is my plan?" },
      });
      fireEvent.click(screen.getByRole("button", { name: "Send" }));
    } else if (entry === "palette") {
      render(wrap(<PaletteCommands query="What is my plan?" />));
      fireEvent.click(screen.getByRole("button", { name: /Ask your notes/ }));
    } else {
      render(
        wrap(
          <CommandResult
            value={{
              type: "form",
              schema: {
                type: "object",
                properties: { question: { type: "string", title: "Question" } },
              },
            }}
            origin={{ plugin: "ask", command: "ask" }}
          />,
        ),
      );
      expect(
        screen.queryByRole("textbox", { name: "Your message" }),
      ).toBeNull();
      fireEvent.change(await screen.findByLabelText("Question"), {
        target: { value: "What is my plan?" },
      });
      fireEvent.click(screen.getByRole("button", { name: "Ask your notes" }));
    }
    await screen.findByText("Legacy response");
    expect(api.command).toHaveBeenCalledWith("ask", "ask", { start: true });
    expect(
      vi
        .mocked(api.command)
        .mock.calls.some((call) => call[2]?.note_actions === true),
    ).toBe(false);
  },
);
