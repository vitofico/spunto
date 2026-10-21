import { useDocumentTitle } from "../lib/useDocumentTitle";
export function ErrorLine({ error }: { error: Error | null }) {
  return error ? (
    <p className="error" role="alert">
      {error.message}
    </p>
  ) : null;
}
export function PageTitle({ page }: { page: string }) {
  useDocumentTitle(page);
  return null;
}
