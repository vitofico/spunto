import { Link, useLocation, useSearch } from "wouter";
import AskConversation from "../features/AskConversation";
import { Icon } from "../components/core/Icon";
import { NotFound } from "../components/core/NotFound";
import { t, useLanguage } from "../lib/i18n";
import { useDocumentTitle } from "../lib/useDocumentTitle";
import styles from "../features/AskConversation.module.css";

export default function AskPage() {
  useLanguage();
  useDocumentTitle(t("plugin.ask.name"));
  const [, navigate] = useLocation();
  const collection = new URLSearchParams(useSearch()).get("collection");
  if (collection !== null && !/^[\da-f-]{36}$/i.test(collection))
    return <NotFound />;
  const back = collection
    ? "/collections/" + encodeURIComponent(collection)
    : "/";
  const restoreLauncher = () => {
    if (!collection)
      requestAnimationFrame(() =>
        document.querySelector<HTMLElement>("[data-ask-launcher]")?.focus(),
      );
  };
  return (
    <div
      className={styles.frame}
      onKeyDown={(event) => {
        if (event.key === "Escape" && !event.defaultPrevented) {
          event.preventDefault();
          navigate(back);
          restoreLauncher();
        }
      }}
    >
      <AskConversation
        key={collection ?? ""}
        value={undefined}
        page
        backLink={
          <Link href={back} className={styles.back} onClick={restoreLauncher}>
            <Icon name="back" />{" "}
            {t(collection ? "ask.back_collection" : "app.all_notes")}
          </Link>
        }
        collectionId={collection ?? undefined}
      />
    </div>
  );
}
