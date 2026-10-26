import {
  readBackgroundStyle,
  backgroundStyleKey,
  saveBackgroundStyle,
  type BackgroundStyle,
} from "../lib/background-style";
import { LanguagePicker } from "../components/core/LanguagePicker";
import { t, useLanguage } from "../lib/i18n";
import { useEffect, useState, type CSSProperties } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/api";
import { currentOwner } from "../lib/query";
import type { Preferences, Session } from "../lib/types";
import {
  readDeviceAppearance,
  applyAppearance,
  appearanceCards,
  appearanceReady,
  resolvedAppearance,
  type DeviceAppearance,
} from "./Appearance";
import { colorRoles } from "../../scripts/theme-contrast.mjs";
import styles from "./Feature.module.css";
import "../styles/theme-previews.css";

export function AppearancePanel() {
  useLanguage();
  const query = useQuery({ queryKey: ["settings"], queryFn: api.settings });
  const manifest = useQuery({ queryKey: ["manifest"], queryFn: api.manifest });
  const client = useQueryClient();
  const [background, setBackground] = useState(readBackgroundStyle);
  useEffect(() => {
    const changed = (event: StorageEvent) => {
      if (event.key === backgroundStyleKey || event.key === null)
        setBackground(readBackgroundStyle());
    };
    window.addEventListener("storage", changed);
    return () => window.removeEventListener("storage", changed);
  }, []);
  const [device, setDevice] = useState(readDeviceAppearance);
  const [face, setFace] = useState(
    () =>
      device.reading ?? localStorage.getItem("spunto-reading-face") ?? "serif",
  );
  const [size, setSize] = useState(
    () => device.size ?? localStorage.getItem("spunto-text-size") ?? "m",
  );
  const [systemDark, setSystemDark] = useState(
    () => matchMedia("(prefers-color-scheme: dark)").matches,
  );
  const cards = appearanceCards(manifest.data);
  const instance = query.data?.theme ?? "carta";
  const requested = device.theme ?? instance;
  const ready = appearanceReady(requested, manifest.data);
  const selected = resolvedAppearance(
    requested,
    device.theme ? device.accent : query.data?.theme_accent,
    cards,
  );
  const active = cards.find((card) => card.id === selected.theme);
  const save = useMutation({
    mutationFn: async (patch: Partial<Preferences>) => {
      const owner = currentOwner();
      const session = client.getQueryData<Session>(["session"])?.credential_id;
      const data = await api.saveSettings(patch);
      return { data, owner, session };
    },
    onSuccess: ({ data, owner, session }) => {
      if (
        !owner ||
        owner !== currentOwner() ||
        session !== client.getQueryData<Session>(["session"])?.credential_id
      )
        return;
      client.setQueryData(["settings"], data);
    },
  });
  useEffect(() => {
    if (query.data && ready)
      applyAppearance(
        selected.theme,
        device.mode,
        selected.accent,
        manifest.data?.plugins_rev,
      );
    else document.documentElement.dataset.mode = device.mode;
  }, [
    query.data,
    manifest.data,
    ready,
    selected.theme,
    selected.accent,
    device.mode,
  ]);
  const storeDevice = (next: DeviceAppearance) => {
    document.cookie = `theme=${encodeURIComponent(JSON.stringify(next))}; Path=/; SameSite=Lax; Max-Age=31536000${location.protocol === "https:" ? "; Secure" : ""}`;
    setDevice(next);
    const choice = resolvedAppearance(
      next.theme ?? instance,
      next.theme ? next.accent : query.data?.theme_accent,
      cards,
    );
    if (appearanceReady(next.theme ?? instance, manifest.data))
      applyAppearance(
        choice.theme,
        next.mode,
        choice.accent,
        manifest.data?.plugins_rev,
      );
    else document.documentElement.dataset.mode = next.mode;
  };
  useEffect(() => {
    const media = matchMedia("(prefers-color-scheme: dark)");
    const changed = () => setSystemDark(media.matches);
    media.addEventListener("change", changed);
    return () => media.removeEventListener("change", changed);
  }, []);
  const preference = (key: "reading-face" | "text-size", value: string) => {
    localStorage.setItem(`spunto-${key}`, value);
    document.documentElement.dataset[
      key === "reading-face" ? "reading" : "size"
    ] = value;
    storeDevice({
      ...device,
      [key === "reading-face" ? "reading" : "size"]: value,
    });
  };
  const dark =
    device.mode === "dark" || (device.mode === "system" && systemDark);
  return (
    <>
      <h1>{t("appearancepanel.appearance")}</h1>
      <LanguagePicker />
      <p className="muted">
        {t("appearancepanel.a_quiet_page_in_your_colours")}
      </p>
      {ready && requested !== selected.theme && (
        <p className={styles.facts}>
          {t(
            "appearancepanel.the_saved_colour_pack_is_unavailable_using_carta",
          )}
        </p>
      )}
      <h2>{t("appearance.background_style")}</h2>
      <p className="muted">{t("appearance.background_help")}</p>
      <div
        className="background-choices"
        role="group"
        aria-label={t("appearance.background_style")}
      >
        {(["plain", "artwork"] as const).map((style: BackgroundStyle) => (
          <button
            type="button"
            key={style}
            aria-pressed={background === style}
            className="background-choice"
            onClick={() => {
              setBackground(style);
              saveBackgroundStyle(style);
            }}
          >
            <span
              aria-hidden="true"
              className={`background-preview ${style}`}
            />
            {t(`appearance.background_${style}`)}
          </button>
        ))}
      </div>
      <h2>{t("appearance.colour_theme")}</h2>
      <div className={styles.themes}>
        {cards.map((card) => {
          const palette = dark ? card.dark : card.light;
          const colors = palette
            ? (Object.fromEntries(
                colorRoles.map((role) => [`--${role}`, palette[role]]),
              ) as CSSProperties)
            : undefined;
          return (
            <button
              className={styles.theme}
              key={card.id}
              aria-label={card.name}
              aria-describedby={`theme-${card.id}-about`}
              aria-pressed={selected.theme === card.id}
              disabled={save.isPending || card.status !== "available"}
              onClick={() =>
                device.theme
                  ? storeDevice({
                      ...device,
                      theme: card.id,
                      accent: card.id === device.theme ? device.accent : null,
                    })
                  : save.mutate({ theme: card.id })
              }
            >
              <span
                className={styles.preview}
                data-theme-preview={card.id}
                style={colors}
                aria-hidden="true"
              ></span>
              <span className={styles.themeName}>
                {card.name}
                {selected.theme === card.id ? " ✓" : ""}
                <small id={`theme-${card.id}-about`}>
                  {card.status === "failed"
                    ? (card.reason ??
                      t("appearancepanel.this_pack_is_unavailable"))
                    : (card.description ??
                      t("appearance.installed_colour_pack"))}
                </small>
              </span>
            </button>
          );
        })}
      </div>
      {!!active?.accents.length && (
        <>
          <h2>{t("appearancepanel.accent")}</h2>
          <div
            className={styles.segments}
            role="group"
            aria-label={t("appearancepanel.accent")}
          >
            {[
              { id: null, name: t("appearancepanel.default") },
              ...active.accents,
            ].map((choice) => (
              <button
                key={choice.id ?? "default"}
                aria-pressed={selected.accent === choice.id}
                disabled={save.isPending}
                onClick={() =>
                  device.theme
                    ? storeDevice({ ...device, accent: choice.id })
                    : save.mutate({ theme_accent: choice.id })
                }
              >
                {choice.name}
              </button>
            ))}
          </div>
        </>
      )}
      <label className={`${styles.row} ${styles.formField}`}>
        <span>{t("appearancepanel.use_a_different_theme_on_this_device")}</span>
        <input
          className={styles.switch}
          type="checkbox"
          role="switch"
          checked={!!device.theme}
          onChange={(event) =>
            storeDevice(
              event.target.checked
                ? { ...device, theme: selected.theme, accent: selected.accent }
                : { ...device, theme: undefined, accent: undefined },
            )
          }
        />
      </label>
      {device.theme && (
        <p className={styles.facts}>
          {t("appearancepanel.other_devices_theme", {
            theme:
              cards.find((card) => card.id === instance)?.name ??
              t("appearancepanel.the_instance_theme"),
          })}
        </p>
      )}
      <h2>{t("appearancepanel.mode")}</h2>
      <div
        className={styles.segments}
        role="group"
        aria-label={t("appearancepanel.mode")}
      >
        {(["system", "light", "dark"] as const).map((mode) => (
          <button
            key={mode}
            aria-pressed={device.mode === mode}
            onClick={() => storeDevice({ ...device, mode })}
          >
            {t(`appearance.mode.${mode}`)}
          </button>
        ))}
      </div>
      <h2>{t("appearancepanel.reading_face")}</h2>
      <div
        className={styles.segments}
        role="group"
        aria-label={t("appearancepanel.reading_face")}
      >
        {["serif", "sans"].map((value) => (
          <button
            key={value}
            aria-pressed={face === value}
            onClick={() => {
              setFace(value);
              preference("reading-face", value);
            }}
          >
            {t(`appearance.reading.${value}`)}
          </button>
        ))}
      </div>
      <h2>{t("appearancepanel.text_size")}</h2>
      <div
        className={styles.segments}
        role="group"
        aria-label={t("appearancepanel.text_size")}
      >
        {[
          ["s", t("appearancepanel.small_16")],
          ["m", t("appearancepanel.medium_17")],
          ["l", t("appearancepanel.large_19")],
        ].map(([value, label]) => (
          <button
            key={value}
            aria-pressed={size === value}
            onClick={() => {
              setSize(value);
              preference("text-size", value);
            }}
          >
            {label}
          </button>
        ))}
      </div>
      <p className={styles.facts}>
        {t("appearancepanel.mode_reading_face_and_text_size_apply_to")}
      </p>
      {(save.error ?? query.error) && (
        <p className="error" role="alert">
          {(save.error ?? query.error)?.message}
        </p>
      )}
    </>
  );
}
