package io.theficos.spunto

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.plugins.dictation.DictationNoticeKind
import io.theficos.spunto.plugins.dictation.DictationState
import io.theficos.spunto.plugins.dictation.voice.RecordingNotice
import io.theficos.spunto.plugins.dictation.voice.RecordingState
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28])
class SystemLocalizationTest {
 private fun context(language:String):Context {
  val base=ApplicationProvider.getApplicationContext<Context>()
  val configuration=Configuration(base.resources.configuration)
  configuration.setLocale(Locale.forLanguageTag(language))
  return base.createConfigurationContext(configuration)
 }
 @Test fun speechNoticesFollowUiLanguageAndPreserveCapturedText() {
  val captured="Testo originale — #Project/Spunto"
  val notice=DictationState(text=captured,notice=DictationNoticeKind.LanguageDownload,language="it-IT")
  for(language in listOf("en","es","it","fr","de")) {
   val context=context(language)
   val languageName=Locale.ITALIAN.getDisplayLanguage(Locale.forLanguageTag(language))
   assertEquals(context.getString(R.string.sys_dictation_language_download,languageName),dictationMessage(context,notice))
   assertEquals(captured,notice.text)
   for(kind in DictationNoticeKind.entries) assertTrue(dictationMessage(context,notice.copy(notice=kind)).isNotBlank())
   for(kind in RecordingNotice.entries) assertTrue(recordingMessage(context,RecordingState(notice=kind)).isNotBlank())
  }
  assertNotEquals(dictationMessage(context("en"),notice),dictationMessage(context("it"),notice))
  assertEquals(context("en").getString(R.string.sys_no_speech),context("pt").getString(R.string.sys_no_speech))
  assertEquals("",dictationMessage(context("it"),DictationState()))
  assertEquals("",recordingMessage(context("it"),RecordingState()))
 }
}
