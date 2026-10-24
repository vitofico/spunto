package io.theficos.spunto

import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/** Real navigation and palette callbacks; only guarded local appearance keys are changed. */
class AppearanceLiveThemeTest {
 @get:Rule val compose=createComposeRule()
 @get:Rule val timeout=Timeout(10,TimeUnit.MINUTES)
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val touched=setOf("theme","theme-accent","theme-snapshot","wallpaper")

 @Test fun selectingEveryPaletteReplacesWallpaperLiveAndInstanceResetDoesTheSame() {
  assumeTrue(Build.VERSION.SDK_INT>=31)
  val app=context.applicationContext as SpuntoApplication
  val model=SpuntoViewModel(app)
  val authority=try {runBlocking {LocalFixtureAuthority.require(model.container)}} catch(error:Exception) {model.retire();throw error}
  val preferences=model.container.preferences
  val original=preferences.all.filterKeys {it in touched}
  val untouched=preferences.all.filterKeys {it !in touched}
  val capture=runBlocking {model.repository.dao.draftSnapshot("capture")}
  val mode=preferences.getString("mode","system")
  val dark=mode=="dark" || mode=="system" && (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES
  val paletteMode=if(dark) "dark" else "light"
  val mounted=mutableStateOf(false)
  var attached=false
  fun guarded()=runBlocking {authority.requireCurrent()}
  try {
   guarded()
   check(preferences.edit().putString("theme","carta").remove("theme-accent").remove("theme-snapshot").putBoolean("wallpaper",true).commit())
   compose.setContent {if(mounted.value) SpuntoApp(model)}
   attached=true
   compose.runOnIdle {mounted.value=true}
   compose.waitUntil(10_000) {compose.onAllNodesWithContentDescription(context.getString(R.string.core_settings)).fetchSemanticsNodes().isNotEmpty()}
   for(choice in builtInThemes) {
    guarded();openAppearance()
    val wallpaper=wallpaperRow()
    // Repeat the original bug trigger for every palette, including the already selected Carta.
    if(!preferences.getBoolean("wallpaper",false)) wallpaper.assertIsOff().performClick()
    wallpaperRow().assertIsOn()
    compose.onNode(hasScrollAction()).performScrollToNode(hasText(choice.name) and hasClickAction())
    compose.onNodeWithText(choice.name).performClick()
    compose.waitUntil(5000) {preferences.getString("theme",null)==choice.id && !preferences.getBoolean("wallpaper",true)}
    wallpaperRow().assertIsOff()
    backToTimeline()
    assertTimelineAccent(Color(android.graphics.Color.parseColor(builtInTokens.getValue(choice.id).getValue(paletteMode).getValue("accent"))))
   }
   guarded();openAppearance()
   wallpaperRow().assertIsOff().performClick()
   compose.waitUntil(5000) {preferences.getBoolean("wallpaper",false)}
   backToTimeline()
   assertTimelineAccent((if(dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary)
   guarded();openAppearance()
   compose.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(R.string.settings_only_on_this_phone)))
   compose.onNodeWithText(context.getString(R.string.settings_only_on_this_phone)).assertIsOn().performClick()
   compose.waitUntil(5000) {!preferences.contains("theme") && !preferences.contains("theme-accent") && !preferences.getBoolean("wallpaper",true)}
   wallpaperRow().assertIsOff()
   backToTimeline()
   val instance=decodeThemeManifest(model.manifest.value.toString()).instance
   assertTimelineAccent(Color(android.graphics.Color.parseColor(instance.mode(dark).getValue("accent"))))
   assertEquals(untouched,preferences.all.filterKeys {it !in touched})
  } finally {
   // Retire this non-editing composer before restoring preferences. Do not overwrite concurrent draft words.
   try {
    val fence=model.repository.beginEditor(null,"capture")
    try {if(attached) {compose.runOnIdle {mounted.value=false};compose.waitForIdle()}}
    finally {model.repository.abandonEditor(fence)}
   } finally {
    try {
     guarded()
     restorePreferences(preferences,original)
     runBlocking {
      val current=model.repository.dao.draftSnapshot("capture")
      assertEquals("Navigation must preserve existing capture text",capture?.body.orEmpty(),current?.body.orEmpty())
      if(capture==null && current!=null && current.body.isEmpty()) model.repository.dao.clearDraftSnapshot(current.id,current.body,current.updatedAt)
     }
    } finally {model.retire()}
   }
  }
 }

 private fun openAppearance() {
  closeSoftKeyboard()
  compose.onNodeWithContentDescription(context.getString(R.string.core_settings)).performClick()
  val appearance=context.getString(R.string.settings_appearance)
  compose.onNode(hasScrollAction()).performScrollToNode(hasText(appearance) and hasClickAction())
  compose.onNodeWithText(appearance).performClick()
 }
 private fun wallpaperRow():SemanticsNodeInteraction {
  val title=context.getString(R.string.settings_wallpaper_colours)
  compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
  return compose.onNodeWithText(title)
 }
 private fun backToTimeline() {
  repeat(2) {compose.onNodeWithContentDescription(context.getString(R.string.core_back)).performClick()}
  closeSoftKeyboard()
  compose.waitUntil(5000) {compose.onAllNodesWithText("spunto.").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("spunto.").assertIsDisplayed()
 }
 private fun assertTimelineAccent(expected:Color) {
  val layouts=mutableListOf<TextLayoutResult>()
  compose.onNodeWithText("spunto.").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
  assertEquals(1,layouts.size)
  assertEquals(expected,layouts.single().layoutInput.text.spanStyles.single {it.start==6 && it.end==7}.item.color)
 }
 private fun restorePreferences(preferences:SharedPreferences,original:Map<String,*>) {
  val editor=preferences.edit()
  touched.forEach {key ->
   editor.remove(key)
   when(val value=original[key]) {
    null -> Unit
    is String -> editor.putString(key,value)
    is Boolean -> editor.putBoolean(key,value)
    is Int -> editor.putInt(key,value)
    is Long -> editor.putLong(key,value)
    is Float -> editor.putFloat(key,value)
    else -> error("Unexpected appearance preference type")
   }
  }
  check(editor.commit())
 }
}
