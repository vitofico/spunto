import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { t, useLanguage } from "../lib/i18n";
import { invalidateContentFor, useNote, useWorkspaceRole } from "../lib/query";
import type { Note } from "../lib/types";
import { useToast } from "../components/core/Toast";
import { NotePicker } from "./NotePicker";
import { outputText } from "./vocabularyData";

export function LinkPicker({
  sourceId,
  onClose,
}: {
  sourceId: string;
  onClose?: () => void;
}) {
  useLanguage();
  const role = useWorkspaceRole();
  const source = useNote(sourceId);
  const [baseVersion, setBaseVersion] = useState(source.data?.version);
  const version = source.data?.version;
  useEffect(() => {
    setBaseVersion((initial) => initial ?? version);
  }, [version]);
  const client = useQueryClient();
  const toast = useToast();
  const insert = useMutation({
    mutationFn: async ({ target, base }: { target: Note; base: number }) => {
      if (document.querySelector("[data-link-draft-blocked]"))
        throw new Error(t("notepicker.unsaved"));
      const result = await api.command("links", "link", {
        note_id: sourceId,
        target_id: target.id,
        base_version: base,
        target_version: target.version,
        insert: true,
      });
      const message = outputText(result);
      if (message === "Link inserted.") return t("notepicker.inserted");
      if (message === "This note already links to the selected note.")
        return t("notepicker.already_linked");
      throw new Error(message || t("notepicker.failed"));
    },
    onSuccess: (message) => {
      void invalidateContentFor(client);
      toast.show({ message });
      onClose?.();
    },
    onError: () => {
      void client.invalidateQueries({ queryKey: ["note", sourceId] });
      void client.invalidateQueries({ queryKey: ["note-picker"] });
    },
  });
  if (source.isPending || (source.data && baseVersion === undefined))
    return <p role="status">{t("timeline.loading_notes")}</p>;
  if (
    role === "viewer" ||
    !source.data ||
    source.data.trashed ||
    baseVersion === undefined
  )
    return (
      <p role="alert">
        {source.error?.message ?? t("vocabulary.note_unavailable")}
      </p>
    );
  return (
    <>
      <NotePicker
        exclude={[sourceId]}
        action={t("notepicker.insert")}
        busy={insert.isPending}
        onSelect={(target) => insert.mutate({ target, base: baseVersion })}
        onCancel={onClose}
      />
      {insert.error && <p role="alert">{insert.error.message}</p>}
    </>
  );
}
