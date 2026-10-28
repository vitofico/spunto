package io.theficos.spunto.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class CaptureSaveTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private lateinit var db:SpuntoDatabase
 private lateinit var notes:NotesRepository
 private lateinit var directory:File
 private lateinit var databaseName:String
 private val context get()=ApplicationProvider.getApplicationContext<android.content.Context>()
 private fun open() {db=Room.databaseBuilder(context,SpuntoDatabase::class.java,databaseName).allowMainThreadQueries().build();notes=NotesRepository(db)}
 @Before fun setup() {databaseName="synthetic-capture-${java.util.UUID.randomUUID()}.db";directory=kotlin.io.path.createTempDirectory("synthetic-capture").toFile();open()}
 @After fun close() {db.close();context.deleteDatabase(databaseName);directory.deleteRecursively()}
 private fun image(name:String)=File(directory,name).apply {writeBytes(byteArrayOf(1,2,3))}.absolutePath to "image/png"
 @Test fun imageOnlyAndTextCapturesCommitAllMarkersOutboxAndExactDraftAcknowledgement()=runBlocking {
  for(body in listOf("","Synthetic thought")) {
   val key="capture:$body";notes.saveDraft(key,body)
   val selected=listOf(image("first-$body"),image("second-$body"))
   val id=notes.capture(body,key,selected,sourceRef="dictation")
   val row=notes.dao.get(id)!!;val attachments=notes.dao.attachments(id).first()
   assertEquals(2,attachments.size);assertTrue(attachments.all {it.marker in row.body && File(it.localPath).isFile})
   assertTrue(row.body.startsWith(body));assertEquals("dictation",row.sourceRef);assertNull(notes.dao.draftSnapshot(key))
   val pending=notes.dao.outbox().filter {it.noteId==id}.map {Json.decodeFromString<OutboxOperation>(it.payload)}
   assertEquals(1,pending.size);assertEquals("note.create",pending.single().type);assertEquals(row.body,pending.single().body)
   db.close();open()
   assertEquals(row,notes.dao.get(id));assertEquals(attachments,notes.dao.attachments(id).first())
   assertEquals(pending,notes.dao.outbox().filter {it.noteId==id}.map {Json.decodeFromString<OutboxOperation>(it.payload)})
   assertNull(notes.dao.draftSnapshot(key))
  }
 }
 @Test fun failedSecondImageRollsBackTheNoteAndFirstImageAndKeepsDraftForRetry()=runBlocking {
  val body="Synthetic retained thought";notes.saveDraft("capture",body)
  val first=image("valid");val missing=File(directory,"missing").absolutePath to "image/png"
  assertTrue(runCatching {notes.capture(body,"capture",listOf(first,missing))}.isFailure)
  assertTrue(notes.dao.all().isEmpty());assertTrue(notes.dao.outbox().isEmpty());assertTrue(notes.dao.attachmentSnapshots().isEmpty())
  assertEquals(body,notes.dao.draftSnapshot("capture")?.body);assertTrue(File(first.first).isFile)
  File(missing.first).writeBytes(byteArrayOf(4))
  val id=notes.capture(body,"capture",listOf(first,missing))
  assertEquals(1,notes.dao.all().size);assertEquals(2,notes.dao.attachments(id).first().size)
 }
 @Test fun acknowledgementPreservesDraftWordsTypedAfterTheSubmittedCapture()=runBlocking {
  notes.saveDraft("capture","Newer unsaved words")
  val id=notes.capture("Submitted words","capture",listOf(image("selected")))
  assertTrue(notes.dao.get(id)!!.body.startsWith("Submitted words"))
  assertEquals("Newer unsaved words",notes.dao.draftSnapshot("capture")?.body)
 }
}
