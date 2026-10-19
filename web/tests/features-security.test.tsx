import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  VocabularyItem,
  VocabularyOutput,
  safePluginHtml,
  validComponent,
} from "../src/features/Vocabulary";
import {
  SchemaForm,
  validSchema,
  validateSettings,
} from "../src/features/SchemaForm";
import { visibleSuggestions } from "../src/features/Slots";
import type { Note, Plugin, Suggestion } from "../src/lib/types";
afterEach(cleanup);
const wrap = (child: React.ReactNode) =>
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      {child}
    </QueryClientProvider>,
  );
describe("closed plugin vocabulary", () => {
  it("removes active HTML, arbitrary styling, and unsafe destinations", () => {
    const html = safePluginHtml(
      '<p style="color:red" onclick="alert(1)">Hello</p><script>alert(2)</script><svg onload="alert(3)"></svg><a href="javascript:alert(4)">link</a><iframe src="https://example.test"></iframe><img src=x onerror="alert(5)"><form><input name="secret"></form>',
    );
    const doc = new DOMParser().parseFromString(html, "text/html");
    expect(doc.querySelector("script,svg,iframe,form,input")).toBeNull();
    expect(
      doc.querySelector("[style],[onclick],[onload],[onerror]"),
    ).toBeNull();
    expect(doc.querySelector("a")?.hasAttribute("href")).toBe(false);
    expect(doc.body.textContent).toContain("Hello");
  });
  it("never interprets browser Markdown or text as HTML", () => {
    wrap(
      <VocabularyOutput
        value={[
          { type: "markdown", markdown: "<script>bad()</script> **words**" },
          { type: "text", text: "<img onerror=bad()>" },
        ]}
      />,
    );
    expect(screen.getByText("<script>bad()</script> **words**")).toBeTruthy();
    expect(document.querySelector("script,img")).toBeNull();
  });
  it("supports a safe declared fallback for a newer component and drops chrome requests", () => {
    wrap(
      <VocabularyItem
        value={{
          type: "future",
          fallback: { type: "text", text: "A safe fallback" },
        }}
      />,
    );
    expect(screen.getByText("A safe fallback")).toBeTruthy();
    expect(validComponent({ type: "text", text: "hello", toolbar: true })).toBe(
      false,
    );
    expect(
      validComponent({
        type: "notes",
        ids: Array.from({ length: 11 }, (_, i) => String(i)),
      }),
    ).toBe(false);
    expect(
      validComponent({ type: "command", id: "x", title: "A".repeat(25) }),
    ).toBe(false);
  });
  it("limits fallback recursion and draws precomputed hunks as escaped text", () => {
    const { container } = wrap(
      <VocabularyItem
        value={{
          type: "diff",
          base_version: 2,
          hunks: [
            {
              old_start: 0,
              new_start: 0,
              old_lines: ["<img src=x onerror=bad()>"],
              new_lines: ["Keep the text"],
            },
          ],
        }}
      />,
    );
    expect(container.querySelector("img")).toBeNull();
    expect(screen.getByText("<img src=x onerror=bad()>")).toBeTruthy();
  });
});
describe("generated settings forms", () => {
  it("secrets stay outside inputs and save payloads", () => {
    const onSave = vi.fn();
    const schema = {
      type: "object",
      properties: {
        token: { type: "string", "x-secret": true },
        time: { type: "string", format: "time", default: "08:00" },
        enabled: { type: "boolean", default: false },
      },
    };
    wrap(
      <SchemaForm
        schema={schema}
        pluginId="test-plugin"
        initial={{ token: "never-render-this" }}
        secrets={{ token: { set: true, source: "environment" } }}
        onSave={onSave}
      />,
    );
    expect(screen.queryByDisplayValue("never-render-this")).toBeNull();
    expect(screen.getByText("SPUNTO_PLUGIN_TEST_PLUGIN_TOKEN")).toBeTruthy();
    fireEvent.click(screen.getByText("Save settings"));
    expect(onSave).toHaveBeenCalledWith({ time: "08:00", enabled: false });
  });
  it("rejects unknown schemas, script fields, invalid numbers, and invalid times", () => {
    expect(
      validSchema({
        type: "object",
        properties: { x: { type: "string", onchange: "evil" } },
      }),
    ).toBe(false);
    expect(
      validSchema({ type: "object", properties: { x: { type: "object" } } }),
    ).toBe(false);
    expect(
      validateSettings(
        { type: "object", properties: { n: { type: "integer", minimum: 1 } } },
        { n: 0 },
      ),
    ).toContain("range");
    expect(
      validateSettings(
        { type: "object", properties: { n: { type: "number" } } },
        { n: NaN },
      ),
    ).toContain("invalid");
    expect(
      validateSettings(
        {
          type: "object",
          properties: { time: { type: "string", format: "time" } as never },
        },
        { time: "25:99" },
      ),
    ).toContain("time");
  });
});
describe("slot budgets and enabled-only output", () => {
  it("drops disabled output and caps each plugin at three proposals", () => {
    const suggestions = Array.from(
      { length: 5 },
      (_, i) =>
        ({ id: String(i), plugin: "tasks", status: "pending" }) as Suggestion,
    );
    suggestions.push({
      id: "other",
      plugin: "off",
      status: "pending",
    } as Suggestion);
    expect(
      visibleSuggestions({ suggestions } as Note, [
        { id: "tasks", position: 0 } as Plugin,
      ]).map((s) => s.id),
    ).toEqual(["0", "1", "2"]);
    expect(visibleSuggestions({ suggestions } as Note, [])).toEqual([]);
  });
});

describe("pasteable agent setup instructions", () => {
  it("uses real line breaks and only environment-backed authentication", async () => {
    const { agentSnippets } = await import("../src/features/agentSnippets");
    const snippets = agentSnippets(
      "https://notes.test",
      "https://notes.test/mcp",
    );
    expect(snippets.codex.split("\n")).toHaveLength(13);
    expect(snippets.codex).not.toContain("\\n");
    expect(snippets.codex).toContain('default_tools_approval_mode = "prompt"');
    expect(snippets.codex).toContain('bearer_token_env_var = "SPUNTO_TOKEN"');
    expect(
      JSON.parse(snippets.claude).mcpServers.spunto.headers.Authorization,
    ).toBe("Bearer ${SPUNTO_TOKEN}");
    expect(snippets.script.split("\n")).toHaveLength(2);
    expect(snippets.script.split("\n")[0].endsWith("\\")).toBe(true);
    expect(snippets.script).toContain('"$SPUNTO_TOKEN"');
  });
});

it("honors server character limits for Unicode plugin text and settings", () => {
  expect(validComponent({ type: "text", text: "🌱".repeat(280) })).toBe(true);
  expect(validComponent({ type: "text", text: "🌱".repeat(281) })).toBe(false);
  expect(
    validateSettings(
      {
        type: "object",
        properties: { title: { type: "string", maxLength: 2 } },
      },
      { title: "🌱🌱" },
    ),
  ).toBeNull();
});
