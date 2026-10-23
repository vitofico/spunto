import { t, useLanguage } from "../../lib/i18n";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { formatShortcut } from "../../lib/platform";
import styles from "./FirstRunGuide.module.css";

export {
  GUIDE_COMPLETED_KEY,
  hasCompletedGuide,
  markGuideCompleted,
} from "../../lib/guide";

const steps = () => [
  {
    name: t("firstrunguide.capture"),
    title: t("timeline.a_quiet_place_for_loose_ideas"),
    body: t("firstrunguide.write_a_thought_a_link_or_a_checklist"),
    example: (
      <p>{t("firstrunguide.remember_the_tiny_bookshop_by_the_station")}</p>
    ),
    hint: () =>
      t("firstrunguide.use_save_or_press_value1_to_add_your", {
        value1: formatShortcut("mod+enter"),
      }),
  },
  {
    name: t("app.labels"),
    title: t("firstrunguide.labels_live_in_your_words"),
    body: t("firstrunguide.add_reading_anywhere_in_a_note_use_a"),
    example: (
      <p>
        {t("firstrunguide.a_book_to_come_back_to")}
        <span>#reading/books</span>
      </p>
    ),
    hint: t("firstrunguide.open_a_label_to_see_its_notes_and"),
  },
  {
    name: t("app.search"),
    title: t("firstrunguide.find_the_thought_again"),
    body: t("firstrunguide.search_words_or_labels_from_search_if_ai"),
    example: (
      <>
        <p className={styles.query}>{t("firstrunguide.search_bookshop")}</p>
        <p>{t("firstrunguide.remember_the_tiny_bookshop_by_the_station")}</p>
      </>
    ),
    hint: t("firstrunguide.ai_is_optional_manage_its_features_and_where"),
  },
  {
    name: t("firstrunguide.your_devices"),
    title: t("firstrunguide.your_notes_across_your_devices"),
    body: t("firstrunguide.saved_notes_live_on_your_spunto_server_pair"),
    example: (
      <p className={styles.query}>
        {t("firstrunguide.settings_devices_pair_an_android_device")}
      </p>
    ),
    hint: t("firstrunguide.android_keeps_a_local_copy_and_syncs_when"),
  },
];

export function FirstRunGuide({
  onSkip,
  onFinish,
}: {
  onSkip: () => void;
  onFinish: () => void;
}) {
  useLanguage();
  const [step, setStep] = useState(0);
  const heading = useRef<HTMLHeadingElement>(null);
  const current = steps()[step];
  useLayoutEffect(() => {
    heading.current?.focus();
  }, [step]);
  // Escape skips, like Skip, unless something on top (a dialog) handles it.
  useEffect(() => {
    const key = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || event.defaultPrevented) return;
      if (document.querySelector("dialog[open]")) return;
      event.preventDefault();
      onSkip();
    };
    document.addEventListener("keydown", key);
    return () => document.removeEventListener("keydown", key);
  }, [onSkip]);
  return (
    <main
      className={styles.guide}
      aria-label={t("firstrunguide.getting_started_with_spunto")}
    >
      <header className={styles.header}>
        <span className="wordmark">
          spunto<span>.</span>
        </span>
        <button className="button" onClick={onSkip}>
          {t("review.skip")}
        </button>
      </header>
      <section className={styles.content} aria-labelledby="guide-title">
        <p id="guide-progress" className={styles.progress}>
          {t("firstrunguide.progress", {
            step: step + 1,
            total: steps().length,
            name: current.name,
          })}
        </p>
        <h1
          id="guide-title"
          aria-describedby="guide-progress"
          ref={heading}
          tabIndex={-1}
        >
          {current.title}
        </h1>
        <p className={styles.body}>{current.body}</p>
        <figure className={styles.example}>
          <figcaption>{t("firstrunguide.example")}</figcaption>
          <div>{current.example}</div>
        </figure>
        <p className={styles.hint}>
          {typeof current.hint === "function" ? current.hint() : current.hint}
        </p>
        <nav
          className={styles.actions}
          aria-label={t("firstrunguide.guide_steps")}
        >
          <button
            className="button"
            disabled={step === 0}
            onClick={() => setStep(step - 1)}
          >
            {t("slots.back")}
          </button>
          {step === steps().length - 1 ? (
            <button className="button primary" onClick={onFinish}>
              {t("firstrunguide.start_writing")}
            </button>
          ) : (
            <button
              className="button primary"
              onClick={() => setStep(step + 1)}
            >
              {t("firstrunguide.next")}
            </button>
          )}
        </nav>
      </section>
    </main>
  );
}
