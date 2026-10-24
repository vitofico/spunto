package io.theficos.spunto

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.design.builtInThemes
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Isolated presentation fixtures; authentication, accounts and saved notes are never accessed. */
class UnlockScreenTest {
 @get:Rule val compose=createComposeRule()
 private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
 private val base get()=instrumentation.targetContext

 private fun preview(name:String) {
  if(InstrumentationRegistry.getArguments().getString("spuntoUnlockScreenshots")!="yes") return
  compose.waitForIdle()
  val screenshot=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
  try {
   val directory=File(base.cacheDir,"unlock-preview").apply {check(mkdirs() || isDirectory)}
   File(directory,"$name.png").outputStream().use {check(screenshot.compress(Bitmap.CompressFormat.PNG,100,it))}
  } finally {screenshot.recycle()}
 }

 @Test fun actionsStayDistinctWithoutAnExtraCloseButtonOrEditablePin() {
  val theme=mutableStateOf("carta");val mode=mutableStateOf("light");var unlocks=0;var credentials=0
  compose.setContent {SpuntoTheme(theme.value,mode.value) {UnlockScreen("",{unlocks++},{credentials++})}}
  for(choice in builtInThemes) for(selectedMode in listOf("light","dark")) {
   compose.runOnIdle {theme.value=choice.id;mode.value=selectedMode}
   compose.onNodeWithContentDescription(base.getString(R.string.sys_lock_title)).assertIsDisplayed()
   compose.onAllNodes(hasClickAction()).assertCountEquals(2)
   compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
   compose.onNodeWithText("Close").assertDoesNotExist()
   compose.onNodeWithText("Spunto is locked").assertDoesNotExist()
   preview("unlock-${choice.id}-$selectedMode")
  }
  compose.onNodeWithText(base.getString(R.string.sys_unlock)).assertHeightIsAtLeast(48.dp).performClick()
  compose.runOnIdle {assertEquals(1,unlocks);assertEquals(0,credentials)}
  compose.onNodeWithText(base.getString(R.string.sys_screen_lock)).assertHeightIsAtLeast(48.dp).performClick()
  compose.runOnIdle {assertEquals(1,unlocks);assertEquals(1,credentials)}
 }

 @Test fun errorsAndBothActionsRemainReachableAtLargeTextInEveryLanguage() {
  val language=mutableStateOf("en");val mode=mutableStateOf("light")
  compose.setContent {
   val configuration=Configuration(base.resources.configuration).apply {setLocale(Locale.forLanguageTag(language.value))}
   val context=base.createConfigurationContext(configuration)
   val density=LocalDensity.current
   CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides configuration,LocalDensity provides Density(density.density,2f)) {
    key(language.value,mode.value) {
     SpuntoTheme("carta",mode.value) {Box(Modifier.width(320.dp).height(480.dp)) {
      UnlockScreen(context.getString(R.string.sys_unlock_incomplete),{},{})
     }}
    }
   }
  }
  for(tag in listOf("en","es","it","fr","de")) for(theme in listOf("light","dark")) {
   compose.runOnIdle {language.value=tag;mode.value=theme}
   val configuration=Configuration(base.resources.configuration).apply {setLocale(Locale.forLanguageTag(tag))}
   val context=base.createConfigurationContext(configuration)
   for(id in listOf(R.string.sys_unlock,R.string.sys_screen_lock)) {
    val action=compose.onNodeWithText(context.getString(id))
    if(!action.isDisplayed()) action.performScrollTo()
    action.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertHasClickAction()
   }
   val error=compose.onNodeWithText(context.getString(R.string.sys_unlock_incomplete))
   if(!error.isDisplayed()) error.performScrollTo()
   error.assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion,LiveRegionMode.Polite))
   compose.onAllNodes(hasClickAction()).assertCountEquals(2)
   compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
  }
 }
}
