package io.theficos.spunto

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.Markdown
import io.theficos.spunto.core.design.SpuntoTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test

/** Rendering translates UI image labels while preserving the owner’s Markdown and alt text. */
class MarkdownLocalizationTest {
 @get:Rule val compose=createComposeRule()
 @Test fun imagePlaceholderUsesEveryResourceLocaleAndKeepsAltText() {
  val language=mutableStateOf("en")
  val base=InstrumentationRegistry.getInstrumentation().targetContext
  val alt="Città #Original words"
  val body="Before ![$alt](/a/synthetic-image) after"
  compose.setContent {
   val configuration=Configuration(base.resources.configuration).apply {setLocale(Locale.forLanguageTag(language.value))}
   val context=base.createConfigurationContext(configuration)
   CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides configuration) {SpuntoTheme {Markdown(body)}}
  }
  for((tag,label) in listOf("en" to "Image:","es" to "Imagen:","it" to "Immagine:","fr" to "Image :","de" to "Bild:")) {
   compose.runOnIdle {language.value=tag}
   compose.onNodeWithText("Before [$label $alt] after").assertExists()
  }
  org.junit.Assert.assertEquals("Before ![Città #Original words](/a/synthetic-image) after",body)
 }
}
