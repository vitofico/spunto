import { useRef, type RefObject } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, safeError, useBrowserOnline } from "../lib";
import { t } from "../lib/i18n";
import { Icon } from "../components/core/Icon";
import { useModalFocus } from "../components/core/useModalFocus";
import CommandResult from "./CommandResult";

const statsOrigin = { plugin: "library-stats", command: "summary" };

function LibraryStats({ owner }: { owner: string }) {
  const online = useBrowserOnline();
  const summary = useQuery({
    queryKey: ["library-stats", owner, "summary"],
    queryFn: () => api.command(statsOrigin.plugin, statsOrigin.command, {}),
    enabled: online,
    staleTime: 0,
    gcTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
  });
  return (
    <div className="palette-group">
      {!online ? (
        <p role="status">{t("library.stats_connect")}</p>
      ) : summary.isError ? (
        <>
          <p className="error" role="alert">
            {safeError(summary.error).message}
          </p>
          <button onClick={() => void summary.refetch()}>
            {t("ask.retry")}
          </button>
        </>
      ) : summary.isPending ? (
        <p role="status">{t("plugins.running")}</p>
      ) : (
        <CommandResult value={summary.data} origin={statsOrigin} />
      )}
    </div>
  );
}

export default function LibraryDialog({
  owner,
  onClose,
  restoreFocus,
}: {
  owner: string;
  onClose: () => void;
  restoreFocus: RefObject<HTMLElement | null>;
}) {
  const dialog = useRef<HTMLElement>(null);
  useModalFocus(dialog, true, onClose, restoreFocus);
  const title = t("plugin.library-stats.name");
  return (
    <div
      className="overlay"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        ref={dialog}
        className="palette"
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <header className="palette-head library-tool-head">
          <strong>{title}</strong>
          <button
            className="button"
            aria-label={t("app.close")}
            onClick={onClose}
            data-modal-initial
          >
            <Icon name="close" />
          </button>
        </header>
        <LibraryStats owner={owner} />
      </section>
    </div>
  );
}
