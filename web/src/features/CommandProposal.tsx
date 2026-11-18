import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { t, useLanguage } from "../lib/i18n";
import { invalidateContentFor } from "../lib/query";
import type { Component } from "../lib/types";
import { Dialog } from "./Dialog";
import { SuggestionPreview, suggestionTitle } from "./SuggestionPreview";
import { useSuggestionFeedback } from "./suggestionFeedback";
import { VocabularyOutput } from "./Vocabulary";
import styles from "./Feature.module.css";

export default function CommandProposal({
  id,
  plugin,
  noteId,
  onClose,
  onApplied,
  inline = false,
  diff,
}: {
  id: string;
  plugin: string;
  noteId: string;
  onClose?: () => void;
  onApplied: () => void;
  inline?: boolean;
  diff?: Extract<Component, { type: "diff" }>;
}) {
  useLanguage();
  const client = useQueryClient();
  const { acknowledge } = useSuggestionFeedback();
  const [expanded, setExpanded] = useState(false);
  const comparisonRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (expanded) comparisonRef.current?.focus();
  }, [expanded]);
  const lookup = useQuery({
    queryKey: ["suggestions", "command", noteId, plugin, id],
    queryFn: async () => {
      const { suggestions } = await api.suggestions("note", noteId);
      return (
        suggestions.find(
          (s) =>
            s.id === id &&
            s.plugin === plugin &&
            s.note_id === noteId &&
            s.status === "pending",
        ) ?? null
      );
    },
    retry: false,
    refetchOnMount: "always",
  });
  const proposal = lookup.isSuccess && !lookup.isFetching ? lookup.data : null;
  const note = useQuery({
    queryKey: ["note", noteId],
    queryFn: () => api.note(noteId),
    enabled: proposal?.note_version != null,
    refetchOnMount: "always",
  });
  const ready =
    !!proposal &&
    (!inline ||
      (proposal.action === "replace_body" && proposal.note_version != null)) &&
    (proposal.note_version == null ||
      note.data?.version === proposal.note_version) &&
    (!diff || diff.base_version === proposal.note_version);
  const accept = useMutation({
    mutationFn: () => api.decideSuggestion(id, "accept"),
    onSuccess: (result) => {
      if (proposal) acknowledge(result, proposal);
      onApplied();
      void invalidateContentFor(client);
    },
    onError: () => void lookup.refetch(),
  });
  const fullReview = !inline || expanded;
  const reviewing = fullReview || !!diff;
  const footer = (
    <>
      {onClose && <button onClick={onClose}>{t("slots.back")}</button>}
      {reviewing ? (
        <button
          className="primary"
          disabled={!ready || note.isFetching || accept.isPending}
          onClick={() => accept.mutate()}
        >
          {t(inline ? "quiet.apply_changes" : "review.apply")}
        </button>
      ) : (
        <button
          className="primary"
          disabled={!ready || note.isFetching}
          onClick={() => setExpanded(true)}
        >
          {t("quiet.review_changes")}
        </button>
      )}
    </>
  );
  const comparison = (
    <>
      {lookup.isFetching || (proposal && note.isPending && note.isFetching) ? (
        <p role="status">{t("timeline.loading_notes")}</p>
      ) : !ready ? (
        <p role="alert">
          {lookup.error?.message ??
            note.error?.message ??
            t("commandproposal.unavailable")}
        </p>
      ) : diff ? (
        <VocabularyOutput value={diff} />
      ) : fullReview && proposal ? (
        <SuggestionPreview suggestion={proposal} baseNote={note.data} />
      ) : (
        <p>{t("quiet.full_comparison")}</p>
      )}
      {accept.error && <p role="alert">{accept.error.message}</p>}
    </>
  );
  if (inline)
    return (
      <div className={styles.result}>
        <div
          className={styles.commandComparison}
          ref={comparisonRef}
          tabIndex={reviewing ? 0 : undefined}
        >
          {comparison}
        </div>
        <footer>{footer}</footer>
      </div>
    );
  return (
    <Dialog
      title={
        proposal
          ? suggestionTitle(proposal)
          : t("suggestionpreview.review_suggestion")
      }
      onClose={onClose ?? onApplied}
      footer={footer}
    >
      {comparison}
    </Dialog>
  );
}
