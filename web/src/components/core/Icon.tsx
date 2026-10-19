import type { CSSProperties } from "react";
const glyphs = {
  menu: <path d="M4.5 6.5h15M4.5 12h15M4.5 17.5h15" />,
  search: (
    <>
      <circle cx="10.5" cy="10.5" r="6" />
      <path d="M15 15l5 5" />
    </>
  ),
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
  notes: (
    <>
      <path d="M5 6.5h14M5 12h14M5 17.5h9" />
    </>
  ),
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
};
export function Icon({
  name,
  style,
}: {
  name: keyof typeof glyphs;
  style?: CSSProperties;
}) {
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
