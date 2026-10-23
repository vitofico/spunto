import { t, useLanguage } from "../lib/i18n";
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
const clientNames: () => Record<string, string> = () => ({
  script: t("settingsaccess.script_or_other_tool"),
  codex: "Codex",
  "claude-code": "Claude Code",
  monitor: t("settingsaccess.status_monitor"),
});
const scopeNames: () => Record<string, string> = () => ({
  read: t("settingsaccess.reads_notes"),
  write: t("settingsaccess.reads_and_writes_notes"),
  status: t("settingsaccess.status_only"),
});
function credentialName(c: Credential) {
  return c.kind === "session" ? browserName(c.name) : c.name;
}
function credentialFacts(c: Credential) {
  // Private and external-note limits only exist on access tokens.
  if (c.kind === "pat")
    return [
      clientNames()[c.client ?? ""] ?? c.client,
      scopeNames()[c.scope] ?? c.scope,
      c.scope === "status"
        ? null
        : c.private_ok
          ? t("settingsaccess.includes_private_notes")
          : t("settingsaccess.excludes_private_notes"),
      c.scope === "status"
        ? null
        : c.external_ok
          ? t("settingsaccess.includes_external_notes")
          : t("settingsaccess.excludes_external_notes"),
    ]
      .filter(Boolean)
      .join(" · ");
  return c.kind === "device" ? t("settingsaccess.android_app") : null;
}
function credentialUse(c: Credential) {
  const used = c.current
    ? t("settingsaccess.active_now")
    : c.last_used_at
      ? t("settingsaccess.last_used_value1", {
          value1: formatWhen(c.last_used_at),
        })
      : c.kind === "pat"
        ? t("settingsaccess.not_used_yet")
        : t("settingsaccess.signed_in_value1", {
            value1: formatWhen(c.created_at),
          });
  return c.expires_at
    ? t("settingsaccess.expires", {
        usage: used,
        date: formatWhen(c.expires_at),
      })
    : used;
}
function CredentialRows({ kind }: { kind: "tokens" | "devices" }) {
  useLanguage();
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
        title: t("settingsaccess.revoke_value1", { value1: credentialName(c) }),
        body: t(
          c.kind === "pat"
            ? "settingsaccess.revoke_token"
            : c.current
              ? "settingsaccess.revoke_current"
              : "settingsaccess.revoke_device",
        ),
        confirmLabel: t("settingsaccess.revoke_access"),
        danger: true,
      })
    )
      mutation.mutate(c);
  };
  return (
    <>
      {query.isPending ? (
        <p>{t("settingsaccess.loading_access")}</p>
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
                      {c.current && t("settingsaccess.this_browser")}
                      {credentialUse(c)}
                    </div>
                  </div>
                  <div className={styles.actions}>
                    <button
                      aria-label={t("settingsaccess.revoke_value1_2", {
                        value1: credentialName(c),
                      })}
                      disabled={mutation.isPending}
                      onClick={(e) => void revoke(c, e.currentTarget)}
                    >
                      {t("settingsaccess.revoke")}
                    </button>
                  </div>
                </div>
              </li>
            );
          })}
        </ul>
      ) : (
        <p className="muted">
          {kind === "tokens"
            ? t("settingsaccess.no_access_tokens_yet")
            : t("settingsaccess.no_other_devices")}
        </p>
      )}
      <ErrorLine error={query.error ?? mutation.error} />
      {dialog}
    </>
  );
}
export function Devices() {
  useLanguage();
  return (
    <>
      <h1>{t("settings.devices")}</h1>
      <p className="muted">
        {t(
          "settingsaccess.every_browser_session_and_connected_device_with_its",
        )}
      </p>
      <CredentialRows kind="devices" />
      <PairDevice />
    </>
  );
}
export function Tokens() {
  useLanguage();
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
      <h1>{t("settings.access_tokens")}</h1>
      <p className="muted">
        {t("settingsaccess.create_a_separate_token_for_each_agent_or")}
      </p>
      <button
        ref={creator}
        className="primary"
        onClick={() => {
          create.reset();
          setOpen(true);
        }}
      >
        {t("settingsaccess.create_token")}
      </button>
      <CredentialRows kind="tokens" />
      {open && (
        <Dialog
          title={t("settingsaccess.create_an_access_token")}
          opener={creator.current}
          onClose={() => setOpen(false)}
          footer={
            <>
              <button onClick={() => setOpen(false)}>
                {t("profilepanel.cancel")}
              </button>
              <button
                className="primary"
                type="submit"
                form={formId}
                disabled={!ready}
              >
                {create.isPending
                  ? t("collaboration.creating")
                  : t("settingsaccess.create_token")}
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
              {t("settingsaccess.name")}
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
              {t("settingsaccess.client")}
              <select
                className="field"
                value={clientName}
                onChange={(e) =>
                  setClientName(e.target.value as TokenInput["client"])
                }
              >
                {Object.entries(clientNames()).map(([value, title]) => (
                  <option key={value} value={value}>
                    {title}
                  </option>
                ))}
              </select>
            </label>
            <label className={styles.formField}>
              {t("settingsaccess.scope")}
              <select
                className="field"
                value={scope}
                onChange={(e) => setScope(e.target.value as typeof scope)}
              >
                <option value="read">{t("settingsaccess.read_notes")}</option>
                <option value="write">
                  {t("settingsaccess.read_and_write_notes")}
                </option>
                <option value="status">
                  {t("settingsaccess.status_only_no_note_text")}
                </option>
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
                  {t("settingsaccess.include_private_notes")}
                </label>
                <label className={styles.row}>
                  <input
                    type="checkbox"
                    checked={externalOk}
                    onChange={(e) => setExternal(e.target.checked)}
                  />
                  {t("settingsaccess.include_external_origin_notes")}
                </label>
              </>
            )}
            <label className={styles.formField}>
              {t("settingsaccess.expires_at_optional")}
              <input
                className="field"
                type="datetime-local"
                value={expires}
                onChange={(e) => setExpires(e.target.value)}
              />
              <small>
                {t("settingsaccess.leave_blank_for_no_expiry_you_can_revoke")}
              </small>
            </label>
          </form>
          <ErrorLine error={create.error} />
        </Dialog>
      )}
      {secret && (
        <Dialog
          title={t("settingsaccess.copy_your_token_now")}
          onClose={() => {
            setSecret(null);
            setCopied(false);
          }}
          footer={
            <>
              <button onClick={() => void copyText(secret).then(setCopied)}>
                {copied
                  ? t("settingsaccess.copied")
                  : t("settingsaccess.copy_token")}
              </button>
              <button
                className="primary"
                onClick={() => {
                  setSecret(null);
                  setCopied(false);
                }}
              >
                {t("collaboration.done")}
              </button>
            </>
          }
        >
          <p>{t("settingsaccess.this_is_the_only_time_it_will_be")}</p>
          <pre className={styles.copy}>{secret}</pre>
        </Dialog>
      )}
    </>
  );
}
function CopySnippet({ title, text }: { title: string; text: string }) {
  useLanguage();
  const [copied, setCopied] = useState(false);
  return (
    <>
      <h2>{title}</h2>
      <pre className={styles.copy}>{text}</pre>
      <button onClick={() => void copyText(text).then(setCopied)}>
        {copied
          ? t("settingsaccess.copied")
          : t("settingsaccess.copy_instructions")}
      </button>
    </>
  );
}
export function Agents() {
  useLanguage();
  const query = useQuery({ queryKey: ["meta"], queryFn: api.meta });
  const url = query.data?.mcp_url;
  const snippets = agentSnippets(query.data?.public_url ?? "", url ?? "");
  return (
    <>
      <h1>{t("settings.agents")}</h1>
      <p className="muted">
        {t("settingsaccess.agents_explanation")}{" "}
        <Link href="/settings/tokens">{t("settings.access_tokens")}</Link>
      </p>
      {url ? (
        <>
          <CopySnippet
            title={t("settingsaccess.codex_codex_config_toml")}
            text={snippets.codex}
          />
          <p className={styles.facts}>
            {t("settingsaccess.read_tools_can_run_directly_other_tools_ask")}{" "}
            <a
              href="https://learn.chatgpt.com/docs/extend/mcp"
              target="_blank"
              rel="noreferrer"
            >
              {t("settingsaccess.official_codex_mcp_setup")}
            </a>
          </p>
          <CopySnippet
            title={t("settingsaccess.claude_code_mcp_json")}
            text={snippets.claude}
          />
          <p className={styles.facts}>
            {t(
              "settingsaccess.the_token_is_expanded_from_your_environment_when",
            )}{" "}
            <a
              href="https://code.claude.com/docs/en/mcp#environment-variable-expansion-in-mcp-json"
              target="_blank"
              rel="noreferrer"
            >
              {t("settingsaccess.claude_code_mcp_setup")}
            </a>
          </p>
          <CopySnippet
            title={t("settingsaccess.scripts_http_api")}
            text={snippets.script}
          />
          <p className={styles.facts}>
            {t("settingsaccess.send_tokens_in_the_authorization_header")}{" "}
            <a href="/api/v1/docs">{t("settingsaccess.api_reference")}</a>
          </p>
        </>
      ) : query.isPending ? (
        <p>{t("settingsaccess.loading_connection_details")}</p>
      ) : (
        <p>{t("settingsaccess.mcp_is_disabled_by_the_operator")}</p>
      )}
      <ErrorLine error={query.error} />
    </>
  );
}
