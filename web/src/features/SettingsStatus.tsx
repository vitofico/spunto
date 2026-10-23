import { localizeCatalog } from "../lib/localizePlugins";
import { getLanguage, t, useLanguage } from "../lib/i18n";
import { useQuery } from "@tanstack/react-query";
import { Link } from "wouter";
import { api } from "../lib/api";
import { useDocumentTitle } from "../lib/useDocumentTitle";
import type { AICapability, AIHealth, VectorStatus } from "../lib/types";
import styles from "./Feature.module.css";
import { AIHealthResults } from "./AISettings";
import { formatBytes, formatWhen, humanize, plural } from "./format";
import { ErrorLine } from "./SettingsParts";
import { capabilityNames } from "./SettingsPlugins";
const pluginStates: () => Record<string, string> = () => ({
  enabled: t("settingsplugins.on_3"),
  disabled: t("aisettings.off"),
  available: t("aisettings.off"),
  waiting: t("settingsstatus.waiting_for_a_requirement"),
  failed: t("settingsstatus.paused_after_failed_runs"),
  removed: t("settingsstatus.removed"),
});
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
  useLanguage();
  useDocumentTitle(t("aisettings.server_status"));
  const query = useQuery({ queryKey: ["status"], queryFn: api.status });
  const catalog = useQuery({
    queryKey: ["catalog"],
    queryFn: api.catalog,
    select: (data) => localizeCatalog(data),
  });
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
      <h1>{t("aisettings.server_status")}</h1>
      {query.isPending ? (
        <p>{t("settingsstatus.loading_status")}</p>
      ) : query.isError ? (
        <ErrorLine error={query.error} />
      ) : (
        <>
          <p className="muted">
            Spunto {String(data?.version)} {t("settingsstatus.database_schema")}{" "}
            {String(data?.schema)}
          </p>
          <dl className={styles.counts}>
            {[
              [
                t("review.notes"),
                (database?.notes ?? 0).toLocaleString(getLanguage()),
              ],
              [
                t("settingsstatus.private_notes"),
                (database?.private_notes ?? 0).toLocaleString(getLanguage()),
              ],
              [
                t("settingsstatus.in_trash"),
                (database?.trashed_notes ?? 0).toLocaleString(getLanguage()),
              ],
              [
                database?.size_kind === "note_rows"
                  ? t("settingsstatus.note_text_estimate")
                  : t("settingsstatus.database_size"),
                formatBytes(database?.size_bytes ?? 0),
              ],
              [
                t("settingsstatus.running_for"),
                duration(Number(data?.uptime_seconds ?? 0)),
              ],
            ].map(([title, value]) => (
              <div key={title}>
                <dt>{title}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
          <h2>{t("settingsstatus.backup")}</h2>
          <p>
            {backup?.managed
              ? t("settingsstatus.backups_are_managed_by_the_host")
              : backup?.last_at
                ? t("settingsstatus.last_backup_value1_value2_ago", {
                    value1: formatWhen(backup.last_at),
                    value2: plural(Math.round(backup.age_hours ?? 0), "hour"),
                  })
                : t("settingsstatus.no_backup_yet")}
          </p>
          <h2>AI</h2>
          <p>
            {ai?.enabled
              ? ai.capabilities
                  .map(
                    (capability) =>
                      capabilityNames()[
                        capability.replace("ai.", "") as AICapability
                      ]?.[0] ?? humanize(capability.replace("ai.", "")),
                  )
                  .join(" · ")
              : t("aisettings.ai_is_off")}
          </p>
          <AIHealthResults rows={ai?.health ?? []} />
          {ai?.indexing && (
            <p className="muted">
              {ai.indexing.available
                ? `${plural(ai.indexing.chunks, "embedding_chunk")}`
                : t("settingsstatus.vector_index_unavailable")}
              {ai.indexing.enabled
                ? t("settingsstatus.indexing_on")
                : t("settingsstatus.indexing_paused")}
            </p>
          )}
          {ai?.stats?.map((stat, index) => (
            <p className={styles.facts} key={index}>
              {name(stat.plugin_id)} ·{" "}
              {capabilityNames()[
                stat.capability.replace("ai.", "") as AICapability
              ]?.[0] ?? humanize(stat.capability)}{" "}
              · {humanize(stat.outcome)}: {stat.n.toLocaleString(getLanguage())}
            </p>
          ))}
          <Link href="/settings/ai">{t("settingsplugins.ai_settings")}</Link>
          <h2>{t("settingsstatus.plugins_and_jobs")}</h2>
          {plugins?.map((p) => (
            <p className="muted" key={p.id}>
              {name(p.id)} · {pluginStates()[p.state] ?? humanize(p.state)}
              {p.last_error ? ` · ${p.last_error}` : ""}
            </p>
          ))}
          {jobs?.map((j, i) => (
            <p className={styles.facts} key={i}>
              {name(j.plugin_id)} · {plural(j.count, "job")}{" "}
              {humanize(j.status).toLowerCase()}
            </p>
          ))}
          <h2>{t("collaboration.access")}</h2>
          <p className="muted">
            {plural(
              Array.isArray(data?.credentials) ? data.credentials.length : 0,
              "active_credential",
            )}
            .
          </p>
          <button onClick={() => void query.refetch()}>
            {t("settingsstatus.refresh_status")}
          </button>
        </>
      )}
    </div>
  );
}
