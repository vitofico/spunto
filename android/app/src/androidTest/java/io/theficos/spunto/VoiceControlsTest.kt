package io.theficos.spunto

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.data.local.RecordingDraftEntity
import io.theficos.spunto.plugins.dictation.voice.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Fake components only: no application database, microphone, recognizer or network. */
class VoiceControlsTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @get:Rule val compose=createComposeRule()
 private val id="019a9d6b-7000-7000-8000-000000000001"
 private fun draft(state:String="recoverable")=RecordingDraftEntity(id,"synthetic",null,"/synthetic/partial","/synthetic/final","https://synthetic.invalid",state,1,durationMs=61_000)
 @Test fun recordingOnlyWaitingShowsAccessibleElapsedLevelAndExplicitStop() {
  var stops=0;var discards=0
  compose.setContent {SpuntoTheme {VoiceControls(RecordingState(RecordingPhase.Recording,id,durationMs=61_000,level=.25f,message="Recording · live text unavailable; transcription after sync"),false,null,emptyList(),true,{stops++},{discards++},{})}}
  compose.onNodeWithContentDescription("Recording elapsed 1:01").assertExists()
  compose.onNodeWithContentDescription("Microphone level").assertExists()
  compose.onNodeWithText("Live text unavailable. Server transcription needs a transcription model; your recording is kept.").assertExists()
  compose.onNodeWithText("Stop recording").performClick()
  compose.runOnIdle {assertEquals(1,stops);assertEquals(0,discards)}
 }
 @Test fun recoveryRequiresOwnerActionAndStillExplainsSaveBeforeAttachment() {
  var recoveries=0;var discards=0;var selected by mutableStateOf<RecordingDraftEntity?>(null)
  compose.setContent {SpuntoTheme {VoiceControls(RecordingState(),false,selected,if(selected==null) listOf(draft()) else emptyList(),true,{}, {discards++}, {recoveries++;selected=draft()})}}
  compose.runOnIdle {assertEquals(0,recoveries);assertEquals(0,discards)}
  compose.onNodeWithText("Recover recording").performClick()
  compose.onNodeWithText("Save to attach this clip. Discard keeps your text.").assertExists()
  compose.onNodeWithText("Discard recording").performClick()
  compose.runOnIdle {assertEquals(1,recoveries);assertEquals(1,discards)}
 }
 @Test fun disabledControlsKeepReadyRecordingVisibleWithoutRunningDiscard() {
  var discards=0
  compose.setContent {SpuntoTheme {VoiceControls(RecordingState(),false,draft("ready"),emptyList(),false,{}, {discards++}, {})}}
  compose.onNodeWithContentDescription("Recording duration 1:01").assertExists()
  compose.onNodeWithText("Discard recording").assertIsNotEnabled()
  compose.runOnIdle {assertEquals(0,discards)}
 }
 @Test fun pendingRecordingStartupBlocksPlainSaveAndDictateButAllowsTyping() {
  var pending by mutableStateOf(true);var text by mutableStateOf("")
  var plainCreates=0;var dictationStarts=0
  compose.setContent {SpuntoTheme {Column {
   BasicTextField(text,{text=it},modifier=Modifier.semantics {contentDescription="Synthetic note text"})
   ComposerSaveAction(true,false,pending) {plainCreates++}
   ComposerDictationAction(true,false,pending) {dictationStarts++}
  }}}
  compose.onNodeWithText("Save").assertIsNotEnabled()
  compose.onNodeWithContentDescription("Dictate").assertIsNotEnabled()
  compose.onNodeWithContentDescription("Synthetic note text").performTextInput("Newer owner words")
  compose.onNodeWithContentDescription("Synthetic note text").assertTextContains("Newer owner words")
  compose.runOnIdle {assertEquals(0,plainCreates);assertEquals(0,dictationStarts);pending=false}
  compose.onNodeWithText("Save").performClick()
  compose.onNodeWithContentDescription("Dictate").performClick()
  compose.runOnIdle {assertEquals(1,plainCreates);assertEquals(1,dictationStarts)}
 }
}
