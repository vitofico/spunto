import { useLanguage } from "../lib/i18n";
import { useDocumentTitle } from "../lib/useDocumentTitle";
export function ErrorLine({ error }: { error: Error | null }) {
  useLanguage();
  return error ? (
    <p className="error" role="alert">
      {error.message}
    </p>
  ) : null;
}
export function PageTitle({ page }: { page: string }) {
  useLanguage();
  useDocumentTitle(page);
  return null;
}
