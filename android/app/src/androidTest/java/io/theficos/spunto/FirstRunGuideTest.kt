package io.theficos.spunto

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Isolated preference/UI fixtures, with no replica, auth, permission or network operations. */
class FirstRunGuideTest {
 @get:Rule val compose=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val preferences by lazy {context.getSharedPreferences("guide-fixture-${System.nanoTime()}",Context.MODE_PRIVATE)}
 @After fun removeFixturePreferences() {check(preferences.edit().clear().commit())}
 private fun reachableAction(text:String):SemanticsNodeInteraction {
  val action=compose.onNodeWithText(text)
  if(!action.isDisplayed()) action.performScrollTo()
  return action.assertIsDisplayed()
 }
 private fun nextStep(number:Int) {
  reachableAction("Next").performClick()
  compose.waitUntil(10_000) {compose.onAllNodesWithText("Getting started · $number of 4").fetchSemanticsNodes().size==1}
  compose.onNodeWithText("Getting started · $number of 4").assertIsDisplayed()
 }

 @Test fun skipPersistsAndANewGateDoesNotShowAgain() {
  val generation=mutableIntStateOf(0)
  compose.setContent {SpuntoTheme {key(generation.intValue) {
   FirstRunGuideGate(FirstRunGuideStore(preferences),true,onStartWriting={}) {Text("Main screen")}
  }}}
  compose.onNodeWithText("Getting started · 1 of 4").assertIsDisplayed()
  compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
  compose.onNodeWithText("Skip").performClick()
  compose.onNodeWithText("Main screen").assertIsDisplayed()
  assertTrue(FirstRunGuideStore(preferences).completed)
  compose.runOnIdle {generation.intValue++}
  compose.onNodeWithText("Getting started",substring=true).assertDoesNotExist()
  compose.onNodeWithText("Main screen").assertIsDisplayed()
 }
 @Test fun finishPersistsAndReplaySkipReturnsWithoutClearingCompletion() {
  val replay=mutableStateOf(false);val screen=mutableStateOf("Main screen");var starts=0
  compose.setContent {SpuntoTheme {
   FirstRunGuideGate(FirstRunGuideStore(preferences),true,replay.value,onStartWriting={starts++;replay.value=false},onSkipReplay={replay.value=false;screen.value="Settings screen"}) {Text(screen.value)}
  }}
  repeat(3) {nextStep(it+2)}
  reachableAction("Start writing").performClick()
  compose.runOnIdle {assertEquals(1,starts);assertTrue(FirstRunGuideStore(preferences).completed);replay.value=true}
  compose.onNodeWithText("Getting started · 1 of 4").assertIsDisplayed()
  compose.onNodeWithText("Skip").performClick()
  compose.onNodeWithText("Settings screen").assertIsDisplayed()
  assertTrue(FirstRunGuideStore(preferences).completed)
 }
 @Test fun stepRestoresAndFooterActionsStayReachableAtCompactLargeText() {
  val restoration=StateRestorationTester(compose);var connects=0;var starts=0
  restoration.setContent {
   val density=LocalDensity.current
   CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
    SpuntoTheme("salvia","dark") {Box(Modifier.width(320.dp).height(480.dp)) {FirstRunGuide(onSkip={},onStart={starts++},onConnect={connects++})}}
   }
  }
  compose.onNodeWithText("Back").assertIsNotEnabled()
  fun assertStepStart(number:Int,title:String) {
   if(number<4) compose.onNodeWithText("Skip").assertIsDisplayed() else compose.onNodeWithText("Skip").assertDoesNotExist()
   compose.onNodeWithText("Getting started · $number of 4").assertIsDisplayed()
   compose.onNodeWithText(title).assertIsDisplayed()
   compose.onNode(hasScrollAction()).assertHeightIsAtLeast(180.dp).assert(SemanticsMatcher("step starts at the top") {
    it.config[SemanticsProperties.VerticalScrollAxisRange].value()==0f
   })
  }
  assertStepStart(1,"Catch a thought.")
  compose.onNodeWithText("Write a quick note",substring=true).performScrollTo().assertIsDisplayed()
  compose.onNodeWithText("Next").performScrollTo().assertIsDisplayed().performClick()
  assertStepStart(2,"Labels live in your words.")
  restoration.emulateSavedInstanceStateRestore()
  assertStepStart(2,"Labels live in your words.")
  compose.onNodeWithText("Back").performScrollTo().assertIsDisplayed().performClick()
  assertStepStart(1,"Catch a thought.")
  listOf("Labels live in your words.","Find it when you need it.","Sync when you're ready.").forEachIndexed {index,title->
   compose.onNodeWithText("Next").performScrollTo().assertIsDisplayed().performClick()
   assertStepStart(index+2,title)
  }
  compose.onNodeWithText("Sync when you're ready.").performScrollTo().assertIsDisplayed()
  compose.onNodeWithText("Keep writing offline",substring=true).performScrollTo().assertIsDisplayed()
  compose.onNodeWithText("Your notes stay on this phone",substring=true).performScrollTo().assertIsDisplayed()
  compose.onNodeWithText("Connect to a server").performScrollTo().assertIsDisplayed().performClick()
  compose.onNodeWithText("Skip").assertDoesNotExist()
  compose.onNodeWithText("Start writing").performScrollTo().assertIsDisplayed().performClick()
  compose.runOnIdle {assertEquals(1,connects);assertEquals(1,starts)}
 }
 @Test fun deferredEntryDoesNotMarkCompletionAndSignedInGuideOffersNoConnectionAction() {
  val eligible=mutableStateOf(false)
  compose.setContent {SpuntoTheme {
   FirstRunGuideGate(FirstRunGuideStore(preferences),eligible.value,onStartWriting={}) {Text("Linked note")}
  }}
  compose.onNodeWithText("Linked note").assertIsDisplayed()
  assertFalse(FirstRunGuideStore(preferences).completed)
  compose.runOnIdle {eligible.value=true}
  repeat(3) {nextStep(it+2)}
  compose.onNodeWithText("Connect to a server").assertDoesNotExist()
  reachableAction("Start writing")
 }
}
