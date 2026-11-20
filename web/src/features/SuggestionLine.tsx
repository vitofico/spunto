import { useEffect, useId, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { t, useLanguage } from "../lib/i18n";
import { invalidateContentFor } from "../lib/query";
import type { Note, Suggestion } from "../lib/types";
import { useToast } from "../components/core/Toast";
import { Dialog } from "./Dialog";
import { SuggestionPreview } from "./LazyPluginViews";
import { usePlugins, visibleSuggestions } from "./Slots";
import { useSuggestionFeedback } from "./suggestionFeedback";
import {
  appliedSuggestionTitle,
  requiresSuggestionPreview,
  suggestionTitle,
  suggestionReason,
} from "./suggestionTitle";
import { labelChangeImpact } from "./labelChange";
import { plural } from "./format";
import styles from "./Feature.module.css";

export default function SuggestionLine({
  note,
  onReview,
  editing = false,
}: {
  note: Note;
  onReview?: () => void;
  editing?: boolean;
}) {
  useLanguage();
  const manifest = usePlugins();
  const client = useQueryClient();
  const [expanded, setExpanded] = useState(false);
  const [order, setOrder] = useState<string[]>([]);
  const { data: hidden } = useQuery<string[]>({
    queryKey: ["command-proposals", note.id],
    queryFn: () => [],
    enabled: false,
  });
  const { acknowledge, undo } = useSuggestionFeedback();
  const [preview, setPreview] = useState<Suggestion | null>(null);
  const opener = useRef<HTMLElement>(null);
  const describe = useId();
  const toast = useToast();
  // A touch long-press shows the provenance a mouse gets from the tooltip.
  const press = useRef<{ timer?: number; shown?: boolean }>({});
  const release = () => {
    window.clearTimeout(press.current.timer);
    press.current.timer = undefined;
  };
  const reveal = (provenance: string) => {
    release();
    press.current.shown = true;
    toast.show({ message: provenance });
  };
  const impact = useQuery({
    queryKey: [
      "label-impact",
      preview?.payload.old,
      preview?.payload.new,
      preview?.payload.note_ids,
    ],
    queryFn: () =>
      api.request<{ notes: number }>("/api/v1/labels/impact", {
        method: "POST",
        body: labelChangeImpact(preview?.payload),
      }),
    enabled: preview?.action === "label_change",
    retry: false,
  });
  const decision = useMutation({
    mutationFn: ({
      s,
      action,
    }: {
      s: Suggestion;
      action: "accept" | "dismiss";
    }) => api.decideSuggestion(s.id, action),
    onSuccess: (result, { s, action }) => {
      setPreview(null);
      void invalidateContentFor(client);
      if (action === "accept") {
        if (result?.suggestion?.receipt?.undoable && s.action !== "add_label")
          setOrder(items.map((item) => item.id));
        acknowledge(result, s);
      }
    },
  });
  useEffect(() => {
    setExpanded(false);
    setOrder([]);
    setPreview(null);
  }, [note.id]);
  const suggestions = visibleSuggestions(
    note,
    manifest.data?.plugins ?? [],
  ).filter((s) => !hidden?.includes(s.id));
  const pending = new Set(suggestions.map((s) => s.id));
  const items = (note.suggestions ?? []).filter(
    (s) =>
      pending.has(s.id) || (s.receipt?.undoable && s.action !== "add_label"),
  );
  if (items.some((s) => s.receipt?.undoable) && order.length) {
    const positions = new Map(order.map((id, index) => [id, index]));
    items.sort(
      (a, b) =>
        (positions.get(a.id) ?? order.length) -
        (positions.get(b.id) ?? order.length),
    );
  }
  if (editing || !items.length) return null;
  const apply = (s: Suggestion, trigger: HTMLElement) => {
    opener.current = trigger;
    if (requiresSuggestionPreview(s)) setPreview(s);
    else decision.mutate({ s, action: "accept" });
  };
  return (
    <div
      className={styles.suggestions}
      aria-label={t("slots.suggestions")}
      onKeyDown={(e) => {
        if (
          e.target instanceof HTMLInputElement ||
          e.target instanceof HTMLTextAreaElement
        )
          return;
        if (
          e.key === "x" &&
          e.target instanceof HTMLElement &&
          !e.target.closest("dialog")
        ) {
          e.preventDefault();
          for (const s of suggestions)
            decision.mutate({ s, action: "dismiss" });
        }
        if (
          e.key === "a" &&
          e.target instanceof HTMLElement &&
          !e.target.closest("dialog")
        ) {
          e.preventDefault();
          const review = suggestions.find(requiresSuggestionPreview);
          if (review) {
            opener.current = null;
            onReview ? onReview() : setPreview(review);
          } else
            for (const s of suggestions)
              decision.mutate({ s, action: "accept" });
        }
      }}
    >
      <div
        className={
          expanded ? styles.expandedSuggestions : styles.suggestionItems
        }
      >
        {(expanded ? items : items.slice(0, 3)).map((s) => {
          if (s.receipt?.undoable)
            return (
              <span className={styles.suggestionGroup} key={s.id}>
                <span
                  className={styles.receipt}
                  title={appliedSuggestionTitle(s)}
                >
                  {appliedSuggestionTitle(s)}
                </span>
                <button
                  className={styles.receiptUndo}
                  disabled={undo.isPending}
                  onClick={() => undo.mutate(s)}
                >
                  {t("settingsplugins.undo")}
                </button>
                <button
                  className={styles.dismiss}
                  disabled={decision.isPending}
                  aria-label={t("slots.dismiss_value1", {
                    value1: appliedSuggestionTitle(s),
                  })}
                  onClick={() => decision.mutate({ s, action: "dismiss" })}
                >
                  ×
                </button>
              </span>
            );
          const source = manifest.data?.plugins.find((p) => p.id === s.plugin);
          const reason = suggestionReason(s, source);
          const provenance = t("slots.suggested_by_value1_value2", {
            value1: source?.name ?? s.plugin,
            value2: reason ? `: ${reason}` : "",
          });
          return (
            <span className={styles.suggestionGroup} key={s.id}>
              <button
                className={styles.pill}
                disabled={decision.isPending}
                title={provenance}
                aria-describedby={`${describe}-${s.id}`}
                onPointerDown={(e) => {
                  press.current =
                    e.pointerType === "mouse"
                      ? {}
                      : {
                          timer: window.setTimeout(
                            () => reveal(provenance),
                            500,
                          ),
                        };
                }}
                onPointerUp={release}
                onPointerCancel={release}
                onPointerLeave={release}
                onContextMenu={(e) => {
                  if (press.current.timer === undefined) return;
                  e.preventDefault();
                  reveal(provenance);
                }}
                onClick={(e) => {
                  const held = press.current.shown;
                  press.current = {};
                  if (!held) apply(s, e.currentTarget);
                }}
              >
                {suggestionTitle(s)}
                {s.payload.is_new === true && <small>{t("slots.new")}</small>}
              </button>
              <span id={`${describe}-${s.id}`} hidden>
                {provenance}
              </span>
              <button
                className={styles.dismiss}
                aria-label={t("slots.dismiss_value1", {
                  value1:
                    s.action === "add_label"
                      ? `#${String(s.payload.path)}`
                      : suggestionTitle(s),
                })}
                disabled={decision.isPending}
                onClick={() => decision.mutate({ s, action: "dismiss" })}
              >
                ×
              </button>
            </span>
          );
        })}
      </div>
      {!expanded && items.length > 3 && (
        <button className={styles.overflow} onClick={() => setExpanded(true)}>
          +{items.length - 3}
        </button>
      )}
      {suggestions.length > 1 && (
        <button
          className={styles.dismiss}
          aria-label={t("slots.dismiss_value1", {
            value1: plural(suggestions.length, "suggestion"),
          })}
          disabled={decision.isPending}
          onClick={() => {
            for (const s of suggestions)
              decision.mutate({ s, action: "dismiss" });
          }}
        >
          {t("review.dismiss")}
        </button>
      )}
      {decision.isError && (
        <p className="error" role="alert">
          {decision.error.message}
        </p>
      )}
      {preview && (
        <Dialog
          title={suggestionTitle(preview)}
          opener={opener.current}
          onClose={() => setPreview(null)}
          footer={
            <>
              <button onClick={() => setPreview(null)}>
                {t("profilepanel.cancel")}
              </button>
              <button
                className="primary"
                disabled={
                  decision.isPending ||
                  (preview.action === "label_change" && !impact.data)
                }
                onClick={() =>
                  decision.mutate({ s: preview, action: "accept" })
                }
              >
                {t("review.apply")}
              </button>
            </>
          }
        >
          <SuggestionPreview suggestion={preview} />
        </Dialog>
      )}
    </div>
  );
}
