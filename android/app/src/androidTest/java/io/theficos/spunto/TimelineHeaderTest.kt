package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.res.painterResource
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.*
import java.io.File
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.SpuntoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Header-only layout fixtures: no notes, account, authentication, network or retained state. */
class TimelineHeaderTest {
 @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
 private val clicks=mutableListOf<String>()
 private val controls=listOf("Choose labels and views","Ask your notes","Search","Filter notes","Settings")

 @Test fun normalPhoneHasOneCompactBarAndAllFiveTargets() {
  content(360,1f)
  assertEquals(64f,headerHeight(),.5f)
  compose.onNodeWithText("spunto.").assertIsDisplayed().assertHasNoClickAction()
  compose.onNodeWithContentDescription("Choose labels and views").assertTextEquals("All notes ▾")
  assertTargets()
  captureEvidence("fixture-normal-ask.png")
  controls.forEach {compose.onNodeWithContentDescription(it).performClick()}
  compose.runOnIdle {assertEquals(controls,clicks)}
 }

 @Test fun maximumSingleRowTextScaleKeepsBrandAndSelectorTextSeparate() {
  content(360,1f)
  assertEquals(64f,headerHeight(),.5f)
  assertTargets()
  val brand=compose.onNodeWithText("spunto.",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
  val label=compose.onNodeWithText("All notes ▾",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
  assertTrue("Wordmark $brand and selector $label must not overlap",brand.bottom<=label.top)
 }

 @Test fun intermediateTextScaleUsesTwoRows() {
  content(360,1.1f)
  assertEquals(104f,headerHeight(),.5f)
  assertTargets()
 }

 @Test fun narrowLargeTextAndRtlKeepControlsOnTwoFunctionalRows() {
  content(320,2f,LayoutDirection.Rtl)
  assertTrue(headerHeight() in 104f..128f)
  captureEvidence("fixture-narrow-200pct-rtl-ask.png")
  assertTargets()
  val layouts=mutableListOf<TextLayoutResult>()
  compose.onNodeWithText("spunto.").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
  compose.runOnIdle {val layout=layouts.single()
   assertFalse("The period must remain visible at 200%: size=${layout.size}, constraints=${layout.layoutInput.constraints}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}",layout.hasVisualOverflow)}
  val wordmark=compose.onNodeWithText("spunto.").fetchSemanticsNode().boundsInRoot
  val ask=compose.onNodeWithContentDescription("Ask your notes").fetchSemanticsNode().boundsInRoot
  assertTrue("Actions belong below the brand and selector",wordmark.bottom<=ask.top)
  controls.forEach {compose.onNodeWithContentDescription(it).performClick()}
  compose.runOnIdle {assertEquals(controls,clicks)}
 }

 @Test fun additionalPageActionsKeepTheirOwnTarget() {
  content(360,1f,extra=true)
  assertEquals(104f,headerHeight(),.5f)
  assertTargets(controls+"Page action")
  captureEvidence("fixture-extra-action.png")
  compose.onNodeWithContentDescription("Page action").performClick()
  compose.runOnIdle {assertEquals(listOf("Page action"),clicks)}
 }

 @Test fun safeCutoutsChooseLayoutFromUsableWidth() {
  compose.setContent {SpuntoTheme("carta","light") {Surface(Modifier.width(400.dp).height(300.dp)) {
   Column {header(WindowInsets(left=40.dp,right=40.dp))}
  }}}
  assertEquals(104f,headerHeight(),.5f)
  assertTargets()
  val frame=compose.onNodeWithTag("timeline-header").fetchSemanticsNode().boundsInRoot
  for(label in controls) {
   val touch=compose.onNodeWithContentDescription(label).fetchSemanticsNode().touchBoundsInRoot
   assertTrue("$label avoids cutouts",touch.left>=frame.left+39.5f*compose.density.density && touch.right<=frame.right-39.5f*compose.density.density)
  }
 }

 @Test fun minimumContrastAccentNeverTintsTheControlSurface() {
  compose.setContent {SpuntoTheme("carta","light") {
   MaterialTheme(colorScheme=MaterialTheme.colorScheme.copy(background=Color.White,surface=Color.White,primary=Color(0xff767676))) {
    Box(Modifier.width(360.dp)) {header(WindowInsets(top=40.dp))}
   }
  }}
  val image=compose.onNodeWithTag("timeline-header").captureToImage().toPixelMap()
  val ask=compose.onNodeWithContentDescription("Ask your notes").fetchSemanticsNode().boundsInRoot
  val frame=compose.onNodeWithTag("timeline-header").fetchSemanticsNode().boundsInRoot
  // Clear padding beside the accent-coloured glyph, at the glyph's vertical centre.
  val x=(ask.left-frame.left+2*compose.density.density).toInt()
  val y=(ask.center.y-frame.top).toInt()
  assertEquals(Color.White,image[x,y])
 }

 @Test fun installedNearThresholdPackKeepsStatusClockAndAskReadable() {
  val surfaceRoles=setOf("paper","raised","sunken","accent-soft","highlight")
  val values=themeColorRoles.associateWith {if(it in surfaceRoles) "#747474" else "#FFFFFF"}+mapOf("on-accent" to "#000000")
  assertTrue(paletteErrors(values).toString(),paletteErrors(values).isEmpty())
  val card=ThemeCard("status-fixture","Synthetic threshold",light=values,dark=values)
  val catalog=validatedThemeCatalog(listOf(card))
  assertTrue(catalog.cards.single {it.id==card.id}.available)
  val selected=resolveThemeSelection(card.id,null,catalog)
  compose.activityRule.scenario.onActivity {it.enableEdgeToEdge()}
  compose.setContent {SpuntoTheme(selected.id,"light",pack=selected.light) {
   Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {Column {
    header(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal))
    Text("Synthetic note: a quiet place to think.",Modifier.padding(20.dp),style=SpuntoType.reading)
   }}
  }}
  assertTargets()
  captureEvidence("fixture-custom-threshold-ask.png")
  compose.onNodeWithContentDescription("Ask your notes").assertIsDisplayed().performClick()
  compose.runOnIdle {assertEquals(listOf("Ask your notes"),clicks)}
 }

 private fun captureEvidence(name:String) {
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  if(InstrumentationRegistry.getArguments().getString("spunto.header.evidence")=="SYNTHETIC-DEBUG-ONLY") {
   check(instrumentation.targetContext.packageName=="io.theficos.spunto.debug")
   val emulator=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")).bufferedReader().use {it.readText().trim()}
   check(emulator=="1") {"Header evidence is restricted to disposable emulator fixtures"}
   val directory=File(instrumentation.targetContext.cacheDir,"header-evidence").apply {check(mkdirs() || isDirectory)}
   compose.mainClock.advanceTimeBy(1000);compose.waitForIdle();instrumentation.uiAutomation.waitForIdle(150,5000)
   val image=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
   File(directory,name).outputStream().use {check(image.compress(Bitmap.CompressFormat.PNG,100,it))}
   image.recycle()
  }
 }

 private fun content(width:Int,scale:Float,direction:LayoutDirection=LayoutDirection.Ltr,extra:Boolean=false) {
  compose.setContent {
   val density=LocalDensity.current
   CompositionLocalProvider(LocalDensity provides Density(density.density,scale),LocalLayoutDirection provides direction) {
    SpuntoTheme("carta","light") {Surface(Modifier.width(width.dp).height(300.dp)) {Column {header(extra=extra)}}}
   }
  }
 }
 @Composable private fun header(insets:WindowInsets=WindowInsets(0,0,0,0),extra:Boolean=false) {
  TimelineHeader(windowInsets=insets,hasAdditionalActions=extra,selector={modifier ->
   TextButton(onClick={clicks+="Choose labels and views"},modifier=modifier.semantics {contentDescription="Choose labels and views"}) {
    Text("All notes ▾",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis)
   }
  },actions={
   TextButton(onClick={clicks+="Ask your notes"},modifier=Modifier.semantics {contentDescription="Ask your notes"}) {Text("Ask")}
   for(label in listOf("Search","Filter notes","Settings")+if(extra) listOf("Page action") else emptyList()) {
    IconButton(onClick={clicks+=label},modifier=Modifier.semantics {contentDescription=label}) {
     val icon=when(label) {"Filter notes" -> R.drawable.ic_filter;"Settings" -> R.drawable.ic_settings;else -> R.drawable.ic_search}
     Icon(painterResource(icon),contentDescription=null,modifier=Modifier.size(24.dp))
    }
   }
  })
 }
 private fun headerHeight()=compose.onNodeWithTag("timeline-header").fetchSemanticsNode().boundsInRoot.height/compose.density.density
 private fun assertTargets(labels:List<String> = controls) {
  val frame=compose.onNodeWithTag("timeline-header").fetchSemanticsNode().boundsInRoot
  val bounds=labels.map {compose.onNodeWithContentDescription(it).fetchSemanticsNode().touchBoundsInRoot}
  for(first in bounds.indices) for(second in first+1 until bounds.size) {
   assertFalse("${labels[first]} ${bounds[first]} must not overlap ${labels[second]} ${bounds[second]}",bounds[first].overlaps(bounds[second]))
  }
  for(label in labels) {
   val node=compose.onNodeWithContentDescription(label).assertIsDisplayed().assertHasClickAction().fetchSemanticsNode()
   val touch=node.touchBoundsInRoot
   assertTrue("$label width",touch.width/compose.density.density>=47.9f)
   assertTrue("$label height",touch.height/compose.density.density>=47.9f)
   assertTrue("$label stays inside header",touch.left>=frame.left-.5f && touch.right<=frame.right+.5f && touch.top>=frame.top-.5f && touch.bottom<=frame.bottom+.5f)
  }
 }
}
