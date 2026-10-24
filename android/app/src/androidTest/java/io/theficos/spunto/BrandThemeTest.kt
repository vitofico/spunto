package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated branding: changing colours must not drop punctuation or introduce focusable decoration. */
class BrandThemeTest {
 @get:Rule val compose=createComposeRule()
 @Test fun wordmarkPeriodTracksEveryThemeCustomPaletteAndWallpaperAccent() {
  val theme=mutableStateOf("carta");val mode=mutableStateOf("light");val wallpaper=mutableStateOf(false)
  val custom=mutableStateOf(false);var expected=Color.Unspecified
  compose.setContent {
   val palette=if(custom.value) builtInTokens.getValue("salvia").getValue(mode.value) else null
   SpuntoTheme(if(custom.value) "fixture-pack" else theme.value,mode.value,pack=palette,wallpaper=wallpaper.value) {
    expected=MaterialTheme.colorScheme.primary
    Column {SpuntoWordmark();SpuntoWave(Modifier.width(280.dp).height(120.dp))}
   }
  }
  fun verify() {
   val layouts=mutableListOf<TextLayoutResult>()
   compose.onNodeWithText("spunto.").assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
   compose.runOnIdle {
    val text=layouts.single().layoutInput.text
    assertEquals("spunto.",text.text)
    val period=text.spanStyles.single {it.start==6 && it.end==7}
    assertEquals(expected,period.item.color)
   }
   compose.onAllNodes(hasClickAction()).assertCountEquals(0)
   compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
  }
  for(choice in builtInThemes) for(selectedMode in listOf("light","dark")) {
   compose.runOnIdle {theme.value=choice.id;mode.value=selectedMode}
   verify()
  }
  compose.runOnIdle {custom.value=true};verify()
  compose.runOnIdle {wallpaper.value=true};verify()
 }
}
