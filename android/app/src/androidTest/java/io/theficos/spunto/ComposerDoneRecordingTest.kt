package io.theficos.spunto

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.model.uuidV7
import io.theficos.spunto.data.local.RecordingDraftEntity
import io.theficos.spunto.data.local.inspectRecording
import io.theficos.spunto.plugins.dictation.voice.AndroidOggEncoder
import io.theficos.spunto.plugins.dictation.voice.RECORDING_RATE
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.sin

/** Generated audio and a task-owned private note only; never records the microphone. */
@androidx.test.filters.SdkSuppress(minSdkVersion=33)
class ComposerDoneRecordingTest {
 @get:Rule val compose=createComposeRule()
 @get:Rule val timeout=org.junit.rules.Timeout(5,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun doneAttachesARecoveredClipWithoutASecondSaveButton() {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
  val model=SpuntoViewModel(app);val repository=model.repository
  val authority=runBlocking {LocalFixtureAuthority.require(model.container)}
  val id=uuidV7();val clip=uuidV7();val body="Synthetic note with recovered recording"
  val file=File(model.container.recordingDirectory,"$clip.ogg")
  var mounted by mutableStateOf(true)
  runBlocking {model.container.recordingRecovery.await();authority.requireCurrent();repository.privateSyntheticNote(id,body)}
  try {
   runBlocking {
    val encoder=AndroidOggEncoder()
    try {
     encoder.start(file)
     repeat(10) {frame ->
      val samples=ShortArray(1600) {index ->(sin(2*Math.PI*440*(frame*1600+index)/RECORDING_RATE)*4000).toInt().toShort()}
      encoder.write(samples,samples.size,frame*100_000L)
     }
     encoder.finish()
    } finally {encoder.close()}
    val metadata=inspectRecording(file,5_000_000)
    authority.requireCurrent()
    repository.dao.putRecording(RecordingDraftEntity(clip,id,id,file.absolutePath+".partial",file.absolutePath,repository.dao.state()?.serverUrl.orEmpty(),"ready",System.currentTimeMillis(),durationMs=metadata.durationMs,byteSize=metadata.byteSize,sha256=metadata.sha256))
   }
   compose.setContent {SpuntoTheme("carta","light") {if(mounted) ShellLocals {NoteScreen(model,id,onClose={},onNote={})}}}
   compose.waitUntil(10_000) {compose.onAllNodes(editableNote).fetchSemanticsNodes().isNotEmpty()}
   compose.onNode(editableNote).performClick()
   compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription("Note text") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithText("Save").assertDoesNotExist()
   compose.onNodeWithText("Recover recording").performScrollTo().performClick()
   compose.waitUntil(10_000) {compose.onAllNodesWithText("Done to attach this clip. Discard keeps your text.").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithText("Done").performClick()
   compose.waitUntil(10_000) {runBlocking {repository.dao.attachment(clip)!=null} && compose.onAllNodes(editableNote).fetchSemanticsNodes().isNotEmpty()}
   runBlocking {
    assertEquals("retained",repository.dao.recording(clip)?.state)
    assertTrue(repository.dao.get(id)!!.privateNote)
    assertTrue(repository.dao.get(id)!!.body.contains("[recording:$clip]"))
    assertEquals(id,repository.dao.attachment(clip)?.noteId)
   }
  } finally {
   compose.runOnIdle {mounted=false}
   runBlocking {
    authority.requireCurrent()
    repository.dao.outbox().filter {it.noteId==id}.forEach {repository.dao.removeOutbox(it.opId)}
    repository.dao.removeAttachment(clip)
    repository.db.openHelper.writableDatabase.execSQL("DELETE FROM recording_drafts WHERE id=?",arrayOf(clip))
    repository.dao.draftSnapshot(id)?.let {repository.dao.clearDraftSnapshot(id,it.body,it.updatedAt)}
    repository.dao.remove(id)
   }
   file.delete();model.retire()
  }
 }
}
