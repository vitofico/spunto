const shellQuote = (value: string) =>
  "'" + value.replaceAll("'", "'\\''") + "'";
/** These snippets contain environment references, never a token value. */
export function agentSnippets(publicUrl: string, mcpUrl: string) {
  const codex = [
    "[mcp_servers.spunto]",
    `url = ${JSON.stringify(mcpUrl)}`,
    'bearer_token_env_var = "SPUNTO_TOKEN"',
    'default_tools_approval_mode = "prompt"',
    "",
    "[mcp_servers.spunto.tools.search_notes]",
    'approval_mode = "approve"',
    "",
    "[mcp_servers.spunto.tools.get_notes]",
    'approval_mode = "approve"',
    "",
    "[mcp_servers.spunto.tools.list_labels]",
    'approval_mode = "approve"',
  ].join("\n");
  const claude = JSON.stringify(
    {
      mcpServers: {
        spunto: {
          type: "http",
          url: mcpUrl,
          headers: { Authorization: "Bearer ${SPUNTO_TOKEN}" },
        },
      },
    },
    null,
    2,
  );
  const script = [
    `curl ${shellQuote(publicUrl + "/api/v1/notes")} \\`,
    `  --header 'Authorization: Bearer '"$SPUNTO_TOKEN"`,
  ].join("\n");
  return { codex, claude, script };
}
