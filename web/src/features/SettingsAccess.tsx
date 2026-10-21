import { useId, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "wouter";
import { api } from "../lib/api";
import { copyText } from "./copy";
import { agentSnippets } from "./agentSnippets";
import { useConfirm } from "../components/core/ConfirmDialog";
import type { Credential, TokenInput } from "../lib/types";
import { Dialog } from "./Dialog";
import styles from "./Feature.module.css";
import { PairDevice } from "./PairDevice";
import { browserName, formatWhen } from "./format";
import { ErrorLine } from "./SettingsParts";
const clientNames: Record<string, string> = {
  script: "Script or other tool",
  codex: "Codex",
  "claude-code": "Claude Code",
  monitor: "Status monitor",
};
const scopeNames: Record<string, string> = {
  read: "Reads notes",
  write: "Reads and writes notes",
  status: "Status only",
};
function credentialName(c: Credential) {
  return c.kind === "session" ? browserName(c.name) : c.name;
}
function credentialFacts(c: Credential) {
  // Private and external-note limits only exist on access tokens.
  if (c.kind === "pat")
    return [
      clientNames[c.client ?? ""] ?? c.client,
      scopeNames[c.scope] ?? c.scope,
      c.scope === "status"
        ? null
        : c.private_ok
          ? "Includes private notes"
          : "Excludes private notes",
      c.scope === "status"
        ? null
        : c.external_ok
          ? "Includes external notes"
          : "Excludes external notes",
    ]
      .filter(Boolean)
      .join(" · ");
  return c.kind === "device" ? "Android app" : null;
}
function credentialUse(c: Credential) {
  const used = c.current
    ? "Active now"
    : c.last_used_at
      ? `Last used ${formatWhen(c.last_used_at)}`
      : c.kind === "pat"
        ? "Not used yet"
        : `Signed in ${formatWhen(c.created_at)}`;
  return c.expires_at ? `${used} · Expires ${formatWhen(c.expires_at)}` : used;
}
function CredentialRows({ kind }: { kind: "tokens" | "devices" }) {
  const query = useQuery({
    queryKey: ["credentials"],
    queryFn: api.credentials,
  });
  const client = useQueryClient();
  const [confirm, dialog] = useConfirm();
  const mutation = useMutation({
    mutationFn: (credential: Credential) => api.revokeCredential(credential.id),
    onSuccess: (_, credential) => {
      if (credential.current) location.assign("/");
      void client.invalidateQueries({ queryKey: ["credentials"] });
    },
  });
  const items =
    query.data?.filter((c) =>
      kind === "tokens"
        ? c.kind === "pat"
        : ["session", "device"].includes(c.kind),
    ) ?? [];
  const revoke = async (c: Credential, opener: HTMLElement) => {
    if (
      await confirm({
        opener,
        title: `Revoke ${credentialName(c)}?`,
        body: `This ${c.kind === "pat" ? "token" : "device or session"} will lose access immediately.${c.current ? " You will be signed out." : ""}`,
        confirmLabel: "Revoke access",
        danger: true,
      })
    )
      mutation.mutate(c);
  };
  return (
    <>
      {query.isPending ? (
        <p>Loading access…</p>
      ) : items.length ? (
        <ul className={styles.list}>
          {items.map((c) => {
            const facts = credentialFacts(c);
            return (
              <li className={styles.plugin} key={c.id}>
                <div className={styles.between}>
                  <div>
                    <strong title={c.kind === "session" ? c.name : undefined}>
                      {credentialName(c)}
                    </strong>
                    {facts && <p>{facts}</p>}
                    <div className={styles.facts}>
                      {c.current && "This browser · "}
                      {credentialUse(c)}
                    </div>
                  </div>
                  <div className={styles.actions}>
                    <button
                      aria-label={`Revoke ${credentialName(c)}`}
                      disabled={mutation.isPending}
                      onClick={(e) => void revoke(c, e.currentTarget)}
                    >
                      Revoke
                    </button>
                  </div>
                </div>
              </li>
            );
          })}
        </ul>
      ) : (
        <p className="muted">
          {kind === "tokens" ? "No access tokens yet." : "No other devices."}
        </p>
      )}
      <ErrorLine error={query.error ?? mutation.error} />
      {dialog}
    </>
  );
}
export function Devices() {
  return (
    <>
      <h1>Devices</h1>
      <p className="muted">
        Every browser session and connected device, with its last use.
      </p>
      <CredentialRows kind="devices" />
      <PairDevice />
    </>
  );
}
export function Tokens() {
  const formId = useId();
  const [open, setOpen] = useState(false);
  const creator = useRef<HTMLButtonElement>(null);
  const [secret, setSecret] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [name, setName] = useState("");
  const [scope, setScope] = useState<"read" | "write" | "status">("read");
  const [clientName, setClientName] = useState<TokenInput["client"]>("script");
  const [privateOk, setPrivate] = useState(false);
  const [externalOk, setExternal] = useState(false);
  const [expires, setExpires] = useState("");
  const client = useQueryClient();
  const create = useMutation({
    mutationFn: async () => {
      const data = await api.createToken({
        name,
        scope,
        client: clientName,
        private_ok: scope === "status" ? false : privateOk,
        external_ok: scope === "status" ? false : externalOk,
        expires_at: expires ? new Date(expires).toISOString() : null,
      });
      setSecret(data.token);
      setOpen(false);
      setName("");
    },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["credentials"] });
    },
  });
  const ready =
    !create.isPending &&
    !!name.trim() &&
    (!expires || new Date(expires).getTime() > Date.now());
  return (
    <>
      <h1>Access tokens</h1>
      <p className="muted">
        Create a separate token for each agent or script. A read token can find
        notes without changing them.
      </p>
      <button
        ref={creator}
        className="primary"
        onClick={() => {
          create.reset();
          setOpen(true);
        }}
      >
        Create token
      </button>
      <CredentialRows kind="tokens" />
      {open && (
        <Dialog
          title="Create an access token"
          opener={creator.current}
          onClose={() => setOpen(false)}
          footer={
            <>
              <button onClick={() => setOpen(false)}>Cancel</button>
              <button
                className="primary"
                type="submit"
                form={formId}
                disabled={!ready}
              >
                {create.isPending ? "Creating…" : "Create token"}
              </button>
            </>
          }
        >
          <form
            id={formId}
            onSubmit={(event) => {
              event.preventDefault();
              if (ready) create.mutate();
            }}
          >
            <label className={styles.formField}>
              Name
              <input
                className="field"
                value={name}
                maxLength={120}
                required
                autoFocus
                onChange={(e) => setName(e.target.value)}
              />
            </label>
            <label className={styles.formField}>
              Client
              <select
                className="field"
                value={clientName}
                onChange={(e) =>
                  setClientName(e.target.value as TokenInput["client"])
                }
              >
                {Object.entries(clientNames).map(([value, title]) => (
                  <option key={value} value={value}>
                    {title}
                  </option>
                ))}
              </select>
            </label>
            <label className={styles.formField}>
              Scope
              <select
                className="field"
                value={scope}
                onChange={(e) => setScope(e.target.value as typeof scope)}
              >
                <option value="read">Read notes</option>
                <option value="write">Read and write notes</option>
                <option value="status">Status only · no note text</option>
              </select>
            </label>
            {scope !== "status" && (
              <>
                <label className={styles.row}>
                  <input
                    type="checkbox"
                    checked={privateOk}
                    onChange={(e) => setPrivate(e.target.checked)}
                  />
                  Include private notes
                </label>
                <label className={styles.row}>
                  <input
                    type="checkbox"
                    checked={externalOk}
                    onChange={(e) => setExternal(e.target.checked)}
                  />
                  Include external-origin notes
                </label>
              </>
            )}
            <label className={styles.formField}>
              Expires at (optional)
              <input
                className="field"
                type="datetime-local"
                value={expires}
                onChange={(e) => setExpires(e.target.value)}
              />
              <small>
                Leave blank for no expiry. You can revoke access at any time.
              </small>
            </label>
          </form>
          <ErrorLine error={create.error} />
        </Dialog>
      )}
      {secret && (
        <Dialog
          title="Copy your token now"
          onClose={() => {
            setSecret(null);
            setCopied(false);
          }}
          footer={
            <>
              <button onClick={() => void copyText(secret).then(setCopied)}>
                {copied ? "Copied" : "Copy token"}
              </button>
              <button
                className="primary"
                onClick={() => {
                  setSecret(null);
                  setCopied(false);
                }}
              >
                Done
              </button>
            </>
          }
        >
          <p>
            This is the only time it will be shown. Store it in your client’s
            environment.
          </p>
          <pre className={styles.copy}>{secret}</pre>
        </Dialog>
      )}
    </>
  );
}
function CopySnippet({ title, text }: { title: string; text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <>
      <h2>{title}</h2>
      <pre className={styles.copy}>{text}</pre>
      <button onClick={() => void copyText(text).then(setCopied)}>
        {copied ? "Copied" : "Copy instructions"}
      </button>
    </>
  );
}
export function Agents() {
  const query = useQuery({ queryKey: ["meta"], queryFn: api.meta });
  const url = query.data?.mcp_url;
  const snippets = agentSnippets(query.data?.public_url ?? "", url ?? "");
  return (
    <>
      <h1>Agents</h1>
      <p className="muted">
        Give each agent its own{" "}
        <Link href="/settings/tokens">access token</Link>. Start with read
        access and keep edit tools asking for confirmation. Set{" "}
        <code>SPUNTO_TOKEN</code> privately in the environment that starts your
        client.
      </p>
      {url ? (
        <>
          <CopySnippet
            title="Codex · ~/.codex/config.toml"
            text={snippets.codex}
          />
          <p className={styles.facts}>
            Read tools can run directly. Other tools ask first.{" "}
            <a
              href="https://learn.chatgpt.com/docs/extend/mcp"
              target="_blank"
              rel="noreferrer"
            >
              Official Codex MCP setup
            </a>
          </p>
          <CopySnippet title="Claude Code · .mcp.json" text={snippets.claude} />
          <p className={styles.facts}>
            The token is expanded from your environment when Claude Code loads
            this configuration.{" "}
            <a
              href="https://code.claude.com/docs/en/mcp#environment-variable-expansion-in-mcp-json"
              target="_blank"
              rel="noreferrer"
            >
              Claude Code MCP setup
            </a>
          </p>
          <CopySnippet title="Scripts · HTTP API" text={snippets.script} />
          <p className={styles.facts}>
            Send tokens in the Authorization header.{" "}
            <a href="/api/v1/docs">API reference</a>
          </p>
        </>
      ) : query.isPending ? (
        <p>Loading connection details…</p>
      ) : (
        <p>MCP is disabled by the operator.</p>
      )}
      <ErrorLine error={query.error} />
    </>
  );
}
