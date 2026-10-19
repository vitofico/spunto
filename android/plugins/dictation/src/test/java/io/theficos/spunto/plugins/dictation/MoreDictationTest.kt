package io.theficos.spunto.plugins.dictation
import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
class MoreDictationTest {
 @get:org.junit.Rule val perTestTimeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private val focus=object:AudioFocus { override fun acquire()=true;override fun release(){} }
 @Test fun saveWaitsForFinalAndBackgroundKeepsVisiblePartial()=runTest {
  val fake=FakeSpeechEngine();val saved=mutableListOf<String>();val session=DictationSession(backgroundScope,{fake},focus,{saved+=it})
  session.start("",0,0,"en-US",true);runCurrent();fake.emit(SpeechEvent.Ready);runCurrent();fake.emit(SpeechEvent.Partial("final words"));runCurrent()
  val result=async { session.stopForSave() };runCurrent();fake.emit(SpeechEvent.Final("final words."));runCurrent();fake.emit(SpeechEvent.End);runCurrent()
  assertEquals("Final words.",result.await());assertTrue(saved.contains("Final words."))
  session.start("",0,0,"it-IT",true);runCurrent();fake.emit(SpeechEvent.Ready);runCurrent();fake.emit(SpeechEvent.Partial("mappa dei nasoni"));runCurrent();session.stopAndKeep();runCurrent()
  assertEquals("Mappa dei nasoni",session.state.value.text)
 }
 @Test fun saveTimeoutCommitsPartialAndSilenceStops()=runTest {
  val fake=FakeSpeechEngine();val session=DictationSession(backgroundScope,{fake},focus,{})
  session.start("",0,0,"en-US",true);runCurrent();fake.emit(SpeechEvent.Ready);runCurrent();fake.emit(SpeechEvent.Partial("kept"));runCurrent()
  val result=async { session.stopForSave() };runCurrent();advanceTimeBy(1501);runCurrent();assertEquals("Kept",result.await())
  session.start("",0,0,"en-US",true);runCurrent();fake.emit(SpeechEvent.Ready);runCurrent();advanceTimeBy(10_001);runCurrent();assertEquals(DictationPhase.Idle,session.state.value.phase)
 }
}
