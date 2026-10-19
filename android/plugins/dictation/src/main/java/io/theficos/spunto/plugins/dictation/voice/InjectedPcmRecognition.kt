package io.theficos.spunto.plugins.dictation.voice

import android.content.Context
import android.media.AudioFormat
import android.os.*
import android.speech.*
import androidx.annotation.RequiresApi
import io.theficos.spunto.plugins.dictation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Unconfirmed services use recording-only mode: this adapter never falls back to a system microphone. */
@RequiresApi(33)
class InjectedPcmRecognition private constructor(
 private val scope:CoroutineScope,
 private val pipe:Array<ParcelFileDescriptor>,
 private val recognizer:SpeechRecognizer,
 private val ended:CompletableDeferred<Unit>,
):PcmRecognition {
 private val closed=AtomicBoolean(false)
 private var startup:Job?=null
 private val queue=Channel<ByteArray>(8)
 private val writer=scope.launch(Dispatchers.IO) {
  try {ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {output -> for(bytes in queue) output.write(bytes)}}
  catch(cancelled:CancellationException) {throw cancelled}
  catch(_:Exception) {closed.set(true)}
 }
 override fun offer(samples:ShortArray,count:Int):Boolean {
  if(closed.get()) return false
  val bytes=ByteBuffer.allocate(count*2).order(ByteOrder.LITTLE_ENDIAN)
  repeat(count) {bytes.putShort(samples[it])}
  return queue.trySend(bytes.array()).isSuccess
 }
 override suspend fun finish() {
  queue.close()
  if(withTimeoutOrNull(1000) {writer.join();true}!=true) close()
  withTimeoutOrNull(1500) {ended.await()}
 }
 override fun close() {
  closed.set(true);queue.close();writer.cancel();startup?.cancel()
  runCatching {pipe[1].close()};runCatching {pipe[0].close()}
  Handler(Looper.getMainLooper()).post {runCatching {recognizer.cancel()};runCatching {recognizer.destroy()}}
 }
 companion object {
  fun recognitionIntent(language:String,audio:ParcelFileDescriptor)=PlatformSpeechEngine.recognitionIntent(language,SpeechOptions()).apply {
   putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,audio)
   putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1)
   putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
   putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,RECORDING_RATE)
   putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,RecognizerIntent.EXTRA_AUDIO_SOURCE)
  }
  suspend fun open(context:Context,scope:CoroutineScope,language:String,confirmedPcmSupport:Boolean,events:(SpeechEvent)->Unit):PcmRecognition?=withContext(Dispatchers.Main) {
   if(!confirmedPcmSupport || !PlatformSpeechEngine.onDeviceAvailable(context)) return@withContext null
   val pipe=ParcelFileDescriptor.createPipe();val ended=CompletableDeferred<Unit>();val ready=CompletableDeferred<Unit>()
   var recognizer:SpeechRecognizer?=null
   try {
    val local=SpeechRecognizer.createOnDeviceSpeechRecognizer(context);recognizer=local
    local.setRecognitionListener(object:RecognitionListener {
     private fun words(value:Bundle?)=value?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
     override fun onReadyForSpeech(params:Bundle?) {ready.complete(Unit);events(SpeechEvent.Ready)}
     override fun onBeginningOfSpeech()=Unit
     override fun onRmsChanged(rmsdB:Float)=Unit
     override fun onBufferReceived(buffer:ByteArray?)=Unit
     override fun onEndOfSpeech()=Unit
     override fun onPartialResults(value:Bundle?) {events(SpeechEvent.Partial(words(value)))}
     override fun onResults(value:Bundle?) {events(SpeechEvent.Final(words(value)));ended.complete(Unit)}
     override fun onSegmentResults(value:Bundle) {events(SpeechEvent.Final(words(value)))}
     override fun onEndOfSegmentedSession() {ended.complete(Unit)}
     override fun onError(error:Int) {events(SpeechEvent.Error(PlatformSpeechEngine.mapError(error),error));ended.complete(Unit)}
     override fun onEvent(type:Int,params:Bundle?)=Unit
    })
    local.startListening(recognitionIntent(language,pipe[0]))
    InjectedPcmRecognition(scope,pipe,local,ended).also {input ->
     input.startup=scope.launch {if(withTimeoutOrNull(2000) {ready.await();true}!=true) {events(SpeechEvent.Error(SpeechFailure.Stopped));input.close()}}
    }
   } catch(cancelled:CancellationException) {pipe.forEach {runCatching {it.close()}};recognizer?.destroy();throw cancelled}
   catch(_:Exception) {pipe.forEach {runCatching {it.close()}};recognizer?.destroy();null}
  }
 }
}
