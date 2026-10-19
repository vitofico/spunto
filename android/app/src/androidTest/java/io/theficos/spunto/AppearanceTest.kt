package io.theficos.spunto

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.theficos.spunto.core.design.*
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class AppearanceTest {
 @get:Rule val timeout=Timeout(10,TimeUnit.MINUTES)
 @get:Rule val compose=createComposeRule()
 @Test fun vividThemeSelectionAndInstanceResetKeepDeviceMode() {
  val theme=mutableStateOf("corallo");val override=mutableStateOf(false);val mode=mutableStateOf("dark")
  compose.setContent { SpuntoTheme(theme.value,mode.value) { AppearanceScreen(theme.value,mode.value,false,false,{theme.value=it;override.value=true},{mode.value=it},{},{},themeOverride=override.value,onUseInstance={theme.value="corallo";override.value=false}) } }
  compose.onNodeWithText("Using the instance theme").assertIsDisplayed()
  compose.onNode(hasScrollAction()).performScrollToNode(hasText("Elettrica",substring=true))
  compose.onNodeWithText("Elettrica",substring=true).performClick()
  compose.runOnIdle { assertEquals("elettrica",theme.value);assertTrue(override.value);assertEquals("dark",mode.value) }
  compose.onNode(hasScrollAction()).performScrollToNode(hasText("Use a different theme on this phone"))
  compose.onNodeWithContentDescription("Use a different theme on this phone").performClick()
  compose.runOnIdle { assertEquals("corallo",theme.value);assertFalse(override.value);assertEquals("dark",mode.value) }
  compose.onNodeWithText("Using the instance theme").assertIsDisplayed()
 }
 @Test fun installedPackAccentAndReadingChoicesStayLocalAndFailedCardIsDisabled() {
  val carta=builtInTokens.getValue("carta");val slate=builtInTokens.getValue("ardesia")
  val pack=ThemeCard("fixture-pack","Fixture paper",light=carta.getValue("light"),dark=carta.getValue("dark"),accents=listOf(ThemeAccent("blue-accent","Blue",slate.getValue("light"),slate.getValue("dark"))))
  val catalog=validatedThemeCatalog(listOf(pack,ThemeCard("fixture-failed","Fixture failure",status="failed",reason="dark: ink-3 on sunken requires 4.5:1")))
  val theme=mutableStateOf("fixture-pack");val accent=mutableStateOf<String?>(null);val face=mutableStateOf("serif");val size=mutableStateOf("M")
  compose.setContent {
   val selected=resolveThemeSelection(theme.value,accent.value,catalog)
   SpuntoTheme(selected.id,"dark",pack=selected.dark,readingFace=face.value,readingSize=size.value) {
    AppearanceScreen(theme.value,"dark",true,true,{theme.value=it;accent.value=null},{},{},{},catalog=catalog,accent=accent.value,onAccent={accent.value=it},instanceName="Corallo",readingFace=face.value,readingSize=size.value,onReadingFace={face.value=it},onReadingSize={size.value=it})
   }
  }
  compose.onNodeWithText("Other devices use Corallo.").assertIsDisplayed()
  compose.onNode(hasScrollAction()).performScrollToNode(hasText("Fixture failure"))
  compose.onNodeWithText("Fixture failure").assertIsNotEnabled().assertHasNoClickAction().performTouchInput { click() }
  compose.runOnIdle { assertEquals("fixture-pack",theme.value);assertNull(accent.value) }
  compose.onNode(hasScrollAction()).performScrollToNode(hasText("Blue"))
  compose.onNodeWithText("Default").assertExists();compose.onNodeWithText("Blue").performClick()
  compose.runOnIdle { assertEquals("blue-accent",accent.value) }
  compose.onNodeWithText("Default").performClick()
  compose.runOnIdle { assertNull(accent.value) }
  compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sans"));compose.onNodeWithText("Sans").performClick()
  compose.onNode(hasScrollAction()).performScrollToNode(hasText("L"));compose.onNodeWithText("L").performClick()
  compose.runOnIdle { assertEquals("sans",face.value);assertEquals("L",size.value);assertEquals("fixture-pack",theme.value) }
 }
}
