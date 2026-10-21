import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "wouter";
import { api } from "../lib/api";
import { useToast } from "../components/core/Toast";
import type { Args, Plugin, AICapability } from "../lib/types";
import { SchemaForm } from "./SchemaForm";
import { Dialog } from "./Dialog";
import { CommandResult, PluginCommands, type CommandOrigin } from "./Slots";
import styles from "./Feature.module.css";
import { AIRouting, AIOffHint } from "./AISettings";
import {
  formatBytes,
  formatWhen,
  humanize,
  plural,
  willSentence,
} from "./format";
import { ErrorLine, PageTitle } from "./SettingsParts";
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
export function pluginCanEnable(plugin: Plugin) {
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
export const capabilityNames: Record<
  AICapability,
  [title: string, model: string]
> = {
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
export function usePluginActions() {
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
export function PermissionDialog({
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
          <li key={i}>{willSentence(p)}</li>
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
export function PluginManager() {
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
export function PluginPage({ id }: { id: string }) {
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
