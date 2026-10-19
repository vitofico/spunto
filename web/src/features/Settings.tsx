import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { copyText } from "./copy";
import { agentSnippets } from "./agentSnippets";
import { AppearancePanel } from "./AppearancePanel";
import { ProfilePanel } from "./ProfilePanel";
import { AccountPanel } from "./AccountPanel";
import { useHosted } from "../lib/query";
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
import { AISettings, AIRouting, AIHealthResults } from "./AISettings";
import { PairDevice } from "./PairDevice";
import { MarkdownImport } from "./MarkdownImport";
import { SpacesPanel } from "./Collaboration";
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
    <p className={styles.error} role="alert">
      {error.message}
    </p>
  ) : null;
}
export function SettingsPage({
  section = "appearance",
  onShowGuide,
}: {
  section?: string;
  onShowGuide?: () => void;
}) {
  const hosted = useHosted();
  const parts = section.replace(/^\//, "").split("/");
  const selected = parts[0] || "appearance";
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
      <nav ref={navRef} className={styles.nav} aria-label="Settings">
        {sections
          .filter(([id]) => id !== "account" || hosted)
          .map(([id, title]) => (
            <a
              href={`/settings/${id}`}
              aria-current={id === selected ? "page" : undefined}
              key={id}
            >
              {title}
            </a>
          ))}
      </nav>
      <section className={styles.section}>
        {onShowGuide && (
          <div className={styles.guideAction}>
            <button onClick={onShowGuide}>Show guide</button>
          </div>
        )}
        {selected === "account" && hosted ? (
          <AccountPanel />
        ) : selected === "spaces" ? (
          <SpacesPanel />
        ) : selected === "profile" ? (
          <ProfilePanel />
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
function pluginFacts(plugin: Plugin) {
  if (plugin.state === "removed") return "Plugin code is no longer installed";
  const ai = plugin.permissions.filter((p) => p.startsWith("ai."));
  if (ai.length)
    return ai
      .map((permission) => {
        const capability = permission.slice(3) as AICapability;
        const label = {
          chat: "Chat",
          embed: "Embeddings",
          transcribe: "Transcription",
          vision: "Vision",
        }[capability];
        const destination = plugin.ai_destinations?.[capability];
        if (destination)
          return `${label}: ${destination.model} at ${destination.host ?? destination.profile} · ${destination.leaves_network ? "Outside your network" : "On your network"}`;
        if (plugin.ai_destinations !== undefined)
          return `${label}: no model is configured for this plugin`;
        return `Requires ${capability === "embed" ? "an embedding" : `a ${label.toLowerCase()}`} model`;
      })
      .join("; ");
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
}: {
  plugin: Plugin;
  onClose: () => void;
  onEnable: () => void;
  busy: boolean;
  error: Error | null;
}) {
  return (
    <Dialog
      title={`Turn on ${plugin.name}?`}
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
      <p className={styles.muted}>{pluginFacts(plugin)}</p>
      <h3>Where it shows up</h3>
      <p className={styles.muted}>{slotSentences(plugin)}</p>
      <ErrorLine error={error} />
    </Dialog>
  );
}
function PluginManager() {
  const query = useQuery({ queryKey: ["catalog"], queryFn: api.catalog });
  const [onlyOn, setOnlyOn] = useState(false);
  const [enable, setEnable] = useState<Plugin | null>(null);
  const [undo, setUndo] = useState<Plugin | null>(null);
  const action = usePluginActions();
  useEffect(() => {
    if (!undo) return;
    const timer = setTimeout(() => setUndo(null), 7000);
    return () => clearTimeout(timer);
  }, [undo]);
  const plugins =
    query.data?.plugins.filter(
      (p) => p.category !== "look" || p.state === "removed",
    ) ?? [];
  const pendingEnable = plugins.find((p) => p.id === enable?.id);
  const canConfirmEnable =
    pendingEnable &&
    !pendingEnable.pinned_by_env &&
    pluginCanEnable(pendingEnable);
  const pendingUndo = plugins.find((p) => p.id === undo?.id);
  const canUndo =
    undo &&
    pendingUndo &&
    pendingUndo.switch === "off" &&
    !pendingUndo.pinned_by_env &&
    pluginCanEnable(pendingUndo) &&
    pluginPermissionsMatch(undo, pendingUndo) &&
    pluginRetainsGrants(undo, pendingUndo);
  const enabled = plugins.filter((p) => p.switch === "on").length;
  return (
    <>
      <h1>Plugins</h1>
      <p className={styles.muted}>
        Everything beyond notes, labels and search. Switch one on and it takes
        its place; switch it off and it leaves the screen, while its data and
        any notes it wrote stay.
      </p>
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
              <h2 className={styles.overline}>
                {category} · {all.filter((p) => p.switch === "on").length} of{" "}
                {all.length} on
              </h2>
              <ul className={styles.list}>
                {items.map((p) => (
                  <li className={styles.plugin} key={p.id}>
                    <div className={styles.between}>
                      <div>
                        <strong>{p.name}</strong>
                        {p.kind === "device" && (
                          <small className={styles.facts}> · Android</small>
                        )}
                        <p>{p.description}</p>
                        <span className={styles.facts}>
                          {pluginOrigin(p)} · {p.version}
                        </span>{" "}
                        <span className={styles.facts}>
                          {pluginFacts(p)}
                          {p.state === "failed"
                            ? " · Paused after failed runs"
                            : ""}
                        </span>
                      </div>
                      <div className={styles.actions}>
                        <a
                          href={`/settings/plugins/${encodeURIComponent(p.id)}`}
                        >
                          {p.state === "removed" ? "Stored data" : "Settings"}
                        </a>
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
                                setEnable(p);
                              } else
                                action.mutate(
                                  { plugin: p, on: false },
                                  {
                                    onSuccess: (receipt) =>
                                      setUndo(
                                        receipt.switch === "off" &&
                                          receipt.state !== "removed"
                                          ? p
                                          : null,
                                      ),
                                  },
                                );
                            }}
                          />
                        )}
                        {p.state === "waiting" && (
                          <a href="/settings/ai" className={styles.facts}>
                            Requirement missing
                          </a>
                        )}
                        {p.state === "failed" && <a href="/status">Details</a>}
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            </section>
          );
        },
      )}
      {canUndo && pendingUndo && undo && (
        <p className={styles.notice}>
          {pendingUndo.name} is off.{" "}
          <button
            disabled={action.isPending}
            onClick={() =>
              action.mutate(
                { plugin: undo, on: true, intent: "undo" },
                { onSuccess: () => setUndo(null) },
              )
            }
          >
            Undo
          </button>
        </p>
      )}
      <ErrorLine
        error={query.error ?? (!canConfirmEnable ? action.error : null)}
      />
      {canConfirmEnable && pendingEnable && (
        <PermissionDialog
          plugin={pendingEnable}
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
  const move = (id: string, direction: number) => {
    const next = [...order];
    const index = next.indexOf(id);
    if (index < 0 || index + direction < 0 || index + direction >= next.length)
      return;
    [next[index], next[index + direction]] = [
      next[index + direction],
      next[index],
    ];
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
  if (!catalog.data?.plugins.length) return null;
  return (
    <>
      <h2>Arrange</h2>
      <p className={styles.muted}>
        Your order chooses the note panel, suggestion order and meta fact.
      </p>
      {order.map((id, index) => {
        const p = catalog.data?.plugins.find((p) => p.id === id);
        if (!p) return null;
        return (
          <div className={styles.plugin} key={id}>
            <div className={styles.between}>
              <span>{p.name}</span>
              <div className={styles.actions}>
                <button
                  aria-label={`Move ${p.name} up`}
                  disabled={save.isPending || index === 0}
                  onClick={() => move(id, -1)}
                >
                  ↑
                </button>
                <button
                  aria-label={`Move ${p.name} down`}
                  disabled={save.isPending || index === order.length - 1}
                  onClick={() => move(id, 1)}
                >
                  ↓
                </button>
              </div>
            </div>
            {p.state !== "removed" &&
              p.contributes?.views?.map((v) => (
                <label key={v.id} className={styles.row}>
                  <input
                    type="checkbox"
                    checked={
                      !settings.data?.hidden_views.includes(`${id}/${v.id}`)
                    }
                    disabled={save.isPending}
                    onChange={() => toggle("hidden_views", `${id}/${v.id}`)}
                  />
                  Show {v.title} in the rail
                </label>
              ))}
            {p.state !== "removed" &&
              p.contributes?.mcp_tools?.map((t) => (
                <label key={t.name} className={styles.row}>
                  <input
                    type="checkbox"
                    checked={
                      !settings.data?.hidden_mcp_tools.includes(
                        `${id}_${t.name}`,
                      )
                    }
                    disabled={save.isPending}
                    onChange={() =>
                      toggle("hidden_mcp_tools", `${id}_${t.name}`)
                    }
                  />
                  Expose{" "}
                  <code>
                    {id}_{t.name}
                  </code>{" "}
                  to agents
                </label>
              ))}
          </div>
        );
      })}
      <ErrorLine error={save.error} />
    </>
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
  const action = usePluginActions();
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
  if (catalog.isPending || (!!plugin && !removed && query.isPending))
    return <p>Loading plugin settings…</p>;
  if (!plugin)
    return (
      <>
        <h1>Plugin unavailable</h1>
        <ErrorLine error={catalog.error} />
      </>
    );
  const data = plugin;
  const recordCount = pluginCountKnown(plugin.data_count)
    ? plugin.data_count
    : undefined;
  const canDelete = pluginCanDelete(plugin);
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
      <a href="/settings/plugins">← Plugins</a>
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
              e.target.checked
                ? setEnable(true)
                : action.mutate({ plugin, on: false })
            }
          />
        )}
      </div>
      <p>{plugin.description}</p>
      <p className={styles.facts}>
        {plugin.category ?? "data"} · {pluginOrigin(plugin)} · {plugin.version}
      </p>
      {!!plugin.pinned_by_env && (
        <p className={styles.secret}>
          Set in the environment: <code>SPUNTO_PLUGINS</code>
        </p>
      )}
      {removed && (
        <p className={styles.notice}>
          Removed. Plugin code is no longer installed. Its stored data and notes
          remain.
        </p>
      )}
      {plugin.state === "waiting" && (
        <p className={styles.notice}>
          A requirement is missing. Your switch stays on and resumes when it is
          available. <a href="/settings/ai">AI settings</a>
        </p>
      )}
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
      {!removed && (
        <>
          <h2>AI</h2>
          <AIRouting plugin={plugin} />
        </>
      )}
      {!removed && !!plugin.contributes?.commands?.length && (
        <>
          <h2>Run</h2>
          <p className={styles.muted}>
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
      <p className={styles.muted}>
        {data.last_run_at
          ? `Last run ${new Date(data.last_run_at).toLocaleString()}`
          : "No runs yet."}
        {data.last_error ? ` · ${data.last_error}` : " · No failures"}
      </p>
      <h2>Access</h2>
      <ul>
        {permissions(plugin).map((text, i) => (
          <li key={i}>{text}</li>
        ))}
      </ul>
      {removed ? (
        <p className={styles.muted}>
          Access above describes the retained plugin. No plugin code is running.
        </p>
      ) : (
        <p className={styles.muted}>
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
      <p className={styles.muted}>
        {recordCount ?? "…"} records · {plugin.data_size_bytes ?? 0} bytes. Its
        notes stay: they are notes.
      </p>
      {canDelete && (
        <button className={styles.danger} onClick={() => setRemove(true)}>
          Delete this plugin’s data…
        </button>
      )}
      <ErrorLine
        error={(!removed ? query.error : null) ?? save.error ?? action.error}
      />
      {enable && !plugin.pinned_by_env && pluginCanEnable(plugin) && (
        <PermissionDialog
          plugin={plugin}
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
                className={styles.danger}
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
function CredentialRows({ kind }: { kind: "tokens" | "devices" }) {
  const query = useQuery({
    queryKey: ["credentials"],
    queryFn: api.credentials,
  });
  const client = useQueryClient();
  const [revoke, setRevoke] = useState<Credential | null>(null);
  const mutation = useMutation({
    mutationFn: (id: string) => api.revokeCredential(id),
    onSuccess: () => {
      if (revoke?.current) location.assign("/login");
      setRevoke(null);
      void client.invalidateQueries({ queryKey: ["credentials"] });
    },
  });
  const items =
    query.data?.filter((c) =>
      kind === "tokens"
        ? c.kind === "pat"
        : ["session", "device"].includes(c.kind),
    ) ?? [];
  return (
    <>
      {query.isPending ? (
        <p>Loading access…</p>
      ) : items.length ? (
        <ul className={styles.list}>
          {items.map((c) => (
            <li className={styles.plugin} key={c.id}>
              <div className={styles.between}>
                <div>
                  <strong>{c.name}</strong>
                  {c.current && (
                    <small className={styles.facts}> · This browser</small>
                  )}
                  <p>
                    {c.client ?? c.kind} · {c.scope}
                    {c.private_ok
                      ? " · Includes private notes"
                      : " · Excludes private notes"}
                    {c.external_ok
                      ? " · Includes external notes"
                      : " · Excludes external notes"}
                  </p>
                  <small className={styles.facts}>
                    Last used{" "}
                    {c.last_used_at
                      ? new Date(c.last_used_at).toLocaleString()
                      : "never"}
                    {c.expires_at
                      ? ` · Expires ${new Date(c.expires_at).toLocaleString()}`
                      : ""}
                  </small>
                </div>
                <button onClick={() => setRevoke(c)}>Revoke</button>
              </div>
            </li>
          ))}
        </ul>
      ) : (
        <p className={styles.muted}>
          {kind === "tokens" ? "No access tokens yet." : "No other devices."}
        </p>
      )}
      <ErrorLine error={query.error ?? mutation.error} />
      {revoke && (
        <Dialog
          title={`Revoke ${revoke.name}?`}
          onClose={() => setRevoke(null)}
          footer={
            <>
              <button onClick={() => setRevoke(null)}>Cancel</button>
              <button
                className={styles.danger}
                disabled={mutation.isPending}
                onClick={() => mutation.mutate(revoke.id)}
              >
                Revoke access
              </button>
            </>
          }
        >
          <p>
            This {revoke.kind === "pat" ? "token" : "device or session"} will
            lose access immediately.
            {revoke.current ? " You will be signed out." : ""}
          </p>
        </Dialog>
      )}
    </>
  );
}
function Devices() {
  const hosted = useHosted();
  const query = useQuery({
    queryKey: ["session"],
    queryFn: () => api.session(),
  });
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState(false);
  const change = useMutation({
    mutationFn: () => api.changePassword(password),
    onSuccess: () => {
      setPassword("");
      location.assign("/login");
    },
  });
  return (
    <>
      <h1>Devices</h1>
      <p className={styles.muted}>
        Every browser session and connected device, with its last use.
      </p>
      <CredentialRows kind="devices" />
      <PairDevice />
      <h2>Password</h2>
      {hosted ? (
        <p className={styles.muted}>
          Manage username, password and optional SSO in{" "}
          <a href="/settings/account">Account settings</a>.
        </p>
      ) : query.data?.password_managed ? (
        <p className={styles.secret}>
          Set in the environment. Change <code>SPUNTO_OWNER_PASSWORD</code> or{" "}
          <code>SPUNTO_OWNER_PASSWORD_HASH</code> and restart the server.
        </p>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            setConfirm(true);
          }}
        >
          <label className={styles.field}>
            New password
            <input
              type="password"
              value={password}
              autoComplete="new-password"
              minLength={12}
              maxLength={1024}
              required
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>
          <button disabled={!password}>Change password…</button>
        </form>
      )}
      <ErrorLine error={change.error} />
      {confirm && (
        <Dialog
          title="Change your password?"
          onClose={() => setConfirm(false)}
          footer={
            <>
              <button onClick={() => setConfirm(false)}>Cancel</button>
              <button
                className="primary"
                disabled={change.isPending}
                onClick={() => change.mutate()}
              >
                Change password
              </button>
            </>
          }
        >
          <p>
            Your web sessions will be signed out. Device and access tokens keep
            their current access.
          </p>
          <ErrorLine error={change.error} />
        </Dialog>
      )}
    </>
  );
}
function Tokens() {
  const [open, setOpen] = useState(false);
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

  return (
    <>
      <h1>Access tokens</h1>
      <p className={styles.muted}>
        Create a separate token for each agent or script. A read token can find
        notes without changing them.
      </p>
      <button
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
          onClose={() => setOpen(false)}
          footer={
            <>
              <button onClick={() => setOpen(false)}>Cancel</button>
              <button
                className="primary"
                disabled={
                  create.isPending ||
                  !name.trim() ||
                  (!!expires && new Date(expires).getTime() <= Date.now())
                }
                onClick={() => create.mutate()}
              >
                {create.isPending ? "Creating…" : "Create token"}
              </button>
            </>
          }
        >
          <label className={styles.field}>
            Name
            <input
              value={name}
              maxLength={120}
              required
              autoFocus
              onChange={(e) => setName(e.target.value)}
            />
          </label>
          <label className={styles.field}>
            Client
            <select
              value={clientName}
              onChange={(e) =>
                setClientName(e.target.value as TokenInput["client"])
              }
            >
              {["script", "codex", "claude-code", "monitor"].map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </label>
          <label className={styles.field}>
            Scope
            <select
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
          <label className={styles.field}>
            Expires at (optional)
            <input
              type="datetime-local"
              value={expires}
              onChange={(e) => setExpires(e.target.value)}
            />
            <small>
              Leave blank for no expiry. You can revoke access at any time.
            </small>
          </label>
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
      <p className={styles.muted}>
        Give each agent its own <a href="/settings/tokens">access token</a>.
        Start with read access and keep edit tools asking for confirmation. Set{" "}
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
  const [empty, setEmpty] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
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
      setEmpty(false);
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
      <p className={styles.muted}>
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
        <p role="alert" className={styles.error}>
          {exportError}
        </p>
      )}
      <h2>Import Markdown</h2>
      {catalog.data?.plugins.find((plugin) => plugin.id === "markdown-import")
        ?.state === "enabled" ? (
        <MarkdownImport />
      ) : (
        <p className={styles.muted}>
          <a href="/settings/plugins/markdown-import">
            Turn on Import Markdown
          </a>{" "}
          in Plugins to preview a ZIP.
        </p>
      )}
      <h2>Trash</h2>
      <p className={styles.muted}>
        {count} notes in Trash. Notes here are deleted for good after 30 days.
      </p>
      <a href="/trash">Open Trash</a>
      {count > 0 && (
        <p>
          <button className={styles.danger} onClick={() => setEmpty(true)}>
            Empty trash…
          </button>
        </p>
      )}
      <ErrorLine error={status.error ?? deletion.error} />
      {empty && (
        <Dialog
          title={`Delete ${count} notes for good?`}
          onClose={() => setEmpty(false)}
          footer={
            <>
              <button onClick={() => setEmpty(false)}>Cancel</button>
              <button
                className={styles.danger}
                disabled={deletion.isPending}
                onClick={() => deletion.mutate()}
              >
                Delete {count} notes
              </button>
            </>
          }
        >
          <p>
            This permanently deletes {count} trashed notes and their
            attachments. This cannot be undone.
          </p>
          <ErrorLine error={deletion.error} />
        </Dialog>
      )}
    </>
  );
}
export function StatusPage() {
  const query = useQuery({ queryKey: ["status"], queryFn: api.status });
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
          <p className={styles.muted}>
            Spunto {String(data?.version)} · Schema {String(data?.schema)}
          </p>
          <dl className={styles.counts}>
            {[
              ["Notes", database?.notes],
              ["Private notes", database?.private_notes],
              ["Trashed notes", database?.trashed_notes],
              [
                database?.size_kind === "note_rows"
                  ? "Note row bytes (estimate)"
                  : "Database bytes",
                database?.size_bytes,
              ],
              ["Uptime seconds", data?.uptime_seconds],
              ["Change sequence", data?.change_seq],
            ].map(([title, value]) => (
              <div key={String(title)}>
                <dt>{String(title)}</dt>
                <dd>{String(value ?? 0)}</dd>
              </div>
            ))}
          </dl>
          <h2>Backup</h2>
          <p>
            {backup?.managed
              ? "Backups are managed by the host."
              : backup?.last_at
                ? `Last backup ${new Date(backup.last_at).toLocaleString()} · ${backup.age_hours} hours ago`
                : "No backup yet."}
          </p>
          <h2>AI</h2>
          <p>
            {ai?.enabled
              ? ai.capabilities
                  .map((capability) => capability.replace("ai.", ""))
                  .join(" · ")
              : "Off."}
          </p>
          <AIHealthResults rows={ai?.health ?? []} />
          {ai?.indexing && (
            <p className={styles.muted}>
              {ai.indexing.available
                ? `${ai.indexing.chunks} embedding chunks`
                : "Vector index unavailable"}
              {ai.indexing.enabled ? " · Indexing on" : " · Indexing paused"}
            </p>
          )}
          {ai?.stats?.map((stat, index) => (
            <p className={styles.facts} key={index}>
              {stat.plugin_id} · {stat.capability} · {stat.outcome}: {stat.n}
            </p>
          ))}
          <a href="/settings/ai">AI settings</a>
          <h2>Plugins and jobs</h2>
          {plugins?.map((p) => (
            <p className={styles.muted} key={p.id}>
              {p.id} · {p.state}
              {p.last_error ? ` · ${p.last_error}` : ""}
            </p>
          ))}
          {jobs?.map((j, i) => (
            <p className={styles.facts} key={i}>
              {j.plugin_id} · {j.status} · {j.count}
            </p>
          ))}
          <h2>Access</h2>
          <p className={styles.muted}>
            {Array.isArray(data?.credentials) ? data.credentials.length : 0}{" "}
            active credentials.
          </p>
          <button onClick={() => void query.refetch()}>Refresh status</button>
        </>
      )}
    </div>
  );
}
