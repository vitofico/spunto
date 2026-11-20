import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useLocation } from "wouter";
import { api } from "../lib/api";
import { t, useLanguage } from "../lib/i18n";
import { activePlugins, usePlugins } from "./Slots";
import styles from "./Feature.module.css";

export default function PluginRailViews({ close }: { close?: () => void }) {
  useLanguage();
  const manifest = usePlugins();
  const settings = useQuery({ queryKey: ["settings"], queryFn: api.settings });
  const [location] = useLocation();
  const client = useQueryClient();
  const [more, setMore] = useState(false);
  const pin = useMutation({
    mutationFn: (id: string) =>
      api.saveSettings({
        hidden_views: settings.data?.hidden_views.filter((v) => v !== id) ?? [],
      }),
    onSuccess: () => void client.invalidateQueries({ queryKey: ["settings"] }),
  });
  const views = activePlugins(manifest.data?.plugins).flatMap((p) =>
    (p.contributes?.views ?? [])
      .slice(0, 1)
      .map((v) => ({ ...v, plugin: p.id, key: `${p.id}/${v.id}` })),
  );
  const review = activePlugins(manifest.data?.plugins).some((plugin) =>
    ["duplicates", "label-gardener"].includes(plugin.id),
  );
  if (!views.length && !review) return null;
  const href = (v: (typeof views)[number]) =>
    `/v/${encodeURIComponent(v.plugin)}/${encodeURIComponent(v.id)}`;
  return (
    <nav className={styles.railGroup} aria-label={t("slots.views")}>
      <h3>{t("slots.views")}</h3>
      {review && (
        <Link
          href="/review"
          onClick={close}
          aria-current={location === "/review" ? "page" : undefined}
        >
          {t("review.review")}
        </Link>
      )}
      {views
        .filter((v) => !settings.data?.hidden_views.includes(v.key))
        .map((v) => (
          <Link
            key={v.key}
            href={href(v)}
            onClick={close}
            aria-current={location === href(v) ? "page" : undefined}
          >
            {v.title}
          </Link>
        ))}
      {views.some((v) => settings.data?.hidden_views.includes(v.key)) && (
        <>
          <button onClick={() => setMore(!more)} aria-expanded={more}>
            {t(more ? "plugins.show_fewer_views" : "plugins.show_more_views")}
          </button>
          {more &&
            views
              .filter((v) => settings.data?.hidden_views.includes(v.key))
              .map((v) => (
                <div key={v.key} className={styles.row}>
                  <Link
                    href={href(v)}
                    onClick={close}
                    aria-current={location === href(v) ? "page" : undefined}
                  >
                    {v.title}
                  </Link>
                  <button
                    onClick={() => pin.mutate(v.key)}
                    disabled={pin.isPending}
                  >
                    {t("slots.pin")}
                  </button>
                </div>
              ))}
        </>
      )}
    </nav>
  );
}
