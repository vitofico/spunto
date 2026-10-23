import { lazy, Suspense } from "react";
import type { Suggestion } from "../lib/types";
const Preview = lazy(() =>
  import("./SuggestionPreview").then((module) => ({
    default: module.SuggestionPreview,
  })),
);
export function SuggestionPreview(props: { suggestion: Suggestion }) {
  return (
    <Suspense fallback={null}>
      <Preview {...props} />
    </Suspense>
  );
}
