import { useEffect, useId, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "wouter";
import { api } from "../lib/api";
import { copyText } from "./copy";
import { agentSnippets } from "./agentSnippets";
import { AppearancePanel } from "./AppearancePanel";
import { ProfilePanel } from "./ProfilePanel";
import { AccountPanel } from "./AccountPanel";
import { useHosted } from "../lib/query";
import { useDocumentTitle } from "../lib/useDocumentTitle";
import { useToast } from "../components/core/Toast";
import { useConfirm } from "../components/core/ConfirmDialog";
import type {
  Args,
  Credential,
  Plugin,
  TokenInput,
  AICapability,
  AIHealth,
  VectorStatus,
} from "../lib/types";
import { SchemaForm } from "./SchemaForm";
import { Dialog } from "./Dialog";
import { CommandResult, PluginCommands, type CommandOrigin } from "./Slots";
import styles from "./Feature.module.css";
import {
  AISettings,
  AIRouting,
  AIHealthResults,
  AIOffHint,
} from "./AISettings";
import { PairDevice } from "./PairDevice";
import { MarkdownImport } from "./MarkdownImport";
import { SpacesPanel } from "./Collaboration";
import {
  browserName,
  formatBytes,
  formatWhen,
  humanize,
  plural,
} from "./format";
const sections = [
  ["account", "Account"],
  ["spaces", "Spaces"],
  ["profile", "Profile"],
  ["appearance", "Appearance"],
  ["plugins", "Plugins"],
  ["devices", "Devices"],
  ["tokens", "Access tokens"],
  ["agents", "Agents"],
  ["ai", "AI"],
  ["data", "Data"],
];
function ErrorLine({ error }: { error: Error | null }) {
  return error ? (
    <p className="error" role="alert">
      {error.message}
    </p>
  ) : null;
}
function PageTitle({ page }: { page: string }) {
  useDocumentTitle(page);
  return null;
}
export function SettingsPage({
  section = "appearance",
  onShowGuide,
}: {
  section?: string;
  onShowGuide?: (trigger: HTMLElement) => void;
}) {
  const hosted = useHosted();
  const parts = section.replace(/^\//, "").split("/");
  const selected = parts[0] || "appearance";
  const visible = sections.filter(
    ([id]) => (id !== "account" && id !== "spaces") || hosted,
  );
  const title = visible.find(([id]) => id === selected)?.[1];
  const navRef = useRef<HTMLElement>(null);
  useEffect(() => {
    const revealSelected = () => {
      const nav = navRef.current;
      const active = nav?.querySelector('a[aria-current="page"]');
      if (!nav || !active || nav.scrollWidth <= nav.clientWidth) return;
      const container = nav.getBoundingClientRect();
      const item = active.getBoundingClientRect();
      if (item.left < container.left + 4)
        nav.scrollLeft += item.left - container.left - 4;
      else if (item.right > container.right - 4)
        nav.scrollLeft += item.right - container.right + 4;
    };
    revealSelected();
    window.addEventListener("resize", revealSelected);
    return () => window.removeEventListener("resize", revealSelected);
  }, [selected, hosted]);
  return (
    <div className={styles.settings}>
      {!(selected === "plugins" && parts[1]) && (
        <PageTitle page={`Settings · ${title ?? "Unavailable"}`} />
      )}
      <nav ref={navRef} className={styles.nav} aria-label="Settings">
        {visible.map(([id, title]) => (
          <Link
            href={`/settings/${id}`}
            aria-current={id === selected ? "page" : undefined}
            key={id}
          >
            {title}
          </Link>
        ))}
      </nav>
      <section className={styles.section}>
        {selected === "account" && hosted ? (
          <AccountPanel />
        ) : selected === "spaces" && hosted ? (
          <SpacesPanel />
        ) : selected === "profile" ? (
          <ProfilePanel onShowGuide={onShowGuide} />
        ) : selected === "appearance" ? (
          <AppearancePanel />
        ) : selected === "plugins" ? (
          parts[1] ? (
            <PluginPage id={parts[1]} />
          ) : (
            <PluginManager />
          )
        ) : selected === "devices" ? (
          <Devices />
        ) : selected === "tokens" ? (
          <Tokens />
        ) : selected === "agents" ? (
          <Agents />
        ) : selected === "ai" ? (
          <AISettings />
        ) : selected === "data" ? (
          <Data />
        ) : (
          <>
            <h1>Settings</h1>
            <p>That settings section is unavailable.</p>
          </>
        )}
      </section>
    </div>
  );
}
function permissions(plugin: Plugin) {
  return (
    plugin.permission_sentences ??
    plugin.permissions.map(
      (p) =>
        ({
          "notes.read": "Reads your notes",
          "notes.create": "Creates notes",
          "notes.update_own": "Updates its own unedited notes",
          suggest: "Proposes edits for your review",
          annotate: "Adds information about notes",
          "search.text": "Adds searchable text",
          schedule: "Runs scheduled jobs",
          routes: "Provides commands",
          mcp: "Adds tools for agents",
          "net.fetch": "Fetches public web pages",
          "attachments.read": "Reads attachments",
          "attachments.write": "Adds attachments",
        })[p] ?? p,
    )
  );
}
function pluginOrigin(plugin: Plugin) {
  return plugin.origin === "bundled"
    ? "Built-in"
    : plugin.origin === "operator"
      ? "Operator-installed"
      : "Origin unknown";
}
function pluginCanEnable(plugin: Plugin) {
  return (
    plugin.state !== "removed" &&
    (plugin.can_enable === undefined || plugin.can_enable === true)
  );
}
function pluginPermissionsMatch(approved: Plugin, current: Plugin) {
  return (
    approved.permissions.length === current.permissions.length &&
    approved.permissions.every((permission) =>
      current.permissions.includes(permission),
    )
  );
}
function pluginRetainsGrants(approved: Plugin, current: Plugin) {
  // Legacy catalogs omitted grants; current catalogs must retain every prior approval.
  return (
    current.granted === undefined ||
    (Array.isArray(current.granted) &&
      approved.permissions.every((permission) =>
        current.granted?.includes(permission),
      ))
  );
}
function pluginCountKnown(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}
function pluginCanDelete(plugin: Plugin) {
  return (
    plugin.can_delete_data === true ||
    (plugin.state !== "removed" && plugin.can_delete_data === undefined)
  );
}
const capabilityNames: Record<AICapability, [title: string, model: string]> = {
  chat: ["Chat", "a chat model"],
  embed: ["Embeddings", "an embedding model"],
  transcribe: ["Transcription", "a transcription model"],
  vision: ["Vision", "a vision model"],
};
function aiCapabilities(plugin: Plugin) {
  return plugin.permissions
    .filter((p) => p.startsWith("ai."))
    .map((p) => p.slice(3) as AICapability)
    .filter((capability) => capability in capabilityNames);
}
/** AI is off when every AI plugin's catalog row names no model at all. */
function aiIsOff(plugins: Plugin[]) {
  const users = plugins.filter(
    (p) => p.state !== "removed" && aiCapabilities(p).length,
  );
  return (
    users.length > 0 &&
    users.every(
      (p) =>
        p.ai_destinations !== undefined &&
        !Object.keys(p.ai_destinations).length,
    )
  );
}
function pluginFacts(plugin: Plugin) {
  if (plugin.state === "removed") return "Plugin code is no longer installed";
  const ai = aiCapabilities(plugin);
  if (ai.length) {
    const missing = ai.filter((c) => !plugin.ai_destinations?.[c]);
    // With no model for any capability, one short requirement replaces a line per capability.
    if (missing.length === ai.length)
      return `Needs ${missing.map((c) => capabilityNames[c][1]).join(" and ")}`;
    return ai
      .map((capability) => {
        const [title, model] = capabilityNames[capability];
        const destination = plugin.ai_destinations?.[capability];
        return destination
          ? `${title}: ${destination.model} at ${destination.host ?? destination.profile} · ${destination.leaves_network ? "Outside your network" : "On your network"}`
          : `${title}: no ${model.split(" ").slice(1).join(" ")} is set up`;
      })
      .join("; ");
  }
  const destinations = (
    (plugin as unknown as { network_destinations?: (string | null)[] })
      .network_destinations ?? []
  ).filter(
    (value): value is string => typeof value === "string" && !!value.trim(),
  );
  return destinations.length
    ? `Connects to ${destinations.join(", ")} · Never reads private notes`
    : plugin.permissions.some((p) => p.startsWith("net.out:"))
      ? "Connects to the configured server · Never reads private notes"
      : plugin.permissions.includes("net.fetch")
        ? "Fetches public web pages · Never reads private notes"
        : plugin.kind === "device"
          ? "Stays on the phone"
          : "Stays on your server";
}
function slotSentences(plugin: Plugin) {
  const c = plugin.contributes ?? {};
  return (
    [
      c.views ? "A view in the rail" : null,
      c.commands ? "Commands in the palette or note actions" : null,
      c.panel ? "A section in About this note" : null,
      c.meta ? "One fact in the note’s meta line" : null,
      c.slip ? "One daily slip" : null,
      plugin.permissions.includes("suggest")
        ? "Pills under notes and the shared Review queue"
        : null,
    ]
      .filter(Boolean)
      .join(". ") || "Settings only."
  );
}
function usePluginActions() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      plugin,
      on,
      intent,
    }: {
      plugin: Plugin;
      on: boolean;
      intent?: "undo";
    }) => {
      const current = client
        .getQueryData<{ plugins: Plugin[] }>(["catalog"])
        ?.plugins.find((item) => item.id === plugin.id);
      if (
        !current ||
        current.state === "removed" ||
        current.pinned_by_env ||
        (on &&
          (!pluginCanEnable(current) ||
            !pluginPermissionsMatch(plugin, current) ||
            (intent === "undo" && !pluginRetainsGrants(plugin, current))))
      )
        throw new Error(
          "That plugin switch is unavailable. Review its current permissions.",
        );
      return on
        ? api.enablePlugin(current.id, plugin.permissions)
        : api.disablePlugin(current.id);
    },
    onSuccess: () => {
      for (const key of [
        "catalog",
        "manifest",
        "settings",
        "notes",
        "note",
        "suggestions",
        "slip",
      ])
        void client.invalidateQueries({ queryKey: [key] });
    },
  });
}
function PermissionDialog({
  plugin,
  onClose,
  onEnable,
  busy,
  error,
  opener,
}: {
  plugin: Plugin;
  onClose: () => void;
  onEnable: () => void;
  busy: boolean;
  error: Error | null;
  opener: HTMLElement | null;
}) {
  return (
    <Dialog
      title={`Turn on ${plugin.name}?`}
      opener={opener}
      onClose={onClose}
      footer={
        <>
          <button disabled={busy} onClick={onClose}>
            Cancel
          </button>
          <button className="primary" disabled={busy} onClick={onEnable}>
            {busy ? "Turning on…" : "Turn on"}
          </button>
        </>
      }
    >
      <p>{plugin.description}</p>
      <h3>It will</h3>
      <ul>
        {permissions(plugin).map((p, i) => (
          <li key={i}>{p}</li>
        ))}
      </ul>
      <h3>Where text goes</h3>
      <p className="muted">{pluginFacts(plugin)}</p>
      <h3>Where it shows up</h3>
      <p className="muted">{slotSentences(plugin)}</p>
      <ErrorLine error={error} />
    </Dialog>
  );
}
/** The chat and embedding models most plugins use, from the catalog's routes. */
function aiSummary(plugins: Plugin[]) {
  const seen = new Map<AICapability, { model: string; host: string }>();
  for (const p of plugins)
    for (const [capability, destination] of Object.entries(
      p.ai_destinations ?? {},
    ) as [AICapability, NonNullable<Plugin["ai_destinations"]>[AICapability]][])
      if (destination && !p.ai_route?.[capability] && !seen.has(capability))
        seen.set(capability, {
          model: destination.model,
          host: destination.host ?? destination.profile,
        });
  const parts = (["chat", "embed"] as const).flatMap((capability) => {
    const route = seen.get(capability);
    return route
      ? [{ ...route, title: capability === "chat" ? "Chat" : "Embed" }]
      : [];
  });
  if (!parts.length) return null;
  const hosts = new Set(parts.map((part) => part.host));
  return hosts.size === 1
    ? `${parts.map((part) => `${part.title} ${part.model}`).join(" · ")} · at ${parts[0].host}`
    : parts
        .map((part) => `${part.title} ${part.model} at ${part.host}`)
        .join(" · ");
}
/** Switching off asks nothing; one toast offers Undo for a few seconds (03 §6.4). */
function useSwitchOff() {
  const action = usePluginActions();
  const toast = useToast();
  const report = (error: Error) => toast.show({ message: error.message });
  const switchOff = (plugin: Plugin) =>
    action.mutate(
      { plugin, on: false },
      {
        onError: report,
        onSuccess: (receipt) => {
          if (receipt.switch !== "off" || receipt.state === "removed") return;
          // Undo re-checks the current catalog, so it never grants a changed permission set.
          toast.show({
            message: `${plugin.name} is off`,
            actionLabel: "Undo",
            onAction: () =>
              action.mutate(
                { plugin, on: true, intent: "undo" },
                { onError: report },
              ),
          });
        },
      },
    );
  return [action, switchOff] as const;
}
function PluginManager() {
  const query = useQuery({ queryKey: ["catalog"], queryFn: api.catalog });
  const [onlyOn, setOnlyOn] = useState(false);
  const [enable, setEnable] = useState<Plugin | null>(null);
  const opener = useRef<HTMLElement>(null);
  const [action, switchOff] = useSwitchOff();
  const plugins =
    query.data?.plugins.filter(
      (p) => p.category !== "look" || p.state === "removed",
    ) ?? [];
  const pendingEnable = plugins.find((p) => p.id === enable?.id);
  const canConfirmEnable =
    pendingEnable &&
    !pendingEnable.pinned_by_env &&
    pluginCanEnable(pendingEnable);
  const enabled = plugins.filter((p) => p.switch === "on").length;
  const aiOff = aiIsOff(plugins);
  const summary = aiOff ? null : aiSummary(plugins);
  return (
    <>
      <h1>Plugins</h1>
      <p className="muted">
        Everything beyond notes, labels and search. Switch one on and it takes
        its place; switch it off and it leaves the screen, while its data and
        any notes it wrote stay. New plugins are added by whoever runs the
        server.
      </p>
      {aiOff ? (
        <p className="notice">
          AI is off, so plugins that need a model wait until one is set up.{" "}
          <Link href="/settings/ai">AI settings</Link>
        </p>
      ) : (
        summary && (
          <p className={styles.facts}>
            {summary} · <Link href="/settings/ai">AI settings</Link>
          </p>
        )
      )}
      <div className={styles.segments}>
        <button aria-pressed={!onlyOn} onClick={() => setOnlyOn(false)}>
          All {plugins.length}
        </button>
        <button aria-pressed={onlyOn} onClick={() => setOnlyOn(true)}>
          On {enabled}
        </button>
      </div>
      {query.isPending && <p>Loading plugins…</p>}
      {["capture", "organize", "recall", "develop", "agents", "data"].map(
        (category) => {
          const all = plugins.filter(
            (p) =>
              (p.category === "look" && p.state === "removed"
                ? "data"
                : (p.category ?? "data")) === category,
          );
          const items = all.filter((p) => !onlyOn || p.switch === "on");
          if (!items.length) return null;
          return (
            <section key={category}>
              <h2 className="overline">
                {category} · {all.filter((p) => p.switch === "on").length} of{" "}
                {all.length} on
              </h2>
              <ul className={styles.list}>
                {items.map((p) => {
                  const linked = p.switch === "on" || p.state === "removed";
                  return (
                    <li
                      className={`${styles.plugin} ${linked ? styles.rowLink : ""}`}
                      key={p.id}
                    >
                      <div className={styles.between}>
                        <div>
                          <strong>
                            {linked ? (
                              <Link
                                href={`/settings/plugins/${encodeURIComponent(p.id)}`}
                              >
                                {p.name}
                              </Link>
                            ) : (
                              p.name
                            )}
                          </strong>
                          {p.kind === "device" && (
                            <small className={styles.facts}> · Android</small>
                          )}
                          <p>{p.description}</p>
                          <div className={styles.facts}>
                            <span>
                              {p.origin === "bundled"
                                ? pluginFacts(p)
                                : `${pluginOrigin(p)} · ${pluginFacts(p)}`}
                            </span>
                            {p.state === "waiting" && !aiOff && (
                              <>
                                {" · "}
                                <Link href="/settings/ai">AI settings</Link>
                              </>
                            )}
                            {p.state === "failed" && (
                              <>
                                {" · Paused after failed runs · "}
                                <Link href="/status">Details</Link>
                              </>
                            )}
                          </div>
                        </div>
                        <div className={styles.actions}>
                          {p.pinned_by_env ? (
                            <span className={styles.facts}>
                              Set in the environment ·{" "}
                              {p.switch === "on" ? "On" : "Off"}
                            </span>
                          ) : p.state === "removed" ? (
                            <span className={styles.facts}>Removed · Off</span>
                          ) : (
                            <input
                              type="checkbox"
                              role="switch"
                              aria-label={`Enable ${p.name}`}
                              className={styles.switch}
                              checked={p.switch === "on"}
                              disabled={
                                action.isPending ||
                                (p.switch !== "on" && !pluginCanEnable(p))
                              }
                              onChange={(e) => {
                                if (e.target.checked) {
                                  action.reset();
                                  opener.current = e.currentTarget;
                                  setEnable(p);
                                } else switchOff(p);
                              }}
                            />
                          )}
                        </div>
                      </div>
                    </li>
                  );
                })}
              </ul>
            </section>
          );
        },
      )}
      <ErrorLine error={query.error} />
      {canConfirmEnable && pendingEnable && (
        <PermissionDialog
          plugin={pendingEnable}
          opener={opener.current}
          onClose={() => setEnable(null)}
          busy={action.isPending}
          error={action.error}
          onEnable={() =>
            action.mutate(
              { plugin: pendingEnable, on: true },
              { onSuccess: () => setEnable(null) },
            )
          }
        />
      )}
      <PluginArrangement />
    </>
  );
}
/** Plugins whose order or visibility matters: on, and filling a shared slot. */
function arrangeable(plugin: Plugin) {
  const c = plugin.contributes ?? {};
  return (
    plugin.state === "enabled" &&
    !!(
      c.panel ||
      c.meta ||
      c.views?.length ||
      c.mcp_tools?.length ||
      plugin.permissions.includes("suggest")
    )
  );
}
function PluginArrangement() {
  const catalog = useQuery({ queryKey: ["catalog"], queryFn: api.catalog });
  const settings = useQuery({ queryKey: ["settings"], queryFn: api.settings });
  const client = useQueryClient();
  const save = useMutation({
    mutationFn: api.saveSettings,
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["settings"] });
      void client.invalidateQueries({ queryKey: ["catalog"] });
      void client.invalidateQueries({ queryKey: ["manifest"] });
    },
  });
  const order = settings.data?.plugin_order ?? [];
  const shown = order.flatMap((id) => {
    const plugin = catalog.data?.plugins.find((p) => p.id === id);
    return plugin && arrangeable(plugin) ? [plugin] : [];
  });
  // Moving swaps with the neighbour on screen; hidden plugins keep their places.
  const move = (id: string, direction: number) => {
    const index = shown.findIndex((p) => p.id === id);
    const other = shown[index + direction]?.id;
    if (index < 0 || !other) return;
    const next = [...order];
    const [a, b] = [next.indexOf(id), next.indexOf(other)];
    [next[a], next[b]] = [next[b], next[a]];
    save.mutate({ plugin_order: next });
  };
  const toggle = (key: "hidden_views" | "hidden_mcp_tools", id: string) => {
    const values = settings.data?.[key] ?? [];
    save.mutate({
      [key]: values.includes(id)
        ? values.filter((v) => v !== id)
        : [...values, id],
    });
  };
  if (shown.length < 1) return null;
  return (
    <details className={styles.arrange}>
      <summary>Arrange</summary>
      <p className="muted">
        Your order chooses the note panel, suggestion order and meta fact.
      </p>
      {shown.map((p, index) => (
        <div className={styles.plugin} key={p.id}>
          <div className={styles.between}>
            <span>{p.name}</span>
            <div className={styles.actions}>
              <button
                aria-label={`Move ${p.name} up`}
                disabled={save.isPending || index === 0}
                onClick={() => move(p.id, -1)}
              >
                ↑
              </button>
              <button
                aria-label={`Move ${p.name} down`}
                disabled={save.isPending || index === shown.length - 1}
                onClick={() => move(p.id, 1)}
              >
                ↓
              </button>
            </div>
          </div>
          {p.contributes?.views?.map((v) => (
            <label key={v.id} className={styles.row}>
              <input
                type="checkbox"
                checked={
                  !settings.data?.hidden_views.includes(`${p.id}/${v.id}`)
                }
                disabled={save.isPending}
                onChange={() => toggle("hidden_views", `${p.id}/${v.id}`)}
              />
              Show {v.title} in the rail
            </label>
          ))}
          {p.contributes?.mcp_tools?.map((t) => (
            <label key={t.name} className={styles.row}>
              <input
                type="checkbox"
                checked={
                  !settings.data?.hidden_mcp_tools.includes(`${p.id}_${t.name}`)
                }
                disabled={save.isPending}
                onChange={() => toggle("hidden_mcp_tools", `${p.id}_${t.name}`)}
              />
              Expose{" "}
              <code>
                {p.id}_{t.name}
              </code>{" "}
              to agents
            </label>
          ))}
        </div>
      ))}
      <ErrorLine error={save.error} />
    </details>
  );
}
function PluginPage({ id }: { id: string }) {
  const catalog = useQuery({ queryKey: ["catalog"], queryFn: api.catalog });
  const plugin = catalog.data?.plugins.find((p) => p.id === id);
  const removed = plugin?.state === "removed";
  const query = useQuery({
    queryKey: ["plugin-settings", id],
    queryFn: () => api.pluginSettings(id),
    enabled: !!plugin && !removed,
  });
  const client = useQueryClient();
  const [enable, setEnable] = useState(false);
  const [remove, setRemove] = useState(false);
  const enabler = useRef<HTMLInputElement>(null);
  const remover = useRef<HTMLButtonElement>(null);
  const [result, setResult] = useState<unknown>(null);
  const [commandOrigin, setCommandOrigin] = useState<CommandOrigin>();
  const [deviceValues, setDeviceValues] = useState<Args>(() => {
    try {
      return JSON.parse(
        localStorage.getItem(`spunto-plugin-device-${id}`) ?? "{}",
      ) as Args;
    } catch {
      return {};
    }
  });
  const [action, switchOff] = useSwitchOff();
  const save = useMutation({
    mutationFn: (values: Args) => {
      const current = client
        .getQueryData<{ plugins: Plugin[] }>(["catalog"])
        ?.plugins.find((item) => item.id === id);
      if (!current || current.state === "removed")
        throw new Error("Plugin settings are unavailable.");
      return api.savePluginSettings(id, values);
    },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["plugin-settings", id] });
      void client.invalidateQueries({ queryKey: ["catalog"] });
    },
  });
  const deletion = useMutation({
    mutationFn: () => {
      const current = client
        .getQueryData<{ plugins: Plugin[] }>(["catalog"])
        ?.plugins.find((item) => item.id === id);
      if (
        !current ||
        !pluginCanDelete(current) ||
        !pluginCountKnown(current.data_count)
      )
        throw new Error("Plugin data cleanup is unavailable.");
      return api.deletePluginData(id);
    },
    onSuccess: () => {
      setRemove(false);
      void client.invalidateQueries({ queryKey: ["catalog"] });
      void client.invalidateQueries({ queryKey: ["notes"] });
    },
  });
  const page = <PageTitle page={`Settings · ${plugin?.name ?? "Plugins"}`} />;
  const back = (
    <Link href="/settings/plugins" className={styles.back}>
      ← Plugins
    </Link>
  );
  if (catalog.isPending || (!!plugin && !removed && query.isPending))
    return (
      <>
        {page}
        <p>Loading plugin settings…</p>
      </>
    );
  if (!plugin)
    return (
      <>
        {page}
        {back}
        <h1>Plugin unavailable</h1>
        <ErrorLine error={catalog.error} />
      </>
    );
  const data = plugin;
  const recordCount = pluginCountKnown(plugin.data_count)
    ? plugin.data_count
    : undefined;
  const canDelete = pluginCanDelete(plugin);
  const usesAI = aiCapabilities(plugin).length > 0;
  const aiOff = aiIsOff([plugin]);
  const saveValues = (values: Args) => {
    const device: Args = {},
      server: Args = {};
    for (const [key, value] of Object.entries(values)) {
      if (
        (query.data?.schema.properties?.[key] as Record<string, unknown>)?.[
          "x-scope"
        ] === "device"
      )
        device[key] = value;
      else server[key] = value;
    }
    localStorage.setItem(`spunto-plugin-device-${id}`, JSON.stringify(device));
    setDeviceValues(device);
    save.mutate(server);
  };
  return (
    <>
      {page}
      {back}
      <div className={styles.between}>
        <h1>{plugin.name}</h1>
        {plugin.pinned_by_env ? (
          <span className={styles.facts}>
            Set in the environment · {plugin.switch === "on" ? "On" : "Off"}
          </span>
        ) : removed ? (
          <span className={styles.facts}>Removed · Off</span>
        ) : (
          <input
            ref={enabler}
            className={styles.switch}
            type="checkbox"
            role="switch"
            aria-label={`Enable ${plugin.name}`}
            checked={plugin.switch === "on"}
            disabled={
              action.isPending ||
              (plugin.switch !== "on" && !pluginCanEnable(plugin))
            }
            onChange={(e) =>
              e.target.checked ? setEnable(true) : switchOff(plugin)
            }
          />
        )}
      </div>
      <p>{plugin.description}</p>
      <p className={styles.facts}>
        {humanize(plugin.category ?? "data")} · {pluginOrigin(plugin)} ·{" "}
        {plugin.version}
      </p>
      {!!plugin.pinned_by_env && (
        <p className={styles.secret}>
          Set in the environment: <code>SPUNTO_PLUGINS</code>
        </p>
      )}
      {removed && (
        <p className="notice">
          Removed. Plugin code is no longer installed. Its stored data and notes
          remain.
        </p>
      )}
      {plugin.state === "waiting" &&
        (aiCapabilities(plugin).length ? (
          <p className="notice">
            {pluginFacts(plugin)}. It stays on and starts working once that is
            set up.{" "}
            {aiOff ? (
              <>
                AI is off. <AIOffHint />
              </>
            ) : (
              <Link href="/settings/ai">AI settings</Link>
            )}
          </p>
        ) : (
          plugin.last_error && <p className="notice">{plugin.last_error}</p>
        ))}
      {!removed && query.data && (
        <SchemaForm
          key={`${id}-${JSON.stringify(query.data.values)}`}
          schema={query.data.schema}
          initial={{ ...query.data.values, ...deviceValues }}
          pluginId={id}
          secrets={query.data.secrets}
          onSave={saveValues}
          busy={save.isPending}
        />
      )}
      {!removed && save.isSuccess && (
        <p role="status" className={styles.facts}>
          Settings saved.
        </p>
      )}
      {!removed && usesAI && !aiOff && (
        <>
          <h2>AI</h2>
          <AIRouting plugin={plugin} />
        </>
      )}
      {plugin.state === "enabled" && !!plugin.contributes?.commands?.length && (
        <>
          <h2>Run</h2>
          <p className="muted">
            Note commands appear in the palette while a note is open.
          </p>
          <PluginCommands
            pluginId={id}
            where="palette"
            onResult={(value, origin) => {
              setResult(value);
              setCommandOrigin(origin);
            }}
          />
          {result && (
            <CommandResult
              value={result}
              origin={commandOrigin}
              onDismiss={() => setResult(null)}
            />
          )}
        </>
      )}
      <h2>Activity</h2>
      <p className="muted">
        {data.last_run_at
          ? `Last run ${formatWhen(data.last_run_at)}`
          : "No runs yet."}
        {data.state === "waiting"
          ? ""
          : data.last_error
            ? ` · ${data.last_error}`
            : " · No failures"}
      </p>
      <h2>Access</h2>
      <ul>
        {permissions(plugin).map((text, i) => (
          <li key={i}>{text}</li>
        ))}
      </ul>
      {removed ? (
        <p className="muted">
          Access above describes the retained plugin. No plugin code is running.
        </p>
      ) : (
        <p className="muted">
          {pluginFacts(plugin)}. Up to 3 commands, 10 panel items and 5
          unattended notes per day.
        </p>
      )}
      {!removed &&
        plugin.contributes?.mcp_tools?.map((t) => (
          <p key={t.name} className={styles.facts}>
            Adds agent tool{" "}
            <code>
              {id}_{t.name}
            </code>
            : {t.description}
          </p>
        ))}
      <h2>Stored data</h2>
      <p className="muted">
        {recordCount === undefined ? "…" : plural(recordCount, "record")} ·{" "}
        {formatBytes(plugin.data_size_bytes ?? 0)}. Its notes stay: they are
        notes.
      </p>
      {canDelete && (
        <button
          ref={remover}
          className="danger"
          onClick={() => setRemove(true)}
        >
          Delete this plugin’s data…
        </button>
      )}
      <ErrorLine error={(!removed ? query.error : null) ?? save.error} />
      {enable && !plugin.pinned_by_env && pluginCanEnable(plugin) && (
        <PermissionDialog
          plugin={plugin}
          opener={enabler.current}
          onClose={() => setEnable(false)}
          busy={action.isPending}
          error={action.error}
          onEnable={() =>
            action.mutate(
              { plugin, on: true },
              { onSuccess: () => setEnable(false) },
            )
          }
        />
      )}
      {remove && canDelete && (
        <Dialog
          title={`Delete ${plugin.name}’s data?`}
          opener={remover.current}
          onClose={() => setRemove(false)}
          footer={
            <>
              <button
                disabled={deletion.isPending}
                onClick={() => setRemove(false)}
              >
                Cancel
              </button>
              <button
                className="danger"
                disabled={
                  deletion.isPending || !pluginCountKnown(plugin.data_count)
                }
                onClick={() => deletion.mutate()}
              >
                Delete {recordCount ?? "…"} records
              </button>
            </>
          }
        >
          <p>
            This deletes {recordCount ?? "…"} plugin records, including
            suggestions and annotations. Its notes stay: they are notes.
          </p>
          <p>This cannot be undone.</p>
          <ErrorLine error={deletion.error} />
        </Dialog>
      )}
    </>
  );
}
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
function Devices() {
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
function Tokens() {
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
function Agents() {
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
function Data() {
  const catalog = useQuery({ queryKey: ["catalog"], queryFn: api.catalog });
  const status = useQuery({ queryKey: ["status"], queryFn: api.status });
  const client = useQueryClient();
  const toast = useToast();
  const [confirm, dialog] = useConfirm();
  const [enable, setEnable] = useState(false);
  const enabler = useRef<HTMLButtonElement>(null);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const action = usePluginActions();
  const importer = catalog.data?.plugins.find(
    (plugin) => plugin.id === "markdown-import",
  );
  const count = Number(
    (status.data?.database as { trashed_notes?: number } | undefined)
      ?.trashed_notes ?? 0,
  );
  const deletion = useMutation({
    mutationFn: () =>
      api.request<{ deleted: number }>("/api/v1/trash/empty", {
        method: "POST",
      }),
    onSuccess: () => {
      toast.show({ message: "Trash emptied" });
      for (const key of ["notes", "status", "labels"])
        void client.invalidateQueries({ queryKey: [key] });
    },
  });
  const exportData = async () => {
    setExporting(true);
    setExportError(null);
    try {
      const url = URL.createObjectURL(await api.exportNotes());
      const link = document.createElement("a");
      link.href = url;
      link.download = "spunto-export.zip";
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      setExportError(error instanceof Error ? error.message : "Export failed.");
    } finally {
      setExporting(false);
    }
  };
  return (
    <>
      <h1>Data</h1>
      <h2>Export</h2>
      <p className="muted">
        All your notes as Markdown, their metadata and attachments in one ZIP.
      </p>
      <button
        className="primary"
        disabled={exporting}
        onClick={() => void exportData()}
      >
        {exporting ? "Preparing export…" : "Export notes"}
      </button>
      {exportError && (
        <p role="alert" className="error">
          {exportError}
        </p>
      )}
      <h2>Import Markdown</h2>
      {importer?.state === "enabled" ? (
        <MarkdownImport />
      ) : importer && importer.state !== "removed" ? (
        <>
          <p className="muted">
            Import a Markdown ZIP, such as a Spunto export or an Obsidian vault.
            The {importer.name} plugin reads it; turn it on to choose a file.
          </p>
          {importer.pinned_by_env ? (
            <p className={styles.facts}>Set in the environment · Off</p>
          ) : (
            <button
              ref={enabler}
              disabled={!pluginCanEnable(importer)}
              onClick={() => {
                action.reset();
                setEnable(true);
              }}
            >
              Turn on {importer.name}…
            </button>
          )}
        </>
      ) : (
        <p className="muted">
          Markdown import is not available on this server.
        </p>
      )}
      <h2>Trash</h2>
      <p className="muted">
        {plural(count, "note")} in Trash. Notes there are deleted for good after
        30 days.
      </p>
      <div className={styles.row}>
        <Link href="/trash">Open Trash</Link>
        {count > 0 && (
          <button
            className="danger"
            disabled={deletion.isPending}
            onClick={async (e) => {
              if (
                await confirm({
                  opener: e.currentTarget,
                  title: `Delete ${plural(count, "note")} for good?`,
                  body: `This permanently deletes ${plural(count, "trashed note")} and their attachments. This cannot be undone.`,
                  confirmLabel: `Delete ${plural(count, "note")}`,
                  danger: true,
                })
              )
                deletion.mutate();
            }}
          >
            Empty trash…
          </button>
        )}
      </div>
      <ErrorLine error={status.error ?? deletion.error} />
      {dialog}
      {enable && importer && pluginCanEnable(importer) && (
        <PermissionDialog
          plugin={importer}
          opener={enabler.current}
          onClose={() => setEnable(false)}
          busy={action.isPending}
          error={action.error}
          onEnable={() =>
            action.mutate(
              { plugin: importer, on: true },
              { onSuccess: () => setEnable(false) },
            )
          }
        />
      )}
    </>
  );
}
const pluginStates: Record<string, string> = {
  enabled: "On",
  disabled: "Off",
  available: "Off",
  waiting: "Waiting for a requirement",
  failed: "Paused after failed runs",
  removed: "Removed",
};
function duration(seconds: number) {
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);
  return days
    ? plural(days, "day")
    : hours
      ? plural(hours, "hour")
      : plural(minutes, "minute");
}
export function StatusPage() {
  useDocumentTitle("Server status");
  const query = useQuery({ queryKey: ["status"], queryFn: api.status });
  const catalog = useQuery({ queryKey: ["catalog"], queryFn: api.catalog });
  const name = (id: string) =>
    catalog.data?.plugins.find((p) => p.id === id)?.name ?? humanize(id);
  const data = query.data;
  const ai = data?.ai as
    | {
        enabled: boolean;
        capabilities: string[];
        health: AIHealth[];
        indexing?: VectorStatus;
        stats?: {
          plugin_id: string;
          capability: string;
          outcome: string;
          n: number;
        }[];
      }
    | undefined;
  const database = data?.database as
    | {
        size_bytes: number;
        size_kind?: string;
        notes: number;
        private_notes: number;
        trashed_notes: number;
      }
    | undefined;
  const backup = data?.backup as
    | { last_at?: string | null; age_hours?: number | null; managed?: boolean }
    | undefined;
  const jobs = data?.jobs as
    { plugin_id: string; status: string; count: number }[] | undefined;
  const plugins = data?.plugins as
    { id: string; state: string; last_error?: string | null }[] | undefined;
  return (
    <div className={styles.status}>
      <h1>Server status</h1>
      {query.isPending ? (
        <p>Loading status…</p>
      ) : query.isError ? (
        <ErrorLine error={query.error} />
      ) : (
        <>
          <p className="muted">
            Spunto {String(data?.version)} · Database schema{" "}
            {String(data?.schema)}
          </p>
          <dl className={styles.counts}>
            {[
              ["Notes", (database?.notes ?? 0).toLocaleString()],
              [
                "Private notes",
                (database?.private_notes ?? 0).toLocaleString(),
              ],
              ["In Trash", (database?.trashed_notes ?? 0).toLocaleString()],
              [
                database?.size_kind === "note_rows"
                  ? "Note text (estimate)"
                  : "Database size",
                formatBytes(database?.size_bytes ?? 0),
              ],
              ["Running for", duration(Number(data?.uptime_seconds ?? 0))],
            ].map(([title, value]) => (
              <div key={title}>
                <dt>{title}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
          <h2>Backup</h2>
          <p>
            {backup?.managed
              ? "Backups are managed by the host."
              : backup?.last_at
                ? `Last backup ${formatWhen(backup.last_at)} · ${plural(Math.round(backup.age_hours ?? 0), "hour")} ago`
                : "No backup yet."}
          </p>
          <h2>AI</h2>
          <p>
            {ai?.enabled
              ? ai.capabilities
                  .map(
                    (capability) =>
                      capabilityNames[
                        capability.replace("ai.", "") as AICapability
                      ]?.[0] ?? humanize(capability.replace("ai.", "")),
                  )
                  .join(" · ")
              : "AI is off."}
          </p>
          <AIHealthResults rows={ai?.health ?? []} />
          {ai?.indexing && (
            <p className="muted">
              {ai.indexing.available
                ? `${plural(ai.indexing.chunks, "embedding chunk")}`
                : "Vector index unavailable"}
              {ai.indexing.enabled ? " · Indexing on" : " · Indexing paused"}
            </p>
          )}
          {ai?.stats?.map((stat, index) => (
            <p className={styles.facts} key={index}>
              {name(stat.plugin_id)} ·{" "}
              {capabilityNames[
                stat.capability.replace("ai.", "") as AICapability
              ]?.[0] ?? humanize(stat.capability)}{" "}
              · {humanize(stat.outcome)}: {stat.n.toLocaleString()}
            </p>
          ))}
          <Link href="/settings/ai">AI settings</Link>
          <h2>Plugins and jobs</h2>
          {plugins?.map((p) => (
            <p className="muted" key={p.id}>
              {name(p.id)} · {pluginStates[p.state] ?? humanize(p.state)}
              {p.last_error ? ` · ${p.last_error}` : ""}
            </p>
          ))}
          {jobs?.map((j, i) => (
            <p className={styles.facts} key={i}>
              {name(j.plugin_id)} · {plural(j.count, "job")}{" "}
              {humanize(j.status).toLowerCase()}
            </p>
          ))}
          <h2>Access</h2>
          <p className="muted">
            {plural(
              Array.isArray(data?.credentials) ? data.credentials.length : 0,
              "active credential",
            )}
            .
          </p>
          <button onClick={() => void query.refetch()}>Refresh status</button>
        </>
      )}
    </div>
  );
}
