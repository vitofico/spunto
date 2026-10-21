package io.theficos.spunto.core.design

import android.os.Build
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONObject
import org.json.JSONArray
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class ThemeManifest(val catalog:ThemeCatalog,val instance:ThemePalette)
private fun JSONObject.themeString(key:String)=opt(key) as? String ?: ""
private fun JSONObject.tokenVersion()=(opt("tokens") as? Int) ?: 0
private fun JSONObject.colours(key:String)=optJSONObject(key)?.let { values -> themeColorRoles.mapNotNull { role -> (values.opt(role) as? String)?.let { role to it } }.toMap() } ?: emptyMap()
private fun JSONObject.themePalette()=validateThemePalette(ThemePalette(themeString("id"),themeString("accent").takeIf { it.isNotEmpty() },tokenVersion(),colours("light"),colours("dark")))
private fun JSONObject.themeCard():ThemeCard {
 val rawAccents=opt("accents")
 if(rawAccents!=null && rawAccents !is JSONArray) return ThemeCard(themeString("id"),themeString("name"),status="failed",reason="Theme accents must be an array.")
 val accents=mutableListOf<ThemeAccent>()
 if(rawAccents is JSONArray) for(index in 0 until rawAccents.length()) {
  val accent=rawAccents.optJSONObject(index) ?: return ThemeCard(themeString("id"),themeString("name"),status="failed",reason="Theme accent requires an object.")
  accents.add(ThemeAccent(accent.themeString("id"),accent.themeString("name"),accent.colours("light"),accent.colours("dark")))
 }
 return ThemeCard(themeString("id"),themeString("name"),themeString("status"),themeString("reason").takeIf { it.isNotBlank() },tokenVersion(),colours("light"),colours("dark"),accents)
}
/** Run off the UI thread: every installed mode and accent is checked before it can become a picker choice. */
fun decodeThemeManifest(raw:String):ThemeManifest = runCatching {
 val document=JSONObject(raw)
 val rawCatalog=document.opt("theme_catalog")
 val cards=if(rawCatalog is JSONArray) (0 until rawCatalog.length()).map { index -> rawCatalog.optJSONObject(index)?.themeCard() ?: ThemeCard("invalid-theme-$index","Unavailable colour pack",status="failed",reason="Theme card requires an object.") } else if(document.has("theme_catalog")) listOf(ThemeCard("invalid-catalog","Unavailable colour catalog",status="failed",reason="Theme catalog must be an array.")) else emptyList()
 val catalog=validatedThemeCatalog(cards,document.has("theme_catalog"))
 val selected=document.optJSONObject("theme")?.themePalette()
 val instance=resolveThemeSelection(selected?.id ?: "carta",selected?.accent,catalog,selected)
 ThemeManifest(catalog,instance)
}.getOrElse { ThemeManifest(validatedThemeCatalog(listOf(ThemeCard("invalid-catalog","Unavailable colour catalog",status="failed",reason="Theme catalog could not be read."))),resolveThemeSelection("carta",null)) }
fun themeManifestFingerprint(raw:String):String = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
data class SelectedThemeSnapshot(val overriding:Boolean,val requestedTheme:String,val requestedAccent:String?,val fingerprint:String,val palette:ThemePalette)
private fun paletteJson(palette:ThemePalette)=JSONObject().put("id",palette.id).put("accent",palette.accent ?: JSONObject.NULL).put("tokens",palette.tokens).put("light",JSONObject(palette.light)).put("dark",JSONObject(palette.dark))
fun readSelectedThemeSnapshot(preferences:SharedPreferences,fingerprint:String):ThemePalette? = runCatching {
 val saved=JSONObject(preferences.getString("theme-snapshot",null) ?: return null)
 if(saved.themeString("fingerprint")!=fingerprint || saved.opt("overriding") !is Boolean) return null
 val overriding=preferences.contains("theme")
 if(saved.getBoolean("overriding")!=overriding) return null
 if(overriding && (saved.themeString("requestedTheme")!=preferences.getString("theme",null) || saved.themeString("requestedAccent").takeIf { it.isNotEmpty() }!=preferences.getString("theme-accent",null))) return null
 val palette=saved.optJSONObject("palette")?.themePalette() ?: return null
 val requested=saved.themeString("requestedTheme")
 val requestedAccent=saved.themeString("requestedAccent").takeIf { it.isNotEmpty() }
 if(palette.id!=requested && palette.id!="carta" || palette.accent!=null && palette.accent!=requestedAccent) return null
 if(palette.id in builtInTokens) resolveThemeSelection(palette.id,null) else palette
}.getOrNull()
fun storeSelectedThemeSnapshot(preferences:SharedPreferences,snapshot:SelectedThemeSnapshot) {
 val palette=validateThemePalette(snapshot.palette) ?: return
 val requestedAccent=if(snapshot.overriding) snapshot.requestedAccent else palette.accent
 val raw=JSONObject().put("overriding",snapshot.overriding).put("requestedTheme",snapshot.requestedTheme).put("requestedAccent",requestedAccent ?: JSONObject.NULL).put("fingerprint",snapshot.fingerprint).put("palette",paletteJson(palette)).toString()
 preferences.edit().putString("theme-snapshot",raw).apply()
}
/** Theme and accent change together; reset leaves every other device and note preference intact. */
fun applyDeviceTheme(preferences:SharedPreferences,theme:String?,accent:String?,palette:ThemePalette,fingerprint:String) {
 val validated=validateThemePalette(palette) ?: return
 val requestedAccent=if(theme==null) validated.accent else accent
 val raw=JSONObject().put("overriding",theme!=null).put("requestedTheme",theme ?: validated.id).put("requestedAccent",requestedAccent ?: JSONObject.NULL).put("fingerprint",fingerprint).put("palette",paletteJson(validated)).toString()
 preferences.edit().apply {
  if(theme==null) remove("theme").remove("theme-accent") else { putString("theme",theme);if(accent==null) remove("theme-accent") else putString("theme-accent",accent) }
  putString("theme-snapshot",raw)
 }.apply()
}
@Composable private fun cachedDevicePalette(context:Context,preferences:SharedPreferences,namespace:String):ThemePalette {
 val manifestPreferences=remember(context,namespace) { context.getSharedPreferences(if(namespace.isEmpty()) "plugin-manifest" else "plugin-manifest-$namespace",Context.MODE_PRIVATE) }
 var revision by remember { mutableIntStateOf(0) }
 DisposableEffect(preferences,manifestPreferences) {
  val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_ -> revision++ }
  preferences.registerOnSharedPreferenceChangeListener(listener);manifestPreferences.registerOnSharedPreferenceChangeListener(listener)
  onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener);manifestPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
 }
 val raw=remember(manifestPreferences,revision) { manifestPreferences.getString("manifest","{}").orEmpty() }
 val fingerprint=remember(raw) { themeManifestFingerprint(raw) }
 val theme=preferences.getString("theme",null)
 val accent=preferences.getString("theme-accent",null)
 val key=Triple(fingerprint,theme,accent)
 var resolved by remember(preferences,manifestPreferences) { mutableStateOf<Pair<Triple<String,String?,String?>,ThemePalette>?>(null) }
 val snapshot=remember(preferences,revision,fingerprint) { readSelectedThemeSnapshot(preferences,fingerprint) }
 LaunchedEffect(preferences,manifestPreferences,key) {
  val manifest=withContext(Dispatchers.Default) { decodeThemeManifest(raw) }
  val palette=if(theme!=null) resolveThemeSelection(theme,accent,manifest.catalog) else manifest.instance
  resolved=key to palette
  storeSelectedThemeSnapshot(preferences,SelectedThemeSnapshot(theme!=null,theme ?: manifest.instance.id,if(theme!=null) accent else manifest.instance.accent,fingerprint,palette))
 }
 return resolved?.takeIf { it.first==key }?.second ?: snapshot ?: resolveThemeSelection(theme ?: "carta",accent)
}

fun tokenColor(value: String) = Color(value.removePrefix("#").toLong(16) or 0xff000000L)
private val LocalSpuntoTheme = compositionLocalOf { false }
private tailrec fun Context.activity():Activity? = when(this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }
fun useDarkSystemBarIcons(background:Color):Boolean = background.luminance()>0.1791288f
fun blackPalette(theme:String,values:Map<String,String>):Map<String,String> {
 val raised=when(theme) { "carta" -> "#110F0D"; "ardesia" -> "#0F1114"; "salvia" -> "#0E110F"; else -> "#"+listOf(1,3,5).joinToString("") { offset -> ((values.getValue("raised").substring(offset,offset+2).toInt(16)+1)/2).toString(16).padStart(2,'0') } }
 return values+mapOf("paper" to "#000000","sunken" to "#000000","raised" to raised)+if(theme=="carta") mapOf("rule" to "#2B2825") else emptyMap()
}
fun paletteColorScheme(values:Map<String,String>,dark:Boolean,pureBlack:Boolean=false,theme:String=""):ColorScheme {
 val palette=if(dark && pureBlack) blackPalette(theme,values) else values
 fun c(name:String)=tokenColor(palette.getValue(name))
 val paper=c("paper")
 val base=if(dark) darkColorScheme() else lightColorScheme()
 return base.copy(
  primary=c("accent"),onPrimary=c("on-accent"),primaryContainer=c("accent-soft"),onPrimaryContainer=c("accent-strong"),inversePrimary=c("paper"),
  secondary=c("accent-strong"),onSecondary=c("on-accent"),secondaryContainer=c("accent-soft"),onSecondaryContainer=c("ink"),
  tertiary=c("accent"),onTertiary=c("on-accent"),tertiaryContainer=c("accent-soft"),onTertiaryContainer=c("ink"),
  background=paper,onBackground=c("ink"),surface=paper,onSurface=c("ink"),surfaceVariant=c("sunken"),onSurfaceVariant=c("ink-2"),surfaceTint=paper,
  inverseSurface=c("ink"),inverseOnSurface=c("paper"),outline=c("field"),outlineVariant=c("rule"),
  error=c("danger"),onError=c("paper"),errorContainer=c("sunken"),onErrorContainer=c("danger"),
  surfaceContainer=c("raised"),surfaceContainerLow=c("raised"),surfaceContainerLowest=c("raised"),surfaceContainerHigh=c("sunken"),surfaceContainerHighest=c("sunken"),surfaceBright=c("raised"),surfaceDim=c("sunken"),scrim=if(dark) Color.Black.copy(alpha=.5f) else c("ink").copy(alpha=.32f)
 )
}
@Composable fun SpuntoTheme(theme: String = "", mode: String = "", pureBlack: Boolean = false, wallpaper: Boolean = false, pack: Map<String,String>? = null, readingFace:String="", readingSize:String="", namespace:String="", content: @Composable () -> Unit) {
 val context=LocalContext.current
 val preferences=remember(context,namespace) { context.getSharedPreferences(if(namespace.isEmpty()) "appearance" else "appearance-$namespace",Context.MODE_PRIVATE) }
 val cached=if(theme.isEmpty()) cachedDevicePalette(context,preferences,namespace) else null
 val selected=theme.ifEmpty { cached?.id ?: "carta" }
 val selectedMode=mode.ifEmpty { preferences.getString("mode","system").orEmpty() }
 val dark=selectedMode == "dark" || selectedMode == "system" && isSystemInDarkTheme()
 val cachedPalette=cached?.mode(dark)
 val values=resolveThemePalette(selected,dark,pack ?: cachedPalette)
 fun c(name:String)=tokenColor(values.getValue(name))
 val black=dark && (pureBlack || theme.isEmpty() && preferences.getBoolean("black",false))
 val scheme=paletteColorScheme(values,dark,black,selected)
 val dynamic=if((wallpaper || theme.isEmpty() && preferences.getBoolean("wallpaper",false)) && Build.VERSION.SDK_INT >= 31) {
  val d=if(dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
  val neutral=d.copy(onSurface=c("ink"),onBackground=c("ink"),onSurfaceVariant=c("ink-2"),outline=c("field"),outlineVariant=scheme.outlineVariant,scrim=scheme.scrim,surfaceTint=d.surface)
  if(black) neutral.copy(background=Color.Black,surface=Color.Black,surfaceTint=Color.Black,surfaceVariant=Color.Black,surfaceContainer=scheme.surfaceContainer,surfaceContainerLowest=scheme.surfaceContainerLowest,surfaceContainerLow=scheme.surfaceContainerLow,surfaceContainerHigh=Color.Black,surfaceContainerHighest=Color.Black,surfaceBright=scheme.surfaceBright,surfaceDim=Color.Black) else neutral
 } else scheme
 val view=LocalView.current
 if(!LocalSpuntoTheme.current && !view.isInEditMode) SideEffect {
  context.activity()?.window?.let { window -> WindowCompat.getInsetsController(window,view).apply {
   isAppearanceLightStatusBars=useDarkSystemBarIcons(dynamic.background)
   isAppearanceLightNavigationBars=useDarkSystemBarIcons(dynamic.background)
  } }
 }
 val face=readingFace.ifEmpty { preferences.getString("reading-face","serif").orEmpty() };val size=readingSize.ifEmpty { preferences.getString("reading-size","M").orEmpty() }
 val type=remember(face,size) { spuntoType(face,size) }
 val roles=remember(values,selected,dark) { spuntoColorRoles(values,builtInTokens[selected]?.get(if(dark) "dark" else "light")) }
 CompositionLocalProvider(LocalSpuntoTheme provides true,LocalSpuntoType provides type,LocalSpuntoColors provides roles) {
  MaterialTheme(colorScheme=dynamic,typography=spuntoMaterialTypography,content=content)
 }
}
