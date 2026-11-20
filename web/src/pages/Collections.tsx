import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useDeferredValue, useState } from "react";
import { Link, useLocation, useSearch } from "wouter";
import {
  api,
  safeError,
  useBrowserOnline,
  useManifest,
  useOwner,
} from "../lib";
import { createIdempotencyKey } from "../lib/drafts";
import { getLanguage, t, useLanguage } from "../lib/i18n";
import { isBundledPlugin } from "../lib/localizePlugins";
import { NotePicker } from "../features/NotePicker";
import { copyText } from "../features/copy";
import { askItems } from "../features/askConversationData";
import { outputText } from "../features/vocabularyData";
import styles from "./ThinkingDesk.module.css";
import collectionStyles from "./Collections.module.css";

type Source = {
  id: string;
  title: string;
  version: number | null;
  status: "current" | "changed" | "unavailable";
  pinned: boolean;
  private?: boolean;
  external?: boolean;
  accepted?: boolean;
};
type Summary = {
  id: string;
  title: string;
  draft: boolean;
  count: number;
  updated_at: number;
};
type Collection = Summary & {
  revision: number;
  intro: string;
  intro_citations: Record<string, string>;
  goal: string;
  private: boolean;
  external: boolean;
  sources: Source[];
  markdown: string;
  agent_access: string;
  ask_ready: boolean;
};
type Page = { collections: Summary[]; next_cursor: string | null };
type Candidate = {
  id: string;
  title: string;
  version: number;
  passage: string;
  status: "new" | "changed";
};
type Suggestion = {
  title: string;
  intro: string;
  sources: Candidate[];
  citations: string[];
  fingerprints: Record<string, string>;
};

function request<T>(body: Record<string, unknown>) {
  return api.request<T>("/api/v1/collections", { method: "POST", body });
}

export default function Collections({ collection }: { collection?: string }) {
  useLanguage();
  const owner = useOwner();
  const search = useSearch();
  const seed = new URLSearchParams(search).get("note") ?? undefined;
  return (
    <section className={`${styles.frame} ${collectionStyles.frame}`}>
      {collection ? (
        <CollectionPage
          key={`${owner}:${collection}:${seed ?? ""}`}
          id={collection}
          seed={seed}
        />
      ) : (
        <CollectionLibrary key={`${owner}:${seed ?? ""}`} seed={seed} />
      )}
    </section>
  );
}

function href(id: string, note?: string) {
  return `/collections/${id}${note ? `?note=${encodeURIComponent(note)}` : ""}`;
}

function CollectionLibrary({ seed }: { seed?: string }) {
  const online = useBrowserOnline();
  const owner = useOwner();
  const [search, setSearch] = useState("");
  const deferred = useDeferredValue(search.trim());
  const pages = useInfiniteQuery({
    queryKey: ["collections", owner, deferred],
    queryFn: ({ pageParam }) =>
      request<Page>({ cursor: pageParam, query: deferred }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.next_cursor ?? undefined,
    enabled: online,
    staleTime: 0,
    retry: false,
  });
  const collections =
    pages.data?.pages.flatMap((page) => page.collections) ?? [];
  return (
    <>
      <h1>{t("collections.title")}</h1>
      <p>{t(seed ? "collections.choose_collection" : "collections.intro")}</p>
      {!online ? (
        <p role="status">{t("collections.connect")}</p>
      ) : (
        <>
          <Link className="button primary" href={href("new", seed)}>
            {t("collections.new")}
          </Link>
          <label>
            {t("collections.search")}
            <input
              type="search"
              className="field"
              maxLength={80}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </label>
          {pages.isPending && <p role="status">{t("app.opening")}</p>}
          {pages.isError && (
            <p role="alert" className="error">
              {safeError(pages.error).message}{" "}
              <button onClick={() => void pages.refetch()}>
                {t("app.try_again")}
              </button>
            </p>
          )}
          {pages.isSuccess &&
            (collections.length ? (
              <ul className={styles.desks}>
                {collections.map((item) => (
                  <li key={item.id}>
                    <Link href={href(item.id, seed)}>
                      <strong>{item.title}</strong>
                      <small>
                        {item.draft
                          ? t("collections.draft")
                          : t("collections.source_count", {
                              count: item.count,
                            })}
                      </small>
                      <small>
                        {new Date(item.updated_at).toLocaleDateString(
                          getLanguage(),
                          { dateStyle: "medium" },
                        )}
                      </small>
                    </Link>
                  </li>
                ))}
              </ul>
            ) : (
              <p>{t("collections.empty")}</p>
            ))}
          {pages.hasNextPage && (
            <button
              disabled={pages.isFetchingNextPage}
              onClick={() => void pages.fetchNextPage()}
            >
              {t("collections.more")}
            </button>
          )}
        </>
      )}
    </>
  );
}

function CollectionPage({ id, seed }: { id: string; seed?: string }) {
  const online = useBrowserOnline();
  const query = useQuery({
    queryKey: ["collection", id],
    queryFn: () => request<Collection>({ action: "read", id }),
    enabled: id !== "new" && online,
    staleTime: 0,
    retry: false,
  });
  const source = useQuery({
    queryKey: ["collection-note", seed],
    queryFn: () => api.note(seed!),
    enabled: !!seed && online,
    retry: false,
  });
  if (!online) return <p role="status">{t("collections.connect")}</p>;
  if (query.isError || source.isError)
    return (
      <p className="error" role="alert">
        {safeError(query.error ?? source.error).message}{" "}
        <Link href="/collections">{t("collections.title")}</Link>
      </p>
    );
  if ((id !== "new" && !query.data) || (seed && !source.data))
    return <p role="status">{t("app.opening")}</p>;
  return <CollectionEditor current={query.data} seed={source.data} />;
}

function CollectionEditor({
  current,
  seed,
}: {
  current?: Collection;
  seed?: { id: string; title: string; version: number };
}) {
  const [, navigate] = useLocation();
  const client = useQueryClient();
  const manifest = useManifest();
  const ai = manifest.data?.plugins.some(
    (item) => item.id === "note-assist" && isBundledPlugin(item),
  );
  const ask = manifest.data?.plugins.some(
    (item) => item.id === "ask" && isBundledPlugin(item),
  );
  const [requestId] = useState(createIdempotencyKey);
  const [saved, setSaved] = useState(current);
  const [editing, setEditing] = useState(!current || current.draft || !!seed);
  const [showUpdates, setShowUpdates] = useState(false);
  const [title, setTitle] = useState(current?.title ?? "");
  const [intro, setIntro] = useState(current?.intro ?? "");
  const [introCitations, setIntroCitations] = useState(
    current?.intro_citations ?? {},
  );
  const [assistGoal, setAssistGoal] = useState("");
  const [goal, setGoal] = useState(current?.goal ?? "");
  const privateGoal =
    !!saved &&
    (saved.private ||
      saved.external ||
      !saved.goal.trim() ||
      saved.sources.some(
        (item) =>
          item.private || item.external || item.status === "unavailable",
      ));
  const requestGoal = privateGoal ? assistGoal : goal;
  const [restricted, setRestricted] = useState(current?.private ?? true);
  const [external, setExternal] = useState(current?.external ?? false);
  const [sources, setSources] = useState<Source[]>(() => {
    const initial = current?.sources ?? [];
    return seed && !initial.some((item) => item.id === seed.id)
      ? [
          ...initial,
          { ...seed, status: "current", pinned: false, accepted: true },
        ]
      : initial;
  });
  const [picker, setPicker] = useState(false);
  const [remove, setRemove] = useState(false);
  const [showUse, setShowUse] = useState(false);
  const [copied, setCopied] = useState(false);
  const [instruction, setInstruction] = useState("");
  const [suggestion, setSuggestion] = useState<Suggestion>();
  const [delta, setDelta] = useState<{ added: number; removed: number }>();
  const [dirty, setDirty] = useState(!current || current.draft || !!seed);
  const [savedNotice, setSavedNotice] = useState(false);

  const write = useMutation<
    Collection | { deleted: true },
    Error,
    "save" | "delete"
  >({
    mutationFn: (action: "save" | "delete") =>
      action === "delete"
        ? request<{ deleted: true }>({
            action,
            id: saved?.id,
            revision: saved?.revision,
          })
        : request<Collection>({
            action,
            id: saved?.id ?? requestId,
            revision: saved?.revision,
            title,
            intro,
            intro_citations: intro.trim() ? introCitations : {},
            goal,
            private: restricted,
            external,
            sources: sources.map((item) => ({
              id: item.id,
              version: item.accepted ? item.version : undefined,
              pinned: item.pinned,
            })),
          }),
    onSuccess: (result) => {
      void client.invalidateQueries({ queryKey: ["collections"] });
      if ("deleted" in result) {
        navigate("/collections");
        return;
      }
      client.setQueryData(["collection", result.id], result);
      setSaved(result);
      setSources(result.sources);
      setIntroCitations(result.intro_citations);
      setRestricted(result.private);
      setExternal(result.external);
      setDirty(false);
      setEditing(false);
      setShowUpdates(false);
      setSavedNotice(true);
      if (!saved || seed) navigate(href(result.id), { replace: true });
    },
  });
  const assist = useMutation({
    mutationFn: (mode: "draft" | "updates") =>
      api.request<Suggestion>("/api/v1/note-assist/collections", {
        method: "POST",
        body: {
          goal: requestGoal,
          instruction,
          mode,
          collection_id: saved?.id,
          selected: sources.map((item) => item.id),
          proposed: suggestion?.sources.map((item) => item.id),
        },
      }),
    onSuccess: (next) => {
      if (instruction.trim() && suggestion) {
        const previousIds = new Set(suggestion.sources.map((item) => item.id));
        const nextIds = new Set(next.sources.map((item) => item.id));
        setDelta({
          added: [...nextIds].filter((id) => !previousIds.has(id)).length,
          removed: [...previousIds].filter((id) => !nextIds.has(id)).length,
        });
      } else setDelta(undefined);
      setSuggestion(next);
    },
  });
  const snapshot = useMutation({
    mutationFn: () => {
      if (!saved) throw new Error(t("collections.connect"));
      return api.command("context-packs", "edit", {
        action: "save-note",
        pack_id: saved.id,
        expected_revision: saved.revision,
      });
    },
  });
  const snapshotIds = askItems(snapshot.data).find(
    (item) => item.type === "notes",
  )?.ids;
  const snapshotId =
    Array.isArray(snapshotIds) && typeof snapshotIds[0] === "string"
      ? snapshotIds[0]
      : undefined;
  const busy = write.isPending || assist.isPending || snapshot.isPending;
  const change = (update: () => void) => {
    update();
    setEditing(true);
    setDirty(true);
    setSavedNotice(false);
  };
  const includeIntro = (draft: Suggestion, selected: Source[]) => {
    if (!draft.intro || !draft.citations.length) return;
    const verified = draft.citations.every(
      (key) =>
        !!draft.fingerprints?.[key] &&
        selected.some(
          (item) =>
            item.id === key &&
            item.status === "current" &&
            draft.sources.some(
              (candidate) =>
                candidate.id === key && candidate.version === item.version,
            ),
        ),
    );
    if (verified) {
      setIntro(draft.intro);
      setIntroCitations(draft.fingerprints);
      setEditing(true);
    }
  };
  const introAvailable = suggestion?.citations.every(
    (key) =>
      !!suggestion.fingerprints?.[key] &&
      sources.some(
        (item) =>
          item.id === key &&
          item.status === "current" &&
          suggestion.sources.some(
            (candidate) =>
              candidate.id === key && candidate.version === item.version,
          ),
      ),
  );
  const accept = (candidate: Candidate) =>
    change(() => {
      setSources((previous) => {
        const source: Source = {
          ...candidate,
          status: "current",
          pinned: false,
          accepted: true,
        };
        const index = previous.findIndex((item) => item.id === source.id);
        if (index === -1)
          return previous.length < 10 ? [...previous, source] : previous;
        return previous.map((item, pos) =>
          pos === index ? { ...source, pinned: item.pinned } : item,
        );
      });
    });
  const move = (index: number, delta: number) =>
    change(() => {
      setSources((old) => {
        const updated = [...old];
        const [item] = updated.splice(index, 1);
        updated.splice(index + delta, 0, item);
        return updated;
      });
    });
  const assistant = (
    <section className={collectionStyles.assist}>
      {editing && <a href="#collection-sources">{t("collections.manual")}</a>}
      {ai ? (
        <>
          <p>{t("collections.model_guidance")}</p>
          <button
            disabled={busy || !requestGoal.trim()}
            onClick={() => assist.mutate(editing ? "draft" : "updates")}
          >
            {t(
              editing ? "collections.find_notes" : "collections.recent_search",
            )}
          </button>{" "}
          {editing && saved && !saved.draft && (
            <button
              disabled={busy || !requestGoal.trim()}
              onClick={() => assist.mutate("updates")}
            >
              {t("collections.whats_new")}
            </button>
          )}
          {delta && <p role="status">{t("collections.delta", delta)}</p>}
          {suggestion && (
            <>
              {suggestion.sources.length ? (
                <ul className={collectionStyles.sources}>
                  {suggestion.sources.map((item) => {
                    const linked = sources.find(
                      (source) => source.id === item.id,
                    );
                    const included =
                      linked?.version === item.version &&
                      linked.status === "current";
                    return (
                      <li key={item.id}>
                        <strong>
                          {item.title || t("collections.untitled")}
                        </strong>
                        {suggestion.citations.includes(item.id) && (
                          <small>{t("collections.cited")}</small>
                        )}
                        <blockquote>{item.passage}</blockquote>
                        <button
                          disabled={
                            busy ||
                            included ||
                            (sources.length >= 10 && !linked)
                          }
                          onClick={() => accept(item)}
                        >
                          {t(
                            included
                              ? "collections.included"
                              : linked
                                ? "collections.accept_update"
                                : "collections.add_note",
                          )}
                        </button>
                      </li>
                    );
                  })}
                </ul>
              ) : (
                <p role="status">{t("collections.no_matches")}</p>
              )}
              {suggestion.sources.length > 0 && (
                <button
                  disabled={busy}
                  onClick={() =>
                    change(() => {
                      if (!title.trim())
                        setTitle(
                          suggestion.title.trim() ||
                            requestGoal.trim().slice(0, 80),
                        );
                      const proposed = suggestion.sources
                        .filter(
                          (item) =>
                            !sources.some((source) => source.id === item.id),
                        )
                        .slice(0, 10 - sources.length);
                      proposed.forEach(accept);
                      if (!intro.trim())
                        includeIntro(suggestion, [
                          ...sources,
                          ...proposed.map((item) => ({
                            ...item,
                            pinned: false,
                            status: "current" as const,
                          })),
                        ]);
                    })
                  }
                >
                  {t("collections.use_suggestions")}
                </button>
              )}
              {suggestion.intro && (
                <>
                  <p>{suggestion.intro}</p>
                  <button
                    disabled={busy || !introAvailable}
                    onClick={() =>
                      change(() => includeIntro(suggestion, sources))
                    }
                  >
                    {t("collections.use_intro")}
                  </button>
                </>
              )}
              <label>
                {t("collections.refine")}
                <textarea
                  className="field"
                  rows={2}
                  maxLength={500}
                  value={instruction}
                  onChange={(e) => setInstruction(e.target.value)}
                />
              </label>
              <button
                disabled={busy || !requestGoal.trim() || !instruction.trim()}
                onClick={() => assist.mutate(assist.variables ?? "draft")}
              >
                {t("collections.refine_action")}
              </button>
            </>
          )}
        </>
      ) : (
        <p>
          {t("collections.ai_off")}{" "}
          <Link href="/settings/plugins/note-assist">
            {t("collections.assist_settings")}
          </Link>
        </p>
      )}
      {assist.isError && (
        <p role="alert" className="error">
          {safeError(assist.error).message}
        </p>
      )}
    </section>
  );
  const brief = showUse && saved && !dirty && (
    <section>
      <h2>{t("collections.use_brief")}</h2>
      <p>{saved.agent_access}</p>
      <pre className={collectionStyles.markdown}>{saved.markdown}</pre>
      <button onClick={() => void copyText(saved.markdown).then(setCopied)}>
        {copied ? t("settingsaccess.copied") : t("slots.copy_text")}
      </button>{" "}
      <button
        disabled={
          busy || saved.sources.some((item) => item.status !== "current")
        }
        onClick={() => snapshot.mutate()}
      >
        {t("collections.save_snapshot")}
      </button>
      {snapshot.isSuccess && (
        <p role="status">
          {snapshotId ? t("slots.saved_as_note") : outputText(snapshot.data)}
          {snapshotId && (
            <>
              {" "}
              <Link href={`/n/${snapshotId}`}>
                {t("collections.open_source")}
              </Link>
            </>
          )}
        </p>
      )}
      {snapshot.isError && (
        <p role="alert" className="error">
          {safeError(snapshot.error).message}
        </p>
      )}
    </section>
  );
  if (!editing && saved)
    return (
      <>
        <Link href="/collections">{t("collections.title")}</Link>
        <h1>{saved.title}</h1>
        {(saved.intro || saved.goal) && (
          <p className={collectionStyles.summary}>
            {saved.intro || saved.goal}
          </p>
        )}
        <h2>{t("collections.sources")}</h2>
        {saved.sources.length ? (
          <ol className={collectionStyles.sources}>
            {saved.sources.map((item) => (
              <li key={item.id}>
                {item.status === "unavailable" ? (
                  t("collections.unavailable")
                ) : (
                  <Link href={`/n/${item.id}`}>
                    {item.title || t("collections.untitled")}
                  </Link>
                )}
                {item.pinned && <small>{t("collections.pinned")}</small>}
                {item.status === "changed" && (
                  <small>{t("collections.changed")}</small>
                )}
              </li>
            ))}
          </ol>
        ) : (
          <p>{t("collections.no_sources")}</p>
        )}
        <div className={collectionStyles.actions}>
          <button className="primary" onClick={() => setEditing(true)}>
            {t("collections.edit")}
          </button>
          <button
            onClick={() => setShowUpdates(!showUpdates)}
            aria-expanded={showUpdates}
          >
            {t("collections.whats_new")}
          </button>
          {ask && saved.ask_ready && (
            <Link className="button" href={`/ask?collection=${saved.id}`}>
              {t("collections.discussion")}
            </Link>
          )}
          <button onClick={() => setShowUse(!showUse)}>
            {t("collections.use_brief")}
          </button>
        </div>
        {ask && (
          <p>
            {t(
              saved.ask_ready
                ? "collections.ask_guidance"
                : "collections.ask_ineligible",
            )}
          </p>
        )}
        {showUpdates && (
          <>
            {privateGoal ? (
              <label>
                {t("collections.assist_goal")}
                <textarea
                  className="field"
                  rows={2}
                  value={assistGoal}
                  maxLength={500}
                  onChange={(event) => setAssistGoal(event.target.value)}
                />
              </label>
            ) : (
              <p>{saved.goal}</p>
            )}
            {assistant}
            {assist.isPending && <p role="status">{t("plugins.running")}</p>}
          </>
        )}
        {brief}
        {savedNotice && <p role="status">{t("collections.saved")}</p>}
      </>
    );
  return (
    <>
      <Link href="/collections">{t("collections.title")}</Link>
      <h1>{saved?.title ?? t("collections.new")}</h1>
      {privateGoal ? (
        <label>
          {t("collections.assist_goal")}
          <textarea
            className="field"
            rows={2}
            value={assistGoal}
            maxLength={500}
            onChange={(event) => setAssistGoal(event.target.value)}
          />
        </label>
      ) : (
        <label>
          {t("collections.goal")}
          <textarea
            className="field"
            rows={2}
            value={goal}
            maxLength={500}
            onChange={(e) => change(() => setGoal(e.target.value))}
          />
        </label>
      )}
      {assistant}
      <div className={collectionStyles.layout}>
        <div>
          <h2>{t("collections.details")}</h2>
          {privateGoal && (
            <label>
              {t("collections.goal")}
              <textarea
                className="field"
                rows={2}
                value={goal}
                maxLength={500}
                onChange={(e) => change(() => setGoal(e.target.value))}
              />
            </label>
          )}

          <label>
            {t("collections.name")}
            <input
              className="field"
              value={title}
              maxLength={80}
              onChange={(e) => change(() => setTitle(e.target.value))}
            />
          </label>
          <label>
            {t("collections.brief")}
            <textarea
              className="field"
              rows={4}
              value={intro}
              maxLength={600}
              onChange={(e) =>
                change(() => {
                  setIntro(e.target.value);
                  if (!e.target.value.trim()) setIntroCitations({});
                })
              }
            />
          </label>
          <details>
            <summary>{t("collections.access")}</summary>
            <label className={collectionStyles.check}>
              <input
                type="checkbox"
                checked={restricted}
                onChange={(e) => change(() => setRestricted(e.target.checked))}
              />
              {t("collections.private")}
            </label>
            <label className={collectionStyles.check}>
              <input
                type="checkbox"
                checked={external}
                onChange={(e) => change(() => setExternal(e.target.checked))}
              />
              {t("collections.external")}
            </label>
          </details>
        </div>
        <div>
          <h2 id="collection-sources">{t("collections.sources")}</h2>
          {!sources.length && <p>{t("collections.no_sources")}</p>}
          <ol className={collectionStyles.sources}>
            {sources.map((item, index) => (
              <li key={item.id}>
                <strong>{item.title || t("collections.unavailable")}</strong>
                {item.pinned && <small>{t("collections.pinned")}</small>}
                {item.status !== "current" && (
                  <small>{t(`collections.${item.status}`)}</small>
                )}
                <div className={collectionStyles.actions}>
                  {item.status !== "unavailable" && (
                    <Link href={`/n/${item.id}`}>
                      {t("collections.open_source")}
                    </Link>
                  )}
                  <button
                    disabled={busy}
                    onClick={() =>
                      change(() =>
                        setSources((old) =>
                          old.map((row) =>
                            row.id === item.id
                              ? { ...row, pinned: !row.pinned }
                              : row,
                          ),
                        ),
                      )
                    }
                  >
                    {t(item.pinned ? "collections.unpin" : "collections.pin")}
                  </button>
                  <button
                    disabled={busy || index === 0}
                    onClick={() => move(index, -1)}
                    aria-label={`${t("collections.up")} ${item.title}`}
                  >
                    ↑
                  </button>
                  <button
                    disabled={busy || index === sources.length - 1}
                    onClick={() => move(index, 1)}
                    aria-label={`${t("collections.down")} ${item.title}`}
                  >
                    ↓
                  </button>
                  {item.status === "changed" && item.version && (
                    <button
                      disabled={busy}
                      onClick={() =>
                        change(() =>
                          setSources((old) =>
                            old.map((row) =>
                              row.id === item.id
                                ? { ...row, status: "current", accepted: true }
                                : row,
                            ),
                          ),
                        )
                      }
                    >
                      {t("collections.accept_update")}
                    </button>
                  )}
                  <button
                    disabled={
                      busy || (!!intro.trim() && item.id in introCitations)
                    }
                    title={
                      item.id in introCitations
                        ? t("collections.intro_source")
                        : undefined
                    }
                    onClick={() =>
                      change(() =>
                        setSources((old) =>
                          old.filter((row) => row.id !== item.id),
                        ),
                      )
                    }
                  >
                    {t("collections.remove_source")}
                  </button>
                </div>
              </li>
            ))}
          </ol>
          <button
            disabled={busy || sources.length >= 10}
            onClick={() => setPicker(!picker)}
          >
            {t("collections.add_notes")}
          </button>
          {picker && sources.length < 10 && (
            <NotePicker
              exclude={sources.map((item) => item.id)}
              action={t("collections.add_note")}
              busy={busy}
              maxSelection={10 - sources.length}
              onSelectMany={(notes) =>
                notes.forEach((note) =>
                  accept({ ...note, passage: "", status: "new" }),
                )
              }
              onCancel={() => setPicker(false)}
            />
          )}
        </div>
      </div>
      {(write.isPending || assist.isPending) && (
        <p role="status">{t("plugins.running")}</p>
      )}
      {write.isError && (
        <p role="alert" className="error">
          {safeError(write.error).message}
        </p>
      )}
      {savedNotice && <p role="status">{t("collections.saved")}</p>}
      <div className={collectionStyles.actions}>
        <button
          className="primary"
          disabled={busy || !dirty || !title.trim() || sources.length > 10}
          onClick={() => write.mutate("save")}
        >
          {t("collections.save")}
        </button>
        {saved && !seed && !saved.draft && (
          <button
            disabled={busy}
            onClick={() => {
              setTitle(saved.title);
              setGoal(saved.goal);
              setIntro(saved.intro);
              setIntroCitations(saved.intro_citations);
              setSources(saved.sources);
              setRestricted(saved.private);
              setExternal(saved.external);
              setDirty(false);
              setEditing(false);
              setPicker(false);
            }}
          >
            {t("profilepanel.cancel")}
          </button>
        )}
        {saved && !saved.draft && (
          <>
            <button disabled={dirty} onClick={() => setShowUse(!showUse)}>
              {t("collections.use_brief")}
            </button>
            {ask && saved.ask_ready && !dirty && (
              <Link href={`/ask?collection=${saved.id}`}>
                {t("collections.ask")}
              </Link>
            )}
          </>
        )}
        {saved && (
          <button disabled={busy} onClick={() => setRemove(!remove)}>
            {t("collections.delete")}
          </button>
        )}
      </div>
      {remove && (
        <p role="group" aria-label={t("collections.delete")}>
          {t("collections.confirm_delete")}{" "}
          <button disabled={busy} onClick={() => write.mutate("delete")}>
            {t("collections.delete")}
          </button>{" "}
          <button onClick={() => setRemove(false)}>
            {t("profilepanel.cancel")}
          </button>
        </p>
      )}
      {ask && saved && !dirty && !saved.draft && (
        <p>
          {t(
            saved.ask_ready
              ? "collections.ask_guidance"
              : "collections.ask_ineligible",
          )}
        </p>
      )}
      {brief}
    </>
  );
}
