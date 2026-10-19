package io.theficos.spunto.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class RecordingRepositoryTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private lateinit var db:SpuntoDatabase
 private lateinit var notes:NotesRepository
 private lateinit var folder:File
 private lateinit var recordings:RecordingRepository
 @Before fun setup() {db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),SpuntoDatabase::class.java).allowMainThreadQueries().build();notes=NotesRepository(db);folder=kotlin.io.path.createTempDirectory("synthetic-recordings").toFile();recordings=RecordingRepository(notes,folder)}
 @After fun close() {db.close();folder.listFiles()?.forEach {it.delete()};folder.delete()}
 private fun waitingCatalog()=File(System.getProperty("spunto.fixtures")?:"/spec/fixtures","recording-waiting-catalog.json").readText()
 private suspend fun enabled(value:Boolean=true) {notes.dao.putState(SyncStateEntity(epoch="e",serverUrl="https://recording.example",maxUploadBytes=20_000_000,manifest="{\"plugins\":[]}",catalog=if(value) waitingCatalog() else "{\"plugins\":[]}"))}
 private suspend fun ready(captured:Boolean=false):RecordingDraftEntity {enabled();val row=recordings.begin();File(row.localPath).writeBytes(syntheticOgg());return recordings.complete(row.id,captured)}
 @Test fun recordingIsOptInAndJournalNeverQueuesUntilOwnerRetains()=runBlocking {
  assertTrue(runCatching {recordings.begin()}.isFailure);assertTrue(folder.listFiles().isNullOrEmpty())
  val row=ready(true);assertEquals("ready",row.state);assertEquals(2000L,row.durationMs);assertEquals(64,row.sha256.length)
  assertTrue(notes.dao.all().isEmpty());assertTrue(notes.dao.pendingAttachments().isEmpty());assertTrue(notes.dao.outbox().isEmpty())
  notes.saveDraft(body="Owner comment");val capture=notes.dao.draftSnapshot("capture")!!
  val id=recordings.retain(row.id,"Owner comment",privateNote=true,captureDraft=capture)
  val note=notes.dao.get(id)!!;assertTrue(note.privateNote);assertEquals("android",note.sourceKind);assertEquals("dictation",note.sourceRef);assertTrue(note.body.contains("[recording:${row.id}]"));assertNull(notes.dao.draftSnapshot("capture"))
  val attachment=notes.dao.attachment(row.id)!!;assertEquals("recording",attachment.kind);assertEquals("https://recording.example",attachment.serverUrl);assertEquals(row.sha256,attachment.sha256)
  val outbox=notes.dao.outbox();assertEquals(id,recordings.retain(row.id,"Ignored retry"));assertEquals(outbox,notes.dao.outbox());assertEquals(note,notes.dao.get(id))
 }
 @Test fun processDeathRecoversValidatedBytesWithoutMicNetworkOrAutomaticAttachment()=runBlocking {
  enabled();val complete=recordings.begin();File(complete.localPath).writeBytes(syntheticOgg())
  val incomplete=recordings.begin();File(incomplete.localPath).writeBytes("OggS unfinished".toByteArray())
  val recovered=RecordingRepository(notes,folder).recover()
  assertEquals("recoverable",recovered.single {it.id==complete.id}.state);assertEquals("interrupted",recovered.single {it.id==incomplete.id}.state)
  assertTrue(notes.dao.all().isEmpty());assertTrue(notes.dao.pendingAttachments().isEmpty());assertTrue(notes.dao.outbox().isEmpty());assertTrue(File(incomplete.localPath).exists())
 }
 @Test fun discardFencesLateFinalizeAndNeverDeletesRetainedAudio()=runBlocking {
  enabled();val row=recordings.begin();File(row.localPath).writeBytes(syntheticOgg());recordings.discard(row.id)
  assertFalse(File(row.localPath).exists());assertTrue(runCatching {recordings.complete(row.id)}.isFailure);assertEquals("discarded",notes.dao.recording(row.id)!!.state)
  File(row.finalPath).writeBytes(syntheticOgg());val owner=notes.create("Owner note for discarded-file safety")
  assertTrue(runCatching {notes.queueAttachment(owner,row.finalPath,"audio/ogg","recording")}.isFailure);assertFalse(notes.dao.get(owner)!!.body.contains("[recording:"))
  val retained=ready();recordings.retain(retained.id,"Keep recording")
  assertTrue(runCatching {recordings.discard(retained.id)}.isFailure);assertTrue(File(retained.finalPath).exists());assertNotNull(notes.dao.attachment(retained.id))
 }
 @Test fun privacyRootMarkerAndNewTypingRemainIndependentOfRecordedBytes()=runBlocking {
  val row=ready();val original=uuidV7();notes.putNote(Note(original,"Old owner body",privateNote=true,sourceKind="share",sourceRef="synthetic.app"))
  notes.saveDraft(body="Newer typing");val old=DraftEntity("capture","Old owner body",1)
  recordings.retain(row.id,"Old owner body",original,captureDraft=old)
  assertEquals("Newer typing",notes.dao.draftSnapshot("capture")!!.body);assertEquals("synthetic.app",notes.dao.get(original)!!.sourceRef);assertTrue(notes.dao.get(original)!!.privateNote)
  val another=ready();notes.dao.putState(notes.dao.state()!!.copy(serverUrl="https://other.example"));assertTrue(runCatching {recordings.retain(another.id,"Do not leak")}.isFailure);assertNull(notes.dao.attachment(another.id))
 }
 @Test fun removedTrashedOrTombstonedTargetCannotBeRecreatedAndDisabledVoicePausesQueue()=runBlocking {
  for(mode in listOf("missing","trashed","tombstone")) {
   val row=ready();val id=uuidV7();if(mode!="missing") notes.putNote(Note(id,"Owner words",trashed=mode=="trashed"))
   if(mode=="tombstone") notes.dao.putTombstone(TombstoneEntity(id,1))
   val count=notes.dao.all().size;assertTrue(runCatching {recordings.retain(row.id,"No resurrection",id)}.isFailure);assertEquals(count,notes.dao.all().size);assertNull(notes.dao.attachment(row.id))
  }
  val row=ready();enabled(false);recordings.retain(row.id,"Keep locally")
  assertEquals("paused",notes.dao.attachment(row.id)!!.status);assertEquals("android",notes.dao.get(notes.dao.attachment(row.id)!!.noteId)!!.sourceKind);assertNull(notes.dao.get(notes.dao.attachment(row.id)!!.noteId)!!.sourceRef)
 }
 @Test fun validatorRejectsIncompleteCorruptAndOverLimitAudioWithoutChangingJournal()=runBlocking {
  enabled();val row=recordings.begin();val bytes=syntheticOgg()
  File(row.localPath).writeBytes(bytes.copyOf(bytes.size-2));assertTrue(runCatching {recordings.complete(row.id)}.isFailure)
  bytes[bytes.lastIndex]=(bytes.last()+1).toByte();File(row.localPath).writeBytes(bytes);assertTrue(runCatching {recordings.complete(row.id)}.isFailure)
  File(row.localPath).writeBytes(syntheticOgg(3601));assertTrue(runCatching {recordings.complete(row.id)}.isFailure)
  assertEquals("capturing",notes.dao.recording(row.id)!!.state);assertTrue(notes.dao.pendingAttachments().isEmpty())
 }
 @Test fun changedNoteDuringRecordingCannotBeOverwrittenAndNewerCommittedTextCanBeKept()=runBlocking {
  enabled();val id=uuidV7();notes.putNote(Note(id,"Original recording basis",serverRev=2));val row=recordings.begin(draftId=id,noteId=id)
  File(row.localPath).writeBytes(syntheticOgg());recordings.complete(row.id)
  notes.putNote(notes.dao.get(id)!!.note().copy(body="Another actor changed this note",serverRev=3))
  assertTrue(runCatching {recordings.retain(row.id,"Original recording basis with dictated words",id)}.isFailure)
  assertEquals("Another actor changed this note",notes.dao.get(id)!!.body);assertNull(notes.dao.attachment(row.id))
  recordings.retain(row.id,"Another actor changed this note",id);assertTrue(notes.dao.get(id)!!.body.startsWith("Another actor changed this note"))
 }
 @Test fun malformedEnabledValueDoesNotAuthorizeRecordingAndWhitespaceIsPreserved()=runBlocking {
  notes.dao.putState(SyncStateEntity(manifest="""{"plugins":[{"id":"voice-memos","enabled":"false"}]}"""))
  assertTrue(runCatching {recordings.begin()}.isFailure)
  val row=ready();val body="Owner trailing Markdown break  "
  val id=recordings.retain(row.id,body);assertEquals(body+"\n\n[recording:${row.id}]",notes.dao.get(id)!!.body)
 }

 @Test fun processDeathBeforeOggEosRecoversOnlyValidatedCompletePacketPages()=runBlocking {
  enabled();val row=recordings.begin();val bytes=syntheticOgg()
  var offset=0;var last=0
  while(offset<bytes.size) {last=offset;val count=bytes[offset+26].toInt() and 255;var length=0;repeat(count) {length+=bytes[offset+27+it].toInt() and 255};offset+=27+count+length}
  bytes[last+5]=0;repeat(4) {bytes[last+22+it]=0};var checksum=0
  for(index in last until bytes.size) {checksum=checksum xor ((bytes[index].toInt() and 255) shl 24);repeat(8) {checksum=if(checksum<0) (checksum shl 1) xor 0x04c11db7 else checksum shl 1}}
  repeat(4) {bytes[last+22+it]=(checksum ushr (8*it)).toByte()}
  File(row.localPath).writeBytes(bytes+byteArrayOf(79,103,103,83))
  assertTrue(runCatching {inspectRecording(File(row.localPath),20_000_000)}.isFailure)
  val recovered=recordings.recover().single {it.id==row.id}
  assertEquals("recoverable",recovered.state);assertEquals(2000L,recovered.durationMs)
  assertEquals(bytes.size.toLong(),File(recovered.finalPath).length());assertEquals(recovered.sha256,inspectRecording(File(recovered.finalPath),20_000_000).sha256)
  assertTrue(notes.dao.pendingAttachments().isEmpty());assertTrue(notes.dao.outbox().isEmpty())
 }

 @Test fun recordingRetainAcknowledgesOnlyCurrentLeaseAndOldDisposalCannotRemoveTheMarker()=runBlocking {
  val row=ready();val lease=notes.beginEditor(null);val old=notes.editorWrite(lease,"Old typed body");val latest=notes.editorWrite(lease,"Latest typed body")
  assertTrue(runCatching {recordings.retain(row.id,old.body,editorWrite=old)}.isFailure);assertTrue(notes.dao.all().isEmpty());assertEquals("ready",notes.dao.recording(row.id)!!.state)
  val id=recordings.retain(row.id,latest.body,editorWrite=latest)
  assertTrue(notes.dao.get(id)!!.body.startsWith("Latest typed body\n\n[recording:"));assertNull(notes.dao.draftSnapshot("capture"))
  assertFalse(notes.finishEditor(old));assertFalse(notes.persistEditor(latest,commit=true));assertTrue(notes.dao.get(id)!!.body.contains("[recording:${row.id}]"))
 }
 @Test fun typingAllocatedDuringRetainRollsBackNoteAttachmentAndJournalThenRemainsSaveable()=runBlocking {
  val row=ready();var later:EditorWrite?=null
  // Use the declared onChange hook as a deterministic post-note-write gate.
  val localLeaseHolder=arrayOfNulls<EditorLease>(1)
  lateinit var atomic:NotesRepository
  atomic=NotesRepository(db) {if(later==null) later=atomic.editorWrite(localLeaseHolder[0]!!,"New text typed during saving")}
  localLeaseHolder[0]=atomic.beginEditor(null)
  val saving=atomic.editorWrite(localLeaseHolder[0]!!,"Captured save text")
  assertTrue(runCatching {RecordingRepository(atomic,folder).retain(row.id,saving.body,editorWrite=saving)}.isFailure)
  assertTrue(notes.dao.all().isEmpty());assertTrue(notes.dao.outbox().isEmpty());assertNull(notes.dao.attachment(row.id));assertEquals("ready",notes.dao.recording(row.id)!!.state)
  assertTrue(atomic.persistEditor(later!!));assertEquals("New text typed during saving",notes.dao.draftSnapshot("capture")!!.body)
  val id=RecordingRepository(atomic,folder).retain(row.id,later!!.body,editorWrite=later)
  assertTrue(notes.dao.get(id)!!.body.startsWith("New text typed during saving"))
 }

 @Test fun sameLiveEditorCanRetainAfterItsOwnAutosaveButCannotOverwriteARemoteBodyChange()=runBlocking {
  enabled();val id=uuidV7();notes.putNote(Note(id,"Original owner body",serverRev=2));val lease=notes.beginEditor(id)
  val row=recordings.begin(draftId=id,noteId=id);File(row.localPath).writeBytes(syntheticOgg());recordings.complete(row.id)
  assertTrue(notes.persistEditor(notes.editorWrite(lease,"Owner body with early transcript"),commit=true))
  val final=notes.editorWrite(lease,"Owner body with final transcript and later typing")
  recordings.retain(row.id,final.body,editorWrite=final)
  assertTrue(notes.dao.get(id)!!.body.startsWith(final.body));assertTrue(notes.dao.get(id)!!.body.contains("[recording:${row.id}]"))
  val nextLease=notes.beginEditor(id);val next=recordings.begin(draftId=id,noteId=id);File(next.localPath).writeBytes(syntheticOgg());recordings.complete(next.id)
  notes.persistEditor(notes.editorWrite(nextLease,"Locally committed transcript"),commit=true)
  notes.putNote(notes.dao.get(id)!!.note().copy(body="Distinct remote owner words",serverRev=7))
  val stale=notes.editorWrite(nextLease,"Locally committed transcript and more")
  assertTrue(runCatching {recordings.retain(next.id,stale.body,editorWrite=stale)}.isFailure)
  assertEquals("Distinct remote owner words",notes.dao.get(id)!!.body);assertNull(notes.dao.attachment(next.id))
 }

 @Test fun headerOnlyAndIllegalContinuationCannotBecomeReadyOrRecoverableAudio()=runBlocking {
  enabled();val valid=syntheticOgg()
  val headSize=27+1+19;val tagsSize=27+1+16
  val headerOnly=syntheticRewritePage(valid.copyOf(headSize+tagsSize),1,flags=4,granule=96_000)
  val illegal=syntheticRewritePage(valid,1,flags=1)
  val tagsBytes=valid.copyOf().also {it[headSize+28]=88}
  val wrongTags=syntheticRewritePage(tagsBytes,1,flags=0)
  for(bytes in listOf(headerOnly,illegal,wrongTags)) {
   val row=recordings.begin();val file=File(row.localPath);file.writeBytes(bytes)
   assertTrue(runCatching {recordings.complete(row.id)}.isFailure);assertEquals("capturing",notes.dao.recording(row.id)!!.state)
   assertTrue(runCatching {inspectRecording(file,20_000_000)}.isFailure)
   recordings.recover();val kept=notes.dao.recording(row.id)!!
   assertEquals("interrupted",kept.state);assertEquals(0L,kept.durationMs);assertEquals("",kept.sha256);assertArrayEquals(bytes,file.readBytes());assertFalse(File(row.finalPath).exists())
   assertTrue(notes.dao.all().isEmpty());assertTrue(notes.dao.pendingAttachments().isEmpty());assertTrue(notes.dao.outbox().isEmpty())
  }
 }

}
