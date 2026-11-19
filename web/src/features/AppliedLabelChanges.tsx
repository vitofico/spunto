import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useId } from "react";
import { Icon } from "../components/core/Icon";
import { api } from "../lib/api";
import { t, useLanguage } from "../lib/i18n";
import { invalidateContentFor } from "../lib/query";
import type { Suggestion } from "../lib/types";
import { useSuggestionFeedback } from "./suggestionFeedback";
import { appliedSuggestionTitle } from "./suggestionTitle";
import styles from "./Feature.module.css";

export function AppliedLabelChanges({ items }: { items: Suggestion[] }) {
  useLanguage();
  const client = useQueryClient();
  const { undo } = useSuggestionFeedback();
  const heading = useId();
  const dismiss = useMutation({
    mutationFn: (id: string) => api.decideSuggestion(id, "dismiss"),
    onSuccess: () => invalidateContentFor(client),
  });
  if (!items.length) return null;
  return (
    <section className={styles.labelReceipts} aria-labelledby={heading}>
      <h2 id={heading}>{t("note.recent_label_changes")}</h2>
      <ul>
        {items.map((item) => {
          const name = appliedSuggestionTitle(item);
          const id = `${heading}-${item.id}`;
          return (
            <li key={item.id}>
              <span id={id}>{name}</span>
              <button
                aria-describedby={id}
                disabled={undo.isPending || dismiss.isPending}
                onClick={() => undo.mutate(item)}
              >
                {t("settingsplugins.undo")}
              </button>
              <button
                className="icon-button"
                disabled={undo.isPending || dismiss.isPending}
                aria-label={t("slots.dismiss_value1", { value1: name })}
                onClick={() => dismiss.mutate(item.id)}
              >
                <Icon name="close" />
              </button>
            </li>
          );
        })}
      </ul>
      {dismiss.isError && (
        <p className="error" role="alert">
          {dismiss.error.message}
        </p>
      )}
    </section>
  );
}
