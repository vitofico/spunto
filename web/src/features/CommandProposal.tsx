import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { t, useLanguage } from "../lib/i18n";
import { invalidateContentFor } from "../lib/query";
import { Dialog } from "./Dialog";
import { SuggestionPreview, suggestionTitle } from "./SuggestionPreview";

export default function CommandProposal({
  id,
  plugin,
  noteId,
  onClose,
  onApplied,
}: {
  id: string;
  plugin: string;
  noteId: string;
  onClose: () => void;
  onApplied: () => void;
}) {
  useLanguage();
  const client = useQueryClient();
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
  const accept = useMutation({
    mutationFn: () => api.decideSuggestion(id, "accept"),
    onSuccess: () => {
      void invalidateContentFor(client);
      onApplied();
    },
  });
  return (
    <Dialog
      title={
        proposal
          ? suggestionTitle(proposal)
          : t("suggestionpreview.review_suggestion")
      }
      onClose={onClose}
      footer={
        <>
          <button onClick={onClose}>{t("profilepanel.cancel")}</button>
          <button
            className="primary"
            disabled={!proposal || accept.isPending}
            onClick={() => accept.mutate()}
          >
            {t("review.apply")}
          </button>
        </>
      }
    >
      {proposal ? (
        <SuggestionPreview suggestion={proposal} />
      ) : lookup.isFetching ? (
        <p role="status">{t("timeline.loading_notes")}</p>
      ) : (
        <p role="alert">
          {lookup.error?.message ?? t("commandproposal.unavailable")}
        </p>
      )}
      {accept.error && <p role="alert">{accept.error.message}</p>}
    </Dialog>
  );
}
