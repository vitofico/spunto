package io.theficos.spunto.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.LocalOperationException
import io.theficos.spunto.core.model.LocalOperationProblem
import io.theficos.spunto.core.model.OutboxOperation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class CaptureSaveTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private lateinit var db:SpuntoDatabase
 private lateinit var notes:NotesRepository
 private lateinit var images:DraftImageStore
 private lateinit var directory:File
 private lateinit var databaseName:String
 private val context get()=ApplicationProvider.getApplicationContext<android.content.Context>()
 private fun open() {
  db=Room.databaseBuilder(context,SpuntoDatabase::class.java,databaseName).addMigrations(MIGRATION_6_7).allowMainThreadQueries().build()
  notes=NotesRepository(db)
  images=DraftImageStore(notes,directory)
 }
 @Before fun setup() {databaseName="synthetic-capture-${java.util.UUID.randomUUID()}.db";directory=kotlin.io.path.createTempDirectory("synthetic-capture").toFile();open()}
 @After fun close() {db.close();context.deleteDatabase(databaseName);directory.deleteRecursively()}
 private suspend fun pick(lease:EditorLease) {images.add(lease,"image/png") {byteArrayOf(1,2,3).inputStream()}}

 @Test fun imageOnlyAndTextCapturesSurviveReopenAndCommitAllMarkersOutboxAndExactDraftAcknowledgement()=runBlocking {
  for(body in listOf("","Synthetic thought")) {
   val key="capture:$body"
   val before=notes.beginEditor(null,key)
   notes.persistEditor(notes.editorWrite(before,body))
   repeat(2) {pick(before)}
   val staged=notes.dao.draftImageSnapshots(key)
   assertEquals(2,staged.size)
   assertTrue(notes.dao.attachmentSnapshots().none {it.localPath in staged.map {image -> image.localPath}})

   db.close();open();images.recover()
   val after=notes.beginEditor(null,key)
   assertEquals(body,notes.openEditor(after).first.orEmpty())
   assertEquals(staged,images.observe(key).first())
   val id=notes.capture(notes.editorWrite(after,body),sourceRef="dictation")
   val row=notes.dao.get(id)!!
   val attachments=notes.dao.attachments(id).first()
   assertEquals(staged.map {it.localPath}.toSet(),attachments.map {it.localPath}.toSet())
   assertTrue(attachments.all {it.marker in row.body && File(it.localPath).isFile})
   assertTrue(row.body.startsWith(body));assertEquals("dictation",row.sourceRef);assertNull(notes.dao.draftSnapshot(key))
   assertTrue(notes.dao.draftImageSnapshots(key).isEmpty())
   val pending=notes.dao.outbox().filter {it.noteId==id}.map {Json.decodeFromString<OutboxOperation>(it.payload)}
   assertEquals(1,pending.size);assertEquals("note.create",pending.single().type);assertEquals(row.body,pending.single().body)

   db.close();open();images.recover()
   assertEquals(row,notes.dao.get(id));assertEquals(attachments,notes.dao.attachments(id).first())
   assertEquals(pending,notes.dao.outbox().filter {it.noteId==id}.map {Json.decodeFromString<OutboxOperation>(it.payload)})
   assertTrue(attachments.all {File(it.localPath).isFile})
  }
 }

 @Test fun failedSecondImageRollsBackNoteAndFirstImageAndKeepsDraftForRetry()=runBlocking {
  val body="Synthetic retained thought";val lease=notes.beginEditor(null)
  notes.persistEditor(notes.editorWrite(lease,body))
  repeat(2) {pick(lease)}
  val staged=notes.dao.draftImageSnapshots("capture")
  val missing=File(staged.last().localPath);assertTrue(missing.delete())
  assertTrue(runCatching {notes.capture(notes.editorWrite(lease,body))}.isFailure)
  assertTrue(notes.dao.all().isEmpty());assertTrue(notes.dao.outbox().isEmpty());assertTrue(notes.dao.attachmentSnapshots().isEmpty())
  assertEquals(body,notes.dao.draftSnapshot("capture")?.body);assertTrue(File(staged.first().localPath).isFile)
  assertEquals(staged,notes.dao.draftImageSnapshots("capture"))
  missing.writeBytes(byteArrayOf(4))
  val id=notes.capture(notes.editorWrite(lease,body))
  assertEquals(1,notes.dao.all().size);assertEquals(2,notes.dao.attachments(id).first().size)
 }

 @Test fun acknowledgementPreservesNewerDraftWordsAndRejectsDelayedDraftWrite()=runBlocking {
  val lease=notes.beginEditor(null)
  val older=notes.editorWrite(lease,"Old delayed draft")
  val submitted=notes.editorWrite(lease,"Submitted words")
  pick(lease)
  notes.persistEditor(notes.editorWrite(lease,"Newer unsaved words"))
  val id=notes.capture(submitted)
  assertFalse(notes.persistEditor(older))
  assertTrue(notes.dao.get(id)!!.body.startsWith("Submitted words"))
  assertEquals("Newer unsaved words",notes.dao.draftSnapshot("capture")?.body)
  assertTrue(notes.dao.draftImageSnapshots("capture").isEmpty())
 }

 @Test fun removeAndRecoveryDoNotDeleteOtherDraftsOrTransferredAttachments()=runBlocking {
  val old=notes.beginEditor(null,"draft-one")
  pick(old)
  val current=notes.beginEditor(null,"draft-one")
  val other=notes.beginEditor(null,"draft-two")
  pick(other)
  val first=File(notes.dao.draftImageSnapshots("draft-one").single().localPath)
  val second=File(notes.dao.draftImageSnapshots("draft-two").single().localPath)
  assertTrue(runCatching {images.clear(old)}.isFailure)
  assertTrue(runCatching {pick(old)}.isFailure)
  assertTrue(first.isFile)
  assertEquals(2,directory.listFiles().orEmpty().size)
  val saved=notes.capture(notes.editorWrite(other,""))
  images.clear(current)
  val orphan=File(directory,"orphan.image").apply {writeText("unused")}
  val partial=File(directory,"old-process.partial").apply {writeText("unfinished")}
  images.recover()
  assertFalse(first.exists());assertFalse(orphan.exists());assertFalse(partial.exists())
  assertTrue(notes.dao.draftImageSnapshots("draft-one").isEmpty())
  assertEquals(second.absolutePath,notes.dao.attachments(saved).first().single().localPath)
  assertTrue(second.isFile)
 }

 @Test fun importBoundsBytesAndSelectionAndLeavesNoRejectedFiles()=runBlocking {
  val lease=notes.beginEditor(null)
  var bytesRead=0L
  val unlimited=object:InputStream() {
   override fun read():Int=error("Use a bounded buffer")
   override fun read(buffer:ByteArray,offset:Int,length:Int):Int {bytesRead+=length;return length}
  }
  val oversized=runCatching {images.add(lease,"image/png") {unlimited}}.exceptionOrNull() as LocalOperationException
  assertEquals(LocalOperationProblem.ImageTooLarge,oversized.problem)
  assertEquals(IMAGE_IMPORT_LIMIT+1,bytesRead)
  assertTrue(directory.listFiles().orEmpty().isEmpty())
  repeat(10) {pick(lease)}
  val tooMany=runCatching {pick(lease)}.exceptionOrNull() as LocalOperationException
  assertEquals(LocalOperationProblem.DraftImageLimit,tooMany.problem)
  assertEquals(10,notes.dao.draftImageSnapshots("capture").size)
  assertEquals(10,directory.listFiles().orEmpty().size)
 }

 @Test fun migrationSixToSevenRetainsExistingDraftText()=runBlocking {
  val text="Existing version six draft"
  notes.saveDraft("capture",text)
  db.close()
  SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use {legacy ->
   legacy.execSQL("DROP TABLE draft_images")
   legacy.version=6
  }
  open()
  val lease=notes.beginEditor(null)
  assertEquals(text,notes.openEditor(lease).first)
  assertTrue(images.observe("capture").first().isEmpty())
  pick(lease)
  assertEquals(7,db.openHelper.writableDatabase.version)
  assertEquals(1,images.observe("capture").first().size)
 }
}
