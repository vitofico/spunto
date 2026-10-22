import { useEffect, useState } from "react";
import { Link } from "wouter";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { useHosted } from "../lib/query";
import type {
  AIAllowanceAction,
  AICapability,
  AIHealth,
  AIModelChoice,
  AIModelSelection,
  AIModelSelections,
  AIQuota,
  AIStatus,
  Plugin,
} from "../lib/types";
import styles from "./Feature.module.css";

const capabilities: [AICapability, string][] = [
  ["chat", "Chat"],
  ["embed", "Embeddings"],
  ["transcribe", "Transcription"],
  ["vision", "Vision"],
];
function sameModel(a?: AIModelChoice, b?: AIModelChoice) {
  return a?.profile === b?.profile && a?.model === b?.model;
}
function sameSelections(a: AIModelSelections, b: AIModelSelections) {
  return capabilities.every(([capability]) =>
    sameModel(a[capability], b[capability]),
  );
}
function modelValue(choice?: AIModelChoice) {
  return choice ? JSON.stringify([choice.profile, choice.model]) : "";
}
function AIModelSettings({
  ai,
  selection,
  onSaved,
}: {
  ai: AIStatus;
  selection: AIModelSelection;
  onSaved: () => void;
}) {
  const client = useQueryClient();
  const canManage = ai.can_manage === true;
  const inventory = useQuery({
    queryKey: ["ai-models"],
    queryFn: api.aiModels,
    enabled: canManage,
  });
  const [draft, setDraft] = useState(() => ({
    revision: selection.revision,
    saved: selection.selections,
    selections: selection.selections,
  }));
  const dirty = !sameSelections(draft.selections, draft.saved);
  useEffect(() => {
    setDraft((previous) =>
      sameSelections(previous.selections, previous.saved)
        ? {
            revision: selection.revision,
            saved: selection.selections,
            selections: selection.selections,
          }
        : previous,
    );
  }, [selection]);
  const accept = (status: AIStatus) => {
    client.setQueryData(["ai"], status);
    if (status.model_selection)
      setDraft({
        revision: status.model_selection.revision,
        saved: status.model_selection.selections,
        selections: status.model_selection.selections,
      });
  };
  const save = useMutation({
    mutationFn: (body: { revision: string; selections: AIModelSelections }) =>
      api.saveAIModels(body),
    onMutate: () => client.cancelQueries({ queryKey: ["ai"] }),
    onSuccess: (status) => {
      accept(status);
      for (const key of [
        "ai",
        "catalog",
        "manifest",
        "meta",
        "status",
        "search",
        "plugin-panel",
      ])
        void client.invalidateQueries({ queryKey: [key] });
      onSaved();
    },
  });
  const reload = useMutation({
    mutationFn: api.ai,
    onSuccess: (status) => {
      accept(status);
      save.reset();
    },
  });
  const busy = save.isPending || reload.isPending;
  const valid = capabilities.every(([capability]) => {
    const choice = draft.selections[capability];
    if (!choice || sameModel(choice, draft.saved[capability])) return true;
    return inventory.data?.profiles
      .find((profile) => profile.profile === choice.profile)
      ?.models.some(
        (model) =>
          model.id === choice.model &&
          (model.capabilities === null ||
            model.capabilities.includes(capability)),
      );
  });
  return (
    <form
      className={styles.modelSettings}
      aria-busy={busy}
      onSubmit={(event) => {
        event.preventDefault();
        if (canManage && dirty && valid && !busy)
          save.mutate({
            revision: draft.revision,
            selections: draft.selections,
          });
      }}
    >
      <div className={styles.sectionHeading}>
        <h2>Models</h2>
        {canManage && (
          <button
            type="button"
            disabled={busy || inventory.isFetching}
            onClick={() => void inventory.refetch()}
          >
            {inventory.isFetching ? "Loading models…" : "Refresh models"}
          </button>
        )}
      </div>
      <p className="muted">
        Model choices apply to this space. Selecting a model also chooses its
        provider for that capability. Each plugin can choose a different
        provider in its settings.
      </p>
      {!canManage && <p>Only a space owner can change models.</p>}
      {inventory.error && (
        <p role="alert" className="error">
          Could not load available models. Your saved choices are kept.
        </p>
      )}
      {ai.profiles.map((profile) =>
        inventory.data?.profiles.find((item) => item.profile === profile.name)
          ?.error ? (
          <p key={profile.name} role="alert" className="error">
            Could not load models from {profile.name}. Saved choices are kept.
          </p>
        ) : null,
      )}
      {capabilities.map(([capability, title]) => {
        const override = draft.selections[capability];
        const effective = override ?? selection.defaults[capability];
        const provider = ai.profiles.find(
          (item) => item.name === effective?.profile,
        );
        const profileInventory = inventory.data?.profiles.find(
          (item) => item.profile === effective?.profile,
        );
        const model = profileInventory?.models.find(
          (item) =>
            item.id === effective?.model &&
            (item.capabilities === null ||
              item.capabilities.includes(capability)),
        );
        const currentModel =
          provider?.[`${capability}_model`] === effective?.model;
        const locality =
          model?.leaves_network ??
          (currentModel ? provider?.leaves_network?.[capability] : undefined);
        const inherited = selection.defaults[capability];
        const unlisted =
          !!effective &&
          !!profileInventory &&
          !profileInventory.error &&
          !model;
        return (
          <div
            key={capability}
            className={`${styles.formField} ${styles.modelField}`}
          >
            <label htmlFor={`ai-model-${capability}`}>{title} model</label>
            <select
              className="field"
              id={`ai-model-${capability}`}
              value={modelValue(override)}
              disabled={!canManage || busy}
              onChange={(event) => {
                const value = event.target.value;
                setDraft((previous) => {
                  const selections = { ...previous.selections };
                  if (value) {
                    const [profile, model] = JSON.parse(value) as [
                      string,
                      string,
                    ];
                    selections[capability] = { profile, model };
                  } else delete selections[capability];
                  return { ...previous, selections };
                });
                save.reset();
              }}
            >
              <option value="">
                Server default (
                {inherited
                  ? `${inherited.model} at ${inherited.profile}`
                  : "Off"}
                )
              </option>
              {override && !model && (
                <option value={modelValue(override)} disabled>
                  {override.model} · {override.profile} (
                  {unlisted ? "not listed by provider" : "current selection"})
                </option>
              )}
              {ai.profiles.map((profile) => (
                <optgroup
                  label={`${profile.name} · ${profile.provider}`}
                  key={profile.name}
                >
                  {inventory.data?.profiles
                    .find((item) => item.profile === profile.name)
                    ?.models.filter(
                      (model) =>
                        model.capabilities === null ||
                        model.capabilities.includes(capability),
                    )
                    .map((model) => (
                      <option
                        key={model.id}
                        value={modelValue({
                          profile: profile.name,
                          model: model.id,
                        })}
                      >
                        {model.id}
                        {model.capabilities === null
                          ? " (capability unverified)"
                          : ""}
                      </option>
                    ))}
                </optgroup>
              ))}
            </select>
            {effective && (
              <small>
                {effective.model} at {provider?.base_url ?? effective.profile}
                {locality === undefined
                  ? " · Network locality unavailable"
                  : locality
                    ? " · Outside your network"
                    : " · On your network"}
                {model?.capabilities === null &&
                  " · Capability not reported by provider. Check providers after saving."}
              </small>
            )}
            {unlisted && (
              <small>
                The selected model is not listed by the provider for this
                capability.
              </small>
            )}
            {capability === "embed" &&
              !sameModel(
                effective,
                draft.saved.embed ?? selection.defaults.embed,
              ) && (
                <p className="muted">
                  Embedding changes rebuild the index; progress appears below
                  after saving.
                </p>
              )}
          </div>
        );
      })}
      {canManage && (
        <div className={styles.formActions}>
          <button className="primary" disabled={busy || !dirty || !valid}>
            {save.isPending ? "Saving models…" : "Save models"}
          </button>
          <button
            type="button"
            disabled={busy || !Object.keys(draft.selections).length}
            onClick={() => {
              setDraft((previous) => ({ ...previous, selections: {} }));
              save.reset();
            }}
          >
            Use server defaults
          </button>
          {dirty && (
            <button
              type="button"
              disabled={busy}
              onClick={() => reload.mutate()}
            >
              {reload.isPending ? "Reloading…" : "Discard changes and reload"}
            </button>
          )}
        </div>
      )}
      {dirty && <p role="status">Changes are not saved.</p>}
      {save.isSuccess && <p role="status">Model choices saved.</p>}
      {save.error && (
        <p role="alert" className="error">
          {save.error.message}
        </p>
      )}
      {reload.error && (
        <p role="alert" className="error">
          Could not reload model settings. Your choices are kept.
        </p>
      )}
    </form>
  );
}
function useAI(enabled = true) {
  return useQuery({
    queryKey: ["ai"],
    queryFn: api.ai,
    enabled,
    refetchInterval: (query) =>
      query.state.data?.indexing?.routes.some(
        (route) => route.indexed < route.total,
      )
        ? 3000
        : false,
  });
}
export function AIHealthResults({ rows }: { rows: AIHealth[] }) {
  return rows.length ? (
    <ul className={styles.healthList}>
      {rows.map((row) => (
        <li key={`${row.profile}/${row.capability}`}>
          {row.profile} ·{" "}
          {capabilities.find(([id]) => id === row.capability)?.[1]}:
          {row.ok ? " OK" : " Unavailable"}
          {row.dimension ? ` · ${row.dimension} dimensions` : ""}
          {row.fix_hint && <p className="muted">{row.fix_hint}</p>}
        </li>
      ))}
    </ul>
  ) : null;
}
const allowances: Record<AIAllowanceAction, string> = {
  labels: "Auto-labelling",
  ask: "Ask",
  chat: "Other AI actions",
  vision: "Image reading",
  transcribe: "Transcription",
  embed: "Search indexing",
};
/** A hosted account's month of AI: what is used, and what waits for the renewal. */
function AIAllowance({ quota }: { quota: AIQuota }) {
  const renews = new Date(quota.resets_at).toLocaleDateString(undefined, {
    day: "numeric",
    month: "long",
    timeZone: "UTC",
  });
  const paused = quota.actions.filter(
    (row) => row.limit !== null && row.used >= row.limit,
  );
  return (
    <>
      <h2>Monthly allowance</h2>
      {paused.length > 0 && (
        <p role="status" className="notice">
          {paused.map((row) => allowances[row.action]).join(", ")} paused until{" "}
          {renews}. Notes and search keep working.
        </p>
      )}
      <dl className={styles.keyValues}>
        {quota.actions.map((row) => (
          <div key={row.action}>
            <dt>{allowances[row.action]}</dt>
            <dd>
              {row.limit === null
                ? `${row.used.toLocaleString()} used`
                : `${row.used.toLocaleString()} of ${row.limit.toLocaleString()}`}
            </dd>
          </div>
        ))}
      </dl>
      <p className="muted">
        AI in this space counts against its owner’s account, shared by all of
        their spaces. The allowance renews on {renews}.
      </p>
    </>
  );
}
/** The one way to turn AI on, said the same on every page that mentions it. */
export function AIOffHint() {
  return useHosted() ? (
    <>Ask whoever runs the server to set up a model.</>
  ) : (
    <>
      To turn it on, set the <code>SPUNTO_AI_*</code> variables and restart the
      server.
    </>
  );
}
export function AISettings() {
  const query = useAI();
  const client = useQueryClient();
  const check = useMutation({
    mutationFn: api.checkAI,
    onSuccess: ({ health }) => {
      client.setQueryData<AIStatus>(["ai"], (previous) =>
        previous ? { ...previous, health } : previous,
      );
      for (const key of ["ai", "catalog", "manifest", "meta", "status"])
        void client.invalidateQueries({ queryKey: [key] });
    },
  });
  const reindex = useMutation({
    mutationFn: api.reindexAI,
    onSuccess: () => void client.invalidateQueries({ queryKey: ["ai"] }),
  });
  const ai = query.data;
  return (
    <>
      <h1>{ai && !ai.profiles.length ? "AI is off." : "AI"}</h1>
      {query.isPending && <p role="status">Loading AI settings…</p>}
      {ai && !ai.profiles.length && (
        <p className="muted">
          Everything else works. <AIOffHint />
        </p>
      )}
      {ai && !!ai.profiles.length && (
        <>
          <p className="muted">
            Providers are set by whoever runs the server. Private and
            machine-written notes are never sent to a model.
          </p>
          {ai.quota && <AIAllowance quota={ai.quota} />}
          {ai.profiles.map((profile) => (
            <section key={profile.name} className={styles.connection}>
              <h2>{profile.name}</h2>
              <p>
                {profile.provider} · <code>{profile.base_url}</code>
              </p>
              <ul>
                {capabilities.map(
                  ([capability, title]) =>
                    profile[`${capability}_model`] && (
                      <li key={capability}>
                        {title}: {profile[`${capability}_model`]}
                        {profile.leaves_network?.[capability]
                          ? " · Leaves this network"
                          : " · Local network"}
                      </li>
                    ),
                )}
              </ul>
            </section>
          ))}
          {ai.model_selection && (
            <AIModelSettings
              ai={ai}
              selection={ai.model_selection}
              onSaved={() => {
                check.reset();
                reindex.reset();
              }}
            />
          )}
          <h2>Default routes</h2>
          <dl className={styles.keyValues}>
            {capabilities.map(([capability, title]) => (
              <div key={capability}>
                <dt>{title}</dt>
                <dd>{ai.bindings[capability] ?? "Off"}</dd>
              </div>
            ))}
          </dl>
          <p className="muted">
            Each plugin can choose a different configured profile in its
            settings.
          </p>
          <button
            disabled={
              ai.can_manage === false ||
              check.isPending ||
              !Object.keys(ai.bindings).length
            }
            onClick={() => check.mutate()}
          >
            {check.isPending ? "Checking providers…" : "Check providers"}
          </button>
          <AIHealthResults rows={check.data?.health ?? ai.health ?? []} />
          {ai.indexing && (
            <>
              <h2>Embedding index</h2>
              <p>
                {!ai.indexing.available
                  ? "Vector index unavailable."
                  : ai.indexing.enabled
                    ? "Indexing is on."
                    : "Indexing is paused."}{" "}
                {ai.indexing.chunks} chunks.
              </p>
              {ai.indexing.routes.map((route) => (
                <p
                  key={route.profile}
                  role="status"
                  className={styles.indexStatus}
                >
                  {route.profile}: {route.indexed} of {route.total} notes
                  indexed.
                  {route.active_key &&
                    route.active_key !== route.target_key &&
                    " The previous index is retained until the new model is ready."}
                </p>
              ))}
              <button
                disabled={
                  reindex.isPending ||
                  ai.can_manage === false ||
                  !ai.indexing.available ||
                  !ai.indexing.enabled
                }
                onClick={() => reindex.mutate()}
              >
                {reindex.isPending ? "Queuing index…" : "Reindex notes"}
              </button>
              {reindex.isSuccess && <p role="status">Index work queued.</p>}
            </>
          )}
        </>
      )}
      {(query.error || check.error || reindex.error) && (
        <p role="alert" className="error">
          {(query.error ?? check.error ?? reindex.error)?.message}
        </p>
      )}
      {ai && !!ai.profiles.length && <Link href="/status">Server status</Link>}
    </>
  );
}
export function AIRouting({ plugin }: { plugin: Plugin }) {
  const query = useAI(
    plugin.permissions.some((permission) => permission.startsWith("ai.")),
  );
  const client = useQueryClient();
  const [routes, setRoutes] = useState<Partial<Record<AICapability, string>>>(
    plugin.ai_route ?? {},
  );
  useEffect(() => {
    setRoutes(plugin.ai_route ?? {});
  }, [plugin.ai_route]);
  const save = useMutation({
    mutationFn: () => api.saveAIRoute(plugin.id, routes),
    onSuccess: () => {
      for (const key of ["catalog", "manifest", "ai", "plugin-panel", "search"])
        void client.invalidateQueries({ queryKey: [key] });
    },
  });
  const used = capabilities.filter(([capability]) =>
    plugin.permissions.includes(`ai.${capability}`),
  );
  if (!used.length)
    return <p className="muted">This plugin does not use AI.</p>;
  if (!query.data)
    return query.isPending ? (
      <p>Loading AI routes…</p>
    ) : (
      <p role="alert" className="error">
        {query.error?.message}
      </p>
    );
  const ai = query.data;
  const stats = ai.stats?.filter((row) => row.plugin_id === plugin.id) ?? [];
  const calls = stats.reduce((total, row) => total + row.n, 0);
  const failures = stats
    .filter((row) =>
      ["invalid", "provider_error", "refused"].includes(row.outcome),
    )
    .reduce((total, row) => total + row.n, 0);
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        save.mutate();
      }}
    >
      {used.map(([capability, title]) => {
        const selected = routes[capability] || ai.bindings[capability];
        const profile = ai.profiles.find((item) => item.name === selected);
        return (
          <div className={styles.formField} key={capability}>
            <label htmlFor={`ai-route-${plugin.id}-${capability}`}>
              {title} profile
            </label>
            <select
              className="field"
              id={`ai-route-${plugin.id}-${capability}`}
              value={routes[capability] ?? ""}
              disabled={save.isPending || ai.can_manage === false}
              onChange={(event) =>
                setRoutes((previous) => {
                  const next = { ...previous };
                  if (event.target.value) next[capability] = event.target.value;
                  else delete next[capability];
                  return next;
                })
              }
            >
              <option value="">
                Default ({ai.bindings[capability] ?? "Off"})
              </option>
              {ai.profiles
                .filter((item) => item[`${capability}_model`])
                .map((item) => (
                  <option key={item.name} value={item.name}>
                    {item.name} · {item[`${capability}_model`]}
                  </option>
                ))}
            </select>
            {profile && (
              <small>
                {profile.base_url}
                {profile.leaves_network?.[capability]
                  ? " · Leaves this network"
                  : " · Local network"}
              </small>
            )}
          </div>
        );
      })}
      <button
        className="primary"
        disabled={save.isPending || ai.can_manage === false}
      >
        {save.isPending ? "Saving…" : "Save AI routes"}
      </button>
      {save.isSuccess && <p role="status">AI routes saved.</p>}
      {ai.can_manage === false && (
        <p>Only a space owner can change AI routes.</p>
      )}
      <p className={styles.facts}>
        {calls} recorded calls · {failures} failures or refusals
      </p>
      {save.error && (
        <p role="alert" className="error">
          {save.error.message}
        </p>
      )}
    </form>
  );
}
