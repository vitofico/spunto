# Wave in the header background

The wave belongs to Spunto's upper app chrome. Place it around the status and
header edge, fading into the selected paper colour before note content. Artwork
does not reserve a separate banner row.

This refines the [existing wave identity](2026-10-24-wave-design.md). Preserve the
established native wave component and web SVG, and keep the lowercase `spunto.`
wordmark with its accent-coloured period. Historical design documents 01–05 remain
unchanged.

## Layout and interaction

- The Android timeline uses compact functional chrome. Keep the list/label
  selector, Ask, search, filters, Settings and plugin actions reachable. Narrow
  windows and enlarged text may use two functional rows to retain 48dp targets.
- Web keeps the existing rail and compact top-bar layout. The wave is a
  decorative upper backdrop with no pointer or focus behaviour.
- Notes, reading typography, capture fields and editing surfaces stay clear of
  the header artwork. Preserve keyboard, cutout, modal and navigation handling.

## Colour and accessibility

Use the resolved theme accent and surface for built-in palettes, installed packs
and custom accents. Android also follows wallpaper colours and its pure-black
setting; web follows the resolved paper token, including black-paper packs.
Theme changes must update the backdrop immediately without changing the user's
other preferences, following the existing
[colour-choice precedence](2026-10-24-theme-colour-precedence.md).

Protect header text and controls from the decorative wave. Accepted theme text
must retain its required contrast; in particular, accent-coloured Ask text must
keep its original paper background. System icons, focus indicators and interactive
targets must remain clear. The backdrop has no screen-reader content or motion.

Android limits the opacity of the whole wave layer, including overlapping
strands, to protect status text and icons. Reduce or omit that decoration for
palettes at the contrast limit, allowing for the rendered pixel precision.

## Verification

Check actual Android and browser renders in light and dark, across the built-in
palettes and supported appearance overrides. Include compact widths, enlarged
text, RTL, keyboard editing, navigation and long labels. Compare the first note's
position with the baseline to ensure the artwork adds no vertical space.

Use synthetic notes and disposable local environments for acceptance. Capture
before/after evidence and run the affected client checks and independent review.
