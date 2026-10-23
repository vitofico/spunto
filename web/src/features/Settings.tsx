import { t, useLanguage } from "../lib/i18n";
import { useEffect, useRef } from "react";
import { Link } from "wouter";
import { AppearancePanel } from "./AppearancePanel";
import { ProfilePanel } from "./ProfilePanel";
import { AccountPanel } from "./AccountPanel";
import { useHosted } from "../lib/query";
import styles from "./Feature.module.css";
import { AISettings } from "./AISettings";
import { SpacesPanel } from "./Collaboration";
import { PageTitle } from "./SettingsParts";
import { PluginManager, PluginPage } from "./SettingsPlugins";
import { Devices, Tokens, Agents } from "./SettingsAccess";
import { Data } from "./SettingsData";
export { StatusPage } from "./SettingsStatus";
const sections = () => [
  ["account", t("accountpanel.account")],
  ["spaces", t("collaboration.spaces")],
  ["profile", t("profilepanel.profile")],
  ["appearance", t("appearancepanel.appearance")],
  ["plugins", t("settings.plugins")],
  ["devices", t("settings.devices")],
  ["tokens", t("settings.access_tokens")],
  ["agents", t("settings.agents")],
  ["ai", "AI"],
  ["data", t("settings.data")],
];
export function SettingsPage({
  section = "appearance",
  onShowGuide,
}: {
  section?: string;
  onShowGuide?: (trigger: HTMLElement) => void;
}) {
  useLanguage();
  const hosted = useHosted();
  const parts = section.replace(/^\//, "").split("/");
  const selected = parts[0] || "appearance";
  const visible = sections().filter(
    ([id]) => (id !== "account" && id !== "spaces") || hosted,
  );
  const title = visible.find(([id]) => id === selected)?.[1];
  const navRef = useRef<HTMLElement>(null);
  useEffect(() => {
    const revealSelected = () => {
      const nav = navRef.current;
      const active = nav?.querySelector('a[aria-current="page"]');
      if (!nav || !active || nav.scrollWidth <= nav.clientWidth) return;
      const container = nav.getBoundingClientRect();
      const item = active.getBoundingClientRect();
      if (item.left < container.left + 4)
        nav.scrollLeft += item.left - container.left - 4;
      else if (item.right > container.right - 4)
        nav.scrollLeft += item.right - container.right + 4;
    };
    revealSelected();
    window.addEventListener("resize", revealSelected);
    return () => window.removeEventListener("resize", revealSelected);
  }, [selected, hosted]);
  return (
    <div className={styles.settings}>
      {!(selected === "plugins" && parts[1]) && (
        <PageTitle
          page={t("settings.settings_value1", {
            value1: title ?? t("settings.unavailable"),
          })}
        />
      )}
      <nav ref={navRef} className={styles.nav} aria-label={t("app.settings")}>
        {visible.map(([id, title]) => (
          <Link
            href={`/settings/${id}`}
            aria-current={id === selected ? "page" : undefined}
            key={id}
          >
            {title}
          </Link>
        ))}
      </nav>
      <section className={styles.section}>
        {selected === "account" && hosted ? (
          <AccountPanel />
        ) : selected === "spaces" && hosted ? (
          <SpacesPanel />
        ) : selected === "profile" ? (
          <ProfilePanel onShowGuide={onShowGuide} />
        ) : selected === "appearance" ? (
          <AppearancePanel />
        ) : selected === "plugins" ? (
          parts[1] ? (
            <PluginPage id={parts[1]} />
          ) : (
            <PluginManager />
          )
        ) : selected === "devices" ? (
          <Devices />
        ) : selected === "tokens" ? (
          <Tokens />
        ) : selected === "agents" ? (
          <Agents />
        ) : selected === "ai" ? (
          <AISettings />
        ) : selected === "data" ? (
          <Data />
        ) : (
          <>
            <h1>{t("app.settings")}</h1>
            <p>{t("settings.that_settings_section_is_unavailable")}</p>
          </>
        )}
      </section>
    </div>
  );
}
