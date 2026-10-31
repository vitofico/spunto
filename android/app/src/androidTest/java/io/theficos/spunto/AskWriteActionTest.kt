package io.theficos.spunto

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.theficos.spunto.core.design.SpuntoTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class,ExperimentalCoroutinesApi::class)
class AskWriteActionTest {
 @get:Rule val compose=createComposeRule(effectContext=StandardTestDispatcher())
 private fun action(stage:String,title:String="Create note")=AskWriteAction(title,buildJsonObject {put("action_id","opaque");put("stage",stage)})
 @Test fun previewNeverConfirmsItselfAndUndoRequiresAnotherExplicitTap() {
  var current by mutableStateOf(action("preview"));var pending by mutableStateOf(false);var confirmations=0
  compose.setContent {SpuntoTheme("carta","light") {AskWriteActionControls(current,pending,false,{confirmations++;pending=true},{error("Unexpected cancellation")},created=true)}}
  compose.runOnIdle {assertEquals(0,confirmations)}
  compose.onNodeWithText("Preview only. Nothing changes until you confirm.").assertIsDisplayed()
  compose.onNodeWithText("Create note").performClick()
  compose.runOnIdle {assertEquals(1,confirmations)}
  compose.onNodeWithText("Working…").assertIsNotEnabled();compose.onNodeWithText("Cancel").assertIsNotEnabled()
  compose.runOnIdle {current=action("undo","Undo");pending=false}
  compose.onNodeWithText("Note created.").assertIsDisplayed()
  compose.onNodeWithText("Undo moves this note to Trash if it has not changed. Undo is available only in this temporary chat. Opening a note leaves the chat.").assertIsDisplayed()
  compose.onNodeWithText("Undo").performClick()
  compose.runOnIdle {assertEquals(2,confirmations);current=action("done","Undo complete");pending=false}
  compose.onNodeWithText("Note moved to Trash.").assertIsDisplayed();compose.onNodeWithText("Undo",useUnmergedTree=true).assertDoesNotExist()
 }
 @Test fun cancellingAnEditProposalHidesConfirmationWithoutCallingApply() {
  var cancelled by mutableStateOf(false);var confirms=0
  compose.setContent {SpuntoTheme("carta","light") {AskWriteActionControls(action("preview","Apply change"),false,cancelled,{confirms++},{cancelled=true})}}
  compose.onNodeWithText("Apply change").assertIsDisplayed();compose.onNodeWithText("Cancel").performClick()
  compose.onNodeWithText("Proposal cancelled.").assertIsDisplayed();compose.onNodeWithText("Apply change").assertDoesNotExist()
  compose.runOnIdle {assertEquals(0,confirms)}
 }

 @Test fun editingExplainsGuardedUndoAndRestoresPreviousTextOnConfirmation() {
  var stage by mutableStateOf("undo");var calls=0
  compose.setContent {SpuntoTheme("carta","light") {AskWriteActionControls(action(stage,"Undo"),false,false,{calls++;stage="done"},{},created=false)}}
  compose.onNodeWithText("Changes saved.").assertIsDisplayed()
  compose.onNodeWithText("Undo restores the previous text if the note has not changed. Undo is available only in this temporary chat. Opening a note leaves the chat.").assertIsDisplayed()
  compose.runOnIdle {assertEquals(0,calls)}
  compose.onNodeWithText("Undo").performClick()
  compose.onNodeWithText("Previous text restored.").assertIsDisplayed()
  compose.runOnIdle {assertEquals(1,calls)}
 }

 @Test fun narrowPreviewKeepsTheTargetAndEveryChunkVisibleBeforeExplicitConfirmation() {
  var confirms=0
  val preview=buildJsonObject {put("items",JsonArray(listOf(
   buildJsonObject {put("type","text");put("text","Target note: Weekend shopping")},
   buildJsonObject {put("type","markdown");put("text","Before:\n\n```\nBuy pasta\n```")},
   buildJsonObject {put("type","markdown");put("text","After:\n\n```\nBuy pasta and tomatoes\n```")},
   buildJsonObject {put("type","markdown");put("text","After:\n\n```\nRemember olive oil\n```")}
  )))}
  compose.setContent {SpuntoTheme("carta","light") {Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
   AskWritePreviewContent(preview)
   AskWriteActionControls(action("preview","Apply change"),false,false,{confirms++},{})
  }}}
  compose.onNodeWithText("Target note: Weekend shopping").assertIsDisplayed()
  compose.onNodeWithText("Buy pasta").assertExists()
  compose.onNodeWithText("Buy pasta and tomatoes",substring=true).performScrollTo().assertIsDisplayed()
  compose.onNodeWithText("Remember olive oil",substring=true).performScrollTo().assertIsDisplayed()
  compose.runOnIdle {assertEquals(0,confirms)}
  compose.onNodeWithText("Apply change").performScrollTo().performClick()
  compose.runOnIdle {assertEquals(1,confirms)}
 }

 private fun reply(stage:String)=buildJsonObject {put("ui",1);put("items",JsonArray(listOf(buildJsonObject {put("type","command");put("id","save-answer");put("title",if(stage=="undo") "Undo" else "Undo complete");put("args",action(stage).args)})))}
 @Test fun lazyTurnDisposalKeepsAppliedUndoAndTerminalStateWithoutRepeatingActions() {
  val state=AskWriteState(action("preview"));var calls=0;var disposals=0;var compositions=0;var listGeneration by mutableStateOf(0)
  compose.setContent {SpuntoTheme("carta","light") {
   val listState=rememberLazyListState()
   key(listGeneration) {
   LazyColumn(Modifier.height(220.dp).testTag("write-turns"),state=listState) {
    item(key="action") {
     DisposableEffect(Unit) {compositions++;onDispose {disposals++}}
     AskWriteActionControls(state.action,state.pending,state.cancelled,{
      calls++;val requested=state.action;assertTrue(state.accept(requested,reply(if(requested.stage=="preview") "undo" else "done")))
     },{state.cancelled=true},created=state.created)
    }
    items((1..80).toList(),key={it}) {Text("Later turn $it",Modifier.height(64.dp))}
   }
   }
  }}
  compose.onNodeWithText("Create note").performClick();compose.onNodeWithText("Undo").performScrollTo().assertIsDisplayed()
  compose.onNodeWithTag("write-turns").performScrollToIndex(60)
  // Lazy layouts may cache offscreen compositions; recreate the list to guarantee disposal.
  compose.runOnIdle {listGeneration++}
  compose.runOnIdle {assertEquals(1,disposals);assertEquals(1,calls)}
  compose.onNodeWithTag("write-turns").performScrollToIndex(0)
  compose.onNodeWithText("Undo").performScrollTo()
  compose.waitUntil(10_000) {compose.onNodeWithText("Undo").isDisplayed()}
  compose.onNodeWithText("Undo").assertIsDisplayed();compose.onNodeWithText("Create note").assertDoesNotExist();compose.onNodeWithText("Cancel").assertDoesNotExist()
  compose.runOnIdle {assertEquals(2,compositions)}
  compose.onNodeWithText("Undo").performClick();compose.onNodeWithText("Note moved to Trash.").assertIsDisplayed()
  compose.onNodeWithTag("write-turns").performScrollToIndex(60)
  compose.runOnIdle {listGeneration++}
  compose.runOnIdle {assertEquals(2,disposals)}
  compose.onNodeWithTag("write-turns").performScrollToIndex(0)
  compose.onNodeWithText("Note moved to Trash.").performScrollTo()
  compose.waitUntil(10_000) {compose.onNodeWithText("Note moved to Trash.").isDisplayed()}
  compose.onNodeWithText("Note moved to Trash.").assertIsDisplayed();compose.onNodeWithText("Undo",useUnmergedTree=true).assertDoesNotExist()
  compose.runOnIdle {assertEquals(2,calls);assertEquals(3,compositions)}
 }
 @Test fun lazyTurnDisposalKeepsCancelledProposalCancelled() {
  val state=AskWriteState(action("preview","Apply change"));var calls=0;var disposals=0;var compositions=0;var listGeneration by mutableStateOf(0)
  compose.setContent {SpuntoTheme("carta","light") {
   val listState=rememberLazyListState()
   key(listGeneration) {
   LazyColumn(Modifier.height(220.dp).testTag("write-turns"),state=listState) {
    item(key="action") {
     DisposableEffect(Unit) {compositions++;onDispose {disposals++}}
     AskWriteActionControls(state.action,state.pending,state.cancelled,{calls++},{state.cancelled=true},created=state.created)
    }
    items((1..80).toList(),key={it}) {Text("Later turn $it",Modifier.height(64.dp))}
   }
   }
  }}
  compose.onNodeWithText("Cancel").performClick();compose.onNodeWithText("Proposal cancelled.").assertIsDisplayed()
  compose.onNodeWithTag("write-turns").performScrollToIndex(60)
  // Preserve scroll position while explicitly evicting the cached list composition.
  compose.runOnIdle {listGeneration++}
  compose.runOnIdle {assertEquals(1,disposals)}
  compose.onNodeWithTag("write-turns").performScrollToIndex(0)
  compose.waitUntil(10_000) {compose.onNodeWithText("Proposal cancelled.").isDisplayed()}
  compose.onNodeWithText("Proposal cancelled.").assertIsDisplayed();compose.onNodeWithText("Apply change").assertDoesNotExist()
  compose.runOnIdle {assertEquals(0,calls);assertEquals(2,compositions)}
 }
}
