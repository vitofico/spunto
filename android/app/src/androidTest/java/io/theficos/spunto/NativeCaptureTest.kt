package io.theficos.spunto

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
@RunWith(AndroidJUnit4::class)
class NativeCaptureTest {
 private val perTestTimeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private val compose=createAndroidComposeRule<MainActivity>()
 @get:Rule val rules=org.junit.rules.RuleChain.outerRule(perTestTimeout).around(GuidePreferenceFixture(true)).around(compose)
 @Test fun localCaptureAndNavigation() {
  val container=(compose.activity.application as SpuntoApplication).container
  val authority=runBlocking {LocalFixtureAuthority.require(container)}
  val repository=container.repository
  val original=runBlocking { repository.draft("capture").first()?.body }
  val unique=System.nanoTime().toString()
  val fixture="Synthetic emulator idea $unique #m5"
  try {
   compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Note text") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
   runBlocking {authority.requireCurrent()}
   compose.onNodeWithContentDescription("Note text").performTextClearance()
   compose.onNodeWithContentDescription("Note text").performTextInput(fixture)
   runBlocking {authority.requireCurrent()}
   compose.onNodeWithText("Save",useUnmergedTree=true).performClick()
   compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Note text") and hasText("") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
   val committed=runBlocking { withTimeout(10_000) { repository.notes.first { notes -> notes.any { it.body==fixture } }.single { it.body==fixture } } }
   assertEquals(fixture,committed.body)
   compose.onNodeWithText("Search",useUnmergedTree=true).performClick()
   compose.onNodeWithText("Search notes",useUnmergedTree=true).assertExists()
   compose.onNode(hasSetTextAction() and hasText("Search notes")).performTextInput(unique)
   compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Note,",substring=true).fetchSemanticsNodes().any { node -> node.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].any { it.contains(fixture) } } }
  } finally {
   authority.restoreCaptureDraft(original) {compose.activityRule.scenario.close()}
  }
 }
}
