# Explicit colour choices replace wallpaper colours

The colour controls use the last explicit choice. Selecting a theme, selecting
an installed pack's accent, or restoring the instance theme applies the full
selected palette and disables the wallpaper override. Making the current theme
local with **Only on this phone** also applies that palette.

Previously a wallpaper override could silently keep the same accent after a
different theme was selected. The wordmark period, header wave, labels and Ask
action all consume the shared accent, so they could appear not to follow the
selected theme.

Wallpaper colours remain supported: enabling that switch deliberately selects
Android's dynamic colours again. Background manifest refreshes, startup, account
switches and changes to mode, pure black or reading preferences do not change
the wallpaper choice. Explicit colour selection saves the palette snapshot and
wallpaper setting together; lock and capture surfaces read the same selection.
Other device settings and note content are unchanged.
