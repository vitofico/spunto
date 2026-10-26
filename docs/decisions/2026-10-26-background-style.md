# Background style independent of colour

The family identity adds a static ink-wave margin to Spunto. Existing colour
palettes, installed packs, custom accents, light/dark mode and wallpaper colours
remain available. Artwork uses the resolved accent instead of a fixed brand colour.

Appearance offers **Plain** and **Artwork** with previews. The default for an
unset or invalid preference is Artwork. The choice is stored locally, separately
from colour, mode and reading preferences; it is not sent to the server. Plain
removes ambient decoration and retains the wordmark and welcome illustration.

Browsing and settings chrome, authentication margins and genuine loading screens
share the same static geometry. Note prose and fields retain opaque paper.
The note reader/editor stays clear. There is no timer, animated wallpaper or
artificial loading delay. Native biometric authentication remains system owned.

The canonical engraved artwork lives in `branding/`, with its untouched source,
compressed WebP and SVG tonal-density mask. `scripts/sync-ink-artwork.py` copies
identical compressed exports to web and Android; `--check` detects drift. The
mask uses original alpha multiplied by one minus source luminance, retaining
irregular line weights, dry-print breaks and pigment wash before semantic tint.
Android constructs and caches that same alpha mask once, then applies the final
opacity after tonal conversion. The rejected uniform Bézier background is removed.
This is texture within the artwork, not a generic grain overlay on the app.
