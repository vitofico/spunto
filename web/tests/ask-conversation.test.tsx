import { StrictMode } from "react";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import AskConversation from "../src/features/AskConversation";
import { CommandResult, PaletteCommands } from "../src/features/Slots";
import { api } from "../src/lib/api";
import { askContinuation } from "../src/features/askConversationData";

const answer = (
  turn = 1,
  text = "First answer",
  conversation = "temporary",
) => ({
  ui: 1,
  items: [
    { type: "markdown", text },
    { type: "notes", ids: ["source"] },
    {
      type: "command",
      id: "save-answer",
      title: "Save as note",
      args: { answer_id: `answer-${turn}` },
    },
    {
      type: "command",
      id: "ask",
      title: "Ask a follow-up",
      args: { conversation_id: conversation, after_turn: turn },
    },
    {
      type: "command",
      id: "clear-conversation",
      title: "Clear conversation",
      args: { conversation_id: conversation },
    },
  ],
});
let client: QueryClient;
beforeEach(() => {
  client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  client.setQueryData(["manifest"], {
    ui: 1,
    plugins_rev: 1,
    plugins: [
      {
        id: "ask",
        name: "Ask",
        state: "enabled",
        position: 1,
        contributes: {
          commands: [
            { id: "ask", title: "Ask your notes", where: ["palette"] },
          ],
        },
      },
    ],
  });
  vi.spyOn(api, "note").mockResolvedValue({
    id: "source",
    title: "Source note",
  } as never);
  vi.spyOn(api, "command").mockImplementation(
    async (_plugin, _command, args) =>
      args?.start === true ? answer(0, "", "allocated") : {},
  );
});
afterEach(() => {
  cleanup();
  client.clear();
  vi.restoreAllMocks();
});
const wrap = (node: React.ReactNode) => (
  <QueryClientProvider client={client}>{node}</QueryClientProvider>
);
const open = () =>
  render(wrap(<AskConversation value={answer()} question="First question?" />));
const send = (question = "What happens next?") => {
  fireEvent.change(screen.getByLabelText("Your message"), {
    target: { value: question },
  });
  fireEvent.click(screen.getByRole("button", { name: "Send" }));
};
it("adopts a first response, appends follow-ups and keeps explicit saving on its turn", async () => {
  const request = vi
    .spyOn(api, "request")
    .mockResolvedValue(answer(2, "Second answer"));
  vi.mocked(api.command).mockResolvedValue({
    ui: 1,
    items: [{ type: "notes", ids: ["saved-note"] }],
  });
  render(
    wrap(
      <CommandResult
        value={answer()}
        origin={{
          plugin: "ask",
          command: "ask",
          args: { question: "First question?" },
        }}
      />,
    ),
  );
  await screen.findByLabelText("Your message");
  fireEvent.click(screen.getByRole("button", { name: "Save as note" }));
  expect(
    await screen.findByRole("button", { name: "Saved as note" }),
  ).toBeTruthy();
  expect(
    screen.getByRole("link", { name: "Open note" }).getAttribute("href"),
  ).toBe("/n/saved-note");
  send();
  await screen.findByText("Second answer");
  expect(screen.getByText("First answer")).toBeTruthy();
  expect(screen.getByRole("button", { name: "Saved as note" })).toBeTruthy();
  expect(screen.getAllByRole("button", { name: "Copy text" })).toHaveLength(2);
  expect(screen.getAllByRole("list")).toHaveLength(2);
  expect(request).toHaveBeenCalledWith(
    "/api/v1/plugins/ask/commands/ask",
    expect.objectContaining({
      body: {
        question: "What happens next?",
        conversation_id: "temporary",
        after_turn: 1,
      },
      signal: expect.any(AbortSignal),
    }),
  );
  expect(api.command).toHaveBeenCalledWith("ask", "save-answer", {
    answer_id: "answer-1",
  });
});
it("keeps the previous answer and submitted question after failure and retries the same turn", async () => {
  const request = vi
    .spyOn(api, "request")
    .mockRejectedValueOnce(new Error("Conversation expired"))
    .mockResolvedValueOnce(answer(2, "Recovered answer"));
  open();
  send();
  await screen.findByRole("alert");
  expect(screen.getByText("First answer")).toBeTruthy();
  expect(
    (screen.getByLabelText("Your message") as HTMLTextAreaElement).value,
  ).toBe("What happens next?");
  expect(
    screen.getByText(/start a new conversation if this one has expired/),
  ).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Try again" }));
  await screen.findByText("Recovered answer");
  expect(request.mock.calls[0][1]?.body).toEqual(
    request.mock.calls[1][1]?.body,
  );
});
it("clears while pending, aborts, discards late output and starts without the old handle", async () => {
  let finish!: (value: unknown) => void;
  const request = vi
    .spyOn(api, "request")
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve as typeof finish;
        }),
    )
    .mockResolvedValueOnce(answer(1, "New answer", "new-session"));
  open();
  send();
  fireEvent.click(screen.getByRole("button", { name: "New conversation" }));
  expect(request.mock.calls[0][1]?.signal?.aborted).toBe(true);
  await act(async () => {
    finish(answer(2, "Discarded answer"));
  });
  expect(screen.queryByText("First answer")).toBeNull();
  expect(screen.queryByText("Discarded answer")).toBeNull();
  expect(api.command).toHaveBeenCalledWith("ask", "clear-conversation", {
    conversation_id: "temporary",
  });
  send("A new question?");
  await screen.findByText("New answer");
  expect(request.mock.calls[1][1]?.body).toEqual({
    question: "A new question?",
    conversation_id: "allocated",
    after_turn: 0,
  });
});
it("cleans up when closed and discards a late answer", async () => {
  let finish!: (value: unknown) => void;
  vi.spyOn(api, "request").mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve as typeof finish;
      }),
  );
  const view = open();
  send();
  view.unmount();
  await act(async () => {
    finish(answer(2, "Late answer"));
  });
  expect(api.command).toHaveBeenCalledWith("ask", "clear-conversation", {
    conversation_id: "temporary",
  });
  expect(screen.queryByText("Late answer")).toBeNull();
});
it("discards messages when Ask is disabled", async () => {
  open();
  act(() => {
    client.setQueryData(["manifest"], {
      ui: 1,
      plugins_rev: 2,
      plugins: [{ id: "ask", state: "disabled" }],
    });
  });
  await waitFor(() => expect(screen.queryByText("First answer")).toBeNull());
  expect(
    (screen.getByLabelText("Your message") as HTMLTextAreaElement).disabled,
  ).toBe(true);
});
it("does not reopen a dismissed palette with a late first answer", async () => {
  let finish!: (value: unknown) => void;
  vi.mocked(api.command).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const onResult = vi.fn();
  const view = render(
    wrap(<PaletteCommands query="What is the plan?" onResult={onResult} />),
  );
  fireEvent.click(screen.getByRole("button", { name: /Ask your notes/ }));
  await waitFor(() => expect(finish).toBeTypeOf("function"));
  view.unmount();
  await act(async () => {
    finish(answer());
  });
  expect(onResult).not.toHaveBeenCalled();
  expect(api.command).toHaveBeenCalledWith("ask", "clear-conversation", {
    conversation_id: "temporary",
  });
});
it("accepts only the closed continuation command with a nonnegative turn", () => {
  expect(askContinuation(answer())).toEqual({
    conversation_id: "temporary",
    after_turn: 1,
  });
  expect(
    askContinuation({
      type: "command",
      id: "ask",
      title: "Ask a follow-up",
      args: { conversation_id: "temporary", after_turn: -1 },
    }),
  ).toBeUndefined();
  expect(
    askContinuation({
      type: "command",
      id: "ask",
      title: "Ask a follow-up",
      args: { conversation_id: "temporary", after_turn: 1 },
      surprise: true,
    }),
  ).toBeUndefined();
});
it("shows a text-only Save refusal and leaves Save available instead of claiming success", async () => {
  vi.mocked(api.command).mockResolvedValueOnce({
    ui: 1,
    items: [
      { type: "text", text: "This answer expired. Ask again before saving." },
    ],
  });
  open();
  fireEvent.click(screen.getByRole("button", { name: "Save as note" }));
  expect(await screen.findByRole("alert")).toHaveProperty(
    "textContent",
    "This answer expired. Ask again before saving.",
  );
  expect(screen.queryByRole("button", { name: "Saved as note" })).toBeNull();
  expect(
    (screen.getByRole("button", { name: "Save as note" }) as HTMLButtonElement)
      .disabled,
  ).toBe(false);
  expect(screen.getByText("First answer")).toBeTruthy();
});
it("clears a handle allocated after clearing and never sends its first question", async () => {
  let finish!: (value: unknown) => void;
  vi.mocked(api.command).mockImplementation(async (_plugin, _command, args) =>
    args?.start === true
      ? new Promise((resolve) => {
          finish = resolve;
        })
      : {},
  );
  const request = vi.spyOn(api, "request");
  open();
  fireEvent.click(screen.getByRole("button", { name: "New conversation" }));
  send("A fresh first question?");
  await waitFor(() => expect(finish).toBeTypeOf("function"));
  fireEvent.click(screen.getByRole("button", { name: "New conversation" }));
  await act(async () => {
    finish(answer(0, "", "late-allocated"));
  });
  expect(request).not.toHaveBeenCalled();
  expect(api.command).toHaveBeenCalledWith("ask", "clear-conversation", {
    conversation_id: "late-allocated",
  });
});
it("reuses the known initial handle after a failed palette request so close can clear it", async () => {
  let requests = 0;
  vi.mocked(api.command).mockImplementation(async (_plugin, command, args) => {
    if (args?.start === true) return answer(0, "", "allocated");
    if (command === "ask" && ++requests === 1)
      throw new Error("Synthetic timeout");
    return command === "ask"
      ? answer(1, "Recovered first answer", "allocated")
      : {};
  });
  render(wrap(<PaletteCommands query="What is the plan?" />));
  fireEvent.click(screen.getByRole("button", { name: /Ask your notes/ }));
  await screen.findByRole("alert");
  fireEvent.click(screen.getByRole("button", { name: /Ask your notes/ }));
  await screen.findByText("Recovered first answer");
  expect(
    vi.mocked(api.command).mock.calls.filter((call) => call[2]?.start === true),
  ).toHaveLength(1);
});

it("keeps an adopted handle and per-answer Save alive through StrictMode's effect rehearsal, then clears on real unmount", async () => {
  vi.mocked(api.command).mockResolvedValue({
    ui: 1,
    items: [{ type: "notes", ids: ["saved-in-strict-mode"] }],
  });
  const view = render(
    wrap(
      <StrictMode>
        <AskConversation value={answer()} question="First question?" />
      </StrictMode>,
    ),
  );
  await act(async () => {});
  expect(api.command).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "Save as note" }));
  expect(
    await screen.findByRole("button", { name: "Saved as note" }),
  ).toBeTruthy();
  expect(
    screen.getByRole("link", { name: "Open note" }).getAttribute("href"),
  ).toBe("/n/saved-in-strict-mode");
  expect(api.command).toHaveBeenCalledExactlyOnceWith("ask", "save-answer", {
    answer_id: "answer-1",
  });
  await act(async () => {
    view.unmount();
  });
  expect(api.command).toHaveBeenLastCalledWith("ask", "clear-conversation", {
    conversation_id: "temporary",
  });
});
