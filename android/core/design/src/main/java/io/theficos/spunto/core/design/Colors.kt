package io.theficos.spunto.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Palette roles Material's ColorScheme has no slot for. Read them with `SpuntoColors.ink3` and so on. */
@Immutable data class SpuntoColorRoles(
 /** Meta, placeholders, counts. */
 val ink3:Color,
 /** Link underlines, dividers in menus (decorative). */
 val ruleStrong:Color,
 /** Search match background; `ink` text on it. */
 val highlight:Color,
 /** Conflict dot, always next to a word. */
 val warn:Color,
 /** The frame and address pill drawn around theme previews. */
 val chrome:Color,
 val chromePill:Color,
)
/** Only built-in themes define the preview frame, so packs fall back to their own `sunken` and `paper`. */
fun spuntoColorRoles(values:Map<String,String>,builtIn:Map<String,String>?=null):SpuntoColorRoles {
 fun c(name:String)=tokenColor(values.getValue(name))
 fun frame(name:String,fallback:String)=(values[name] ?: builtIn?.get(name))?.let(::tokenColor) ?: c(fallback)
 return SpuntoColorRoles(c("ink-3"),c("rule-strong"),c("highlight"),c("warn"),frame("chrome","sunken"),frame("chrome-pill","paper"))
}
val LocalSpuntoColors=staticCompositionLocalOf { spuntoColorRoles(builtInTokens.getValue("carta").getValue("light")) }
val SpuntoColors:SpuntoColorRoles @Composable @ReadOnlyComposable get()=LocalSpuntoColors.current
