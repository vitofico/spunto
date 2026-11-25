import { useLanguage } from "../../lib/i18n";
import type { CSSProperties } from "react";
const glyphs = {
  bell: (
    <path d="M5 16.5h14l-1.8-2.6V10a5.2 5.2 0 0 0-10.4 0v3.9L5 16.5ZM10 19a2.2 2.2 0 0 0 4 0" />
  ),
  menu: <path d="M4.5 6.5h15M4.5 12h15M4.5 17.5h15" />,
  search: (
    <>
      <circle cx="10.5" cy="10.5" r="6" />
      <path d="M15 15l5 5" />
    </>
  ),
  ask: (
    <path d="M20 10.5a7.5 7.5 0 0 1-7.5 7.5H9l-4 3v-5a7.5 7.5 0 0 1 5.5-13h2a7.5 7.5 0 0 1 7.5 7.5ZM8 10.5h.1m3.9 0h.1m3.9 0h.1" />
  ),
  width: <path d="M4 5v14M20 5v14M7.5 12h9M10 9l-3 3 3 3M14 9l3 3-3 3" />,
  stats: <path d="M4 4v16h16M8 16v-4m5 4V8m5 8V5" />,
  hash: (
    <>
      <path d="M9.8 4.5l-2 15M16.2 4.5l-2 15M5.5 9.3h14M4.5 14.7h14" />
    </>
  ),
  cbox: (
    <>
      <rect x="4" y="4" width="16" height="16" rx="4" />
      <path d="M8.5 12.2l2.4 2.4 4.6-5" />
    </>
  ),
  image: (
    <>
      <rect x="3.5" y="4.5" width="17" height="15" rx="3" />
      <circle cx="9" cy="10" r="1.6" />
      <path d="M20 15.5l-4.5-4.5L7 19.5" />
    </>
  ),
  person: (
    <>
      <circle cx="12" cy="7.5" r="3.5" />
      <path d="M5 20v-2a7 7 0 0 1 14 0v2" />
    </>
  ),
  notes: (
    <>
      <path d="M5 6.5h14M5 12h14M5 17.5h9" />
    </>
  ),
  add: <path d="M12 5v14M5 12h14" />,
  send: <path d="M12 19V5m-6 6 6-6 6 6" />,
  archive: (
    <>
      <rect x="3.5" y="4.5" width="17" height="4" rx="1.2" />
      <path d="M5 8.5v9.5a1.5 1.5 0 0 0 1.5 1.5h11a1.5 1.5 0 0 0 1.5-1.5V8.5M10 12.5h4" />
    </>
  ),
  trash: (
    <>
      <path d="M4.5 7h15M9.5 7V4.5h5V7M6.5 7l.9 12.5h9.2L17.5 7" />
    </>
  ),
  tune: (
    <>
      <path d="M4 7.5h9M17 7.5h3M4 16.5h3M11 16.5h9" />
      <circle cx="15" cy="7.5" r="2" />
      <circle cx="9" cy="16.5" r="2" />
    </>
  ),
  close: (
    <>
      <path d="M6.5 6.5l11 11M17.5 6.5l-11 11" />
    </>
  ),
  back: (
    <>
      <path d="M19.5 12h-15M10.5 6l-6 6 6 6" />
    </>
  ),
  chevron: <path d="m6 9 6 6 6-6" />,
  more: (
    <>
      <circle cx="6" cy="12" r="1.5" fill="currentColor" stroke="none" />
      <circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none" />
      <circle cx="18" cy="12" r="1.5" fill="currentColor" stroke="none" />
    </>
  ),
  lock: (
    <>
      <rect x="5" y="10.5" width="14" height="9.5" rx="2.5" />
      <path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5" />
    </>
  ),
  pin: (
    <>
      <path d="M9 4h6M10 4v5.5L7 13.5h10L14 9.5V4M12 13.5V20" />
    </>
  ),
  edit: (
    <>
      <path d="M4.5 19.5h3.8L19 8.8a2 2 0 0 0 0-2.8l-1-1a2 2 0 0 0-2.8 0L4.5 15.7z" />
      <path d="M13.5 6.5l4 4" />
    </>
  ),
  tools: (
    <path d="M12 3.5 14.3 9.7 20.5 12 14.3 14.3 12 20.5 9.7 14.3 3.5 12 9.7 9.7z" />
  ),
  link: (
    <>
      <path d="m9 15 6-6m-5.8 8.8-1 1a3.5 3.5 0 0 1-5-5l4-4a3.5 3.5 0 0 1 5 0m2.6-3.6 1-1a3.5 3.5 0 0 1 5 5l-4 4a3.5 3.5 0 0 1-5 0" />
    </>
  ),
  audio: <path d="M4 10v4m4-8v12m4-15v18m4-15v12m4-8v4" />,
  translate: (
    <path d="M3 6h11M8.5 3.5V6m3 0c0 5-4 8-8 10m2-7c1 3 3 5 6 7m2.5 4 3.5-9 3.5 9m-5.8-3h4.6" />
  ),
  branch: (
    <>
      <circle cx="6" cy="6" r="2" />
      <circle cx="6" cy="18" r="2" />
      <circle cx="18" cy="6" r="2" />
      <path d="M6 8v8m12-8c0 6-12 2-12 8" />
    </>
  ),
};
export type IconName = keyof typeof glyphs;
export function Icon({
  name,
  style,
}: {
  name: IconName;
  style?: CSSProperties;
}) {
  useLanguage();
  return (
    <svg
      className="icon"
      style={style}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {glyphs[name]}
    </svg>
  );
}
