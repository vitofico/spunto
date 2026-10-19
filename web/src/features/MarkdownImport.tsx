import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, request } from "../lib/api";
import { VocabularyOutput } from "./Vocabulary";
import styles from "./Feature.module.css";

type Preview = {
  archive_id: string;
  report: {
    notes: number;
    attachments: number;
    attachment_bytes: number;
    missing_links: number;
  };
};

export function MarkdownImport() {
  const client = useQueryClient();
  const [preview, setPreview] = useState<Preview>();
  const [report, setReport] = useState<unknown>();
  const [queued, setQueued] = useState(false);
  const upload = useMutation({
    mutationFn: async (file: File) => {
      if (!file.size || file.size > 20_000_000)
        throw new Error("Choose a ZIP of at most 20 MB.");
      const form = new FormData();
      form.append("file", file);
      return request<Preview>("/api/v1/plugins/markdown-import/upload", {
        method: "POST",
        body: form,
      });
    },
    onMutate: () => {
      setPreview(undefined);
      setReport(undefined);
      setQueued(false);
    },
    onSuccess: setPreview,
  });
  const refresh = useMutation({
    mutationFn: () => api.pluginRoute("markdown-import", "report"),
    onSuccess: (result) => {
      setReport(result);
      void client.invalidateQueries({ queryKey: ["notes"] });
    },
  });
  const importing = useMutation({
    mutationFn: () =>
      api.pluginRoute("markdown-import", "import", "POST", {
        archive_id: preview!.archive_id,
      }),
    onSuccess: (result) => {
      setQueued(true);
      setReport(result);
    },
  });
  const busy = upload.isPending || importing.isPending;
  const error = upload.error ?? importing.error ?? refresh.error;
  return (
    <section aria-label="Import Markdown">
      <p className={styles.muted}>
        Upload a Markdown ZIP to preview its notes and attachments. Spunto
        exports preserve their metadata; other vaults use supported front
        matter. Notes you edited here are kept.
      </p>
      <label>
        Markdown ZIP{" "}
        <input
          type="file"
          accept=".zip,application/zip"
          disabled={busy}
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) {
              importing.reset();
              refresh.reset();
              upload.mutate(file);
            }
            event.target.value = "";
          }}
        />
      </label>
      {upload.isPending && <p role="status">Preparing preview…</p>}
      {preview && (
        <div>
          <p>
            {preview.report.notes} notes and {preview.report.attachments}{" "}
            attachments ({preview.report.attachment_bytes} bytes).
          </p>
          {preview.report.missing_links > 0 && (
            <p>
              {preview.report.missing_links} local links could not be found.
              Their original links will stay.
            </p>
          )}
          <button disabled={busy || queued} onClick={() => importing.mutate()}>
            {importing.isPending ? "Queueing import…" : "Import these notes"}
          </button>
        </div>
      )}
      {queued && (
        <p>
          Imports run in the background. Refresh the report to check progress.
        </p>
      )}
      {(queued || report !== undefined) && (
        <button disabled={refresh.isPending} onClick={() => refresh.mutate()}>
          Refresh import report
        </button>
      )}
      {report ? <VocabularyOutput value={report} /> : null}
      {error && (
        <p className={styles.error} role="alert">
          {error.message}
        </p>
      )}
    </section>
  );
}
