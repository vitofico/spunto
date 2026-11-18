import { useEffect } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useLocation } from "wouter";
import { useToast } from "../components/core/Toast";
import { api } from "../lib/api";
import { safeError } from "../lib/errors";
import { t } from "../lib/i18n";
import { invalidateContentFor } from "../lib/query";
import type { Suggestion, SuggestionDecision } from "../lib/types";
import { appliedSuggestionTitle } from "./suggestionTitle";

export function useSuggestionFeedback() {
  const toast = useToast();
  const client = useQueryClient();
  const [, navigate] = useLocation();
  const undo = useMutation({
    mutationFn: (s: Suggestion) => api.decideSuggestion(s.id, "undo"),
    onSuccess: (result) => {
      void invalidateContentFor(client);
      if (result.applied) toast.show({ message: t("quiet.undone") });
    },
    onError: (error, s) => {
      if (safeError(error).code !== "suggestion_undo_stale" || !s.note_id) {
        toast.show({ message: error.message });
        return;
      }
      const noteId = s.note_id;
      void invalidateContentFor(client);
      toast.show({
        message: t("quiet.undo_stale"),
        actionLabel: t("quiet.open_history"),
        onAction: () => navigate(`/n/${encodeURIComponent(noteId)}?history=1`),
      });
    },
  });
  return {
    undo,
    acknowledge: (result: SuggestionDecision | undefined, s: Suggestion) => {
      if (!result?.applied || s.action === "open") return;
      const receipt = result.suggestion?.receipt;
      if (!receipt && s.action !== "merge_notes" && s.action !== "label_change")
        return;
      toast.show({
        message: appliedSuggestionTitle(s),
        actionLabel: receipt?.undoable ? t("settingsplugins.undo") : undefined,
        onAction: receipt?.undoable ? () => undo.mutate(s) : undefined,
      });
    },
  };
}

export function useCommandProposals(noteId: string | undefined, ids: string[]) {
  const client = useQueryClient();
  useEffect(() => {
    if (!noteId || !ids.length) return;
    const key = ["command-proposals", noteId];
    client.setQueryData<string[]>(key, (prior) => [...(prior ?? []), ...ids]);
    return () => {
      client.setQueryData<string[]>(key, (prior) => {
        const next = [...(prior ?? [])];
        for (const id of ids) {
          const index = next.indexOf(id);
          if (index >= 0) next.splice(index, 1);
        }
        return next;
      });
    };
  }, [client, ids, noteId]);
}
