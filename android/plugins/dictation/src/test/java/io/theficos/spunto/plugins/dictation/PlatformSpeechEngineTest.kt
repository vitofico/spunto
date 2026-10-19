package io.theficos.spunto.plugins.dictation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class PlatformSpeechEngineTest {
 @get:org.junit.Rule val perTestTimeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun segmentedMinimumIsIntAndNeverEnablesOnlineFallbackImplicitly() {
  val intent=PlatformSpeechEngine.recognitionIntent("it-IT",SpeechOptions())
  assertEquals("it-IT",intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
  assertEquals(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,intent.getStringExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION))
  assertEquals(600_000,intent.getIntExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,-1))
  assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,false))
  assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false))
 }
 @Test @Config(sdk=[34]) fun autoUsesOnlyItalianEnglishWithBalancedSwitching() {
  val intent=PlatformSpeechEngine.recognitionIntent("it-IT",SpeechOptions(auto=true))
  assertEquals(arrayListOf("it-IT","en-US"),intent.getStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES))
  assertEquals(RecognizerIntent.LANGUAGE_SWITCH_BALANCED,intent.getStringExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH))
 }
 @Test fun everyPlatformErrorPreservesExpectedReasonCategory() {
  val cases=mapOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to SpeechFailure.Permission,SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE to SpeechFailure.Language,SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED to SpeechFailure.Language,SpeechRecognizer.ERROR_NO_MATCH to SpeechFailure.Empty,SpeechRecognizer.ERROR_SPEECH_TIMEOUT to SpeechFailure.Empty,SpeechRecognizer.ERROR_RECOGNIZER_BUSY to SpeechFailure.Microphone,SpeechRecognizer.ERROR_AUDIO to SpeechFailure.Microphone,SpeechRecognizer.ERROR_NETWORK to SpeechFailure.Network,SpeechRecognizer.ERROR_SERVER_DISCONNECTED to SpeechFailure.Network,SpeechRecognizer.ERROR_TOO_MANY_REQUESTS to SpeechFailure.Network,SpeechRecognizer.ERROR_CLIENT to SpeechFailure.Stopped)
  cases.forEach { (code,reason) -> assertEquals(reason,PlatformSpeechEngine.mapError(code)) }
  assertEquals(SpeechFailure.Stopped,PlatformSpeechEngine.mapError(999))
 }
}
