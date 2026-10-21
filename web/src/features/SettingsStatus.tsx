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
