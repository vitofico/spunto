import { localizeCatalog } from "../lib/localizePlugins";
import { t, useLanguage } from "../lib/i18n";
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
          "notes.read": t("settingsplugins.reads_your_notes"),
          "notes.create": t("settingsplugins.creates_notes"),
          "notes.update_own": t(
            "settingsplugins.updates_its_own_unedited_notes",
          ),
          suggest: t("settingsplugins.proposes_edits_for_your_review"),
          annotate: t("settingsplugins.adds_information_about_notes"),
          "search.text": t("settingsplugins.adds_searchable_text"),
          schedule: t("settingsplugins.runs_scheduled_jobs"),
          routes: t("settingsplugins.provides_commands"),
          mcp: t("settingsplugins.adds_tools_for_agents"),
          "net.fetch": t("settingsplugins.fetches_public_web_pages"),
          "attachments.read": t("settingsplugins.reads_attachments"),
          "attachments.write": t("settingsplugins.adds_attachments"),
        })[p] ?? p,
    )
  );
}
function pluginOrigin(plugin: Plugin) {
  return plugin.origin === "bundled"
    ? t("settingsplugins.built_in")
    : plugin.origin === "operator"
      ? t("settingsplugins.operator_installed")
      : t("settingsplugins.origin_unknown");
}
export function pluginCanEnable(plugin: Plugin) {
  return (
    plugin.state !== "removed" &&
    !plugin.availability_reason &&
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
export const capabilityNames: () => Record<
  AICapability,
  [title: string, model: string]
> = () => ({
  chat: [t("aisettings.chat"), t("settingsplugins.a_chat_model")],
  embed: [t("aisettings.embeddings"), t("settingsplugins.an_embedding_model")],
  transcribe: [
    t("aisettings.transcription"),
    t("settingsplugins.a_transcription_model"),
  ],
  vision: [t("aisettings.vision"), t("settingsplugins.a_vision_model")],
});
function aiCapabilities(plugin: Plugin) {
  return plugin.permissions
    .filter((p) => p.startsWith("ai."))
    .map((p) => p.slice(3) as AICapability)
    .filter((capability) => capability in capabilityNames());
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
  if (plugin.state === "removed")
    return t("settingsplugins.plugin_code_is_no_longer_installed");
  if (plugin.availability_reason) return plugin.availability_reason;
  const ai = aiCapabilities(plugin);
  if (ai.length) {
    const missing = ai.filter((c) => !plugin.ai_destinations?.[c]);
    // With no model for any capability, one short requirement replaces a line per capability.
    if (missing.length === ai.length)
      return t("settingsplugins.needs_value1", {
        value1: missing
          .map((c) => capabilityNames()[c][1])
          .join(t("format.and")),
      });
    return ai
      .map((capability) => {
        const [title, model] = capabilityNames()[capability];
        const destination = plugin.ai_destinations?.[capability];
        return destination
          ? t("settingsplugins.destination", {
              capability: title,
              model: destination.model,
              host: destination.host ?? destination.profile,
              network: destination.leaves_network
                ? t("settingsplugins.outside_your_network")
                : t("settingsplugins.on_your_network"),
            })
          : t(`settingsplugins.missing_model.${capability}`, {
              capability: title,
            });
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
    ? t("settingsplugins.connects_to_value1_never_reads_private_notes", {
        value1: destinations.join(", "),
      })
    : plugin.permissions.some((p) => p.startsWith("net.out:"))
      ? t(
          "settingsplugins.connects_to_the_configured_server_never_reads_private",
        )
      : plugin.permissions.includes("net.fetch")
        ? t(
            "settingsplugins.fetches_public_web_pages_never_reads_private_notes",
          )
        : plugin.kind === "device"
          ? t("settingsplugins.stays_on_the_phone")
          : t("settingsplugins.stays_on_your_server");
}
function slotSentences(plugin: Plugin) {
  const c = plugin.contributes ?? {};
  return (
    [
      c.views ? t("settingsplugins.a_view_in_the_rail") : null,
      c.commands
        ? t("settingsplugins.commands_in_the_palette_or_note_actions")
        : null,
      c.panel ? t("settingsplugins.a_section_in_about_this_note") : null,
      c.meta ? t("settingsplugins.one_fact_in_the_note_s_meta_line") : null,
      c.slip ? t("settingsplugins.one_daily_slip") : null,
      plugin.permissions.includes("suggest")
        ? t("settingsplugins.pills_under_notes_and_the_shared_review_queue")
        : null,
    ]
      .filter(Boolean)
      .join(". ") || t("settingsplugins.settings_only")
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
          t(
            "settingsplugins.that_plugin_switch_is_unavailable_review_its_current",
          ),
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
  useLanguage();
  return (
    <Dialog
      title={t("settingsplugins.turn_on_value1", { value1: plugin.name })}
      opener={opener}
      onClose={onClose}
      footer={
        <>
          <button disabled={busy} onClick={onClose}>
            {t("profilepanel.cancel")}
          </button>
          <button className="primary" disabled={busy} onClick={onEnable}>
            {busy
              ? t("settingsplugins.turning_on")
              : t("settingsplugins.turn_on")}
          </button>
        </>
      }
    >
      <p>{plugin.description}</p>
      <h3>{t("settingsplugins.it_will")}</h3>
      <ul>
        {permissions(plugin).map((p, i) => (
          <li key={i}>{willSentence(p)}</li>
        ))}
      </ul>
      <h3>{t("settingsplugins.where_text_goes")}</h3>
      <p className="muted">{pluginFacts(plugin)}</p>
      <h3>{t("settingsplugins.where_it_shows_up")}</h3>
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
      ? [
          {
            ...route,
            title:
              capability === "chat"
                ? t("aisettings.chat")
                : t("settingsplugins.embed"),
          },
        ]
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
            message: t("settingsplugins.value1_is_off", {
              value1: plugin.name,
            }),
            actionLabel: t("settingsplugins.undo"),
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
  useLanguage();
  const query = useQuery({
    queryKey: ["catalog"],
    queryFn: api.catalog,
    select: (data) => localizeCatalog(data),
  });
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
      <h1>{t("settings.plugins")}</h1>
      <p className="muted">
        {t(
          "settingsplugins.everything_beyond_notes_labels_and_search_switch_one",
        )}
      </p>
      {aiOff ? (
        <p className="notice">
          {t("settingsplugins.ai_is_off_so_plugins_that_need_a")}{" "}
          <Link href="/settings/ai">{t("settingsplugins.ai_settings")}</Link>
        </p>
      ) : (
        summary && (
          <p className={styles.facts}>
            {summary} ·{" "}
            <Link href="/settings/ai">{t("settingsplugins.ai_settings")}</Link>
          </p>
        )
      )}
      <div className={styles.segments}>
        <button aria-pressed={!onlyOn} onClick={() => setOnlyOn(false)}>
          {t("settingsplugins.all")}
          {plugins.length}
        </button>
        <button aria-pressed={onlyOn} onClick={() => setOnlyOn(true)}>
          {t("settingsplugins.on")}
          {enabled}
        </button>
      </div>
      {query.isPending && <p>{t("settingsplugins.loading_plugins")}</p>}
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
                {t("settingsplugins.category_count", {
                  category: t(`category.${category}`),
                  enabled: all.filter((p) => p.switch === "on").length,
                  total: all.length,
                })}
              </h2>
              <ul className={styles.list}>
                {items.map((p) => {
                  const unavailable = p.switch !== "on" && !pluginCanEnable(p);
                  const linked =
                    p.switch === "on" || p.state === "removed" || unavailable;
                  return (
                    <li
                      className={`${styles.plugin} ${linked ? styles.rowLink : ""} ${unavailable ? styles.pluginUnavailable : ""}`}
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
                            <small className={styles.facts}>
                              {" "}
                              {t("settingsplugins.android")}
                            </small>
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
                                <Link href="/settings/ai">
                                  {t("settingsplugins.ai_settings")}
                                </Link>
                              </>
                            )}
                            {p.state === "failed" && (
                              <>
                                {t("settingsplugins.paused_after_failed_runs")}
                                <Link href="/status">
                                  {t("settingsplugins.details")}
                                </Link>
                              </>
                            )}
                          </div>
                        </div>
                        <div className={styles.actions}>
                          {p.pinned_by_env ? (
                            <span className={styles.facts}>
                              {t("settingsplugins.set_in_the_environment")}{" "}
                              {p.switch === "on"
                                ? t("settingsplugins.on_3")
                                : t("aisettings.off")}
                            </span>
                          ) : p.state === "removed" ? (
                            <span className={styles.facts}>
                              {t("settingsplugins.removed_off")}
                            </span>
                          ) : (
                            <input
                              type="checkbox"
                              role="switch"
                              aria-label={t("settingsplugins.enable_value1", {
                                value1: p.name,
                              })}
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
  useLanguage();
  const catalog = useQuery({
    queryKey: ["catalog"],
    queryFn: api.catalog,
    select: (data) => localizeCatalog(data),
  });
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
      <summary>{t("settingsplugins.arrange")}</summary>
      <p className="muted">
        {t(
          "settingsplugins.your_order_chooses_the_note_panel_suggestion_order",
        )}
      </p>
      {shown.map((p, index) => (
        <div className={styles.plugin} key={p.id}>
          <div className={styles.between}>
            <span>{p.name}</span>
            <div className={styles.actions}>
              <button
                aria-label={t("settingsplugins.move_value1_up", {
                  value1: p.name,
                })}
                disabled={save.isPending || index === 0}
                onClick={() => move(p.id, -1)}
              >
                ↑
              </button>
              <button
                aria-label={t("settingsplugins.move_value1_down", {
                  value1: p.name,
                })}
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
              {t("settingsplugins.show_view", { view: v.title })}
            </label>
          ))}
          {p.contributes?.mcp_tools?.map((tool) => (
            <label key={tool.name} className={styles.row}>
              <input
                type="checkbox"
                checked={
                  !settings.data?.hidden_mcp_tools.includes(
                    `${p.id}_${tool.name}`,
                  )
                }
                disabled={save.isPending}
                onChange={() =>
                  toggle("hidden_mcp_tools", `${p.id}_${tool.name}`)
                }
              />
              {t("settingsplugins.expose")}{" "}
              <code>
                {p.id}_{tool.name}
              </code>{" "}
              {t("settingsplugins.to_agents")}
            </label>
          ))}
        </div>
      ))}
      <ErrorLine error={save.error} />
    </details>
  );
}
export function PluginPage({ id }: { id: string }) {
  useLanguage();
  const catalog = useQuery({
    queryKey: ["catalog"],
    queryFn: api.catalog,
    select: (data) => localizeCatalog(data),
  });
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
        throw new Error(t("settingsplugins.plugin_settings_are_unavailable"));
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
        throw new Error(
          t("settingsplugins.plugin_data_cleanup_is_unavailable"),
        );
      return api.deletePluginData(id);
    },
    onSuccess: () => {
      setRemove(false);
      void client.invalidateQueries({ queryKey: ["catalog"] });
      void client.invalidateQueries({ queryKey: ["notes"] });
    },
  });
  const page = (
    <PageTitle
      page={t("settings.settings_value1", {
        value1: plugin?.name ?? t("settings.plugins"),
      })}
    />
  );
  const back = (
    <Link href="/settings/plugins" className={styles.back}>
      {t("settingsplugins.plugins")}
    </Link>
  );
  if (catalog.isPending || (!!plugin && !removed && query.isPending))
    return (
      <>
        {page}
        <p>{t("settingsplugins.loading_plugin_settings")}</p>
      </>
    );
  if (!plugin)
    return (
      <>
        {page}
        {back}
        <h1>{t("settingsplugins.plugin_unavailable")}</h1>
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
            {t("schemaform.set_in_the_environment")}
            {plugin.switch === "on"
              ? t("settingsplugins.on_3")
              : t("aisettings.off")}
          </span>
        ) : removed ? (
          <span className={styles.facts}>
            {t("settingsplugins.removed_off")}
          </span>
        ) : (
          <input
            ref={enabler}
            className={styles.switch}
            type="checkbox"
            role="switch"
            aria-label={t("settingsplugins.enable_value1", {
              value1: plugin.name,
            })}
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
        {t(
          `category.${["capture", "organize", "recall", "develop", "agents", "data", "look"].includes(plugin.category) ? plugin.category : "data"}`,
        )}{" "}
        · {pluginOrigin(plugin)} · {plugin.version}
      </p>
      {!!plugin.pinned_by_env && (
        <p className={styles.secret}>
          {t("settingsplugins.set_in_the_environment_2")}
          <code>SPUNTO_PLUGINS</code>
        </p>
      )}
      {removed && (
        <p className="notice">
          {t("settingsplugins.removed_plugin_code_is_no_longer_installed_its")}
        </p>
      )}
      {plugin.switch !== "on" && plugin.availability_reason && (
        <p className="notice">{pluginFacts(plugin)}</p>
      )}
      {plugin.state === "waiting" &&
        (aiCapabilities(plugin).length ? (
          <p className="notice">
            {pluginFacts(plugin)}
            {t("settingsplugins.it_stays_on_and_starts_working_once_that")}{" "}
            {aiOff ? (
              <>
                {t("settingsplugins.ai_is_off")}
                <AIOffHint />
              </>
            ) : (
              <Link href="/settings/ai">
                {t("settingsplugins.ai_settings")}
              </Link>
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
          {t("settingsplugins.settings_saved")}
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
          <h2>{t("settingsplugins.run")}</h2>
          <p className="muted">
            {t("settingsplugins.note_commands_appear_in_the_palette_while_a")}
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
      <h2>{t("settingsplugins.activity")}</h2>
      <p className="muted">
        {data.last_run_at
          ? t("settingsplugins.last_run_value1", {
              value1: formatWhen(data.last_run_at),
            })
          : t("settingsplugins.no_runs_yet")}
        {data.state === "waiting"
          ? ""
          : data.last_error
            ? ` · ${data.last_error}`
            : t("settingsplugins.no_failures")}
      </p>
      <h2>{t("collaboration.access")}</h2>
      <ul>
        {permissions(plugin).map((text, i) => (
          <li key={i}>{text}</li>
        ))}
      </ul>
      {removed ? (
        <p className="muted">
          {t(
            "settingsplugins.access_above_describes_the_retained_plugin_no_plugin",
          )}
        </p>
      ) : (
        <p className="muted">
          {pluginFacts(plugin)}
          {t("settingsplugins.up_to_3_commands_10_panel_items_and")}
        </p>
      )}
      {!removed &&
        plugin.contributes?.mcp_tools?.map((tool) => (
          <p key={tool.name} className={styles.facts}>
            {t("settingsplugins.adds_agent_tool")}{" "}
            <code>
              {id}_{tool.name}
            </code>
            : {tool.description}
          </p>
        ))}
      <h2>{t("settingsplugins.stored_data")}</h2>
      <p className="muted">
        {recordCount === undefined ? "…" : plural(recordCount, "record")} ·{" "}
        {formatBytes(plugin.data_size_bytes ?? 0)}
        {t("settingsplugins.its_notes_stay_they_are_notes")}
      </p>
      {canDelete && (
        <button
          ref={remover}
          className="danger"
          onClick={() => setRemove(true)}
        >
          {t("settingsplugins.delete_this_plugin_s_data")}
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
          title={t("settingsplugins.delete_value1_s_data", {
            value1: plugin.name,
          })}
          opener={remover.current}
          onClose={() => setRemove(false)}
          footer={
            <>
              <button
                disabled={deletion.isPending}
                onClick={() => setRemove(false)}
              >
                {t("profilepanel.cancel")}
              </button>
              <button
                className="danger"
                disabled={
                  deletion.isPending || !pluginCountKnown(plugin.data_count)
                }
                onClick={() => deletion.mutate()}
              >
                {t("settingsplugins.delete_records", {
                  count: recordCount ?? "…",
                })}
              </button>
            </>
          }
        >
          <p>
            {t("settingsplugins.delete_records_description", {
              count: recordCount ?? "…",
            })}
          </p>
          <p>{t("settingsplugins.this_cannot_be_undone")}</p>
          <ErrorLine error={deletion.error} />
        </Dialog>
      )}
    </>
  );
}
