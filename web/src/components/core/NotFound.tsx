import { t, useLanguage } from "../../lib/i18n";
import { Link } from "wouter";
import { useDocumentTitle } from "../../lib/useDocumentTitle";

/** The one "not here" state, for unknown addresses and notes that no longer exist. */
export function NotFound({
  title = t("notfound.this_page_isn_t_here"),
}: {
  title?: string;
}) {
  useLanguage();
  useDocumentTitle(t("notfound.not_found"));
  return (
    <div className="empty">
      <h1>{title}</h1>
      <p>{t("notfound.it_may_have_moved_or_been_deleted")}</p>
      <Link href="/">{t("notfound.go_to_your_notes")}</Link>
    </div>
  );
}
