package io.theficos.spunto

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/** Guarded local launcher/share fixtures; original guide marker and primary draft are restored. */
class FirstRunGuideIntegrationTest {
 private val compose=createEmptyComposeRule()
 @get:Rule val rules=RuleChain.outerRule(Timeout(5,TimeUnit.MINUTES)).around(GuidePreferenceFixture(null)).around(compose)
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val app get()=context.applicationContext as SpuntoApplication
 private val store get()=FirstRunGuideStore(firstRunGuidePreferences(context))
 private fun launcher()=Intent(context,MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 private fun waitFor(text:String) {compose.waitUntil(10_000) {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
 private fun waitForEditor() {compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription("Note text") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}}
 private fun reachableAction(text:String):SemanticsNodeInteraction {
  val action=compose.onNodeWithText(text)
  if(!action.isDisplayed()) action.performScrollTo()
  return action.assertIsDisplayed()
 }
 private fun nextStep(number:Int) {
  reachableAction("Next").performClick()
  waitFor("Getting started · $number of 4")
  compose.onNodeWithText("Getting started · $number of 4").assertIsDisplayed()
 }
 private fun settingsGuideEntry():SemanticsNodeInteraction {
  compose.waitUntil(10_000) {
   compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty() &&
    compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size==1
  }
  compose.onNodeWithContentDescription("Note text").assertDoesNotExist()
  val entry=compose.onNodeWithText("Show the guide")
  // Lazy discovery can compose the row just outside the viewport; then reveal its text.
  if(!entry.isDisplayed()) {
   compose.onNode(hasScrollAction()).performScrollToNode(hasText("Show the guide"))
   if(!entry.isDisplayed()) entry.performScrollTo()
  }
  compose.waitUntil(10_000) {entry.isDisplayed()}
  return entry.assertIsDisplayed()
 }

 @Test fun actualLauncherRestoresStepCompletesOnceAndReplaysBackToSettings() {
  val authority=runBlocking {LocalFixtureAuthority.require(app.container)}
  val original=runBlocking {app.container.repository.draft("capture").first()?.body}
  val scenario=ActivityScenario.launch<MainActivity>(launcher())
  try {
   scenario.onActivity {assertEquals(Intent.ACTION_MAIN,it.intent.action);assertTrue(it.intent.hasCategory(Intent.CATEGORY_LAUNCHER))}
   waitFor("Getting started · 1 of 4")
   compose.onNodeWithContentDescription("Note text").assertDoesNotExist()
   nextStep(2);scenario.recreate()
   waitFor("Getting started · 2 of 4")
   repeat(2) {nextStep(it+3)}
   reachableAction("Start writing").performClick();waitForEditor()
   assertTrue(store.completed)
   scenario.recreate();waitForEditor()
   compose.onNodeWithText("Getting started",substring=true).assertDoesNotExist()
   compose.onNodeWithContentDescription("Settings").performClick()
   settingsGuideEntry().performClick()
   nextStep(2);scenario.recreate()
   waitFor("Getting started · 2 of 4")
   compose.onNodeWithText("Skip").performClick()
   settingsGuideEntry().assertIsDisplayed()
   compose.onNodeWithContentDescription("Note text").assertDoesNotExist()
   assertTrue(store.completed)
  } finally {authority.restoreCaptureDraft(original) {scenario.close()}}
 }
 @Test fun incomingNoteDefersGuideAndItsMarkerUntilANormalLauncherEntry() {
  ActivityScenario.launch<MainActivity>(launcher().putExtra("noteId","synthetic-guide-deferred-missing-note")).use {
   waitFor("This note isn't on this phone.")
   compose.onNodeWithText("Getting started",substring=true).assertDoesNotExist()
   assertFalse(store.completed)
  }
  ActivityScenario.launch<MainActivity>(launcher()).use {
   waitFor("Getting started · 1 of 4")
   compose.onNodeWithContentDescription("Note text").assertDoesNotExist()
   assertFalse(store.completed)
  }
 }
 @Test fun incomingShareKeepsCaptureAndLeavesGuideAvailable() {
  val before=runBlocking {app.container.repository.dao.all().size}
  val share=Intent(context,CaptureActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"Synthetic guide share ${System.nanoTime()}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  ActivityScenario.launch<CaptureActivity>(share).use {
   waitForEditor()
   compose.onNodeWithText("Getting started",substring=true).assertDoesNotExist()
   assertFalse(store.completed)
  }
  assertEquals(before,runBlocking {app.container.repository.dao.all().size})
 }
}
