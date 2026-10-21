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
  const catalog = useQuery({ queryKey: ["catalog"], queryFn: api.catalog });
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
      toast.show({ message: "Trash emptied" });
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
      setExportError(error instanceof Error ? error.message : "Export failed.");
    } finally {
      setExporting(false);
    }
  };
  return (
    <>
      <h1>Data</h1>
      <h2>Export</h2>
      <p className="muted">
        All your notes as Markdown, their metadata and attachments in one ZIP.
      </p>
      <button
        className="primary"
        disabled={exporting}
        onClick={() => void exportData()}
      >
        {exporting ? "Preparing export…" : "Export notes"}
      </button>
      {exportError && (
        <p role="alert" className="error">
          {exportError}
        </p>
      )}
      <h2>Import Markdown</h2>
      {importer?.state === "enabled" ? (
        <MarkdownImport />
      ) : importer && importer.state !== "removed" ? (
        <>
          <p className="muted">
            Import a Markdown ZIP, such as a Spunto export or an Obsidian vault.
            The {importer.name} plugin reads it; turn it on to choose a file.
          </p>
          {importer.pinned_by_env ? (
            <p className={styles.facts}>Set in the environment · Off</p>
          ) : (
            <button
              ref={enabler}
              disabled={!pluginCanEnable(importer)}
              onClick={() => {
                action.reset();
                setEnable(true);
              }}
            >
              Turn on {importer.name}…
            </button>
          )}
        </>
      ) : (
        <p className="muted">
          Markdown import is not available on this server.
        </p>
      )}
      <h2>Trash</h2>
      <p className="muted">
        {plural(count, "note")} in Trash. Notes there are deleted for good after
        30 days.
      </p>
      <div className={styles.row}>
        <Link href="/trash">Open Trash</Link>
        {count > 0 && (
          <button
            className="danger"
            disabled={deletion.isPending}
            onClick={async (e) => {
              if (
                await confirm({
                  opener: e.currentTarget,
                  title: `Delete ${plural(count, "note")} for good?`,
                  body: `This permanently deletes ${plural(count, "trashed note")} and their attachments. This cannot be undone.`,
                  confirmLabel: `Delete ${plural(count, "note")}`,
                  danger: true,
                })
              )
                deletion.mutate();
            }}
          >
            Empty trash…
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
