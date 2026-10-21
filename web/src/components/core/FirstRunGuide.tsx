import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { formatShortcut } from "../../lib/platform";
import styles from "./FirstRunGuide.module.css";

export const GUIDE_COMPLETED_KEY = "spunto:guide:v1";

export function hasCompletedGuide() {
  try {
    return localStorage.getItem(GUIDE_COMPLETED_KEY) === "complete";
  } catch {
    return false;
  }
}

export function markGuideCompleted() {
  try {
    localStorage.setItem(GUIDE_COMPLETED_KEY, "complete");
  } catch {
    // App also keeps completion in memory when browser storage is unavailable.
  }
}

const steps = [
  {
    name: "Capture",
    title: "A quiet place for loose ideas.",
    body: "Write a thought, a link or a checklist. There’s no title or folder to choose. Save when you’re ready.",
    example: <p>Remember the tiny bookshop by the station.</p>,
    hint: () =>
      `Use Save, or press ${formatShortcut("mod+enter")}, to add your note.`,
  },
  {
    name: "Labels",
    title: "Labels live in your words.",
    body: "Add #reading anywhere in a note. Use a slash to nest labels, like #reading/books. The label list grows from your notes.",
    example: (
      <p>
        A book to come back to. <span>#reading/books</span>
      </p>
    ),
    hint: "Open a label to see its notes and the notes under its child labels.",
  },
  {
    name: "Search",
    title: "Find the thought again.",
    body: "Search words or #labels from Search. If AI features are enabled, search can also match meaning and Ask can answer from your notes.",
    example: (
      <>
        <p className={styles.query}>Search: bookshop</p>
        <p>Remember the tiny bookshop by the station.</p>
      </>
    ),
    hint: "AI is optional. Manage its features and where text goes in Settings → Plugins and AI.",
  },
  {
    name: "Your devices",
    title: "Your notes, across your devices.",
    body: "Saved notes live on your Spunto server. Pair the Android app in Settings → Devices using this server’s address.",
    example: (
      <p className={styles.query}>
        Settings → Devices → Pair an Android device
      </p>
    ),
    hint: "Android keeps a local copy and syncs when connected. The browser needs a connection to load notes; unfinished writing stays in this browser.",
  },
];

export function FirstRunGuide({
  onSkip,
  onFinish,
}: {
  onSkip: () => void;
  onFinish: () => void;
}) {
  const [step, setStep] = useState(0);
  const heading = useRef<HTMLHeadingElement>(null);
  const current = steps[step];
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
    <main className={styles.guide} aria-label="Getting started with Spunto">
      <header className={styles.header}>
        <span className="wordmark">
          spunto<span>.</span>
        </span>
        <button className="button" onClick={onSkip}>
          Skip
        </button>
      </header>
      <section className={styles.content} aria-labelledby="guide-title">
        <p id="guide-progress" className={styles.progress}>
          Step {step + 1} of {steps.length} · {current.name}
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
          <figcaption>Example</figcaption>
          <div>{current.example}</div>
        </figure>
        <p className={styles.hint}>
          {typeof current.hint === "function" ? current.hint() : current.hint}
        </p>
        <nav className={styles.actions} aria-label="Guide steps">
          <button
            className="button"
            disabled={step === 0}
            onClick={() => setStep(step - 1)}
          >
            Back
          </button>
          {step === steps.length - 1 ? (
            <button className="button primary" onClick={onFinish}>
              Start writing
            </button>
          ) : (
            <button
              className="button primary"
              onClick={() => setStep(step + 1)}
            >
              Next
            </button>
          )}
        </nav>
      </section>
    </main>
  );
}
