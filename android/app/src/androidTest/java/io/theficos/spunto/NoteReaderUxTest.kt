package io.theficos.spunto

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
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
  runBlocking {authority.requireCurrent();repository.privateSyntheticNote(id,original)}
  try {
   compose.setContent {if(mounted) SpuntoApp(model,initialNoteId=id)}
   compose.waitUntil(10_000) {compose.onAllNodes(editableNote).fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithContentDescription("More note actions").assertIsDisplayed()
   listOf("History","Share","Save as Markdown","Pin","Archive","Move to trash").forEach {compose.onNodeWithText(it).assertDoesNotExist()}
   compose.onNode(hasScrollAction()).performTouchInput {swipeUp()}
   // The text itself is the way in, wherever the reader has scrolled to.
   compose.onNode(editableNote).performSemanticsAction(SemanticsActions.OnClick)
   compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription("Note text") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithText("Done").assertIsDisplayed()
   compose.onNodeWithText("Save",useUnmergedTree=true).assertDoesNotExist()
   compose.onNodeWithContentDescription("More note actions").assertDoesNotExist()
   compose.onNodeWithContentDescription("Note text").performTextReplacement(changed)
   compose.onNodeWithText("Done").performClick()
   compose.waitUntil(10_000) {runBlocking {repository.dao.get(id)?.body==changed} && compose.onAllNodes(editableNote).fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithContentDescription("More note actions").performClick()
   compose.onNodeWithText("History").assertIsDisplayed()
   compose.onNodeWithText("Move to trash").assertIsDisplayed()
   compose.onNode(isToggleable() and hasText("Private")).assertIsOn()
   compose.onNodeWithText("Pin").performClick()
   compose.waitUntil(10_000) {runBlocking {repository.dao.get(id)?.pinned==true}}
   compose.onNodeWithText("Move to trash").assertDoesNotExist()
   compose.onNode(editableNote).performSemanticsAction(SemanticsActions.OnClick)
   compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription("Note text") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
   val backEdit=changed+" saved through the visible Back button"
   compose.onNodeWithContentDescription("Note text").performTextReplacement(backEdit)
   compose.onNodeWithContentDescription("Back").performClick()
   compose.waitUntil(10_000) {runBlocking {repository.dao.get(id)?.body==backEdit} && compose.onAllNodes(editableNote).fetchSemanticsNodes().isNotEmpty()}
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
 @Test fun tappingTheTextOpensTheEditorWithTheCaretWhereTheFingerLanded() {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
  val model=SpuntoViewModel(app);val repository=model.repository
  val authority=runBlocking {LocalFixtureAuthority.require(model.container)}
  val id=uuidV7();val body="Synthetic caret note\n\nSecond with **bold** words and a [link](https://example.org) here.\n\n- one item tail"
  val rendered="Second with bold words and a link here."
  var mounted by mutableStateOf(true)
  runBlocking {authority.requireCurrent();repository.putNote(Note(id,body,privateNote=true))}
  fun tap(text:String,at:(TextLayoutResult)->Offset):TextRange {
   compose.waitUntil(10_000) {compose.onAllNodes(editableNote).fetchSemanticsNodes().isNotEmpty()}
   val node=compose.onNodeWithText(text,useUnmergedTree=true);val layouts=mutableListOf<TextLayoutResult>()
   node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
   node.performTouchInput {click(at(layouts.first()))}
   compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription("Note text") and isEnabled() and isFocused()).fetchSemanticsNodes().isNotEmpty()}
   val caret=compose.onNodeWithContentDescription("Note text").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
   compose.onNodeWithText("Done").performClick()
   return caret
  }
  try {
   compose.setContent {if(mounted) SpuntoApp(model,initialNoteId=id)}
   // On a word after markup: the caret goes just before it, not to the end of the note.
   assertEquals(TextRange(body.indexOf("words")),tap(rendered) {val box=it.getBoundingBox(rendered.indexOf("words"));Offset(box.left+1f,box.center.y)})
   assertEquals(TextRange(body.indexOf("here.")+5),tap(rendered) {val box=it.getBoundingBox(rendered.length-1);Offset(box.right-1f,box.center.y)})
   assertEquals(TextRange(body.indexOf("one item")),tap("one item tail") {val box=it.getBoundingBox(0);Offset(box.left+1f,box.center.y)})
   assertEquals(body,runBlocking {repository.dao.get(id)?.body})
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
