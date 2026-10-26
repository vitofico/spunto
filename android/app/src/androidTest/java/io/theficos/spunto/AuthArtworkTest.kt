package io.theficos.spunto

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.design.spuntoBackground
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Presentation-only fixtures: no account, saved notes or authentication calls. */
class AuthArtworkTest {
 @get:Rule val compose=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

 private fun hasInk(frame:SemanticsNodeInteraction,area:Rect,paper:Color):Boolean {
  val bounds=frame.fetchSemanticsNode().boundsInRoot
  val pixels=frame.captureToImage().toPixelMap()
  val left=(area.left-bounds.left).roundToInt().coerceIn(0,pixels.width)
  val right=(area.right-bounds.left).roundToInt().coerceIn(0,pixels.width)
  val top=(area.top-bounds.top).roundToInt().coerceIn(0,pixels.height)
  val bottom=(area.bottom-bounds.top).roundToInt().coerceIn(0,pixels.height)
  assertTrue("The decoration check must cover visible space",right>left && bottom>top)
  for(y in top until bottom step 2) for(x in left until right step 2) {
   val pixel=pixels[x,y]
   if(maxOf(abs(pixel.red-paper.red),abs(pixel.green-paper.green),abs(pixel.blue-paper.blue))>.02f) return true
  }
  return false
 }

 @Test fun signInHeaderKeepsItsSpacingWithoutAnExtraIllustration() {
  val background=mutableStateOf("artwork");val mode=mutableStateOf("light")
  var paper=Color.Unspecified;var gap=0f;var headerToContent=0f
  compose.setContent {
   val density=LocalDensity.current
   CompositionLocalProvider(LocalDensity provides Density(density.density,1f)) {
    SpuntoTheme("carta",mode.value,backgroundStyle=background.value) {
     paper=MaterialTheme.colorScheme.background
     gap=with(LocalDensity.current) {12.dp.toPx()}
     headerToContent=with(LocalDensity.current) {68.dp.toPx()}
     Box(Modifier.width(320.dp).height(640.dp).testTag("auth-fixture")) {
      LoginPage {Text("Synthetic sign-in")}
     }
    }
   }
  }
  for(selectedMode in listOf("light","dark")) for(style in listOf("plain","artwork")) {
   compose.runOnIdle {mode.value=selectedMode;background.value=style}
   compose.waitForIdle()
   val frame=compose.onNodeWithTag("auth-fixture")
   val bounds=frame.fetchSemanticsNode().boundsInRoot
   val wordmark=compose.onNodeWithText("spunto.").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
   val content=compose.onNodeWithText("Synthetic sign-in").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
   assertEquals("The 52dp header and 16dp content gap must not move",headerToContent,content.top-wordmark.top,1f)
   assertFalse("Sign-in keeps only its wordmark on the opaque form header",hasInk(frame,Rect(wordmark.right+gap,wordmark.top,bounds.right-gap,wordmark.bottom),paper))
  }
 }

 @Test fun unlockUsesOnlyTheOptionalPageBackdropAndKeepsBothActions() {
  val background=mutableStateOf("plain");val mode=mutableStateOf("light");val backdropOnly=mutableStateOf(false)
  var paper=Color.Unspecified;var gap=0f
  compose.setContent {
   val density=LocalDensity.current
   CompositionLocalProvider(LocalDensity provides Density(density.density,1f)) {
    SpuntoTheme("carta",mode.value,backgroundStyle=background.value) {
     paper=MaterialTheme.colorScheme.background;gap=with(LocalDensity.current) {16.dp.toPx()}
     Box(Modifier.width(320.dp).height(640.dp).testTag("auth-fixture")) {
      if(backdropOnly.value) Box(Modifier.fillMaxSize().spuntoBackground()) else UnlockScreen("",{},{})
     }
    }
   }
  }
  for(selectedMode in listOf("light","dark")) {
   var actionTop:Float?=null
   for(style in listOf("plain","artwork")) {
    compose.runOnIdle {mode.value=selectedMode;background.value=style}
    compose.waitForIdle()
    val frame=compose.onNodeWithTag("auth-fixture")
    val bounds=frame.fetchSemanticsNode().boundsInRoot
    val wordmark=compose.onNodeWithContentDescription(context.getString(R.string.sys_lock_title)).fetchSemanticsNode().boundsInRoot
    val welcome=compose.onNodeWithText(context.getString(R.string.sys_welcome_back)).fetchSemanticsNode().boundsInRoot
    val area=Rect(bounds.left+gap,wordmark.bottom+gap,bounds.right-gap,welcome.top-gap)
    val ink=hasInk(frame,area,paper)
    assertEquals("Only Artwork decorates the reserved unlock space",style=="artwork",ink)
    val actual=frame.captureToImage().toPixelMap()
    compose.runOnIdle {backdropOnly.value=true}
    compose.waitForIdle()
    val expected=frame.captureToImage().toPixelMap()
    // Compare the reserved space with one canonical backdrop, catching an extra wave in Artwork too.
    for(y in (area.top-bounds.top).roundToInt() until (area.bottom-bounds.top).roundToInt() step 2) {
     for(x in (area.left-bounds.left).roundToInt() until (area.right-bounds.left).roundToInt() step 2) {
      val a=actual[x,y];val e=expected[x,y]
      assertTrue("Unlock must not add an illustration over its page backdrop",maxOf(abs(a.red-e.red),abs(a.green-e.green),abs(a.blue-e.blue))<=.02f)
     }
    }
    compose.runOnIdle {backdropOnly.value=false}
    compose.waitForIdle()
    val unlock=compose.onNodeWithText(context.getString(R.string.sys_unlock)).assertIsDisplayed().assertHasClickAction()
    val top=unlock.fetchSemanticsNode().boundsInRoot.top
    actionTop?.let {assertEquals("Changing background style must not move Unlock",it,top,1f)}
    actionTop=top
    compose.onNodeWithText(context.getString(R.string.sys_screen_lock)).assertIsDisplayed().assertHasClickAction()
   }
  }
 }
}
