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
import { NotFound } from "../components/core/NotFound";
import { useConfirm } from "../components/core/ConfirmDialog";
import { useToast } from "../components/core/Toast";
import { groupedNotes, NoteCard } from "../components/core/NoteCard";

const notesCount = (count: number) =>
  `${count} ${count === 1 ? "note" : "notes"}`;
/** Mirrors the server's label rule, so the dialog can say what is wrong before sending. */
function labelProblem(path: string, current: string) {
  const segments = path.split("/");
  if (!path) return "Type a label name.";
  if (fold(path) === fold(current)) return "That is already this label’s name.";
  if (
    path.length > 128 ||
    segments.length > 5 ||
    /^\d+$/.test(segments[0]) ||
    segments.some((segment) => !/^[\p{L}\p{N}_-]+$/u.test(segment))
  )
    return "Use letters, digits, - and _, with / between up to 5 levels.";
  return null;
}

function LabelMenu({
  disabled,
  onChoose,
}: {
  disabled: boolean;
  onChoose: (action: "rename" | "merge" | "remove") => void;
}) {
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const id = useId();
  useEffect(() => {
    if (!open) return;
    menu.current?.querySelector<HTMLElement>("[role=menuitem]")?.focus();
    const outside = (event: PointerEvent) => {
      if (!menu.current?.parentElement?.contains(event.target as Node))
        setOpen(false);
    };
    document.addEventListener("pointerdown", outside);
    return () => document.removeEventListener("pointerdown", outside);
  }, [open]);
  const items: ["rename" | "merge" | "remove", string][] = [
    ["rename", "Rename"],
    ["merge", "Merge into…"],
    ["remove", "Remove label"],
  ];
  function choose(action: "rename" | "merge" | "remove") {
    setOpen(false);
    // The dialog this opens returns focus to what was focused when it opened.
    trigger.current?.focus();
    onChoose(action);
  }
  return (
    <div className="menu-anchor">
      <button
        ref={trigger}
        className="button icon-button"
        aria-label="Label actions"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        disabled={disabled}
        onClick={() => setOpen(!open)}
      >
        <Icon name="more" />
      </button>
      {open && (
        <div
          ref={menu}
          id={id}
          role="menu"
          aria-label="Label actions"
          className="menu"
          onKeyDown={(event) => {
            const entries = [
              ...(menu.current?.querySelectorAll<HTMLElement>(
                "[role=menuitem]",
              ) ?? []),
            ];
            const at = entries.indexOf(document.activeElement as HTMLElement);
            if (event.key === "ArrowDown" || event.key === "ArrowUp") {
              event.preventDefault();
              const step = event.key === "ArrowDown" ? 1 : -1;
              entries[(at + step + entries.length) % entries.length]?.focus();
            } else if (event.key === "Escape" || event.key === "Tab") {
              if (event.key === "Escape") event.preventDefault();
              setOpen(false);
              trigger.current?.focus();
            }
          }}
        >
          {items.map(([action, text]) => (
            <button
              key={action}
              role="menuitem"
              tabIndex={-1}
              className={action === "remove" ? "danger" : undefined}
              onClick={() => choose(action)}
            >
              {text}
            </button>
          ))}
        </div>
      )}
    </div>
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
      message: `${exists ? "Merged" : "Renamed"} in ${notesCount(result.rewritten)}`,
      // Renaming back is exact only when the new name was unused before.
      ...(exists
        ? {}
        : {
            actionLabel: "Undo",
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
      title={merge ? `Merge #${label} into…` : `Rename #${label}`}
      onClose={onClose}
      footer={
        <>
          <button onClick={onClose}>Cancel</button>
          <button
            className="primary"
            type="submit"
            form={formId}
            disabled={rename.isPending || (impact.isPending && !problem)}
          >
            {exists && !problem
              ? `Merge into #${path}`
              : merge
                ? "Merge"
                : "Rename"}
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
          {merge ? "Existing label" : "New name"}
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
                ? "Choose the label these notes should move to."
                : "Rename changes the label in every note that has it."
              : count === undefined
                ? "Counting notes…"
                : !count
                  ? `No notes use #${label} yet.`
                  : exists
                    ? `#${path} already exists. Merge ${notesCount(count)} into it?`
                    : `Renames #${label} in ${notesCount(count)}.` +
                      (children ? " Its child labels are renamed too." : ""))}
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
  focusComposer = true,
}: {
  view?: string;
  label?: string;
  focusComposer?: boolean;
}) {
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
  const groups = useMemo(() => groupedNotes(all), [all, today]);
  const count =
    labels.data?.labels.find((l) => fold(l.path) === fold(label ?? ""))
      ?.count ?? all.length;
  const title = label
    ? "#" + label
    : view === "archive"
      ? "Archive"
      : view === "trash"
        ? "Trash"
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
      title: `Remove #${label}?`,
      body:
        (impact ? `Takes #${label} out of ${notesCount(impact.notes)}. ` : "") +
        "The note text stays; child labels are removed too.",
      confirmLabel: "Remove label",
      danger: true,
    });
    if (!ok) return;
    const result = await remove.mutateAsync(label);
    navigate("/");
    toast.show({ message: `Removed from ${notesCount(result.rewritten)}` });
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
          title: `Delete ${notesCount(total)} forever?`,
          body: "This can’t be undone.",
          confirmLabel: "Delete forever",
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
          : "Could not empty trash. Try again.",
      );
    } finally {
      setEmptyBusy(false);
    }
  }
  if (view.includes("/") && manifest.data && !pluginView)
    return <NotFound title="This view isn’t here." />;
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
                  aria-label={`Don’t add #${label} to this note`}
                  onClick={() => {
                    setAddLabel(false);
                    document.getElementById("composer")?.focus();
                  }}
                >
                  <Icon name="close" />
                </button>
              </span>
              <span className="muted">added when you save</span>
            </p>
          )}
          <Editor
            body={draft.body}
            setBody={draft.setBody}
            onSave={() => void save()}
            upload={draft.uploadAttachment}
            autoFocus={focusComposer}
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
              Device storage is full or unavailable; keep this tab open until
              saved.
            </p>
          )}
          <ReviewLine />
          <DailySlip />
        </>
      )}
      {view === "trash" && all.length > 0 && (
        <p className="muted">Notes here are deleted for good after 30 days.</p>
      )}
      {notes.isPending && (
        <p className="page-status" role="status">
          Loading notes…
        </p>
      )}
      {notes.isError && (
        <p className="error" role="alert">
          {notes.error.message}{" "}
          <button className="button" onClick={() => void notes.refetch()}>
            Try again
          </button>
        </p>
      )}
      {!notes.isPending &&
        !all.length &&
        !notes.isError &&
        (label ? (
          <Empty heading={EmptyHeading} title={`Nothing under #${label} yet.`}>
            {canEdit && <p>Notes you write here get #{label} automatically.</p>}
          </Empty>
        ) : view === "archive" ? (
          <Empty heading={EmptyHeading} title="Nothing archived.">
            <p>Archive keeps a note out of the timeline without deleting it.</p>
          </Empty>
        ) : view === "trash" ? (
          <Empty heading={EmptyHeading} title="Trash is empty.">
            <p>Deleted notes stay here for 30 days.</p>
          </Empty>
        ) : view === "timeline" && canEdit ? (
          <Empty heading={EmptyHeading} title="A quiet place for loose ideas.">
            <p>
              Write anything above and press {formatShortcut("mod+enter")}. Add
              #labels anywhere
              {suggested ? ", or let them be suggested later" : ""}. Connect
              Claude Code or Codex in Settings → Agents.
            </p>
          </Empty>
        ) : (
          <Empty heading={EmptyHeading} title="No notes here yet." />
        ))}
      <section aria-label="Notes" aria-busy={notes.isPending}>
        {groups.map((group) => (
          <section key={group.title} aria-label={group.title}>
            <h2 className="overline day">{group.title}</h2>
            {group.notes.map((note) => (
              <NoteCard key={note.id} note={note} />
            ))}
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
          {notes.isFetchingNextPage ? "Loading more notes…" : "Load more notes"}
        </button>
      )}
      {view === "trash" && canEdit && all.length > 0 && (
        <button
          className="button danger"
          disabled={emptyBusy}
          onClick={() => void emptyTrash()}
        >
          {emptyBusy ? "Counting notes…" : "Empty trash"}
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
