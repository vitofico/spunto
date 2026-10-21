import { useEffect } from "react";

/** Names the tab "<page> · Spunto", or "Spunto" on home, and restores it on unmount. */
export function useDocumentTitle(page?: string) {
  useEffect(() => {
    const previous = document.title;
    document.title = page ? `${page} · Spunto` : "Spunto";
    return () => {
      document.title = previous;
    };
  }, [page]);
}
