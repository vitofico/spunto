package io.theficos.spunto.plugins.dictation
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
class DictationTest {
 @get:org.junit.Rule val perTestTimeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun nothingCapturedKeepsSelectedWordsAndReverseSelectionMatches() {
  val untouched=TranscriptBuffer("Keep these words",5,10)
  assertEquals("Keep these words",untouched.text);assertEquals("Keep these words",untouched.commitPartial())
  val reverse=TranscriptBuffer("Idea: old end",9,6);assertEquals("Idea: mappa end",reverse.partial("mappa"));assertEquals(6 until 11,reverse.partialSpan)
 }
 @Test fun partialReplacesSelectionAndFinalSpacing() {
  val buffer=TranscriptBuffer("Idea: old end",6,9)
  assertEquals("Idea: mappa end",buffer.partial("mappa"))
  assertEquals("Idea: mappa del Tevere end",buffer.partial("mappa del Tevere"))
  buffer.final(" mappa del Tevere."); buffer.partial("domani")
  assertEquals("Idea: mappa del Tevere. Domani end",buffer.text)
  assertEquals(buffer.text,buffer.commitPartial())
 }
 @Test fun finalsKeepWordsAndEmptyFinalKeepsPartial() {
  val buffer=TranscriptBuffer("",0)
  buffer.partial("hello,"); buffer.partial("hello world"); buffer.final("")
  buffer.final(" next sentence."); buffer.partial("italiano")
  assertEquals("Hello world next sentence. Italiano",buffer.text)
 }
 @Test fun permissionDoesNotCreateEngine() = runTest {
  var created=0
  val session=DictationSession(backgroundScope,{ created++; FakeSpeechEngine() },object:AudioFocus { override fun acquire()=true; override fun release() {} },{})
  session.start("kept",4,4,"it-IT",false)
  assertEquals(0,created); assertEquals(DictationPhase.NeedsPermission,session.state.value.phase)
 }
 @Test fun errorKeepsPartialAndIgnoresLateReady() = runTest {
  val engine=FakeSpeechEngine()
  val session=DictationSession(backgroundScope,{engine},object:AudioFocus { override fun acquire()=true; override fun release() {} },{})
  session.start("",0,0,"it-IT",true); runCurrent()
  engine.emit(SpeechEvent.Ready); runCurrent(); engine.emit(SpeechEvent.Partial("idea")); runCurrent()
  engine.emit(SpeechEvent.Error(SpeechFailure.Microphone)); runCurrent(); engine.emit(SpeechEvent.Ready); runCurrent()
  assertEquals("Idea",session.state.value.text); assertEquals(DictationPhase.Failed,session.state.value.phase)
 }
 @Test fun switchingLanguageClearsTheOldDownloadNoticeAndKeepsText() = runTest {
  val engine=FakeSpeechEngine()
  val session=DictationSession(backgroundScope,{engine},object:AudioFocus { override fun acquire()=true; override fun release() {} },{})
  session.start("kept",4,4,"it-IT",true); runCurrent()
  engine.emit(SpeechEvent.Error(SpeechFailure.Language)); runCurrent()
  assertEquals(DictationPhase.NeedsLanguage,session.state.value.phase); assertEquals("Italian needs a one-time download to work offline",session.state.value.message)
  session.clearNotice()
  assertEquals(DictationState(text="kept"),session.state.value)
 }
 @Test fun missingReadyRetriesOnce() = runTest {
  var count=0
  val session=DictationSession(backgroundScope,{ count++; FakeSpeechEngine() },object:AudioFocus { override fun acquire()=true; override fun release() {} },{})
  session.start("",0,0,"en-US",true); runCurrent(); advanceTimeBy(4001); runCurrent()
  assertEquals(2,count); assertEquals(DictationPhase.Failed,session.state.value.phase)
 }
}
