import { Link } from "wouter";
import { useDocumentTitle } from "../../lib/useDocumentTitle";

/** The one "not here" state, for unknown addresses and notes that no longer exist. */
export function NotFound({
  title = "This page isn’t here.",
}: {
  title?: string;
}) {
  useDocumentTitle("Not found");
  return (
    <div className="empty">
      <h1>{title}</h1>
      <p>It may have moved or been deleted.</p>
      <Link href="/">Go to your notes</Link>
    </div>
  );
}
