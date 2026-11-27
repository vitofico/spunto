package io.theficos.spunto

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class ExpandedCaptureGlyphTest {
 @get:Rule val compose=createComposeRule()

 @Test fun firstLineDrawsTheWholeAscenderAtDefaultAndLargeFontScales() {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
  val authority=runBlocking {LocalFixtureAuthority.require(app.container)}
  val model=SpuntoViewModel(app)
  val key="test-glyph:${System.nanoTime()}"
  val text="t È\n\nt È"
  val mounted=mutableStateOf(true)
  val textScale=mutableStateOf(1f)
  try {
   compose.setContent {
    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,textScale.value)) {
     SpuntoTheme("spunto-prugna","light") {
      if(mounted.value) Composer(model,initial=text,draftId=key,autoFocus=false,onExpandCapture={},
       captureExpanded=true,modifier=Modifier.fillMaxSize(),onSave={})
     }
    }
   }
   val enabledField=hasContentDescription("Note text") and isEnabled()
   compose.waitUntil(10_000) {compose.onAllNodes(enabledField).fetchSemanticsNodes().isNotEmpty()}
   val field=compose.onNode(enabledField)
   for(scale in listOf(1f,2f)) {
    compose.runOnIdle {textScale.value=scale}
    val layouts=mutableListOf<TextLayoutResult>()
    field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
    val layout=layouts.single()
    val splitY=((layout.getLineTop(1)+layout.getLineBottom(1))/2).roundToInt()
    val splitX=((layout.getBoundingBox(0).right+layout.getBoundingBox(2).left)/2).roundToInt()
    val pixels=field.captureToImage().toPixelMap()

    fun inkHeight(columns:IntRange,rows:IntRange):Int {
     val ink=rows.filter {y -> columns.any {x ->
      val pixel=pixels[x,y]
      pixel.red<.55f && pixel.green<.55f && pixel.blue<.55f
     }}
     assertTrue("The glyph must be visible at font scale $scale",ink.isNotEmpty())
     return ink.last()-ink.first()+1
    }
    for(columns in listOf(0 until splitX,splitX until pixels.width)) {
     val first=inkHeight(columns,0 until splitY)
     val reference=inkHeight(columns,splitY until pixels.height)
     assertTrue("First line ink height $first should match the next line's $reference at $scale",abs(first-reference)<=1)
    }
   }
  } finally {
   val cleanup=model.repository.beginEditor(null,key)
   compose.runOnIdle {mounted.value=false}
   compose.waitForIdle()
   runBlocking {authority.requireCurrent();model.repository.clearDraft(key,text)}
   model.repository.abandonEditor(cleanup)
   model.retire()
  }
 }
}
