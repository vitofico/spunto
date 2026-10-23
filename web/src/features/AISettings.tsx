import { getLanguage, t, useLanguage } from "../lib/i18n";
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

const capabilities: () => [AICapability, string][] = () => [
  ["chat", t("aisettings.chat")],
  ["embed", t("aisettings.embeddings")],
  ["transcribe", t("aisettings.transcription")],
  ["vision", t("aisettings.vision")],
];
function sameModel(a?: AIModelChoice, b?: AIModelChoice) {
  return a?.profile === b?.profile && a?.model === b?.model;
}
function sameSelections(a: AIModelSelections, b: AIModelSelections) {
  return capabilities().every(([capability]) =>
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
  useLanguage();
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
  const valid = capabilities().every(([capability]) => {
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
        <h2>{t("aisettings.models")}</h2>
        {canManage && (
          <button
            type="button"
            disabled={busy || inventory.isFetching}
            onClick={() => void inventory.refetch()}
          >
            {inventory.isFetching
              ? t("aisettings.loading_models")
              : t("aisettings.refresh_models")}
          </button>
        )}
      </div>
      <p className="muted">
        {t("aisettings.model_choices_apply_to_this_space_selecting_a")}
      </p>
      {!canManage && (
        <p>{t("aisettings.only_a_space_owner_can_change_models")}</p>
      )}
      {inventory.error && (
        <p role="alert" className="error">
          {t("aisettings.could_not_load_available_models_your_saved_choices")}
        </p>
      )}
      {ai.profiles.map((profile) =>
        inventory.data?.profiles.find((item) => item.profile === profile.name)
          ?.error ? (
          <p key={profile.name} role="alert" className="error">
            {t("aisettings.provider_failed", { provider: profile.name })}
          </p>
        ) : null,
      )}
      {capabilities().map(([capability, title]) => {
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
            <label htmlFor={`ai-model-${capability}`}>
              {t("aisettings.model_label", { capability: title })}
            </label>
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
                {t("aisettings.server_default_choice", {
                  model: inherited
                    ? `${inherited.model} · ${inherited.profile}`
                    : t("aisettings.off"),
                })}
              </option>
              {override && !model && (
                <option value={modelValue(override)} disabled>
                  {override.model} · {override.profile} (
                  {unlisted
                    ? t("aisettings.not_listed_by_provider")
                    : t("aisettings.current_selection")}
                  )
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
                          ? t("aisettings.capability_unverified")
                          : ""}
                      </option>
                    ))}
                </optgroup>
              ))}
            </select>
            {effective && (
              <small>
                {effective.model} {t("aisettings.at")}
                {provider?.base_url ?? effective.profile}
                {locality === undefined
                  ? t("aisettings.network_locality_unavailable")
                  : locality
                    ? t("aisettings.outside_your_network")
                    : t("aisettings.on_your_network")}
                {model?.capabilities === null &&
                  t(
                    "aisettings.capability_not_reported_by_provider_check_providers_after",
                  )}
              </small>
            )}
            {unlisted && (
              <small>
                {t("aisettings.the_selected_model_is_not_listed_by_the")}
              </small>
            )}
            {capability === "embed" &&
              !sameModel(
                effective,
                draft.saved.embed ?? selection.defaults.embed,
              ) && (
                <p className="muted">
                  {t(
                    "aisettings.embedding_changes_rebuild_the_index_progress_appears_below",
                  )}
                </p>
              )}
          </div>
        );
      })}
      {canManage && (
        <div className={styles.formActions}>
          <button className="primary" disabled={busy || !dirty || !valid}>
            {save.isPending
              ? t("aisettings.saving_models")
              : t("aisettings.save_models")}
          </button>
          <button
            type="button"
            disabled={busy || !Object.keys(draft.selections).length}
            onClick={() => {
              setDraft((previous) => ({ ...previous, selections: {} }));
              save.reset();
            }}
          >
            {t("aisettings.use_server_defaults")}
          </button>
          {dirty && (
            <button
              type="button"
              disabled={busy}
              onClick={() => reload.mutate()}
            >
              {reload.isPending
                ? t("aisettings.reloading")
                : t("aisettings.discard_changes_and_reload")}
            </button>
          )}
        </div>
      )}
      {dirty && <p role="status">{t("aisettings.changes_are_not_saved")}</p>}
      {save.isSuccess && (
        <p role="status">{t("aisettings.model_choices_saved")}</p>
      )}
      {save.error && (
        <p role="alert" className="error">
          {save.error.message}
        </p>
      )}
      {reload.error && (
        <p role="alert" className="error">
          {t("aisettings.could_not_reload_model_settings_your_choices_are")}
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
  useLanguage();
  return rows.length ? (
    <ul className={styles.healthList}>
      {rows.map((row) => (
        <li key={`${row.profile}/${row.capability}`}>
          {row.profile} ·{" "}
          {capabilities().find(([id]) => id === row.capability)?.[1]}:{" "}
          {t(row.ok ? "aisettings.health_ok" : "aisettings.health_unavailable")}
          {row.dimension
            ? ` · ${t("aisettings.dimensions", { count: row.dimension })}`
            : ""}
          {row.fix_hint && <p className="muted">{row.fix_hint}</p>}
        </li>
      ))}
    </ul>
  ) : null;
}
const allowances: () => Record<AIAllowanceAction, string> = () => ({
  labels: t("aisettings.auto_labelling"),
  ask: t("aisettings.ask"),
  chat: t("aisettings.other_ai_actions"),
  vision: t("aisettings.image_reading"),
  transcribe: t("aisettings.transcription"),
  embed: t("aisettings.search_indexing"),
});
/** A hosted account's month of AI: what is used, and what waits for the renewal. */
function AIAllowance({ quota }: { quota: AIQuota }) {
  useLanguage();
  const renews = new Date(quota.resets_at).toLocaleDateString(getLanguage(), {
    day: "numeric",
    month: "long",
    timeZone: "UTC",
  });
  const paused = quota.actions.filter(
    (row) => row.limit !== null && row.used >= row.limit,
  );
  return (
    <>
      <h2>{t("aisettings.monthly_allowance")}</h2>
      {paused.length > 0 && (
        <p role="status" className="notice">
          {t("aisettings.allowance_paused", {
            actions: paused.map((row) => allowances()[row.action]).join(", "),
            date: renews,
          })}
        </p>
      )}
      <dl className={styles.keyValues}>
        {quota.actions.map((row) => (
          <div key={row.action}>
            <dt>{allowances()[row.action]}</dt>
            <dd>
              {row.limit === null
                ? t("aisettings.used", { count: row.used })
                : t("aisettings.used_limit", {
                    count: row.used,
                    limit: row.limit,
                  })}
            </dd>
          </div>
        ))}
      </dl>
      <p className="muted">
        {t("aisettings.allowance_renews", { date: renews })}
      </p>
    </>
  );
}
/** The one way to turn AI on, said the same on every page that mentions it. */
export function AIOffHint() {
  useLanguage();
  return useHosted() ? (
    <>{t("aisettings.ask_whoever_runs_the_server_to_set_up")}</>
  ) : (
    <>{t("aisettings.environment_setup")}</>
  );
}
export function AISettings() {
  useLanguage();
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
      <h1>{ai && !ai.profiles.length ? t("aisettings.ai_is_off") : "AI"}</h1>
      {query.isPending && (
        <p role="status">{t("aisettings.loading_ai_settings")}</p>
      )}
      {ai && !ai.profiles.length && (
        <p className="muted">
          {t("aisettings.everything_else_works")}
          <AIOffHint />
        </p>
      )}
      {ai && !!ai.profiles.length && (
        <>
          <p className="muted">
            {t("aisettings.providers_are_set_by_whoever_runs_the_server")}
          </p>
          {ai.quota && <AIAllowance quota={ai.quota} />}
          {ai.profiles.map((profile) => (
            <section key={profile.name} className={styles.connection}>
              <h2>{profile.name}</h2>
              <p>
                {profile.provider} · <code>{profile.base_url}</code>
              </p>
              <ul>
                {capabilities().map(
                  ([capability, title]) =>
                    profile[`${capability}_model`] && (
                      <li key={capability}>
                        {title}: {profile[`${capability}_model`]}
                        {profile.leaves_network?.[capability]
                          ? t("aisettings.leaves_this_network")
                          : t("aisettings.local_network")}
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
          <h2>{t("aisettings.default_routes")}</h2>
          <dl className={styles.keyValues}>
            {capabilities().map(([capability, title]) => (
              <div key={capability}>
                <dt>{title}</dt>
                <dd>{ai.bindings[capability] ?? t("aisettings.off")}</dd>
              </div>
            ))}
          </dl>
          <p className="muted">
            {t(
              "aisettings.each_plugin_can_choose_a_different_configured_profile",
            )}
          </p>
          <button
            disabled={
              ai.can_manage === false ||
              check.isPending ||
              !Object.keys(ai.bindings).length
            }
            onClick={() => check.mutate()}
          >
            {check.isPending
              ? t("aisettings.checking_providers")
              : t("aisettings.check_providers")}
          </button>
          <AIHealthResults rows={check.data?.health ?? ai.health ?? []} />
          {ai.indexing && (
            <>
              <h2>{t("aisettings.embedding_index")}</h2>
              <p>
                {!ai.indexing.available
                  ? t("aisettings.vector_index_unavailable")
                  : ai.indexing.enabled
                    ? t("aisettings.indexing_is_on")
                    : t("aisettings.indexing_is_paused")}{" "}
                {t("aisettings.chunks_count", { count: ai.indexing.chunks })}
              </p>
              {ai.indexing.routes.map((route) => (
                <p
                  key={route.profile}
                  role="status"
                  className={styles.indexStatus}
                >
                  {t("aisettings.index_progress", {
                    provider: route.profile,
                    indexed: route.indexed,
                    total: route.total,
                  })}
                  {route.active_key &&
                    route.active_key !== route.target_key &&
                    t(
                      "aisettings.the_previous_index_is_retained_until_the_new",
                    )}
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
                {reindex.isPending
                  ? t("aisettings.queuing_index")
                  : t("aisettings.reindex_notes")}
              </button>
              {reindex.isSuccess && (
                <p role="status">{t("aisettings.index_work_queued")}</p>
              )}
            </>
          )}
        </>
      )}
      {(query.error || check.error || reindex.error) && (
        <p role="alert" className="error">
          {(query.error ?? check.error ?? reindex.error)?.message}
        </p>
      )}
      {ai && !!ai.profiles.length && (
        <Link href="/status">{t("aisettings.server_status")}</Link>
      )}
    </>
  );
}
export function AIRouting({ plugin }: { plugin: Plugin }) {
  useLanguage();
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
  const used = capabilities().filter(([capability]) =>
    plugin.permissions.includes(`ai.${capability}`),
  );
  if (!used.length)
    return (
      <p className="muted">{t("aisettings.this_plugin_does_not_use_ai")}</p>
    );
  if (!query.data)
    return query.isPending ? (
      <p>{t("aisettings.loading_ai_routes")}</p>
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
              {t("aisettings.profile_label", { capability: title })}
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
                {t("aisettings.default_profile_choice", {
                  profile: ai.bindings[capability] ?? t("aisettings.off"),
                })}
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
                  ? t("aisettings.leaves_this_network")
                  : t("aisettings.local_network")}
              </small>
            )}
          </div>
        );
      })}
      <button
        className="primary"
        disabled={save.isPending || ai.can_manage === false}
      >
        {save.isPending ? t("app.saving") : t("aisettings.save_ai_routes")}
      </button>
      {save.isSuccess && <p role="status">{t("aisettings.ai_routes_saved")}</p>}
      {ai.can_manage === false && (
        <p>{t("aisettings.only_a_space_owner_can_change_ai_routes")}</p>
      )}
      <p className={styles.facts}>
        {t("aisettings.calls", { calls, failures })}
      </p>
      {save.error && (
        <p role="alert" className="error">
          {save.error.message}
        </p>
      )}
    </form>
  );
}
