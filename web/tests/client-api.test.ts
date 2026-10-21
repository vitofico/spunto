import { afterEach, expect, it, vi } from "vitest";
import { api, request } from "../src/lib/api";
import { ApiError } from "../src/lib/errors";
afterEach(() => vi.restoreAllMocks());
it("uses generated create contract, same-origin cookies and a stable idempotency header", async () => {
  const calls: Request[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      calls.push(input as Request);
      return new Response(
        JSON.stringify({ id: "id", body: "draft", version: 1 }),
        { status: 201, headers: { "Content-Type": "application/json" } },
      );
    }),
  );
  const key = "00000000-0000-4000-8000-000000000001";
  await api.createNote("draft", key);
  expect(calls[0].credentials).toBe("same-origin");
  expect(calls[0].headers.get("Idempotency-Key")).toBe(key);
  expect(await calls[0].json()).toEqual({
    body: "draft",
    private: false,
    source: { kind: "web" },
  });
  vi.unstubAllGlobals();
});
it("sends the edited version and never puts note text in a URL", async () => {
  const calls: Request[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      calls.push(input as Request);
      return new Response(
        JSON.stringify({ id: "id", body: "private draft", version: 4 }),
        { headers: { "Content-Type": "application/json" } },
      );
    }),
  );
  await api.updateNote("id", "private draft", 3);
  expect(calls[0].headers.get("If-Match")).toBe('"3"');
  expect(calls[0].url).not.toContain("private");
  vi.unstubAllGlobals();
});
it("uses multipart upload and leaves the boundary to the browser", async () => {
  const fetcher = vi
    .spyOn(globalThis, "fetch")
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ max_upload_bytes: 20_000_000 })),
    )
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          id: "id",
          url: "/a/id",
          snippet: "![photo](/a/id)",
          mime: "image/png",
          size: 3,
        }),
        { headers: { "Content-Type": "application/json" } },
      ),
    );
  const file = new File(["png"], "photo.png", { type: "image/png" });
  await api.uploadAttachment(file);
  const options = fetcher.mock.calls[1][1]!;
  expect(options.body).toBeInstanceOf(FormData);
  expect((options.headers as Headers).has("Content-Type")).toBe(false);
  expect((options.body as FormData).get("file")).toBe(file);
});
it("checks the fresh configured upload cap before sending multipart bytes", async () => {
  const fetcher = vi
    .spyOn(globalThis, "fetch")
    .mockImplementation(async (path) => {
      expect(path).toBe("/api/v1/meta");
      return new Response(JSON.stringify({ max_upload_bytes: 1_000_000 }));
    });
  const file = new File([new Uint8Array(1_000_001)], "synthetic.png", {
    type: "image/png",
  });
  await expect(api.uploadAttachment(file)).rejects.toThrow(
    "This file is too large. The server accepts files up to 1 MB.",
  );
  expect(fetcher).toHaveBeenCalledOnce();
});
it("uses the legacy decimal cap for absent or malformed resource facts", async () => {
  const fetcher = vi.spyOn(globalThis, "fetch");
  const file = new File([new Uint8Array(5_000_001)], "synthetic.png", {
    type: "image/png",
  });
  for (const value of [undefined, "20000000", -1, 0, 1.5, 1_000_000_001]) {
    fetcher.mockResolvedValueOnce(
      new Response(JSON.stringify({ max_upload_bytes: value })),
    );
    await expect(api.uploadAttachment(file)).rejects.toBeInstanceOf(ApiError);
  }
  expect(fetcher).toHaveBeenCalledTimes(6);
});
it("rejects external or traversing endpoint paths without sending credentials", async () => {
  const fetcher = vi.spyOn(globalThis, "fetch");
  for (const path of [
    "https://evil.invalid/api/v1/notes",
    "//evil.invalid/api/v1/notes",
    "/api/v1/../notes",
  ])
    await expect(request(path)).rejects.toBeInstanceOf(ApiError);
  expect(fetcher).not.toHaveBeenCalled();
});
it("lists provider models and saves versioned choices through the workspace API", async () => {
  const fetcher = vi.spyOn(globalThis, "fetch").mockImplementation(
    async () =>
      new Response(JSON.stringify({ profiles: [] }), {
        headers: { "Content-Type": "application/json" },
      }),
  );
  await api.aiModels();
  expect(fetcher.mock.calls[0][0]).toBe("/api/v1/ai/models");
  const choices = {
    revision: "synthetic-revision",
    selections: { embed: { profile: "local", model: "fixture-embedding-v2" } },
  };
  await api.saveAIModels(choices);
  expect(fetcher.mock.calls[1][0]).toBe("/api/v1/ai/models");
  const options = fetcher.mock.calls[1][1]!;
  expect(options.method).toBe("PUT");
  expect(options.credentials).toBe("same-origin");
  expect(JSON.parse(options.body as string)).toEqual(choices);
});
it("keeps the client's sentence for draft and sign-in codes, never the raw body", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(
      JSON.stringify({
        code: "version_conflict",
        detail: "password and note secrets",
        conflict_id: "00000000-0000-4000-8000-000000000002",
      }),
      { status: 409, headers: { "Content-Type": "application/problem+json" } },
    ),
  );
  const error = await request("/api/v1/notes/id", {
    method: "PATCH",
    body: { body: "secret" },
  }).catch((error) => error);
  if (!(error instanceof ApiError))
    throw new Error("Expected sanitized API error");
  expect(error.message).not.toContain("secrets");
  expect(error.conflictId).toBe("00000000-0000-4000-8000-000000000002");
  expect(error.retryable).toBe(false);
});

it("shows a problem document's detail, but not a detail from any other body", async () => {
  const fail = (headers: Record<string, string>) =>
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          code: "forbidden",
          detail: "Viewers can't change this space's notes.",
        }),
        { status: 403, headers },
      ),
    );
  fail({ "Content-Type": "application/problem+json" });
  const problem = (await request("/api/v1/notes/id").catch(
    (error) => error,
  )) as ApiError;
  expect(problem.message).toBe("Viewers can't change this space's notes.");
  fail({ "Content-Type": "application/json" });
  const proxy = (await request("/api/v1/notes/id").catch(
    (error) => error,
  )) as ApiError;
  expect(proxy.message).toBe("This action is not permitted.");
});

it("names an unreachable server and carries the back-off from Retry-After", async () => {
  vi.spyOn(globalThis, "fetch").mockRejectedValueOnce(
    new TypeError("Failed to fetch"),
  );
  const offline = (await request("/api/v1/notes").catch(
    (error) => error,
  )) as ApiError;
  expect(offline.message).toMatch(/^Can’t reach the server/);
  vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(
    new Response(JSON.stringify({ code: "rate_limited", detail: "Wait." }), {
      status: 429,
      headers: {
        "Content-Type": "application/problem+json",
        "Retry-After": "4",
      },
    }),
  );
  const limited = (await request("/api/v1/auth/login", {
    method: "POST",
    body: { password: "x" },
  }).catch((error) => error)) as ApiError;
  expect(limited.code).toBe("rate_limited");
  expect(limited.retryAfter).toBe(4);
});

it("normalizes embedded note plugin IDs and nested slip notes consistently", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(
      JSON.stringify({
        slip: {
          note: {
            id: "n",
            body: "b",
            version: 1,
            suggestions: [{ id: "s", plugin_id: "labels", status: "pending" }],
            annotations: [
              {
                plugin_id: "tasks",
                kind: "meta",
                payload: { type: "text", text: "1 of 2" },
              },
            ],
          },
        },
      }),
      { headers: { "Content-Type": "application/json" } },
    ),
  );
  const { slip } = await request<{
    slip: { note: import("../src/lib/types").Note };
  }>("/api/v1/slip");
  expect(slip.note.suggestions[0].plugin).toBe("labels");
  expect(slip.note.annotations[0].plugin).toBe("tasks");
  expect(slip.note.annotations[0].id).toBe("tasks/n/meta");
});
