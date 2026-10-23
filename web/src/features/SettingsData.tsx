import { localizeCatalog } from "../lib/localizePlugins";
import { t, useLanguage } from "../lib/i18n";
import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "wouter";
import { api } from "../lib/api";
import { useToast } from "../components/core/Toast";
import { useConfirm } from "../components/core/ConfirmDialog";
import styles from "./Feature.module.css";
import { MarkdownImport } from "./MarkdownImport";
import { plural } from "./format";
import { ErrorLine } from "./SettingsParts";
import {
  PermissionDialog,
  pluginCanEnable,
  usePluginActions,
} from "./SettingsPlugins";
export function Data() {
  useLanguage();
  const catalog = useQuery({
    queryKey: ["catalog"],
    queryFn: api.catalog,
    select: (data) => localizeCatalog(data),
  });
  const status = useQuery({ queryKey: ["status"], queryFn: api.status });
  const client = useQueryClient();
  const toast = useToast();
  const [confirm, dialog] = useConfirm();
  const [enable, setEnable] = useState(false);
  const enabler = useRef<HTMLButtonElement>(null);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const action = usePluginActions();
  const importer = catalog.data?.plugins.find(
    (plugin) => plugin.id === "markdown-import",
  );
  const count = Number(
    (status.data?.database as { trashed_notes?: number } | undefined)
      ?.trashed_notes ?? 0,
  );
  const deletion = useMutation({
    mutationFn: () =>
      api.request<{ deleted: number }>("/api/v1/trash/empty", {
        method: "POST",
      }),
    onSuccess: () => {
      toast.show({ message: t("settingsdata.trash_emptied") });
      for (const key of ["notes", "status", "labels"])
        void client.invalidateQueries({ queryKey: [key] });
    },
  });
  const exportData = async () => {
    setExporting(true);
    setExportError(null);
    try {
      const url = URL.createObjectURL(await api.exportNotes());
      const link = document.createElement("a");
      link.href = url;
      link.download = "spunto-export.zip";
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      setExportError(
        error instanceof Error
          ? error.message
          : t("settingsdata.export_failed"),
      );
    } finally {
      setExporting(false);
    }
  };
  return (
    <>
      <h1>{t("settings.data")}</h1>
      <h2>{t("settingsdata.export")}</h2>
      <p className="muted">
        {t("settingsdata.all_your_notes_as_markdown_their_metadata_and")}
      </p>
      <button
        className="primary"
        disabled={exporting}
        onClick={() => void exportData()}
      >
        {exporting
          ? t("settingsdata.preparing_export")
          : t("settingsdata.export_notes")}
      </button>
      {exportError && (
        <p role="alert" className="error">
          {exportError}
        </p>
      )}
      <h2>{t("markdownimport.import_markdown")}</h2>
      {importer?.state === "enabled" ? (
        <MarkdownImport />
      ) : importer && importer.state !== "removed" ? (
        <>
          <p className="muted">
            {t("settingsdata.import_explanation", { plugin: importer.name })}
          </p>
          {importer.pinned_by_env ? (
            <p className={styles.facts}>
              {t("settingsdata.set_in_the_environment_off")}
            </p>
          ) : (
            <button
              ref={enabler}
              disabled={!pluginCanEnable(importer)}
              onClick={() => {
                action.reset();
                setEnable(true);
              }}
            >
              {t("settingsdata.turn_on_plugin", { plugin: importer.name })}
            </button>
          )}
        </>
      ) : (
        <p className="muted">
          {t("settingsdata.markdown_import_is_not_available_on_this_server")}
        </p>
      )}
      <h2>{t("app.trash")}</h2>
      <p className="muted">{t("settingsdata.trash_count", { count })}</p>
      <div className={styles.row}>
        <Link href="/trash">{t("settingsdata.open_trash")}</Link>
        {count > 0 && (
          <button
            className="danger"
            disabled={deletion.isPending}
            onClick={async (e) => {
              if (
                await confirm({
                  opener: e.currentTarget,
                  title: t("settingsdata.delete_value1_for_good", {
                    value1: plural(count, "note"),
                  }),
                  body: t(
                    "settingsdata.this_permanently_deletes_value1_and_their_attachments_this",
                    { value1: plural(count, "trashed_note") },
                  ),
                  confirmLabel: t("settingsdata.delete_value1", {
                    value1: plural(count, "note"),
                  }),
                  danger: true,
                })
              )
                deletion.mutate();
            }}
          >
            {t("settingsdata.empty_trash")}
          </button>
        )}
      </div>
      <ErrorLine error={status.error ?? deletion.error} />
      {dialog}
      {enable && importer && pluginCanEnable(importer) && (
        <PermissionDialog
          plugin={importer}
          opener={enabler.current}
          onClose={() => setEnable(false)}
          busy={action.isPending}
          error={action.error}
          onEnable={() =>
            action.mutate(
              { plugin: importer, on: true },
              { onSuccess: () => setEnable(false) },
            )
          }
        />
      )}
    </>
  );
}
