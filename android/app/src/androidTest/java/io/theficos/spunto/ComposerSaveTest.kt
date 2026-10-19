package io.theficos.spunto
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
class ComposerSaveTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @get:Rule val compose=createComposeRule()
 private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
 @Test fun failedSaveKeepsTextAndSuccessfulSaveWaitsForLocalCommit() {
  val authority=runBlocking {LocalFixtureAuthority.require(app.container)}
  val model=SpuntoViewModel(app);val gate=CompletableDeferred<Unit>();var attempts=0
  val fixture="Synthetic retained draft ${System.nanoTime()}";val key="test-save:${System.nanoTime()}"
  compose.setContent { SpuntoTheme { Composer(model,initial=fixture,draftId=key,autoFocus=false,onSave={ attempts++;if(attempts==1) error("Synthetic Room failure");gate.await() }) } }
  compose.waitUntil(10_000) { compose.onAllNodes(hasText("Save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
  runBlocking {authority.requireCurrent()}
  compose.onNodeWithText("Save").performClick()
  compose.waitUntil(10_000) { model.message.value.isNotEmpty() }
  compose.onNodeWithContentDescription("Note text").assertTextContains(fixture)
  runBlocking {authority.requireCurrent()}
  compose.onNodeWithText("Save").performClick()
  compose.onNodeWithContentDescription("Note text").assertTextContains(fixture)
  compose.onNodeWithText("Saving…").assertExists()
  compose.runOnIdle { gate.complete(Unit) }
  compose.waitUntil(10_000) { compose.onAllNodesWithText("Saving…").fetchSemanticsNodes().isEmpty() }
  compose.onNodeWithContentDescription("Note text").assertTextEquals("")
 }
 @Test fun shareDraftDoesNotLoadOrClearMainCaptureDraft() {
  val authority=runBlocking {LocalFixtureAuthority.require(app.container)}
  val model=SpuntoViewModel(app);val key="share:test:${System.nanoTime()}";val main=runBlocking { model.repository.draft("capture").first()?.body };val comment="Synthetic share comment ${System.nanoTime()}"
  runBlocking {authority.requireCurrent();model.repository.saveDraft(key,comment)}
  compose.setContent { SpuntoTheme { Composer(model,draftId=key,autoFocus=false,onSave={model.saveLocal(it)}) } }
  compose.waitUntil(10_000) { compose.onAllNodesWithText(comment,substring=true).fetchSemanticsNodes().isNotEmpty() }
  compose.onNodeWithContentDescription("Note text").assertTextContains(comment)
  runBlocking {authority.requireCurrent()}
  compose.onNodeWithText("Save").performClick()
  compose.waitUntil(10_000) { compose.onAllNodesWithText(comment,substring=true).fetchSemanticsNodes().isEmpty() }
  runBlocking { assertEquals(main,model.repository.draft("capture").first()?.body) }
 }
}
