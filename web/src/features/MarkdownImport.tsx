import { t, useLanguage } from "../lib/i18n";
import { useId, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, request } from "../lib/api";
import { VocabularyOutput } from "./Vocabulary";
import { formatBytes, plural } from "./format";
import styles from "./Feature.module.css";

const namedSources = {
  obsidian: "Obsidian",
  notion: "Notion",
  evernote: "Evernote",
} as const;
type SourceFormat = "markdown" | keyof typeof namedSources;

type Preview = {
  archive_id: string;
  report: {
    notes: number;
    attachments: number;
    attachment_bytes: number;
    missing_links: number;
    unsupported_items?: number;
    planned?: {
      created: number;
      updated: number;
      unchanged: number;
      skipped_owner_edits: number;
    };
  };
};

export function MarkdownImport() {
  useLanguage();
  const client = useQueryClient();
  const formatId = useId();
  const [format, setFormat] = useState<SourceFormat>("markdown");
  const [preview, setPreview] = useState<Preview>();
  const [report, setReport] = useState<unknown>();
  const [queued, setQueued] = useState(false);
  const upload = useMutation({
    mutationFn: async ({
      file,
      source,
    }: {
      file: File;
      source: SourceFormat;
    }) => {
      if (!file.size || file.size > 20_000_000)
        throw new Error(t("markdownimport.file_size_limit"));
      const form = new FormData();
      form.append("file", file);
      const query = source === "markdown" ? "" : `?source_format=${source}`;
      return request<Preview>(
        `/api/v1/plugins/markdown-import/upload${query}`,
        {
          method: "POST",
          body: form,
        },
      );
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
  const busy = upload.isPending || importing.isPending || refresh.isPending;
  const error = upload.error ?? importing.error ?? refresh.error;
  return (
    <section aria-label={t("markdownimport.import_markdown")}>
      <div className={styles.formField}>
        <label htmlFor={formatId}>{t("markdownimport.source_format")}</label>
        <select
          id={formatId}
          className="field"
          value={format}
          disabled={busy}
          onChange={(event) => {
            setFormat(event.target.value as SourceFormat);
            setPreview(undefined);
            setQueued(false);
            setReport(undefined);
            upload.reset();
            importing.reset();
            refresh.reset();
          }}
        >
          <option value="markdown">
            {t("markdownimport.source_markdown")}
          </option>
          {Object.entries(namedSources).map(([value, product]) => (
            <option key={value} value={value}>
              {product}
            </option>
          ))}
        </select>
      </div>
      <p className="muted">{t(`markdownimport.help_${format}`)}</p>
      <p className="muted">{t("markdownimport.preview_help")}</p>
      <label className={styles.filePicker}>
        {t(
          format === "evernote"
            ? "markdownimport.choose_enex"
            : "markdownimport.choose_a_markdown_zip",
        )}
        <input
          className="sr-only"
          type="file"
          accept={format === "evernote" ? ".enex" : ".zip,application/zip"}
          disabled={busy}
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) {
              importing.reset();
              refresh.reset();
              upload.mutate({ file, source: format });
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
          {preview.report.planned && (
            <p>{t("markdownimport.preview_changes", preview.report.planned)}</p>
          )}
          {!!preview.report.unsupported_items && (
            <p>
              {t("markdownimport.unsupported_items", {
                count: preview.report.unsupported_items,
              })}
            </p>
          )}
          {preview.report.missing_links > 0 && (
            <p>
              {t("markdownimport.missing_links", {
                count: preview.report.missing_links,
              })}
            </p>
          )}
          <button
            className="primary"
            disabled={busy || queued}
            onClick={() => importing.mutate()}
          >
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
