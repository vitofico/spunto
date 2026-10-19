package io.theficos.spunto.plugins.dictation.voice

import io.theficos.spunto.plugins.dictation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

const val RECORDING_RATE=16_000
const val RECORDING_BITRATE=24_000
const val RECORDING_MAX_SECONDS=3600L

interface PcmSource:AutoCloseable { fun start();suspend fun read(buffer:ShortArray):Int;fun stop() }
interface RecordingEncoder:AutoCloseable { fun start(file:File);suspend fun write(samples:ShortArray,count:Int,timeUs:Long);suspend fun finish() }
/** Only injected samples; implementations must never acquire their own microphone. */
interface PcmRecognition:AutoCloseable { fun offer(samples:ShortArray,count:Int):Boolean;suspend fun finish() }
fun interface PcmRecognitionFactory { suspend fun open(language:String,events:(SpeechEvent)->Unit):PcmRecognition? }

data class ReadyRecording(val id:String,val path:String,val durationMs:Long,val byteSize:Long,val text:String,val capturedText:Boolean)
sealed interface RecordingOutcome {
 data class Ready(val recording:ReadyRecording,val reason:String?=null):RecordingOutcome
 data class Interrupted(val id:String,val reason:String):RecordingOutcome
 data class Discarded(val id:String):RecordingOutcome
}
enum class RecordingPhase { Idle,Starting,Recording,Stopping,Ready,Failed,Discarded }
data class RecordingState(val phase:RecordingPhase=RecordingPhase.Idle,val id:String?=null,val durationMs:Long=0,val level:Float=0f,val text:String="",val textRevision:Long=0,val partialSpan:IntRange?=null,val recordingOnly:Boolean=true,val message:String="",val recording:ReadyRecording?=null)
class RecordingFailure(val reason:String):Exception(reason)

/** Explicit-start foreground session. The existing ordinary DictationSession is not changed. */
class RecordingSession(
 private val scope:CoroutineScope,
 private val sourceFactory:()->PcmSource,
 private val encoderFactory:()->RecordingEncoder,
 private val focus:AudioFocus,
 private val recognitionFactory:PcmRecognitionFactory?=null,
 private val onOutcome:suspend (RecordingOutcome)->Unit={},
 private val nanoTime:()->Long=System::nanoTime,
 private val persistText:suspend (RecordingTextSnapshot)->Unit={},
 private val isEnabled:()->Boolean={false},
) {
 private val mutable=MutableStateFlow(RecordingState())
 val state:StateFlow<RecordingState> = mutable.asStateFlow()
 private var job:Job?=null
 @Volatile private var source:PcmSource?=null
 @Volatile private var recognition:PcmRecognition?=null
 private val textLock=Any()
 private var textBuffer:RecordingTextBuffer?=null
 private val stopping=AtomicBoolean(false)
 private val discarded=AtomicBoolean(false)
 private val discardMutex=Mutex()
 private val discardAttempts=AtomicLong(0)
 @Volatile private var generation=0
 private var target:File?=null
 private var sessionId:String?=null
 suspend fun start(id:String,file:File,initialText:String="",selectionStart:Int=initialText.length,selectionEnd:Int=selectionStart,language:String="en-US",maximumBytes:Long=20_000_000L) {
  // Reject an active capture promptly, then recheck after a prior discard finishes.
  check(job?.isActive!=true && state.value.phase!=RecordingPhase.Ready) {"Save or discard the current recording first."}
  discardMutex.withLock {
  check(job?.isActive!=true && state.value.phase!=RecordingPhase.Ready) {"Save or discard the current recording first."}
  require(isEnabled()) {"Enable Voice memos before recording."}
  require(maximumBytes in 100_000L..1_000_000_000L)
  require(!file.exists() || file.length()==0L) {"Use a new recording file."}
  if(file.name.endsWith(".partial")) require(!File(file.parentFile,file.name.removeSuffix(".partial")).exists()) {"Recording already exists."}
  require(language in setOf("it-IT","en-US"))
  target=file;sessionId=id;stopping.set(false);discarded.set(false);discardAttempts.set(0)
  val token=++generation
  synchronized(textLock) {textBuffer=RecordingTextBuffer(initialText,selectionStart,selectionEnd)}
  mutable.value=RecordingState(RecordingPhase.Starting,id,text=initialText,message="Starting recording…")
  job=scope.launch(Dispatchers.IO) {
   var encoder:RecordingEncoder?=null;var frames=0L;var failure:String?=null
   var lastSpeech=nanoTime();var captured=false;var lastPartialWrite=Long.MIN_VALUE
   val events=Channel<SpeechEvent>(32);val overflow=AtomicBoolean(false)
   val audio=Channel<ShortArray>(8);var reader:Job?=null;val stalled=AtomicBoolean(false)
   fun textChange(change:(RecordingTextBuffer)->RecordingTextSnapshot):RecordingTextSnapshot=synchronized(textLock) {
    val buffer=requireNotNull(textBuffer);val snapshot=change(buffer)
    mutable.update {it.copy(text=snapshot.text,textRevision=snapshot.revision,partialSpan=buffer.partialSpan)};snapshot
   }
   suspend fun applyEvents() {
    while(true) when(val event=events.tryReceive().getOrNull()?:break) {
     is SpeechEvent.Partial -> {lastSpeech=nanoTime();val snapshot=textChange {buffer ->val prior=buffer.snapshot();buffer.partial(event.text).also {value ->captured=captured || event.text.isNotBlank() && value.text!=prior.text}};val now=nanoTime();if(lastPartialWrite==Long.MIN_VALUE || now-lastPartialWrite>=1_000_000_000L) {persistText(snapshot);lastPartialWrite=now}}
     is SpeechEvent.Final -> {lastSpeech=nanoTime();val snapshot=textChange {buffer ->val prior=buffer.snapshot();buffer.final(event.text).also {value ->captured=captured || event.text.isNotBlank() && value.text!=prior.text}};persistText(snapshot)}
     is SpeechEvent.Error -> {recognition?.close();recognition=null;mutable.update {it.copy(recordingOnly=true,message="Recording · live text unavailable; transcription after sync")}}
     else -> Unit
    }
    if(overflow.get() || stalled.get()) {recognition?.close();recognition=null;mutable.update {it.copy(recordingOnly=true,message="Recording · live text unavailable; transcription after sync")}}
   }
   suspend fun publish(outcome:RecordingOutcome):Boolean {
    return try {onOutcome(outcome);true} catch(cancelled:CancellationException) {throw cancelled} catch(_:Exception) {mutable.update {it.copy(message="Recording kept locally · storage needs retry")};false}
   }
   try {
    if(stopping.get() || !isEnabled()) throw RecordingFailure("interrupted")
    if(!focus.acquire()) throw RecordingFailure("microphone_busy")
    encoder=encoderFactory();encoder!!.start(file)
    source=sourceFactory();if(stopping.get() || !isEnabled()) throw RecordingFailure("interrupted");source!!.start()
    recognition=try {recognitionFactory?.open(language) {event ->
     if(token==generation && !discarded.get() && !events.trySend(event).isSuccess) overflow.set(true)
    }} catch(cancelled:CancellationException) {throw cancelled} catch(_:Exception) {null}
    mutable.update {it.copy(phase=RecordingPhase.Recording,recordingOnly=recognition==null,message=if(recognition==null) "Recording · live text unavailable; transcription after sync" else "Recording · on this phone")}
    reader=CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
     try {
      val chunk=ShortArray(1600);var lastAudio=nanoTime()
      while(isActive && !stopping.get()) {
       if(!isEnabled()) throw RecordingFailure("voice_disabled")
       val count=source!!.read(chunk)
       if(count<0) {if(stopping.get()) break else throw RecordingFailure("microphone_failed")}
       if(count==0) {if(nanoTime()-lastAudio>=2_000_000_000L) throw RecordingFailure("microphone_stalled");delay(10);continue}
       lastAudio=nanoTime()
       require(count<=chunk.size)
       val samples=chunk.copyOf(count)
       if(!audio.trySend(samples).isSuccess) throw RecordingFailure("recording_stalled")
       recognition?.let {if(!it.offer(samples,count)) stalled.set(true)}
      }
      audio.close()
     } catch(error:Exception) {audio.close(error)}
    }
    while(currentCoroutineContext().isActive && !discarded.get()) {
     applyEvents()
     val next=withTimeoutOrNull(100) {audio.receiveCatching()}?:continue;next.exceptionOrNull()?.let {throw it}
     val samples=next.getOrNull()?:break;val count=samples.size
     encoder!!.write(samples,count,frames*1_000_000L/RECORDING_RATE)
     frames+=count
     var amplitude=0;repeat(count) {amplitude=maxOf(amplitude,kotlin.math.abs(samples[it].toInt()))};val level=amplitude/32768f
     if(amplitude>500) lastSpeech=nanoTime()
     mutable.update {it.copy(durationMs=frames*1000/RECORDING_RATE,level=level)}
     if(frames>=RECORDING_MAX_SECONDS*RECORDING_RATE || file.length()>=maximumBytes-65_536) {failure="recording_limit";break}
     if(nanoTime()-lastSpeech>=10_000_000_000L) break
    }
   } catch(cancelled:CancellationException) {failure="interrupted";throw cancelled}
   catch(error:RecordingFailure) {failure=error.reason}
   catch(_:Exception) {failure="recording_failed"}
   finally {
    withContext(NonCancellable+Dispatchers.IO) {
     stopping.set(true);mutable.update {it.copy(phase=RecordingPhase.Stopping)}
     runCatching {source?.stop()};runCatching {source?.close()}
     reader?.cancelAndJoin();audio.close();source=null
     try {recognition?.finish()} catch(_:Exception) { };runCatching {recognition?.close()};recognition=null
     try {encoder?.finish()} catch(_:Exception) {failure="encoder_failed"};runCatching {encoder?.close()}
     runCatching {focus.release()}
     try {applyEvents()} catch(_:Exception) {failure="draft_save_failed"}
     events.close()
     val snapshot=textChange {it.finish()};generation++
     try {persistText(snapshot)} catch(_:Exception) {failure="draft_save_failed"}
     if(discarded.get()) {
      discardAttempts.incrementAndGet()
      if(publish(RecordingOutcome.Discarded(id))) {file.delete();mutable.update {it.copy(phase=RecordingPhase.Discarded,level=0f)}}
      else mutable.update {it.copy(phase=RecordingPhase.Failed,level=0f)}
     } else if(frames>0 && file.isFile && file.length() in 1..maximumBytes && failure!="encoder_failed") {
      val finalized=try {
       FileOutputStream(file,true).use {it.fd.sync()}
       (if(file.name.endsWith(".partial")) File(file.parentFile,file.name.removeSuffix(".partial")).also {require(!it.exists());Files.move(file.toPath(),it.toPath(),StandardCopyOption.ATOMIC_MOVE)} else file).also {final -> java.nio.channels.FileChannel.open(final.parentFile.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}}
      } catch(_:Exception) {null}
      if(finalized==null) {
       mutable.update {it.copy(phase=RecordingPhase.Failed,message="Recording kept locally · finalization needs retry")}
       publish(RecordingOutcome.Interrupted(id,"file_finalize_failed"))
       return@withContext
      }
      target=finalized
      val ready=ReadyRecording(id,finalized.absolutePath,frames*1000/RECORDING_RATE,finalized.length(),textSnapshot().text,captured)
      val persisted=publish(RecordingOutcome.Ready(ready,failure))
      mutable.update {it.copy(phase=if(persisted) RecordingPhase.Ready else RecordingPhase.Failed,level=0f,recording=ready.copy(text=it.text),message=if(!persisted) "Recording kept locally · storage needs retry" else if(failure==null) "Recording kept on this phone · Save to attach" else "Recording stopped. Your clip stays on this phone.")}
     } else {
      mutable.update {it.copy(phase=RecordingPhase.Failed,level=0f,message=when(failure) {"permission_required"->"Allow microphone permission to record.";"android_13_required"->"Voice recording needs Android 13 or newer.";"microphone_busy"->"The microphone is in use by another app.";else->"Recording unavailable. Your text is kept."})}
      publish(RecordingOutcome.Interrupted(id,failure?:"empty_recording"))
     }
    }
   }
  }
  }
 }
 /** Call for every owner edit/selection change. Persistence callbacks must CAS the snapshot revision against the current draft lease. */
 fun updateTypedText(text:String,selectionStart:Int=text.length,selectionEnd:Int=selectionStart):RecordingTextSnapshot=synchronized(textLock) {
  val buffer=textBuffer?:RecordingTextBuffer(text,selectionStart,selectionEnd).also {textBuffer=it}
  val snapshot=buffer.typed(text,selectionStart,selectionEnd)
  mutable.update {it.copy(text=snapshot.text,textRevision=snapshot.revision,partialSpan=buffer.partialSpan,recording=it.recording?.copy(text=snapshot.text))};snapshot
 }
 fun textSnapshot():RecordingTextSnapshot=synchronized(textLock) {textBuffer?.snapshot()?:RecordingTextSnapshot(state.value.text,state.value.textRevision)}
 fun isCurrent(snapshot:RecordingTextSnapshot):Boolean=textSnapshot()==snapshot
 suspend fun stop():ReadyRecording? {stopping.set(true);runCatching {source?.stop()};job?.join();return state.value.recording}
 /** Transfer file ownership after the data transaction retains this clip. */
 fun detachRetained(id:String) {check(state.value.phase==RecordingPhase.Ready && sessionId==id);target=null;sessionId=null;synchronized(textLock) {textBuffer=null;mutable.value=RecordingState()}}
 fun onForegroundChanged(foreground:Boolean) {if(!foreground) {stopping.set(true);runCatching {source?.stop()}}}
 fun onAudioFocusLost()=onForegroundChanged(false)
 suspend fun discard():Unit=discardMutex.withLock {
  val attemptsBefore=discardAttempts.get()
  discarded.set(true);generation++;stopping.set(true);runCatching {source?.stop()}
  job?.join()
  // Keep a worker's success or failure; a later explicit discard may retry failure.
  if(state.value.phase!=RecordingPhase.Discarded && discardAttempts.get()==attemptsBefore) {
   sessionId?.let {onOutcome(RecordingOutcome.Discarded(it))}
   target?.delete();mutable.value=state.value.copy(phase=RecordingPhase.Discarded,recording=null)
  } else if(state.value.phase==RecordingPhase.Discarded) target?.delete()
 }
}
