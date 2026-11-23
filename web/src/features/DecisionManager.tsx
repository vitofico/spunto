import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useRef, useState } from "react";
import { Link, useLocation } from "wouter";
import { api } from "../lib/api";
import { NoteBody } from "../components/core/NoteCard";
import { requestGeneration } from "../lib/identity";
import { t, useLanguage } from "../lib/i18n";
import {
  invalidateContentFor,
  useBrowserOnline,
  useWorkspaceRole,
} from "../lib/query";
import type { Args, Json, Schema } from "../lib/types";
import { askItems } from "./askConversationData";
import CommandResult from "./CommandResult";
import {
  DecisionCapture,
  DecisionCommand,
  DecisionRationale,
} from "./DecisionJournal";
import { NoteReference } from "./Vocabulary";
import { decisionsPath, decisionStatus } from "./decisionJournalData";
import { Dialog } from "./Dialog";
import { activePlugins, usePlugins } from "./Slots";
import { record } from "./vocabularyData";
import styles from "./Feature.module.css";

const journal = "decision-journal";
const assistPath = "/api/v1/note-assist/decisions";
type Source = {
  id: string;
  title: string;
  passage: string;
  supports?: string[];
};
type Draft = {
  draft?: Args;
  message?: string;
  matches: (Source & { version: number; fingerprint: string })[];
  sources?: (Source & { version: number; fingerprint: string })[];
  basis?: { id: string; version: number; fingerprint: string };
};

function Evidence({ sources }: { sources: Source[] }) {
  return (
    <ul>
      {sources.map((source) => (
        <li key={source.id}>
          <Link href={`/n/${encodeURIComponent(source.id)}`}>
            {source.title || t("vocabulary.untitled_note")}
          </Link>
          {source.supports?.includes("reason") && (
            <small> {t("decisions.reason_evidence")}</small>
          )}
          {source.supports?.includes("question") && (
            <small> {t("decisions.form.questions")}</small>
          )}
          <blockquote className={styles.quote}>{source.passage}</blockquote>
        </li>
      ))}
    </ul>
  );
}

function useDecisionAssist() {
  const manifest = usePlugins();
  return activePlugins(manifest.data?.plugins).some(
    (plugin) => plugin.id === "note-assist",
  );
}

function DraftDecision({ noteId }: { noteId?: string }) {
  const [phrase, setPhrase] = useState("");
  const [preview, setPreview] = useState<{
    form: Schema;
    evidence: Args;
    target?: string;
    command: string;
    targetTitle?: string;
  }>();
  const online = useBrowserOnline();
  const assist = useDecisionAssist();
  const draft = useMutation({
    mutationFn: async () => {
      const generation = requestGeneration();
      const result = await api.request<Draft>(assistPath, {
        method: "POST",
        body: { phrase, ...(noteId ? { note_id: noteId } : {}) },
      });
      if (generation !== requestGeneration())
        throw new DOMException("Workspace changed", "AbortError");
      return result;
    },
  });
  const prepare = useMutation({
    mutationFn: async ({
      candidate,
      target = noteId,
    }: {
      candidate: Draft;
      target?: string;
    }) => {
      const command = target ? "review-decision" : "record-decision";
      const updating = !!target && !noteId;
      const values: Args = updating
        ? { outcome: phrase, status: "open" }
        : target
          ? (candidate.draft ?? (phrase ? { outcome: phrase } : {}))
          : { ...candidate.draft, decision: phrase };
      const generation = requestGeneration();
      const form = await api.command(
        journal,
        command,
        target ? { note_id: target } : {},
      );
      if (generation !== requestGeneration())
        throw new DOMException("Workspace changed", "AbortError");
      if (!record(form) || form.type !== "form" || !record(form.schema))
        throw new Error(t("decisions.resume_manually"));
      const schema = form.schema as Schema;
      const match = updating
        ? candidate.matches.find((item) => item.id === target)
        : undefined;
      const evidence = Object.fromEntries(
        [
          ...(candidate.sources ?? []),
          ...(candidate.basis ? [candidate.basis] : []),
          ...(match ? [match] : []),
        ].map((source) => [
          source.id,
          { version: source.version, fingerprint: source.fingerprint },
        ]),
      );
      return {
        command,
        target,
        targetTitle: match?.title,
        form: {
          ...schema,
          properties: Object.fromEntries(
            Object.entries(schema.properties ?? {}).map(([key, field]) => {
              const value = values[key];
              return [
                key,
                typeof value === "string"
                  ? { ...field, default: value }
                  : field,
              ];
            }),
          ),
        },
        evidence,
      };
    },
    onSuccess: setPreview,
  });
  const prepared = useMemo(
    () =>
      preview
        ? {
            value: { type: "form", schema: preview.form },
            origin: {
              plugin: journal,
              command: preview.command,
              args: {
                ...(preview.target ? { note_id: preview.target } : {}),
                ...(Object.keys(preview.evidence).length
                  ? { evidence: preview.evidence as Json }
                  : {}),
              },
            },
          }
        : undefined,
    [preview],
  );
  const source = draft.data;
  return (
    <section aria-label={t("decisions.assisted_draft")}>
      {!preview && (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            setPreview(undefined);
            if (assist) draft.mutate();
            else {
              draft.reset();
              prepare.mutate({ candidate: { matches: [] } });
            }
          }}
        >
          <label>
            {t(noteId ? "decisions.since_focus" : "decisions.phrase")}
            <input
              className="field"
              value={phrase}
              onChange={(event) => {
                setPhrase(event.target.value);
                draft.reset();
                prepare.reset();
              }}
              disabled={draft.isPending || prepare.isPending}
              maxLength={500}
              required={!noteId}
            />
          </label>
          <button type="submit" disabled={!online || draft.isPending}>
            {t(noteId ? "decisions.what_happened" : "decisions.continue")}
          </button>
        </form>
      )}
      {!assist && (
        <p>
          <Link href="/settings/plugins/note-assist">
            {t("decisions.enable_assist")}
          </Link>
        </p>
      )}
      {draft.isPending && <p role="status">{t("plugins.running")}</p>}
      {draft.isError && <p role="alert">{draft.error.message}</p>}
      {source?.message && <p role="status">{source.message}</p>}
      {!!source?.matches.length && (
        <>
          <h3>{t("decisions.related_decisions")}</h3>
          <ul>
            {source.matches.map((match) => (
              <li key={match.id}>
                <button
                  onClick={() =>
                    prepare.mutate({ candidate: source, target: match.id })
                  }
                >
                  {t("decisions.update_match", {
                    title: match.title || t("vocabulary.untitled_note"),
                  })}
                </button>
                <blockquote className={styles.quote}>
                  {match.passage}
                </blockquote>
              </li>
            ))}
          </ul>
        </>
      )}
      {source?.sources?.length ? (
        <>
          <h3>{t("decisions.evidence")}</h3>
          <Evidence sources={source.sources} />
        </>
      ) : null}
      {source && !preview && (
        <button
          disabled={!online || prepare.isPending}
          onClick={() => prepare.mutate({ candidate: source })}
        >
          {t(
            noteId
              ? source.draft
                ? "decisions.review_outcome_draft"
                : "decisions.record_outcome"
              : source.draft
                ? "decisions.review_distinct_draft"
                : "decisions.use_wording",
          )}
        </button>
      )}
      {draft.isError && (
        <button onClick={() => prepare.mutate({ candidate: { matches: [] } })}>
          {t(noteId ? "decisions.record_outcome" : "decisions.use_wording")}
        </button>
      )}
      {prepare.isError && <p role="alert">{prepare.error.message}</p>}
      {preview && (
        <>
          {preview.targetTitle && (
            <h3>
              {t("decisions.update_match", { title: preview.targetTitle })}
            </h3>
          )}
          {prepared && <CommandResult {...prepared} />}
          <button onClick={() => setPreview(undefined)}>
            {t("decisions.back_to_wording")}
          </button>
        </>
      )}
    </section>
  );
}

function DecisionLink({ id, reason }: { id: string; reason?: string }) {
  const note = useQuery({
    queryKey: ["note", id],
    queryFn: () => api.note(id),
    retry: false,
  });
  if (note.isError) return null;
  return (
    <Link
      className={styles.noteRef}
      href={`/decisions/${encodeURIComponent(id)}`}
    >
      <span>{note.data?.title || t("vocabulary.untitled_note")}</span>{" "}
      <small>{reason}</small>
    </Link>
  );
}

export default function DecisionManager({ noteId }: { noteId?: string }) {
  useLanguage();
  const online = useBrowserOnline();
  const role = useWorkspaceRole();
  const client = useQueryClient();
  const [, navigate] = useLocation();
  const manifest = usePlugins();
  const plugin = activePlugins(manifest.data?.plugins).find(
    (item) => item.id === journal,
  );
  const [draft, setDraft] = useState(false);
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const [due, setDue] = useState(false);
  const [cursor, setCursor] = useState<string>();
  const [outcomeCursor, setOutcomeCursor] = useState<string>();
  const dialogOpener = useRef<HTMLElement | null>(null);
  const [trash, setTrash] = useState(false);
  const allowed = role === "owner" && !!plugin;
  const assist = useDecisionAssist();
  const original = useQuery({
    queryKey: ["note", noteId],
    queryFn: () => api.note(noteId!),
    enabled: allowed && !!noteId && online,
    retry: false,
  });
  const [major, minor] = (plugin?.version ?? "").split(".").map(Number);
  const upgraded = major > 1 || (major === 1 && minor >= 1);
  const args: Args = {
    ...(noteId ? { note_id: noteId } : {}),
    ...(upgraded
      ? {
          manager: true,
          ...(query ? { query } : {}),
          ...(status ? { status } : {}),
          ...(due ? { due: true } : {}),
        }
      : {}),
    ...(cursor ? { cursor } : {}),
    ...(outcomeCursor ? { outcome_cursor: outcomeCursor } : {}),
  };
  const result = useQuery({
    queryKey: ["notes", "decision-journal", args],
    queryFn: () => api.command(journal, "journal", args),
    enabled: allowed && online,
    retry: false,
  });
  const remove = useMutation({
    mutationFn: () => api.flags(noteId!, { trashed: true }),
    onSuccess: () => {
      void invalidateContentFor(client);
      navigate(decisionsPath);
    },
  });
  const criteria = () => setCursor(undefined);
  const items = askItems(result.data);
  const refs =
    !noteId && items[0]?.type === "notes" && Array.isArray(items[0].items)
      ? items[0].items.filter(record)
      : [];
  const summary =
    noteId && items[0]?.type === "text" ? String(items[0].text) : "";
  const outcomes =
    noteId && items[2]?.type === "notes" && Array.isArray(items[2].items)
      ? items[2].items.filter(record)
      : [];
  const reviewable =
    !!noteId &&
    items.some((item) => item.type === "notes") &&
    !!original.data &&
    !original.data.trashed;
  const schedule = items.find(
    (item) => record(item.args) && item.args.operation === "schedule",
  );
  const moreOutcomes = items.find(
    (item) => record(item.args) && typeof item.args.outcome_cursor === "string",
  );
  const next = items.find(
    (item) =>
      item.type === "command" &&
      item.id === "journal" &&
      record(item.args) &&
      item.args.cursor,
  );
  return (
    <div className={styles.review}>
      {noteId && <Link href={decisionsPath}>{t("decisions.all")}</Link>}
      <h1 className="page-title">
        {reviewable
          ? original.data?.title || t("vocabulary.untitled_note")
          : t("plugin.decision-journal.views.journal.title")}
      </h1>
      {!allowed ? (
        <p>{t("decisions.manager_unavailable")}</p>
      ) : (
        <>
          {!online && <p>{t("decisions.reconnect")}</p>}
          {!noteId && (
            <>
              {!upgraded && <DecisionCapture plugin={plugin} />}
              {upgraded && (
                <>
                  <DraftDecision />
                  <form
                    className={styles.row}
                    onSubmit={(event) => {
                      event.preventDefault();
                      criteria();
                      setQuery(search.trim());
                    }}
                  >
                    <label>
                      {t("decisions.search")}{" "}
                      <input
                        className="field"
                        value={search}
                        onChange={(event) => setSearch(event.target.value)}
                        maxLength={200}
                      />
                    </label>
                    <button type="submit">{t("app.search")}</button>
                    <label>
                      {t("decisions.status_filter")}
                      <select
                        value={status}
                        onChange={(event) => {
                          criteria();
                          setStatus(event.target.value);
                        }}
                      >
                        <option value="">{t("settingsplugins.all")}</option>
                        {["open", "reviewed", "closed"].map((value) => (
                          <option key={value} value={value}>
                            {t(`decisions.status.${value}`)}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      <input
                        type="checkbox"
                        checked={due}
                        onChange={(event) => {
                          criteria();
                          setDue(event.target.checked);
                        }}
                      />{" "}
                      {t("decisions.only_due")}
                    </label>
                  </form>
                </>
              )}
            </>
          )}
          {result.isPending && <p role="status">{t("plugins.running")}</p>}
          {result.isError && <p role="alert">{result.error.message}</p>}
          {noteId ? (
            reviewable && original.data ? (
              <article>
                <p className={styles.facts}>{decisionStatus(summary)}</p>
                <NoteBody note={original.data} preview />
                <DecisionRationale note={original.data} />
                <div className={styles.row}>
                  {upgraded && assist ? (
                    <button
                      className="primary"
                      disabled={!online}
                      onClick={(event) => {
                        dialogOpener.current = event.currentTarget;
                        setDraft(true);
                      }}
                    >
                      {t("decisions.what_happened")}
                    </button>
                  ) : (
                    <DecisionCommand
                      id="review-decision"
                      label={t("decisions.record_outcome")}
                      args={{ note_id: noteId }}
                      disabled={!online}
                      primary
                    />
                  )}
                  {upgraded && assist && (
                    <DecisionCommand
                      id="review-decision"
                      label={t("decisions.manual_outcome")}
                      args={{ note_id: noteId }}
                      disabled={!online}
                    />
                  )}
                </div>
                <h2>{t("decisions.review_history")}</h2>
                {outcomes.length ? (
                  <ul>
                    {outcomes.map(
                      (item) =>
                        typeof item.id === "string" && (
                          <li key={item.id}>
                            <NoteReference
                              id={item.id}
                              reason={
                                typeof item.reason === "string"
                                  ? decisionStatus(item.reason)
                                  : undefined
                              }
                            />
                          </li>
                        ),
                    )}
                  </ul>
                ) : (
                  <p>{t("decisions.no_outcomes")}</p>
                )}
                {outcomeCursor && (
                  <button onClick={() => setOutcomeCursor(undefined)}>
                    {t("decisions.first_page")}
                  </button>
                )}
                {moreOutcomes &&
                  record(moreOutcomes.args) &&
                  typeof moreOutcomes.args.outcome_cursor === "string" && (
                    <button
                      onClick={() =>
                        setOutcomeCursor(
                          String((moreOutcomes.args as Args).outcome_cursor),
                        )
                      }
                    >
                      {t("decisions.more_outcomes")}
                    </button>
                  )}
                <div className={styles.row}>
                  <button
                    onClick={() =>
                      navigate(`/n/${encodeURIComponent(noteId)}`, {
                        state: { edit: true },
                      })
                    }
                  >
                    {t("decisions.edit_original")}
                  </button>
                  <Link href={`/n/${encodeURIComponent(noteId)}`}>
                    {t("decisions.open_original")}
                  </Link>
                  {schedule && (
                    <DecisionCommand
                      id="journal"
                      label={t("decisions.review_settings")}
                      args={schedule.args as Args}
                      disabled={!online}
                    />
                  )}
                  <button
                    disabled={!online}
                    onClick={(event) => {
                      dialogOpener.current = event.currentTarget;
                      setTrash(true);
                    }}
                  >
                    {t("decisions.trash")}
                  </button>
                </div>
              </article>
            ) : (
              result.isSuccess && <p>{summary}</p>
            )
          ) : (
            <>
              {items[0]?.type === "text" && <p>{String(items[0].text)}</p>}
              <ul>
                {refs.map((item) =>
                  typeof item.id === "string" ? (
                    <li key={item.id}>
                      <DecisionLink
                        id={item.id}
                        reason={
                          typeof item.reason === "string"
                            ? decisionStatus(item.reason)
                            : undefined
                        }
                      />
                    </li>
                  ) : null,
                )}
              </ul>
              {cursor && (
                <button onClick={criteria}>{t("decisions.first_page")}</button>
              )}
              {next &&
                record(next.args) &&
                typeof next.args.cursor === "string" && (
                  <button
                    onClick={() =>
                      setCursor(String((next.args as Args).cursor))
                    }
                  >
                    {t("decisions.more_decisions")}
                  </button>
                )}
            </>
          )}
        </>
      )}
      {draft && (
        <Dialog
          title={t(
            noteId ? "decisions.what_happened" : "decisions.assisted_draft",
          )}
          onClose={() => setDraft(false)}
          opener={dialogOpener.current}
        >
          <DraftDecision noteId={noteId} />
        </Dialog>
      )}
      {trash && (
        <Dialog
          title={t("decisions.trash")}
          onClose={() => setTrash(false)}
          opener={dialogOpener.current}
        >
          <p>{t("decisions.trash_explanation")}</p>
          {remove.isError && <p role="alert">{remove.error.message}</p>}
          <button
            className="danger"
            onClick={() => remove.mutate()}
            disabled={!online || remove.isPending}
          >
            {t("decisions.trash")}
          </button>
        </Dialog>
      )}
    </div>
  );
}
