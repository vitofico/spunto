import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useLocation } from "wouter";
import { Icon } from "../components/core/Icon";
import { api, useHosted, useOwner, type NotificationFeed } from "../lib";
import { t, useLanguage } from "../lib/i18n";

export function useNotifications() {
  const owner = useOwner();
  const hosted = useHosted();
  return useQuery({
    queryKey: ["notifications", owner],
    queryFn: api.notifications,
    enabled: !!owner && hosted,
    refetchInterval: 60_000,
    staleTime: 15_000,
  });
}

export function NotificationLink({ close }: { close: () => void }) {
  useLanguage();
  const [location] = useLocation();
  const feed = useNotifications();
  const count = feed.data?.notifications?.filter((event) => !event.read).length;
  const label = count
    ? t("notifications.unread", { count })
    : t("notifications.title");
  return (
    <Link
      href="/notifications"
      aria-label={label}
      title={label}
      onClick={close}
      aria-current={location === "/notifications" ? "page" : undefined}
    >
      <Icon name="bell" />
      <span className="rail-label">{t("notifications.title")}</span>
      {!!count && <strong>{count}</strong>}
    </Link>
  );
}

export function FollowSpace() {
  useLanguage();
  const owner = useOwner();
  const feed = useNotifications();
  const client = useQueryClient();
  const change = useMutation({
    mutationFn: api.followSpace,
    onSuccess: ({ following }) => {
      const key = ["notifications", owner];
      client.setQueryData<NotificationFeed>(
        key,
        (data) => data && { ...data, following },
      );
      void client.invalidateQueries({ queryKey: key });
    },
  });
  return (
    <>
      <label>
        <input
          type="checkbox"
          checked={feed.data?.following ?? false}
          disabled={!feed.data?.followable || change.isPending}
          onChange={(event) => change.mutate(event.target.checked)}
        />{" "}
        {t("notifications.follow")}
      </label>
      {(change.error || feed.error) && (
        <p role="alert" className="error">
          {(change.error || feed.error)?.message}
        </p>
      )}
    </>
  );
}
