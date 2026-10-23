import { t, useLanguage } from "../lib/i18n";
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, request } from "../lib/api";
import { VocabularyOutput } from "./Vocabulary";
import { formatBytes, plural } from "./format";
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
  useLanguage();
  const client = useQueryClient();
  const [preview, setPreview] = useState<Preview>();
  const [report, setReport] = useState<unknown>();
  const [queued, setQueued] = useState(false);
  const upload = useMutation({
    mutationFn: async (file: File) => {
      if (!file.size || file.size > 20_000_000)
        throw new Error(t("markdownimport.choose_a_zip_of_at_most_20_mb"));
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
      void client.invalidateQueries({ queryKey: ["labels"] });
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
    <section aria-label={t("markdownimport.import_markdown")}>
      <p className="muted">
        {t("markdownimport.upload_a_markdown_zip_to_preview_its_notes")}
      </p>
      <label className={styles.filePicker}>
        {t("markdownimport.choose_a_markdown_zip")}
        <input
          className="sr-only"
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
      {upload.isPending && (
        <p role="status">{t("markdownimport.preparing_preview")}</p>
      )}
      {preview && (
        <div>
          <p>
            {t("markdownimport.preview_counts", {
              notes: plural(preview.report.notes, "note"),
              attachments: plural(preview.report.attachments, "attachment"),
              size:
                preview.report.attachment_bytes > 0
                  ? ` (${formatBytes(preview.report.attachment_bytes)})`
                  : "",
            })}
          </p>
          {preview.report.missing_links > 0 && (
            <p>
              {t("markdownimport.missing_links", {
                count: preview.report.missing_links,
              })}
            </p>
          )}
          <button disabled={busy || queued} onClick={() => importing.mutate()}>
            {importing.isPending
              ? t("markdownimport.queueing_import")
              : t("markdownimport.import_these_notes")}
          </button>
        </div>
      )}
      {/* The server's report says what happens next, such as "Import queued." */}
      {report ? <VocabularyOutput value={report} /> : null}
      {(queued || report !== undefined) && (
        <button disabled={refresh.isPending} onClick={() => refresh.mutate()}>
          {t("markdownimport.refresh_import_report")}
        </button>
      )}
      {error && (
        <p className="error" role="alert">
          {error.message}
        </p>
      )}
    </section>
  );
}
