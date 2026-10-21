package io.theficos.spunto

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.model.Note
import io.theficos.spunto.core.model.uuidV7
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real editor/repository behavior with a task-owned private synthetic note only. */
@RunWith(AndroidJUnit4::class)
class NoteReaderUxTest {
 @get:Rule val compose=createComposeRule()
 @get:Rule val timeout=org.junit.rules.Timeout(5,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun readerKeepsEditingInReachAndPutsSecondaryActionsInOneSheet() {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
  val model=SpuntoViewModel(app);val repository=model.repository
  val authority=runBlocking {LocalFixtureAuthority.require(model.container)}
  val id=uuidV7();val changed="Synthetic reader edit ${System.nanoTime()}"
  val original=(1..60).joinToString("\n\n") {"Synthetic long reader paragraph $it with enough words to fill the screen."}
  var mounted by mutableStateOf(true)
  val capture=runBlocking {repository.dao.draftSnapshot("capture")}
  runBlocking {authority.requireCurrent();repository.putNote(Note(id,original,privateNote=true))}
  try {
   compose.setContent {if(mounted) SpuntoApp(model,initialNoteId=id)}
   compose.waitUntil(10_000) {compose.onAllNodesWithText("Edit").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithText("Edit").assertIsDisplayed()
   compose.onNodeWithContentDescription("More note actions").assertIsDisplayed()
   listOf("About this note","History","Share","Save Markdown","Pin","Archive","Move to trash").forEach {compose.onNodeWithText(it).assertDoesNotExist()}
   compose.onNodeWithContentDescription("Private note").assertDoesNotExist()
   compose.onNode(hasScrollAction()).performTouchInput {swipeUp()}
   compose.onNodeWithText("Edit").assertIsDisplayed().performClick()
   compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription("Note text") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithText("Done").assertIsDisplayed()
   compose.onNodeWithText("Save",useUnmergedTree=true).assertDoesNotExist()
   compose.onNodeWithContentDescription("More note actions").assertDoesNotExist()
   compose.onNodeWithContentDescription("Note text").performTextReplacement(changed)
   compose.onNodeWithText("Done").performClick()
   compose.waitUntil(10_000) {runBlocking {repository.dao.get(id)?.body==changed} && compose.onAllNodesWithText("Edit").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithContentDescription("More note actions").performClick()
   compose.onNodeWithText("Note actions").assertIsDisplayed()
   compose.onNodeWithText("About this note").assertIsDisplayed()
   compose.onNodeWithText("History").assertIsDisplayed()
   compose.onNodeWithContentDescription("Private note").assertIsOn()
   compose.onNodeWithText("Pin").performClick()
   compose.waitUntil(10_000) {runBlocking {repository.dao.get(id)?.pinned==true}}
   compose.onNodeWithText("Note actions").assertDoesNotExist()
   compose.onNodeWithText("Edit").assertIsDisplayed()
   compose.onNodeWithText("Edit").performClick()
   compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription("Note text") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
   val backEdit=changed+" saved through the visible Back button"
   compose.onNodeWithContentDescription("Note text").performTextReplacement(backEdit)
   compose.onNodeWithContentDescription("Back").performClick()
   compose.waitUntil(10_000) {runBlocking {repository.dao.get(id)?.body==backEdit} && compose.onAllNodesWithText("Edit").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithContentDescription("More note actions").assertIsDisplayed()
   assertEquals(capture,runBlocking {repository.dao.draftSnapshot("capture")})
  } finally {
   compose.runOnIdle {mounted=false}
   runBlocking {
    authority.requireCurrent()
    repository.dao.outbox().filter {it.noteId==id}.forEach {repository.dao.removeOutbox(it.opId)}
    repository.dao.draftSnapshot(id)?.let {repository.dao.clearDraftSnapshot(id,it.body,it.updatedAt)}
    repository.dao.remove(id)
   }
   model.retire()
  }
 }
}
