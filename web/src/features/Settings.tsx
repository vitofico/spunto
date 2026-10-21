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
const sections = [
  ["account", "Account"],
  ["spaces", "Spaces"],
  ["profile", "Profile"],
  ["appearance", "Appearance"],
  ["plugins", "Plugins"],
  ["devices", "Devices"],
  ["tokens", "Access tokens"],
  ["agents", "Agents"],
  ["ai", "AI"],
  ["data", "Data"],
];
export function SettingsPage({
  section = "appearance",
  onShowGuide,
}: {
  section?: string;
  onShowGuide?: (trigger: HTMLElement) => void;
}) {
  const hosted = useHosted();
  const parts = section.replace(/^\//, "").split("/");
  const selected = parts[0] || "appearance";
  const visible = sections.filter(
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
        <PageTitle page={`Settings · ${title ?? "Unavailable"}`} />
      )}
      <nav ref={navRef} className={styles.nav} aria-label="Settings">
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
            <h1>Settings</h1>
            <p>That settings section is unavailable.</p>
          </>
        )}
      </section>
    </div>
  );
}
