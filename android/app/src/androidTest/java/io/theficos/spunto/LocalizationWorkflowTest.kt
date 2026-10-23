package io.theficos.spunto

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test

/** Isolated UI: no account, saved notes, settings writes or network. */
class LocalizationWorkflowTest {
 @get:Rule val compose=createComposeRule()

 @Test fun onboardingSwitchesAcrossEveryBundledLocaleAndKeepsNavigationUsable() {
  val language=mutableStateOf("en")
  val base=InstrumentationRegistry.getInstrumentation().targetContext
  compose.setContent {
   val configuration=Configuration(base.resources.configuration).apply {setLocale(Locale.forLanguageTag(language.value))}
   val context=base.createConfigurationContext(configuration)
   CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides configuration) {
    key(language.value) {SpuntoTheme {FirstRunGuide(onSkip={},onStart={})}}
   }
  }
  for(tag in listOf("en","es","it","fr","de")) {
   compose.runOnIdle {language.value=tag}
   val configuration=Configuration(base.resources.configuration).apply {setLocale(Locale.forLanguageTag(tag))}
   val context=base.createConfigurationContext(configuration)
   compose.onNodeWithText(context.getString(R.string.core_guide_catch)).assertIsDisplayed()
   val next=compose.onNodeWithText(context.getString(R.string.core_next))
   if(!next.isDisplayed()) next.performScrollTo()
   next.performClick()
   compose.onNodeWithText(context.getString(R.string.core_guide_labels)).assertIsDisplayed()
   val back=compose.onNodeWithText(context.getString(R.string.core_back))
   if(!back.isDisplayed()) back.performScrollTo()
   back.performClick()
   compose.onNodeWithText(context.getString(R.string.core_guide_catch)).assertIsDisplayed()
  }
 }
}
