package io.theficos.spunto.plugins.dictation.voice

import org.junit.Test
import org.junit.Assert.*

class RecordingTextBufferTest {
 @Test fun ownerEditsOutsideTheLiveSpanSurviveAChangedFinalResult() {
  val text=RecordingTextBuffer("Owner suffix",0,0)
  assertEquals("Hello Owner suffix",text.partial("hello").text)
  val typed=text.typed("Prefix Hello Owner suffix edited",0,0)
  assertEquals("Prefix Hello there. Owner suffix edited",text.final("Hello there.").text)
  assertTrue(text.snapshot().revision>typed.revision)
 }
 @Test fun editingTheLiveSpanOwnsThatSegmentAndNextSegmentCanContinue() {
  val text=RecordingTextBuffer("Owner",5,5)
  text.partial("first guess")
  val manual="Owner corrected words";text.typed(manual,manual.length,manual.length)
  assertEquals(manual,text.partial("first guessing more").text)
  assertEquals(manual,text.final("first guessed").text)
  assertEquals("Owner corrected words next segment",text.final("next segment").text)
 }
 @Test fun TypingBeforeAnyAudioOrSpeechDoesNotDeleteTheOriginalSelection() {
  val text=RecordingTextBuffer("Preserve selected owner words",0,8)
  val current=text.typed("Owner replacement without any speech",5,5)
  assertEquals(current,text.finish());assertNull(text.partialSpan)
 }
 @Test fun distinctPrefixAndSuffixReplacementsKeepTheUneditedLiveSpan() {
  val text=RecordingTextBuffer("Old prefix. Old suffix",12,12)
  val partial=text.partial("live guess").text
  assertEquals("Old prefix. Live guess Old suffix",partial)
  text.typed("New prefix. Live guess New suffix",0,0)
  assertEquals("New prefix. Final result New suffix",text.final("Final result").text)
 }
 @Test fun editedOrAmbiguouslyRelocatedSpanDoesNotReplaceOwnerText() {
  val text=RecordingTextBuffer("same later same",0,4)
  text.partial("same")
  val owner="Owner changed later same";text.typed(owner,owner.length,owner.length)
  assertEquals(owner,text.final("recognizer replacement").text)
  val ambiguous=RecordingTextBuffer("",0,0);ambiguous.partial("same")
  ambiguous.typed("Same Same",9,9)
  assertEquals("Same Same",ambiguous.final("would overwrite an owner copy").text)
 }

 @Test fun movingALongLiveFragmentPastAnUnchangedOwnerContextDoesNotRelocateRecognition() {
  val text=RecordingTextBuffer("x",0,0);text.partial("long live utterance with several words")
  val owner="Owner edited the original span x Long live utterance with several words"
  text.typed(owner,owner.length,owner.length)
  assertEquals(owner,text.final("would replace the owner's copied words").text)
 }

}
