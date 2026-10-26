import { t, useLanguage } from "../lib/i18n";
import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useQuery } from "@tanstack/react-query";
import { useLocation } from "wouter";
import {
  api,
  fold,
  queryClient,
  useNotes,
  useDraftEditor,
  useDeleteLabel,
  useManifest,
  useRenameLabel,
  useLabels,
  labelTokens,
  useSyncState,
  useWorkspaceRole,
} from "../lib";
import { formatShortcut } from "../lib/platform";
import { useDocumentTitle } from "../lib/useDocumentTitle";
import { activePlugins, ReviewLine, DailySlip } from "../features/Slots";
import { Dialog } from "../features/Dialog";
import { Editor } from "../components/core/Editor";
import { Icon } from "../components/core/Icon";
import { MoreMenu } from "../components/core/Menu";
import { NotFound } from "../components/core/NotFound";
import { useConfirm } from "../components/core/ConfirmDialog";
import { useToast } from "../components/core/Toast";
import { groupedNotes, NoteCard } from "../components/core/NoteCard";

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

function LabelMenu({
  disabled,
  onChoose,
}: {
  disabled: boolean;
  onChoose: (action: "rename" | "merge" | "remove") => void;
}) {
  useLanguage();
  return (
    <MoreMenu
      label={t("timeline.label_actions")}
      disabled={disabled}
      items={[
        { label: t("timeline.rename"), onSelect: () => onChoose("rename") },
        { label: t("timeline.merge_into"), onSelect: () => onChoose("merge") },
        {
          label: t("timeline.remove_label"),
          onSelect: () => onChoose("remove"),
          danger: true,
        },
      ]}
    />
  );
}

// Set when a rename or merge lands on the new label, so focus returns to its title.
let focusLabelTitle = false;
function LabelDialog({
  label,
  merge,
  onClose,
}: {
  label: string;
  merge: boolean;
  onClose: () => void;
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
    focusLabelTitle = true;
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

function Empty({
  heading: Heading,
  title,
  children,
}: {
  heading: "h1" | "h2";
  title: string;
  children?: ReactNode;
}) {
  useLanguage();
  return (
    <div className="empty">
      <Heading>{title}</Heading>
      {children}
    </div>
  );
}

export default function Timeline({
  view = "timeline",
  label,
}: {
  view?: string;
  label?: string;
}) {
  const language = useLanguage();
  const canEdit = useWorkspaceRole() !== "viewer";
  const [, navigate] = useLocation();
  const toast = useToast();
  const [confirm, confirmDialog] = useConfirm();
  const manifest = useManifest();
  const plugins = activePlugins(manifest.data?.plugins);
  const pluginView = view.includes("/")
    ? plugins
        .find((plugin) => plugin.id === view.split("/")[0])
        ?.contributes?.views?.find((item) => item.id === view.split("/")[1])
    : undefined;
  const notes = useNotes({ view, label, limit: 50 });
  const draft = useDraftEditor({
    draftKey: label ? "composer:" + label : "composer",
    enabled: canEdit,
  });
  const { online } = useSyncState();
  const [addLabel, setAddLabel] = useState(true);
  const labels = useLabels();
  const remove = useDeleteLabel();
  const [action, setAction] = useState<"rename" | "merge" | null>(null);
  const [emptyBusy, setEmptyBusy] = useState(false);
  const [emptyError, setEmptyError] = useState<string | null>(null);
  const sentinel = useRef<HTMLDivElement>(null);
  const all = useMemo(
    () => notes.data?.pages.flatMap((p) => p.notes) ?? [],
    [notes.data],
  );
  const today = new Date().toDateString();
  const groups = useMemo(() => groupedNotes(all), [all, today, language]);
  const count =
    labels.data?.labels.find((l) => fold(l.path) === fold(label ?? ""))
      ?.count ?? all.length;
  const title = label
    ? "#" + label
    : view === "archive"
      ? t("app.archive")
      : view === "trash"
        ? t("app.trash")
        : pluginView?.title;
  useDocumentTitle(title);
  const EmptyHeading = label || view !== "timeline" ? "h2" : "h1";
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    if (!focusLabelTitle) return;
    focusLabelTitle = false;
    heading.current?.focus();
  }, []);
  useEffect(() => {
    if (!sentinel.current || !notes.hasNextPage) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && !notes.isFetchingNextPage)
          void notes.fetchNextPage();
      },
      { rootMargin: "400px" },
    );
    observer.observe(sentinel.current);
    return () => observer.disconnect();
  }, [notes.hasNextPage, notes.isFetchingNextPage, notes.fetchNextPage]);
  async function save() {
    // The view's label is added once, on save, unless the owner removed the chip.
    if (
      label &&
      addLabel &&
      draft.body.trim() &&
      !labelTokens(draft.body).some((token) => fold(token.path) === fold(label))
    )
      draft.setBody(draft.body.trimEnd() + " #" + label);
    await draft.save();
    setAddLabel(true);
    document.getElementById("composer")?.focus();
  }
  async function removeLabel() {
    if (!label) return;
    const impact = await api
      .request<{ notes: number }>("/api/v1/labels/impact", {
        method: "POST",
        body: { old: label, new: null },
      })
      .catch(() => null);
    const ok = await confirm({
      title: t("timeline.remove_value1", { value1: label }),
      body:
        (impact
          ? t("timeline.takes_value1_out_of_value2", {
              value1: label,
              value2: notesCount(impact.notes),
            })
          : "") + t("timeline.the_note_text_stays_child_labels_are_removed"),
      confirmLabel: t("timeline.remove_label"),
      danger: true,
    });
    if (!ok) return;
    const result = await remove.mutateAsync(label);
    navigate("/");
    toast.show({
      message: t("timeline.removed_from_value1", {
        value1: notesCount(result.rewritten),
      }),
    });
  }
  async function emptyTrash() {
    setEmptyBusy(true);
    setEmptyError(null);
    try {
      let total = 0;
      let cursor: string | undefined;
      do {
        const page = await api.notes({ view: "trash", limit: 500, cursor });
        total += page.notes.length;
        cursor = page.next_cursor ?? undefined;
      } while (cursor);
      setEmptyBusy(false);
      if (
        await confirm({
          title: t("timeline.delete_value1_forever", {
            value1: notesCount(total),
          }),
          body: t("timeline.this_can_t_be_undone"),
          confirmLabel: t("note.delete_forever"),
          danger: true,
        })
      ) {
        await api.emptyTrash();
        await queryClient.invalidateQueries({ queryKey: ["notes"] });
      }
    } catch (error) {
      setEmptyError(
        error instanceof Error
          ? error.message
          : t("timeline.could_not_empty_trash_try_again"),
      );
    } finally {
      setEmptyBusy(false);
    }
  }
  if (view.includes("/") && manifest.data && !pluginView)
    return <NotFound title={t("timeline.this_view_isn_t_here")} />;
  const suggested = plugins.some((plugin) => plugin.id === "label-suggestions");
  return (
    <>
      {label ? (
        <div className="label-title">
          <h1 ref={heading} className="page-title" tabIndex={-1}>
            #{label} <span className="muted">{count}</span>
          </h1>
          {canEdit && (
            <LabelMenu
              disabled={remove.isPending}
              onChoose={(choice) =>
                choice === "remove"
                  ? void removeLabel().catch((error: Error) =>
                      toast.show({ message: error.message }),
                    )
                  : setAction(choice)
              }
            />
          )}
        </div>
      ) : (
        title && <h1 className="page-title">{title}</h1>
      )}
      {action && label && (
        <LabelDialog
          label={label}
          merge={action === "merge"}
          onClose={() => setAction(null)}
        />
      )}
      {confirmDialog}
      {view === "timeline" && canEdit && (
        <>
          {label && addLabel && (
            <p className="composer-label">
              <span className="chip">
                <span>#{label}</span>
                <button
                  aria-label={t("timeline.don_t_add_value1_to_this_note", {
                    value1: label,
                  })}
                  onClick={() => {
                    setAddLabel(false);
                    document.getElementById("composer")?.focus();
                  }}
                >
                  <Icon name="close" />
                </button>
              </span>
              <span className="muted">{t("timeline.added_when_you_save")}</span>
            </p>
          )}
          <Editor
            body={draft.body}
            setBody={draft.setBody}
            onSave={() => void save()}
            upload={draft.uploadAttachment}
            autoFocus={false}
            compact
            // Offline, the banner above already says the server can't be reached.
            error={
              online || draft.error?.status !== 0
                ? draft.error?.message
                : undefined
            }
            busy={draft.state === "saving"}
          />
          {draft.storageFailed && (
            <p className="error" role="alert">
              {t("note.device_storage_is_full_or_unavailable_keep_this")}
            </p>
          )}
          <ReviewLine />
          <DailySlip />
        </>
      )}
      {view === "trash" && all.length > 0 && (
        <p className="muted">
          {t("timeline.notes_here_are_deleted_for_good_after_30")}
        </p>
      )}
      {notes.isPending && (
        <p className="page-status" role="status">
          {t("timeline.loading_notes")}
        </p>
      )}
      {notes.isError && (
        <p className="error" role="alert">
          {notes.error.message}{" "}
          <button className="button" onClick={() => void notes.refetch()}>
            {t("app.try_again")}
          </button>
        </p>
      )}
      {!notes.isPending &&
        !all.length &&
        !notes.isError &&
        (label ? (
          <Empty
            heading={EmptyHeading}
            title={t("timeline.nothing_under_value1_yet", { value1: label })}
          >
            {canEdit && <p>{t("timeline.automatic_label", { label })}</p>}
          </Empty>
        ) : view === "archive" ? (
          <Empty heading={EmptyHeading} title={t("timeline.nothing_archived")}>
            <p>{t("timeline.archive_keeps_a_note_out_of_the_timeline")}</p>
          </Empty>
        ) : view === "trash" ? (
          <Empty heading={EmptyHeading} title={t("timeline.trash_is_empty")}>
            <p>{t("timeline.deleted_notes_stay_here_for_30_days")}</p>
          </Empty>
        ) : view === "timeline" && canEdit ? (
          <Empty
            heading={EmptyHeading}
            title={t("timeline.a_quiet_place_for_loose_ideas")}
          >
            <p>
              {t(
                suggested
                  ? "timeline.empty_help_suggestions"
                  : "timeline.empty_help",
                { shortcut: formatShortcut("mod+enter") },
              )}
            </p>
          </Empty>
        ) : (
          <Empty
            heading={EmptyHeading}
            title={t("timeline.no_notes_here_yet")}
          />
        ))}
      <section aria-label={t("review.notes")} aria-busy={notes.isPending}>
        {groups.map((group) => (
          <section key={group.title} aria-label={group.title}>
            <h2 className="overline day">{group.title}</h2>
            <div className="note-pane">
              {group.notes.map((note) => (
                <NoteCard key={note.id} note={note} />
              ))}
            </div>
          </section>
        ))}
      </section>
      <div ref={sentinel} />
      {notes.hasNextPage && (
        <button
          className="button load-more"
          disabled={notes.isFetchingNextPage}
          onClick={() => void notes.fetchNextPage()}
        >
          {notes.isFetchingNextPage
            ? t("timeline.loading_more_notes")
            : t("timeline.load_more_notes")}
        </button>
      )}
      {view === "trash" && canEdit && all.length > 0 && (
        <button
          className="button danger"
          disabled={emptyBusy}
          onClick={() => void emptyTrash()}
        >
          {emptyBusy ? t("timeline.counting_notes") : t("timeline.empty_trash")}
        </button>
      )}
      {emptyError && (
        <p className="error" role="alert">
          {emptyError}
        </p>
      )}
    </>
  );
}
