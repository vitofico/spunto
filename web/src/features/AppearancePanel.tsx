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
  const query = useQuery({ queryKey: ["settings"], queryFn: api.settings });
  const manifest = useQuery({ queryKey: ["manifest"], queryFn: api.manifest });
  const client = useQueryClient();
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
      <h1>Appearance</h1>
      <p className="muted">A quiet page, in your colours.</p>
      {ready && requested !== selected.theme && (
        <p className={styles.facts}>
          The saved colour pack is unavailable. Using Carta until it returns.
        </p>
      )}
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
              >
                <small>Today · 09:41</small>
                <p>Leave room for the next thought.</p>
                <span className={styles.previewLabel}>#ideas</span>
                <br />
                <span className={styles.pill}>#notebook</span>
                <span className={styles.swatches}>
                  {["paper", "ink", "accent", "accentSoft", "highlight"].map(
                    (role) => (
                      <span key={role} className={styles[role]} />
                    ),
                  )}
                </span>
              </span>
              <span className={styles.themeName}>
                {card.name}
                {selected.theme === card.id ? " ✓" : ""}
                <small>
                  {card.status === "failed"
                    ? (card.reason ?? "This pack is unavailable.")
                    : (card.description ?? "Installed colour pack")}
                </small>
              </span>
            </button>
          );
        })}
      </div>
      {!!active?.accents.length && (
        <>
          <h2>Accent</h2>
          <div className={styles.segments} role="group" aria-label="Accent">
            {[{ id: null, name: "Default" }, ...active.accents].map(
              (choice) => (
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
              ),
            )}
          </div>
        </>
      )}
      <label className={`${styles.row} ${styles.formField}`}>
        <span>Use a different theme on this device</span>
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
          Other devices use{" "}
          {cards.find((card) => card.id === instance)?.name ??
            "the instance theme"}
          .
        </p>
      )}
      <h2>Mode</h2>
      <div className={styles.segments} role="group" aria-label="Mode">
        {(["system", "light", "dark"] as const).map((mode) => (
          <button
            key={mode}
            aria-pressed={device.mode === mode}
            onClick={() => storeDevice({ ...device, mode })}
          >
            {mode[0].toUpperCase() + mode.slice(1)}
          </button>
        ))}
      </div>
      <h2>Reading face</h2>
      <div className={styles.segments} role="group" aria-label="Reading face">
        {["serif", "sans"].map((value) => (
          <button
            key={value}
            aria-pressed={face === value}
            onClick={() => {
              setFace(value);
              preference("reading-face", value);
            }}
          >
            {value[0].toUpperCase() + value.slice(1)}
          </button>
        ))}
      </div>
      <h2>Text size</h2>
      <div className={styles.segments} role="group" aria-label="Text size">
        {[
          ["s", "Small · 16"],
          ["m", "Medium · 17"],
          ["l", "Large · 19"],
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
        Mode, reading face and text size apply to this browser.
      </p>
      {(save.error ?? query.error) && (
        <p className="error" role="alert">
          {(save.error ?? query.error)?.message}
        </p>
      )}
    </>
  );
}
