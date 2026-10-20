package io.theficos.spunto

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.model.Note
import io.theficos.spunto.core.model.uuidV7
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.data.local.RevisionEntity
import io.theficos.spunto.data.sync.HistoryRefreshResult
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Rendering fixtures plus one unique private synthetic note; owner settings and capture draft remain untouched. */
@RunWith(AndroidJUnit4::class)
class HistoryAndDateControlsTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @get:Rule val compose=createComposeRule()
 @Test fun offlineCanonicalHistoryRestoresTheExactSelectedBodyAndGuardsAnActiveEditor() {
  val revision=RevisionEntity(id="server/e/2",noteId="synthetic-note",body="Synthetic historical words",at=2000,version=2,historySource="server",changeKind="restore")
  var editing by mutableStateOf(true);var restored:RevisionEntity?=null
  compose.setContent { SpuntoTheme("carta","light") { LazyColumn { revisionHistoryItems(listOf(revision),false,HistoryRefreshResult.Cached("unavailable"),editing) {restored=it} } } }
  compose.onNodeWithText("Showing history saved on this phone.").assertIsDisplayed();compose.onNodeWithText("Finish editing before restoring a revision.").assertIsDisplayed()
  compose.onNodeWithText("Restore this revision").assertIsNotEnabled();compose.runOnIdle {editing=false}
  compose.onNodeWithText("Restore this revision").performClick();compose.runOnIdle {assertEquals(revision,restored)}
 }
 @Test fun dateFilterSheetPreventsReversedRangesAndKeepsClearExplicit() {
  var from by mutableStateOf("2026-10-15");var to by mutableStateOf("2026-10-14")
  compose.setContent {SpuntoTheme("carta","light") {NoteFilterContent(from,to,java.time.LocalDate.of(2026,10,20),{from=it},{to=it},{},{},{})}}
  compose.onNodeWithText("Choose an end date on or after the start date.").assertIsDisplayed()
  compose.onNodeWithText("Apply filters").assertIsNotEnabled()
  compose.onNodeWithContentDescription("Choose start date").assertTextContains("15 Oct 2026")
  compose.onNodeWithText("Clear dates").performClick()
  compose.runOnIdle {assertEquals("",from);assertEquals("",to)}
  compose.onNodeWithText("Apply filters").assertIsEnabled()
  compose.onNodeWithText("Last 7 days").performClick()
  compose.runOnIdle {assertEquals("2026-10-14",from);assertEquals("2026-10-20",to)}
 }
 @Test fun editReadRestoreEditLoadsRestoredWordsAndKeepsSubsequentTyping() {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
  val model=SpuntoViewModel(app);val repository=model.repository;val id=uuidV7()
  val a="Synthetic history A "+System.nanoTime();val b="Synthetic history B "+System.nanoTime();val d="Synthetic history D "+System.nanoTime()
  val capture=runBlocking {repository.dao.draftSnapshot("capture")}
  runBlocking {repository.putNote(Note(id,a,privateNote=true))}
  try {
   compose.setContent {SpuntoTheme("carta","light") {NoteScreen(model,id,onClose={},onNote={})}}
   compose.waitUntil(10_000) {compose.onAllNodesWithText("Edit").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithText("Edit").performClick()
   compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription("Note text") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithContentDescription("Note text").performTextReplacement(b)
   compose.onNodeWithText("Done").performClick()
   compose.waitUntil(10_000) {runBlocking {repository.dao.get(id)?.body==b} && compose.onAllNodesWithText("Edit").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithContentDescription("More note actions").performClick()
   compose.onNodeWithText("History").performClick()
   compose.onNodeWithText("Restore this revision").performScrollTo().performClick()
   compose.waitUntil(10_000) {runBlocking {repository.dao.get(id)?.body==a}}
   compose.onNodeWithText("Close history").performScrollTo().performClick()
   compose.onNodeWithText("Edit").performClick()
   compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription("Note text") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithContentDescription("Note text").assertTextEquals(a)
   compose.mainClock.advanceTimeBy(5500);compose.waitForIdle()
   assertEquals(a,runBlocking {repository.dao.get(id)!!.body})
   compose.onNodeWithContentDescription("Note text").performTextReplacement(d)
   compose.onNodeWithText("Done").performClick()
   compose.waitUntil(10_000) {runBlocking {repository.dao.get(id)?.body==d}}
   assertTrue(runBlocking {repository.history(id).first().any {it.body==b && it.changeKind=="restore"}})
  } finally {
   compose.runOnIdle { /* Finish UI callbacks before checking the untouched primary capture draft. */ }
   assertEquals(capture,runBlocking {repository.dao.draftSnapshot("capture")})
  }
 }

 @Test fun editorOpenedWithStaleObservedBodyUsesTheCompletedRestoreAndKeepsNewTyping() {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
  val authority=runBlocking {LocalFixtureAuthority.require(app.container)}
  val model=SpuntoViewModel(app);val repository=model.repository;val id=uuidV7()
  val a="Synthetic restored snapshot A "+System.nanoTime();val b="Synthetic stale observed B "+System.nanoTime();val d="Synthetic subsequent typing D "+System.nanoTime()
  val capture=runBlocking {repository.dao.draftSnapshot("capture")}
  runBlocking {
   authority.requireCurrent()
   repository.putNote(Note(id,a,privateNote=true))
   val editor=repository.beginEditor(id)
   assertTrue(repository.finishEditor(repository.editorWrite(editor,b)))
   val original=repository.history(id).first().first {it.body==a}
   repository.restoreHistory(id,original)
   assertEquals(a,repository.dao.get(id)!!.body)
   assertNull(repository.dao.draftSnapshot(id))
  }
  // Model the exact restore-to-observer gap without relying on Room's delivery timing.
  compose.setContent {SpuntoTheme("carta","light") {Composer(model,id,b,autoFocus=false,onSave={})}}
  compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription("Note text") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithContentDescription("Note text").assertTextEquals(a)
  compose.mainClock.advanceTimeBy(5500);compose.waitForIdle()
  assertEquals(a,runBlocking {repository.dao.get(id)!!.body})
  runBlocking {authority.requireCurrent()}
  compose.onNodeWithContentDescription("Note text").performTextReplacement(d)
  compose.waitUntil(10_000) {runBlocking {repository.dao.draftSnapshot(id)?.body==d}}
  compose.mainClock.advanceTimeBy(5500)
  compose.waitUntil(10_000) {runBlocking {repository.dao.get(id)?.body==d}}
  compose.onNodeWithContentDescription("Note text").assertTextEquals(d)
  assertEquals(capture,runBlocking {repository.dao.draftSnapshot("capture")})
 }

 @Test fun selectedQuoteStartsOutsideTheSavedQuoteAndRetainedDraftStillWins() {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
  val authority=runBlocking {LocalFixtureAuthority.require(app.container)}
  val model=SpuntoViewModel(app);val repository=model.repository;val id=uuidV7();val resumed=uuidV7()
  val quote="> Synthetic saved selection "+System.nanoTime();val thought="Synthetic new thought "+System.nanoTime()
  val retained="> Synthetic retained selection\n\nUnfinished thought "+System.nanoTime()
  val capture=runBlocking {repository.dao.draftSnapshot("capture")}
  runBlocking {
   authority.requireCurrent()
   repository.putNote(Note(id,quote,privateNote=true));repository.putNote(Note(resumed,"> Synthetic resumed quote",privateNote=true))
   repository.saveDraft(resumed,retained)
  }
  val selected=mutableStateOf(id)
  compose.setContent {SpuntoTheme("carta","light") {Composer(model,selected.value,"Stale selection",autoFocus=false,startNewParagraph=true,onSave={})}}
  compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription("Note text") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithContentDescription("Note text").assertTextEquals(quote+"\n\n").performTextInput(thought)
  compose.onNodeWithContentDescription("Note text").assertTextEquals(quote+"\n\n"+thought)
  compose.waitUntil(10_000) {runBlocking {repository.dao.draftSnapshot(id)?.body==quote+"\n\n"+thought}}
  compose.runOnIdle {selected.value=resumed}
  compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription("Note text") and hasText(retained) and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithContentDescription("Note text").assertTextEquals(retained)
  assertEquals(capture,runBlocking {repository.dao.draftSnapshot("capture")})
 }

 @Test fun noteRemovedBeforeEditorDisposalKeepsDraftWithoutCrashingOrResurrecting() {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
  val model=SpuntoViewModel(app);val repository=model.repository;val id=uuidV7()
  val body="Synthetic deleted editor "+System.nanoTime();var shown by mutableStateOf(true)
  runBlocking {repository.putNote(Note(id,body,privateNote=true));repository.dao.putDraft(io.theficos.spunto.data.local.DraftEntity(id,body,1))}
  compose.setContent {SpuntoTheme("carta","light") {if(shown) Composer(model,id,body,autoFocus=false,onSave={}) else androidx.compose.material3.Text("Editor closed")}}
  compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription("Note text") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
  compose.waitUntil(10_000) {runBlocking {repository.dao.draftSnapshot(id)!!.updatedAt>1}}
  // New words have not reached the debounce yet when the note disappears.
  val latest=body+" retained last words"
  compose.onNodeWithContentDescription("Note text").performTextReplacement(latest)
  runBlocking {repository.dao.remove(id)}
  compose.runOnIdle {shown=false}
  compose.waitUntil(10_000) {model.message.value=="This note is no longer available."}
  compose.onNodeWithText("Editor closed").assertIsDisplayed()
  assertNull(runBlocking {repository.dao.get(id)})
  assertEquals(latest,runBlocking {repository.dao.draftSnapshot(id)!!.body})
  assertTrue(runBlocking {repository.dao.outbox().none {it.noteId==id}})
 }

 @Test fun mountedEditorAutosaveAfterPendingTombstoneKeepsWordsAndReportsSafely() {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
  val model=SpuntoViewModel(app);val repository=model.repository;val id=uuidV7()
  val original="Synthetic tombstoned committed "+System.nanoTime();val typed="Synthetic tombstoned visible words "+System.nanoTime()
  runBlocking {repository.putNote(Note(id,original,privateNote=true));repository.update(id,original)}
  compose.setContent {SpuntoTheme("carta","light") {Composer(model,id,original,autoFocus=false,onSave={})}}
  compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription("Note text") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithContentDescription("Note text").performTextReplacement(typed)
  compose.waitUntil(10_000) {runBlocking {repository.dao.draftSnapshot(id)?.body==typed}}
  val queued=runBlocking {repository.dao.outbox().filter {it.noteId==id}.map {it.opId to it.payload}}
  assertEquals(original,runBlocking {repository.dao.get(id)!!.body})
  runBlocking {
   repository.dao.putTombstone(io.theficos.spunto.data.local.TombstoneEntity(id,System.currentTimeMillis()))
   repository.putNote(repository.dao.get(id)!!.note().copy(trashed=true,syncError="deleted_on_server"))
  }
  // The editor deliberately remains mounted, as it does for a tombstone with pending edits.
  compose.mainClock.advanceTimeBy(5032)
  compose.waitUntil(10_000) {model.message.value=="This note is no longer available."}
  compose.onNodeWithContentDescription("Note text").assertIsEnabled().assertTextEquals(typed)
  assertEquals(typed,runBlocking {repository.dao.draftSnapshot(id)!!.body})
  assertEquals(original,runBlocking {repository.dao.get(id)!!.body});assertTrue(runBlocking {repository.dao.get(id)!!.trashed})
  assertEquals(queued,runBlocking {repository.dao.outbox().filter {it.noteId==id}.map {it.opId to it.payload}})
 }

}
