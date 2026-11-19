import { t, useLanguage } from "../lib/i18n";
import { useId, useRef, useState } from "react";
import { Link } from "wouter";
import { Icon } from "../components/core/Icon";
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
import { useUnreadWhatsNew, WhatsNew } from "./WhatsNew";
export { StatusPage } from "./SettingsStatus";
const sections = () => [
  ["account", t("accountpanel.account")],
  ["spaces", t("collaboration.spaces")],
  ["profile", t("profilepanel.profile")],
  ["appearance", t("appearancepanel.appearance")],
  ["whats-new", t("whatsnew.title")],
  ["plugins", t("settings.plugins")],
  ["devices", t("settings.devices")],
  ["tokens", t("settings.access_tokens")],
  ["agents", t("settings.agents")],
  ["ai", t("settings.ai")],
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
  const unreadWhatsNew = useUnreadWhatsNew(selected === "whats-new");
  const unreadDescriptionId = useId();
  const choicesId = useId();
  const [openedFrom, setOpenedFrom] = useState<string>();
  const chooser = useRef<HTMLButtonElement>(null);
  const expanded = openedFrom === section;
  const visible = sections().filter(
    ([id]) => (id !== "account" && id !== "spaces") || hosted,
  );
  const title = visible.find(([id]) => id === selected)?.[1];
  const closeChooser = () => {
    setOpenedFrom(undefined);
    if (chooser.current?.getClientRects().length) chooser.current.focus();
  };
  return (
    <div className={styles.settings}>
      {!(selected === "plugins" && parts[1]) && (
        <PageTitle
          page={t("settings.settings_value1", {
            value1: title ?? t("settings.unavailable"),
          })}
        />
      )}
      <nav
        className={styles.nav}
        aria-label={t("app.settings")}
        onKeyDown={(event) => {
          if (!expanded || event.key !== "Escape") return;
          event.preventDefault();
          closeChooser();
        }}
      >
        <button
          ref={chooser}
          className={styles.settingsChooser}
          type="button"
          aria-expanded={expanded}
          aria-controls={choicesId}
          aria-describedby={unreadWhatsNew ? unreadDescriptionId : undefined}
          onClick={() => setOpenedFrom(expanded ? undefined : section)}
        >
          <Icon name="tune" />
          {t("settings.browse_sections")}
          {unreadWhatsNew && (
            <span className={styles.unreadDot} aria-hidden="true" />
          )}
          <Icon name="chevron" />
        </button>
        <div
          id={choicesId}
          className={styles.settingsChoices}
          data-expanded={expanded || undefined}
        >
          {visible.map(([id, title]) => (
            <Link
              href={`/settings/${id}`}
              aria-current={id === selected ? "page" : undefined}
              aria-describedby={
                id === "whats-new" && unreadWhatsNew
                  ? unreadDescriptionId
                  : undefined
              }
              onClick={() => {
                if (expanded) closeChooser();
              }}
              key={id}
            >
              {title}
              {id === "whats-new" && unreadWhatsNew && (
                <span className={styles.unreadDot} aria-hidden="true" />
              )}
            </Link>
          ))}
        </div>
        {unreadWhatsNew && (
          <span id={unreadDescriptionId} hidden>
            {t("whatsnew.unread")}
          </span>
        )}
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
        ) : selected === "whats-new" ? (
          <WhatsNew />
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
