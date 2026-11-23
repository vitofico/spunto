package io.theficos.spunto
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import io.theficos.spunto.core.model.OutboxOperation
import kotlinx.serialization.json.Json
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
 @Test fun decisionSurvivesReopeningAndSavesOnceWithTheOriginalBody() {
  val model=SpuntoViewModel(app)
  val authority=runBlocking {LocalFixtureAuthority.require(model.container)}
  val draftId="test-decision:${System.nanoTime()}"
  val body="One decision with #evidence"
  val reason="We can reverse it."
  val mounted=mutableStateOf(true)
  var savedId:String?=null
  try {
   compose.setContent {SpuntoTheme {Column(Modifier.verticalScroll(rememberScrollState())) {
    if(mounted.value) Composer(model,compact=true,draftId=draftId,autoFocus=false,onPlainCaptureSaved={savedId=it},onSave={error("Bypassed capture")})
   }}}
   compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription("Note text") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
   compose.runOnIdle {model.manifest.value=payload("""{"plugins":[{"id":"decision-journal","version":"1.2.0","enabled":true}]}""")}
   compose.onNodeWithContentDescription("Note text").performTextInput(body)
   compose.onNodeWithText("Decision").performScrollTo().performClick()
   val rationale=hasSetTextAction() and hasText("Why this choice? (optional)")
   val date=hasSetTextAction() and hasText("Review date (optional)")
   compose.onNode(rationale).performScrollTo().performTextReplacement(reason)
   compose.onNode(date).performScrollTo().performTextReplacement("2026-02-31")
   compose.onNodeWithText("Save").assertIsNotEnabled()
   compose.onNode(date).performTextReplacement("2027-02-28")
   androidx.test.espresso.Espresso.closeSoftKeyboard()
   compose.runOnIdle {mounted.value=false}
   compose.waitUntil(10_000) {runBlocking {model.repository.dao.draftSnapshot(draftId)?.decisionJournal?.contains("2027-02-28")==true}}
   compose.runOnIdle {mounted.value=true}
   compose.waitUntil(10_000) {compose.onAllNodes(rationale).fetchSemanticsNodes().isNotEmpty()}
   compose.onNode(rationale).assertTextContains(reason)
   compose.onNode(date).assertTextContains("2027-02-28")
   compose.onNodeWithContentDescription("Note text").assertTextContains(body)
   runBlocking {authority.requireCurrent()}
   compose.onNodeWithText("Save").performScrollTo().performClick()
   compose.waitUntil(10_000) {savedId!=null}
   runBlocking {
    val noteId=requireNotNull(savedId)
    assertEquals(body,model.repository.dao.get(noteId)?.body)
    val op=model.repository.dao.outbox().filter {it.noteId==noteId}.single()
    val journal=Json.decodeFromString<OutboxOperation>(op.payload).decisionJournal
    assertNotNull(journal);assertEquals(reason,journal?.rationale);assertEquals("2027-02-28",journal?.reviewOn)
   }
   compose.onNode(rationale).assertDoesNotExist()
  } finally {
   val cleanup=model.repository.beginEditor(null,draftId)
   compose.runOnIdle {mounted.value=false}
   compose.waitForIdle()
   runBlocking {
    authority.requireCurrent()
    savedId?.let {id ->
     model.repository.dao.outbox().filter {it.noteId==id}.forEach {model.repository.dao.removeOutbox(it.opId)}
     model.repository.dao.outputs().first().filter {it.noteId==id}.forEach {model.repository.dao.removeOutput(it.kind,it.id)}
     model.repository.dao.clearLabels(id);model.repository.dao.remove(id)
    }
    model.repository.saveDraft(draftId,"");model.repository.clearDraft(draftId,"")
   }
   model.repository.abandonEditor(cleanup);model.retire()
  }
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
