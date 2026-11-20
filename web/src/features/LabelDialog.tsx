import { useId, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useLocation } from "wouter";
import { useToast } from "../components/core/Toast";
import { api, fold, queryClient, useLabels, useRenameLabel } from "../lib";
import { t, useLanguage } from "../lib/i18n";
import { Dialog } from "./Dialog";

const notesCount = (count: number) => t("count.note", { count });

/** Mirrors the server's label rule, so the dialog can say what is wrong before sending. */
function labelProblem(path: string, current: string) {
  const segments = path.split("/");
  if (!path) return t("timeline.type_a_label_name");
  if (fold(path) === fold(current))
    return t("timeline.that_is_already_this_label_s_name");
  if (
    path.length > 128 ||
    segments.length > 5 ||
    /^\d+$/.test(segments[0]) ||
    segments.some((segment) => !/^[\p{L}\p{N}_-]+$/u.test(segment))
  )
    return t("timeline.use_letters_digits_and_with_between_up_to");
  return null;
}

export default function LabelDialog({
  label,
  merge,
  onClose,
  onRename,
}: {
  label: string;
  merge: boolean;
  onClose: () => void;
  onRename: () => void;
}) {
  useLanguage();
  const [, navigate] = useLocation();
  const toast = useToast();
  const labels = useLabels();
  const rename = useRenameLabel();
  const [target, setTarget] = useState(merge ? "" : label);
  const [submitted, setSubmitted] = useState(false);
  const path = target.trim().replace(/^#/, "");
  const problem = labelProblem(path, label);
  const exists = (labels.data?.labels ?? []).some(
    (item) =>
      fold(item.path) === fold(path) ||
      fold(item.path).startsWith(fold(path) + "/"),
  );
  const children = (labels.data?.labels ?? []).some((item) =>
    fold(item.path).startsWith(fold(label) + "/"),
  );
  const impact = useQuery({
    queryKey: ["label-impact", label, path],
    queryFn: () =>
      api.request<{ notes: number }>("/api/v1/labels/impact", {
        method: "POST",
        body: { old: label, new: path },
      }),
    enabled: !problem,
    retry: false,
  });
  const count = impact.data?.notes;
  const formId = useId();
  async function submit() {
    setSubmitted(true);
    if (problem || count === undefined || !count) return;
    const result = await rename.mutateAsync({ from: label, to: path });
    onRename();
    onClose();
    navigate("/l/" + path, { replace: true, state: history.state });
    toast.show({
      message: t(exists ? "timeline.merged_count" : "timeline.renamed_count", {
        count: result.rewritten,
      }),
      // Renaming back is exact only when the new name was unused before.
      ...(exists
        ? {}
        : {
            actionLabel: t("settingsplugins.undo"),
            onAction: () =>
              void api.renameLabel(path, label).then(() => {
                void queryClient.invalidateQueries();
                navigate("/l/" + label, {
                  replace: true,
                  state: history.state,
                });
              }),
          }),
    });
  }
  const error =
    (submitted && problem) ||
    (!problem && impact.error?.message) ||
    rename.error?.message;
  return (
    <Dialog
      title={
        merge
          ? t("timeline.merge_value1_into", { value1: label })
          : t("timeline.rename_value1", { value1: label })
      }
      onClose={onClose}
      footer={
        <>
          <button onClick={onClose}>{t("profilepanel.cancel")}</button>
          <button
            className="primary"
            type="submit"
            form={formId}
            disabled={rename.isPending || (impact.isPending && !problem)}
          >
            {exists && !problem
              ? t("timeline.merge_into_value1", { value1: path })
              : merge
                ? t("timeline.merge")
                : t("timeline.rename")}
          </button>
        </>
      }
    >
      <form
        id={formId}
        className="stack"
        onSubmit={(event) => {
          event.preventDefault();
          void submit().catch(() => {});
        }}
      >
        <label className="stack">
          {merge ? t("timeline.existing_label") : t("timeline.new_name")}
          <input
            className="field"
            value={target}
            autoFocus
            list={merge ? formId + "-labels" : undefined}
            aria-invalid={!!error || undefined}
            aria-describedby={formId + "-help"}
            onChange={(event) => setTarget(event.target.value)}
          />
          {merge && (
            <datalist id={formId + "-labels"}>
              {labels.data?.labels
                .filter((item) => fold(item.path) !== fold(label))
                .map((item) => (
                  <option key={item.path} value={item.path} />
                ))}
            </datalist>
          )}
        </label>
        <p
          id={formId + "-help"}
          className={error ? "error" : "muted"}
          role={error ? "alert" : undefined}
        >
          {error ||
            (problem
              ? merge
                ? t("timeline.choose_the_label_these_notes_should_move_to")
                : t("timeline.rename_changes_the_label_in_every_note_that")
              : count === undefined
                ? t("timeline.counting_notes")
                : !count
                  ? t("timeline.no_notes_use_value1_yet", { value1: label })
                  : exists
                    ? t("timeline.value1_already_exists_merge_value2_into_it", {
                        value1: path,
                        value2: notesCount(count),
                      })
                    : t("timeline.renames_value1_in_value2", {
                        value1: label,
                        value2: notesCount(count),
                      }) +
                      (children
                        ? t("timeline.its_child_labels_are_renamed_too")
                        : ""))}
        </p>
      </form>
    </Dialog>
  );
}
