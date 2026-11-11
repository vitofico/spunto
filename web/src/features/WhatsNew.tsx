import { useEffect, useState } from "react";
import { Link } from "wouter";
import { t } from "../lib/i18n";
import styles from "./Feature.module.css";

const seenKey = "spunto:whats-new-seen";
// Advance this ID when adding the next group of updates.
const latestUpdate = "2026-11-prugna";

export function useUnreadWhatsNew(isOpen: boolean) {
  const [seen, setSeen] = useState(() => {
    try {
      return localStorage.getItem(seenKey);
    } catch {
      return null;
    }
  });

  useEffect(() => {
    if (!isOpen) return;
    setSeen(latestUpdate);
    try {
      localStorage.setItem(seenKey, latestUpdate);
    } catch {
      // The page still works when storage is unavailable.
    }
  }, [isOpen]);

  useEffect(() => {
    const update = (event: StorageEvent) => {
      if (event.key === seenKey || event.key === null) setSeen(event.newValue);
    };
    window.addEventListener("storage", update);
    return () => window.removeEventListener("storage", update);
  }, []);

  return !isOpen && seen !== latestUpdate;
}

export function WhatsNew() {
  return (
    <div className={styles.updates}>
      <h1>{t("whatsnew.title")}</h1>
      <article>
        <h2 className="overline">{t("whatsnew.latest")}</h2>
        <h3>{t("whatsnew.prugna_title")}</h3>
        <p>{t("whatsnew.prugna_body")}</p>
        <Link href="/settings/appearance">{t("whatsnew.open_appearance")}</Link>
      </article>
      <article>
        <h2 className="overline">{t("whatsnew.earlier")}</h2>
        <h3>{t("whatsnew.plain_title")}</h3>
        <p>{t("whatsnew.plain_body")}</p>
      </article>
    </div>
  );
}
