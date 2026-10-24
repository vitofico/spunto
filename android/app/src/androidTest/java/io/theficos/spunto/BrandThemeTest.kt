package io.theficos.spunto

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Expected colours come from tokens or Android's wallpaper palette, never the rendered MaterialTheme. */
class BrandThemeTest {
 @get:Rule val timeout=Timeout(10,TimeUnit.MINUTES)
 @get:Rule val compose=createComposeRule()
 private val context:Context get()=InstrumentationRegistry.getInstrumentation().targetContext

 @Test fun liveBuiltInThemeAndModeChangesRecolourRenderedContent() {
  val theme=mutableStateOf("carta");val mode=mutableStateOf("light")
  compose.setContent {RenderedThemeFixture(theme.value,mode.value)}
  for(choice in builtInThemes) for(selectedMode in listOf("light","dark")) {
   compose.runOnIdle {theme.value=choice.id;mode.value=selectedMode}
   verifyRenderedColours(expectation(builtInTokens.getValue(choice.id).getValue(selectedMode),selectedMode),"${choice.id}/$selectedMode")
  }
 }

 @Test fun wallpaperColoursAreIntentionalAndTurningThemOffRestoresTheSelectedTheme() {
  assumeTrue(Build.VERSION.SDK_INT>=31)
  val theme=mutableStateOf("corallo");val mode=mutableStateOf("light");val wallpaper=mutableStateOf(false)
  compose.setContent {RenderedThemeFixture(theme.value,mode.value,wallpaper.value)}
  for(selectedMode in listOf("light","dark")) {
   compose.runOnIdle {theme.value="corallo";mode.value=selectedMode;wallpaper.value=true}
   verifyRenderedColours(expectation(builtInTokens.getValue("corallo").getValue(selectedMode),selectedMode,true),"Corallo/$selectedMode/wallpaper")
   // Switching themes while wallpaper colours are enabled still updates the selected ink.
   compose.runOnIdle {theme.value="elettrica"}
   verifyRenderedColours(expectation(builtInTokens.getValue("elettrica").getValue(selectedMode),selectedMode,true),"Elettrica/$selectedMode/wallpaper")
   compose.runOnIdle {wallpaper.value=false}
   verifyRenderedColours(expectation(builtInTokens.getValue("elettrica").getValue(selectedMode),selectedMode),"Elettrica/$selectedMode/restored")
  }
 }

 @Test fun installedPackAndSelectedAccentRestoreAfterWallpaperColours() {
  val carta=builtInTokens.getValue("carta");val slate=builtInTokens.getValue("ardesia")
  val card=ThemeCard("fixture-pack","Fixture paper",light=carta.getValue("light"),dark=carta.getValue("dark"),accents=listOf(ThemeAccent("blue-accent","Blue",slate.getValue("light"),slate.getValue("dark"))))
  val catalog=validatedThemeCatalog(listOf(card))
  val mode=mutableStateOf("light");val accent=mutableStateOf<String?>(null);val wallpaper=mutableStateOf(false)
  compose.setContent {
   val selection=resolveThemeSelection(card.id,accent.value,catalog)
   RenderedThemeFixture(selection.id,mode.value,wallpaper.value,selection.mode(mode.value=="dark"))
  }
  for(selectedMode in listOf("light","dark")) {
   compose.runOnIdle {mode.value=selectedMode;accent.value=null;wallpaper.value=false}
   verifyRenderedColours(expectation(carta.getValue(selectedMode),selectedMode),"custom/$selectedMode/default")
   compose.runOnIdle {accent.value="blue-accent"}
   verifyRenderedColours(expectation(slate.getValue(selectedMode),selectedMode),"custom/$selectedMode/blue")
   if(Build.VERSION.SDK_INT>=31) {
    compose.runOnIdle {wallpaper.value=true}
    verifyRenderedColours(expectation(slate.getValue(selectedMode),selectedMode,true),"custom/$selectedMode/wallpaper")
    compose.runOnIdle {wallpaper.value=false}
    verifyRenderedColours(expectation(slate.getValue(selectedMode),selectedMode),"custom/$selectedMode/restored")
   }
  }
 }

 private data class ExpectedColours(val accent:Color,val paper:Color,val ink:Color)
 private fun expectation(palette:Map<String,String>,mode:String,wallpaper:Boolean=false):ExpectedColours {
  fun token(name:String)=Color(android.graphics.Color.parseColor(palette.getValue(name)))
  if(wallpaper && Build.VERSION.SDK_INT>=31) {
   // Independently query Android; comparing the period with MaterialTheme.primary would miss a stale scheme.
   val dynamic=if(mode=="dark") dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
   return ExpectedColours(dynamic.primary,dynamic.background,token("ink"))
  }
  return ExpectedColours(token("accent"),token("paper"),token("ink"))
 }

 private fun textLayout(text:String):TextLayoutResult {
  val layouts=mutableListOf<TextLayoutResult>()
  compose.onNodeWithText(text).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
  assertEquals("One rendered layout for $text",1,layouts.size)
  return layouts.single()
 }

 private fun verifyRenderedColours(expected:ExpectedColours,case:String) {
  val wordmark=textLayout("spunto.")
  val ask=textLayout("Ask")
  val body=textLayout("Body foreground")
  val markdown=textLayout("A note with #theme")
  compose.runOnIdle {
   val text=wordmark.layoutInput.text
   assertEquals("spunto.",text.text)
   val period=text.spanStyles.single {it.start==6 && it.end==7}
   assertEquals("$case period",expected.accent,period.item.color)
   assertEquals("$case TextButton foreground",expected.accent,ask.layoutInput.style.color)
   assertEquals("$case Text foreground",expected.ink,body.layoutInput.style.color)
   assertEquals("$case Markdown foreground",expected.ink,markdown.layoutInput.style.color)
   val label=markdown.layoutInput.text.spanStyles.single {it.start==12 && it.end==18}
   assertEquals("$case Markdown label",expected.accent,label.item.color)
  }
  compose.onNodeWithText("spunto.").assertHasNoClickAction()
  compose.onNodeWithTag("wave-sample").assertHasNoClickAction()
  val background=compose.onNodeWithTag("theme-surface").captureToImage().toPixelMap()
  assertColourClose("$case background",expected.paper,background[background.width-1,background.height-1])
  assertWavePixels(expected,case)
 }

 /** Check visible strand pixels against the expected accent blended over its paper, allowing antialiasing. */
 private fun assertWavePixels(expected:ExpectedColours,case:String) {
  val image=compose.onNodeWithTag("wave-sample").captureToImage().toPixelMap()
  val paper=floatArrayOf(expected.paper.red,expected.paper.green,expected.paper.blue)
  val direction=floatArrayOf(expected.accent.red-paper[0],expected.accent.green-paper[1],expected.accent.blue-paper[2])
  val length=direction.sumOf {(it*it).toDouble()}.toFloat()
  var strands=0
  for(y in 0 until image.height step 2) for(x in 0 until image.width step 2) {
   val pixel=image[x,y]
   val difference=floatArrayOf(pixel.red-paper[0],pixel.green-paper[1],pixel.blue-paper[2])
   if(difference.maxOf {abs(it)}<.08f) continue
   val alpha=direction.indices.sumOf {(difference[it]*direction[it]).toDouble()}.toFloat()/length
   assertTrue("$case strand alpha $alpha",alpha in -.02f..1.02f)
   val error=direction.indices.maxOf {abs(difference[it]-alpha*direction[it])}
   assertTrue("$case wave must use expected accent; RGB residual $error",error<.035f)
   strands++
  }
  assertTrue("$case must draw visible wave strands",strands>20)
 }

 private fun assertColourClose(message:String,expected:Color,actual:Color) {
  assertEquals("$message red",expected.red,actual.red,.015f)
  assertEquals("$message green",expected.green,actual.green,.015f)
  assertEquals("$message blue",expected.blue,actual.blue,.015f)
 }
}

@Composable private fun RenderedThemeFixture(theme:String,mode:String,wallpaper:Boolean=false,pack:Map<String,String>?=null) {
 SpuntoTheme(theme,mode,wallpaper=wallpaper,pack=pack,namespace="brand-colour-regression") {
  Surface(Modifier.fillMaxSize().testTag("theme-surface"),color=MaterialTheme.colorScheme.background) {
   Column(Modifier.padding(16.dp)) {
    SpuntoWordmark()
    TextButton(onClick={}) {Text("Ask")}
    Text("Body foreground")
    Markdown("A note with #theme")
    Box(Modifier.width(280.dp).height(120.dp).testTag("wave-sample")) {SpuntoWave(Modifier.fillMaxSize())}
   }
  }
 }
}
