package io.theficos.spunto

import android.os.Build
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.data.local.*
import io.theficos.spunto.plugins.dictation.AudioFocus
import io.theficos.spunto.plugins.dictation.SpeechEvent
import io.theficos.spunto.plugins.dictation.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

/** Isolated in-memory Room plus generated PCM. Never uses the app's database, mic or auth. */
@androidx.test.filters.SdkSuppress(minSdkVersion=33)
class VoiceCaptureControllerTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val focus=object:AudioFocus {override fun acquire()=true;override fun release()=Unit}
 private val catalog="""{"plugins":[{"id":"voice-memos","switch":"on","state":"waiting","permissions":["attachments.read","attachments.write","ai.transcribe","suggest"],"granted":["attachments.read","attachments.write","ai.transcribe","suggest"]}]}"""
 private class GeneratedPcm:PcmSource {
  private val stopped=AtomicBoolean(false);private val started=AtomicBoolean(false)
  @Volatile private var frame=0
  val active get()=started.get() && !stopped.get()
  val readFrames get()=frame
  override fun start() {started.set(true)}
  override suspend fun read(buffer:ShortArray):Int {
   delay(100)
   if(stopped.get()) return -1
   repeat(buffer.size) {index ->buffer[index]=(sin(2*Math.PI*440*(frame*buffer.size+index)/RECORDING_RATE)*4000).toInt().toShort()}
   frame++;return buffer.size
  }
  override fun stop() {stopped.set(true)}
  override fun close()=stop()
 }
 @Test fun explicitWaitingCaptureKeepsOwnerEditLocalUntilAtomicSave()=runBlocking {
  if(Build.VERSION.SDK_INT<33) {Assume.assumeTrue(false);return@runBlocking}
  val db=Room.inMemoryDatabaseBuilder(context,SpuntoDatabase::class.java).build()
  val folder=File(context.cacheDir,"voice-controller-${System.nanoTime()}").apply {mkdirs()}
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
  val notes=NotesRepository(db);val recordings=RecordingRepository(notes,folder);val lease=notes.beginEditor(null,"synthetic-controller")
  var sources=0;var encoders=0
  val controller=VoiceCaptureController(context,scope,notes,recordings,CompletableDeferred(emptyList()),{lease},sourceFactory={sources++;GeneratedPcm()},encoderFactory={encoders++;AndroidOggEncoder()},focusFactory={focus})
  try {
   assertEquals(0,sources);assertEquals(0,encoders)
   assertTrue(runCatching {controller.start("Synthetic comment",17,17,"en-US")}.isFailure)
   assertEquals(0,sources);assertTrue(notes.dao.recordingSnapshots().isEmpty())
   notes.dao.putState(SyncStateEntity(serverUrl="https://synthetic.invalid",manifest="{\"plugins\":[]}",catalog=catalog))
   controller.policy(recordingPluginFacts(notes.dao.state()))
   controller.start("Synthetic comment",17,17,"en-US")
   withTimeout(10_000) {controller.state.first {it.phase==RecordingPhase.Recording && it.durationMs>=200}}
   val typed="Synthetic newer owner words"
   controller.ownerEdit(typed,typed.length,typed.length)
   controller.stop()
   assertEquals(RecordingPhase.Ready,controller.state.value.phase)
   assertTrue(controller.state.value.recordingOnly)
   assertEquals(typed,notes.dao.draftSnapshot(lease.draftId)!!.body)
   assertTrue(notes.dao.all().isEmpty());assertTrue(notes.dao.pendingAttachments().isEmpty());assertTrue(notes.dao.outbox().isEmpty())
   val row=notes.dao.recordingSnapshots().single()
   val stale=notes.editorWrite(lease,typed)
   val newer="$typed while Save was in flight"
   controller.ownerEdit(newer,newer.length,newer.length)
   assertTrue(runCatching {controller.retain(row.id,stale)}.isFailure)
   assertTrue(controller.hasSession);assertTrue(notes.dao.pendingAttachments().isEmpty())
   val id=controller.retain(row.id,notes.editorWrite(lease,newer))
   val note=notes.dao.get(id)!!
   assertEquals("android",note.sourceKind);assertNull(note.sourceRef)
   assertTrue(note.body.startsWith(newer));assertTrue(note.body.contains("[recording:${row.id}]"))
   assertEquals("recording",notes.dao.attachment(row.id)!!.kind)
   assertEquals(1,sources);assertEquals(1,encoders)
   assertFalse(controller.hasSession)
  } finally {controller.onForegroundChanged(false);controller.stop();scope.cancel();db.close();folder.deleteRecursively()}
 }
 @Test fun leavingWhileRecoveryIsPendingNeverStartsCapture()=runBlocking {
  if(Build.VERSION.SDK_INT<33) {Assume.assumeTrue(false);return@runBlocking}
  val db=Room.inMemoryDatabaseBuilder(context,SpuntoDatabase::class.java).build()
  val folder=File(context.cacheDir,"voice-startup-${System.nanoTime()}").apply {mkdirs()}
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
  val notes=NotesRepository(db);val recordings=RecordingRepository(notes,folder);val lease=notes.beginEditor(null,"synthetic-startup")
  val recovery=CompletableDeferred<List<RecordingDraftEntity>>()
  var sources=0;var encoders=0
  val controller=VoiceCaptureController(context,scope,notes,recordings,recovery,{lease},sourceFactory={sources++;GeneratedPcm()},encoderFactory={encoders++;AndroidOggEncoder()},focusFactory={focus})
  try {
   notes.dao.putState(SyncStateEntity(serverUrl="https://synthetic.invalid",manifest="{\"plugins\":[]}",catalog=catalog))
   controller.policy(recordingPluginFacts(notes.dao.state()))
   val start=async {runCatching {controller.start("Synthetic startup",17,17,"en-US")}}
   yield();controller.onForegroundChanged(false);recovery.complete(emptyList())
   assertTrue(start.await().isFailure)
   assertEquals(0,sources);assertEquals(0,encoders);assertTrue(notes.dao.recordingSnapshots().isEmpty())
  } finally {scope.cancel();db.close();folder.deleteRecursively()}
 }
 @Test fun activeRecordingKeepsTypedCaretAndDraftThroughChangedFinalUntilExplicitStop()=runBlocking {
  if(Build.VERSION.SDK_INT<33) {Assume.assumeTrue(false);return@runBlocking}
  val db=Room.inMemoryDatabaseBuilder(context,SpuntoDatabase::class.java).build()
  val folder=File(context.cacheDir,"voice-active-edit-${System.nanoTime()}").apply {mkdirs()}
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
  val notes=NotesRepository(db);val recordings=RecordingRepository(notes,folder);val lease=notes.beginEditor(null,"synthetic-active-edit")
  val source=GeneratedPcm()
  val callback=CompletableDeferred<(SpeechEvent)->Unit>()
  val recognition=PcmRecognitionFactory {_,events ->
   callback.complete(events)
   object:PcmRecognition {override fun offer(samples:ShortArray,count:Int)=true;override suspend fun finish()=Unit;override fun close()=Unit}
  }
  val controller=VoiceCaptureController(context,scope,notes,recordings,CompletableDeferred(emptyList()),{lease},sourceFactory={source},encoderFactory={AndroidOggEncoder()},focusFactory={focus},recognitionFactory=recognition)
  try {
   notes.dao.putState(SyncStateEntity(serverUrl="https://synthetic.invalid",manifest="{\"plugins\":[]}",catalog=catalog))
   controller.policy(recordingPluginFacts(notes.dao.state()))
   val initial="old initial words"
   controller.start(initial,initial.length,initial.length,"en-US")
   withTimeout(10_000) {controller.state.first {it.phase==RecordingPhase.Recording && it.durationMs>=200}}
   val events=withTimeout(10_000) {callback.await()}
   val typed="typed before after";val caret="typed before ".length
   withContext(Dispatchers.Main) {controller.ownerEdit(typed,caret,caret)}
   withTimeout(10_000) {notes.draft(lease.draftId).first {it?.body==typed}}
   assertTrue(source.active);assertEquals(RecordingPhase.Recording,controller.state.value.phase)
   events(SpeechEvent.Partial("first words"))
   withTimeout(10_000) {controller.state.first {it.text=="typed before first words after"}}
   val comment="comment typed before first words after"
   withContext(Dispatchers.Main) {controller.ownerEdit(comment,"comment typed before ".length,"comment typed before ".length)}
   withTimeout(10_000) {notes.draft(lease.draftId).first {it?.body==comment}}
   val beforeFinal=source.readFrames
   assertTrue(source.active)
   events(SpeechEvent.Final("changed final"))
   val expected="comment typed before changed final after"
   withTimeout(10_000) {controller.state.first {it.text==expected && source.readFrames>beforeFinal}}
   withTimeout(10_000) {notes.draft(lease.draftId).first {it?.body==expected}}
   assertTrue(source.active);assertEquals(RecordingPhase.Recording,controller.state.value.phase)
   assertTrue(notes.dao.all().isEmpty());assertTrue(notes.dao.pendingAttachments().isEmpty())
   controller.stopCapture();controller.stop()
   assertFalse(source.active);assertEquals(RecordingPhase.Ready,controller.state.value.phase)
   val row=notes.dao.recordingSnapshots().single()
   val id=controller.retain(row.id,notes.editorWrite(lease,expected))
   assertTrue(notes.dao.get(id)!!.body.startsWith(expected));assertEquals("dictation",notes.dao.get(id)!!.sourceRef)
  } finally {withContext(NonCancellable) {controller.onForegroundChanged(false);controller.stop();scope.cancel();db.close();folder.deleteRecursively()}}
 }
 @Test fun typingAndCaretWhileRecoveryIsSuspendedSurviveCompletionAndSave()=runBlocking {
  if(Build.VERSION.SDK_INT<33) {Assume.assumeTrue(false);return@runBlocking}
  exerciseStartupTyping(holdRecovery=true)
 }
 @Test fun typingAndCaretAfterJournalCommitBeforeStartupSurviveCompletionAndSave()=runBlocking {
  if(Build.VERSION.SDK_INT<33) {Assume.assumeTrue(false);return@runBlocking}
  exerciseStartupTyping(holdRecovery=false)
 }
 private suspend fun exerciseStartupTyping(holdRecovery:Boolean)=coroutineScope {
  val db=Room.inMemoryDatabaseBuilder(context,SpuntoDatabase::class.java).build()
  val folder=File(context.cacheDir,"voice-typing-startup-${System.nanoTime()}").apply {mkdirs()}
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
  val notes=NotesRepository(db);val recordings=RecordingRepository(notes,folder);val lease=notes.beginEditor(null,"synthetic-typing-startup")
  val recovery:CompletableDeferred<List<RecordingDraftEntity>> = if(holdRecovery) CompletableDeferred() else CompletableDeferred(emptyList())
  val committed=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  var sources=0;var encoders=0
  val recognition=PcmRecognitionFactory {_,events ->
   events(SpeechEvent.Final("inserted"))
   object:PcmRecognition {override fun offer(samples:ShortArray,count:Int)=true;override suspend fun finish()=Unit;override fun close()=Unit}
  }
  val controller=VoiceCaptureController(context,scope,notes,recordings,recovery,{lease},sourceFactory={sources++;GeneratedPcm()},encoderFactory={encoders++;AndroidOggEncoder()},focusFactory={focus},recognitionFactory=recognition,beginJournal={draft,note ->
   val row=recordings.begin(draft,note)
   if(!holdRecovery) {committed.complete(Unit);release.await()}
   row
  })
  var startup:Deferred<Unit>?=null
  try {
   notes.dao.putState(SyncStateEntity(serverUrl="https://synthetic.invalid",manifest="{\"plugins\":[]}",catalog=catalog))
   controller.policy(recordingPluginFacts(notes.dao.state()))
   val original="Synthetic old text at the end"
   val operation=async(start=CoroutineStart.UNDISPATCHED) {controller.start(original,original.length,original.length,"en-US")}
   startup=operation
   if(!holdRecovery) withTimeout(10_000) {committed.await()}
   val newer="owner before after";val caret="owner before ".length
   withContext(Dispatchers.Main) {controller.ownerEdit(newer,caret,caret)}
   withTimeout(10_000) {notes.draft(lease.draftId).first {it?.body==newer}}
   assertEquals(0,sources);assertEquals(0,encoders)
   if(holdRecovery) recovery.complete(emptyList()) else release.complete(Unit)
   operation.await()
   val expected="owner before inserted after"
   withTimeout(10_000) {controller.state.first {it.durationMs>=200 && it.text==expected}}
   controller.stop()
   assertEquals(expected,controller.textSnapshot().text)
   assertTrue(notes.dao.all().isEmpty());assertTrue(notes.dao.pendingAttachments().isEmpty())
   val row=notes.dao.recordingSnapshots().single()
   val id=controller.retain(row.id,notes.editorWrite(lease,expected))
   val note=notes.dao.get(id)!!
   assertTrue(note.body.startsWith(expected));assertFalse(note.body.contains(original));assertEquals("dictation",note.sourceRef)
   assertEquals(1,notes.dao.pendingAttachments().size)
  } finally {withContext(NonCancellable) {controller.onForegroundChanged(false);recovery.complete(emptyList());release.complete(Unit);startup?.cancelAndJoin();controller.stop();scope.cancel();db.close();folder.deleteRecursively()}}
 }
 @Test fun cancellationAfterJournalCommitInterruptsOnlyThatRowWithoutStartingMic()=runBlocking {
  if(Build.VERSION.SDK_INT<33) {Assume.assumeTrue(false);return@runBlocking}
  val db=Room.inMemoryDatabaseBuilder(context,SpuntoDatabase::class.java).build()
  val folder=File(context.cacheDir,"voice-cancel-startup-${System.nanoTime()}").apply {mkdirs()}
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
  val notes=NotesRepository(db);val recordings=RecordingRepository(notes,folder);val lease=notes.beginEditor(null,"synthetic-cancel-startup")
  val committed=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  var sources=0;var encoders=0
  val controller=VoiceCaptureController(context,scope,notes,recordings,CompletableDeferred(emptyList()),{lease},sourceFactory={sources++;GeneratedPcm()},encoderFactory={encoders++;AndroidOggEncoder()},focusFactory={focus},beginJournal={draft,note ->recordings.begin(draft,note).also {committed.complete(Unit);release.await()}})
  var startup:Job?=null
  try {
   notes.dao.putState(SyncStateEntity(serverUrl="https://synthetic.invalid",manifest="{\"plugins\":[]}",catalog=catalog))
   val other=recordings.begin("synthetic-other-controller")
   controller.policy(recordingPluginFacts(notes.dao.state()))
   val operation=launch {controller.start("Synthetic cancellable",21,21,"en-US")}
   startup=operation
   withTimeout(10_000) {committed.await()};operation.cancel();release.complete(Unit);operation.join()
   val rows=notes.dao.recordingSnapshots()
   assertEquals("interrupted",rows.single {it.draftId==lease.draftId}.state)
   assertEquals("capturing",rows.single {it.id==other.id}.state)
   assertEquals(0,sources);assertEquals(0,encoders);assertTrue(notes.dao.pendingAttachments().isEmpty())
  } finally {withContext(NonCancellable) {controller.onForegroundChanged(false);release.complete(Unit);startup?.cancelAndJoin();scope.cancel();db.close();folder.deleteRecursively()}}
 }
}
