package io.theficos.spunto.plugins.dictation.voice

import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import android.media.AudioFormat
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class InjectedPcmIntentTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun injectionAlwaysUsesOnePcmPipeAndNeverRequestsOnlineRecognition() {
  val pipe=ParcelFileDescriptor.createPipe()
  try {
   val intent=InjectedPcmRecognition.recognitionIntent("it-IT",pipe[0])
   assertEquals(RecognizerIntent.EXTRA_AUDIO_SOURCE,intent.getStringExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION))
   assertEquals(1,intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,0));assertEquals(RECORDING_RATE,intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,0))
   assertEquals(AudioFormat.ENCODING_PCM_16BIT,intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,0));assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,false))
   assertNotNull(intent.getParcelableExtra<ParcelFileDescriptor>(RecognizerIntent.EXTRA_AUDIO_SOURCE))
  } finally {pipe.forEach {it.close()}}
 }
}
