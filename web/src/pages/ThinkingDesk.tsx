import { useEffect, useRef, useState, type ReactNode } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { Link, useLocation } from "wouter";
import { NotFound } from "../components/core/NotFound";
import { Icon } from "../components/core/Icon";
import { useToast } from "../components/core/Toast";
import CommandResult from "../features/CommandResult";
import {
  dedicatedDesk,
  deskChoices,
  deskListing,
  deskWorkspace,
} from "../features/thinkingDeskData";
import { outputText } from "../features/vocabularyData";
import type { CommandOrigin } from "../features/Slots";
import { api } from "../lib/api";
import { t, useLanguage } from "../lib/i18n";
import { isBundledPlugin } from "../lib/localizePlugins";
import { useManifest, useOwner, useWorkspaceRole } from "../lib/query";
import type { Args } from "../lib/types";
import { useDocumentTitle } from "../lib/useDocumentTitle";
import styles from "./ThinkingDesk.module.css";

const root = "/thinking-desk";
const deskTitle = () => t("plugin.thinking-desk.name");
const commandOrigin = { plugin: "thinking-desk", command: "desk" };
const loadDesk = (args: Args) => api.command("thinking-desk", "desk", args);
const loading = () => <p role="status">{t("app.opening")}</p>;

export default function ThinkingDesk({ desk }: { desk?: string }) {
  useLanguage();
  const manifest = useManifest();
  const owner = useOwner();
  const role = useWorkspaceRole();
  const plugin = manifest.data?.plugins.find(
    (item) =>
      item.id === "thinking-desk" &&
      isBundledPlugin(item) &&
      (item.state === "enabled" || item.state === undefined),
  );
  if (role !== "owner" || !owner) return <NotFound />;
  if (manifest.isPending) return loading();
  if (manifest.isError)
    return (
      <DeskFrame title={deskTitle()}>
        <DeskError error={manifest.error} retry={manifest.refetch} />
      </DeskFrame>
    );
  if (!plugin || (desk && desk !== "new" && !/^[\da-f-]{36}$/i.test(desk)))
    return <NotFound />;
  return desk ? (
    <OpenDesk
      key={owner + "/" + desk}
      desk={desk}
      modern={dedicatedDesk(plugin)}
    />
  ) : (
    <DeskLanding key={owner} />
  );
}

function DeskLanding() {
  const language = useLanguage();
  const owner = useOwner();
  const listing = useInfiniteQuery({
    queryKey: ["thinking-desk", owner, language],
    queryFn: ({ pageParam }) =>
      loadDesk(pageParam ? { cursor: pageParam } : {}),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (value) => deskListing(value).after,
    gcTime: 0,
    staleTime: (query) =>
      query.state.data?.pages.some((value) => deskListing(value).desks.length)
        ? 0
        : Infinity,
  });
  const first = listing.data?.pages[0];
  const desks = new Map(
    listing.data?.pages
      .flatMap((value) => deskListing(value).desks)
      .map((desk) => [desk.id, desk.title]),
  );
  return (
    <DeskFrame title={deskTitle()}>
      {listing.isPending && loading()}
      {desks.size > 0 ? (
        <>
          <div className={styles.actions}>
            <h2>{t("thinkingdesk.resume")}</h2>
            <Link className="button primary" href={root + "/new"}>
              {t("thinkingdesk.new")}
            </Link>
          </div>
          <ul className={styles.desks}>
            {[...desks].map(([id, title]) => (
              <li key={id}>
                <Link href={root + "/" + encodeURIComponent(id)}>{title}</Link>
              </li>
            ))}
          </ul>
          {listing.hasNextPage && (
            <button
              disabled={listing.isFetchingNextPage}
              onClick={() => void listing.fetchNextPage()}
            >
              {t("thinkingdesk.more")}
            </button>
          )}
        </>
      ) : (
        first !== undefined && <DeskContent value={first} />
      )}
      {listing.error && (
        <DeskError
          error={listing.error}
          retry={
            desks.size && listing.hasNextPage
              ? listing.fetchNextPage
              : listing.refetch
          }
        />
      )}
    </DeskFrame>
  );
}

function OpenDesk({ desk, modern }: { desk: string; modern: boolean }) {
  const owner = useOwner();
  const isNew = desk === "new";
  const deskData = useQuery({
    queryKey: ["thinking-desk", owner, desk],
    queryFn: () =>
      loadDesk(isNew ? (modern ? { action: "new" } : {}) : { desk_id: desk }),
    gcTime: 0,
    staleTime: Infinity,
  });
  if (deskData.isPending)
    return (
      <DeskFrame title={deskTitle()} back>
        {loading()}
      </DeskFrame>
    );
  if (deskData.isError)
    return (
      <DeskFrame title={deskTitle()} back>
        <DeskError error={deskData.error} retry={deskData.refetch} />
      </DeskFrame>
    );
  return <DeskContent value={deskData.data} desk={desk} />;
}

function DeskContent({ value, desk }: { value: unknown; desk?: string }) {
  const [, navigate] = useLocation();
  const toast = useToast();
  const initial = deskWorkspace(value);
  const [choiceVersion, setChoiceVersion] = useState(0);
  const [title, setTitle] = useState(initial?.title);
  const initialChoice = initial ? value : deskChoices(value);
  const pageValue = useRef(initialChoice).current;
  const origin = useRef({
    ...commandOrigin,
    args: initial ? { desk_id: initial.id } : undefined,
  }).current;
  const pageActions = {
    route(args: Args, from: CommandOrigin | undefined) {
      if (typeof args.desk_id === "string") {
        if (args.desk_id === initial?.id) return false;
        navigate(root + "/" + encodeURIComponent(args.desk_id));
      } else if (from?.command === "edit" && recordStart(from.args)) {
        setChoiceVersion((version) => version + 1);
      } else {
        navigate(args.action === "new" ? root + "/new" : root);
      }
      return true;
    },
    changed(next: unknown, command: string, args?: Args) {
      const current = deskWorkspace(next);
      if (current) {
        setTitle(current.title);
        if (!initial)
          navigate(root + "/" + encodeURIComponent(current.id), {
            replace: desk === "new",
          });
      } else if (command === "edit" && args?.action === "delete") {
        toast.show({ message: outputText(next) });
        navigate(root, { replace: true });
      }
    },
  };
  const content = (
    <CommandResult
      key={choiceVersion}
      value={pageValue}
      origin={origin}
      deskPage={pageActions}
    />
  );
  if (!desk) return content;
  return (
    <DeskFrame title={title ?? t("thinkingdesk.new")} back>
      {content}
    </DeskFrame>
  );
}

function recordStart(args: Args | undefined) {
  return args?.action === "start" && typeof args.title !== "string";
}

function DeskFrame({
  title,
  back = false,
  children,
}: {
  title: string;
  back?: boolean;
  children: ReactNode;
}) {
  useDocumentTitle(title);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => heading.current?.focus({ preventScroll: true }), []);
  return (
    <div className={styles.frame}>
      {back && (
        <Link className={styles.back} href={root}>
          <Icon name="back" /> {deskTitle()}
        </Link>
      )}
      <h1 tabIndex={-1} ref={heading}>
        {title}
      </h1>
      {children}
    </div>
  );
}

function DeskError({ error, retry }: { error: Error; retry: () => unknown }) {
  return (
    <>
      <p className="error" role="alert">
        {error.message}
      </p>
      <button onClick={() => void retry()}>{t("app.try_again")}</button>
    </>
  );
}
