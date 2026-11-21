import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useLocation } from "wouter";
import { useNotifications } from "../features/Notifications";
import {
  api,
  currentSession,
  switchWorkspace,
  useHosted,
  useOwner,
  type CollaborationNotification,
} from "../lib";
import { getLanguage, t, useLanguage } from "../lib/i18n";
import { useDocumentTitle } from "../lib/useDocumentTitle";

export default function NotificationsPage() {
  useLanguage();
  useDocumentTitle(t("notifications.title"));
  const hosted = useHosted();
  const owner = useOwner();
  const feed = useNotifications();
  const client = useQueryClient();
  const [, navigate] = useLocation();
  const open = useMutation({
    mutationFn: async (event: CollaborationNotification) => {
      const note = event.note_id && encodeURIComponent(event.note_id);
      if (!event.read) {
        await api.readNotification(event.id);
        void client.invalidateQueries({ queryKey: ["notifications", owner] });
      }
      if (event.kind === "note_shared" && note) {
        navigate(
          `/shared/n/${note}?${new URLSearchParams({ workspace_id: event.workspace_id })}`,
        );
      } else {
        await switchWorkspace(event.workspace_id);
        if (currentSession()?.workspace_id === event.workspace_id)
          navigate(note ? `/n/${note}` : "/");
      }
    },
  });
  return (
    <>
      <h1 className="page-title">{t("notifications.title")}</h1>
      {!hosted ? (
        <p>{t("notifications.hosted")}</p>
      ) : feed.error ? (
        <p className="error" role="alert">
          {feed.error.message}
        </p>
      ) : feed.isPending ? (
        <p role="status">{t("notifications.loading")}</p>
      ) : !feed.data.notifications.length ? (
        <p className="muted">{t("notifications.empty")}</p>
      ) : (
        <ul className="stack">
          {feed.data.notifications.map((event) => (
            <li key={event.id} className="entry">
              <button
                className="button"
                disabled={open.isPending}
                onClick={() => open.mutate(event)}
              >
                {t(`notifications.${event.kind}`, {
                  space: event.workspace_name,
                })}
              </button>{" "}
              {!event.read && <strong>{t("notifications.new")}</strong>}{" "}
              <time
                className="muted"
                dateTime={new Date(event.created_at).toISOString()}
              >
                {new Date(event.created_at).toLocaleString(getLanguage(), {
                  dateStyle: "medium",
                  timeStyle: "short",
                })}
              </time>
            </li>
          ))}
        </ul>
      )}
      {open.error && (
        <p className="error" role="alert">
          {open.error.message}
        </p>
      )}
    </>
  );
}
