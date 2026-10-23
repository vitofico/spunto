package io.theficos.spunto

import android.os.Build
import android.content.Context
import io.theficos.spunto.plugins.dictation.AudioFocus
import io.theficos.spunto.data.local.*
import io.theficos.spunto.plugins.dictation.PlatformAudioFocus
import io.theficos.spunto.plugins.dictation.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** A foreground editor may finalize in app scope; only an explicit Save transfers its clip. */
class VoiceCaptureController(
 private val context:Context,
 private val scope:CoroutineScope,
 private val notes:NotesRepository,
 private val recordings:RecordingRepository,
 private val recovery:Deferred<List<RecordingDraftEntity>>,
 private val currentLease:()->EditorLease,
 sourceFactory:()->PcmSource={AndroidPcmSource(context)},
 encoderFactory:()->RecordingEncoder={if(Build.VERSION.SDK_INT>=33) AndroidOggEncoder() else throw RecordingFailure("android_13_required")},
 focusFactory:((()->Unit)->AudioFocus)={lost ->PlatformAudioFocus(context,lost)},
 recognitionFactory:PcmRecognitionFactory?=PcmRecognitionFactory {language,events ->if(Build.VERSION.SDK_INT>=33) InjectedPcmRecognition.open(context,scope,language,confirmedPcmSupport=false,events=events) else null},
 private val beginJournal:suspend (String,String?)->RecordingDraftEntity={draftId,noteId ->recordings.begin(draftId,noteId)},
) {
 constructor(model:SpuntoViewModel,currentLease:()->EditorLease):this(model.container.context,model.container.scope,model.repository,model.container.recordings,model.container.recordingRecovery,currentLease)
 @Volatile private var allowed=false
 @Volatile private var foreground=true
 private val starting=AtomicBoolean(false)
 private var retainedId:String?=null
 private data class EditorInput(val text:String,val start:Int,val end:Int,val revision:Long)
 private val inputLock=Any()
 private var inputRevision=0L
 private var ownerInput:EditorInput?=null
 private val session:RecordingSession=RecordingSession(
  scope=scope,
  sourceFactory=sourceFactory,
  encoderFactory=encoderFactory,
  focus=focusFactory {stopCapture()},
  // A recognition service must first prove that it consumes injected PCM without opening a mic.
  recognitionFactory=recognitionFactory,
  onOutcome={outcome -> when(outcome) {
   is RecordingOutcome.Ready -> recordings.complete(outcome.recording.id,outcome.recording.capturedText)
   is RecordingOutcome.Interrupted -> recordings.interrupted(outcome.id,outcome.reason)
   is RecordingOutcome.Discarded -> recordings.discard(outcome.id)
  }},
  persistText={snapshot -> persistSnapshot(snapshot)},
  isEnabled={allowed && foreground},
 )
 val state:StateFlow<RecordingState> = session.state
 val hasSession get()=state.value.id!=null && state.value.id!=retainedId && state.value.phase!=RecordingPhase.Discarded
 fun policy(facts:RecordingPluginFacts) {allowed=facts.captureAllowed;if(!allowed) session.onForegroundChanged(false)}
 suspend fun start(text:String,start:Int,end:Int,language:String) {
  require(Build.VERSION.SDK_INT>=33) {context.getString(R.string.sys_recording_android13)}
  require(allowed) {context.getString(R.string.sys_enable_voice)}
  check(starting.compareAndSet(false,true)) {context.getString(R.string.sys_recording_starting)}
  synchronized(inputLock) {ownerInput=EditorInput(text,start,end,inputRevision)}
  var journal:RecordingDraftEntity?=null
  var handedOff=false
  try {
   recovery.await()
   check(foreground && allowed) {context.getString(R.string.sys_recording_stopped_text_kept)}
   val lease=currentLease()
   require(notes.dao.recordingSnapshots().none {it.draftId==lease.draftId && it.state in setOf("capturing","interrupted","recoverable","ready")}) {context.getString(R.string.sys_previous_recording)}
   val row=withContext(NonCancellable) {beginJournal(lease.draftId,lease.noteId).also {journal=it}}
   currentCoroutineContext().ensureActive()
   withContext(Dispatchers.Main.immediate) {
    check(foreground && allowed)
    val input=synchronized(inputLock) {requireNotNull(ownerInput)}
    session.start(row.id,File(row.localPath),input.text,input.start,input.end,if(language=="it-IT") language else "en-US",row.maximumBytes)
    // A caller outside Main may edit during the initial publication; its newer words still win.
    synchronized(inputLock) {ownerInput?.takeIf {it.revision>input.revision}?.let {session.updateTypedText(it.text,it.start,it.end)}}
    handedOff=true
   }
  } catch(error:Exception) {
   journal?.let {row ->
    if(session.state.value.id==row.id) session.onForegroundChanged(false)
    if(!handedOff) withContext(NonCancellable) {recordings.interrupted(row.id)}
   }
   throw error
  } finally {starting.set(false)}
 }
 private suspend fun persistSnapshot(snapshot:RecordingTextSnapshot) {
  val write=withContext(Dispatchers.Main.immediate) {
   if(session.isCurrent(snapshot)) notes.editorWrite(currentLease(),snapshot.text) else null
  }
  write?.let {notes.persistEditor(it)}
 }
 fun ownerEdit(text:String,start:Int,end:Int) {
  val writing=synchronized(inputLock) {
   ownerInput=EditorInput(text,start,end,++inputRevision)
   if(hasSession) session.updateTypedText(text,start,end)
   hasSession || starting.get()
  }
  if(!writing) return
  val write=notes.editorWrite(currentLease(),text)
  scope.launch {notes.persistEditor(write)}
 }
 fun isCurrent(snapshot:RecordingTextSnapshot)=session.isCurrent(snapshot)
 fun textSnapshot()=session.textSnapshot()
 suspend fun stop() {session.stop()}
 fun onForegroundChanged(foreground:Boolean):Unit {this.foreground=foreground;session.onForegroundChanged(foreground)}
 fun stopCapture():Unit {session.onForegroundChanged(false)}
 suspend fun recover(id:String) {recovery.await();recordings.complete(id)}
 suspend fun discard(id:String) {if(state.value.id==id) session.discard() else recordings.discard(id)}
 suspend fun retain(id:String,write:EditorWrite):String {
  val saved=recordings.retain(id,write.body,noteId=write.lease.noteId,editorWrite=write)
  retainedId=id
  if(state.value.id==id && state.value.phase==RecordingPhase.Ready) session.detachRetained(id)
  return saved
 }
}
